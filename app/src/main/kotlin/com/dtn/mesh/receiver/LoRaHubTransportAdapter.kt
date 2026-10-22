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
 *
 * ## Auto-join
 * On API 29+ we request the hub network via [WifiNetworkSpecifier] (SSID prefix "DTN-HUB-") and
 * bind our socket to it, so the phone can attach without leaving the app and without losing its
 * cellular data default. On older APIs we assume the user has joined the hub WiFi manually and
 * connect on the active network.
 */
@Singleton
class LoRaHubTransportAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: LocalNodeIdentity,
) : DtnTransport {

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

        private const val CONNECT_TIMEOUT_MS = 8_000
        /** A hub reboot takes ~6 s before its Wi-Fi is back; asking sooner ends in "No devices found". */
        private const val REREQUEST_DELAY_MS = 8_000L
        /** Well inside the hub's 120 s idle timeout. */
        private const val PING_INTERVAL_MS = 30_000L

        /** Node ids in a ROSTER payload (`[1B n][n × 4B]`), or null when malformed. Pure (tested). */
        fun parseRoster(payload: ByteArray): List<NodeId>? {
            if (payload.isEmpty()) return null
            val n = payload[0].toInt() and 0xFF
            if (payload.size < 1 + n * 4) return null
            return (0 until n).map { i ->
                val o = 1 + i * 4
                NodeId.fromNodeNum(
                    ((payload[o].toInt() and 0xFF) shl 24) or ((payload[o + 1].toInt() and 0xFF) shl 16) or
                        ((payload[o + 2].toInt() and 0xFF) shl 8) or (payload[o + 3].toInt() and 0xFF),
                )
            }
        }

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

    private val _hubId = MutableStateFlow<String?>(null)
    /** The connected hub's node id (null while not connected). */
    val hubId: StateFlow<String?> = _hubId.asStateFlow()

    private val _hubPeers = MutableStateFlow<Set<String>>(emptySet())
    /** Other phones registered at our hub right now (from its ROSTER; never ourselves). */
    val hubPeers: StateFlow<Set<String>> = _hubPeers.asStateFlow()

    /** Whether [peer] is another phone on our hub (reachable through it right now). */
    fun isHubPeer(peer: NodeId): Boolean = isConnected && peer.value in _hubPeers.value

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

    /** Hub + the phones on it, for the link details sheet. */
    val linkInfo: StateFlow<LinkInfo> = combine(_connectionState, _hubId, _hubPeers) { st, hub, peers ->
        when {
            st == MeshConnectionState.CONNECTED && hub != null -> LinkInfo(
                summary = "Connected to hub $hub · " + when (peers.size) {
                    0 -> "no other phones on it"
                    1 -> "1 other phone on it"
                    else -> "${peers.size} other phones on it"
                },
                facts = listOf(
                    "Phones on this hub reach each other through it; the hub also floods messages " +
                        "over LoRa to other hubs.",
                ),
                peers = peers.sorted().map { LinkPeer(it, role = "On this hub") },
            )
            st == MeshConnectionState.CONNECTED -> LinkInfo(summary = "Connected — waiting for the hub to answer")
            st == MeshConnectionState.CONNECTING -> LinkInfo(summary = "Joining the hub's Wi-Fi (DTN-HUB-…)")
            else -> LinkInfo(summary = "Not connected")
        }
    }.stateIn(scope, SharingStarted.Eagerly, LinkInfo())

    override val isConnected: Boolean
        get() = _connectionState.value == MeshConnectionState.CONNECTED

    override val localNodeId: NodeId get() = identity.nodeId

    /** Whether the given peer is the currently-connected hub. */
    fun isHub(peerId: NodeId): Boolean = peerId == hubNodeId

    // ── Lifecycle ────────────────────────────────────────────────────────

    override suspend fun connect(): Boolean {
        if (isConnected) return true
        wantConnection = true
        _connectionState.value = MeshConnectionState.CONNECTING

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // A previous request may have been declined in the system dialog or timed out with
                // no hub in range. Drop it before asking again, otherwise every "tap to retry"
                // would stack another pending request and callback.
                networkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
                networkCallback = null
                boundNetwork = null
                requestHubNetwork() // learns the hub's WiFi Network via the callback
            }
            // One supervised reconnect loop serves both the API-29 specifier path and the legacy
            // manual-join path, retrying with exponential backoff whenever the socket drops.
            if (connectionJob?.isActive != true) {
                connectionJob = scope.launch { hubConnectionLoop() }
            }
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "LoRa Hub permission missing: ${e.message}")
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "LoRa Hub startup failed: ${e.message}", e)
            _connectionState.value = MeshConnectionState.DISCONNECTED
            disconnect()
            return false
        }
    }

    override fun disconnect() {
        wantConnection = false
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
            _nodeEvents.tryEmit(NodeEncounterEvent(nodeId = NodeId(id), rssi = -200, snr = 0f, timestampMs = ts, isOnline = true))
        }
        (before - now).forEach { id ->
            _nodeEvents.tryEmit(NodeEncounterEvent(nodeId = NodeId(id), rssi = 0, snr = 0f, timestampMs = ts, isOnline = false))
        }
        Log.i(TAG, "Hub roster: ${now.size} other phone(s) ${now.joinToString { it.takeLast(8) }}")
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
                // The hub drops phones that stay silent for 2 minutes; keep the link alive.
                runCatching { pingJob?.cancel() }
                pingJob = scope.launch {
                    while (true) {
                        kotlinx.coroutines.delay(PING_INTERVAL_MS)
                        runCatching { writeFrame(FRAME_PING, byteArrayOf(0)) }.onFailure { return@launch }
                    }
                }
                readLoop(DataInputStream(sock.getInputStream())) // blocks until the socket drops
            } catch (e: Exception) {
                Log.w(TAG, "Hub connect/read failed: ${e.message}")
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
        }.onFailure { Log.w(TAG, "hub Wi-Fi re-request failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun requestHubNetwork() {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(HUB_SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            .setWpa2Passphrase(HUB_PASSPHRASE)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Hub WiFi available")
                boundNetwork = network // the reconnect loop picks this up and (re)connects
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Hub WiFi lost")
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
                    emitHubEncounter(hub, online = true)
                }
                FRAME_ROSTER -> parseRoster(payload)?.let { updateRoster(it) }
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
        ))
    }

    private fun int32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun readInt32(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
}
