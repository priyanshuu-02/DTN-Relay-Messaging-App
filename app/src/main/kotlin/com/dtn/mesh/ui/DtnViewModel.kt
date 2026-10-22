package com.dtn.mesh.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.crypto.GroupKeyStore
import com.dtn.mesh.database.dao.ChatDao
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.dao.MessageDao
import com.dtn.mesh.database.entity.ChatMessageEntity
import com.dtn.mesh.service.OutboundMessageService
import com.dtn.mesh.settings.SettingsRepository
import com.dtn.mesh.export.FileExportHelper
import com.dtn.mesh.export.ResearchExporter
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.crypto.DtnCrypto
import com.dtn.mesh.crypto.GroupInvite
import com.dtn.mesh.crypto.IdentityQr
import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.ForwardingStateBuilder
import com.dtn.mesh.receiver.LinkInfo
import com.dtn.mesh.receiver.MeshConnectionState
import com.dtn.mesh.receiver.MeshTransport
import com.dtn.mesh.receiver.MultiTransportManager
import com.dtn.mesh.receiver.RadioStatusMonitor
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.receiver.TransportStatus
import com.dtn.mesh.routing.StrategySelector
import com.dtn.mesh.service.DtnOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DtnViewModel @Inject constructor(
    application: Application,
    private val transport: MeshTransport,
    private val orchestrator: DtnOrchestrator,
    private val queueManager: MessageQueueManager,
    private val strategySelector: StrategySelector,
    private val exporter: ResearchExporter,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val outbound: OutboundMessageService,
    private val groupKeyStore: GroupKeyStore,
    private val identity: DtnIdentity,
    private val settings: SettingsRepository,
    private val lifecycleLog: com.dtn.mesh.service.MessageLifecycleLog,
    private val benchmarkTracker: com.dtn.mesh.learning.BenchmarkTracker,
    private val radioMonitor: RadioStatusMonitor,
    private val qEngine: DoubleQLearningEngine,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DtnViewModel"
        /** Sentinel string used to represent the "broadcast to everyone" chat in the UI. */
        const val BROADCAST_CHAT_ID = "__broadcast__"
        /** Default message lifetime (4 hours). */
        const val DEFAULT_TTL_MS = 4 * 3_600_000L
        /** Conservative plaintext byte budget shown in the composer (actual check is server-side). */
        const val MAX_MSG_BYTES = 200
    }

    /** Aggregate state, recomputed from every transport (never a stale connect-time snapshot). */
    val connectionState: StateFlow<MeshConnectionState> = transport.connectionState

    /** Per-transport live state + what the user wants running (drives the transport chips). */
    val transportStatus: StateFlow<TransportStatus> = transport.transportStatus

    /** Bluetooth / Wi-Fi / location / internet availability. */
    val radios: StateFlow<RadioStatusMonitor.Radios> = radioMonitor.radios

    /** Contacts currently present on the internet relay. */
    val internetPeers: StateFlow<Set<String>> = transport.internetPeers

    private val mgr: MultiTransportManager? get() = transport as? MultiTransportManager

    /** What each link is doing and which devices are on it (tap a transport chip). */
    val linkInfo: StateFlow<Map<TransportKind, LinkInfo>> =
        mgr?.linkInfo ?: MutableStateFlow<Map<TransportKind, LinkInfo>>(emptyMap()).asStateFlow()

    /** Wi-Fi Direct's invitation-free groups need Android 10+. */
    val wifiDirectSupported: Boolean get() = mgr?.wifiDirectSupported ?: false

    /** One-shot user-facing notices (snackbars): joined a group, invalid QR, cleared buffer, … */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private fun notifyUser(message: String) {
        _userMessages.tryEmit(message)
        addLog(message)
    }

    /** Whether the "before you connect" checklist is showing. */
    private val _showPreflight = MutableStateFlow(false)
    val showPreflight: StateFlow<Boolean> = _showPreflight.asStateFlow()

    private val _localNodeId = MutableStateFlow<String?>(null)
    val localNodeId: StateFlow<String?> = _localNodeId.asStateFlow()

    private val _logEntries = MutableStateFlow<List<String>>(emptyList())
    val logEntries: StateFlow<List<String>> = _logEntries.asStateFlow()

    /** Reactive buffered count straight from Room — always accurate. */
    val bufferedCount: StateFlow<Int> = queueManager.observeBufferedCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Live list of all currently-buffered messages (for the Network tab). */
    val bufferedMessages: StateFlow<List<BufferedMsgInfo>> = queueManager.observeBufferedMessages()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _exportPath = MutableStateFlow<String?>(null)
    val exportPath: StateFlow<String?> = _exportPath.asStateFlow()

    /** Message lifecycle events for the dedicated Lifecycle page. */
    val lifecycleEntries: StateFlow<List<com.dtn.mesh.service.LifecycleEntry>> = lifecycleLog.entries

    /** Engine console (encounter history + per-strategy probability calc) for the Log tab. */
    val engineLog: StateFlow<List<String>> = lifecycleLog.console

    /** Per-strategy benchmark metrics for the Network tab comparison card. */
    val benchmarks: StateFlow<List<com.dtn.mesh.learning.BenchmarkStat>> = benchmarkTracker.flow

    /** Clear accumulated benchmark counters (start a fresh comparison run). */
    fun resetBenchmarks() = benchmarkTracker.reset()

    /** Per-message hop routes for the Network tab. */
    val messageRoutes: StateFlow<List<com.dtn.mesh.service.MessageLifecycleLog.RouteRecord>> = lifecycleLog.routes

    private val _activeStrategy = MutableStateFlow("PROPHET")
    val activeStrategy: StateFlow<String> = _activeStrategy.asStateFlow()

    /**
     * Discovered peers with online state, driven by the Room `contacts` table. This means:
     * - Offline peers stay in the list (so we can still target them; the message goes to buffer).
     * - Peers survive app restarts (Task 3).
     * - Custom nicknames (Task 4) flow through automatically.
     */
    val peerList: StateFlow<List<PeerInfo>> = combine(
        contactDao.observeAll(), transport.internetPeers, transport.hubPeers, linkInfo,
    ) { contacts, present, onHub, links ->
            val me = identity.nodeId.value
            // Phones a link of ours ends at (BLE in range, Wi-Fi Direct group): "nearby".
            val direct = (links[TransportKind.BLE]?.peers.orEmpty() + links[TransportKind.WIFI_DIRECT]?.peers.orEmpty())
                .mapTo(HashSet()) { it.nodeId }
            contacts.filter { it.nodeId != me }.map { c ->
                PeerInfo(
                    nodeId = c.nodeId,
                    isOnline = c.isOnline,
                    lastSeenMs = c.lastEncounterMs,
                    customName = c.customName,
                    longName = c.longName,
                    lastRssi = c.lastRssi,
                    internetOnline = c.nodeId in present,
                    hubOnly = c.nodeId in onHub && c.nodeId !in direct,
                    announceName = c.announceName,
                    lastSnr = c.lastSnr,
                    totalEncounters = c.totalEncounters,
                    totalEncounterDurationMs = c.totalEncounterDurationMs,
                    deliverySuccessCount = c.deliverySuccessCount,
                    deliveryAttemptCount = c.deliveryAttemptCount,
                    keyVerified = c.keyVerified,
                    fingerprintHex = if (c.xPub != null && c.edPub != null)
                        DtnCrypto.fingerprint(c.xPub, c.edPub).joinToString("") { "%02x".format(it) } else null,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Active strategy's per-peer score for the Network tab (P / f / Q), refreshed periodically. */
    private val _peerMetrics = MutableStateFlow<Map<String, PeerMetric>>(emptyMap())
    val peerMetrics: StateFlow<Map<String, PeerMetric>> = _peerMetrics.asStateFlow()

    /** Last measured buffer occupancy, for the synchronous Q-learning explanation. */
    @Volatile private var lastOccupancy: Double = 0.0

    /** Backward-compatibility shim — just node ids. */
    val discoveredPeers: StateFlow<List<String>> = peerList
        .map { list -> list.map { it.nodeId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Currently selected chat.
     *   null  → chat list screen (no chat open)
     *   [BROADCAST_CHAT_ID] → the broadcast conversation
     *   any other value → the 1:1 conversation with that node id
     */
    private val _selectedChat = MutableStateFlow<String?>(null)
    val selectedChat: StateFlow<String?> = _selectedChat.asStateFlow()

    /**
     * Legacy destination selector (kept because parts of the app still read it). Kept in sync
     * with [_selectedChat]: broadcast → null destination, 1:1 → that peer.
     */
    private val _selectedDestination = MutableStateFlow<String?>(null)
    val selectedDestination: StateFlow<String?> = _selectedDestination.asStateFlow()

    /**
     * All chat messages (sent + received), sourced from the persistent [ChatDao] chat log — the
     * plaintext conversation store, decoupled from the forwarding buffer. Outgoing messages are
     * written by [OutboundMessageService]; decrypted inbound messages by [DtnOrchestrator].
     */
    val receivedMessages: StateFlow<List<ChatMessage>> = combine(chatDao.observeAll(), lifecycleLog.routes) { rows, routes ->
            // Each bubble's state merges the chat row with the (persistent) route journal.
            val byId = routes.associateBy { it.msgId }
            rows.map { toChatMessage(it, byId[it.id.take(8)]) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Internet relay status line (endpoint, attempt, last error) for Settings and the Log. */
    val relayDiagnostics: StateFlow<String> =
        (transport as? MultiTransportManager)?.relayDiagnostics
            ?: MutableStateFlow("Relay unavailable").asStateFlow()

    /** Joined groups for the chat list (channel + name). */
    val groups: StateFlow<List<GroupInfo>> = groupKeyStore.observeGroups()
        .map { list -> list.map { GroupInfo(it.channel, it.name) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Transient composer error (e.g. "no key yet", "too long") for the UI to surface. */
    private val _composerError = MutableStateFlow<String?>(null)
    val composerError: StateFlow<String?> = _composerError.asStateFlow()
    fun clearComposerError() { _composerError.value = null }

    // ── Settings (DataStore-backed) ─────────────────────────────────────────
    val displayNameSetting: StateFlow<String> = settings.displayName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val internetEnabled: StateFlow<Boolean> = settings.internetEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val brokerUrl: StateFlow<String> = settings.brokerUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_BROKER)

    /** My public identity for the QR / safety-number screen. */
    val myNodeId: String get() = identity.nodeId.value
    val myFingerprintHex: String get() = identity.fingerprint().joinToString("") { "%02x".format(it) }

    @Volatile private var cachedName: String = ""

    /**
     * Messages filtered to the currently selected chat.
     *
     * Rules:
     * - Broadcast chat: only broadcasts (toNodeId == null).
     * - 1:1 chat with peer X: only messages whose conversation peer is X — broadcasts are
     *   *not* mixed in, they live exclusively in the broadcast chat.
     * - No chat open: empty.
     */
    val filteredMessages: StateFlow<List<ChatMessage>> = combine(
        receivedMessages,
        _selectedChat,
    ) { all, selected ->
        when (selected) {
            null -> emptyList()
            BROADCAST_CHAT_ID -> all.filter { it.toNodeId == null }
            else -> all.filter { it.peerOf() == selected }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        _localNodeId.value = identity.nodeId.value

        // Older builds pinned our own announce as a contact when a carrier echoed it back. The
        // orchestrator now ignores our own bundles; drop any self-contact left behind.
        viewModelScope.launch {
            val removed = runCatching { contactDao.deleteByNodeId(identity.nodeId.value) }.getOrDefault(0)
            if (removed > 0) addLog("Removed this phone's own entry from the contact list")
        }

        // NODE events: log only. Peer bookkeeping + inbound decryption + chat persistence are all
        // handled in the orchestrator now; the UI just observes the ChatDao / contacts tables.
        viewModelScope.launch {
            transport.nodeEvents.collect { event ->
                addLog("NODE: ${event.nodeId.value} online=${event.isOnline} rssi=${event.rssi}")
            }
        }

        // Hop-level transport ACKs are informational only (never end-to-end delivery proof).
        viewModelScope.launch {
            transport.deliveryStatus.collect { event ->
                addLog("ACK: pktId=${event.meshPacketId} status=${event.status}")
            }
        }

        // Chat-row status (delivered / expired / dropped) is persisted by MessageQueueManager
        // itself, so it's recorded even while this screen is closed. Here we only journal the
        // user's own "Clear" action for the messages the journal tracks.
        viewModelScope.launch {
            queueManager.terminalEvents.collect { ev ->
                if (ev.reason == MessageQueueManager.REASON_CLEARED && lifecycleLog.isTracked(ev.msgId)) {
                    lifecycleLog.log(com.dtn.mesh.service.LifecycleEvent.DROPPED, ev.msgId,
                        extra = "buffer cleared by user")
                }
            }
        }

        // Re-announce our identity/keys periodically while connected so newly-met peers can learn
        // our public keys (and therefore encrypt DMs to us). Announces flood + are TOFU-pinned.
        // Each re-announce supersedes our previous one in the buffer (MessageDao.supersedeAnnounces),
        // so this refreshes the single buffered copy rather than stacking up. Kept well inside the
        // 10-min announce TTL; 5 min trades a little key-propagation latency for much less LoRa
        // airtime than the old 2-min cadence.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5 * 60_000L)
                if (transport.isConnected) runCatching { outbound.sendAnnounce(displayName()) }
            }
        }

        // Keep the cached display name in sync so announces use the user's chosen name.
        viewModelScope.launch { settings.displayName.collect { cachedName = it } }

        // The relay (foreground service + orchestrator) runs exactly while at least one transport
        // is wanted. This replaces the old "start only after Connect succeeded" logic, which left
        // nothing running when a single transport was switched on from its chip.
        viewModelScope.launch {
            transport.transportStatus
                .map { it.wanted.isNotEmpty() }
                .distinctUntilChanged()
                .collect { running ->
                    if (running) {
                        com.dtn.mesh.service.DtnForegroundService.start(getApplication())
                        orchestrator.start()
                    } else {
                        com.dtn.mesh.service.DtnForegroundService.stop(getApplication())
                        orchestrator.stop()
                        // With every transport down we can't verify anyone's liveness (also clears
                        // stale online flags left by a previous session that was killed).
                        runCatching { contactDao.markAllOffline() }
                    }
                }
        }

        // Each time the relay (re)connects, announce our keys over it so contacts present on the
        // internet can encrypt to us without ever having met us over radio.
        viewModelScope.launch {
            transport.transportStatus.map { it.internet }.distinctUntilChanged().collect { st ->
                if (st == MeshConnectionState.CONNECTED) runCatching { outbound.sendAnnounce(displayName()) }
            }
        }

        // Log every transport state change (and relay diagnostics) to the Log tab, so what each
        // link is doing — and why it isn't up — is visible on the phone.
        viewModelScope.launch {
            var last: TransportStatus? = null
            transport.transportStatus.collect { st ->
                val prev = last
                last = st
                if (prev == null) return@collect
                for (kind in TransportKind.entries) {
                    val before = prev.stateOf(kind)
                    val now = st.stateOf(kind)
                    if (before != now) addLog("${kind.label()}: ${before.name.lowercase()} → ${now.name.lowercase()}")
                }
            }
        }
        viewModelScope.launch {
            relayDiagnostics.collect { line -> if (line.isNotBlank()) addLog("Relay: $line") }
        }

        // Strategy-specific per-peer metric for the Network tab. Routing tables aren't observable,
        // so this samples them on a short tick (and immediately whenever the peer list changes).
        viewModelScope.launch {
            while (true) {
                refreshPeerMetrics()
                delay(3_000L)
            }
        }
        viewModelScope.launch { peerList.collect { refreshPeerMetrics() } }
    }

    private suspend fun refreshPeerMetrics() {
        lastOccupancy = runCatching { queueManager.getBufferOccupancy() }.getOrDefault(lastOccupancy)
        val peers = peerList.value
        _peerMetrics.value = withContext(Dispatchers.Default) { computePeerMetrics(peers) }
    }

    private fun computePeerMetrics(peers: List<PeerInfo>): Map<String, PeerMetric> {
        val active = strategySelector.active
        return peers.associate { p ->
            val id = NodeId(p.nodeId)
            p.nodeId to when (active) {
                is com.dtn.mesh.routing.ProphetStrategy ->
                    PeerMetric("P", fmt2(active.getDeliveryProbability(id)), "delivery predictability")
                is com.dtn.mesh.routing.MaxPropStrategy ->
                    PeerMetric("f", fmt2(active.getDeliveryProbability(id)), "meeting likelihood")
                is com.dtn.mesh.routing.EpidemicStrategy ->
                    PeerMetric("flood", "all", "epidemic forwards to every peer")
                is com.dtn.mesh.routing.QLearningStrategy -> {
                    val (qf, qs) = qEngine.qValues(previewState(p))
                    PeerMetric("Q", "${fmt2(qf)}/${fmt2(qs)}", if (qf > qs) "fwd > wait: would forward" else "wait ≥ fwd: would hold")
                }
                else -> PeerMetric("P", "—", "")
            }
        }
    }

    /**
     * The Q-learning state a fresh message would see when meeting [peer], built with the same
     * normalisation the orchestrator uses for real decisions ([ForwardingStateBuilder]).
     */
    private fun previewState(peer: PeerInfo) = ForwardingStateBuilder.build(
        lastRssi = peer.lastRssi,
        lastSnr = peer.lastSnr,
        totalEncounters = peer.totalEncounters,
        totalEncounterDurationMs = peer.totalEncounterDurationMs,
        deliveryProbability = strategySelector.prophetDeliveryProbability(NodeId(peer.nodeId)),
        remainingTtlMs = DEFAULT_TTL_MS,
        ttlMs = DEFAULT_TTL_MS,
        bufferOccupancy = lastOccupancy,
        deliverySuccessCount = peer.deliverySuccessCount,
        deliveryAttemptCount = peer.deliveryAttemptCount,
        duplicateCount = 0,
    )

    /** Short human name advertised in our announce: the user-set name, else a node-id short form. */
    private fun displayName(): String = cachedName.ifBlank { "node-" + identity.nodeId.value.takeLast(4) }

    /** Map a persisted chat row (+ its route journal record) to the UI model. */
    private fun toChatMessage(e: ChatMessageEntity, route: com.dtn.mesh.service.MessageLifecycleLog.RouteRecord?): ChatMessage {
        val isOut = e.direction == ChatMessageEntity.Direction.OUT
        val multiParty = e.convKey.startsWith("grp:") || e.convKey == NodeId.BROADCAST.value
        val toNodeId = when {
            e.convKey.startsWith("grp:") -> e.convKey
            e.convKey == NodeId.BROADCAST.value -> null
            else -> e.peerNodeId ?: e.convKey
        }
        val state = deliveryState(e.status, isOut, multiParty, route)
        return ChatMessage(
            msgId = e.id,
            text = e.text,
            // For group / broadcast rows peerNodeId is the ORIGIN (sender). Rows stored by older
            // builds have none — "" makes the UI show "Unknown sender" instead of "grp:1".
            from = if (isOut) "me" else (e.peerNodeId ?: ""),
            toNodeId = toNodeId,
            isOutgoing = isOut,
            status = state.label,
            time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(e.createdAtMs)),
            createdAtMs = e.createdAtMs,
            state = state,
            via = route?.lastPath.orEmpty(),
            isMultiParty = multiParty,
        )
    }

    // ── Actions ──────────────────────────────────────────────────────────

    /**
     * Connect button. Runs the preflight checklist first (permissions, Bluetooth, Wi-Fi, location
     * where required) and only shows it when something would stop a transport from working —
     * instead of silently sitting on "Connecting…" with Bluetooth off.
     */
    fun requestConnect() {
        radioMonitor.refresh()
        if (preflightIssues().isEmpty()) connectAll() else _showPreflight.value = true
    }

    fun dismissPreflight() { _showPreflight.value = false }

    /** Re-read radio state (called when the app resumes, e.g. back from system settings). */
    fun refreshRadios() = radioMonitor.refresh()

    /** What would currently stop a transport from working. Pure read — safe to call often. */
    fun preflightIssues(): List<PreflightIssue> {
        val r = radios.value
        val issues = mutableListOf<PreflightIssue>()
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            issues += PreflightIssue(
                PreflightIssue.Kind.PERMISSIONS,
                "Permissions needed",
                "Allow ${describePermissions(missing)} so the app can find nearby phones and the LoRa hub.",
            )
        }
        if (!r.bluetoothOn) {
            issues += PreflightIssue(
                PreflightIssue.Kind.BLUETOOTH_OFF,
                "Bluetooth is off",
                "Bluetooth carries messages to nearby phones. Turn it on, or reach your contacts " +
                    "over the internet relay instead.",
            )
        }
        if (!r.wifiOn) {
            issues += PreflightIssue(
                PreflightIssue.Kind.WIFI_OFF,
                "Wi-Fi is off",
                "The LoRa hub and Wi-Fi Direct both need Wi-Fi switched on (no network required).",
            )
        }
        // Wi-Fi Direct discovery needs location services on before Android 13 (BLE scans before 12).
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU && !r.locationOn) {
            issues += PreflightIssue(
                PreflightIssue.Kind.LOCATION_OFF,
                "Location is off",
                "This Android version only discovers nearby devices with location services on.",
            )
        }
        return issues
    }

    /** Start every radio transport (+ the internet relay when it's enabled in settings). */
    fun connectAll() {
        _showPreflight.value = false
        viewModelScope.launch {
            val withInternet = runCatching { settings.internetEnabledOnce() }.getOrDefault(false)
            addLog("Starting transports (BLE + Wi-Fi Direct + LoRa hub${if (withInternet) " + internet relay" else ""})…")
            mgr?.connectAll(includeInternet = withInternet) ?: transport.connect()
            _localNodeId.value = identity.nodeId.value
            // Announce our identity/keys so peers can encrypt to us (TOFU pinning on their side).
            runCatching { outbound.sendAnnounce(displayName()) }
            val st = transportStatus.value
            if (!radios.value.bluetoothOn) addLog("BLE waiting — Bluetooth is off (starts automatically when it's turned on)")
            if (st.activeKinds.isEmpty()) addLog("Transports starting — see the status chips for each link")
        }
    }

    /** Bluetooth is off and the user chose the internet: enable the relay and start the rest. */
    fun useInternetRelayInstead() {
        _showPreflight.value = false
        viewModelScope.launch {
            settings.setInternetEnabled(true)
            mgr?.connectAll(includeInternet = true)
            runCatching { outbound.sendAnnounce(displayName()) }
            notifyUser("Internet relay on — contacts with the relay enabled can reach you anywhere")
        }
    }

    /** Disconnect button: stop every transport. The relay setting itself is kept for next time. */
    fun disconnectAll() {
        transport.disconnect()
        addLog("Disconnected — all transports stopped")
    }

    /**
     * Status-chip tap: stop a transport that is up or coming up, otherwise (off, or wanted but
     * failed) start / retry it. The internet chip also persists the relay setting.
     */
    fun toggleTransport(kind: TransportKind) {
        viewModelScope.launch {
            val st = transportStatus.value
            val state = st.stateOf(kind)
            val up = state == MeshConnectionState.CONNECTED || state == MeshConnectionState.CONNECTING
            if (st.isWanted(kind) && up) {
                if (kind == TransportKind.INTERNET) settings.setInternetEnabled(false)
                mgr?.disconnect(kind)
                addLog("${kind.label()} stopped")
            } else {
                if (kind == TransportKind.INTERNET) settings.setInternetEnabled(true)
                val ok = mgr?.connect(kind) ?: false
                addLog(if (ok) "${kind.label()} starting" else "${kind.label()} could not start")
                if (ok) runCatching { outbound.sendAnnounce(displayName()) }
            }
        }
    }

    /** Link details "Turn off": stop [kind]; it stays off until its chip is tapped again. */
    fun turnOffTransport(kind: TransportKind) {
        viewModelScope.launch {
            if (kind == TransportKind.INTERNET) settings.setInternetEnabled(false)
            mgr?.disconnect(kind)
            addLog("${kind.label()} stopped")
        }
    }

    private fun TransportKind.label(): String = when (this) {
        TransportKind.BLE -> "BLE"
        TransportKind.WIFI_DIRECT -> "Wi-Fi Direct"
        TransportKind.HUB -> "LoRa hub"
        TransportKind.INTERNET -> "Internet relay"
    }

    /**
     * Transport permissions that are not currently granted. Notifications are excluded: the relay
     * works without them (the foreground notification is just hidden), so they never block.
     */
    private fun missingPermissions(): List<String> {
        val ctx = getApplication<Application>()
        return PermissionHelper.requiredPermissions()
            .filter { it != android.Manifest.permission.POST_NOTIFICATIONS }
            .filter { ctx.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
    }

    private fun describePermissions(perms: List<String>): String = perms.map {
        when {
            it.startsWith("android.permission.BLUETOOTH") -> "Nearby devices (Bluetooth)"
            it == "android.permission.NEARBY_WIFI_DEVICES" -> "Nearby devices (Wi-Fi)"
            it.contains("LOCATION") -> "Location"
            else -> it.substringAfterLast('.').lowercase().replace('_', ' ')
        }
    }.distinct().joinToString(" and ")

    // ── Permissions ──────────────────────────────────────────────────────

    fun onPermissionsGranted() {
        addLog("All permissions granted")
    }

    fun onPermissionsDenied(denied: List<String>) {
        addLog("WARN: permissions denied: ${denied.joinToString()}")
        addLog("Some transports may not work without these permissions")
    }

    /**
     * Open a chat. Pass [BROADCAST_CHAT_ID] for the broadcast conversation, a node id for a
     * 1:1 chat, or null to return to the chat list screen.
     */
    fun openChat(chatKey: String?) {
        _selectedChat.value = chatKey
        _selectedDestination.value = when (chatKey) {
            null, BROADCAST_CHAT_ID -> null   // legacy: null = broadcast
            else -> chatKey
        }
        if (chatKey != null) {
            addLog("Opened chat: ${chatKey.takeLast(8)}")
        }
    }

    /** Set (or clear) a user-provided nickname for a peer. Blank string clears the name. */
    fun renamePeer(nodeId: String, name: String) {
        viewModelScope.launch {
            val clean = name.trim().ifBlank { null }
            contactDao.updateCustomName(nodeId, clean)
            addLog("Renamed ${nodeId.takeLast(8)} → ${clean ?: "(cleared)"}")
        }
    }

    fun sendTestMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val sel = _selectedChat.value
            val (dest, channel) = when {
                sel == null || sel == BROADCAST_CHAT_ID -> NodeId.BROADCAST to 0
                sel.startsWith("grp:") -> NodeId.BROADCAST to (sel.removePrefix("grp:").toIntOrNull() ?: 0)
                else -> NodeId(sel) to 0
            }
            when (val result = outbound.sendText(trimmed, dest, channel, DEFAULT_TTL_MS)) {
                is OutboundMessageService.Result.Sent -> {
                    val m = result.message
                    val target = when {
                        channel > 0 -> "grp:$channel"
                        dest == NodeId.BROADCAST -> "ALL"
                        else -> dest.value.takeLast(8)
                    }
                    // Never log message text (logcat ends up in bug reports): id + size only. The
                    // message journal (CREATED / BUFFERED + route) is written by OutboundMessageService.
                    addLog("QUEUED ${m.id.take(8)} → $target (${trimmed.length} chars)")
                    runCatching { orchestrator.triggerFlush() }.onFailure { Log.e(TAG, "triggerFlush failed", it) }
                }
                OutboundMessageService.Result.NoKey ->
                    _composerError.value = if (channel > 0)
                        "You're no longer a member of this group (its key isn't on this phone)."
                    else
                        "No encryption key for this contact yet — scan each other's QR (🔑) or wait for their announce."
                is OutboundMessageService.Result.TooLong ->
                    _composerError.value = "Message too long (max ${result.maxBytes} bytes for this chat)."
                OutboundMessageService.Result.Error ->
                    _composerError.value = "Could not queue message (duplicate or expired)."
            }
        }
    }

    fun switchStrategy() {
        val newType = if (strategySelector.activeType == StrategySelector.StrategyType.PROPHET)
            StrategySelector.StrategyType.MAXPROP else StrategySelector.StrategyType.PROPHET
        strategySelector.switchTo(newType)
        _activeStrategy.value = newType.name
        addLog("Strategy switched to ${newType.name}")
    }

    /** Explicitly select a routing protocol from the UI switch. [typeName] is "PROPHET" or "MAXPROP". */
    fun setStrategy(typeName: String) {
        val type = runCatching { StrategySelector.StrategyType.valueOf(typeName) }
            .getOrDefault(StrategySelector.StrategyType.PROPHET)
        if (type == strategySelector.activeType) return
        strategySelector.switchTo(type)
        _activeStrategy.value = type.name
        addLog("Strategy set to ${type.name}")
        viewModelScope.launch { refreshPeerMetrics() }
    }

    /**
     * Build a human-readable breakdown of how PROPHET would route toward [destNodeId]:
     * my own delivery predictability P(me,dest), each online peer's belief P(peer,dest), the
     * 2-hop path likelihood through them P(me,peer)·P(peer,dest), and the transitive gain each
     * would contribute to my table. Returns null when the active strategy isn't PROPHET
     * (MaxProp uses a different cost model) or the destination is blank/broadcast.
     */
    fun explainRoute(destNodeId: String): RoutingExplanation? {
        if (destNodeId.isBlank() || destNodeId == "^all" || destNodeId == NodeId.BROADCAST.value) return null
        val dest = NodeId(destNodeId)
        val candidates = peerList.value.filter { it.nodeId != destNodeId }

        return when (val active = strategySelector.active) {
            is com.dtn.mesh.routing.ProphetStrategy -> {
                val cfg = active.config
                val myP = active.getDeliveryProbability(dest)
                val beliefs = candidates.map { peer ->
                    val pMeToNode = active.getDeliveryProbability(NodeId(peer.nodeId))
                    val pNodeToDest = active.peerDeliveryProbability(NodeId(peer.nodeId), dest)
                    val pathVia = pMeToNode * pNodeToDest
                    val gain = (1.0 - myP) * pMeToNode * pNodeToDest * cfg.betaTransitivity
                    RoutingNodeBelief(
                        nodeId = peer.nodeId,
                        displayName = peer.displayName(),
                        isOnline = peer.isOnline,
                        score = pathVia,
                        detail = "P(me→node) ${fmt(pMeToNode)} · P(node→dest) ${fmt(pNodeToDest)} · +transit ${fmt(gain)}",
                    )
                }.sortedByDescending { it.score }
                val best = beliefs.firstOrNull { it.score > 0.0 }
                RoutingExplanation(
                    dest = destNodeId,
                    strategy = active.name,
                    myScore = myP,
                    myScoreLabel = "P(me → dest)",
                    beliefs = beliefs,
                    bestNextHop = best?.nodeId,
                    bestNextHopName = best?.displayName,
                    bestScore = best?.score ?: 0.0,
                    bestScoreLabel = "P_path",
                    formulas = listOf(
                        FormulaItem(
                            "Direct encounter",
                            if (cfg.stabilityAware) "P(a,b) = P + (1−δ−P)·P_enc·q   (q = link quality)"
                            else "P(a,b) = P + (1−δ−P)·P_enc   (P_enc adaptive; caps at 1−δ)",
                        ),
                        FormulaItem("Transitivity", "P(a,c) += (1−P(a,c))·P(a,b)·P(b,c)·β"),
                        FormulaItem("Aging", "P(a,b) = P(a,b)·γ^k"),
                        FormulaItem("2-hop path", "P_path(via j) = P(me,j)·P(j,dest)"),
                    ),
                    constantsLine = "P_init=${fmt2(cfg.pEncounter)} · δ=${fmt2(cfg.pDelta)} · " +
                        "β=${fmt2(cfg.betaTransitivity)} · γ=${fmt2(cfg.gammaAging)} · " +
                        "I_typ=${cfg.typicalEncounterIntervalMs / 1000}s · path floor=${fmt2(cfg.pFloorPath)}",
                )
            }
            is com.dtn.mesh.routing.MaxPropStrategy -> {
                val cfg = active.config
                val fDest = active.getDeliveryProbability(dest) // f(me, dest)
                val beliefs = candidates.map { peer ->
                    val fPeer = active.getDeliveryProbability(NodeId(peer.nodeId)) // f(me, peer)
                    RoutingNodeBelief(
                        nodeId = peer.nodeId,
                        displayName = peer.displayName(),
                        isOnline = peer.isOnline,
                        score = fPeer,
                        detail = "f(me→node) ${fmt(fPeer)} · est. cost ${fmt(1.0 - fPeer)}",
                    )
                }.sortedByDescending { it.score }
                val best = beliefs.firstOrNull { it.score > 0.0 }
                RoutingExplanation(
                    dest = destNodeId,
                    strategy = "MAXPROP",
                    myScore = fDest,
                    myScoreLabel = "f(me → dest)",
                    beliefs = beliefs,
                    bestNextHop = best?.nodeId,
                    bestNextHopName = best?.displayName,
                    bestScore = best?.score ?: 0.0,
                    bestScoreLabel = "f",
                    formulas = listOf(
                        FormulaItem("Path likelihood", "f(i,j) = encounters(j) / Σ encounters"),
                        FormulaItem("Delivery cost", "cost(dest) = 1 − f(dest)  (lower = better)"),
                        FormulaItem("Transitivity", "f(i,k) ← peer f(j,k) · discount (if unknown)"),
                        FormulaItem("Recency decay", "count ← count · decay each cycle"),
                    ),
                    constantsLine = "discount=${fmt2(cfg.transitivityDiscount)} · " +
                        "recency decay=${fmt2(cfg.recencyDecayFactor)}",
                )
            }
            is com.dtn.mesh.routing.EpidemicStrategy -> {
                val beliefs = candidates.map { peer ->
                    RoutingNodeBelief(
                        nodeId = peer.nodeId,
                        displayName = peer.displayName(),
                        isOnline = peer.isOnline,
                        score = 1.0,
                        detail = "epidemic — every peer receives a copy",
                    )
                }
                val firstOnline = beliefs.firstOrNull { it.isOnline }
                RoutingExplanation(
                    dest = destNodeId,
                    strategy = "EPIDEMIC",
                    myScore = 1.0,
                    myScoreLabel = "flood (all peers)",
                    beliefs = beliefs,
                    bestNextHop = firstOnline?.nodeId,
                    bestNextHopName = firstOnline?.displayName,
                    bestScore = 1.0,
                    bestScoreLabel = "flood",
                    formulas = listOf(
                        FormulaItem("Rule", "forward every bundle to every encountered peer"),
                        FormulaItem("Dedup", "duplicates dropped by message-id at ingest"),
                        FormulaItem("Bound", "hop limit stops infinite re-flooding"),
                    ),
                    constantsLine = "no probability model — maximises delivery at maximum bandwidth",
                )
            }
            is com.dtn.mesh.routing.QLearningStrategy -> {
                // Q-learning decides per (message, carrier) from a 9-feature state. Show, for each
                // candidate, the averaged Q-values a fresh message would see when meeting it and the
                // greedy choice. Score = ΔQ = Q(forward) − Q(wait); positive ⇒ the policy forwards.
                val cfg = qEngine.config
                val beliefs = candidates.map { peer ->
                    val state = previewState(peer)
                    val (qf, qs) = qEngine.qValues(state)
                    RoutingNodeBelief(
                        nodeId = peer.nodeId,
                        displayName = peer.displayName(),
                        isOnline = peer.isOnline,
                        score = qf - qs,
                        detail = "Q(fwd) ${fmt(qf)} · Q(wait) ${fmt(qs)} → ${if (qf > qs) "FORWARD" else "WAIT"} · " +
                            "P ${fmt2(state.deliveryProbability)} · rssi ${if (peer.lastRssi <= -200) "—" else peer.lastRssi}",
                    )
                }.sortedByDescending { it.score }
                val best = beliefs.firstOrNull { it.score > 0.0 }
                RoutingExplanation(
                    dest = destNodeId,
                    strategy = "Q-LEARNING",
                    myScore = qEngine.currentEpsilon,
                    myScoreLabel = "ε (explore rate)",
                    beliefs = beliefs,
                    bestNextHop = best?.nodeId,
                    bestNextHopName = best?.displayName,
                    bestScore = best?.score ?: 0.0,
                    bestScoreLabel = "ΔQ",
                    formulas = listOf(
                        FormulaItem("Decision", "ε-greedy on (Q_A+Q_B)/2: FORWARD if Q(fwd) > Q(wait)"),
                        FormulaItem("Double-Q update", "Q_A(s,a) += α·[r + γ·Q_B(s′, argmax Q_A(s′,·)) − Q_A(s,a)]"),
                        FormulaItem("Reward", "+1.0 forward that delivered · +0.3 baseline · −0.3 expired"),
                        FormulaItem("State", "rssi · snr · enc freq · enc dur · P · ttl · buffer · success · dups (bucketed)"),
                    ),
                    constantsLine = "α=${fmt2(cfg.learningRate)} · γ=${fmt2(cfg.discountFactor)} · " +
                        "ε=${fmt2(qEngine.currentEpsilon)} (min ${fmt2(cfg.epsilonMin)}, decay ${cfg.epsilonDecay}) · " +
                        "updates=${qEngine.totalUpdates}",
                )
            }
            else -> null
        }
    }

    private fun fmt(v: Double): String = "%.3f".format(v)
    private fun fmt2(v: Double): String = "%.2f".format(v)

    /** Manually retry sending all buffered messages to online peers. */
    fun syncBuffer() {
        viewModelScope.launch {
            addLog("Manual sync — retrying buffered messages...")
            runCatching { orchestrator.triggerFlush() }
                .onFailure { Log.e(TAG, "triggerFlush failed", it) }
        }
    }

    /**
     * Force refresh online/offline status for all peers.
     * Runs an aggressive 3-second staleness check on the BLE transport — any peer whose
     * scan advertisements have stopped arriving in the last 3s gets marked offline
     * immediately. This catches the "peer's app is disconnected but their Bluetooth is
     * still on" scenario where cached scan records would otherwise keep them showing
     * green for up to 20 seconds.
     */
    fun refreshPeerStatus() {
        viewModelScope.launch {
            addLog("Refreshing peer status (3s aggressive check)...")
            val mgr = transport as? com.dtn.mesh.receiver.MultiTransportManager
            mgr?.forceStalenessCheck()
            // Also push any pending receipts / buffered messages while we're here.
            runCatching { orchestrator.triggerFlush() }
                .onFailure { Log.e(TAG, "triggerFlush failed on refresh", it) }
        }
    }

    /** Manually clear the buffer (force-drop all pending messages). Confirmed in the UI first. */
    fun clearBuffer() {
        viewModelScope.launch {
            val n = queueManager.clearBuffer()
            notifyUser("Cleared $n buffered bundle(s)")
        }
    }

    fun exportData() {
        viewModelScope.launch {
            addLog("Exporting research data...")
            val bundle = exporter.exportAll()
            val path = FileExportHelper.writeToStorage(getApplication(), bundle)
            _exportPath.value = path
            addLog(if (path != null) "Exported to: $path" else "Export FAILED")
        }
    }

    // Chat-bubble status is now driven by the persistent ChatDao log (updated from the
    // deliveredIds / terminalEvents collectors in init), so the old in-memory bubble mutators
    // (markChatDeliveredById / markChatTerminalById) are gone.

    // ── Groups ─────────────────────────────────────────────────────────────

    /** Create a new encrypted group; returns the invite string to share (QR). */
    fun createGroup(name: String, onReady: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                val g = groupKeyStore.createGroup(name.ifBlank { "group" })
                val key = groupKeyStore.key(g.channel) ?: ByteArray(0)
                val invite = groupKeyStore.inviteString(g.channel, g.name, key)
                notifyUser("Created group “${g.name}” — share its invite QR with members")
                onReady(invite)
            }.onFailure { notifyUser("Could not create group: ${it.message}") }
        }
    }

    /** Join a group from a scanned/pasted invite string. [onJoined] gets the channel to open. */
    fun joinGroupFromInvite(invite: String, onJoined: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val parsed = groupKeyStore.parseInvite(invite)
            if (parsed == null) { notifyUser("That group invite is invalid or damaged."); return@launch }
            when (val r = runCatching { groupKeyStore.joinGroup(parsed.channel, parsed.name, parsed.key) }
                .getOrElse { notifyUser("Could not join group: ${it.message}"); return@launch }) {
                is GroupKeyStore.JoinResult.Joined -> {
                    notifyUser("Joined group “${r.name}”")
                    onJoined(r.channel)
                }
                is GroupKeyStore.JoinResult.AlreadyMember -> {
                    notifyUser("You're already in “${r.name}”")
                    onJoined(r.channel)
                }
                is GroupKeyStore.JoinResult.Conflict -> notifyUser(
                    "Can't join “${parsed.name}”: your group “${r.existingName}” uses the same channel slot " +
                        "(${r.channel}). Leave “${r.existingName}” first, or ask for a newly created group.",
                )
            }
        }
    }

    /** Leave a group: forget its key (chat history stays on this phone). */
    fun leaveGroup(channel: Int) {
        viewModelScope.launch {
            val name = groupKeyStore.groups().firstOrNull { it.channel == channel }?.name ?: "group"
            groupKeyStore.leaveGroup(channel)
            if (_selectedChat.value == "grp:$channel") openChat(null)
            notifyUser("Left “$name”")
        }
    }

    /**
     * Route any scanned / uploaded QR to the right action: a group invite joins the group, an
     * identity QR adds + verifies the contact. [onOpenChat] receives the chat key to open.
     */
    fun handleScannedQr(text: String, onOpenChat: (String) -> Unit) {
        val t = text.trim()
        when {
            t.startsWith(GroupInvite.PREFIX) -> joinGroupFromInvite(t) { ch -> onOpenChat("grp:$ch") }
            t.startsWith(IdentityQr.PREFIX) -> importContactQr(t) { node -> if (node != null) onOpenChat(node) }
            else -> notifyUser("That QR code isn't a DTN contact or group invite.")
        }
    }

    /** Mark (or un-mark) a contact's safety number as verified in person. */
    fun setContactVerified(nodeId: String, verified: Boolean) {
        viewModelScope.launch {
            contactDao.setKeyVerified(nodeId, verified)
            notifyUser(if (verified) "Safety number marked as verified" else "Verification cleared")
        }
    }

    /** Surface a message from UI-only flows (e.g. an image without a QR code). */
    fun showMessage(message: String) = notifyUser(message)

    /** Invite string for an existing group (for re-sharing). */
    fun groupInvite(channel: Int, onReady: (String?) -> Unit) {
        viewModelScope.launch {
            val g = groupKeyStore.groups().firstOrNull { it.channel == channel }
            val key = groupKeyStore.key(channel)
            onReady(if (g != null && key != null) groupKeyStore.inviteString(channel, g.name, key) else null)
        }
    }

    // ── Identity QR (exchange keys in person without waiting for an announce) ──

    /** Our identity QR payload: public keys + name, so a contact can pin us directly. */
    fun identityQrString(): String = IdentityQr.build(identity.xPub, identity.edPub, displayName())

    /** Pin a contact from their scanned identity QR (marks them verified). Returns their node id. */
    fun importContactQr(text: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val parsed = IdentityQr.parse(text)
            if (parsed == null) {
                notifyUser("That contact QR is invalid or damaged.")
                onDone(null)
                return@launch
            }
            val nodeId = NodeId.fromNodeNum(DtnCrypto.deriveNodeNum(parsed.xPub, parsed.edPub))
            if (nodeId == identity.nodeId) {
                notifyUser("That's your own QR code.")
                onDone(null)
                return@launch
            }
            val ok = runCatching {
                contactDao.insertIfNew(com.dtn.mesh.database.entity.ContactEntity(nodeId = nodeId.value))
                contactDao.updateKeys(nodeId.value, parsed.xPub, parsed.edPub, parsed.name, System.currentTimeMillis())
                contactDao.setKeyVerified(nodeId.value, true)
                // Use the QR's name as a nickname only if the user hasn't chosen one already.
                val existing = contactDao.getByNodeId(nodeId.value)
                if (parsed.name.isNotBlank() && existing?.customName.isNullOrBlank()) {
                    contactDao.updateCustomName(nodeId.value, parsed.name)
                }
            }.isSuccess
            if (!ok) {
                notifyUser("Could not save that contact.")
                onDone(null)
                return@launch
            }
            val label = parsed.name.ifBlank { nodeId.value.takeLast(8) }
            val reach = if (nodeId.value in internetPeers.value) "online via internet" else
                "they'll show online when nearby or on the internet relay"
            notifyUser("Added $label — keys verified ($reach)")
            onDone(nodeId.value)
        }
    }

    // ── Settings actions ─────────────────────────────────────────────────────

    /**
     * Apply the Settings dialog in one go (nothing changes until Save — Cancel discards all
     * edits, including the relay switch). A changed broker reconnects a running relay.
     */
    fun saveSettings(name: String, brokerUrl: String, internetEnabled: Boolean) {
        viewModelScope.launch {
            val oldName = settings.displayNameOnce()
            val oldBroker = settings.brokerUrlOnce()
            val oldInternet = settings.internetEnabledOnce()
            if (name != oldName) {
                settings.setDisplayName(name)
                cachedName = name
                if (transport.isConnected) runCatching { outbound.sendAnnounce(displayName()) }
            }
            val broker = brokerUrl.trim()
            val brokerChanged = broker.isNotBlank() && broker != oldBroker
            if (brokerChanged) settings.setBrokerUrl(broker)
            when {
                internetEnabled != oldInternet -> setInternetEnabled(internetEnabled)
                brokerChanged && internetEnabled && transportStatus.value.isWanted(TransportKind.INTERNET) -> {
                    mgr?.disconnect(TransportKind.INTERNET)
                    mgr?.connect(TransportKind.INTERNET)
                    addLog("Internet relay reconnecting to $broker")
                }
            }
        }
    }

    private suspend fun setInternetEnabled(enabled: Boolean) {
        settings.setInternetEnabled(enabled)
        if (enabled) {
            addLog("Internet relay: connecting…")
            mgr?.connect(TransportKind.INTERNET)
        } else {
            mgr?.disconnect(TransportKind.INTERNET)
            addLog("Internet relay off")
        }
    }

    private fun addLog(entry: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _logEntries.value = listOf("[$timestamp] $entry") + _logEntries.value.take(99)
        Log.d(TAG, entry)
    }
}

