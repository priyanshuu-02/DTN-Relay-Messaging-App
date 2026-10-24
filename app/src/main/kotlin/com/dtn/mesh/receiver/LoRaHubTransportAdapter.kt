package com.dtn.mesh.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.util.Log
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.LocalNodeIdentity
import com.dtn.mesh.model.NodeId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/*
 * LoRa-hub transport: the phone joins a hub's WiFi SoftAP (as a STA) and exchanges DTN bundles
 * with the hub over a framed TCP connection. The hub (custom ESP32 firmware) bridges to the
 * long-range LoRa backbone. This replaces the old Meshtastic AIDL path.
 *
 * ## Framed TCP protocol (port 9740)
 * Frame = `[1B type][2B length BE][payload...]`
 * - HELLO    (0x03): hub → phone, 4B hub node id. Announces the hub so we can treat it as a peer.
 * - REGISTER (0x01): phone → hub, 4B our node id. Lets the hub route bundles back to us.
 * - BUNDLE   (0x02): either direction, a self-contained DTN bundle ([DtnWireCodec] wire bytes).
 * - PING     (0x04): phone → hub every [PING_INTERVAL_MS], so an idle phone isn't timed out.
 * - ROSTER   (0x05): hub → phone, `[1B n][n × 4B node id]` of every phone registered at the hub
 *   (firmware v3+). Those phones are reachable through the hub right now: they're reported as
 *   online peers ([hubPeers]) and DMs to them go straight through the hub.
 * - MESH_ROSTER (0x06): hub → phone, `[1B n][n × (4B phone id, 4B hub id)]` of the phones on the
 *   other hubs ours hears over LoRa (from their heartbeats). Shown as reachable over LoRa
 *   ([meshPeers]); not online peers — a DM to one still goes to our hub, which floods it.
 *
 * ## Auto-join
 * On API 29+ we request the hub network via [WifiNetworkSpecifier] and bind our socket to it, so
 * the phone can attach without leaving the app and without losing its cellular data default. The
 * request names the strongest hub whose Bluetooth beacon we hear ([HubBeaconRegistry], exact SSID
 * "DTN-HUB-XXXX" = the last 4 hex digits of its id), or the hub picked with [joinHub]; with no
 * beacon heard it asks for any "DTN-HUB-" network (SSID prefix), as before. On older APIs we
 * assume the user has joined the hub WiFi manually and connect on the active network.
 */
