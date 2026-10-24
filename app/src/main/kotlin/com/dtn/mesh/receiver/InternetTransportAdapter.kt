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
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5DisconnectException
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * ## Connection supervisor (why the relay used to show a false "Connecting")
 * HiveMQ's automatic reconnect was opaque: the app only changed state in its callbacks, so when a
 * reconnect attempt never finished (seen: a WebSocket attempt silent for 28 min) the relay showed
 * "Connecting" forever, and a phone that moved from mobile data to Wi-Fi never restarted it. Now
 * [RelayLinkMachine] owns every decision and this class only carries them out: one attempt at a
 * time, each a fresh client with a hard [ATTEMPT_BUDGET_MS] (no automatic reconnect), its own
 * back-off and endpoint rotation, a 1 s tick that starts due retries and aborts attempts that ran
 * too long, and the default network watched through [RadioStatusMonitor] ("Waiting for internet"
 * while there is none, a fresh run within seconds when it returns or changes). All of it runs on
 * one control thread ([ctl]); DNS, sockets and publishes never do, and none ever runs on the main
 * thread. Every state change is one line in the diagnostics log (source `relay`).
 *
 * ## Threading (why the app used to freeze on Disconnect)
 * HiveMQ's `publish…send()` is NOT always asynchronous: while the client is not connected it
 * blocks the calling thread inside `MqttPublishFlowables.add()` waiting for flow-control credit.
 * Every publish therefore runs on a dedicated single [publishDispatcher], only while the client
 * reports CONNECTED, inside [runInterruptible] + a timeout. [connect] / [disconnect] / [retryNow]
 * only post to the control thread and return at once.
 *
 * ## Reachability (why the relay rarely linked)
 * The broker host is resolved here and tried address by address, **IPv4 before IPv6**, rotating
 * to the next candidate (and fallback port/transport, see [RelayEndpoints]) after each failed
 * attempt. A session that worked and then dropped is retried on the same endpoint first.
 */
