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
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.crypto.DtnCrypto
import com.dtn.mesh.crypto.GroupInvite
import com.dtn.mesh.crypto.IdentityQr
import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.ForwardingStateBuilder
import com.dtn.mesh.receiver.HubBeacon
import com.dtn.mesh.receiver.HubBeacons
import com.dtn.mesh.receiver.LinkInfo
import com.dtn.mesh.receiver.LinkScanTimes
import com.dtn.mesh.receiver.MeshConnectionState
import com.dtn.mesh.receiver.MeshTransport
import com.dtn.mesh.receiver.MultiTransportManager
import com.dtn.mesh.receiver.RadioStatusMonitor
import com.dtn.mesh.receiver.RelayTestResult
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.receiver.TransportStatus
import com.dtn.mesh.receiver.hubLabel
import com.dtn.mesh.ui.groups.InviteCheck
import com.dtn.mesh.ui.groups.InviteStatus
import com.dtn.mesh.routing.StrategySelector
import com.dtn.mesh.service.DiagLevel
import com.dtn.mesh.service.DiagLine
import com.dtn.mesh.service.DiagSource
import com.dtn.mesh.service.DiagnosticsLog
import com.dtn.mesh.service.DtnOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    private val diagnostics: DiagnosticsLog,
    private val contactKeys: com.dtn.mesh.service.ContactKeyManager,
    private val dataExport: com.dtn.mesh.export.DataExportManager,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DtnViewModel"
        /** Sentinel string used to represent the "broadcast to everyone" chat in the UI. */
        const val BROADCAST_CHAT_ID = "__broadcast__"
        /** Default message lifetime (4 hours). */
        const val DEFAULT_TTL_MS = 4 * 3_600_000L
        /** Conservative plaintext byte budget shown in the composer (actual check is server-side). */
        const val MAX_MSG_BYTES = 200
        /** Display names are cut to this many characters (the announce carries at most 48 bytes). */
        const val MAX_DISPLAY_NAME = 48
    }

    /** Aggregate state, recomputed from every transport (never a stale connect-time snapshot). */
    val connectionState: StateFlow<MeshConnectionState> = transport.connectionState

    /** Per-transport live state + what the user wants running (drives the link tiles, sheet and pages). */
    val transportStatus: StateFlow<TransportStatus> = transport.transportStatus

    /** Bluetooth / Wi-Fi / location / internet availability. */
    val radios: StateFlow<RadioStatusMonitor.Radios> = radioMonitor.radios

    /** Contacts currently present on the internet relay. */
    val internetPeers: StateFlow<Set<String>> = transport.internetPeers

    private val mgr: MultiTransportManager? get() = transport as? MultiTransportManager

    /** What each link is doing and which devices are on it (the link pages, `transport/{kind}`). */
    val linkInfo: StateFlow<Map<TransportKind, LinkInfo>> =
        mgr?.linkInfo ?: MutableStateFlow<Map<TransportKind, LinkInfo>>(emptyMap()).asStateFlow()

    /** Wi-Fi Direct's invitation-free groups need Android 10+. */
    val wifiDirectSupported: Boolean get() = mgr?.wifiDirectSupported ?: false

    // ── Link pages: hubs heard over Bluetooth, Join, rescans, relay test ──

    /** LoRa hubs whose Bluetooth beacon this phone hears, strongest first (LoRa hub › Available hubs). */
    val availableHubs: StateFlow<List<HubBeacon>> =
        mgr?.hubBeacons ?: MutableStateFlow<List<HubBeacon>>(emptyList()).asStateFlow()

    /** The hub we're connected to (null while not on one). */
    val connectedHubId: StateFlow<String?> = mgr?.connectedHubId ?: MutableStateFlow<String?>(null).asStateFlow()

    /** The exact hub Wi-Fi the hub link is asking for; null = any `DTN-HUB-*`. */
    val requestedHubSsid: StateFlow<String?> = mgr?.requestedHubSsid ?: MutableStateFlow<String?>(null).asStateFlow()

    /** Last-scan times for the link pages (cheap; the pages read it once a second). */
    fun linkScanTimes(): LinkScanTimes = mgr?.scanTimes() ?: LinkScanTimes()

    /** Available hubs › Join: switch the hub link on and ask for exactly that hub's Wi-Fi. */
    fun joinHub(hubId: String) {
        viewModelScope.launch {
            val ok = mgr?.joinHub(hubId) ?: false
            addLog("Join ${hubLabel(hubId)}: ${if (ok) "asked Android for ${HubBeacons.ssidFor(hubId)}" else "could not start"}",
                source = DiagSource.HUB)
            notifyUser(if (ok) "Joining ${hubLabel(hubId)} (${HubBeacons.ssidFor(hubId)})…" else "Couldn't start joining ${hubLabel(hubId)}")
            if (ok) runCatching { outbound.sendAnnounce(displayName()) }
        }
    }

    /**
     * A link page's Refresh: Bluetooth runs the 3 s staleness check (and drops quiet hub beacons);
     * Wi-Fi Direct starts discovery now (only while it isn't linked); the hub page forgets quiet
     * hubs and asks for a hub again when not on one; the relay retries now.
     */
    fun refreshLink(kind: TransportKind) {
        when (kind) {
            TransportKind.BLE -> refreshPeerStatus()
            TransportKind.WIFI_DIRECT -> {
                val started = mgr?.rescanWifiDirect() ?: false
                notifyUser(if (started) "Looking for Wi-Fi Direct phones now"
                else "Wi-Fi Direct isn't searching: it's off or linked (a scan would disturb the group)")
            }
            TransportKind.HUB -> {
                mgr?.retryHubScan()
                addLog("LoRa hub: retry scan", source = DiagSource.HUB)
            }
            TransportKind.INTERNET -> {
                mgr?.retryRelay()
                addLog("Internet relay: retry now", source = DiagSource.RELAY)
            }
        }
    }

    /**
     * Relay Configuration › Test connection against [url] (blank = the default broker). A
     * throwaway client on the IO dispatcher; the running relay is untouched.
     */
    suspend fun testRelayConnection(url: String): RelayTestResult =
        mgr?.testRelay(url.trim().ifBlank { SettingsRepository.DEFAULT_BROKER })
            ?: RelayTestResult(false, "—", 0L, "The relay isn't available in this build")

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

    /** The app-wide diagnostics log (levels + sources, newest first), for Developer Tools › Console. */
    val diagnosticsLines: StateFlow<List<DiagLine>> = diagnostics.lines

    /** Reactive buffered count straight from Room — always accurate. */
    val bufferedCount: StateFlow<Int> = queueManager.observeBufferedCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Every DATA bundle in the buffer (DMs + broadcast / group messages still spreading), for the
     * Queue tab and the Queued message page. The DM counters ([bufferedCount]) are unchanged.
     */
    val queueBundles: StateFlow<List<BufferedMsgInfo>> = queueManager.observeBufferedData()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The message journal's events (Event Log, Buffer History, the Queue and the message pages). */
    val lifecycleEntries: StateFlow<List<com.dtn.mesh.service.LifecycleEntry>> = lifecycleLog.entries

    /** Engine console (encounter history + per-strategy probability calc) for Developer Tools › Routing Engine. */
    val engineLog: StateFlow<List<String>> = lifecycleLog.console

    /** Per-strategy benchmark metrics for Developer Tools › Benchmarks. */
    val benchmarks: StateFlow<List<com.dtn.mesh.learning.BenchmarkStat>> = benchmarkTracker.flow

    /** Clear accumulated benchmark counters (start a fresh comparison run). */
    fun resetBenchmarks() = benchmarkTracker.reset()

    /** Per-message hop routes (the journal's route records). */
    val messageRoutes: StateFlow<List<com.dtn.mesh.service.MessageLifecycleLog.RouteRecord>> = lifecycleLog.routes

    // From the (app-wide) selector: the service can outlive this ViewModel, and with it the choice.
    private val _activeStrategy = MutableStateFlow(strategySelector.activeType.name)
    val activeStrategy: StateFlow<String> = _activeStrategy.asStateFlow()

    // ── Contacts: block, delete, unread, key changes (DataStore) ─────────────

    /** Blocked node ids (decision 13): hidden everywhere, DMs dropped after the receipt. */
    val blockedNodes: StateFlow<Set<String>> = settings.blockedNodes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Deleted contacts → when; hidden from Discovered until they announce after that. */
    val deletedContacts: StateFlow<Map<String, Long>> = settings.deletedContacts
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Chat key → last-read time, for the unread badges. */
    val lastRead: StateFlow<Map<String, Long>> = settings.lastRead
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val startedAtMs = System.currentTimeMillis()

    /** Chats without a last-read time count messages after this as unread (see [SettingsRepository.unreadBaseline]). */
    val unreadBaseline: StateFlow<Long> = settings.unreadBaseline.map { it ?: startedAtMs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, startedAtMs)

    /** Contact → when its safety number last changed, for the notice in its chat. */
    val keyChangeNotices: StateFlow<Map<String, Long>> = settings.keyChangedAt
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Discovered peers with online state, driven by the Room `contacts` table. This means:
     * - Offline peers stay in the list (so we can still target them; the message goes to buffer).
     * - Peers survive app restarts (Task 3).
     * - Custom nicknames (Task 4) flow through automatically.
     */
    val peerList: StateFlow<List<PeerInfo>> = combine(
        contactDao.observeAll(), transport.internetPeers, transport.hubPeers, transport.meshPeers,
        combine(linkInfo, settings.blockedNodes, settings.pendingKeyNodes) { l, b, p -> Triple(l, b, p) },
    ) { contacts, present, onHub, otherHubs, (links, blocked, pendingKeys) ->
            val me = identity.nodeId.value
            // Phones a link of ours ends at (BLE in range, Wi-Fi Direct group): "nearby".
            val bleIds = links[TransportKind.BLE]?.peers.orEmpty().mapTo(HashSet()) { it.nodeId }
            val wfdIds = links[TransportKind.WIFI_DIRECT]?.peers.orEmpty().mapTo(HashSet()) { it.nodeId }
            val direct = bleIds + wfdIds
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
                    meshHub = otherHubs[c.nodeId],
                    added = c.isAdded,
                    announceName = c.announceName,
                    lastSnr = c.lastSnr,
                    totalEncounters = c.totalEncounters,
                    totalEncounterDurationMs = c.totalEncounterDurationMs,
                    deliverySuccessCount = c.deliverySuccessCount,
                    deliveryAttemptCount = c.deliveryAttemptCount,
                    keyVerified = c.keyVerified,
                    fingerprintHex = if (c.xPub != null && c.edPub != null)
                        DtnCrypto.fingerprint(c.xPub, c.edPub).joinToString("") { "%02x".format(it) } else null,
                    onBle = c.nodeId in bleIds,
                    onWifiDirect = c.nodeId in wfdIds,
                    // Hubs become contact rows through their encounters ("HUB"): not phones.
                    isHub = c.shortName == "HUB",
                    blocked = c.nodeId in blocked,
                    keysChanged = c.nodeId in pendingKeys,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Active strategy's per-peer score (P / f / w / Q) for the contact page and the Routing Engine, refreshed periodically. */
    private val _peerMetrics = MutableStateFlow<Map<String, PeerMetric>>(emptyMap())
    val peerMetrics: StateFlow<Map<String, PeerMetric>> = _peerMetrics.asStateFlow()

    /** Last measured buffer occupancy, for the synchronous Q-learning explanation. */
    @Volatile private var lastOccupancy: Double = 0.0

    /**
     * Currently selected chat (kept in step with the `chat/{key}` destination by the shell).
     *   null  → no chat open
     *   [BROADCAST_CHAT_ID] → the broadcast conversation
     *   "grp:<channel>" → a group conversation
     *   any other value → the 1:1 conversation with that node id
     */
    private val _selectedChat = MutableStateFlow<String?>(null)
    val selectedChat: StateFlow<String?> = _selectedChat.asStateFlow()

    /**
     * All chat messages (sent + received), sourced from the persistent [ChatDao] chat log — the
     * plaintext conversation store, decoupled from the forwarding buffer. Outgoing messages are
     * written by [OutboundMessageService]; decrypted inbound messages by [DtnOrchestrator].
     */
    val receivedMessages: StateFlow<List<ChatMessage>> = combine(
        chatDao.observeAll(), lifecycleLog.routes, settings.blockedNodes, settings.groupInvited,
    ) { rows, routes, blocked, invited ->
            // Each bubble's state merges the chat row with the (persistent) route journal.
            val byId = routes.associateBy { it.msgId }
            // Each group's known members, for our group messages' "Received by 1 of 2".
            val posts = rows.filter { it.direction == ChatMessageEntity.Direction.IN && it.convKey.startsWith("grp:") }
                .map { it.channel to it.peerNodeId.orEmpty() }
            val members = GroupReceiptStatus.knownByChannel(invited, posts, routes, identity.nodeId.value.takeLast(8), blocked)
            // A blocked contact's group / broadcast posts are hidden (kept on the phone).
            rows.filter { r ->
                val multiParty = r.convKey.startsWith("grp:") || r.convKey == NodeId.BROADCAST.value
                !multiParty || r.direction == ChatMessageEntity.Direction.OUT ||
                    com.dtn.mesh.service.ContactRules.showPost(r.peerNodeId.orEmpty(), blocked)
            }.map { toChatMessage(it, byId[it.id.take(8)], members[it.channel].orEmpty()) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Internet relay status line (endpoint, attempt, last error) for Settings, Relay Configuration and the relay page. */
    val relayDiagnostics: StateFlow<String> =
        (transport as? MultiTransportManager)?.relayDiagnostics
            ?: MutableStateFlow("Relay unavailable").asStateFlow()

    /** What the internet relay is doing right now ([relayStatusText] names it on every surface). */
    val relayStatus: StateFlow<com.dtn.mesh.receiver.RelayStatus> =
        (transport as? MultiTransportManager)?.relayStatus
            ?: MutableStateFlow<com.dtn.mesh.receiver.RelayStatus>(com.dtn.mesh.receiver.RelayStatus.Off).asStateFlow()

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

    /** Discover mode (off by default): when on, everyone in reach and on the relay can see us. */
    val discoverable: StateFlow<Boolean> = settings.discoverable
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** DiMaxProp λ (distance weight) and Q-learning η (progress-reward scale). */
    val diMaxPropLambda: StateFlow<Double> = settings.diMaxPropLambda
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_LAMBDA)
    val qShapingEta: StateFlow<Double> = settings.qShapingEta
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_ETA)

    /**
     * First launch: null while loading, true when no display name was saved when the app started
     * (the First launch page shows), false otherwise. Read once, so clearing the name in Settings
     * doesn't bounce the user back to it mid-session.
     */
    private val _firstLaunch = MutableStateFlow<Boolean?>(null)
    val firstLaunch: StateFlow<Boolean?> = _firstLaunch.asStateFlow()

    init {
        viewModelScope.launch {
            _firstLaunch.value = runCatching { settings.displayNameOnce().isBlank() }.getOrDefault(false)
        }
    }

    /** First launch › Continue: save the name, then the shell opens on Network. */
    fun finishFirstLaunch(name: String) {
        val clean = name.trim().take(MAX_DISPLAY_NAME)
        if (clean.isEmpty()) return
        viewModelScope.launch {
            runCatching { applyDisplayName(clean) }.onFailure { Log.w(TAG, "first-launch name save failed", it) }
            addLog("First launch: display name saved", source = DiagSource.SYSTEM)
            _firstLaunch.value = false
        }
    }

    /** Settings › Profile › Display name (Save in its dialog): a new name is announced right away. */
    fun saveDisplayName(name: String) {
        viewModelScope.launch { runCatching { applyDisplayName(name.trim().take(MAX_DISPLAY_NAME)) } }
    }

    /** Settings › Internet relay switch: applies at once, like the Connection links switch. */
    fun setInternetRelay(on: Boolean) {
        viewModelScope.launch {
            setInternetEnabled(on)
            if (on) runCatching { outbound.sendAnnounce(displayName()) }
        }
    }

    /** Relay Configuration › Save changes: a changed broker reconnects a running relay. */
    fun saveBrokerUrl(url: String) {
        viewModelScope.launch {
            val broker = url.trim().ifBlank { SettingsRepository.DEFAULT_BROKER }
            if (broker == settings.brokerUrlOnce()) {
                notifyUser("No changes to save")
                return@launch
            }
            settings.setBrokerUrl(broker)
            if (transportStatus.value.isWanted(TransportKind.INTERNET)) {
                mgr?.disconnect(TransportKind.INTERNET)
                mgr?.connect(TransportKind.INTERNET)
                addLog("Internet relay reconnecting to $broker", source = DiagSource.RELAY)
            }
            notifyUser("Relay server saved")
        }
    }

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

        // Unread badges start counting from the first run of this version (old chats aren't "new").
        viewModelScope.launch { runCatching { settings.ensureUnreadBaseline(startedAtMs) } }

        // One identity per phone (decision 14): an install from before this build switched to the
        // id derived from ANDROID_ID (keys kept). Tell every contact the new id right away (and
        // everyone, with Discover on); the announces wait in the buffer until a link is up.
        viewModelScope.launch(Dispatchers.IO) {
            val old = identity.pendingIdSwitch() ?: return@launch
            val name = runCatching { settings.displayNameOnce() }.getOrDefault("")
                .ifBlank { "node-" + identity.nodeId.value.takeLast(4) }
            val n = runCatching { outbound.announceIdentityChange(name) }.getOrDefault(0)
            identity.clearIdSwitch()
            diagnostics.w(DiagSource.SYSTEM, "Node id changed ${old.takeLast(8)} → ${identity.nodeId.value.takeLast(8)} " +
                "(now derived from this phone's Android id, keys kept) · new id announced to $n contact(s)")
            delay(2_500L) // let the first screen subscribe to the snackbars
            notifyUser("This phone's node id is now ${identity.nodeId.value.takeLast(8)}. Contacts need this version and " +
                "must re-add you once (scan your QR).")
        }

        // NODE events: log only. Peer bookkeeping + inbound decryption + chat persistence are all
        // handled in the orchestrator now; the UI just observes the ChatDao / contacts tables.
        viewModelScope.launch {
            transport.nodeEvents.collect { event ->
                // Every scan hit (per peer every few seconds): DEBUG. Transitions are logged by the
                // orchestrator as presence lines.
                addLog("NODE: ${event.nodeId.value} online=${event.isOnline} rssi=${event.rssi}",
                    DiagLevel.DEBUG, DiagSource.PRESENCE)
            }
        }

        // Hop-level transport ACKs are informational only (never end-to-end delivery proof).
        viewModelScope.launch {
            transport.deliveryStatus.collect { event ->
                addLog("ACK: pktId=${event.meshPacketId} status=${event.status}", DiagLevel.DEBUG, DiagSource.ENGINE)
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
                        diagnostics.i(DiagSource.SYSTEM, "Relay service started (a link is switched on)")
                    } else {
                        com.dtn.mesh.service.DtnForegroundService.stop(getApplication())
                        orchestrator.stop()
                        diagnostics.i(DiagSource.SYSTEM, "Relay service stopped (every link is off)")
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

        // Log every transport state change (and relay diagnostics) to the diagnostics log (Console),
        // so what each link is doing — and why it isn't up — is visible on the phone.
        viewModelScope.launch {
            var last: TransportStatus? = null
            transport.transportStatus.collect { st ->
                val prev = last
                last = st
                if (prev == null) return@collect
                for (kind in TransportKind.entries) {
                    val before = prev.stateOf(kind)
                    val now = st.stateOf(kind)
                    if (before != now) addLog("${kind.label()}: ${before.name.lowercase()} → ${now.name.lowercase()}",
                        source = kind.diagSource())
                }
            }
        }
        // Relay state changes are logged by the relay itself (one line each, source "relay").

        // Radios switched on / off (Bluetooth, Wi-Fi, location, internet): the first thing to check
        // when a link won't come up.
        viewModelScope.launch {
            var last: RadioStatusMonitor.Radios? = null
            radios.collect { r ->
                val prev = last
                last = r
                fun onOff(b: Boolean) = if (b) "on" else "off"
                if (prev == null) {
                    diagnostics.i(DiagSource.SYSTEM, "Radios: Bluetooth ${onOff(r.bluetoothOn)} · Wi-Fi ${onOff(r.wifiOn)} · " +
                        "location ${onOff(r.locationOn)} · internet ${if (r.internetAvailable) "available" else "unavailable"}")
                    return@collect
                }
                if (prev.bluetoothOn != r.bluetoothOn) diagnostics.i(DiagSource.SYSTEM, "Bluetooth turned ${onOff(r.bluetoothOn)}")
                if (prev.wifiOn != r.wifiOn) diagnostics.i(DiagSource.SYSTEM, "Wi-Fi turned ${onOff(r.wifiOn)}")
                if (prev.locationOn != r.locationOn) diagnostics.i(DiagSource.SYSTEM, "Location turned ${onOff(r.locationOn)}")
                if (prev.internetAvailable != r.internetAvailable) {
                    diagnostics.i(DiagSource.SYSTEM, "Internet ${if (r.internetAvailable) "available" else "lost"}")
                }
            }
        }

        // Strategy-specific per-peer metric (contact page, Routing Engine). Routing tables aren't observable,
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
                is com.dtn.mesh.routing.DiMaxPropStrategy -> {
                    val e = active.edgeInfo(com.dtn.mesh.routing.DiMaxPropStrategy.ME_LABEL, p.nodeId)
                    PeerMetric("w", e?.let { fmt2(it.weight) } ?: "—",
                        if (e?.dNorm != null) "hop cost (1−f)+λ·d" else "hop cost 1−f (no distance)")
                }
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

    /**
     * Map a persisted chat row (+ its route journal record) to the UI model. [groupMembers] = the
     * known members of the row's group (8-character ids), for our own group messages' receipts.
     */
    private fun toChatMessage(
        e: ChatMessageEntity,
        route: com.dtn.mesh.service.MessageLifecycleLog.RouteRecord?,
        groupMembers: Set<String> = emptySet(),
    ): ChatMessage {
        val isOut = e.direction == ChatMessageEntity.Direction.OUT
        val multiParty = e.convKey.startsWith("grp:") || e.convKey == NodeId.BROADCAST.value
        val toNodeId = when {
            e.convKey.startsWith("grp:") -> e.convKey
            e.convKey == NodeId.BROADCAST.value -> null
            else -> e.peerNodeId ?: e.convKey
        }
        // Our own group message: who of its known members sent a receipt (never ROUTING_ACK). The
        // receipts live in its journal route: without one (older than the journal keeps) it's unknown.
        val ownGroup = isOut && e.convKey.startsWith("grp:") && route != null
        val members = if (ownGroup) GroupReceiptStatus.members(route, groupMembers) else emptyList()
        val group = if (ownGroup) GroupReceiptStatus.progress(members) else null
        val state = deliveryState(e.status, isOut, multiParty, route, group)
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
            members = members,
            group = group,
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
     * Link tap (tile / sheet row): stop a transport that is up or coming up, otherwise (off, or
     * wanted but failed) start / retry it. The internet link also persists the relay setting.
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

    /** A link's switch turned off (sheet row, link page): stop [kind]; it stays off until it's turned on again. */
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
        addLog("All permissions granted", source = DiagSource.SYSTEM)
    }

    fun onPermissionsDenied(denied: List<String>) {
        addLog("WARN: permissions denied: ${denied.joinToString()}", DiagLevel.WARN, DiagSource.SYSTEM)
        addLog("Some transports may not work without these permissions", DiagLevel.WARN, DiagSource.SYSTEM)
    }

    /**
     * Open a chat. Pass [BROADCAST_CHAT_ID] for the broadcast conversation, "grp:<channel>" for a
     * group, a node id for a 1:1 chat, or null to close it (the shell pops the chat page).
     */
    fun openChat(chatKey: String?) {
        _selectedChat.value = chatKey
        if (chatKey != null) {
            addLog("Opened chat: ${chatKey.takeLast(8)}")
        }
    }

    /**
     * Discover on: broadcast our name + keys to every phone in reach and on the relay (now, then
     * every 5 minutes). Off: only contacts get them; others add us by scanning our QR.
     */
    fun setDiscoverable(on: Boolean) {
        viewModelScope.launch {
            settings.setDiscoverable(on)
            if (on) {
                runCatching { outbound.sendAnnounce(displayName()) }
                notifyUser("Discoverable — phones nearby and on the internet relay can see you and add you")
            } else {
                notifyUser("Hidden — only your contacts see you. Others can add you by scanning your QR (My identity, top right)")
            }
        }
    }

    /** Move a discovered phone into the chat list, and send it our name + keys so it can reply. */
    fun addContact(nodeId: String) {
        viewModelScope.launch {
            runCatching { contactDao.markAdded(nodeId) }
            runCatching { settings.clearDeleted(nodeId) }
            runCatching { outbound.sendAnnounceTo(NodeId(nodeId), displayName()) }
            val name = peerList.value.firstOrNull { it.nodeId == nodeId }?.displayName() ?: nodeId.takeLast(8)
            notifyUser("Added $name to your chats")
            runCatching { orchestrator.triggerFlush() }
        }
    }

    /** Apply DiMaxProp λ and Q-learning η from Settings (both 0..1). */
    fun saveRoutingTuning(lambda: Double, eta: Double) {
        viewModelScope.launch {
            settings.setDiMaxPropLambda(lambda)
            settings.setQShapingEta(eta)
            addLog("Routing tuning: DiMaxProp λ=%.2f · Q-learning η=%.2f".format(lambda, eta))
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
                    // Whoever we message is a contact (keeps them in the chat list).
                    if (channel == 0 && dest != NodeId.BROADCAST) runCatching { contactDao.markAdded(dest.value) }
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
                is OutboundMessageService.Result.Held -> {
                    runCatching { contactDao.markAdded(dest.value) }
                    addLog("HELD ${result.chatId.take(8)} → ${dest.value.takeLast(8)} (${trimmed.length} chars): their keys " +
                        "changed — sent once you accept the new keys or re-scan their QR", source = DiagSource.CRYPTO)
                }
                OutboundMessageService.Result.NoKey ->
                    _composerError.value = if (channel > 0)
                        "You're no longer a member of this group (its key isn't on this phone)."
                    else
                        "No encryption key for this contact yet — scan each other's QR (My identity, top right) or wait for their announce."
                is OutboundMessageService.Result.TooLong ->
                    _composerError.value = "Message too long (max ${result.maxBytes} bytes for this chat)."
                OutboundMessageService.Result.Error ->
                    _composerError.value = "Could not queue message (duplicate or expired)."
            }
        }
    }

    /**
     * Select a routing protocol (Developer Tools › Routing & Tuning, the only switch). [typeName] is a
     * [StrategySelector.StrategyType] name. Applies to the next forwarding decision; not saved, so the
     * app starts on PRoPHET again.
     */
    fun setStrategy(typeName: String) {
        val type = runCatching { StrategySelector.StrategyType.valueOf(typeName) }
            .getOrDefault(StrategySelector.StrategyType.PROPHET)
        _activeStrategy.value = type.name
        if (type == strategySelector.activeType) return
        strategySelector.switchTo(type)
        addLog("Strategy set to ${type.name}", source = DiagSource.ROUTING)
        viewModelScope.launch { refreshPeerMetrics() }
    }

    /** Routing & Tuning › Reset to defaults: PRoPHET, λ 0.50, η 0.20 (λ / η saved). */
    fun resetRoutingDefaults() {
        setStrategy(StrategySelector.StrategyType.PROPHET.name)
        saveRoutingTuning(SettingsRepository.DEFAULT_LAMBDA, SettingsRepository.DEFAULT_ETA)
        notifyUser("Routing reset to the defaults: PRoPHET, λ 0.50, η 0.20")
    }

    /** The selected algorithm's formulas and live constants (Routing & Tuning › Formulas & constants). */
    fun algorithmFormulas(): AlgorithmFormulas? = formulasFor(strategySelector.active)

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
        val active = strategySelector.active
        val f = formulasFor(active) ?: return null

        return when (active) {
            is com.dtn.mesh.routing.ProphetStrategy -> {
                val cfg = active.config
                val myP = active.getDeliveryProbability(dest)
                val beliefs = candidates.map { peer ->
                    val pMeToNode = active.getDeliveryProbability(NodeId(peer.nodeId))
                    val pNodeToDest = active.peerDeliveryProbability(NodeId(peer.nodeId), dest)
                    val pathVia = pMeToNode * pNodeToDest
                    // RFC 6693 eq. 3: P(me,dest) becomes max(P, P(me,node)·P(node,dest)·β).
                    val gain = (pathVia * cfg.betaTransitivity - myP).coerceAtLeast(0.0)
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
                    formulas = f.formulas,
                    constantsLine = f.constantsLine,
                )
            }
            is com.dtn.mesh.routing.MaxPropStrategy -> {
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
                    formulas = f.formulas,
                    constantsLine = f.constantsLine,
                )
            }
            is com.dtn.mesh.routing.DiMaxPropStrategy -> {
                // Score = 1 / (1 + C(node, dest)): higher means the node is closer to the destination
                // in DiMaxProp cost. My own cost is shown as the reference a carrier must beat.
                val mine = active.costFromMe(destNodeId)
                val beliefs = candidates.map { peer ->
                    val theirs = active.costFromPeer(peer.nodeId, destNodeId)
                    val e = active.edgeInfo(com.dtn.mesh.routing.DiMaxPropStrategy.ME_LABEL, peer.nodeId)
                    RoutingNodeBelief(
                        nodeId = peer.nodeId,
                        displayName = peer.displayName(),
                        isOnline = peer.isOnline,
                        score = if (theirs.reachable) 1.0 / (1.0 + theirs.cost) else 0.0,
                        detail = "C(node→dest) ${if (theirs.reachable) fmt(theirs.cost) else "no path"} · " +
                            "me→node ${e?.let { fmt(it.weight) } ?: "—"}" +
                            (e?.dNorm?.let { " (d ${fmt2(it)})" } ?: " (no distance)"),
                    )
                }.sortedByDescending { it.score }
                val best = beliefs.firstOrNull { it.score > 0.0 }
                RoutingExplanation(
                    dest = destNodeId,
                    strategy = "DIMAXPROP",
                    // Shown as a score tile: 1/(1+C) like the carriers (0 = no known path).
                    myScore = if (mine.reachable) 1.0 / (1.0 + mine.cost) else 0.0,
                    myScoreLabel = if (mine.reachable) "1/(1+C) me" else "me: no path",
                    beliefs = beliefs,
                    bestNextHop = best?.nodeId,
                    bestNextHopName = best?.displayName,
                    bestScore = best?.score ?: 0.0,
                    bestScoreLabel = "1/(1+C)",
                    formulas = f.formulas,
                    constantsLine = f.constantsLine,
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
                    formulas = f.formulas,
                    constantsLine = f.constantsLine,
                )
            }
            is com.dtn.mesh.routing.QLearningStrategy -> {
                // Q-learning decides per (message, carrier) from a 9-feature state. Show, for each
                // candidate, the averaged Q-values a fresh message would see when meeting it and the
                // greedy choice. Score = ΔQ = Q(forward) − Q(wait); positive ⇒ the policy forwards.
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
                    formulas = f.formulas,
                    constantsLine = f.constantsLine,
                )
            }
            else -> null
        }
    }

    /**
     * One algorithm's formulas and its live constants, shared by the routing math ([explainRoute])
     * and Routing & Tuning. Null for a strategy without a model to show.
     */
    private fun formulasFor(active: com.dtn.mesh.routing.RoutingStrategy): AlgorithmFormulas? = when (active) {
        is com.dtn.mesh.routing.ProphetStrategy -> {
            val cfg = active.config
            AlgorithmFormulas(
                strategy = active.name,
                formulas = listOf(
                    FormulaItem(
                        "Direct encounter",
                        if (cfg.stabilityAware) "P(a,b) = P + (1−δ−P)·P_enc·q   (q = link quality)"
                        else "P(a,b) = P + (1−δ−P)·P_enc   (P_enc adaptive; caps at 1−δ)",
                    ),
                    FormulaItem("Transitivity", "P(a,c) = max(P(a,c), P(a,b)·P(b,c)·β)   (RFC 6693)"),
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
            AlgorithmFormulas(
                strategy = "MAXPROP",
                formulas = listOf(
                    FormulaItem("Path likelihood", "f(i,j) = encounters(j) / Σ encounters"),
                    FormulaItem("Delivery cost", "cost(dest) = 1 − f(dest)  (lower = better)"),
                    FormulaItem("Multi-hop", "C(me,k) = min over j of (1 − f(me,j)) + C(j,k)   (j reports C(j,k))"),
                    FormulaItem("Recency decay", "count ← count · decay each cycle"),
                ),
                constantsLine = "recency decay=${fmt2(cfg.recencyDecayFactor)}",
            )
        }
        is com.dtn.mesh.routing.DiMaxPropStrategy -> AlgorithmFormulas(
            strategy = "DIMAXPROP",
            formulas = listOf(
                FormulaItem("Hop cost", "w(u,v) = (1 − f(u,v)) + λ·d_norm(u,v)   (λ only where a distance is known)"),
                FormulaItem("Path cost", "C(path) = Σ w   — cheapest path by Dijkstra (all w ≥ 0)"),
                FormulaItem("Multi-hop", "a neighbour j also reports C(j,x) for nodes further away (distance vector)"),
                FormulaItem("Forward rule", "hand to B if C(B,dest) < C(me,dest), or B is my cheapest path's first hop"),
                FormulaItem("Distance", "d_norm = BLE distance from RSSI / ${active.config.bleRangeM.toInt()} m (clamped to 1)"),
            ),
            constantsLine = "λ=${fmt2(active.lambda)} · f = MaxProp meeting likelihood · " +
                "λ = 0 gives plain MaxProp",
        )
        is com.dtn.mesh.routing.EpidemicStrategy -> AlgorithmFormulas(
            strategy = "EPIDEMIC",
            formulas = listOf(
                FormulaItem("Rule", "forward every bundle to every encountered peer"),
                FormulaItem("Dedup", "duplicates dropped by message-id at ingest"),
                FormulaItem("Bound", "hop limit stops infinite re-flooding"),
            ),
            constantsLine = "no probability model — maximises delivery at maximum bandwidth",
        )
        is com.dtn.mesh.routing.QLearningStrategy -> {
            val cfg = qEngine.config
            AlgorithmFormulas(
                strategy = "Q-LEARNING",
                formulas = listOf(
                    FormulaItem("Decision", "ε-greedy on (Q_A+Q_B)/2: FORWARD if Q(fwd) > Q(wait); ties → DiMaxProp rule"),
                    FormulaItem("Double-Q update", "Q_A(s,a) += α·[r + γ·Q_B(s′, argmax Q_A(s′,·)) − Q_A(s,a)]"),
                    FormulaItem("Reward", "+1.0 forward that delivered · +0.3 baseline · −0.3 expired · " +
                        "η·(C(me,D) − C(relay,D)) per forward"),
                    FormulaItem("State", "rssi · enc freq · enc dur · P · ttl · buffer · success · dups · " +
                        "DiMaxProp C(relay,D) · hops · relay buffer (bucketed)"),
                ),
                constantsLine = "α=${fmt2(cfg.learningRate)} · γ=${fmt2(cfg.discountFactor)} · " +
                    "ε=${fmt2(qEngine.currentEpsilon)} (min ${fmt2(cfg.epsilonMin)}, decay ${cfg.epsilonDecay}) · " +
                    "η=${fmt2(qShapingEta.value)} · updates=${qEngine.totalUpdates}",
            )
        }
        else -> null
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

    /** Settings › Data & storage: buffer use and the on-phone log sizes (read on the IO dispatcher). */
    suspend fun storageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        val dir = getApplication<Application>().filesDir
        fun size(name: String) = runCatching { java.io.File(dir, name).length() }.getOrDefault(0L)
        val (used, max) = runCatching { queueManager.bufferUse() }.getOrDefault(0L to 0L)
        StorageInfo(
            bufferBytes = used,
            bufferMaxBytes = max,
            journalBytes = size(com.dtn.mesh.service.MessageLifecycleLog.JOURNAL_FILE),
            diagnosticsBytes = size(DiagnosticsLog.FILE_NAME) + size(DiagnosticsLog.FILE_NAME + ".1"),
        )
    }

    /** Manually clear the buffer (force-drop all pending messages). Confirmed in the UI first. */
    fun clearBuffer() {
        viewModelScope.launch {
            val n = queueManager.clearBuffer()
            notifyUser("Cleared $n buffered bundle(s)")
        }
    }

    /**
     * Writes one message's event details ([text]: journal events, ids, links, hops and times —
     * never the message text) to `cache/events/` for the share sheet. Null if it couldn't be written.
     */
    suspend fun writeMessageEventsFile(msgId8: String, text: String): java.io.File? {
        val name = msgId8.filter { it.isLetterOrDigit() }.take(16).ifEmpty { "message" }
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(getApplication<Application>().cacheDir, "events").apply { mkdirs() }
                java.io.File(dir, "dtn-message-$name.txt").also { it.writeText(text) }
            }.onFailure { Log.w(TAG, "message event export failed", it) }.getOrNull()
        }
        if (file != null) diagnostics.i(DiagSource.UI, "MSG $name: event details exported (${text.lines().size} lines)")
        else diagnostics.w(DiagSource.UI, "MSG $name: event details export failed")
        return file
    }

    /**
     * Developer Tools › Event Log › Export events: writes [text] (the filtered events: ids, links,
     * hops and times — never message text) to `cache/events/` for the share sheet. Null on failure.
     */
    suspend fun writeEventLogFile(text: String, events: Int): java.io.File? {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(getApplication<Application>().cacheDir, "events").apply { mkdirs() }
                java.io.File(dir, "dtn-events-$stamp.txt").also { it.writeText(text) }
            }.onFailure { Log.w(TAG, "event log export failed", it) }.getOrNull()
        }
        if (file != null) diagnostics.i(DiagSource.UI, "Event Log: $events event(s) exported")
        else diagnostics.w(DiagSource.UI, "Event Log: export failed")
        return file
    }

    // ── Developer Tools › Export Data (files under exports/, written on the IO dispatcher) ──

    /**
     * Writes the switched-on datasets into a new export folder; timestamped ones are limited to
     * [range]. Runs in the ViewModel's scope, so leaving the page doesn't cut an export short.
     */
    fun startExport(
        selection: com.dtn.mesh.export.ExportSelection,
        range: com.dtn.mesh.export.ExportRange,
    ): kotlinx.coroutines.Deferred<com.dtn.mesh.export.ExportResult> = viewModelScope.async { dataExport.generate(selection, range) }

    /** Export all logs: the diagnostics log (+ backup), engine console, message journal and this app's logcat. */
    fun startExportAllLogs(): kotlinx.coroutines.Deferred<com.dtn.mesh.export.ExportResult> =
        viewModelScope.async { dataExport.exportAllLogs() }

    /** The export folders, newest first. */
    suspend fun exportHistory(): List<com.dtn.mesh.export.ExportFolder> = dataExport.history()

    /** Zips one export folder for Share; null if it couldn't be written. */
    suspend fun zipExport(folder: com.dtn.mesh.export.ExportFolder): java.io.File? = dataExport.zip(folder)

    // Chat-bubble status is now driven by the persistent ChatDao log (updated from the
    // deliveredIds / terminalEvents collectors in init), so the old in-memory bubble mutators
    // (markChatDeliveredById / markChatTerminalById) are gone.

    // ── Groups ─────────────────────────────────────────────────────────────

    /**
     * Create group (decision 10): a new group named [name] (capped so its invite fits one DM
     * frame), then the invite as an ordinary end-to-end encrypted DM to each of [members] (contacts
     * with keys). They see a Join group card and join only by tapping it. No members = today's
     * flow (share the invite QR). [onCreated] gets the new channel. Only ids and counts are logged:
     * the invite carries the group key.
     */
    fun createGroupWithInvites(name: String, members: List<String>, onCreated: (Int?) -> Unit) {
        viewModelScope.launch {
            val clean = GroupInvite.cleanName(name).ifBlank { "group" }
            val created = runCatching {
                val g = groupKeyStore.createGroup(clean)
                val key = groupKeyStore.key(g.channel) ?: error("the new group's key is unavailable")
                g to groupKeyStore.inviteString(g.channel, g.name, key)
            }.getOrElse {
                notifyUser("Could not create group: ${it.message}")
                onCreated(null)
                return@launch
            }
            val (group, invite) = created
            var queued = 0
            val failed = mutableListOf<String>()
            val invited = mutableListOf<String>()
            for (node in members.distinct()) {
                when (runCatching { outbound.sendText(invite, NodeId(node), 0, DEFAULT_TTL_MS) }.getOrNull()) {
                    is OutboundMessageService.Result.Sent, is OutboundMessageService.Result.Held -> {
                        queued++
                        invited += node
                        runCatching { contactDao.markAdded(node) }
                    }
                    else -> failed += node
                }
            }
            // The invited members are this group's known members for its receipts ("Received by 1 of 2").
            runCatching { settings.addGroupInvited(group.channel, invited) }
            if (queued > 0) runCatching { orchestrator.triggerFlush() }
            addLog("Created group on channel ${group.channel} · invite DM to ${members.size} member(s): " +
                "$queued queued, ${failed.size} not sent", source = DiagSource.CRYPTO)
            val names = failed.map { id -> peerList.value.firstOrNull { it.nodeId == id }?.displayName() ?: id.takeLast(8) }
            notifyUser(when {
                members.isEmpty() -> "Created group “${group.name}” — share its invite QR with members"
                failed.isEmpty() -> "Created “${group.name}” · invite sent to $queued contact${if (queued == 1) "" else "s"}"
                else -> "Created “${group.name}” · invite sent to $queued; couldn't send to ${names.joinToString()}"
            })
            onCreated(group.channel)
        }
    }

    /**
     * Whether this phone can join [invite] (a Join group card): already a member (same key),
     * blocked by another local group on that channel slot (today's conflict rule), or joinable.
     */
    suspend fun inviteStatus(invite: String): InviteCheck = withContext(Dispatchers.IO) {
        val parsed = groupKeyStore.parseInvite(invite) ?: return@withContext InviteCheck(InviteStatus.INVALID)
        val existing = runCatching { groupKeyStore.groups().firstOrNull { it.channel == parsed.channel } }.getOrNull()
            ?: return@withContext InviteCheck(InviteStatus.JOINABLE)
        val key = runCatching { groupKeyStore.key(parsed.channel) }.getOrNull()
        when {
            key != null && key.contentEquals(parsed.key) -> InviteCheck(InviteStatus.MEMBER)
            // A row whose key can't be unwrapped any more is replaced on join (GroupKeyStore).
            key == null -> InviteCheck(InviteStatus.JOINABLE)
            else -> InviteCheck(InviteStatus.CONFLICT, existing.name)
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
            runCatching { settings.clearGroupInvited(channel) }
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

    /** Our identity QR payload: node id + public keys + name, so a contact can pin us directly. */
    fun identityQrString(): String = IdentityQr.build(identity.nodeNum32, identity.xPub, identity.edPub, displayName())

    /**
     * Pin a contact from their scanned identity QR (marks them verified, replacing any keys held
     * or waiting for review). Scanning a blocked or deleted contact's QR adds them back. Returns
     * their node id.
     */
    fun importContactQr(text: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val parsed = IdentityQr.parse(text)
            if (parsed == null) {
                notifyUser("That contact QR is invalid or damaged.")
                onDone(null)
                return@launch
            }
            val nodeId = NodeId.fromNodeNum(parsed.nodeNum)
            if (nodeId == identity.nodeId || parsed.xPub.contentEquals(identity.xPub)) {
                notifyUser("That's your own QR code.")
                onDone(null)
                return@launch
            }
            val wasBlocked = nodeId.value in blockedNodes.value
            var keysChanged = false
            val ok = runCatching {
                keysChanged = contactKeys.applyScannedKeys(nodeId.value, parsed.xPub, parsed.edPub, parsed.name)
                contactDao.markAdded(nodeId.value)
                settings.clearDeleted(nodeId.value)
                if (wasBlocked) settings.setBlocked(nodeId.value, false)
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
            // They need our keys too, to reply: send them our identity directly.
            runCatching { outbound.sendAnnounceTo(nodeId, displayName()) }
            // DMs that waited for their key change go out now, sealed to the scanned keys.
            val resent = runCatching { outbound.resendHeld(nodeId, DEFAULT_TTL_MS) }.getOrDefault(0)
            if (resent > 0) addLog("Sent $resent held message(s) to ${nodeId.value.takeLast(8)} after the QR re-scan", source = DiagSource.CRYPTO)
            runCatching { orchestrator.triggerFlush() }
            val label = parsed.name.ifBlank { nodeId.value.takeLast(8) }
            val reach = if (nodeId.value in internetPeers.value) "online via internet" else
                "they'll show online when nearby or on the internet relay"
            notifyUser(when {
                wasBlocked -> "Unblocked and added $label — keys verified ($reach)"
                keysChanged -> "Updated $label's keys from their QR — verified"
                else -> "Added $label — keys verified ($reach)"
            })
            onDone(nodeId.value)
        }
    }

    // ── Delete contact / Block / key changes / unread ────────────────────────

    /**
     * Delete contact (asks first in the UI): their chat history goes and they leave the chat list
     * (`is_added = 0`), but the contact row stays — keys and routing stats — so relaying keeps
     * working and a later DM from them still decrypts (and adds them back). They're hidden from
     * Discovered until they announce after this moment.
     */
    fun deleteContact(nodeId: String) {
        viewModelScope.launch {
            val name = peerList.value.firstOrNull { it.nodeId == nodeId }?.displayName() ?: nodeId.takeLast(8)
            runCatching {
                chatDao.deleteConversation(nodeId)
                contactDao.markNotAdded(nodeId)
                settings.setDeleted(nodeId, System.currentTimeMillis())
                settings.clearLastRead(nodeId)
                // No direct announces to them any more (they're not a contact).
                messageDao.dropAnnouncesTo(identity.nodeId.value, nodeId)
            }.onFailure { Log.w(TAG, "delete contact failed", it) }
            if (_selectedChat.value == nodeId) openChat(null)
            addLog("Deleted contact ${nodeId.takeLast(8)} (chat removed, contact row kept for relaying)")
            notifyUser("Deleted $name — chat history removed")
        }
    }

    /**
     * Block / Unblock (asks first in the UI). Blocked: their DMs are acknowledged and dropped, no
     * direct announces go to them, their announces are ignored and their group posts hidden.
     * Relaying their bundles for others is unchanged. Unblocking sends them our keys again if
     * they're a contact.
     */
    fun setBlocked(nodeId: String, blocked: Boolean) {
        viewModelScope.launch {
            val peer = peerList.value.firstOrNull { it.nodeId == nodeId }
            val name = peer?.displayName() ?: nodeId.takeLast(8)
            runCatching { settings.setBlocked(nodeId, blocked) }
            if (blocked) {
                runCatching { messageDao.dropAnnouncesTo(identity.nodeId.value, nodeId) }
                if (_selectedChat.value == nodeId) openChat(null)
                addLog("Blocked ${nodeId.takeLast(8)}")
                notifyUser("Blocked $name — their messages won't be shown")
            } else {
                if (peer?.added == true) runCatching { outbound.sendAnnounceTo(NodeId(nodeId), displayName()) }
                addLog("Unblocked ${nodeId.takeLast(8)}")
                notifyUser("Unblocked $name")
            }
        }
    }

    /**
     * Accept a QR-verified contact's new keys ("Keys changed — Accept"): switch to them, clear
     * "verified", and send the DMs that waited.
     */
    fun acceptNewKeys(nodeId: String) {
        viewModelScope.launch {
            val ok = runCatching { contactKeys.acceptPendingKeys(nodeId) }.getOrDefault(false)
            if (!ok) return@launch
            val resent = runCatching { outbound.resendHeld(NodeId(nodeId), DEFAULT_TTL_MS) }.getOrDefault(0)
            if (resent > 0) runCatching { orchestrator.triggerFlush() }
            val name = peerList.value.firstOrNull { it.nodeId == nodeId }?.displayName() ?: nodeId.takeLast(8)
            notifyUser("Using $name's new keys — not verified. Scan their QR to verify." +
                if (resent > 0) " $resent waiting message(s) sent." else "")
        }
    }

    /** Everything in [chatKey] up to [upToMs] has been seen (opening a chat marks it read). */
    fun markChatRead(chatKey: String, upToMs: Long) {
        if (upToMs <= (lastRead.value[chatKey] ?: 0L)) return
        viewModelScope.launch { runCatching { settings.markRead(chatKey, upToMs) } }
    }

    // ── Settings actions ─────────────────────────────────────────────────────

    /** Save [name] if it changed; a new name goes out now, to everyone (Discover) or to each contact. */
    private suspend fun applyDisplayName(name: String) {
        if (name == settings.displayNameOnce()) return
        settings.setDisplayName(name)
        cachedName = name
        if (transport.isConnected) runCatching { outbound.sendAnnounce(displayName(), force = true) }
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

    /** An app line in the diagnostics log (Developer Tools › Console, source App unless [source] says otherwise). */
    private fun addLog(entry: String, level: DiagLevel = DiagLevel.INFO, source: String = DiagSource.UI) {
        diagnostics.log(level, source, entry)
        Log.d(TAG, entry)
    }

    private fun TransportKind.diagSource(): String = when (this) {
        TransportKind.BLE -> DiagSource.BLE
        TransportKind.WIFI_DIRECT -> DiagSource.WIFID
        TransportKind.HUB -> DiagSource.HUB
        TransportKind.INTERNET -> DiagSource.RELAY
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
    /** Our own group message: each known member and their receipt ([GroupReceiptStatus]); empty otherwise. */
    val members: List<MemberReceipt> = emptyList(),
    /** Our own group message: how many of [members] sent a receipt; null for every other message. */
    val group: GroupProgress? = null,
) {
    /** The "other side" of this chat — used by [DtnViewModel.filteredMessages]. */
    fun peerOf(): String? = toNodeId
}

/** A joined group/channel shown in the chat list. */
data class GroupInfo(val channel: Int, val name: String)

/** Settings › Data & storage: buffer use and the on-phone log files (bytes). */
data class StorageInfo(
    val bufferBytes: Long,
    val bufferMaxBytes: Long,
    val journalBytes: Long,
    val diagnosticsBytes: Long,
)

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
    /**
     * Id of the OTHER LoRa hub this peer is on, when our hub hears that hub over LoRa (null
     * otherwise). Reachable over LoRa through the hubs; [isOnline] stays false (no link of ours).
     */
    val meshHub: String? = null,
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
    /** In the chat list (QR, Add, or a message either way); otherwise only in "Discovered". */
    val added: Boolean = false,
    /** In Bluetooth range right now (a DTN phone our BLE link sees). */
    val onBle: Boolean = false,
    /** Linked in our Wi-Fi Direct group right now. */
    val onWifiDirect: Boolean = false,
    /** A LoRa hub (it became a contact row through its encounters), not a phone. */
    val isHub: Boolean = false,
    /** Blocked (decision 13): hidden from the lists, DMs dropped after the receipt. */
    val blocked: Boolean = false,
    /** QR-verified contact whose keys changed: new keys wait for Accept or a QR re-scan. */
    val keysChanged: Boolean = false,
) {
    /** Display label preferring the custom nickname, then longName, announce name, last 8 of the id. */
    fun displayName(): String =
        customName?.takeIf { it.isNotBlank() }
            ?: longName?.takeIf { it.isNotBlank() }
            ?: announceName?.takeIf { it.isNotBlank() }
            ?: nodeId.takeLast(8)

    /** Reachable by any transport right now (incl. over LoRa on another hub). */
    val reachable: Boolean get() = isOnline || internetOnline || meshHub != null

    /** Reachable only over LoRa through another hub right now (no own link, not on the relay). */
    val viaOtherHub: Boolean get() = !isOnline && !internetOnline && meshHub != null

    /** "hub BDB8" for [meshHub], matching that hub's Wi-Fi name. */
    val meshHubLabel: String get() = meshHub?.let { com.dtn.mesh.receiver.hubLabel(it) }.orEmpty()

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

/** One routing algorithm's formulas and live constants ([strategy] is its name, e.g. "MAXPROP"). */
data class AlgorithmFormulas(val strategy: String, val formulas: List<FormulaItem>, val constantsLine: String)

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

/** Buffered message info for the Queue and the Queued message page (ids are the last 8 characters). */
data class BufferedMsgInfo(
    val msgId: String,
    val origin: String,
    val dest: String,
    val hopCount: Int,
    val forwardCount: Int,
    val ttlMin: Long,
    val bufferedFor: Long,  // seconds
    val status: String,
    /** The full bundle id. */
    val fullId: String = "",
    /** Wire type name ("DATA", "ANNOUNCE", …): control traffic never shows in the Queue. */
    val messageType: String = "DATA",
    /** Group channel (0 = DM or public broadcast). */
    val channel: Int = 0,
    /** When the bundle entered this phone's buffer, and when its copy here expires (epoch ms). */
    val receivedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val createdAtMs: Long = 0L,
    /** Its lifetime when it was stored here (ms). */
    val ttlMs: Long = 0L,
)