/** A single chat message displayed in the chat views. */
data class ChatMessage(
    /** DTN message id — used to correlate delivery signals back to the UI bubble. */
    val msgId: String,
    val text: String,
    /** Sender id, or "me" for outgoing messages. */
    val from: String,
    /**
     * Conversation key. `null` for broadcasts (they live only in the broadcast chat).
     * For outgoing 1:1: destination node id.
     * For incoming 1:1: sender node id (the peer we're chatting with).
     */
    val toNodeId: String?,
    val isOutgoing: Boolean,
    /** Human label of [state] (kept as a string for simple displays). */
    val status: String,
    val time: String,
    val createdAtMs: Long = 0L,
    /** Unified delivery state (chat row + route journal), see [deliveryState]. */
    val state: DeliveryState = DeliveryState.QUEUED,
    /** Link that carried the last hop ("BLE", "Internet relay", …), blank if unknown. */
    val via: String = "",
    /** Group or broadcast conversation (several senders, no single receipt). */
    val isMultiParty: Boolean = false,
) {
    /** The "other side" of this chat — used by [DtnViewModel.filteredMessages]. */
    fun peerOf(): String? = toNodeId
}

/** A joined group/channel shown in the chat list. */
data class GroupInfo(val channel: Int, val name: String)

/** A peer we've seen. Sourced from the Room contacts table so it survives app restarts. */
data class PeerInfo(
    val nodeId: String,
    /** Reachable over a local radio right now (BLE / Wi-Fi Direct / hub). */
    val isOnline: Boolean,
    val lastSeenMs: Long,
    /** User-provided nickname; falls back to [longName], [announceName], then the truncated id. */
    val customName: String? = null,
    val longName: String? = null,
    /** Most recent RSSI in dBm from any transport (sentinel -200 = unknown). */
    val lastRssi: Int = -200,
    /** Present on the internet relay right now. */
    val internetOnline: Boolean = false,
    /**
     * Reached only through our LoRa hub right now (another phone registered at the same hub),
     * not over Bluetooth or Wi-Fi Direct. [isOnline] is true as well.
     */
    val hubOnly: Boolean = false,
    /** Name from the peer's signed announce / QR. */
    val announceName: String? = null,
    val lastSnr: Float = -100f,
    val totalEncounters: Int = 0,
    val totalEncounterDurationMs: Long = 0,
    val deliverySuccessCount: Int = 0,
    val deliveryAttemptCount: Int = 0,
    /** Safety number verified in person (QR scan or manual). */
    val keyVerified: Boolean = false,
    /** Hex safety-number fingerprint, null until we hold the peer's keys. */
    val fingerprintHex: String? = null,
) {
    /** Display label preferring the custom nickname, then longName, announce name, last 8 of the id. */
    fun displayName(): String =
        customName?.takeIf { it.isNotBlank() }
            ?: longName?.takeIf { it.isNotBlank() }
            ?: announceName?.takeIf { it.isNotBlank() }
            ?: nodeId.takeLast(8)

    /** Reachable by any transport right now. */
    val reachable: Boolean get() = isOnline || internetOnline

    /** We hold this peer's encryption keys (can send it end-to-end encrypted DMs). */
    val hasKeys: Boolean get() = fingerprintHex != null
}

