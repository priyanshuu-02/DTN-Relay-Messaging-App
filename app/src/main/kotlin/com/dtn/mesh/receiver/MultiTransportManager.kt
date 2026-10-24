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
 * Send priority for a peer: BLE (a link that is up now; fix 3) → Wi-Fi Direct → LoRa hub (peer is
 * the hub) → same hub (peer is another phone on our hub, bundle addressed to it) → internet relay
 * (peer is present there) → LoRa hub flood (last resort). No step waits for a BLE connect.
 */
@Singleton
class MultiTransportManager @Inject constructor(
    private val bleTransport: BleTransportAdapter,
    private val wifiDirectTransport: WifiDirectTransportAdapter,
    private val loRaHubTransport: LoRaHubTransportAdapter,
    private val internetTransport: InternetTransportAdapter,
    private val radios: RadioStatusMonitor,
    private val hubBeaconRegistry: HubBeaconRegistry,
) : MeshTransport {

    companion object {
        private const val TAG = "MultiTransport"
        private val ALL_PATHS: Set<SendPath> = SendPath.entries.toSet()
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

    override val meshPeers: StateFlow<Map<String, String>> = loRaHubTransport.meshPeers

    override val linkReady: Flow<NodeId> = bleTransport.linkReady

    /** Wi-Fi Direct groups (no invitations) need Android 10+; older phones show it unavailable. */
    val wifiDirectSupported: Boolean get() = WifiDirectTransportAdapter.SUPPORTED

    private val internetLinkInfo = combine(
        internetTransport.status, internetTransport.onlinePeers, internetTransport.diagnostics,
    ) { st, peers, diag ->
        val facts = buildList {
            if (diag.isNotBlank()) add(diag)
            add("Messages stay end-to-end encrypted: the relay only ever sees ciphertext.")
        }
        // The headline itself comes from [RelayStatus] on every surface; this is the extra line.
        if (st is RelayStatus.Connected) LinkInfo(
            summary = when (peers.size) {
                0 -> "No contacts online"
                1 -> "1 contact online"
                else -> "${peers.size} contacts online"
            },
            facts = facts,
            peers = peers.sorted().map { LinkPeer(it, role = "On the relay") },
        ) else LinkInfo(summary = "", facts = facts)
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
        // Internet availability is followed by the relay itself (InternetTransportAdapter watches the
        // default network: "Waiting for internet" without one, a fresh run when it returns or changes).
    }

    /** Relay status for diagnostics (endpoint, attempts, last error). */
    val relayDiagnostics: StateFlow<String> = internetTransport.diagnostics

    /** What the internet relay is doing right now: the one model every relay surface shows. */
    val relayStatus: StateFlow<RelayStatus> = internetTransport.status

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

    // ── Link pages: hubs heard over Bluetooth, joining one, rescans ─────

    /** LoRa hubs whose Bluetooth beacon the BLE scan hears, strongest first (Available hubs). */
    val hubBeacons: StateFlow<List<HubBeacon>> = hubBeaconRegistry.beacons

    /** The hub we're connected to (from its HELLO), null while not connected. */
    val connectedHubId: StateFlow<String?> = loRaHubTransport.hubId

    /** The exact hub Wi-Fi the hub link is asking for, null = any `DTN-HUB-*`. */
    val requestedHubSsid: StateFlow<String?> = loRaHubTransport.requestedSsid

    /** Available hubs › Join: the hub link is wanted and asks for exactly that hub's Wi-Fi. */
    suspend fun joinHub(hubId: String): Boolean {
        wanted.update { it + TransportKind.HUB }
        val ok = runCatching { loRaHubTransport.joinHub(hubId) }
            .onFailure { Log.e(TAG, "join hub threw", it) }
            .getOrDefault(false)
        Log.i(TAG, "Join hub ${hubLabel(hubId)}: ${if (ok) "requested" else "could not start"}")
        return ok
    }

    /** LoRa hub page › Retry scan: forget hubs gone quiet and, when not on a hub, ask for one again. */
    fun retryHubScan() {
        hubBeaconRegistry.prune(System.currentTimeMillis())
        if (TransportKind.HUB in wanted.value && !loRaHubTransport.isConnected) loRaHubTransport.rerequestNetwork()
    }

    /** Wi-Fi Direct page › Refresh scan: discovery now (only while not linked). @return whether it started. */
    fun rescanWifiDirect(): Boolean = wifiDirectTransport.rescan()

    /** Internet relay page › Retry now: a fresh run (DNS, first endpoint) instead of waiting out the back-off. */
    fun retryRelay() = internetTransport.retryNow()

    /** Last-scan times for the link pages (read on a timer; the scan paths don't publish them). */
    fun scanTimes(): LinkScanTimes = LinkScanTimes(
        bleLastResultMs = bleTransport.lastScanResultMs,
        wifiDirectLastDiscoveryMs = wifiDirectTransport.lastDiscoveryAtMs,
        wifiDirectIntervalMs = wifiDirectTransport.discoveryIntervalMs,
        hubLastBeaconMs = hubBeaconRegistry.lastHeardMs,
        hubLastJoinRequestMs = loRaHubTransport.lastJoinRequestMs,
    )

    /** Relay Configuration › Test connection: a throwaway client against [url] (off the main thread). */
    suspend fun testRelay(url: String): RelayTestResult = internetTransport.testConnection(url)

    /** Fan a broadcast / group / announce bundle out over the relay (no-op when it's down). */
    fun publishBroadcastToInternet(message: DtnMessage) = internetTransport.publishBroadcast(message)

    // ── Send ─────────────────────────────────────────────────────────────

    override fun reachesDirectly(peer: NodeId): Boolean = directLinks(peer).isNotEmpty()

    override fun directLinks(peer: NodeId): List<SendPath> = buildList {
        if (bleTransport.isConnected && bleTransport.isPeerReachable(peer)) add(SendPath.BLE)
        if (wifiDirectTransport.isConnected && wifiDirectTransport.isPeerReachable(peer)) add(SendPath.WIFI_DIRECT)
        if (isHub(peer)) add(SendPath.HUB)
    }

    override fun isHub(peer: NodeId): Boolean = loRaHubTransport.isConnected && loRaHubTransport.isHub(peer)

    override fun isHubPeer(peer: NodeId): Boolean = loRaHubTransport.isHubPeer(peer)

    override fun isRadioReachable(peer: NodeId): Boolean = reachesDirectly(peer) || isHubPeer(peer)

    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? =
        sendMessageVia(message, targetPeer)?.packetId

    override suspend fun sendMessageVia(message: DtnMessage, targetPeer: NodeId): SendOutcome? =
        sendMessageVia(message, targetPeer, ALL_PATHS)

    override suspend fun sendMessageVia(message: DtnMessage, targetPeer: NodeId, only: Set<SendPath>): SendOutcome? {
        // 1–2. BLE — primary P2P, no airtime cost, coexists with hub WiFi — then Wi-Fi Direct. Only a
        //      BLE link that is up NOW counts ([DirectLinkChoice], fix 3): without one, Wi-Fi Direct
        //      (when the peer is in our group) gets the bundle at once while the BLE link comes up in
        //      the background, and its readiness triggers a flush. Nothing here waits for a connect.
        //      Wi-Fi Direct is also the next try, at once, when a BLE write fails.
        val bleInRange = bleTransport.isConnected && bleTransport.isPeerReachable(targetPeer)
        val direct = DirectLinkChoice.plan(
            only = only,
            bleInRange = bleInRange,
            bleReady = bleInRange && bleTransport.hasReadyLink(targetPeer),
            wifiDirectLinked = wifiDirectTransport.isConnected && wifiDirectTransport.isPeerReachable(targetPeer),
        )
        if (direct.connectBle) bleTransport.requestLink(targetPeer)
        for (path in direct.order) {
            val packetId = when (path) {
                SendPath.BLE -> bleTransport.sendMessage(message, targetPeer)
                SendPath.WIFI_DIRECT -> wifiDirectTransport.sendMessage(message, targetPeer)
                else -> null
            }
            if (packetId != null) {
                Log.d(TAG, "Sent via ${path.label} to ${targetPeer.value}")
                return SendOutcome(packetId, path)
            }
        }

        // 3. LoRa hub — the target is the hub itself.
        if (SendPath.HUB in only && loRaHubTransport.isConnected && loRaHubTransport.isHub(targetPeer)) {
            loRaHubTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Handed to LoRa hub for ${targetPeer.value}"); return SendOutcome(it, SendPath.HUB)
            }
        }

        // 4. Same hub — the target is another phone registered at our hub. The hub routes on the
        //    bundle's destination, so this only applies to bundles addressed to that phone: the
        //    hub passes them straight on (no LoRa airtime), but it's two hops, so delivery still
        //    waits for the end-to-end receipt.
        if (SendPath.HUB_LOCAL in only && message.destinationNodeId == targetPeer && loRaHubTransport.isHubPeer(targetPeer)) {
            loRaHubTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Handed to LoRa hub for ${targetPeer.value} (same hub)"); return SendOutcome(it, SendPath.HUB_LOCAL)
            }
        }

        // 5. Internet relay — the target is present on the relay and no local radio reaches it.
        //    Direct, reliable and free of LoRa airtime, so it beats flooding the backbone.
        if (SendPath.INTERNET in only && internetTransport.isConnected && internetTransport.isPeerOnline(targetPeer)) {
            internetTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Sent via internet relay to ${targetPeer.value}"); return SendOutcome(it, SendPath.INTERNET)
            }
        }

        // 6. LoRa hub flood — last resort. The hub floods the backbone and delivers to whichever
        //    phone registered with that node id (critical for receipts to remote phones).
        if (SendPath.HUB_FLOOD in only && loRaHubTransport.isConnected) {
            loRaHubTransport.sendMessage(message, targetPeer)?.let {
                Log.d(TAG, "Relayed via LoRa hub to ${targetPeer.value}"); return SendOutcome(it, SendPath.HUB_FLOOD)
            }
        }

        Log.w(TAG, "No transport available for ${targetPeer.value}" + if (only.size < ALL_PATHS.size) " over $only" else "")
        return null
    }
}
