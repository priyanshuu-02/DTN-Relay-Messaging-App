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
import com.dtn.mesh.receiver.MeshConnectionState
import com.dtn.mesh.receiver.MeshTransport
import com.dtn.mesh.routing.StrategySelector
import com.dtn.mesh.service.DtnOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    val connectionState: StateFlow<MeshConnectionState> = transport.connectionState

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
    val peerList: StateFlow<List<PeerInfo>> = contactDao.observeAll()
        .map { contacts ->
            contacts.map { c ->
                PeerInfo(
                    nodeId = c.nodeId,
                    isOnline = c.isOnline,
                    lastSeenMs = c.lastEncounterMs,
                    customName = c.customName,
                    longName = c.longName,
                    lastRssi = c.lastRssi,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    val receivedMessages: StateFlow<List<ChatMessage>> = chatDao.observeAll()
        .map { rows -> rows.map { toChatMessage(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

        // End-to-end delivered (receipt arrived / direct delivery) → mark the chat row delivered.
        viewModelScope.launch {
            queueManager.deliveredIds.collect { msgId ->
                runCatching { chatDao.updateStatus(msgId, ChatMessageEntity.Status.DELIVERED) }
            }
        }

        // A message left the buffer without delivery (TTL expiry / housekeeping / manual clear).
        viewModelScope.launch {
            queueManager.terminalEvents.collect { ev ->
                val status = when (ev.reason) {
                    MessageQueueManager.REASON_EXPIRED -> ChatMessageEntity.Status.EXPIRED
                    else -> ChatMessageEntity.Status.DROPPED
                }
                runCatching { chatDao.updateStatus(ev.msgId, status) }
                if (ev.reason == MessageQueueManager.REASON_CLEARED) {
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
    }

    /** Short human name advertised in our announce: the user-set name, else a node-id short form. */
    private fun displayName(): String = cachedName.ifBlank { "node-" + identity.nodeId.value.takeLast(4) }

    /** Map a persisted chat row to the UI model. */
    private fun toChatMessage(e: ChatMessageEntity): ChatMessage {
        val isOut = e.direction == ChatMessageEntity.Direction.OUT
        val toNodeId = when {
            e.convKey.startsWith("grp:") -> e.convKey
            e.convKey == NodeId.BROADCAST.value -> null
            else -> e.peerNodeId ?: e.convKey
        }
        return ChatMessage(
            msgId = e.id,
            text = e.text,
            from = if (isOut) "me" else (e.peerNodeId ?: e.convKey),
            toNodeId = toNodeId,
            isOutgoing = isOut,
            status = mapChatStatus(e.status, isOut),
            time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(e.createdAtMs)),
        )
    }

    private fun mapChatStatus(status: String, isOut: Boolean): String = when (status) {
        ChatMessageEntity.Status.SENDING -> "sending…"
        ChatMessageEntity.Status.SENT -> "✓ sent"
        ChatMessageEntity.Status.BUFFERED -> "buffered"
        ChatMessageEntity.Status.DELIVERED -> if (isOut) "✓ delivered" else "✓ received"
        ChatMessageEntity.Status.RECEIVED -> "✓ received"
        ChatMessageEntity.Status.EXPIRED -> "expired"
        ChatMessageEntity.Status.DROPPED -> "dropped"
        ChatMessageEntity.Status.FAILED -> "failed"
        else -> status
    }

    // ── Actions ──────────────────────────────────────────────────────────

    fun connect() {
        viewModelScope.launch {
            val missing = missingPermissions()
            if (missing.isNotEmpty()) {
                addLog("WARNING: missing permissions (${missing.joinToString { it.substringAfterLast('.') }}) — " +
                    "grant them in system settings or transports may not work")
            }
            addLog("Starting all transports (BLE + WiFi Direct + LoRa hub)...")
            val success = transport.connect()
            if (success) {
                // Start the foreground service + orchestrator only AFTER transports come up, so a
                // permission failure can't leave a zombie service running with no radios.
                // start() is idempotent (the service's onCreate also calls it), so this is safe.
                com.dtn.mesh.service.DtnForegroundService.start(getApplication())
                orchestrator.start()
                addLog("Transports initiated — foreground service running")
                _localNodeId.value = identity.nodeId.value
                // Announce our identity/keys so peers can encrypt to us (TOFU pinning on their side).
                runCatching { outbound.sendAnnounce(displayName()) }
                // Bring up the internet relay if the user enabled it in settings.
                if (runCatching { settings.internetEnabledOnce() }.getOrDefault(false)) {
                    (transport as? com.dtn.mesh.receiver.MultiTransportManager)?.connectInternet()
                }
            } else {
                addLog("ERROR: Connection failed — check Bluetooth is on and permissions are granted")
            }
        }
    }

    /** Transport permissions that are not currently granted (for a clear warning before connect). */
    private fun missingPermissions(): List<String> {
        val ctx = getApplication<Application>()
        return PermissionHelper.requiredPermissions().filter {
            ctx.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    fun connectHubOnly() {
        viewModelScope.launch {
            addLog("Joining LoRa hub WiFi...")
            val mgr = transport as? com.dtn.mesh.receiver.MultiTransportManager
            val success = mgr?.connectHub() ?: transport.connect()
            if (success) {
                orchestrator.start()
                addLog("LoRa hub transport connecting")
            } else {
                addLog("ERROR: could not join hub — is a DTN-HUB in range?")
            }
        }
    }

    fun connectBleOnly() {
        viewModelScope.launch {
            addLog("Starting BLE discovery...")
            val mgr = transport as? com.dtn.mesh.receiver.MultiTransportManager
            val success = mgr?.connectBle() ?: false
            if (success) {
                orchestrator.start()
                addLog("BLE advertising + scanning started")
            } else {
                addLog("ERROR: BLE not available (is Bluetooth on?)")
            }
        }
    }

    fun connectWifiDirectOnly() {
        viewModelScope.launch {
            addLog("Starting WiFi Direct discovery...")
            val mgr = transport as? com.dtn.mesh.receiver.MultiTransportManager
            val success = mgr?.connectWifiDirect() ?: false
            if (success) {
                orchestrator.start()
                addLog("WiFi Direct discovery started")
            } else {
                addLog("ERROR: WiFi Direct not available")
            }
        }
    }

    fun disconnect() {
        com.dtn.mesh.service.DtnForegroundService.stop(getApplication())
        orchestrator.stop()
        transport.disconnect()
        // Once our transports are down we can't verify any peer's liveness. Clearing the
        // online flags avoids the classic reconnect bug: sendMessage sees a stale is_online=1
        // and tries to hand a bundle to a device we haven't rediscovered yet, so the message
        // silently buffers. Peers flip green again on the next real encounter after reconnect.
        viewModelScope.launch { contactDao.markAllOffline() }
        addLog("Disconnected — foreground service stopped")
    }

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
                    addLog("QUEUED: \"$trimmed\" → $target (${m.id.take(8)})")
                    lifecycleLog.log(com.dtn.mesh.service.LifecycleEvent.CREATED, m.id,
                        origin = m.originNodeId.value, dest = m.destinationNodeId.value, ttlRemainingMs = m.ttlMs)
                    lifecycleLog.log(com.dtn.mesh.service.LifecycleEvent.BUFFERED, m.id,
                        origin = m.originNodeId.value, dest = m.destinationNodeId.value)
                    lifecycleLog.recordAsOrigin(m.id, source = m.originNodeId.value,
                        dest = m.destinationNodeId.value, me = m.originNodeId.value)
                    runCatching { orchestrator.triggerFlush() }.onFailure { Log.e(TAG, "triggerFlush failed", it) }
                }
                OutboundMessageService.Result.NoKey ->
                    _composerError.value = "No encryption key for this contact yet — share your QR or wait for their announce."
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

    /** Current PROPHET P-values for display in the Network tab. */
    fun getProbabilities(): Map<String, Double> {
        return try {
            val prophet = strategySelector.active as? com.dtn.mesh.routing.ProphetStrategy
            prophet?.getPTable() ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    /** Manually clear the buffer (force-drop all pending messages). */
    fun clearBuffer() {
        viewModelScope.launch {
            val n = queueManager.clearBuffer()
            addLog("Cleared $n buffered message(s)")
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
                addLog("Created group '${g.name}' on channel ${g.channel}")
                onReady(invite)
            }.onFailure { _composerError.value = "Could not create group: ${it.message}" }
        }
    }

    /** Join a group from a scanned/pasted invite string. */
    fun joinGroupFromInvite(invite: String, onJoined: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val parsed = groupKeyStore.parseInvite(invite.trim())
            if (parsed == null) { _composerError.value = "Invalid group invite"; return@launch }
            val (channel, name, key) = parsed
            runCatching {
                groupKeyStore.joinGroup(channel, name, key)
                addLog("Joined group '$name' (channel $channel)")
                onJoined(channel)
            }.onFailure { _composerError.value = "Could not join group: ${it.message}" }
        }
    }

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
    fun identityQrString(): String {
        val x = android.util.Base64.encodeToString(identity.xPub, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        val ed = android.util.Base64.encodeToString(identity.edPub, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        return "dtnid:1:$x:$ed:${displayName()}"
    }

    /** Pin a contact from their scanned identity QR (marks them verified). Returns their node id. */
    fun importContactQr(text: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val node = runCatching {
                require(text.startsWith("dtnid:1:"))
                val parts = text.removePrefix("dtnid:1:").split(":", limit = 3)
                val xPub = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                val edPub = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                val name = parts.getOrNull(2) ?: ""
                require(xPub.size == 32 && edPub.size == 32)
                val nodeId = NodeId.fromNodeNum(com.dtn.mesh.crypto.DtnCrypto.deriveNodeNum(xPub, edPub))
                contactDao.insertIfNew(com.dtn.mesh.database.entity.ContactEntity(nodeId = nodeId.value))
                contactDao.updateKeys(nodeId.value, xPub, edPub, name, System.currentTimeMillis())
                contactDao.setKeyVerified(nodeId.value, true)
                if (name.isNotBlank()) contactDao.updateCustomName(nodeId.value, name)
                addLog("Verified contact ${nodeId.value.takeLast(8)} ($name) from QR")
                nodeId.value
            }.getOrNull()
            if (node == null) _composerError.value = "Invalid contact QR"
            onDone(node)
        }
    }

    // ── Settings actions ─────────────────────────────────────────────────────

    fun setDisplayName(name: String) {
        viewModelScope.launch {
            settings.setDisplayName(name)
            if (transport.isConnected) runCatching { outbound.sendAnnounce(name.ifBlank { displayName() }) }
        }
    }

    fun setBrokerUrl(url: String) { viewModelScope.launch { settings.setBrokerUrl(url.trim()) } }

    fun setInternetEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setInternetEnabled(enabled)
            val mgr = transport as? com.dtn.mesh.receiver.MultiTransportManager
            if (enabled) {
                addLog("Internet relay: connecting…")
                val ok = mgr?.connectInternet() ?: false
                addLog(if (ok) "Internet relay connected" else "Internet relay failed to connect")
            } else {
                mgr?.disconnectInternet()
                addLog("Internet relay disconnected")
            }
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
    val status: String,
    val time: String,
) {
    /** The "other side" of this chat — used by [DtnViewModel.filteredMessages]. */
    fun peerOf(): String? = toNodeId
}

/** A joined group/channel shown in the chat list. */
data class GroupInfo(val channel: Int, val name: String)

/** A peer we've seen. Sourced from the Room contacts table so it survives app restarts. */
data class PeerInfo(
    val nodeId: String,
    val isOnline: Boolean,
    val lastSeenMs: Long,
    /** User-provided nickname; falls back to [longName] then to the truncated id. */
    val customName: String? = null,
    val longName: String? = null,
    /** Most recent RSSI in dBm from any transport (sentinel -200 = unknown). */
    val lastRssi: Int = -200,
) {
    /** Display label preferring the custom nickname, then longName, then the last 8 of the id. */
    fun displayName(): String =
        customName?.takeIf { it.isNotBlank() }
            ?: longName?.takeIf { it.isNotBlank() }
            ?: nodeId.takeLast(8)
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