/** The active strategy's score for one peer, e.g. ("P", "0.42", "delivery predictability"). */
data class PeerMetric(val label: String, val value: String, val hint: String)

/** Something that would stop a transport from working, shown before connecting. */
data class PreflightIssue(val kind: Kind, val title: String, val detail: String) {
    enum class Kind { PERMISSIONS, BLUETOOTH_OFF, WIFI_OFF, LOCATION_OFF }
}

/** One candidate carrier's score toward a destination, strategy-agnostic for the UI. */
data class RoutingNodeBelief(
    val nodeId: String,
    val displayName: String,
    val isOnline: Boolean,
    /** Primary ranking score for this carrier (PROPHET: P_path; MaxProp: f(me,node)). */
    val score: Double,
    /** Pre-formatted secondary detail line explaining how [score] was derived. */
    val detail: String,
)

/** A named formula shown in the "Formulas & constants" breakdown. */
data class FormulaItem(val label: String, val formula: String)

/** Full explanation of how the active strategy scores routes toward one destination. */
data class RoutingExplanation(
    val dest: String,
    val strategy: String,
    /** My own score toward the destination (PROPHET P(me,dest); MaxProp f(me,dest)). */
    val myScore: Double,
    val myScoreLabel: String,
    val beliefs: List<RoutingNodeBelief>,
    val bestNextHop: String?,
    val bestNextHopName: String?,
    val bestScore: Double,
    val bestScoreLabel: String,
    val formulas: List<FormulaItem>,
    val constantsLine: String,
)

/** Buffered message info for the Network dashboard. */
data class BufferedMsgInfo(
    val msgId: String,
    val origin: String,
    val dest: String,
    val hopCount: Int,
    val forwardCount: Int,
    val ttlMin: Long,
    val bufferedFor: Long,  // seconds
    val status: String,
)
