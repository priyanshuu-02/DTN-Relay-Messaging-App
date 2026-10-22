package com.dtn.mesh.receiver

import android.util.Log
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.settings.SettingsRepository
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientTransportConfig
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional internet transport: bridges the DTN mesh to an MQTT broker so phones with internet can
 * exchange bundles when they are out of radio range. Bundles stay end-to-end encrypted (the broker
 * only ever sees ciphertext + the routing header), so a public broker is acceptable for testing.
 *
 * Topics (root `dtn/v1`):
 *  - `node/<hex>`     — bundles addressed to one node (DMs, receipts, per-peer catch-up pushes).
 *  - `bcast`          — broadcasts, group messages and announces, published once by the origin.
 *  - `presence/<hex>` — retained `on:<ts>` / `off:<ts>` records ([Presence]). Each client sets a
 *                       Last Will of `off`, publishes `on` after every (re)connect and refreshes it
 *                       on a heartbeat, so peers see who is reachable over the relay right now.
 *
 * ## Threading (why the app used to freeze on Disconnect)
 * HiveMQ's `publish…send()` is NOT always asynchronous: while the client is not connected it
 * blocks the calling thread inside `MqttPublishFlowables.add()` waiting for flow-control credit.
 * Disconnect called it from the UI thread with the relay mid-reconnect → main thread blocked →
 * ANR ("app crashes"). Now every publish runs on a dedicated single [publishDispatcher], only
 * while the client reports CONNECTED, inside [runInterruptible] + a timeout, so a stuck publish
 * is interrupted and can never block the UI or the orchestrator's send pipeline.
 *
 * ## Reachability (why the relay rarely linked)
 * The broker host is resolved here and tried address by address, **IPv4 before IPv6**, rotating
 * to the next candidate (and fallback port/transport, see [RelayEndpoints]) on each failed
 * attempt. Networks with black-holed IPv6 previously made every attempt time out.
 */
