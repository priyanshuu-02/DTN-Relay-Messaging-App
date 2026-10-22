package com.dtn.mesh.receiver

import android.util.Log
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates every [DtnTransport] into one unified [MeshTransport] for the [DtnOrchestrator].
 *
 * Transports (independent of Meshtastic):
 * - [BleTransportAdapter]: phone-to-phone, primary P2P (coexists with a busy WiFi STA).
 * - [WifiDirectTransportAdapter]: phone-to-phone, higher-throughput fallback.
 * - [LoRaHubTransportAdapter]: phone ⇄ ESP32 hub over WiFi; the hub bridges the LoRa backbone.
 * - [InternetTransportAdapter]: optional MQTT relay for peers present on the internet.
 *
 * Each transport is controlled individually ([connect] / [disconnect] with a [TransportKind]) and
 * tracked in a "wanted" set. [transportStatus] and the aggregate [connectionState] are derived
 * reactively from every transport's own state flow, so a hub that joins (or a BLE stack that
 * dies) a minute after Connect is reflected immediately.
 *
 * Send priority for a peer: BLE → Wi-Fi Direct → LoRa hub (peer is the hub) → same hub (peer is
 * another phone on our hub, bundle addressed to it) → internet relay (peer is present there) →
 * LoRa hub flood (last resort).
 */