@Singleton
class LoRaHubTransportAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: LocalNodeIdentity,
    private val diagnostics: com.dtn.mesh.service.DiagnosticsLog,
    private val hubBeacons: HubBeaconRegistry,
) : DtnTransport {

    private fun diagInfo(text: String) = diagnostics.i(com.dtn.mesh.service.DiagSource.HUB, text)
    private fun diagWarn(text: String) = diagnostics.w(com.dtn.mesh.service.DiagSource.HUB, text)
    private fun diagError(text: String) = diagnostics.e(com.dtn.mesh.service.DiagSource.HUB, text)

    companion object {
        private const val TAG = "LoRaHubTransport"

        /** SoftAP SSID prefix every hub uses (e.g. "DTN-HUB-A"). */
        private const val HUB_SSID_PREFIX = "DTN-HUB-"
        /** Shared SoftAP passphrase (matches firmware AP_PASS). */
        private const val HUB_PASSPHRASE = "dtnmesh123"
        /** SoftAP gateway IP + DTN server port (matches firmware). */
        private const val HUB_IP = "192.168.4.1"
        private const val HUB_PORT = 9740

        private const val FRAME_REGISTER: Byte = 0x01
        private const val FRAME_BUNDLE: Byte = 0x02
        private const val FRAME_HELLO: Byte = 0x03
        private const val FRAME_PING: Byte = 0x04
        private const val FRAME_ROSTER: Byte = 0x05
        private const val FRAME_MESH_ROSTER: Byte = 0x06

        private const val CONNECT_TIMEOUT_MS = 8_000
        /** A hub reboot takes ~6 s before its Wi-Fi is back; asking sooner ends in "No devices found". */
        private const val REREQUEST_DELAY_MS = 8_000L
        /** Auto-join skips a hub whose Wi-Fi request just failed for this long (then it's tried again). */
        private const val FAILED_HUB_BACKOFF_MS = 60_000L
        /** Right after the BLE scan starts, auto-join waits up to this long for a hub beacon. */
        private const val BEACON_WAIT_MS = 3_000L
        /** Well inside the hub's 120 s idle timeout. */
        private const val PING_INTERVAL_MS = 30_000L

        /** Node ids in a ROSTER payload (`[1B n][n × 4B]`), or null when malformed. Pure (tested). */
        fun parseRoster(payload: ByteArray): List<NodeId>? {
            if (payload.isEmpty()) return null
            val n = payload[0].toInt() and 0xFF
            if (payload.size < 1 + n * 4) return null
            return (0 until n).map { i -> NodeId.fromNodeNum(be32(payload, 1 + i * 4)) }
        }

        /**
         * (phone, its hub) pairs in a MESH_ROSTER payload (`[1B n][n × (4B phone id, 4B hub id)]`),
         * or null when malformed. Pure (tested).
         */
        fun parseMeshRoster(payload: ByteArray): List<Pair<NodeId, NodeId>>? {
            if (payload.isEmpty()) return null
            val n = payload[0].toInt() and 0xFF
            if (payload.size < 1 + n * 8) return null
            return (0 until n).map { i ->
                val o = 1 + i * 8
                NodeId.fromNodeNum(be32(payload, o)) to NodeId.fromNodeNum(be32(payload, o + 4))
            }
        }

        private fun be32(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

        private var packetIdCounter = 3_000_000
    }

    override val transportName: String = "LoRa Hub (WiFi)"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendMutex = Mutex()

    /** Whether the user wants the hub link (survives drops; cleared by [disconnect]). */
    val isWanted: Boolean get() = wantConnection

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    @Volatile private var wantConnection = false
    private var connectionJob: kotlinx.coroutines.Job? = null

    /** The hub's node id, learned from its HELLO frame. Null until connected. */
    @Volatile private var hubNodeId: NodeId? = null
    private var pingJob: kotlinx.coroutines.Job? = null

    /** The hub picked with Join (Available hubs), kept until auto-join runs again or the link is switched off. */
    @Volatile private var pinnedHubId: String? = null
    /** Hub id → until when auto-join skips it (its Wi-Fi request failed). */
    private val failedHubs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The hub the current Wi-Fi request asks for (null = any DTN-HUB-* network). */
    @Volatile private var requestedHubId: String? = null

    private val _requestedSsid = MutableStateFlow<String?>(null)
    /** The exact hub Wi-Fi being asked for ("DTN-HUB-A9B0"), null = any `DTN-HUB-*` (no beacon heard). */
    val requestedSsid: StateFlow<String?> = _requestedSsid.asStateFlow()

    /** When the hub Wi-Fi was last asked for (0 = not yet), for the LoRa hub page. */
    @Volatile var lastJoinRequestMs: Long = 0L
        private set

    /** Auto-join waiting briefly for the first hub beacons right after Bluetooth started scanning. */
    @Volatile private var beaconWaitJob: kotlinx.coroutines.Job? = null

    private val _hubId = MutableStateFlow<String?>(null)
    /** The connected hub's node id (null while not connected). */
    val hubId: StateFlow<String?> = _hubId.asStateFlow()

    private val _hubPeers = MutableStateFlow<Set<String>>(emptySet())
    /** Other phones registered at our hub right now (from its ROSTER; never ourselves). */
    val hubPeers: StateFlow<Set<String>> = _hubPeers.asStateFlow()

    /** Whether [peer] is another phone on our hub (reachable through it right now). */
    fun isHubPeer(peer: NodeId): Boolean = isConnected && peer.value in _hubPeers.value

    private val _meshPeers = MutableStateFlow<Map<String, String>>(emptyMap())
    /**
     * Phones on the other hubs our hub hears over LoRa (phone id → that hub's id), from its
     * MESH_ROSTER. Never ourselves, our hub, or a phone on our own hub.
     */
    val meshPeers: StateFlow<Map<String, String>> = _meshPeers.asStateFlow()

    // ── Flows ────────────────────────────────────────────────────────────

    private val _inboundMessages = MutableSharedFlow<InboundPacket>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inboundMessages: Flow<InboundPacket> = _inboundMessages.asSharedFlow()

    private val _nodeEvents = MutableSharedFlow<NodeEncounterEvent>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val nodeEvents: Flow<NodeEncounterEvent> = _nodeEvents.asSharedFlow()

    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusEvent>(
        extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val deliveryStatus: Flow<DeliveryStatusEvent> = _deliveryStatus.asSharedFlow()

    private val _connectionState = MutableStateFlow(MeshConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    /** Hub + the phones on it (and on the hubs it hears over LoRa), for the link details sheet. */
    val linkInfo: StateFlow<LinkInfo> = combine(_connectionState, _hubId, _hubPeers, _meshPeers, _requestedSsid) { st, hub, peers, mesh, asked ->
        when {
            st == MeshConnectionState.CONNECTED && hub != null -> LinkInfo(
                summary = "Connected to hub $hub · " + when (peers.size) {
                    0 -> "no other phones on it"
                    1 -> "1 other phone on it"
                    else -> "${peers.size} other phones on it"
                } + if (mesh.isEmpty()) "" else " · ${mesh.size} on other hubs",
                facts = listOf(
                    "Phones on this hub reach each other through it; the hub also floods messages " +
                        "over LoRa to other hubs.",
                ),
                peers = peers.sorted().map { LinkPeer(it, role = "On this hub") } +
                    mesh.entries.sortedBy { it.key }.map { (phone, viaHub) ->
                        LinkPeer(phone, role = "On ${hubLabel(viaHub)} · over LoRa")
                    },
            )
            st == MeshConnectionState.CONNECTED -> LinkInfo(summary = "Connected — waiting for the hub to answer")
            st == MeshConnectionState.CONNECTING -> LinkInfo(summary = "Joining the hub's Wi-Fi (${asked ?: "DTN-HUB-…"})")
            else -> LinkInfo(summary = "Not connected")
        }
    }.stateIn(scope, SharingStarted.Eagerly, LinkInfo())

    override val isConnected: Boolean
        get() = _connectionState.value == MeshConnectionState.CONNECTED

    override val localNodeId: NodeId get() = identity.nodeId

    /** Whether the given peer is the currently-connected hub. */
    fun isHub(peerId: NodeId): Boolean = peerId == hubNodeId

    // ── Lifecycle ────────────────────────────────────────────────────────

    /** Auto-join (switch on, Connect, retry): the strongest hub heard, else any `DTN-HUB-*`. */
    override suspend fun connect(): Boolean {
        if (isConnected) return true
        pinnedHubId = null
        return start()
    }

    /**
     * Join one hub (Available hubs › Join): ask Android for exactly its Wi-Fi ("DTN-HUB-A9B0"),
     * leaving the hub we're on. It stays the choice until auto-join runs again ([connect]) or the
     * link is switched off. Before Android 10 the user joins hub Wi-Fi by hand, so this is [connect].
     */
    suspend fun joinHub(hubId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return connect()
        pinnedHubId = hubId
        if (isConnected && hubNodeId?.value == hubId) return true
        if (socket != null || boundNetwork != null) {
            diagInfo("Leaving ${hubNodeId?.let { hubLabel(it.value) } ?: "the current hub"} to join ${hubLabel(hubId)}")
            runCatching { socket?.close() }
            socket = null; output = null
            linkDown()
        }
        return start()
    }

    private fun start(): Boolean {
        wantConnection = true
        _connectionState.value = MeshConnectionState.CONNECTING

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // A previous request may have been declined in the system dialog or timed out with
                // no hub in range. Drop it before asking again, otherwise every "tap to retry"
                // would stack another pending request and callback.
                runCatching { beaconWaitJob?.cancel() }
                networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
                networkCallback = null
                boundNetwork = null
                val now = System.currentTimeMillis()
                val scanning = hubBeacons.scanStartedMs
                val waitMs = if (scanning > 0) scanning + BEACON_WAIT_MS - now else 0L
                if (pinnedHubId == null && waitMs > 0 && hubBeacons.strongest(now) == null) {
                    // Bluetooth only just started scanning (Connect starts both): give the hubs'
                    // ~1 s beacons a moment, so auto-join can ask for the strongest hub.
                    beaconWaitJob = scope.launch {
                        val until = System.currentTimeMillis() + waitMs
                        while (System.currentTimeMillis() < until && hubBeacons.strongest(System.currentTimeMillis()) == null) {
                            kotlinx.coroutines.delay(250L)
                        }
                        if (wantConnection && networkCallback == null && !isConnected) {
                            runCatching { requestHubNetwork() }.onFailure {
                                Log.w(TAG, "hub Wi-Fi request failed", it)
                                diagWarn("Hub Wi-Fi request failed: ${it.javaClass.simpleName}")
                            }
                        }
                    }
                } else {
                    requestHubNetwork() // learns the hub's WiFi Network via the callback
                }
            }
            // One supervised reconnect loop serves both the API-29 specifier path and the legacy
            // manual-join path, retrying with exponential backoff whenever the socket drops.
            if (connectionJob?.isActive != true) {
                connectionJob = scope.launch { hubConnectionLoop() }
            }
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "LoRa Hub permission missing: ${e.message}")
            diagError("Start failed: Wi-Fi permission missing")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "LoRa Hub startup failed: ${e.message}", e)
            diagError("Start failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        }
    }

    override fun disconnect() {
        wantConnection = false
        pinnedHubId = null
        _requestedSsid.value = null
        runCatching { beaconWaitJob?.cancel() }
        beaconWaitJob = null
        runCatching { connectionJob?.cancel() }
        connectionJob = null
        _connectionState.value = MeshConnectionState.DISCONNECTED
        runCatching { socket?.close() }
        socket = null
        output = null
        linkDown()
        networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        networkCallback = null
        boundNetwork = null
    }

    /** The hub link is gone: the hub and every phone we reached through it go offline. */
    private fun linkDown() {
        runCatching { pingJob?.cancel() }; pingJob = null
        updateRoster(emptyList())
        _meshPeers.value = emptyMap()
        hubNodeId?.let { emitHubEncounter(it, online = false) }
        hubNodeId = null
        _hubId.value = null
    }

    /** Apply a ROSTER: phones that joined the hub come online, phones that left go offline. */
    private fun updateRoster(nodes: List<NodeId>) {
        val me = identity.nodeId.value
        val now = nodes.map { it.value }.filter { it != me && it != hubNodeId?.value }.toSet()
        val before = _hubPeers.value
        if (now == before) return
        _hubPeers.value = now // update first: offline handling checks reachability
        val ts = System.currentTimeMillis()
        (now - before).forEach { id ->
            _nodeEvents.tryEmit(NodeEncounterEvent(nodeId = NodeId(id), rssi = -200, snr = 0f, timestampMs = ts,
                isOnline = true, transport = TransportKind.HUB))
        }
        (before - now).forEach { id ->
            _nodeEvents.tryEmit(NodeEncounterEvent(nodeId = NodeId(id), rssi = 0, snr = 0f, timestampMs = ts,
                isOnline = false, transport = TransportKind.HUB))
        }
        Log.i(TAG, "Hub roster: ${now.size} other phone(s) ${now.joinToString { it.takeLast(8) }}")
        diagInfo("Roster changed: ${now.size} other phone(s) on this hub" +
            if (now.isEmpty()) "" else " (${now.joinToString { it.takeLast(8) }})")
    }

    /** Apply a MESH_ROSTER: the phones on the other hubs ours hears (presence only, no encounters). */
    private fun updateMeshRoster(pairs: List<Pair<NodeId, NodeId>>) {
        val me = identity.nodeId.value
        val local = _hubPeers.value
        val now = pairs.map { (phone, hub) -> phone.value to hub.value }
            .filter { (phone, _) -> phone != me && phone != hubNodeId?.value && phone !in local }
            .toMap()
        if (now == _meshPeers.value) return
        _meshPeers.value = now
        Log.i(TAG, "Other hubs: ${now.size} phone(s) " +
            now.entries.joinToString { "${it.key.takeLast(8)}@${it.value.takeLast(8)}" })
        diagInfo("Other hubs changed: ${now.size} phone(s) over LoRa on ${now.values.toSet().size} hub(s)" +
            if (now.isEmpty()) "" else " (" + now.entries.joinToString { "${it.key.takeLast(8)}@${hubLabel(it.value)}" } + ")")
    }

    /**
     * Keeps a connection to the hub while [wantConnection] holds: (re)connects, registers, pumps
     * the read loop until the socket drops, then backs off and retries. Waits for [boundNetwork]
     * (set by the WiFi specifier callback) on API 29+; uses the active network on legacy.
     */
    private suspend fun hubConnectionLoop() {
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        var backoff = 1_000L
        while (wantConnection) {
            val net = boundNetwork
            if (net == null && !legacy) { kotlinx.coroutines.delay(1_000L); continue } // wait for hub WiFi
            try {
                val sock = (net?.socketFactory?.createSocket() ?: Socket()).also { it.tcpNoDelay = true }
                sock.connect(InetSocketAddress(HUB_IP, HUB_PORT), CONNECT_TIMEOUT_MS)
                socket = sock
                output = DataOutputStream(sock.getOutputStream())
                // Announce ourselves so the hub can route bundles back to us (+ replay buffered ones).
                writeFrameBlocking(FRAME_REGISTER, int32(identity.nodeNum32))
                _connectionState.value = MeshConnectionState.CONNECTED
                backoff = 1_000L
                Log.i(TAG, "Connected to hub at $HUB_IP:$HUB_PORT as ${identity.nodeId}")
                diagInfo("Connected to the hub at $HUB_IP:$HUB_PORT, registered as ${identity.nodeId.value.takeLast(8)}")
                // The hub drops phones that stay silent for 2 minutes; keep the link alive.
                runCatching { pingJob?.cancel() }
                pingJob = scope.launch {
                    while (true) {
                        kotlinx.coroutines.delay(PING_INTERVAL_MS)
                        runCatching { writeFrame(FRAME_PING, byteArrayOf(0)) }.onFailure {
                            diagWarn("Ping to the hub failed: ${it.javaClass.simpleName}")
                            return@launch
                        }
                    }
                }
                readLoop(DataInputStream(sock.getInputStream())) // blocks until the socket drops
                if (wantConnection) diagWarn("Hub link closed — reconnecting")
            } catch (e: Exception) {
                Log.w(TAG, "Hub connect/read failed: ${e.message}")
                if (wantConnection) diagWarn("Hub connect/read failed: ${e.javaClass.simpleName} ${e.message.orEmpty()} " +
                    "— retrying in ${backoff / 1000} s")
            }
            // Still trying (the loop retries below) — report CONNECTING, not "not joined". Only a
            // definitive "no hub / declined" from the system (onUnavailable) means DISCONNECTED,
            // and that verdict is kept until the user retries.
            val systemGaveUp = _connectionState.value == MeshConnectionState.DISCONNECTED && boundNetwork == null
            if (!systemGaveUp) {
                _connectionState.value = if (wantConnection) MeshConnectionState.CONNECTING else MeshConnectionState.DISCONNECTED
            }
            runCatching { socket?.close() }; socket = null; output = null
            linkDown()
            if (!wantConnection) break
            kotlinx.coroutines.delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    /**
     * Ask Android for the hub Wi-Fi again (after it was lost, e.g. Wi-Fi toggled or the hub
     * rebooted). A specifier request is not re-satisfied automatically once its network is gone;
     * Android remembers the user's earlier approval, so this normally reconnects without a dialog.
     */
    fun rerequestNetwork() {
        if (!wantConnection || isConnected || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
            networkCallback = null
            boundNetwork = null
            _connectionState.value = MeshConnectionState.CONNECTING
            requestHubNetwork()
            Log.i(TAG, "Re-requested hub Wi-Fi")
            diagInfo("Asked Android for the hub Wi-Fi again")
        }.onFailure {
            Log.w(TAG, "hub Wi-Fi re-request failed", it)
            diagWarn("Hub Wi-Fi re-request failed: ${it.javaClass.simpleName}")
        }
    }

    /**
     * The hub to ask for: the one picked with Join, else the strongest hub beacon heard (minus hubs
     * whose Wi-Fi just failed), else null = any `DTN-HUB-*` (no beacon heard: today's behaviour).
     */
    private fun chooseHub(): Pair<String, String>? {
        pinnedHubId?.let { return it to "picked with Join" }
        val now = System.currentTimeMillis()
        failedHubs.entries.removeAll { it.value < now }
        val best = hubBeacons.strongest(now, exclude = failedHubs.keys) ?: return null
        return best.hubId to "strongest hub heard, ${best.rssi} dBm"
    }

    @SuppressLint("MissingPermission")
    private fun requestHubNetwork() {
        val choice = chooseHub()
        val hubId = choice?.first
        val builder = WifiNetworkSpecifier.Builder()
        if (hubId != null) {
            builder.setSsid(HubBeacons.ssidFor(hubId))
        } else {
            builder.setSsidPattern(PatternMatcher(HUB_SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
        }
        val specifier = builder.setWpa2Passphrase(HUB_PASSPHRASE).build()
        requestedHubId = hubId
        _requestedSsid.value = hubId?.let { HubBeacons.ssidFor(it) }
        lastJoinRequestMs = System.currentTimeMillis()
        diagInfo(if (choice != null) "Asking Android for ${HubBeacons.ssidFor(choice.first)} (${choice.second})"
            else "Asking Android for any $HUB_SSID_PREFIX* network (no hub beacon heard)")

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Hub WiFi available")
                diagInfo("Hub Wi-Fi found and joined")
                boundNetwork = network // the reconnect loop picks this up and (re)connects
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Hub WiFi lost")
                diagWarn("Hub Wi-Fi lost")
                boundNetwork = null // the loop will wait until the hub WiFi returns
                _connectionState.value = if (wantConnection) MeshConnectionState.CONNECTING else MeshConnectionState.DISCONNECTED
                runCatching { socket?.close() }
                socket = null; output = null
                linkDown()
                // Give the radio a moment (Wi-Fi toggles, hub reboots), then ask again.
                scope.launch {
                    kotlinx.coroutines.delay(REREQUEST_DELAY_MS)
                    if (boundNetwork == null) rerequestNetwork()
                }
            }

            override fun onUnavailable() {
                // The system gave up: no hub in range, or the user declined the join dialog.
                Log.w(TAG, "Hub WiFi unavailable")
                val asked = requestedHubId
                diagWarn("Hub Wi-Fi unavailable: " + (asked?.let { "${HubBeacons.ssidFor(it)} not found" } ?: "no DTN-HUB-* network in range") +
                    ", or the join was declined")
                // Auto-join tries another hub (or any DTN-HUB-*) on the next retry; a hub picked
                // with Join stays picked.
                if (asked != null && pinnedHubId == null) failedHubs[asked] = System.currentTimeMillis() + FAILED_HUB_BACKOFF_MS
                _connectionState.value = MeshConnectionState.DISCONNECTED
            }
        }
        networkCallback = cb
        connectivityManager.requestNetwork(request, cb)
    }

    // ── Socket + read loop ───────────────────────────────────────────────

    private fun readLoop(input: DataInputStream) {
        while (socket?.isClosed == false) {
            val type = input.readByte()
            val len = (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            if (len < 0 || len > DtnMessage.LORA_MAX_FRAME_BYTES * 4) {
                Log.w(TAG, "Bad frame length $len, closing")
                diagWarn("Bad frame from the hub (length $len) — closing the link")
                break
            }
            val payload = ByteArray(len)
            input.readFully(payload)

            when (type) {
                FRAME_HELLO -> if (len >= 4) {
                    val hub = NodeId.fromNodeNum(readInt32(payload))
                    hubNodeId = hub
                    _hubId.value = hub.value
                    Log.i(TAG, "Hub HELLO: $hub")
                    diagInfo("Hub HELLO: hub id ${hub.value.takeLast(8)} (${hubLabel(hub.value)})")
                    emitHubEncounter(hub, online = true)
                }
                FRAME_ROSTER -> parseRoster(payload)?.let { updateRoster(it) }
                FRAME_MESH_ROSTER -> parseMeshRoster(payload)?.let { updateMeshRoster(it) }
                FRAME_BUNDLE -> {
                    val msg = DtnWireCodec.decode(payload, rssi = -40, snr = 15f) ?: continue
                    // The hub is our immediate previous hop — attribute it so the orchestrator's
                    // reverse-echo suppression and 1-hop routing-summary trust apply to hub traffic.
                    _inboundMessages.tryEmit(InboundPacket(message = msg, channel = msg.channel, viaPeer = hubNodeId, transport = TransportKind.HUB))
                }
                else -> Log.d(TAG, "Unknown frame type $type")
            }
        }
    }

    // ── Send ─────────────────────────────────────────────────────────────

    /**
     * Hand a bundle to the hub. [targetPeer] is expected to be the hub (see [isHub]); the hub
     * decides onward LoRa routing from the bundle's self-contained destination.
     *
     * Handing off over reliable TCP is treated as a successful delivery for local buffer
     * accounting (a hop-level ack, not an end-to-end one) — consistent with the WiFi Direct path.
     */
    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? {
        if (!isConnected) return null
        return try {
            val wire = DtnWireCodec.encode(message)
            writeFrame(FRAME_BUNDLE, wire)
            val pktId = packetIdCounter++
            _deliveryStatus.tryEmit(DeliveryStatusEvent(meshPacketId = pktId, status = DeliveryOutcome.DELIVERED))
            Log.d(TAG, "Handed ${wire.size}B bundle to hub (id=$pktId)")
            pktId
        } catch (e: Exception) {
            Log.e(TAG, "Send to hub failed", e)
            diagWarn("Send to the hub failed: ${e.javaClass.simpleName}")
            null
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private suspend fun writeFrame(type: Byte, payload: ByteArray) = sendMutex.withLock {
        writeFrameBlocking(type, payload)
    }

    private fun writeFrameBlocking(type: Byte, payload: ByteArray) {
        val out = output ?: throw IllegalStateException("Not connected")
        out.writeByte(type.toInt())
        out.writeByte((payload.size ushr 8) and 0xFF)
        out.writeByte(payload.size and 0xFF)
        out.write(payload)
        out.flush()
    }

    private fun emitHubEncounter(hub: NodeId, online: Boolean) {
        _nodeEvents.tryEmit(NodeEncounterEvent(
            nodeId = hub,
            rssi = -40,
            snr = 15f,
            timestampMs = System.currentTimeMillis(),
            isOnline = online,
            longName = "LoRa Hub",
            shortName = "HUB",
            transport = TransportKind.HUB,
        ))
    }

    private fun int32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun readInt32(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}