@Singleton
class InternetTransportAdapter @Inject constructor(
    private val identity: DtnIdentity,
    private val settings: SettingsRepository,
) : DtnTransport {

    override val transportName: String = "Internet (MQTT)"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The only thread that ever calls into HiveMQ publish APIs (see class doc). */
    private val publishDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dtn-relay-publish").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    @Volatile private var client: Mqtt5AsyncClient? = null
    @Volatile private var wanted = false
    private var heartbeatJob: Job? = null
    private var startJob: Job? = null
    private var teardownJob: Job? = null
    private val packetIds = AtomicInteger(5_000_000)

    /** Endpoints to try, in order; rotated on every failed attempt. */
    @Volatile private var candidates: List<RelayEndpoints.Candidate> = emptyList()
    private val candidateIndex = AtomicInteger(0)
    private val attempts = AtomicInteger(0)

    /**
     * Each client gets a generation number. Listener callbacks from a client that is no longer
     * the current one (stopped / restarted) shut that client down instead of acting on it, so an
     * old client can never keep reconnecting under our client id and fight the new one.
     */
    private val generation = AtomicInteger(0)
    private val sessions = ConcurrentHashMap<Int, Mqtt5AsyncClient>()

    private val _inboundMessages = MutableSharedFlow<InboundPacket>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inboundMessages: Flow<InboundPacket> = _inboundMessages.asSharedFlow()

    // Presence is deliberately NOT reported as encounters: an internet "contact" is not a physical
    // meeting and must not train PRoPHET / MaxProp / Q-learning. See [onlinePeers] instead.
    private val _nodeEvents = MutableSharedFlow<NodeEncounterEvent>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val nodeEvents: Flow<NodeEncounterEvent> = _nodeEvents.asSharedFlow()

    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusEvent>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val deliveryStatus: Flow<DeliveryStatusEvent> = _deliveryStatus.asSharedFlow()

    private val _connectionState = MutableStateFlow(MeshConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    override val isConnected: Boolean get() = _connectionState.value == MeshConnectionState.CONNECTED
    override val localNodeId: NodeId get() = identity.nodeId

    private val _diagnostics = MutableStateFlow("Relay off")

    /** Human-readable relay status: endpoint in use, attempt count, last error. */
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    /** Node id → local time (ms) we last heard an "online" presence record from it. */
    private val presenceHeard = ConcurrentHashMap<String, Long>()
    private val _onlinePeers = MutableStateFlow<Set<String>>(emptySet())

    /** Node ids ("!hex") currently present on the relay (excluding ourselves). */
    val onlinePeers: StateFlow<Set<String>> = _onlinePeers.asStateFlow()

    fun isPeerOnline(peer: NodeId): Boolean = peer.value in _onlinePeers.value

    private fun hex(num: Int): String = String.format("%08x", num)
    private fun nodeTopic(num: Int) = "$TOPIC_ROOT/node/${hex(num)}"
    private fun presenceTopic(num: Int) = "$PRESENCE_PREFIX${hex(num)}"
    private fun nowSec() = System.currentTimeMillis() / 1000L

    private fun clientConnected(c: Mqtt5AsyncClient?): Boolean =
        c != null && runCatching { c.state.isConnected }.getOrDefault(false)

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Start the relay. Never blocks the caller (DNS + client setup run on IO). Returns true once
     * the start is scheduled; [connectionState] reports CONNECTING until the broker accepts.
     */
    override suspend fun connect(): Boolean {
        wanted = true
        if (client != null || startJob?.isActive == true) return true
        _connectionState.value = MeshConnectionState.CONNECTING
        _diagnostics.value = "Starting…"
        startJob = scope.launch { startClient() }
        return true
    }

    /** Stop the relay. Returns immediately; the goodbye + DISCONNECT happen in the background. */
    override fun disconnect() {
        wanted = false
        generation.incrementAndGet() // invalidate the running client's callbacks
        startJob?.cancel(); startJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        val c = client
        client = null
        clearPresence()
        _connectionState.value = MeshConnectionState.DISCONNECTED
        _diagnostics.value = "Relay off"
        if (c != null) teardownJob = scope.launch { teardown(c, sayGoodbye = true) }
    }

    /**
     * Drop the current attempt and start over with fresh DNS — used when connectivity returns,
     * so we don't sit out the reconnect back-off after the network comes back.
     */
    fun restartIfStalled() {
        if (!wanted || isConnected || startJob?.isActive == true) return
        generation.incrementAndGet()
        val c = client
        client = null
        Log.i(TAG, "Network is back — restarting the relay connection")
        startJob = scope.launch {
            if (c != null) teardown(c, sayGoodbye = false)
            startClient()
        }
    }

    private suspend fun teardown(c: Mqtt5AsyncClient, sayGoodbye: Boolean) {
        if (sayGoodbye && clientConnected(c)) {
            // Best-effort explicit "off"; the DISCONNECT below also asks the broker to publish
            // our will, so either one clears our retained presence for everyone else.
            publishBounded(c, presenceTopic(identity.nodeNum32), Presence.encode(false, nowSec()), retain = true)
        }
        runCatching {
            withTimeoutOrNull(DISCONNECT_WAIT_MS) {
                c.disconnectWith().reasonCode(Mqtt5DisconnectReasonCode.DISCONNECT_WITH_WILL_MESSAGE).send().await()
            }
        }
        sessions.entries.removeAll { it.value === c }
    }

    private suspend fun startClient() {
        teardownJob?.join() // let a previous session say goodbye before taking over its client id
        if (!wanted) return
        val url = runCatching { settings.brokerUrlOnce() }.getOrDefault(SettingsRepository.DEFAULT_BROKER)
        candidates = RelayEndpoints.candidates(RelayEndpoints.specsFor(url)) { host ->
            InetAddress.getAllByName(host).toList()
        }
        candidateIndex.set(0)
        attempts.set(0)
        val first = candidates.firstOrNull() ?: run {
            _diagnostics.value = "No broker address"
            return
        }
        if (!wanted) return
        val gen = generation.incrementAndGet()
        val c = try {
            MqttClient.builder()
                .useMqttVersion5()
                // Stable per device: a reconnect takes over the old broker session, so the old
                // session's Last Will ("off") fires BEFORE our fresh "on" instead of overwriting it.
                .identifier("dtn-${hex(identity.nodeNum32)}")
                .transportConfig(transportFor(first))
                .automaticReconnect()
                .initialDelay(1, TimeUnit.SECONDS)
                .maxDelay(15, TimeUnit.SECONDS)
                .applyAutomaticReconnect()
                .addConnectedListener { onConnected(gen) }
                .addDisconnectedListener { ctx ->
                    if (!wanted || gen != generation.get()) {
                        // Stopped, or superseded by a newer client: never reconnect this one.
                        ctx.reconnector.reconnect(false)
                        sessions.remove(gen)
                        return@addDisconnectedListener
                    }
                    // Try the next endpoint (IPv4 first, then IPv6, then other ports).
                    val next = nextCandidate()
                    if (next != null) runCatching { ctx.reconnector.transportConfig(transportFor(next)) }
                    val n = attempts.incrementAndGet()
                    _diagnostics.value = "Attempt $n failed (${shortReason(ctx.cause)}) · next: ${next?.label ?: "—"}"
                    onDisconnected(ctx.cause)
                }
                .buildAsync()
        } catch (e: Exception) {
            Log.e(TAG, "MQTT client setup failed", e)
            _diagnostics.value = "Setup failed: ${e.message}"
            _connectionState.value = MeshConnectionState.DISCONNECTED
            return
        }
        // One global consumer (registered before connecting) instead of per-subscription
        // callbacks, so re-subscribing after every reconnect can't duplicate delivery.
        c.publishes(MqttGlobalPublishFilter.ALL) { pub -> if (gen == generation.get()) onPublish(pub) }
        sessions[gen] = c
        client = c
        _diagnostics.value = "Connecting · ${first.label}"
        Log.i(TAG, "Relay candidates: ${candidates.joinToString { it.label }}")
        runCatching {
            c.connectWith()
                .cleanStart(true)
                .keepAlive(KEEP_ALIVE_S)
                .willPublish()
                .topic(presenceTopic(identity.nodeNum32))
                .payload(Presence.encode(false, nowSec()))
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .applyWillPublish()
                .send()
            // No await: auto-reconnect owns retries; the connected listener flips the state.
        }.onFailure { Log.w(TAG, "MQTT connect could not be started", it) }
    }

    private fun nextCandidate(): RelayEndpoints.Candidate? {
        val list = candidates
        if (list.isEmpty()) return null
        return list[candidateIndex.incrementAndGet().mod(list.size)]
    }

    private fun currentCandidate(): RelayEndpoints.Candidate? {
        val list = candidates
        if (list.isEmpty()) return null
        return list[candidateIndex.get().mod(list.size)]
    }

    private fun transportFor(c: RelayEndpoints.Candidate): MqttClientTransportConfig {
        var b = MqttClientTransportConfig.builder()
        b = if (c.address != null) {
            // Keep the host name with the address so TLS still does SNI + host-name verification.
            b.serverAddress(InetSocketAddress(InetAddress.getByAddress(c.spec.host, c.address.address), c.spec.port))
        } else {
            b.serverHost(c.spec.host).serverPort(c.spec.port)
        }
        b = when (c.spec.scheme) {
            RelayEndpoints.Scheme.TCP -> b
            RelayEndpoints.Scheme.TLS -> b.sslWithDefaultConfig()
            RelayEndpoints.Scheme.WSS -> b.sslWithDefaultConfig()
                .webSocketConfig().serverPath(c.spec.path.removePrefix("/")).applyWebSocketConfig()
        }
        return b.socketConnectTimeout(SOCKET_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .mqttConnectTimeout(MQTT_CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private fun shortReason(t: Throwable?): String {
        var cause = t
        while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
        val name = cause?.javaClass?.simpleName ?: "closed"
        return cause?.message?.take(60)?.let { "$name: $it" } ?: name
    }

    private fun onConnected(gen: Int) {
        if (!wanted || gen != generation.get()) {
            // A stopped or superseded client got through: close it so it can't hold our id.
            sessions.remove(gen)?.let { stale -> scope.launch { teardown(stale, sayGoodbye = false) } }
            return
        }
        val c = client ?: return
        // cleanStart=true → every connect is a fresh broker session: subscribe again each time.
        // (Same filters, so the broker replaces rather than duplicates the subscriptions.)
        for (filter in listOf(nodeTopic(identity.nodeNum32), BCAST_TOPIC, "$PRESENCE_PREFIX+")) {
            runCatching {
                c.subscribeWith().topicFilter(filter).qos(MqttQos.AT_LEAST_ONCE).send()
                    .whenComplete { _, ex -> if (ex != null) Log.w(TAG, "subscribe $filter failed", ex) }
            }
        }
        _connectionState.value = MeshConnectionState.CONNECTED
        _diagnostics.value = "Connected · ${currentCandidate()?.label ?: "broker"}"
        publishPresenceAsync()
        startHeartbeat()
        Log.i(TAG, "Connected to MQTT relay as ${identity.nodeId} via ${currentCandidate()?.label}")
    }

    private fun onDisconnected(cause: Throwable?) {
        _connectionState.value = if (wanted) MeshConnectionState.CONNECTING else MeshConnectionState.DISCONNECTED
        // We can no longer verify anyone's presence; it is re-learned from the retained records
        // the broker replays when we re-subscribe.
        clearPresence()
        if (wanted) Log.w(TAG, "MQTT relay not connected (${shortReason(cause)}) — retrying")
    }

    // ── Publishing (single thread, connected-only, bounded) ─────────────

    /**
     * Publish on [publishDispatcher], only while connected, giving up after [PUBLISH_WAIT_MS].
     * [runInterruptible] interrupts HiveMQ if `send()` blocks on flow control.
     * @return true when the broker acknowledged (QoS 1 PUBACK).
     */
    private suspend fun publishBounded(c: Mqtt5AsyncClient, topic: String, payload: ByteArray, retain: Boolean = false): Boolean =
        withTimeoutOrNull(PUBLISH_WAIT_MS) {
            if (!clientConnected(c)) return@withTimeoutOrNull false
            val future = runInterruptible(publishDispatcher) {
                c.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).retain(retain).payload(payload).send()
            }
            runCatching { future.await() }.isSuccess
        } ?: false

    private fun publishPresenceAsync() {
        val c = client ?: return
        scope.launch {
            if (!publishBounded(c, presenceTopic(identity.nodeNum32), Presence.encode(true, nowSec()), retain = true)) {
                Log.w(TAG, "presence publish not acknowledged")
            }
        }
    }

    /**
     * Refresh our presence every [HEARTBEAT_MS], and every [PRESENCE_SWEEP_MS] expire peers whose
     * heartbeat stopped for [PRESENCE_STALE_MS] (their will can be lost, or not fire for a while).
     */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            var lastBeat = System.currentTimeMillis()
            while (isActive && wanted) {
                delay(PRESENCE_SWEEP_MS)
                val now = System.currentTimeMillis()
                if (now - lastBeat >= HEARTBEAT_MS) {
                    lastBeat = now
                    if (isConnected) publishPresenceAsync()
                }
                val cutoff = now - PRESENCE_STALE_MS
                val gone = presenceHeard.filterValues { it < cutoff }.keys
                if (gone.isNotEmpty()) {
                    gone.forEach { presenceHeard.remove(it) }
                    Log.i(TAG, "Presence: ${gone.joinToString { it.takeLast(8) }} offline (no heartbeat for ${PRESENCE_STALE_MS / 1000}s)")
                    emitPresence()
                }
            }
        }
    }

    private fun clearPresence() {
        presenceHeard.clear()
        emitPresence()
    }

    private fun emitPresence() {
        val now = presenceHeard.keys.toSet()
        if (now != _onlinePeers.value) _onlinePeers.value = now
    }

    // ── Inbound ──────────────────────────────────────────────────────────

    private fun onPublish(pub: Mqtt5Publish) {
        runCatching {
            val topic = pub.topic.toString()
            val bytes = pub.payloadAsBytes
            if (topic.startsWith(PRESENCE_PREFIX)) onPresence(topic, bytes, retained = pub.isRetain) else onBundle(bytes)
        }.onFailure { Log.w(TAG, "bad relay message dropped", it) }
    }

    private fun onPresence(topic: String, payload: ByteArray, retained: Boolean) {
        val hexId = Presence.nodeHexFromTopic(topic, PRESENCE_PREFIX) ?: return
        val node = "!$hexId"
        if (node == identity.nodeId.value) return
        val status = Presence.decode(payload) ?: return
        val heard = Presence.heardAtMs(status, retained, System.currentTimeMillis(), PRESENCE_STALE_MS, CLOCK_SKEW_MS)
        val was = node in _onlinePeers.value
        if (heard != null) presenceHeard.merge(node, heard, ::maxOf) else presenceHeard.remove(node)
        emitPresence()
        val now = node in _onlinePeers.value
        if (was != now) {
            Log.i(TAG, "Presence: ${node.takeLast(8)} ${if (now) "online" else "offline"} " +
                "(${if (status.online) "on" else "off"}${if (retained) ", retained" else ""}, sent ${nowSec() - status.epochSec}s ago)")
        }
    }

    private fun onBundle(payload: ByteArray) {
        val framed = InternetFrames.unwrap(payload)
        val msg = DtnWireCodec.decode(framed.wire, rssi = 0, snr = 0f)
            ?: DtnWireCodec.decode(payload, rssi = 0, snr = 0f)
            ?: return
        val publisher = framed.publisherNum?.let { NodeId.fromNodeNum(it) }
        // No-Local isn't set, so the broker echoes our own publishes back — drop them.
        if (publisher == identity.nodeId) return
        if (publisher == null && msg.originNodeId == identity.nodeId) return
        // Routing summaries describe a 1-hop radio neighbour; the publisher prefix is not
        // authenticated, so a summary arriving over the relay can never be trusted.
        if (msg.messageType == DtnMessageType.ROUTING_SUMMARY) return
        _inboundMessages.tryEmit(InboundPacket(message = msg, channel = msg.channel, viaPeer = publisher, transport = TransportKind.INTERNET))
    }

    // ── Outbound ─────────────────────────────────────────────────────────

    /**
     * Publish a bundle to [targetPeer]'s node topic and wait (bounded) for the broker's PUBACK.
     * A PUBACK only means the broker accepted it — callers must still wait for the end-to-end
     * receipt before treating a DM as delivered.
     */
    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? {
        val c = client ?: return null
        if (!isConnected || !clientConnected(c)) return null
        return try {
            val payload = InternetFrames.wrap(identity.nodeNum32, DtnWireCodec.encode(message))
            if (!publishBounded(c, nodeTopic(targetPeer.toNodeNum32()), payload)) {
                Log.w(TAG, "relay publish to ${targetPeer.value.takeLast(8)} not acknowledged")
                null
            } else {
                packetIds.incrementAndGet().also {
                    _deliveryStatus.tryEmit(DeliveryStatusEvent(meshPacketId = it, status = DeliveryOutcome.DELIVERED))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "relay publish failed", e)
            null
        }
    }

    /**
     * Fan a broadcast / group / announce bundle out to every relay subscriber (origin only).
     * [onPublished] runs (off the main thread) once the broker acknowledged it.
     */
    fun publishBroadcast(message: DtnMessage, onPublished: (() -> Unit)? = null) {
        val c = client ?: return
        if (!isConnected) return
        scope.launch {
            val payload = runCatching { InternetFrames.wrap(identity.nodeNum32, DtnWireCodec.encode(message)) }.getOrNull()
                ?: return@launch
            if (publishBounded(c, BCAST_TOPIC, payload)) onPublished?.invoke()
            else Log.w(TAG, "relay broadcast publish not acknowledged")
        }
    }

    companion object {
        private const val TAG = "InternetTransport"
        private const val TOPIC_ROOT = "dtn/v1"
        private const val BCAST_TOPIC = "$TOPIC_ROOT/bcast"
        private const val PRESENCE_PREFIX = "$TOPIC_ROOT/presence/"
        private const val KEEP_ALIVE_S = 30
        private const val SOCKET_CONNECT_TIMEOUT_S = 7L
        private const val MQTT_CONNECT_TIMEOUT_S = 10L
        private const val PUBLISH_WAIT_MS = 8_000L
        private const val DISCONNECT_WAIT_MS = 3_000L
        /** Presence refresh period. */
        private const val HEARTBEAT_MS = 60_000L
        /** How often expired peers are swept out of [onlinePeers]. */
        private const val PRESENCE_SWEEP_MS = 15_000L
        /**
         * A peer not heard for two heartbeats (+ margin) is gone even if its will never arrived.
         * Used for retained records too, so a record left behind by a phone that vanished can't
         * make it look online to everyone who (re)subscribes for the next ten minutes.
         */
        private const val PRESENCE_STALE_MS = 2 * HEARTBEAT_MS + 30_000L
        /** Presence timestamps this far ahead of our clock are drift; further ahead is bogus. */
        private const val CLOCK_SKEW_MS = 5 * 60_000L
    }
}