@Singleton
class MultiTransportManager @Inject constructor(
    private val bleTransport: BleTransportAdapter,
    private val wifiDirectTransport: WifiDirectTransportAdapter,
    private val loRaHubTransport: LoRaHubTransportAdapter,
    private val internetTransport: InternetTransportAdapter,
    private val radios: RadioStatusMonitor,
) : MeshTransport {

    companion object {
        private const val TAG = "MultiTransport"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Local radio transports (send-priority chain). */
    private val radioTransports: List<DtnTransport> =
        listOf(bleTransport, wifiDirectTransport, loRaHubTransport)

    /** Every transport whose inbound streams we observe. */
    private val inboundSources: List<DtnTransport> = radioTransports + internetTransport

    private fun transportFor(kind: TransportKind): DtnTransport = when (kind) {
        TransportKind.BLE -> bleTransport
        TransportKind.WIFI_DIRECT -> wifiDirectTransport
        TransportKind.HUB -> loRaHubTransport
        TransportKind.INTERNET -> internetTransport
    }

    // ── Merged Flows ─────────────────────────────────────────────────────

    override val inboundMessages: Flow<InboundPacket> =
        inboundSources.map { it.inboundMessages }.merge()

    override val nodeEvents: Flow<NodeEncounterEvent> =
        inboundSources.map { it.nodeEvents }.merge()

    override val deliveryStatus: Flow<DeliveryStatusEvent> =
        inboundSources.map { it.deliveryStatus }.merge()

    private val wanted = MutableStateFlow<Set<TransportKind>>(emptySet())

    override val transportStatus: StateFlow<TransportStatus> = combine(
        bleTransport.connectionState,
        wifiDirectTransport.connectionState,
        loRaHubTransport.connectionState,
        internetTransport.connectionState,
        wanted,
    ) { ble, wfd, hub, inet, want -> TransportStatus(ble, wfd, hub, inet, want) }
        .stateIn(scope, SharingStarted.Eagerly, TransportStatus())

    override val connectionState: StateFlow<MeshConnectionState> = transportStatus
        .map { it.aggregate }
        .stateIn(scope, SharingStarted.Eagerly, MeshConnectionState.DISCONNECTED)

    override val internetPeers: StateFlow<Set<String>> = internetTransport.onlinePeers

    override val hubPeers: StateFlow<Set<String>> = loRaHubTransport.hubPeers

    /** Wi-Fi Direct groups (no invitations) need Android 10+; older phones show it unavailable. */
    val wifiDirectSupported: Boolean get() = WifiDirectTransportAdapter.SUPPORTED

    private val internetLinkInfo = combine(
        internetTransport.connectionState, internetTransport.onlinePeers, internetTransport.diagnostics,
    ) { st, peers, diag ->
        val facts = buildList {
            if (diag.isNotBlank()) add(diag)
            add("Messages stay end-to-end encrypted: the relay only ever sees ciphertext.")
        }
        when (st) {
            MeshConnectionState.CONNECTED -> LinkInfo(
                summary = "Connected to the relay · " + when (peers.size) {
                    0 -> "no contacts online"
                    1 -> "1 contact online"
                    else -> "${peers.size} contacts online"
                },
                facts = facts,
                peers = peers.sorted().map { LinkPeer(it, role = "On the relay") },
            )
            MeshConnectionState.CONNECTING -> LinkInfo(summary = "Connecting to the relay…", facts = facts)
            else -> LinkInfo(summary = "Off", facts = facts)
        }
    }

    /** What every link is doing and who is on it, for the link details sheet (tap a chip). */
    val linkInfo: StateFlow<Map<TransportKind, LinkInfo>> = combine(
        bleTransport.linkInfo, wifiDirectTransport.linkInfo, loRaHubTransport.linkInfo, internetLinkInfo,
    ) { ble, wfd, hub, inet ->
        mapOf(TransportKind.BLE to ble, TransportKind.WIFI_DIRECT to wfd, TransportKind.HUB to hub, TransportKind.INTERNET to inet)
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override val isConnected: Boolean
        get() = inboundSources.any { it.isConnected }

    override val localNodeId: NodeId?
        get() = bleTransport.localNodeId

    init {
        // Follow the Bluetooth radio: if BLE is wanted, stop it when Bluetooth turns off (so no
        // peer is shown reachable over a dead radio) and bring it back when Bluetooth returns.
        scope.launch {
            radios.radios.map { it.bluetoothOn }.distinctUntilChanged().drop(1).collect { on ->
                if (TransportKind.BLE !in wanted.value) return@collect
                if (on) {
                    Log.i(TAG, "Bluetooth back on — restarting BLE")
                    bleTransport.connect()
                } else {
                    Log.i(TAG, "Bluetooth turned off — stopping BLE")
                    bleTransport.disconnect()
                }
            }
        }
        // Follow Wi-Fi: when it comes back, re-ask for the hub network (a specifier request isn't
        // re-satisfied by itself) and restart Wi-Fi Direct if it never started.
        scope.launch {
            radios.radios.map { it.wifiOn }.distinctUntilChanged().drop(1).collect { on ->
                if (!on) return@collect
                kotlinx.coroutines.delay(1_500L) // let the Wi-Fi stack settle
                if (TransportKind.HUB in wanted.value && !loRaHubTransport.isConnected) {
                    Log.i(TAG, "Wi-Fi back on — re-requesting the hub network")
                    loRaHubTransport.rerequestNetwork()
                }
                if (TransportKind.WIFI_DIRECT in wanted.value &&
                    wifiDirectTransport.connectionState.value == MeshConnectionState.DISCONNECTED
                ) {
                    Log.i(TAG, "Wi-Fi back on — restarting Wi-Fi Direct")
                    runCatching { wifiDirectTransport.connect() }
                }
            }
        }
        // Follow internet availability: when it returns, retry the relay now rather than waiting
        // out its reconnect back-off.
        scope.launch {
            radios.radios.map { it.internetAvailable }.distinctUntilChanged().drop(1).collect { available ->
                if (available && TransportKind.INTERNET in wanted.value) internetTransport.restartIfStalled()
            }
        }
    }

    /** Relay status for diagnostics (endpoint, attempts, last error). */
    val relayDiagnostics: StateFlow<String> = internetTransport.diagnostics

    // ── Lifecycle ────────────────────────────────────────────────────────

    /** MeshTransport contract: bring up the local radios. */
    override suspend fun connect(): Boolean = connectAll(includeInternet = false)

    /** Connect BLE, Wi-Fi Direct and the hub (and the relay when [includeInternet]). */
    suspend fun connectAll(includeInternet: Boolean): Boolean {
        val kinds = buildList {
            add(TransportKind.BLE); add(TransportKind.WIFI_DIRECT); add(TransportKind.HUB)
            if (includeInternet) add(TransportKind.INTERNET)
        }
        var any = false
        for (k in kinds) if (connect(k)) any = true
        return any
    }

    /**
     * Mark [kind] wanted and start it. A transport that can't start right now (e.g. BLE with
     * Bluetooth off) stays wanted, so it starts automatically once its radio comes back.
     */
    suspend fun connect(kind: TransportKind): Boolean {
        wanted.update { it + kind }
        val ok = runCatching { transportFor(kind).connect() }
            .onFailure { Log.e(TAG, "$kind connect threw", it) }
            .getOrDefault(false)
        Log.i(TAG, "${transportFor(kind).transportName} ${if (ok) "started" else "could not start"}")
        return ok
    }

    fun disconnect(kind: TransportKind) {
        wanted.update { it - kind }
        runCatching { transportFor(kind).disconnect() }
            .onFailure { Log.w(TAG, "$kind disconnect threw", it) }
    }

    override fun disconnect() {
        TransportKind.entries.forEach { disconnect(it) }
    }

    /**
     * Aggressive on-demand liveness check triggered by the UI Refresh button: any BLE peer we
     * haven't heard from in the last 3 seconds is marked offline immediately.
     */
    fun forceStalenessCheck() {
        bleTransport.forceStalenessCheck()
    }

    /** Fan a broadcast / group / announce bundle out over the relay (no-op when it's down). */
    fun publishBroadcastToInternet(message: DtnMessage) = internetTransport.publishBroadcast(message)

    // ── Send ─────────────────────────────────────────────────────────────

    override fun reachesDirectly(peer: NodeId): Boolean =
        (bleTransport.isConnected && bleTransport.isPeerReachable(peer)) ||
            (wifiDirectTransport.isConnected && wifiDirectTransport.isPeerReachable(peer)) ||
            isHub(peer)

    override fun isHub(peer: NodeId): Boolean = loRaHubTransport.isConnected && loRaHubTransport.isHub(peer)

    override fun isHubPeer(peer: NodeId): Boolean = loRaHubTransport.isHubPeer(peer)

    override fun isRadioReachable(peer: NodeId): Boolean = reachesDirectly(peer) || isHubPeer(peer)

    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? =
        sendMessageVia(message, targetPeer)?.packetId

    override suspend fun sendMessageVia(message: DtnMessage, targetPeer: NodeId): SendOutcome? {
        // 1. BLE — primary P2P, no airtime cost, coexists with hub WiFi.
        if (bleTransport.isConnected && bleTransport.isPeerReachable(targetPeer)) {
            bleTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Sent via BLE to ${targetPeer.value}"); return SendOutcome(it, SendPath.BLE)
            }
        }

        // 2. WiFi Direct — higher throughput when the peer is a P2P neighbor.
        if (wifiDirectTransport.isConnected && wifiDirectTransport.isPeerReachable(targetPeer)) {
            wifiDirectTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Sent via WiFi Direct to ${targetPeer.value}"); return SendOutcome(it, SendPath.WIFI_DIRECT)
            }
        }

        // 3. LoRa hub — the target is the hub itself.
        if (loRaHubTransport.isConnected && loRaHubTransport.isHub(targetPeer)) {
            loRaHubTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Handed to LoRa hub for ${targetPeer.value}"); return SendOutcome(it, SendPath.HUB)
            }
        }

        // 4. Same hub — the target is another phone registered at our hub. The hub routes on the
        //    bundle's destination, so this only applies to bundles addressed to that phone: the
        //    hub passes them straight on (no LoRa airtime), but it's two hops, so delivery still
        //    waits for the end-to-end receipt.
        if (message.destinationNodeId == targetPeer && loRaHubTransport.isHubPeer(targetPeer)) {
            loRaHubTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Handed to LoRa hub for ${targetPeer.value} (same hub)"); return SendOutcome(it, SendPath.HUB_LOCAL)
            }
        }

        // 5. Internet relay — the target is present on the relay and no local radio reaches it.
        //    Direct, reliable and free of LoRa airtime, so it beats flooding the backbone.
        if (internetTransport.isConnected && internetTransport.isPeerOnline(targetPeer)) {
            internetTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Sent via internet relay to ${targetPeer.value}"); return SendOutcome(it, SendPath.INTERNET)
            }
        }

        // 6. LoRa hub flood — last resort. The hub floods the backbone and delivers to whichever
        //    phone registered with that node id (critical for receipts to remote phones).
        if (loRaHubTransport.isConnected) {
            loRaHubTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Relayed via LoRa hub to ${targetPeer.value}"); return SendOutcome(it, SendPath.HUB_FLOOD)
            }
        }

        Log.w(TAG, "No transport available for ${targetPeer.value}")
        return null
    }
}