@Singleton
class InternetTransportAdapter @Inject constructor(
    private val identity: DtnIdentity,
    private val settings: SettingsRepository,
    /** The app-wide diagnostics log (not [diagnostics], this relay's one-line status). */
    private val diagLog: com.dtn.mesh.service.DiagnosticsLog,
    private val radios: RadioStatusMonitor,
) : DtnTransport {

    private fun diagInfo(text: String) = diagLog.i(com.dtn.mesh.service.DiagSource.RELAY, text)
    private fun diagWarn(text: String) = diagLog.w(com.dtn.mesh.service.DiagSource.RELAY, text)

    override val transportName: String = "Internet (MQTT)"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The only thread that ever calls into HiveMQ publish APIs (see class doc). */
    private val publishDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dtn-relay-publish").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** The only thread that touches [machine] and the attempt / session bookkeeping below. */
    private val ctl = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dtn-relay-ctl").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val machine = radios.radios.value.let { RelayLinkMachine(internetUsable = it.internetAvailable, networkId = it.internetNetworkId) }

    /** One connection attempt: its id (the machine's), its client once built, and whether we gave up on it. */
    private class Attempt(val id: Int) {
        @Volatile var client: Mqtt5AsyncClient? = null
        @Volatile var abandoned = false
        var job: Job? = null
    }

    private var attempt: Attempt? = null // ctl only
    /** The connected client (null while not connected). Publishes read it from any thread. */
    @Volatile private var session: Mqtt5AsyncClient? = null
    @Volatile private var wanted = false
    private var shown: RelayStatus = RelayStatus.Off // ctl only
    private var tickerJob: Job? = null // ctl only
    private var heartbeatJob: Job? = null
    @Volatile private var teardownJob: Job? = null
    private val packetIds = AtomicInteger(5_000_000)
    /** Presence publishes in a row that got no PUBACK: two of them end the session (half-open TCP). */
    private val presenceTimeouts = AtomicInteger(0)

    /** Endpoints to try, in order (from the last DNS lookup). */
    @Volatile private var candidates: List<RelayEndpoints.Candidate> = emptyList()

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

    private val _status = MutableStateFlow<RelayStatus>(RelayStatus.Off)

    /** What the relay is doing right now ([RelayStatus]): the model every relay surface shows. */
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    private val _diagnostics = MutableStateFlow("Relay off")

    /** [status] as one line (endpoint, attempt, reason): for the link details and older readers. */
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
    private fun now() = System.currentTimeMillis()

    private fun clientConnected(c: Mqtt5AsyncClient?): Boolean =
        c != null && runCatching { c.state.isConnected }.getOrDefault(false)

    init {
        // The default network: "Waiting for internet" without one, a fresh run when it returns or
        // changes (mobile data → Wi-Fi). The hub's SoftAP is never the default network.
        scope.launch {
            radios.radios.map { it.internetAvailable to it.internetNetworkId }.distinctUntilChanged().collect { (usable, id) ->
                post { act(machine.onInternet(usable, id, now())) }
            }
        }
    }

    // ── Lifecycle (posts to the control thread; never blocks the caller) ──

    /** Start the relay. Returns at once; [status] follows what actually happens. */
    override suspend fun connect(): Boolean {
        wanted = true
        post {
            act(machine.start(now()))
            startTicker()
        }
        return true
    }

    /** Stop the relay. Returns at once; the goodbye + DISCONNECT happen in the background. */
    override fun disconnect() {
        wanted = false
        post { act(machine.stop()) }
    }

    /** Retry now with fresh DNS (the relay page's Retry, tapping a relay that gave up). */
    fun retryNow() {
        post { act(machine.retryNow(now())) }
    }

    private fun post(block: () -> Unit) {
        scope.launch(ctl) {
            runCatching(block).onFailure {
                Log.w(TAG, "relay control step failed", it)
                diagWarn("Control step failed: ${it.javaClass.simpleName}")
            }
        }
    }

    /** Publish the step's status, then carry out its actions in order. Control thread only. */
    private fun act(step: RelayLinkMachine.Step?) {
        if (step == null) return
        show(step.status)
        for (a in step.actions) when (a) {
            is RelayLinkMachine.Action.Connect -> startAttempt(a)
            RelayLinkMachine.Action.Abort -> abortAll()
            RelayLinkMachine.Action.Close -> closeAll()
        }
    }

    private fun show(s: RelayStatus) {
        val before = shown
        if (s == before) return
        shown = s
        val text = RelayStatusLog.describe(s, now())
        _diagnostics.value = text
        _status.value = s
        _connectionState.value = when (s) {
            is RelayStatus.Connected -> MeshConnectionState.CONNECTED
            is RelayStatus.Connecting, is RelayStatus.Retrying -> MeshConnectionState.CONNECTING
            else -> MeshConnectionState.DISCONNECTED
        }
        val line = "Relay ${RelayStatusLog.name(before)} → $text"
        Log.i(TAG, line)
        if (s is RelayStatus.Retrying || s is RelayStatus.GaveUp) diagWarn(line) else diagInfo(line)
    }

    /** Once a second while the relay is on: due retries, the attempt watchdog, a session that died silently. */
    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive && wanted) {
                delay(TICK_MS)
                post { tick() }
            }
        }
    }

    private fun tick() {
        val s = session
        if (machine.status is RelayStatus.Connected && !clientConnected(s)) {
            sessionLost(s, "the client is no longer connected")
            return
        }
        act(machine.onTick(now()))
    }

    // ── Attempts ─────────────────────────────────────────────────────────

    private fun startAttempt(a: RelayLinkMachine.Action.Connect) {
        abortAttempt()
        val at = Attempt(a.attemptId)
        attempt = at
        at.job = scope.launch { runAttempt(at, a.endpointIndex, a.resolve) }
    }

    /**
     * One attempt on the IO pool: DNS if asked, a fresh client, CONNECT. The whole attempt has
     * [ATTEMPT_BUDGET_MS], so it always reports before the machine's watchdog would.
     */
    private suspend fun runAttempt(at: Attempt, index: Int, resolve: Boolean) {
        val startedMs = now()
        fun left() = (startedMs + ATTEMPT_BUDGET_MS - now()).coerceAtLeast(1L)
        // Let a previous session say goodbye before taking over its client id (bounded).
        withTimeoutOrNull(TEARDOWN_WAIT_MS) { teardownJob?.join() }
        if (resolve || candidates.isEmpty()) {
            val url = runCatching { settings.brokerUrlOnce() }.getOrDefault(SettingsRepository.DEFAULT_BROKER)
            val specs = RelayEndpoints.specsFor(url)
            // getAllByName can't be interrupted: look it up beside us and stop waiting after DNS_TIMEOUT_MS.
            val lookup = scope.async {
                RelayEndpoints.candidates(specs) { host -> InetAddress.getAllByName(host).toList() }
            }
            val list = withTimeoutOrNull(minOf(DNS_TIMEOUT_MS, left())) { lookup.await() }
                ?: specs.map { RelayEndpoints.Candidate(it, null) } // DNS hung: let the client resolve
            candidates = list
            val labels = list.map { it.label }
            post { act(machine.onEndpoints(at.id, labels)) }
            diagInfo("Endpoints in order: ${labels.joinToString()}")
        }
        val list = candidates
        if (list.isEmpty()) {
            post { attemptFailed(at, "no broker address", fatal = true) }
            return
        }
        val candidate = list[index.coerceIn(0, list.lastIndex)]
        val client = try {
            buildClient(candidate)
        } catch (e: Exception) {
            Log.e(TAG, "MQTT client setup failed", e)
            post { attemptFailed(at, "setup failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim(), fatal = true) }
            return
        }
        at.client = client
        if (at.abandoned) return
        /** How the CONNECT ended: [error] null = the broker accepted it. */
        class Done(val error: Throwable?)
        val done = CompletableDeferred<Done>()
        try {
            client.connectWith()
                .cleanStart(true)
                .keepAlive(KEEP_ALIVE_S)
                .willPublish()
                .topic(presenceTopic(identity.nodeNum32))
                .payload(Presence.encode(false, nowSec()))
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .applyWillPublish()
                .send()
                .whenComplete { _, error ->
                    done.complete(Done(error))
                    // Connected after we stopped waiting: this client must not hold our id.
                    if (error == null && at.abandoned) closeQuietly(client)
                }
        } catch (e: Exception) {
            done.complete(Done(e))
        }
        val result = withTimeoutOrNull(left()) { done.await() }
        if (result == null) {
            at.abandoned = true
            closeQuietly(client)
            val took = (now() - startedMs + 500) / 1000
            post { attemptFailed(at, "no answer within $took s") }
            return
        }
        val error = result.error
        post {
            if (error == null) attemptConnected(at, client)
            else attemptFailed(at, reasonOf(error), fatal = isFatal(error))
        }
    }

    private fun buildClient(c: RelayEndpoints.Candidate): Mqtt5AsyncClient {
        // The disconnected listener is registered before the client exists: it finds it here.
        val self = java.util.concurrent.atomic.AtomicReference<Mqtt5AsyncClient?>(null)
        val client = MqttClient.builder()
            .useMqttVersion5()
            // Stable per device: a reconnect takes over the old broker session, so the old
            // session's Last Will ("off") fires BEFORE our fresh "on" instead of overwriting it.
            .identifier("dtn-${hex(identity.nodeNum32)}")
            .transportConfig(transportFor(c))
            // No automatic reconnect: [machine] decides every retry (see the class doc). A failed
            // CONNECT also lands here; it is handled through the connect result instead.
            .addDisconnectedListener { ctx ->
                runCatching { ctx.reconnector.reconnect(false) }
                val reason = reasonOf(ctx.cause)
                val me = self.get()
                post { if (me != null && session === me) sessionLost(me, reason) }
            }
            .buildAsync()
        self.set(client)
        // One global consumer (registered before connecting) instead of per-subscription
        // callbacks, so re-subscribing after every reconnect can't duplicate delivery.
        client.publishes(MqttGlobalPublishFilter.ALL) { pub -> if (session === client) onPublish(pub) }
        return client
    }

    private fun attemptConnected(at: Attempt, client: Mqtt5AsyncClient) {
        if (attempt !== at) { closeQuietly(client); return }
        val step = machine.onConnected(at.id, now())
        attempt = null
        if (step == null) { closeQuietly(client); return }
        session = client
        presenceTimeouts.set(0)
        act(step)
        onSessionUp(client)
    }

    private fun attemptFailed(at: Attempt, reason: String, fatal: Boolean = false) {
        if (attempt !== at) return
        attempt = null
        at.client?.let { closeQuietly(it) }
        act(machine.onAttemptFailed(at.id, reason, now(), fatal))
    }

    /** Control thread: the connected session is gone ([reason]); the machine schedules the reconnect. */
    private fun sessionLost(client: Mqtt5AsyncClient?, reason: String) {
        if (client != null && session !== client) return
        session = null
        heartbeatJob?.cancel(); heartbeatJob = null
        clearPresence()
        client?.let { closeQuietly(it) }
        act(machine.onSessionLost(reason, now()))
    }

    private fun abortAttempt() {
        val at = attempt ?: return
        attempt = null
        at.abandoned = true
        at.job?.cancel()
        at.client?.let { closeQuietly(it) }
    }

    /** Action.Abort: drop the attempt and the session (a new attempt follows, or we wait for internet). */
    private fun abortAll() {
        abortAttempt()
        val s = session ?: return
        session = null
        heartbeatJob?.cancel(); heartbeatJob = null
        clearPresence()
        closeQuietly(s)
    }

    /** Action.Close: switched off. The session says goodbye in the background. */
    private fun closeAll() {
        abortAttempt()
        heartbeatJob?.cancel(); heartbeatJob = null
        tickerJob?.cancel(); tickerJob = null
        clearPresence()
        val s = session ?: return
        session = null
        teardownJob = scope.launch { teardown(s, sayGoodbye = true) }
    }

    /** Disconnect [c] without a goodbye (its will is discarded), off the control thread. */
    private fun closeQuietly(c: Mqtt5AsyncClient) {
        scope.launch {
            runCatching { withTimeoutOrNull(DISCONNECT_WAIT_MS) { c.disconnect().await() } }
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
    }

    /** A new session: subscribe again (clean start), publish presence, start the heartbeat. */
    private fun onSessionUp(c: Mqtt5AsyncClient) {
        // cleanStart=true → every connect is a fresh broker session: subscribe again each time.
        // (Same filters, so the broker replaces rather than duplicates the subscriptions.)
        for (filter in listOf(nodeTopic(identity.nodeNum32), BCAST_TOPIC, "$PRESENCE_PREFIX+")) {
            runCatching {
                c.subscribeWith().topicFilter(filter).qos(MqttQos.AT_LEAST_ONCE).send()
                    .whenComplete { _, ex -> if (ex != null) Log.w(TAG, "subscribe $filter failed", ex) }
            }
        }
        publishPresenceAsync(c)
        startHeartbeat(c)
    }

    /**
     * Relay Configuration › Test connection: connect a throwaway client to [url] and disconnect it.
     * It has its own random client id and no Last Will, so the running relay session (and the
     * presence others see) is untouched. Endpoints are tried in the relay's own order (IPv4 first;
     * the public broker's TLS 8883 → TCP 1883 → WebSocket 8884), at most [TEST_MAX_TRIES] of them.
     * Runs on the IO dispatcher (DNS + sockets), never on the caller's thread.
     */
    suspend fun testConnection(url: String): RelayTestResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val list = runCatching {
            RelayEndpoints.candidates(RelayEndpoints.specsFor(url)) { host -> InetAddress.getAllByName(host).toList() }
        }.getOrDefault(emptyList())
        if (list.isEmpty()) return@withContext RelayTestResult(false, "—", 0L, "No broker address")
        var lastReason = "not tried"
        var lastEndpoint = list.first().label
        for (c in list.take(TEST_MAX_TRIES)) {
            lastEndpoint = c.label
            val t0 = System.currentTimeMillis()
            val outcome = runCatching {
                val probe = MqttClient.builder()
                    .useMqttVersion5()
                    .identifier("dtn-test-" + java.util.UUID.randomUUID().toString().take(8))
                    .transportConfig(transportFor(c))
                    .buildAsync()
                val connecting = probe.connectWith().cleanStart(true).keepAlive(KEEP_ALIVE_S).send()
                // Closed as soon as it connects, also when we stopped waiting (timeout, page closed):
                // a probe never stays connected.
                connecting.whenComplete { _, error -> if (error == null) runCatching { probe.disconnect() } }
                withTimeout(TEST_TRY_TIMEOUT_MS) { connecting.asDeferred().await() }
            }
            if (outcome.isSuccess) {
                val took = System.currentTimeMillis() - t0
                diagInfo("Test connection: the broker answered on ${c.label} in $took ms")
                return@withContext RelayTestResult(true, c.label, took, "The broker accepted a test connection")
            }
            val error = outcome.exceptionOrNull()
            lastReason = if (error is kotlinx.coroutines.TimeoutCancellationException) "no answer within ${TEST_TRY_TIMEOUT_MS / 1000} s"
            else reasonOf(error)
        }
        diagWarn("Test connection failed: $lastReason (last tried $lastEndpoint)")
        RelayTestResult(false, lastEndpoint, System.currentTimeMillis() - started, lastReason)
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

    /** Why an attempt failed or a session dropped, with the broker's reason code when it sent one. */
    private fun reasonOf(t: Throwable?): String {
        var e = t
        var depth = 0
        while (e != null && depth++ < 8) {
            when (e) {
                is Mqtt5DisconnectException -> {
                    val m = e.mqttMessage
                    return "Server sent DISCONNECT: " + m.reasonCode.name.lowercase().replace('_', ' ') +
                        " (0x%02X)".format(m.reasonCode.code) + m.reasonString.map { " · ${it.toString().take(60)}" }.orElse("")
                }
                is Mqtt5ConnAckException -> {
                    val m = e.mqttMessage
                    return "Server refused the connection: " + m.reasonCode.name.lowercase().replace('_', ' ') +
                        " (0x%02X)".format(m.reasonCode.code)
                }
            }
            e = e.cause.takeIf { it !== e }
        }
        return shortReason(t)
    }

    /** Refusals that another try can't fix: the broker said no to who we are. */
    private fun isFatal(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 8) {
            if (e is Mqtt5ConnAckException) return e.mqttMessage.reasonCode in FATAL_CONNACK
            e = e.cause.takeIf { it !== e }
        }
        return false
    }

    private fun shortReason(t: Throwable?): String {
        var cause = t
        while (cause?.cause != null && cause.cause !== cause) cause = cause.cause
        val name = cause?.javaClass?.simpleName ?: "closed"
        return cause?.message?.take(60)?.let { "$name: $it" } ?: name
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

    private fun publishPresenceAsync(c: Mqtt5AsyncClient) {
        scope.launch {
            if (publishBounded(c, presenceTopic(identity.nodeNum32), Presence.encode(true, nowSec()), retain = true)) {
                presenceTimeouts.set(0)
                return@launch
            }
            Log.w(TAG, "presence publish not acknowledged")
            diagWarn("Presence publish timed out (no PUBACK)")
            // Two in a row on a "connected" client = a half-open link: end the session, reconnect.
            if (presenceTimeouts.incrementAndGet() >= 2) {
                post { if (session === c) sessionLost(c, "no answer from the broker (2 presence updates without PUBACK)") }
            }
        }
    }

    /**
     * Refresh our presence every [HEARTBEAT_MS], and every [PRESENCE_SWEEP_MS] expire peers whose
     * heartbeat stopped for [PRESENCE_STALE_MS] (their will can be lost, or not fire for a while).
     */
    private fun startHeartbeat(c: Mqtt5AsyncClient) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var lastBeat = System.currentTimeMillis()
            while (isActive && session === c) {
                delay(PRESENCE_SWEEP_MS)
                val now = System.currentTimeMillis()
                if (now - lastBeat >= HEARTBEAT_MS) {
                    lastBeat = now
                    if (isConnected) publishPresenceAsync(c)
                }
                val cutoff = now - PRESENCE_STALE_MS
                val gone = presenceHeard.filterValues { it < cutoff }.keys
                if (gone.isNotEmpty()) {
                    gone.forEach { presenceHeard.remove(it) }
                    Log.i(TAG, "Presence: ${gone.joinToString { it.takeLast(8) }} offline (no heartbeat for ${PRESENCE_STALE_MS / 1000}s)")
                    diagLog.i(com.dtn.mesh.service.DiagSource.PRESENCE, "Relay: ${gone.joinToString { it.takeLast(8) }} " +
                        "offline (no heartbeat for ${PRESENCE_STALE_MS / 1000} s)")
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
            diagLog.i(com.dtn.mesh.service.DiagSource.PRESENCE, "Relay: ${node.takeLast(8)} ${if (now) "online" else "offline"} " +
                "(${if (status.online) "on" else "off"}${if (retained) ", retained" else ""}, sent ${nowSec() - status.epochSec} s ago)")
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
        val c = session ?: return null
        if (!isConnected || !clientConnected(c)) return null
        return try {
            val payload = InternetFrames.wrap(identity.nodeNum32, DtnWireCodec.encode(message))
            if (!publishBounded(c, nodeTopic(targetPeer.toNodeNum32()), payload)) {
                Log.w(TAG, "relay publish to ${targetPeer.value.takeLast(8)} not acknowledged")
                diagWarn("Publish to ${targetPeer.value.takeLast(8)} timed out (no PUBACK, ${payload.size} B)")
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
        val c = session ?: return
        if (!isConnected) return
        scope.launch {
            val payload = runCatching { InternetFrames.wrap(identity.nodeNum32, DtnWireCodec.encode(message)) }.getOrNull()
                ?: return@launch
            if (publishBounded(c, BCAST_TOPIC, payload)) onPublished?.invoke()
            else {
                Log.w(TAG, "relay broadcast publish not acknowledged")
                diagWarn("Broadcast publish timed out (no PUBACK)")
            }
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
        /**
         * One attempt (DNS + socket + TLS / WebSocket + CONNACK) may take this long, then it is
         * abandoned and counted as failed: under the machine's 30 s watchdog, which is only a backstop.
         */
        private const val ATTEMPT_BUDGET_MS = 27_000L
        /** A DNS lookup that hasn't answered by then is left behind (the client resolves itself). */
        private const val DNS_TIMEOUT_MS = 8_000L
        /** How long an attempt waits for the previous session's goodbye. */
        private const val TEARDOWN_WAIT_MS = 2_000L
        /** The control tick: due retries, the attempt watchdog, a silently dead session. */
        private const val TICK_MS = 1_000L
        /** Test connection: endpoints tried, and the time each may take. */
        private const val TEST_MAX_TRIES = 4
        private const val TEST_TRY_TIMEOUT_MS = 8_000L
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
        /** CONNACK refusals another try can't fix. */
        private val FATAL_CONNACK = setOf(
            Mqtt5ConnAckReasonCode.NOT_AUTHORIZED,
            Mqtt5ConnAckReasonCode.BAD_USER_NAME_OR_PASSWORD,
            Mqtt5ConnAckReasonCode.BANNED,
            Mqtt5ConnAckReasonCode.CLIENT_IDENTIFIER_NOT_VALID,
            Mqtt5ConnAckReasonCode.UNSUPPORTED_PROTOCOL_VERSION,
            Mqtt5ConnAckReasonCode.BAD_AUTHENTICATION_METHOD,
        )
    }
}
