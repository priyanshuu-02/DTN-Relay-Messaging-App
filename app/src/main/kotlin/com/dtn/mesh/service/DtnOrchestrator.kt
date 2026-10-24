package com.dtn.mesh.service

import android.util.Log
import com.dtn.mesh.crypto.Announce
import com.dtn.mesh.crypto.EnvelopeService
import com.dtn.mesh.database.dao.ChatDao
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.dao.EncounterDao
import com.dtn.mesh.database.dao.ForwardingDecisionDao
import com.dtn.mesh.database.dao.SeenBundleDao
import com.dtn.mesh.database.entity.ChatMessageEntity
import com.dtn.mesh.database.entity.ContactEntity
import com.dtn.mesh.database.entity.SeenBundleEntity
import com.dtn.mesh.database.entity.EncounterEntity
import com.dtn.mesh.database.entity.ForwardingDecisionEntity
import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.ForwardingState
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.ForwardingAction
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.queue.MessageQueueManager
import com.dtn.mesh.receiver.DeliveryOutcome
import com.dtn.mesh.receiver.DeliveryStatusEvent
import com.dtn.mesh.receiver.InboundPacket
import com.dtn.mesh.receiver.MeshTransport
import com.dtn.mesh.receiver.NodeEncounterEvent
import com.dtn.mesh.receiver.SendPath
import com.dtn.mesh.receiver.journalLabel
import com.dtn.mesh.routing.DiMaxPropStrategy
import com.dtn.mesh.routing.RadioDistanceEstimator
import com.dtn.mesh.routing.RoutingSummary
import com.dtn.mesh.routing.StrategySelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DTN Orchestrator — implements store-carry-forward with:
 *
 * 1. DIRECT DELIVERY: send to destination if in range
 * 2. CUSTODY TRANSFER: hand off to mule peers who relay toward the destination
 * 3. DELIVERY RECEIPTS: propagate "message X reached destination" so all carriers
 *    clear their buffers for that message.
 *
 * ## Receipt wire format
 * A receipt bundle is a normal DtnMessage with messageType = ROUTING_ACK and payload
 * containing UUID bytes (16 bytes each) concatenated. The wire header carries a fresh
 * random UUID as the bundle id; the payload is the LIST of message ids we've confirmed
 * delivered. If more receipts exist than fit in one bundle, we send multiple bundles.
 */
@Singleton
class DtnOrchestrator @Inject constructor(
    private val transport: MeshTransport,
    private val queueManager: MessageQueueManager,
    private val receiptStore: DeliveryReceiptStore,
    private val qEngine: DoubleQLearningEngine,
    private val contactDao: ContactDao,
    private val chatDao: ChatDao,
    private val seenBundleDao: SeenBundleDao,
    private val envelope: EnvelopeService,
    private val encounterDao: EncounterDao,
    private val decisionDao: ForwardingDecisionDao,
    private val strategySelector: StrategySelector,
    private val lifecycle: MessageLifecycleLog,
    private val benchmark: com.dtn.mesh.learning.BenchmarkTracker,
    private val localIdentity: com.dtn.mesh.model.LocalNodeIdentity,
    private val settings: com.dtn.mesh.settings.SettingsRepository,
    private val qStore: QTableStore,
    private val diagnostics: DiagnosticsLog,
    private val contactKeys: ContactKeyManager,
) {
    companion object {
        private const val TAG = "DtnOrchestrator"
        /** How long a dedup tombstone is kept for a message with no known absolute expiry. */
        private const val TOMBSTONE_RETENTION_MS = 24L * 3_600_000L
        /** Each UUID = 16 bytes; max receipts per bundle = MAX_SINGLE_PACKET_PAYLOAD / 16. */
        private const val UUID_BYTES = 16
        private const val MAX_RECEIPTS_PER_BUNDLE = DtnMessage.MAX_SINGLE_PACKET_PAYLOAD / UUID_BYTES // 13
        /**
         * If we sent a message to a peer but got no receipt within this window, allow a
         * retransmit on the next flush. Handles silent BLE drops without spamming the peer.
         */
        private const val SEND_RETRY_INTERVAL_MS = 30_000L

        /**
         * How often we proactively re-flush the buffer against online peers. Encounters flush
         * on arrival, but scan-based encounter events are debounced (and can be suppressed by
         * the OS during an active BLE link), so without this a message buffered just after a
         * peer came online could wait many seconds — or until the 15-min housekeeping worker —
         * before its first send attempt. A short tick makes forwarding feel immediate.
         * `recentlySentTo` (30s) still prevents re-spamming the same message to the same peer.
         */
        private const val PERIODIC_FLUSH_MS = 4_000L

        /**
         * Max peers we transmit to concurrently during a flush.
         *
         * Set to 1 (fully serial). On-device logs showed that opening multiple GATT client
         * connections at once — on top of our own GATT server + advertiser + scanner — overloads
         * the phone's BLE stack and produces `status=133` connect failures, which in turn dropped
         * links and made peers appear to go offline. Serialising sends is the stable choice; since
         * fix 3 BLE connects run in the background (a send only uses a link that is up), so the
         * head-of-line latency is a few writes, not a connect timeout. (Coalescing, backoff, and
         * receipt de-duplication from the multi-phone pass are retained.)
         */
        private const val MAX_CONCURRENT_SENDS = 1

        /**
         * After a slow failed send to a peer ([SLOW_SEND_FAILURE_MS]), skip it for this long instead
         * of paying the timeout again on every 4s flush. Cleared the moment any send to that peer
         * succeeds, a BLE link with it comes up, or it goes offline.
         */
        private const val CONNECT_BACKOFF_MS = 10_000L

        // Q-learning reward shaping (backfilled when a message's outcome becomes known).
        private const val REWARD_BASELINE_DELIVERED = 0.3  // any decision on a delivered msg
        private const val REWARD_FORWARD_DELIVERED = 1.0   // the FORWARD that reached the deliverer
        const val REWARD_EXPIRED = -0.3                    // message expired before delivery

        /** Saved Q-tables are rewritten at most this often (and on stop). */
        private const val Q_SAVE_DELAY_MS = 5_000L
        /**
         * A flush pass slower than this is written to the diagnostics log (timing). Since fix 3 no
         * send waits for a BLE connect, so a pass normally takes well under a second.
         */
        private const val SLOW_FLUSH_MS = 2_000L
        /**
         * A failed send that took at least this long backs its peer off ([CONNECT_BACKOFF_MS]). One
         * that failed at once (no link up yet: BLE connects in the background) costs nothing to
         * retry, and the link coming up flushes anyway.
         */
        private const val SLOW_SEND_FAILURE_MS = 1_000L
        /** "Q-tables saved" goes to the diagnostics log at most this often. */
        private const val Q_SAVE_LOG_INTERVAL_MS = 60_000L

        /**
         * The links a DATA DM takes in its peer's pass ([sendToSingle]): a phone a link of ours ends
         * at gets it over that link only (BLE, then Wi-Fi Direct at once if the BLE write fails); a
         * phone only on our hub through the hub; one only on the relay over the relay. Every other
         * link is the 5 s fallback's ([fallbackPass]), paced there.
         */
        private val DIRECT_TIER = setOf(SendPath.BLE, SendPath.WIFI_DIRECT)
        private val SAME_HUB_TIER = setOf(SendPath.HUB_LOCAL)
        private val RELAY_TIER = setOf(SendPath.INTERNET)

        /** A member's group receipts to one origin wait this long, so one DM carries several ids. */
        private const val GROUP_RECEIPT_BATCH_MS = 3_000L
    }

    /** Per-peer "held because…" summaries, logged only when they change (see [sendToSingle]). */
    private val heldTracker = HeldReasonTracker()
    /** peer → link it came online over, so presence is logged on transitions only (not every scan hit). */
    private val presenceLink = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var lastQSaveLogMs = 0L

    private fun logQSave(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastQSaveLogMs < Q_SAVE_LOG_INTERVAL_MS) return
        lastQSaveLogMs = now
        diagnostics.d(DiagSource.QLEARN, "Q-tables saved ($reason) · ε=${"%.3f".format(qEngine.currentEpsilon)} · " +
            "updates=${qEngine.totalUpdates}")
    }

    private sealed class OrchestratorEvent {
        data class Encounter(val event: NodeEncounterEvent) : OrchestratorEvent()
        data class Message(val packet: InboundPacket) : OrchestratorEvent()
        data class Status(val event: DeliveryStatusEvent) : OrchestratorEvent()
        data class QUpdateBatch(val decisions: List<ForwardingDecisionEntity>) : OrchestratorEvent()
        data object FlushBuffer : OrchestratorEvent()
        /** Apply any reward-assigned-but-unapplied Q decisions now (real-time training). */
        data object DrainQUpdates : OrchestratorEvent()
        /** Immediate DiMaxProp progress rewards for FORWARD decisions just made (paper eq. 13). */
        data class QShaping(val updates: List<Pair<ForwardingState, Double>>) : OrchestratorEvent()
    }

    /**
     * Nullable + `var` so start()/stop() can be called repeatedly without leaking state.
     * On stop() we close the channel and cancel the scope, then null them out. On the
     * next start() we allocate fresh instances. Public entry points use ?.trySend so a
     * send arriving between stop() and start() is silently dropped instead of crashing.
     */
    private var eventChannel: Channel<OrchestratorEvent>? = null
    private var scope: CoroutineScope? = null
    private var isRunning = false

    /**
     * When we last sent each (message, peer) pair. Not a boolean "already sent" flag any
     * more — a bare-boolean lockout meant that if BLE silently dropped a fire-and-forget
     * write, we'd never retry. Entries expire after [SEND_RETRY_INTERVAL_MS] so the next
     * flush cycle can retransmit if the receipt never came back.
     */
    private val sentTo = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Long>>()

    /**
     * msgId → every peer that handed us that bundle (the real previous hops, as reported by the
     * transport): the first copy and any later duplicate. Used to suppress the reverse-echo bug:
     * in an A→B→C→D chain, C must not hand the bundle straight back to B just because B isn't the
     * wire header's origin (A). One previous hop per message wasn't enough: a duplicate from
     * another carrier replaced it, and the real previous hop became eligible again (the multi-hop
     * simulation caught G sending a bundle back to B). Cleared alongside [sentTo] when the message
     * leaves our buffer.
     */
    private val receivedFrom = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * msgId → carriers that a BLE / Wi-Fi Direct write handed a mule copy to (acknowledged) this
     * session: [CarrierRules] doesn't re-offer it to them until they go offline and come back.
     * Cleared with [sentTo] (per message) and per peer when it goes offline.
     */
    private val handedOver = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** Reassembles multi-fragment messages for THIS node, keyed by canonical parent id. */
    private val reassembler = FragmentReassembler()

    /** msgId8 → the sender's routing algorithm read from a DM we just decrypted (for its route record). */
    private val senderAlgorithm = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * msgId → (link → when this phone last handed the DM over it), for the relay publish and the
     * same-hub hand-off: the 5 s fallback's 30 s retry windows ([DmFallback.Attempts]).
     */
    private val pathSentAt = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<SendPath, Long>>()

    /** msgId → failed writes to its destination over the links in its peer's pass (the fallback's log line). */
    private data class LinkFailures(val count: Int, val links: String)
    private val dmFailures = java.util.concurrent.ConcurrentHashMap<String, LinkFailures>()

    /** One pending flush for the 5 s fallback (the earliest one wins; see [scheduleFlushAt]). */
    private val fallbackFlushLock = Any()
    private var fallbackFlushAtMs = 0L
    private var fallbackFlushJob: kotlinx.coroutines.Job? = null
    /** One fallback pass at a time: the flush's own and the scheduled one ([runScheduledFallback]). */
    private val fallbackMutex = Mutex()

    /** Group receipts this phone owes origins, batched per (origin, channel) for [GROUP_RECEIPT_BATCH_MS]. */
    private val groupReceipts = GroupReceiptBatcher(GROUP_RECEIPT_BATCH_MS)

    /**
     * "$msgId|$peer" → the Q-learning choice for that pair, kept for [SEND_RETRY_INTERVAL_MS]. The
     * flush runs every 4 s; re-drawing an ε-greedy action each time (but recording only one per
     * 30 s) meant the forward that actually happened was often not the decision that got the reward.
     */
    private data class QChoice(val action: ForwardingAction, val exploratory: Boolean, val atMs: Long, val note: String)
    private val qChoice = java.util.concurrent.ConcurrentHashMap<String, QChoice>()

    /** η of the progress reward, kept in sync with Settings. */
    @Volatile private var shapingEta: Double = qEngine.config.shapingEta
    /** The saved Q-tables are loaded once per process (a stop/start must not roll them back). */
    @Volatile private var qLoaded = false
    /** Saves outlive [stop] (it cancels [scope]), so the last updates always reach the disk. */
    private val persistScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var qSaveJob: kotlinx.coroutines.Job? = null

    // ── Concurrency control for the send path (multi-phone hardening) ──
    /** Only one flush runs at a time; overlapping requests coalesce via [flushPending]. */
    private val flushMutex = Mutex()
    @Volatile private var flushPending = false
    /** Bounds how many peers we transmit to at once. */
    private val sendSemaphore = Semaphore(MAX_CONCURRENT_SENDS)
    /** Per-peer lock so concurrent flushes never interleave writes to the same GATT link. */
    private val peerLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    /** peer → epoch ms until which we skip it after a failed send (connect backoff). */
    private val connectBackoffUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** peer → receipt UIDs already delivered to it, so we don't re-send the whole set each flush. */
    private val receiptsSentTo = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    private fun peerLock(peer: String): Mutex = peerLocks.getOrPut(peer) { Mutex() }
    private fun isBackedOff(peer: String, now: Long): Boolean = now < (connectBackoffUntil[peer] ?: 0L)
    private fun markSendFailed(peer: String) { connectBackoffUntil[peer] = System.currentTimeMillis() + CONNECT_BACKOFF_MS }
    private fun markSendOk(peer: String) { connectBackoffUntil.remove(peer) }

    /**
     * [peer] is another phone on our LoRa hub and no link of ours ends at it (no BLE / Wi-Fi
     * Direct). The hub routes on the bundle's destination, so the only thing worth handing over
     * "for" such a phone is a bundle addressed to it; broadcasts and receipts reach it through the
     * hub's own fan-out, and custody for a third party would just be a hub flood.
     */
    private fun isHubOnly(peer: NodeId): Boolean = transport.isHubPeer(peer) && !transport.reachesDirectly(peer)

    /** What [CarrierRules.decide] asks about a bundle: receipts, send tracking, previous hops, links. */
    private val carrierFacts = object : CarrierRules.Facts {
        override fun delivered(msgId: String): Boolean = receiptStore.isDelivered(msgId)
        override fun cameFrom(msgId: String, peer: String): Boolean = receivedFrom[msgId]?.contains(peer) == true
        override fun sentThisSession(msgId: String, peer: String): Boolean = sentTo[msgId]?.containsKey(peer) == true
        override fun sentRecently(msgId: String, peer: String): Boolean = recentlySentTo(msgId, peer)
        override fun handedOverThisSession(msgId: String, peer: String): Boolean = handedOver[msgId]?.contains(peer) == true
        override fun reachesDirectly(peer: NodeId): Boolean = transport.reachesDirectly(peer)
        override fun reachableElsewhere(dest: NodeId): Boolean =
            transport.isRadioReachable(dest) || dest.value in transport.internetPeers.value
    }

    /** Signal a flush without blocking the caller (event loop stays responsive). Coalesced. */
    private fun requestFlush() {
        scope?.launch { flushToOnlinePeers() }
    }

    private fun markSentTo(msgId: String, peer: String) {
        sentTo.getOrPut(msgId) { java.util.concurrent.ConcurrentHashMap() }[peer] = System.currentTimeMillis()
    }

    /** True if we sent this msg to this peer recently and shouldn't yet retry. */
    private fun recentlySentTo(msgId: String, peer: String): Boolean {
        val last = sentTo[msgId]?.get(peer) ?: return false
        return System.currentTimeMillis() - last < SEND_RETRY_INTERVAL_MS
    }

    /** Drop tracking for a message that is no longer in our buffer (delivered / expired). */
    private fun forgetSentTo(msgId: String) {
        sentTo.remove(msgId)
        handedOver.remove(msgId)
        receivedFrom.remove(msgId)
        pathSentAt.remove(msgId)
        dmFailures.remove(msgId)
    }

    /** Remember a relay publish / same-hub hand-off of [msgId] for the fallback's retry windows. */
    private fun markPathSent(msgId: String, path: SendPath) {
        if (path == SendPath.INTERNET || path == SendPath.HUB_LOCAL) {
            pathSentAt.getOrPut(msgId) { java.util.concurrent.ConcurrentHashMap() }[path] = System.currentTimeMillis()
        }
    }

    private fun pathSentAtMs(msgId: String, path: SendPath): Long = pathSentAt[msgId]?.get(path) ?: 0L

    /** When this phone buffered [msg] (the fallback counts from it). */
    private fun queuedSince(msg: DtnMessage): Long = if (msg.bufferedAtMs > 0) msg.bufferedAtMs else msg.createdAtMs

    /**
     * Request the 5 s fallback at [atMs] (now if it's past), then a flush. Coalesced: one is pending
     * at a time and the earliest wins; each fallback pass schedules the next due DM itself
     * ([fallbackPass]).
     */
    private fun scheduleFlushAt(atMs: Long) {
        val sc = scope ?: return
        synchronized(fallbackFlushLock) {
            val now = System.currentTimeMillis()
            val at = maxOf(atMs, now)
            if (fallbackFlushJob?.isActive == true && fallbackFlushAtMs in now..at) return
            fallbackFlushJob?.cancel()
            fallbackFlushAtMs = at
            // A little past the due time, so the DM is surely "queued 5 s" when the pass looks. The
            // work gets its own coroutine: the pass schedules the next one, which cancels this job.
            fallbackFlushJob = sc.launch { delay(at - now + 50L); sc.launch { runScheduledFallback() } }
        }
    }

    /**
     * The scheduled 5 s fallback. It doesn't wait for a flush that is still busy: it only hands DMs
     * to the hub and the relay, whose transports serialise their own writes. A flush follows for
     * the direct links.
     */
    private suspend fun runScheduledFallback() {
        runCatching {
            val (radioPeers, relayPeers) = passTargets()
            val snapshot = queueManager.getAllBuffered()
            if (snapshot.isNotEmpty()) fallbackPass(snapshot, radioPeers, radioPeers.toSet() + relayPeers)
        }.onFailure { Log.w(TAG, "scheduled fallback failed", it) }
        requestFlush()
    }

    /**
     * A write of [msg] to its own destination over its pass's links ([links], e.g. "BLE") failed:
     * counted for the fallback's log line, and a flush is due when its 5 s are up.
     */
    private fun noteDmFailure(msg: DtnMessage, links: String) {
        dmFailures.merge(msg.id, LinkFailures(1, links)) { old, _ -> LinkFailures(old.count + 1, links) }
        scheduleFlushAt(DmFallback.dueAtMs(queuedSince(msg)))
    }

    /** "BLE", "BLE + Wi-Fi Direct": the links ending at [peer] now. */
    private fun directLinkNames(peer: NodeId): List<String> =
        transport.directLinks(peer).filter { it != SendPath.HUB }.map { it.label }

    /**
     * Drop tracking for every message we sent to [peer]. Called when a peer transitions
     * offline so that a subsequent reconnection retries immediately instead of waiting
     * out the SEND_RETRY_INTERVAL_MS — receipts might have been lost with the drop, and a
     * duplicate send is safe (the peer's ingest deduplicates by message id).
     */
    private fun forgetSentToPeer(peer: String) {
        for ((_, peers) in sentTo) peers.remove(peer)
        for ((_, peers) in handedOver) peers.remove(peer)
    }

    /**
     * Reload persisted PROPHET delivery predictability from the contacts table into the routing
     * strategy at startup. Without this, everything learned in a previous session was lost on
     * restart (the P-table is in-memory) and the DB column — now written on every encounter —
     * would only ever be read by the UI/export, never fed back into routing.
     */
    private suspend fun seedRoutingProbabilitiesFromDb() {
        runCatching {
            val seed = contactDao.getAll()
                .filter { it.deliveryProbability > 0.0 }
                .associate { it.nodeId to it.deliveryProbability }
            if (seed.isNotEmpty()) {
                strategySelector.seedProphetProbabilities(seed)
                Log.d(TAG, "Seeded PROPHET P for ${seed.size} contact(s) from DB")
            }
        }.onFailure { Log.w(TAG, "seed routing probabilities from DB failed", it) }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    fun start() {
        if (isRunning) return
        // Fresh channel + scope on every start — the previous ones (if any) were closed/cancelled.
        val ch = Channel<OrchestratorEvent>(capacity = Channel.BUFFERED)
        val sc = CoroutineScope(Dispatchers.IO + SupervisorJob())
        eventChannel = ch
        scope = sc
        isRunning = true
        Log.i(TAG, "Started")
        sc.launch { transport.nodeEvents.collect { ch.trySend(OrchestratorEvent.Encounter(it)) } }
        sc.launch { transport.inboundMessages.collect { ch.trySend(OrchestratorEvent.Message(it)) } }
        sc.launch { transport.deliveryStatus.collect { ch.trySend(OrchestratorEvent.Status(it)) } }
        // A BLE link just came up (fix 3: sends never wait for one): what waited for it goes now.
        sc.launch {
            transport.linkReady.collect { peer ->
                connectBackoffUntil.remove(peer.value)
                ch.trySend(OrchestratorEvent.FlushBuffer)
            }
        }
        // A buffered message that expired / was dropped / cleared is gone — drop its per-message
        // send-tracking so the sentTo map doesn't leak entries for messages we'll never resend.
        sc.launch {
            queueManager.terminalEvents.collect { ev ->
                forgetSentTo(ev.msgId)
                // Keep the route record honest about how the message ended.
                lifecycle.markRouteOutcome(ev.msgId,
                    if (ev.reason == MessageQueueManager.REASON_EXPIRED) "EXPIRED" else "DROPPED")
                // Expiry is journaled by the housekeeping worker and "Clear" by the UI; a buffer
                // pressure drop had no journal entry at all, so the timeline just stopped.
                if (ev.reason == MessageQueueManager.REASON_DROPPED && lifecycle.isTracked(ev.msgId)) {
                    lifecycle.log(LifecycleEvent.DROPPED, ev.msgId,
                        extra = "evicted — buffer full (lowest-priority bundles are dropped first)")
                }
                // Tombstone messages that reached a definitive end so a late re-flood can't
                // resurrect them after the buffer row is purged. Pressure drops are transient
                // (the message may still be deliverable elsewhere) so they get NO tombstone.
                val now = System.currentTimeMillis()
                when (ev.reason) {
                    MessageQueueManager.REASON_EXPIRED ->
                        rememberTombstone(ev.msgId, "", "", SeenBundleEntity.Status.EXPIRED, now + TOMBSTONE_RETENTION_MS)
                    MessageQueueManager.REASON_CLEARED ->
                        rememberTombstone(ev.msgId, "", "", SeenBundleEntity.Status.DROPPED, now + TOMBSTONE_RETENTION_MS)
                    else -> { /* pressure drop — re-acceptable, no tombstone */ }
                }
            }
        }
        // Internet presence: a contact appearing on the relay gets a flush right away (DMs for it,
        // receipts, and a one-time catch-up of buffered broadcasts). A contact leaving the relay
        // ends its "session", so the next appearance re-syncs — unless a radio still reaches it.
        sc.launch {
            var previous = emptySet<String>()
            transport.internetPeers.collect { present ->
                for (gone in previous - present) {
                    val radioOnline = runCatching { contactDao.getByNodeId(gone)?.isOnline }.getOrNull() == true
                    if (!radioOnline) {
                        forgetSentToPeer(gone)
                        receiptsSentTo.remove(gone)
                        connectBackoffUntil.remove(gone)
                        heldTracker.forget(gone)
                    }
                }
                if ((present - previous).isNotEmpty()) {
                    // A new relay session: the fallback may publish again right away.
                    pathSentAt.values.forEach { it.remove(SendPath.INTERNET) }
                    ch.trySend(OrchestratorEvent.FlushBuffer)
                }
                previous = present
            }
        }
        // Proactive short-interval flush so buffered messages reach online peers promptly
        // instead of waiting for the next (debounced/suppressed) encounter event.
        sc.launch {
            while (isActive) {
                kotlinx.coroutines.delay(PERIODIC_FLUSH_MS)
                ch.trySend(OrchestratorEvent.FlushBuffer)
            }
        }
        // Group receipts batched just before the last stop(): their send was cancelled with the old scope.
        if (groupReceipts.waiting > 0) {
            sc.launch {
                delay(GROUP_RECEIPT_BATCH_MS)
                runCatching { sendDueGroupReceipts() }.onFailure { Log.w(TAG, "group receipts failed", it) }
            }
        }
        // DiMaxProp λ and the Q-learning progress-reward η follow Settings.
        strategySelector.setSelfId(localIdentity.nodeId.value)
        sc.launch { settings.diMaxPropLambda.collect { strategySelector.diMaxProp.lambda = it } }
        sc.launch { settings.qShapingEta.collect { shapingEta = it } }
        sc.launch {
            // Restore learned PROPHET predictability from the DB BEFORE processing events, so a
            // restart doesn't begin from a blank routing table. Runs on this single consumer
            // coroutine so it can't race the pTable mutations that onEncounter performs.
            seedRoutingProbabilitiesFromDb()
            // Same for the Q-tables (and ε): before this they were lost on every restart.
            if (!qLoaded) {
                qLoaded = true
                if (qStore.load(qEngine)) {
                    lifecycle.console("Q-LEARNING: loaded saved tables (ε=%.3f, updates=${qEngine.totalUpdates})"
                        .format(qEngine.currentEpsilon))
                }
            }
            for (ev in ch) {
                try {
                    when (ev) {
                        is OrchestratorEvent.Encounter -> onPeerEvent(ev.event)
                        is OrchestratorEvent.Message -> onMessageReceived(ev.packet)
                        is OrchestratorEvent.Status -> onDeliveryStatus(ev.event)
                        is OrchestratorEvent.QUpdateBatch -> processBatchQUpdates(ev.decisions)
                        is OrchestratorEvent.FlushBuffer -> requestFlush()
                        is OrchestratorEvent.DrainQUpdates -> {
                            // Fetch fresh on the loop (not passed in) so concurrent drains can't
                            // double-apply; markApplied inside processBatchQUpdates dedups.
                            val pending = decisionDao.getPendingUpdates()
                            if (pending.isNotEmpty()) processBatchQUpdates(pending)
                        }
                        is OrchestratorEvent.QShaping -> {
                            for ((state, reward) in ev.updates) {
                                qEngine.update(state, ForwardingAction.FORWARD, reward, nextState = null)
                            }
                            scheduleQSave()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Handler error for $ev", e)
                }
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { eventChannel?.close() }
        runCatching { scope?.cancel() }
        eventChannel = null
        scope = null
        if (qLoaded) persistScope.launch { qStore.save(qEngine); lastQSaveLogMs = 0L; logQSave("on stop") }
        presenceLink.clear()
        Log.i(TAG, "Stopped")
    }

    /** Write the Q-tables to disk shortly (coalesced), off the event loop. */
    private fun scheduleQSave() {
        if (qSaveJob?.isActive == true) return
        qSaveJob = persistScope.launch {
            delay(Q_SAVE_DELAY_MS)
            qStore.save(qEngine)
            logQSave("after training")
        }
    }

    /** Non-suspending: uses trySend so callers never crash on a stopped orchestrator. */
    fun enqueueBatchQUpdates(d: List<ForwardingDecisionEntity>) {
        eventChannel?.trySend(OrchestratorEvent.QUpdateBatch(d))
    }

    /** Non-suspending: uses trySend so callers never crash on a stopped orchestrator. */
    fun triggerFlush() {
        eventChannel?.trySend(OrchestratorEvent.FlushBuffer)
    }

    // ══════════════════════════════════════════════════════════════════════
    // ENCOUNTER
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun onPeerEvent(event: NodeEncounterEvent) {
        val peerId = event.nodeId
        if (peerId == transport.localNodeId) return // never a contact of ourselves

        if (!event.isOnline) {
            // One link lost it, but another still reaches it (BLE went quiet while the phone is on
            // our hub, or it left the hub but is still in Bluetooth range): it hasn't gone anywhere.
            // Transports drop a peer from their reachable set before reporting it offline.
            if (transport.isRadioReachable(peerId)) {
                Log.d(TAG, "OFFLINE ignored: ${peerId.value.takeLast(8)} is still reachable over another link")
                return
            }
            contactDao.markOffline(peerId.value)
            encounterDao.getMostRecentEncounter(peerId.value)?.let { enc ->
                if (enc.endTimeMs == 0L) {
                    val closeAt = System.currentTimeMillis()
                    encounterDao.closeEncounter(enc.id, closeAt)
                    // Accumulate this window's length onto the contact so avg-duration stats
                    // (a Q-learning feature) actually populate.
                    contactDao.addEncounterDuration(peerId.value, (closeAt - enc.startTimeMs).coerceAtLeast(0L))
                }
            }
            queueManager.revertForwardingForPeer(peerId)
            // We don't know whether the peer received the messages we sent before dropping
            // — if the receipt was lost with the connection, we want to retry the moment
            // they reappear rather than sitting on the 30 s retry timer.
            forgetSentToPeer(peerId.value)
            // Drop per-peer send state so a fresh reconnection starts clean: clear any connect
            // backoff (we want to try immediately when they reappear) and the receipt-dedup set
            // (re-send receipts on reconnect in case the earlier ones were lost with the link).
            connectBackoffUntil.remove(peerId.value)
            receiptsSentTo.remove(peerId.value)
            peerLocks.remove(peerId.value)
            heldTracker.forget(peerId.value)
            presenceLink.remove(peerId.value)?.let { was ->
                diagnostics.i(DiagSource.PRESENCE, "${peerId.value.takeLast(8)} offline · lost on " +
                    "${event.transport.journalLabel()} (was online via $was) and no other link reaches it")
            }
            Log.d(TAG, "OFFLINE: ${peerId.value.takeLast(8)}")
            return
        }
        // NOTE: we intentionally do NOT clear connectBackoffUntil here. A peer can keep
        // appearing in scans (advertising) while still failing to accept a GATT connection;
        // clearing backoff on every scan encounter made us retry such a peer on every 4s flush,
        // thrashing the BLE radio. Backoff is cleared only by a genuinely successful send
        // (markSendOk) or by natural expiry after CONNECT_BACKOFF_MS.

        contactDao.insertIfNew(ContactEntity(nodeId = peerId.value))
        // Always flip to online on any live encounter. recordEncounter below also sets this,
        // but is gated by the `isNew` novelty check — without this, a peer that was marked
        // offline (by the staleness sweeper or by our own disconnect) can stay showing red
        // in the UI even while we're actively receiving from them.
        contactDao.markOnline(peerId.value)
        // Keep the RSSI signal bars fresh — recordEncounter only fires on novel encounters,
        // so without this, the UI's signal indicator would stick to a stale value. A phone seen
        // through the hub's roster has no signal of its own (-200): keep the last real reading.
        if (event.rssi > -200) contactDao.updateSignal(peerId.value, event.rssi, event.snr)
        if (event.longName != null || event.shortName != null) {
            contactDao.updateNodeInfo(peerId.value, event.longName, event.shortName, event.hwModel)
        }

        val now = System.currentTimeMillis()
        val lastEnc = encounterDao.getMostRecentEncounter(peerId.value)
        val isNew = lastEnc == null || lastEnc.endTimeMs != 0L ||
            (now - lastEnc.startTimeMs) > EncounterEntity.ENCOUNTER_GAP_THRESHOLD_MS
        if (isNew) {
            encounterDao.insert(EncounterEntity(
                peerNodeId = peerId.value, startTimeMs = now,
                bestRssi = event.rssi, bestSnr = event.snr,
            ))
            contactDao.recordEncounter(peerId.value, now, 0, event.rssi, event.snr)
        }

        Log.d(TAG, "ONLINE: ${peerId.value.takeLast(8)}")
        val link = event.transport.journalLabel()
        if (presenceLink.putIfAbsent(peerId.value, link) == null) {
            diagnostics.i(DiagSource.PRESENCE, "${peerId.value.takeLast(8)} online via $link" +
                if (event.rssi > -200) " · rssi ${event.rssi}" else "")
        }

        // A phone we only see in our hub's roster was never met: it's two hops away through the
        // hub, like a contact present on the internet relay. It must not train the encounter-based
        // models (PRoPHET P, MaxProp / DiMaxProp f), or they'd treat the hub as a direct meeting.
        if (event.transport == com.dtn.mesh.receiver.TransportKind.HUB && !transport.isHub(peerId)) {
            lifecycle.console("ON HUB ${peerId.value.takeLast(8)}: reachable through our LoRa hub (not an encounter)")
            requestFlush()
            return
        }

        // Feed the routing strategy so PROPHET's P-table (and MaxProp / DiMaxProp's likelihoods)
        // learn from this contact. Distance comes from the RSSI via the log-distance path-loss
        // model, and only for BLE: it is the one link with a real per-peer signal (scans). Wi-Fi
        // Direct and the hub report fixed placeholder values, so for them it stays unknown and
        // every distance term is skipped (DiMaxProp then uses plain MaxProp cost for that link).
        val measured = event.transport == com.dtn.mesh.receiver.TransportKind.BLE && event.rssi > -200
        val distanceM = if (measured) {
            RadioDistanceEstimator.estimateMeters(event.rssi, RadioDistanceEstimator.Transport.BLE)
        } else {
            RadioDistanceEstimator.UNKNOWN_DISTANCE
        }
        val activeStrat = strategySelector.active
        val pBefore = (activeStrat as? com.dtn.mesh.routing.ProphetStrategy)?.getDeliveryProbability(peerId)
        strategySelector.onEncounter(
            peerId = peerId,
            contactRecord = ContactRecord(
                peerId = peerId,
                startTimeMs = event.timestampMs,
                rssi = event.rssi,
                snr = event.snr,
                distanceMeters = distanceM,
                radio = if (distanceM >= 0.0) RadioDistanceEstimator.Transport.BLE else null,
            ),
        )
        // Persist the canonical PROPHET delivery predictability so the contacts table reflects it
        // (Network tab / export / DB queries) and so it can be reloaded on the next app start.
        // We always write the PROPHET value (both PROPHET variants learn on every encounter),
        // independent of which strategy is active, keeping the column's meaning stable.
        runCatching {
            contactDao.updateDeliveryProbability(
                peerId.value, strategySelector.prophetDeliveryProbability(peerId), now,
            )
        }.onFailure { Log.w(TAG, "persist delivery probability failed", it) }
        // ── Encounter-history + probability logging ──────────────────────────────────────
        // Show exactly what each routing model consumed to (re)compute this peer's score:
        // the encounter-history signals (novelty, gap since last contact, RSSI/SNR, distance)
        // and the resulting per-strategy value with the formula that produced it.
        val peer8 = peerId.value.takeLast(8)
        val sinceLastMs = if (lastEnc != null) now - lastEnc.startTimeMs else -1L
        lifecycle.console("ENCOUNTER $peer8 via ${event.transport.journalLabel()}: new=$isNew sinceLast=${sinceLastMs}ms " +
            "rssi=${event.rssi} snr=${event.snr} " +
            if (distanceM >= 0.0) "dist≈%.1fm".format(distanceM) else "dist=unknown")
        when (activeStrat) {
            is com.dtn.mesh.routing.ProphetStrategy -> {
                val cfg = activeStrat.config
                // Stability-aware variant modulates P_init by link quality; the classic variant
                // adds ε^d, which is why a close encounter can saturate P to ~1.0.
                lifecycle.console("  ${activeStrat.name} P(me→$peer8): %.3f → %.3f  [P_init=%.2f, %s]"
                    .format(pBefore ?: 0.0, activeStrat.getDeliveryProbability(peerId), cfg.pEncounter,
                        if (cfg.stabilityAware) "link-quality modulated" else "RFC δ=%.2f, adaptive I_typ=%.0fs".format(cfg.pDelta, cfg.typicalEncounterIntervalMs / 1000.0)))
            }
            is com.dtn.mesh.routing.MaxPropStrategy -> {
                // f(me,peer) = encounters(peer) / Σ encounters — more meetings ⇒ higher f.
                lifecycle.console("  MAXPROP f(me→$peer8)=%.3f  [encounters(peer)/Σencounters]"
                    .format(activeStrat.getDeliveryProbability(peerId)))
            }
            is com.dtn.mesh.routing.EpidemicStrategy -> {
                lifecycle.console("  EPIDEMIC: floods to every peer — no per-peer probability model")
            }
            is com.dtn.mesh.routing.DiMaxPropStrategy -> {
                val e = activeStrat.edgeInfo(com.dtn.mesh.routing.DiMaxPropStrategy.ME_LABEL, peerId.value)
                lifecycle.console("  DIMAXPROP me→$peer8: f=%.3f d=%s → hop cost %.3f  [(1−f) + λ·d, λ=%.2f]".format(
                    e?.f ?: 0.0, e?.dNorm?.let { "%.2f".format(it) } ?: "none", e?.weight ?: 1.0, activeStrat.lambda))
            }
        }

        // Exchange routing summaries so both sides can drive Stage-2 (smart-spread) decisions
        // based on the peer's own predictability table. Launched OFF the event loop (so a slow
        // write to this peer can't stall other peers' events) but under this peer's lock so it
        // serialises with the flush's writes to the same GATT link. A summary describes a 1-hop
        // neighbour, so only over a link that ends at the peer — not to a phone two hops away
        // through our hub, and not to the hub itself (it never reads them).
        if (transport.reachesDirectly(peerId) && !transport.isHub(peerId)) {
            scope?.launch {
                peerLock(peerId.value).withLock {
                    runCatching { sendRoutingSummary(peerId) }
                        .onFailure { Log.w(TAG, "routing summary to ${peerId.value.takeLast(8)} failed", it) }
                }
            }
        }

        // Receipts + buffered messages now flow through the coalesced, parallel flush (which
        // covers this peer and every other online peer). Signalled non-blocking.
        requestFlush()
    }

    /**
     * Push our current PROPHET P-vector (or MaxProp equivalent) to the peer so their
     * Stage-2 forwarding decisions have accurate `P(peer, dest)` values to compare against.
     * The summary is sent as an ephemeral [DtnMessageType.ROUTING_SUMMARY] bundle — it is
     * never persisted in the peer's forwarding buffer.
     */
    private suspend fun sendRoutingSummary(peerId: NodeId) {
        // DiMaxProp summaries carry our buffer occupancy (a Q-learning input on the other side).
        strategySelector.diMaxProp.localBufferOccupancy =
            runCatching { queueManager.getBufferOccupancy() }.getOrDefault(0.0)
        val summary = strategySelector.buildRoutingSummary()
        if (summary.payload.isEmpty()) return
        // Guard: the summary is a self-describing binary vector — truncating mid-entry
        // would give the peer a corrupt payload that fails to decode. Rather than send
        // garbage, skip until a smaller summary is available. A future improvement can
        // send top-K entries or fragment across multiple bundles.
        if (summary.payload.size > DtnMessage.MAX_SINGLE_PACKET_PAYLOAD) {
            Log.w(TAG, "Routing summary too large (${summary.payload.size} B) — skipping this cycle")
            return
        }
        val myId = transport.localNodeId ?: NodeId.LOCAL
        val bundle = DtnMessage(
            id = UUID.randomUUID().toString(),
            originNodeId = myId,
            destinationNodeId = peerId,
            payloadBytes = summary.payload,
            createdAtMs = System.currentTimeMillis(),
            ttlMs = 300_000L, // 5 min — short-lived control traffic
            messageType = DtnMessageType.ROUTING_SUMMARY,
        )
        // Direct links only (one hop): a BLE link that isn't up yet must not push it onto the relay
        // or a LoRa flood instead. The next encounter (≤ 8 s) sends it again.
        runCatching { transport.sendMessageVia(bundle, peerId, DIRECT_TIER) }
            .onFailure { Log.w(TAG, "routing-summary push to ${peerId.value.takeLast(8)} failed", it) }
    }

    /**
     * Flush receipts + buffered messages to all online peers, coalescing concurrent requests.
     *
     * At most one flush runs at a time. If a flush is requested while one is in flight, we set
     * [flushPending] and the running flush re-runs one more pass when it finishes — so bursty
     * triggers (RX + encounter + periodic tick all firing at once) collapse into a single
     * follow-up pass instead of stacking up. The actual per-peer work happens in [flushOnce].
     */
    private suspend fun flushToOnlinePeers() {
        // Coalesce: if a flush is already running, mark that another is wanted and return —
        // don't pile up overlapping flushes (which caused the "spends all its time flushing"
        // failure mode and double-sends under load).
        if (!flushMutex.tryLock()) {
            flushPending = true
            return
        }
        try {
            do {
                flushPending = false
                flushOnce()
            } while (flushPending)
        } finally {
            flushMutex.unlock()
        }
    }

    /**
     * The peers a flush runs a pass for: the radio-online contacts, and the contacts present on the
     * internet relay that no local radio reaches. A radio link always wins (normal DTN semantics incl.
     * custody); relay-only peers get relay semantics.
     */
    private suspend fun passTargets(): Pair<List<String>, List<String>> {
        val radioPeers = contactDao.getOnlineContacts().map { it.nodeId }
        val myId = transport.localNodeId?.value
        val radioSet = radioPeers.toSet()
        val relayPeers = transport.internetPeers.value
            .filter { it != myId && it !in radioSet }
            .filter { runCatching { contactDao.getByNodeId(it) }.getOrNull() != null }
        return radioPeers to relayPeers
    }

    /**
     * One flush pass: transmit receipts + buffered messages to every online peer, running peers
     * CONCURRENTLY (bounded by [sendSemaphore]) so one slow/unreachable peer can't stall the
     * others. Each peer's transmissions are serialised by a per-peer lock so writes to a single
     * GATT link never interleave.
     */
    private suspend fun flushOnce() {
        val (radioPeers, relayPeers) = passTargets()
        // Nobody to run a pass for. Phones present on the relay that aren't contacts still count:
        // the fallback publishes a DM carried for one of them to its own topic.
        if (radioPeers.isEmpty() && relayPeers.isEmpty() && transport.internetPeers.value.isEmpty()) return
        val now = System.currentTimeMillis()
        val radioSet = radioPeers.toSet()
        val onlineIds = radioSet + relayPeers
        val snapshot = queueManager.getAllBuffered()
        // msgId → set of peers we successfully fanned a broadcast to this pass (thread-safe).
        val broadcastsSentTo = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

        coroutineScope {
            val targets = radioPeers.map { it to false } + relayPeers.map { it to true }
            for ((pid, viaInternet) in targets) {
                if (isBackedOff(pid, now)) continue // skip recently-failed peers (no 8s stall)
                launch {
                    sendSemaphore.withPermit {
                        peerLock(pid).withLock {
                            // A phone we reach only through our hub gets receipts from the hub: it
                            // hands every receipt to all of its phones, so the copy sent to the hub
                            // covers them (one per phone would each be flooded over LoRa too).
                            if (!isHubOnly(NodeId(pid))) {
                                runCatching { sendDeliveryReceipts(NodeId(pid)) }
                                    .onFailure { Log.w(TAG, "receipt push to ${pid.takeLast(8)} failed", it) }
                            }
                            if (snapshot.isNotEmpty()) {
                                sendToSingle(NodeId(pid), snapshot, onlineIds, broadcastsSentTo, viaInternet)
                            }
                        }
                    }
                }
            }
        }

        // The 5 s fallback: DMs still queued also go to the hub and the relay (after the peers'
        // passes, so a DM delivered directly just now isn't sent again).
        if (snapshot.isNotEmpty()) {
            runCatching { fallbackPass(snapshot, radioPeers, onlineIds) }.onFailure { Log.w(TAG, "fallback pass failed", it) }
        }

        // Broadcasts / group messages are epidemic: they are NOT cleared after fan-out. They keep
        // propagating to peers met later (store-carry-forward for broadcast) until their TTL
        // expires, bounded by per-peer send throttling (recentlySentTo) + receiver-side dedup, and
        // reclaimed by TTL expiry or buffer pressure. Clearing a broadcast once every *currently*
        // online peer had it (the old behaviour) stranded peers that were offline at that instant.
        if (broadcastsSentTo.isNotEmpty()) {
            Log.d(TAG, "Broadcast fan-out this pass: ${broadcastsSentTo.size} msg(s) kept for epidemic spread")
        }
        val tookMs = System.currentTimeMillis() - now
        if (tookMs > SLOW_FLUSH_MS) {
            diagnostics.i(DiagSource.TIMING, "Slow flush pass: %.1f s · %d peer(s) · %d bundle(s)"
                .format(tookMs / 1000.0, radioPeers.size + relayPeers.size, snapshot.size))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SEND: deliver or relay buffered messages
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Send applicable messages from [snapshot] to a single peer.
     * - Direct delivery (dest == peer): send + mark delivered immediately (receipt generated).
     * - Broadcast: sent at most once per peer per encounter/presence session (and never back to
     *   the peer that gave it to us); receiver-side dedup makes a rare repeat harmless.
     * - Custody transfer (dest is someone else): send to mule, keep our copy for receipt.
     *
     * [viaInternet] = the peer is reachable only over the MQTT relay. Then: DMs addressed to it are
     * published and kept until the end-to-end receipt returns (a broker PUBACK is not delivery),
     * broadcasts are pushed once per presence session as a catch-up, and no third-party custody is
     * handed over. Relay sends don't feed the routing benchmarks or Q-learning decisions.
     *
     * DATA DMs (and custody copies) only take this pass's own links (the companion's tiers): a
     * failed BLE write tries Wi-Fi Direct at once, but no longer falls through to the hub or the
     * relay. Those are the 5 s fallback's ([fallbackPass]), with their retry windows.
     */
    private suspend fun sendToSingle(
        peerId: NodeId,
        snapshot: List<DtnMessage>,
        onlinePeerIds: Set<String>,
        broadcastsSentTo: java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>,
        viaInternet: Boolean = false,
    ) {
        // A peer the contacts table still shows radio-online may only be reachable over the relay
        // right now (its BLE adverts just stopped). Apply relay semantics whenever the relay is the
        // path the transport layer will actually use.
        val relay = viaInternet ||
            (!transport.isRadioReachable(peerId) && peerId.value in transport.internetPeers.value)
        // The LoRa hub carries every DM it can't hand to a phone itself (it floods the backbone
        // and replays to phones that register later). It sends no routing summaries, so the
        // strategies have nothing to rank it by: it bypasses them (like a direct delivery).
        val peerIsHub = transport.isHub(peerId)
        val hubOnly = !peerIsHub && isHubOnly(peerId)
        // The links a DATA DM takes in this pass (null = the transport's whole chain, for the hub).
        val dmLinks: Set<SendPath>? = when {
            peerIsHub -> null
            relay -> RELAY_TIER
            hubOnly -> SAME_HUB_TIER
            else -> DIRECT_TIER
        }
        // Q-learning's per-message reasoning, saved with each forward it approves (Message info).
        val qNotes = HashMap<String, String>()
        // The strategy that decides this pass: the one active NOW, captured once, so every
        // forward is attributed to (and explained by) the algorithm that actually made it.
        val active = strategySelector.active
        val relayApproved: Set<String> = if (relay || peerIsHub || hubOnly) emptySet() else try {
            // Q-learning: the engine decides FORWARD vs STORE per (message, peer) from the live
            // 12-feature state, and each decision is recorded for training. The others rank.
            if (active is com.dtn.mesh.routing.QLearningStrategy) qDecisionApproved(snapshot, peerId, onlinePeerIds, qNotes)
            else CarrierRules.approvedByStrategy(active, snapshot, peerId, onlinePeerIds)
        } catch (e: Exception) {
            Log.e(TAG, "rankForForwarding threw, falling back to unfiltered", e)
            snapshot.map { it.id }.toSet()
        }

        // Active routing algorithm name — stamped onto each forward for the lifecycle feed and
        // the message-details view so it's clear which strategy made the decision.
        val strat = active.name

        // The saved "why" for handing a bundle to this peer as a carrier, with the numbers used.
        fun carrierDecision(msg: DtnMessage): String = runCatching {
            when (active) {
                is com.dtn.mesh.routing.ProphetStrategy -> active.explain(msg, peerId, onlinePeerIds)
                is com.dtn.mesh.routing.MaxPropStrategy -> active.explain(msg, peerId, onlinePeerIds)
                is com.dtn.mesh.routing.DiMaxPropStrategy -> active.explain(msg, peerId, onlinePeerIds)
                is com.dtn.mesh.routing.QLearningStrategy -> qNotes[msg.id].orEmpty()
                is com.dtn.mesh.routing.EpidemicStrategy ->
                    "EPIDEMIC · every carrier met gets a copy (hop limit 10)\nDecision: copy to ${peerId.value.takeLast(8)}"
                else -> ""
            }
        }.getOrDefault("")

        var sentCount = 0
        // Why each bundle was or wasn't handed over this pass; logged only when it changes.
        val summary = PeerFlushSummary().apply { offered = snapshot.size }
        // The carrier rules (pure, [CarrierRules]): origin / previous hop never get a bundle back,
        // relay peers carry no one else's DMs, hub-only phones only their own, the hub each DM once
        // per hub session, broadcasts once per session, the 30 s re-send window, the strategy veto.
        val carrier = CarrierRules.Peer(peerId, relay = relay, isHub = peerIsHub, hubOnly = hubOnly)
        for ((index, msg) in snapshot.withIndex()) {
            when (val verdict = CarrierRules.decide(msg, carrier, carrierFacts, relayApproved)) {
                // A receipt already confirmed delivery elsewhere: clear locally.
                CarrierRules.Verdict.Clear -> {
                    queueManager.markDelivered(msg.id)
                    lifecycle.markRouteDelivered(msg.id) // route observation → RESOLVED, matches chat
                    if (msg.messageType == DtnMessageType.DATA && lifecycle.isTracked(msg.id)) {
                        lifecycle.log(LifecycleEvent.BUFFER_CLEARED, msg.id,
                            origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                            extra = "buffer copy cleared — delivery already confirmed by a receipt")
                    }
                    forgetSentTo(msg.id)
                    summary.cleared++
                    continue
                }
                is CarrierRules.Verdict.Hold -> { summary.hold(verdict.reason); continue }
                CarrierRules.Verdict.Send -> Unit
            }

            val isBroadcast = msg.destinationNodeId == NodeId.BROADCAST
            val isDirectDelivery = msg.destinationNodeId.value == peerId.value

            // Send with hop incremented. A DATA DM only over this pass's links (see [dmLinks]).
            val wireMsg = CarrierRules.onWire(msg)
            val links = dmLinks?.takeIf { msg.messageType == DtnMessageType.DATA && !isBroadcast }
            val sendStartedMs = System.currentTimeMillis()
            val outcome = if (links != null) transport.sendMessageVia(wireMsg, peerId, links) else transport.sendMessageVia(wireMsg, peerId)
            if (outcome == null) {
                Log.w(TAG, "FAIL: ${peerId.value.takeLast(8)} unreachable")
                // Back off only after a failure that cost time (a write that timed out), so the next
                // flushes don't pay it again. No link up yet fails at once: retrying is free, and the
                // BLE link coming up flushes right away ([MeshTransport.linkReady]).
                if (System.currentTimeMillis() - sendStartedMs >= SLOW_SEND_FAILURE_MS) markSendFailed(peerId.value)
                // A DM for this very peer: the 5 s fallback (hub / relay) takes over on time.
                if (links != null && isDirectDelivery) {
                    val names = if (links === DIRECT_TIER) directLinkNames(peerId) else links.map { it.label }
                    if (names.isNotEmpty()) noteDmFailure(msg, names.joinToString(" + "))
                }
                summary.hold(HoldReason.SEND_FAILED, snapshot.size - index) // this one and the rest
                break // transport failed — stop trying this peer for now
            }
            markSendOk(peerId.value) // reachable again — clear any backoff

            markSentTo(msg.id, peerId.value)
            markPathSent(msg.id, outcome.path)
            // A mule copy the link itself acknowledged (BLE write with response, Wi-Fi Direct TCP):
            // the carrier has it for this session (no 30 s re-offers while it stays in range).
            if (!isDirectDelivery && !isBroadcast &&
                (outcome.path == SendPath.BLE || outcome.path == SendPath.WIFI_DIRECT)
            ) {
                handedOver.getOrPut(msg.id) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(peerId.value)
            }
            sentCount++
            summary.sent++

            val myId = transport.localNodeId?.value ?: ""
            val path = outcome.path
            val peer8 = peerId.value.takeLast(8)

            // Control traffic (key announces etc.) is not a user message: engine console only, so
            // it never shows up as mystery "broadcast messages in transit".
            if (msg.messageType != DtnMessageType.DATA) {
                lifecycle.console("CTRL ${msg.messageType.name} ${msg.id.take(8)} → $peer8 via ${path.label}")
                // A direct announce handed to its own contact (straight, through our hub, or over
                // the relay while they're present) has done its job: no receipt comes back for
                // control traffic, so drop it instead of re-sending it every 30 s until it expires.
                if (msg.messageType == DtnMessageType.ANNOUNCE && isDirectDelivery &&
                    (path.reachesTargetDirectly || path == SendPath.HUB_LOCAL || path == SendPath.INTERNET)
                ) {
                    runCatching { queueManager.markDelivered(msg.id) }
                    forgetSentTo(msg.id)
                }
                continue
            }

            // One of OUR group receipts: an ordinary DM on the wire (its own receipt clears it), but
            // control traffic here — console only, no journal, no benchmark.
            if (msg.portNum == DtnMessage.PORT_GROUP_RECEIPT) {
                lifecycle.console("CTRL GROUP-RECEIPT ${msg.id.take(8)} → $peer8 via ${path.label}" +
                    if (isDirectDelivery && path.reachesTargetDirectly) " ✓ delivered" else "")
                if (isDirectDelivery && path.reachesTargetDirectly) {
                    runCatching { queueManager.markDelivered(msg.id) }
                    forgetSentTo(msg.id)
                }
                continue
            }

            if (relay || path == SendPath.INTERNET) {
                // Relay hand-off: the broker accepted it, which is NOT delivery. DMs stay buffered
                // until the destination's receipt comes back over the relay.
                val what = if (isBroadcast) "BCAST" else "DM"
                lifecycle.log(LifecycleEvent.FORWARDED, msg.id,
                    origin = msg.originNodeId.value,
                    dest = if (isBroadcast) "BROADCAST" else msg.destinationNodeId.value,
                    hopCount = wireMsg.hopCount, ttlRemainingMs = msg.remainingTtlMs(),
                    extra = "$what → $peer8 via ${path.label}" + if (isBroadcast) "" else " · awaiting receipt")
                // The relay stores nothing: a DM published to a phone that just went offline is
                // simply lost, and only our buffered copy still exists. So a relay publish is not a
                // custody hand-off and must not turn the message into "Sent" — it stays Queued until
                // the receipt proves delivery (the route records that link then). A broadcast
                // pushed to a present peer did reach it, so that one is recorded.
                if (isBroadcast) {
                    lifecycle.recordOutgoing(
                        msgId = msg.id, source = msg.originNodeId.value,
                        dest = "^all", me = myId,
                        nextHop = peerId.value, delivered = false, strategy = "INTERNET RELAY",
                        path = path.label, channel = msg.channel,
                    )
                }
                Log.d(TAG, "RELAY-$what: ${msg.id.take(8)} → $peer8")
                continue
            }
            runCatching { queueManager.incrementForwardCount(msg.id) }
            // Benchmarks measure direct messages: a broadcast copy never leads to a delivery. A DM
            // still buffered from before the app started counts as handled at its first forward.
            if (!isBroadcast) {
                benchmark.recordHandled(strat, msg.id, msg.createdAtMs)
                benchmark.recordForward(strat)
            }

            when {
                // Only a link that reaches the destination itself proves delivery. A "direct"
                // send that fell back to a hub flood is a hand-off like any custody transfer.
                isDirectDelivery && path.reachesTargetDirectly -> {
                    // BLE direct delivery: the GATT link is established and the write
                    // succeeded — reliable connection, so mark delivered immediately.
                    queueManager.markDelivered(msg.id)
                    // Count a delivery attempt via this peer (success is credited in
                    // resolveRewardDelivered) — feeds the Q-state historicalSuccessRate feature.
                    runCatching { contactDao.incrementDeliveryAttempt(peerId.value) }
                    forgetSentTo(msg.id)
                    benchmark.recordDelivery(strat, wireMsg.hopCount, System.currentTimeMillis() - msg.createdAtMs, msgId = msg.id)
                    // Only the number goes through format(): a '%' in a label must never throw here.
                    val tookS = "%.1f".format((System.currentTimeMillis() - msg.createdAtMs).coerceAtLeast(0L) / 1000.0)
                    diagnostics.i(DiagSource.TIMING, "MSG ${msg.id.take(8)} delivered directly to $peer8 via " +
                        "${path.label} $tookS s after it was sent · hop ${wireMsg.hopCount}")
                    // Q-learning credit: this forward delivered the message to its destination.
                    resolveRewardDelivered(msg.id, peerId.value)
                    lifecycle.log(LifecycleEvent.FORWARDED, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        hopCount = wireMsg.hopCount, forwardCount = msg.forwardCount + 1,
                        ttlRemainingMs = msg.remainingTtlMs(),
                        extra = "direct to $peer8 via ${path.label} ✓ delivered [$strat]")
                    // Also emit a buffer-clear so the buffer-history view stops showing this
                    // message as "still buffered" — direct delivery removes it from our buffer.
                    lifecycle.log(LifecycleEvent.BUFFER_CLEARED, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        extra = "delivered directly to $peer8 via ${path.label}")
                    lifecycle.recordOutgoing(
                        msgId = msg.id, source = msg.originNodeId.value,
                        dest = msg.destinationNodeId.value, me = myId,
                        nextHop = peerId.value, delivered = true, strategy = strat,
                        path = path.label, channel = msg.channel,
                        decision = "The destination ${msg.destinationNodeId.value.takeLast(8)} was in range: " +
                            "delivered straight over ${path.label}. No routing decision was needed " +
                            "($strat was selected).",
                    )
                    Log.d(TAG, "DELIVERED-DIRECT: ${msg.id.take(8)} → $peer8 via ${path.label}")
                }
                isBroadcast -> {
                    broadcastsSentTo.getOrPut(msg.id) {
                        java.util.Collections.synchronizedSet(mutableSetOf())
                    }.add(peerId.value)
                    lifecycle.log(LifecycleEvent.FORWARDED, msg.id,
                        origin = msg.originNodeId.value, dest = "BROADCAST",
                        hopCount = wireMsg.hopCount, forwardCount = msg.forwardCount + 1,
                        extra = "${if (msg.channel > 0) "group" else "broadcast"} copy → $peer8 via ${path.label} [$strat]")
                    lifecycle.recordOutgoing(
                        msgId = msg.id, source = msg.originNodeId.value,
                        dest = "^all", me = myId,
                        nextHop = peerId.value, delivered = false, strategy = strat,
                        path = path.label, channel = msg.channel,
                    )
                    Log.d(TAG, "BCAST → $peer8: ${msg.id.take(8)} via ${path.label}")
                }
                else -> {
                    // Count a delivery attempt via this carrier — success is credited later when a
                    // receipt confirms end delivery (feeds the Q-state historicalSuccessRate).
                    runCatching { contactDao.incrementDeliveryAttempt(peerId.value) }
                    val how = when {
                        path == SendPath.HUB_LOCAL -> "handed to our LoRa hub for $peer8 (on the same hub)"
                        peerIsHub -> "handed to LoRa hub $peer8 (floods it over LoRa)"
                        isDirectDelivery -> "flooded toward $peer8 via ${path.label}"
                        else -> "handed to carrier $peer8 via ${path.label}"
                    }
                    lifecycle.log(LifecycleEvent.FORWARDED, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        hopCount = wireMsg.hopCount, forwardCount = msg.forwardCount + 1,
                        extra = "$how · awaiting receipt [$strat]")
                    val decision = when {
                        path == SendPath.HUB_LOCAL ->
                            "The destination ${peer8} is on the same LoRa hub as this phone: handed to the " +
                                "hub, which passes it straight on. Not an algorithm decision."
                        peerIsHub ->
                            "Handed to LoRa hub $peer8: the hub floods it over LoRa to other hubs and gives " +
                                "it to the destination's phone when it connects. Hub hand-offs skip the " +
                                "routing algorithm ($strat was selected)."
                        isDirectDelivery ->
                            "No direct link to ${peer8}: sent toward it via ${path.label} " +
                                "(the hub floods it). Not an algorithm decision."
                        else -> carrierDecision(msg)
                    }
                    lifecycle.recordOutgoing(
                        msgId = msg.id, source = msg.originNodeId.value,
                        dest = msg.destinationNodeId.value, me = myId,
                        nextHop = peerId.value, delivered = false, strategy = strat,
                        path = path.label, channel = msg.channel, decision = decision,
                    )
                    Log.d(TAG, "RELAYED: ${msg.id.take(8)} → $peer8 via ${path.label}")
                }
            }
        }
        if (sentCount > 0) Log.d(TAG, "$sentCount msgs sent to ${peerId.value.takeLast(8)}")
        heldTracker.lineFor(peerId.value, summary)?.let { diagnostics.i(DiagSource.ROUTING, it) }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DELIVERY RECEIPTS: propagate confirmation
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Send our delivery receipt list to a peer, chunked into multiple bundles if needed.
     * Each bundle carries up to MAX_RECEIPTS_PER_BUNDLE (13) UIDs as raw 16-byte UUIDs.
     */
    private suspend fun sendDeliveryReceipts(peerId: NodeId) {
        val receipts = receiptStore.getAllReceipts()
        if (receipts.isEmpty()) return

        // Per-peer dedup: only send receipt UIDs this peer hasn't already been told about.
        // Previously the ENTIRE receipt set was re-sent to every peer on every 4s flush — a
        // storm that grew unbounded with delivery count. New receipts still propagate promptly.
        val alreadySent = receiptsSentTo.getOrPut(peerId.value) {
            java.util.Collections.synchronizedSet(mutableSetOf())
        }
        val newUids = receipts.filter { it !in alreadySent }
        if (newUids.isEmpty()) return

        // Convert receipt UIDs to their raw 16-byte UUID form.
        // Skip any UID that isn't a valid UUID (e.g. legacy ids from earlier builds).
        val uuidPairs = newUids.mapNotNull { uid ->
            try {
                val u = UUID.fromString(uid)
                val bb = java.nio.ByteBuffer.allocate(UUID_BYTES)
                bb.putLong(u.mostSignificantBits)
                bb.putLong(u.leastSignificantBits)
                uid to bb.array()
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        if (uuidPairs.isEmpty()) return

        val chunks = uuidPairs.chunked(MAX_RECEIPTS_PER_BUNDLE)
        val myId = transport.localNodeId ?: NodeId.LOCAL
        var sentUids = 0

        for (chunk in chunks) {
            val payload = ByteArray(chunk.size * UUID_BYTES)
            for ((i, pair) in chunk.withIndex()) {
                System.arraycopy(pair.second, 0, payload, i * UUID_BYTES, UUID_BYTES)
            }
            val receiptBundle = DtnMessage(
                id = UUID.randomUUID().toString(), // fresh valid UUID for the bundle itself
                originNodeId = myId,
                destinationNodeId = peerId,
                payloadBytes = payload,
                createdAtMs = System.currentTimeMillis(),
                ttlMs = 300_000L, // 5 min — receipts are short-lived control traffic
                messageType = DtnMessageType.ROUTING_ACK,
            )
            // Short-circuit: if a bundle fails to send, stop (peer unreachable) and don't mark
            // the rest as sent — they'll retry next flush.
            val outcome = transport.sendMessageVia(receiptBundle, peerId) ?: break
            chunk.forEach { alreadySent.add(it.first) }
            sentUids += chunk.size
            lifecycle.console("RECEIPT ×${chunk.size} → ${peerId.value.takeLast(8)} via ${outcome.path.label}")
        }
        if (sentUids > 0) Log.d(TAG, "Sent $sentUids new receipts to ${peerId.value.takeLast(8)}")
    }

    /**
     * Parse a receipt bundle payload — a concatenation of 16-byte UUIDs.
     * For each UID: mark it delivered locally and clear from buffer if present.
     */
    private suspend fun processInboundReceipts(msg: DtnMessage, via: com.dtn.mesh.receiver.TransportKind? = null) {
        val payload = msg.payloadBytes
        if (payload.size < UUID_BYTES) return
        val count = payload.size / UUID_BYTES

        var cleared = 0
        val from8 = msg.originNodeId.value.takeLast(8)
        for (i in 0 until count) {
            val off = i * UUID_BYTES
            val bb = java.nio.ByteBuffer.wrap(payload, off, UUID_BYTES)
            val uuid = UUID(bb.long, bb.long).toString()
            // News to this phone? The same receipt comes back later (a hub replays its buffer on
            // REGISTER, carriers re-push after a reconnect): the journal records the first one only,
            // also across restarts (the receipt store is in memory, the journal isn't).
            val news = receiptStore.markDelivered(uuid) && !lifecycle.hasEvent(uuid, LifecycleEvent.ACK_RECEIVED)
            // Our own group receipt (control traffic) never goes into the message journal.
            val ownControl = !lifecycle.isTracked(uuid) && queueManager.isOwnGroupReceipt(uuid)
            // Receipts spread epidemically, so most are for other people's messages: only the ones
            // about a message we hold or track belong in the message journal.
            if (ownControl) {
                lifecycle.console("RECEIPT ${uuid.take(8)} from $from8 via ${via.journalLabel()} (our group receipt arrived)")
            } else if (lifecycle.isTracked(uuid) || queueManager.existsInBuffer(uuid)) {
                if (news) {
                    lifecycle.log(LifecycleEvent.ACK_RECEIVED, uuid,
                        origin = msg.originNodeId.value, extra = "delivery receipt from $from8 via ${via.journalLabel()}")
                } else {
                    lifecycle.console("RECEIPT ${uuid.take(8)} from $from8 via ${via.journalLabel()} (already had it, not journaled again)")
                }
            } else {
                lifecycle.console("RECEIPT ${uuid.take(8)} from $from8 via ${via.journalLabel()} (not ours, relaying)")
            }
            // Benchmarks: a delivery if this phone handled that DM (most receipts are someone else's).
            benchmark.recordReceipt(uuid)
            // Timing: our own message, confirmed for the first time by this receipt.
            val me8 = transport.localNodeId?.value?.takeLast(8)
            lifecycle.routes.value.firstOrNull { it.msgId == uuid.take(8) }
                ?.takeIf { !it.delivered && it.source == me8 && it.createdAtMs > 0 }
                ?.let { r ->
                    val tookS = "%.1f".format((System.currentTimeMillis() - r.createdAtMs).coerceAtLeast(0L) / 1000.0)
                    diagnostics.i(DiagSource.TIMING, "MSG ${r.msgId} to ${r.destination} confirmed by receipt via " +
                        "${via.journalLabel()} $tookS s after it was sent")
                }
            // An end-to-end receipt confirms delivery — upgrade our route observation to RESOLVED
            // whether or not we still hold a buffer copy (no-op if we never tracked this route).
            lifecycle.markRouteDelivered(uuid, via = via?.journalLabel().orEmpty(), confirmedBy = msg.originNodeId.value)
            // …and our own chat row, even if our buffer copy is mid-forward (FORWARDING) or gone.
            runCatching { chatDao.markOutgoingDelivered(uuid) }
            if (queueManager.existsInBuffer(uuid)) {
                queueManager.markDelivered(uuid)
                if (!ownControl) lifecycle.log(LifecycleEvent.BUFFER_CLEARED, uuid,
                    extra = "delivered — confirmed by end-to-end receipt (via $from8)")
                // A receipt confirms this message reached its destination — reward the
                // Q-learning FORWARD decisions that carried it (credit the peer that acked).
                resolveRewardDelivered(uuid, msg.originNodeId.value)
                cleared++
            }
            // The message is done — drop any retry-tracking state for it.
            forgetSentTo(uuid)
        }
        if (cleared > 0) Log.d(TAG, "RECEIPTS-IN: $cleared msgs cleared from buffer")
    }

    // ══════════════════════════════════════════════════════════════════════
    // RECEIVE: inbound message handling
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun onMessageReceived(packet: InboundPacket) {
        val msg = packet.message

        // One of OUR OWN bundles coming back (a carrier re-flooding it, a hub replaying its buffer,
        // an older app version). We already hold it or are done with it — and acting on it pinned
        // our own announce as a contact ("I can see myself") and could overwrite a sent chat row.
        val me = transport.localNodeId
        if (me != null && msg.originNodeId == me) {
            lifecycle.console("ECHO ${msg.messageType.name} ${msg.id.take(8)} (our own) via ${packet.transport.journalLabel()} — ignored")
            return
        }

        // Delivery receipt bundle — handle and return.
        if (msg.messageType == DtnMessageType.ROUTING_ACK) {
            processInboundReceipts(msg, packet.transport)
            return
        }

        // Routing summary from a peer — feed to strategy for transitivity + peer-P cache.
        if (msg.messageType == DtnMessageType.ROUTING_SUMMARY) {
            // SECURITY: a routing summary describes a DIRECT neighbour's state. Only trust it when
            // the transport attributes the frame to the very peer that claims to be its origin
            // (a 1-hop message). A relayed or forged summary — including one carried over the LoRa
            // hub with no attributable sender — is ignored, so it can't poison PRoPHET/MaxProp
            // tables (a blackhole attack) or rebind another node's identity.
            val via = packet.viaPeer
            if (via != null && via == msg.originNodeId) {
                // The tag isn't on the wire: a DiMaxProp summary is recognised by its first byte
                // (sent by DiMaxProp and Q-learning phones); anything else is read as our own
                // strategy's format, as before.
                val tag = if (com.dtn.mesh.routing.DiMaxPropStrategy.isSummary(msg.payloadBytes)) {
                    com.dtn.mesh.routing.DiMaxPropStrategy.TAG
                } else {
                    strategySelector.name
                }
                runCatching {
                    strategySelector.onRoutingSummaryReceived(
                        peerId = msg.originNodeId,
                        summary = RoutingSummary(strategyTag = tag, payload = msg.payloadBytes),
                    )
                    Log.d(TAG, "ROUTING-SUMMARY-IN from ${msg.originNodeId.value.takeLast(8)}")
                }.onFailure { Log.w(TAG, "routing-summary decode failed from ${msg.originNodeId.value}", it) }
            } else {
                Log.d(TAG, "Ignoring unattributed routing summary (via=${via?.value?.takeLast(8)}, origin=${msg.originNodeId.value.takeLast(8)})")
            }
            return
        }

        // ANNOUNCE: a signed identity/key broadcast. Verify + pin the peer's keys (TOFU), then
        // let it keep flooding so keys propagate across the mesh.
        if (msg.messageType.isAnnounce) {
            // A direct announce (Discover off on the sender) travels like a DM: only the phone it is
            // addressed to pins the keys; carriers just pass it on without learning who it is.
            val announceForMe = me != null && msg.destinationNodeId == me
            val announceForAll = msg.destinationNodeId == NodeId.BROADCAST
            var outcome: ContactKeyManager.Outcome? = null
            if (announceForMe || announceForAll) {
                outcome = processAnnounce(msg)
                if (outcome?.relay == false) return
            }
            if (announceForMe) {
                lifecycle.console("ANNOUNCE (direct) from ${msg.originNodeId.value.takeLast(8)} via ${packet.transport.journalLabel()} — ${describe(outcome)}")
                return
            }
            val stored = queueManager.ingest(msg)
            // The peer that handed it to us already has it — don't push it straight back.
            packet.viaPeer?.let { markSentTo(msg.id, it.value) }
            if (stored) {
                lifecycle.console("ANNOUNCE from ${msg.originNodeId.value.takeLast(8)} via ${packet.transport.journalLabel()} — " +
                    (if (announceForAll) describe(outcome) + ", " else "") + "relaying")
                requestFlush() // epidemic relay of the announce
            }
            return
        }

        if (!msg.messageType.isUserData) return // unknown control type — drop (never shown as chat)

        // Remember the real previous hop (from the transport, not the wire origin) so the
        // relay logic never echoes this bundle back to anyone who gave it to us.
        packet.viaPeer?.let { receivedFrom.getOrPut(msg.id) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(it.value) }

        val myId = transport.localNodeId?.value
        val isForMe = myId != null && msg.destinationNodeId.value == myId
        val isBroadcast = msg.destinationNodeId == NodeId.BROADCAST

        // Resurrection guard: a terminal tombstone (delivered / expired / dropped) means we are
        // done with this id — don't re-accept a late or re-flooded copy even though the forwarding
        // buffer row may have been purged. A delivered DM still re-pushes its receipt so the sender
        // can clear its own copy.
        runCatching { seenBundleDao.get(msg.id) }.getOrNull()?.let { tomb ->
            when (tomb.status) {
                SeenBundleEntity.Status.DELIVERED -> { if (isForMe) requestFlush(); return }
                SeenBundleEntity.Status.EXPIRED, SeenBundleEntity.Status.DROPPED -> return
                else -> { /* SEEN — fall through */ }
            }
        }

        if (isForMe) {
            // UNICAST TO US — decrypt, show in chat, acknowledge end-to-end.
            val stored = queueManager.ingest(msg)
            if (stored) {
                val origin = msg.originNodeId.value
                // Block (decision 13): a blocked sender's DM is still acknowledged — the receipt
                // goes out, so they see Delivered and carriers drop their copies — but it is only
                // decrypted to check it's genuine: never shown, stored, or used to re-add them.
                val senderBlocked = origin in runCatching { settings.blockedNodesOnce() }.getOrDefault(emptySet())
                val action = ContactRules.inboundDm(senderBlocked)
                val content = when (action) {
                    ContactRules.InboundDm.SHOW -> openAndPersistInbound(msg, convKey = origin, peer = origin, packet = packet)
                    ContactRules.InboundDm.RECEIPT_ONLY -> when (val plain = envelope.openBytes(msg)) {
                        null -> Opened.NO
                        // A blocked member's group receipt: acknowledged like any DM, never recorded.
                        else -> if (msg.fragmentTotal <= 1 && GroupReceiptCodec.isMarked(plain)) Opened.GROUP_RECEIPT else Opened.TEXT
                    }
                }
                val opened = content != Opened.NO
                // A member's group receipt: control traffic, acknowledged like any DM (so carriers
                // drop it) but never a chat, never re-adding the sender, never in the journal.
                val groupReceipt = content == Opened.GROUP_RECEIPT
                if (!opened) {
                    // We can't decrypt it yet — typically the sender scanned OUR QR but we never
                    // received their keys. Acknowledging would make the sender drop a message the
                    // user never saw. Instead forget our copy (no tombstone) so a retransmission
                    // is accepted once their announce has pinned the keys.
                    queueManager.forget(msg.id)
                    lifecycle.log(LifecycleEvent.IGNORED, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        extra = "can't decrypt yet — no key for sender, not acknowledged")
                    return
                }
                queueManager.markDelivered(msg.id)
                receiptStore.markDelivered(msg.id) // propagate an end-to-end receipt
                if (action == ContactRules.InboundDm.SHOW && !groupReceipt) {
                    // Someone who messages us is a contact from now on (e.g. they added us by QR),
                    // also after a Delete: their DM brings them back to the chat list.
                    runCatching { contactDao.markAdded(origin) }
                    runCatching { if (settings.deletedAtOnce(origin) != null) settings.clearDeleted(origin) }
                }
                rememberTombstone(msg.id, msg.originNodeId.value, msg.destinationNodeId.value,
                    SeenBundleEntity.Status.DELIVERED, msg.createdAtMs + msg.ttlMs)
                // Not a benchmark delivery: the sender's and carriers' algorithms routed it, not ours.
                resolveRewardDelivered(msg.id, packet.viaPeer?.value)
                val via = packet.transport.journalLabel()
                val prev8 = (packet.viaPeer ?: msg.originNodeId).value.takeLast(8)
                if (groupReceipt) {
                    lifecycle.console("RECEIPT group DM ${msg.id.take(8)} from ${origin.takeLast(8)} via $via " +
                        "(handed over by $prev8) — its delivery receipt is queued")
                    requestFlush()
                    return
                }
                lifecycle.log(LifecycleEvent.DELIVERED, msg.id,
                    origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                    hopCount = msg.hopCount, ttlRemainingMs = msg.remainingTtlMs(),
                    extra = "received from $prev8 via $via · " + when (action) {
                        ContactRules.InboundDm.SHOW -> "decrypted"
                        ContactRules.InboundDm.RECEIPT_ONLY -> "from a blocked contact: receipt sent, not shown or stored"
                    })
                lifecycle.recordIncoming(
                    msgId = msg.id, source = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                    prevHop = packet.viaPeer?.value ?: msg.originNodeId.value,
                    me = transport.localNodeId?.value ?: "", delivered = true, path = via,
                    senderStrategy = senderAlgorithm.remove(msg.id.take(8)).orEmpty(),
                )
                lifecycle.log(LifecycleEvent.ACK_GENERATED, msg.id, extra = "delivery receipt queued for the sender")
                Log.d(TAG, "RX-MINE: ${msg.id.take(8)} from ${msg.originNodeId.value.takeLast(8)}")
                requestFlush()
            } else if (receiptStore.isDelivered(msg.id)) {
                // Already have it and already hold a receipt — re-push so the sender's buffer clears.
                lifecycle.log(LifecycleEvent.DUPLICATE, msg.id,
                    origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                    extra = "already delivered — re-pushing ACK")
                requestFlush()
            }
            return
        }

        if (isBroadcast) {
            // BROADCAST / GROUP — epidemic store-carry-forward. Buffer for onward flooding and
            // show once if we can decrypt. Crucially NO receipt and NO early markDelivered: a
            // broadcast keeps propagating until its TTL so peers met later still receive it.
            // (Previously receivers minted receipts for broadcasts, which made the origin and
            // hubs drop the broadcast after the first receiver got it.)
            val stored = queueManager.ingest(msg)
            // Whoever handed us this bundle already has it: count it as "sent to them" for this
            // session so the once-per-peer broadcast fan-out never pushes it straight back.
            packet.viaPeer?.let { markSentTo(msg.id, it.value) }
            if (stored) {
                // peer = the ORIGIN, so group / broadcast bubbles can show who actually sent it
                // (the conversation key alone said "grp:N" / "^all" for every message).
                val readable = openAndPersistInbound(
                    msg,
                    convKey = if (msg.channel > 0) "grp:${msg.channel}" else NodeId.BROADCAST.value,
                    peer = msg.originNodeId.value,
                    // A group message read here for the first time: its origin gets a group receipt
                    // (a DM, batched). Never a ROUTING_ACK: the message must keep spreading.
                    onWhole = { chatId -> if (msg.channel > 0) queueGroupReceipt(msg, chatId) },
                ) != Opened.NO
                val via = packet.transport.journalLabel()
                val prev8 = (packet.viaPeer ?: msg.originNodeId).value.takeLast(8)
                val kind = if (msg.channel > 0) "group message" else "broadcast"
                lifecycle.log(LifecycleEvent.BUFFERED, msg.id,
                    origin = msg.originNodeId.value, dest = "^all",
                    hopCount = msg.hopCount, ttlRemainingMs = msg.remainingTtlMs(),
                    extra = "$kind received from $prev8 via $via · " +
                        (if (readable) "shown in chat" else "not a member — carried, not readable") +
                        " · keeps spreading until TTL")
                lifecycle.recordIncoming(
                    msgId = msg.id, source = msg.originNodeId.value, dest = "^all",
                    prevHop = packet.viaPeer?.value ?: msg.originNodeId.value,
                    me = transport.localNodeId?.value ?: "", delivered = false,
                    channel = msg.channel, path = via,
                )
                requestFlush()
            }
            return
        }

        // UNICAST FOR SOMEONE ELSE — we're a mule carrying opaque ciphertext we can't read.
        if (receiptStore.isDelivered(msg.id)) {
            Log.d(TAG, "RX-MULE ignored (already delivered): ${msg.id.take(8)}")
            return
        }
        if (queueManager.ingest(msg)) {
            Log.d(TAG, "RX-MULE: ${msg.id.take(8)} → dest ${msg.destinationNodeId.value.takeLast(8)}")
            benchmark.recordHandled(strategySelector.name, msg.id, msg.createdAtMs)
            val via = packet.transport.journalLabel()
            val prev8 = (packet.viaPeer ?: msg.originNodeId).value.takeLast(8)
            lifecycle.log(LifecycleEvent.BUFFERED, msg.id,
                origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                hopCount = msg.hopCount, ttlRemainingMs = msg.remainingTtlMs(),
                extra = "carrying for ${msg.destinationNodeId.value.takeLast(8)} (received from $prev8 via $via, encrypted)")
            lifecycle.recordIncoming(
                msgId = msg.id, source = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                prevHop = packet.viaPeer?.value ?: msg.originNodeId.value,
                me = transport.localNodeId?.value ?: "", delivered = false, path = via,
            )
            requestFlush()
        }
    }

    /**
     * Verify a signed ANNOUNCE and let [ContactKeyManager] pin / switch / hold the peer's keys
     * (decision 14: a newer announce replaces old keys, stale copies are ignored, a QR-verified
     * contact's new keys wait for the user).
     *
     * @return what happened, or null for an announce that can't be trusted (bad signature, node id
     *   not the origin), which is relayed as before but never pinned. `relay == false` = neither
     *   pinned nor passed on.
     */
    private suspend fun processAnnounce(msg: DtnMessage): ContactKeyManager.Outcome? {
        val from8 = msg.originNodeId.value.takeLast(8)
        val announce = Announce.parseAndVerify(msg.payloadBytes) ?: run {
            Log.w(TAG, "ANNOUNCE failed signature verification from $from8")
            diagnostics.w(DiagSource.CRYPTO, "Announce ${msg.id.take(8)} from $from8: bad signature — ignored")
            return null
        }
        if (NodeId.fromNodeNum(announce.nodeNum) != msg.originNodeId) {
            Log.w(TAG, "ANNOUNCE node-id binding mismatch; rejecting $from8")
            diagnostics.w(DiagSource.CRYPTO, "Announce ${msg.id.take(8)} from $from8: signed for another node id — ignored")
            return null
        }
        // Data-clear ghosts: with the id derived from ANDROID_ID, this phone's identity from before a
        // data clear has OUR node id, so its circulating announces are dropped as echoes. Only a
        // phone without a usable ANDROID_ID gets a new id after a clear; there an announce issued
        // before its keys existed is still its former self, and is ignored.
        val bornAtSec = localIdentity.createdAtMs / 1000L
        if (!localIdentity.idIsStable && bornAtSec > 0 && announce.issuedAtSec.toLong() < bornAtSec) {
            lifecycle.console("ANNOUNCE from $from8 issued before this phone's keys were created — ignored " +
                "(likely this phone's identity before its data was cleared)")
            return ContactKeyManager.Outcome.STALE
        }
        val outcome = runCatching { contactKeys.onAnnounce(msg.originNodeId.value, announce) }
            .onFailure { Log.w(TAG, "processing announce keys failed", it) }
            .getOrNull() ?: return null
        when (outcome) {
            ContactKeyManager.Outcome.OWN_KEYS ->
                lifecycle.console("ANNOUNCE $from8 carries this phone's own keys (its id before the switch) — ignored")
            ContactKeyManager.Outcome.STALE ->
                lifecycle.console("ANNOUNCE from $from8 is older than the keys pinned here — ignored, not relayed")
            else -> Log.i(TAG, "ANNOUNCE ${outcome.name.lowercase()} for $from8")
        }
        return outcome
    }

    /** Engine-console wording for an announce outcome. */
    private fun describe(outcome: ContactKeyManager.Outcome?): String = when (outcome) {
        null -> "not trusted (bad signature or node id), not pinned"
        ContactKeyManager.Outcome.PINNED -> "keys pinned"
        ContactKeyManager.Outcome.REFRESHED -> "keys unchanged"
        ContactKeyManager.Outcome.REPLACED -> "safety number changed, new keys in use"
        ContactKeyManager.Outcome.HELD -> "new keys held until you accept them (verified contact)"
        ContactKeyManager.Outcome.STALE -> "stale, ignored"
        ContactKeyManager.Outcome.OWN_KEYS -> "our own keys, ignored"
        ContactKeyManager.Outcome.BLOCKED -> "blocked contact, ignored"
    }

    /**
     * Decrypt an inbound DATA bundle addressed to us (or a group we're in) and persist it to the
     * chat log, reassembling multi-fragment messages by their canonical parent id first. Byte-exact
     * reassembly (decode to text only after joining all chunks) keeps multi-byte UTF-8 chars intact
     * when a character straddles a fragment boundary.
     */
    /** What an inbound DATA bundle turned out to be. */
    private enum class Opened {
        /** Didn't decrypt (no key for the sender / group, or a bad tag). */
        NO,
        /** A chat message (or a fragment of one; the chat row is written once it's whole). */
        TEXT,
        /** A DM carrying a member's group receipt ([GroupReceiptCodec]): recorded, never a chat. */
        GROUP_RECEIPT,
    }

    /**
     * [packet] is the inbound packet of a DM for us: needed to record a group receipt's link.
     * [onWhole] runs with the chat row's id once the whole message is stored (at once for a single
     * frame, on the last fragment otherwise).
     */
    private suspend fun openAndPersistInbound(
        msg: DtnMessage,
        convKey: String,
        peer: String?,
        packet: InboundPacket? = null,
        onWhole: (suspend (String) -> Unit)? = null,
    ): Opened {
        val bytes = envelope.openBytes(msg) ?: run {
            Log.w(TAG, "RX undecryptable ${msg.id.take(8)} (no key / not a group member?)")
            diagnostics.w(DiagSource.CRYPTO, "MSG ${msg.id.take(8)} from ${msg.originNodeId.value.takeLast(8)}" +
                (if (msg.channel > 0) " on channel ${msg.channel}" else "") +
                ": could not decrypt (${msg.payloadBytes.size} B) — no key for the sender / group, or a bad tag")
            return Opened.NO
        }
        if (msg.fragmentTotal <= 1) {
            // A DM whose first byte is the group-receipt marker is control data, never text. Only
            // unfragmented DMs: a later fragment of a long text can start with any byte.
            if (msg.destinationNodeId != NodeId.BROADCAST && GroupReceiptCodec.isMarked(bytes)) {
                onGroupReceipt(msg, bytes, packet)
                return Opened.GROUP_RECEIPT
            }
            persistInboundChat(msg.id, msg.channel, convKey, peer, text(msg, bytes))
            onWhole?.invoke(msg.id)
            return Opened.TEXT
        }
        val parentKey = com.dtn.mesh.crypto.Fragmenter.canonicalParent(msg.id)
        val full = reassembler.add(parentKey, msg.fragmentIndex, msg.fragmentTotal, bytes) ?: return Opened.TEXT
        persistInboundChat(parentKey, msg.channel, convKey, peer, text(msg, full))
        onWhole?.invoke(parentKey)
        return Opened.TEXT
    }

    // ══════════════════════════════════════════════════════════════════════
    // GROUP RECEIPTS: which members got our group message
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Member side: we just read [msg] (a group message) for the first time. Unless its origin is
     * blocked, the origin gets a group receipt for [chatId] — batched for [GROUP_RECEIPT_BATCH_MS]
     * per (origin, channel), then sent as an ordinary end-to-end encrypted DM.
     */
    private suspend fun queueGroupReceipt(msg: DtnMessage, chatId: String) {
        val origin = msg.originNodeId.value
        if (origin in runCatching { settings.blockedNodesOnce() }.getOrDefault(emptySet())) return
        val now = System.currentTimeMillis()
        val dueAt = groupReceipts.add(origin, msg.channel, chatId, msg.remainingTtlMs(now), now) ?: return
        scope?.launch {
            delay((dueAt - now).coerceAtLeast(0L))
            runCatching { sendDueGroupReceipts() }.onFailure { Log.w(TAG, "group receipts failed", it) }
        }
    }

    /** Seal and buffer the group receipts that are due (one DM per batch, each fits one frame). */
    private suspend fun sendDueGroupReceipts() {
        val me = transport.localNodeId ?: return
        val due = groupReceipts.takeDue(System.currentTimeMillis())
        if (due.isEmpty()) return
        var queued = 0
        for (b in due) {
            val origin8 = b.origin.takeLast(8)
            val id = UUID.randomUUID().toString()
            val dest = NodeId(b.origin)
            val sealed = envelope.sealBytes(GroupReceiptCodec.encode(b.channel, b.ids), id, me, dest, 0, 0, 1)
            if (sealed !is EnvelopeService.Sealed.Ok) {
                diagnostics.w(DiagSource.RECEIPTS, "Group receipt for ${b.ids.size} message(s) on channel ${b.channel} " +
                    "to $origin8 not sent: " + if (sealed is EnvelopeService.Sealed.NoKey) "no keys for them on this phone" else "too long")
                continue
            }
            val now = System.currentTimeMillis()
            val ttl = GroupReceiptCodec.ttlFor(b.ttlLeftMs)
            val dm = DtnMessage(
                id = id, originNodeId = me, destinationNodeId = dest, payloadBytes = sealed.payload,
                portNum = DtnMessage.PORT_GROUP_RECEIPT, createdAtMs = now, ttlMs = ttl,
                messageType = DtnMessageType.DATA, flags = sealed.flags,
            )
            if (queueManager.ingest(dm)) {
                queued++
                lifecycle.console("RECEIPT group ${id.take(8)} → $origin8 queued: ${b.ids.size} message(s) on " +
                    "channel ${b.channel}, TTL ${ttl / 60_000L} min")
            }
        }
        if (queued > 0) {
            diagnostics.i(DiagSource.RECEIPTS, "Group receipts: $queued DM(s) queued for ${due.map { it.origin }.distinct().size} origin(s)")
            requestFlush()
        }
    }

    /**
     * Origin side: a member's group receipt arrived (the DM from [msg]'s origin decrypted, so it's
     * really from that member). Each id that is one of our group messages on that channel records
     * the member, the time and the link it came over; the message itself keeps spreading.
     */
    private fun onGroupReceipt(msg: DtnMessage, plain: ByteArray, packet: InboundPacket?) {
        val member = msg.originNodeId.value
        val member8 = member.takeLast(8)
        val receipt = GroupReceiptCodec.decode(plain) ?: run {
            diagnostics.w(DiagSource.RECEIPTS, "Group receipt ${msg.id.take(8)} from $member8: unreadable (${plain.size} B) — ignored")
            return
        }
        val via = packet?.transport.journalLabel()
        val hop = packet?.viaPeer?.value.orEmpty()
        val hub = if (packet?.transport == com.dtn.mesh.receiver.TransportKind.HUB && hop.isNotBlank()) " (${com.dtn.mesh.receiver.hubLabel(hop)})" else ""
        var recorded = 0
        for (gid in receipt.ids) {
            if (lifecycle.recordGroupReceipt(gid, receipt.channel, member, via, hop)) {
                recorded++
                lifecycle.log(LifecycleEvent.ACK_RECEIVED, gid, origin = member, dest = "BROADCAST",
                    extra = "group receipt from $member8 via $via$hub")
            }
        }
        diagnostics.i(DiagSource.RECEIPTS, "Group receipt from $member8 via $via$hub: $recorded of ${receipt.ids.size} " +
            "message(s) recorded (channel ${receipt.channel})")
    }

    // ══════════════════════════════════════════════════════════════════════
    // FALLBACK: a DM still queued after 5 s takes every other path that's up
    // ══════════════════════════════════════════════════════════════════════

    /**
     * The 5 s fallback ([DmFallback]): a DM queued 5 s on this phone without being delivered keeps
     * trying its direct links (its peer's pass) and is also handed to every other path that's up —
     * our LoRa hub (the same-hub hand-off when the destination is on it, else a flood, once per hub
     * session) and the internet relay (destination present; it stays buffered until the end-to-end
     * receipt, 30 s window). Own DMs and carried ones alike: publishing a carried DM to its own
     * destination is a direct delivery, not custody. Fallback sends don't train the routing tables
     * or Q-learning and aren't benchmark forwards. Also schedules the next fallback for DMs whose 5 s
     * aren't up yet.
     *
     * @param passPeers the peers a flush runs a pass for ([passTargets]): a destination present on the
     *   relay that isn't one of them (a carried DM for a phone that isn't a contact here) gets the
     *   relay from the fallback only.
     */
    private suspend fun fallbackPass(snapshot: List<DtnMessage>, radioPeers: List<String>, passPeers: Set<String>) {
        val me = transport.localNodeId?.value ?: return
        val hub = radioPeers.firstOrNull { transport.isHub(NodeId(it)) }
        var nextDueAt = Long.MAX_VALUE
        fallbackMutex.withLock {
            val present = transport.internetPeers.value
            for (msg in snapshot) {
                if (msg.messageType != DtnMessageType.DATA) continue
                val dest = msg.destinationNodeId
                if (dest == NodeId.BROADCAST || dest.value == me || msg.originNodeId == dest) continue
                val now = System.currentTimeMillis()
                val links = DmFallback.Links(
                    direct = transport.reachesDirectly(dest),
                    hub = hub != null,
                    onOurHub = transport.isHubPeer(dest),
                    relay = dest.value in present,
                    relayContact = dest.value in passPeers,
                )
                val since = queuedSince(msg)
                if (since <= 0L) continue // no buffering time known: never "late", nothing to schedule
                if (!DmFallback.isLate(since, now)) {
                    if (DmFallback.opensLater(links)) nextDueAt = minOf(nextDueAt, DmFallback.dueAtMs(since))
                    continue
                }
                val plan = DmFallback.plan(
                    broadcast = false,
                    delivered = receiptStore.isDelivered(msg.id),
                    queuedSinceMs = since, nowMs = now, links = links,
                    attempts = DmFallback.Attempts(
                        relayAtMs = pathSentAtMs(msg.id, SendPath.INTERNET),
                        hubLocalAtMs = pathSentAtMs(msg.id, SendPath.HUB_LOCAL),
                        hubThisSession = hub != null && sentTo[msg.id]?.containsKey(hub) == true,
                    ),
                )
                if (plan.fallback.isEmpty()) continue
                // Delivered meanwhile (straight over BLE in this flush, or a receipt)?
                if (!queueManager.existsInBuffer(msg.id)) continue
                for (p in plan.fallback) sendFallback(msg, p, hub, links, since, me)
            }
        }
        if (nextDueAt != Long.MAX_VALUE) scheduleFlushAt(nextDueAt)
    }

    /** One fallback hand-off of [msg] over [p] (journal + route + the diagnostics line). */
    private suspend fun sendFallback(
        msg: DtnMessage, p: DmFallback.Path, hub: String?, links: DmFallback.Links, since: Long, me: String,
    ) {
        val dest = msg.destinationNodeId
        val (target, only) = when (p) {
            DmFallback.Path.RELAY -> dest to RELAY_TIER
            DmFallback.Path.HUB_LOCAL -> dest to SAME_HUB_TIER
            DmFallback.Path.HUB_FLOOD -> NodeId(hub ?: return) to setOf(SendPath.HUB)
            DmFallback.Path.DIRECT -> return
        }
        // The hub it came from already holds it (and hands it to the destination when it registers).
        if (p != DmFallback.Path.RELAY && hub != null && receivedFrom[msg.id]?.contains(hub) == true) return
        val wireMsg = msg.copy(hopCount = (msg.hopCount + 1).coerceAtMost(255))
        val outcome = transport.sendMessageVia(wireMsg, target, only) ?: run {
            Log.d(TAG, "FALLBACK ${msg.id.take(8)} over ${p.label} failed")
            return
        }
        // The relay / same-hub retry windows; the hub's "once per hub session" is the usual sentTo.
        markPathSent(msg.id, outcome.path)
        if (p == DmFallback.Path.HUB_FLOOD) markSentTo(msg.id, target.value)

        val dest8 = dest.value.takeLast(8)
        // What it waited on: the links whose writes failed, else the links that reach it now.
        val failed = dmFailures[msg.id]
        val after = failed?.links ?: DmFallback.waitingOn(directLinkNames(dest), links)
        val failures = failed?.count ?: 0
        val via = when (p) {
            DmFallback.Path.HUB_FLOOD -> "LoRa hub (${com.dtn.mesh.receiver.hubLabel(target.value)})"
            DmFallback.Path.HUB_LOCAL -> "LoRa hub (same hub)"
            else -> outcome.path.label
        }
        diagnostics.i(DiagSource.ROUTING, DmFallback.logLine(msg.id.take(8), via, System.currentTimeMillis() - since, after, failures))
        if (msg.portNum == DtnMessage.PORT_GROUP_RECEIPT) {
            lifecycle.console("CTRL GROUP-RECEIPT ${msg.id.take(8)} → $dest8 via $via (fallback after $after)")
            return
        }
        val link = if (p == DmFallback.Path.RELAY) "Internet relay" else "LoRa hub"
        when (p) {
            DmFallback.Path.RELAY -> {
                lifecycle.log(LifecycleEvent.FORWARDED, msg.id,
                    origin = msg.originNodeId.value, dest = dest.value,
                    hopCount = wireMsg.hopCount, ttlRemainingMs = msg.remainingTtlMs(),
                    extra = "fallback after $after: DM → $dest8 via Internet relay · awaiting receipt")
                // Not a hand-off (the relay stores nothing): it stays Queued until the receipt.
                lifecycle.recordFallback(msg.id, via = link, after = after)
            }
            DmFallback.Path.HUB_LOCAL, DmFallback.Path.HUB_FLOOD -> {
                val next = target.value
                val how = if (p == DmFallback.Path.HUB_LOCAL) "handed to our LoRa hub for $dest8 (on the same hub)"
                else "handed to LoRa hub ${next.takeLast(8)} (floods it over LoRa)"
                lifecycle.log(LifecycleEvent.FORWARDED, msg.id,
                    origin = msg.originNodeId.value, dest = dest.value,
                    hopCount = wireMsg.hopCount, forwardCount = msg.forwardCount + 1, ttlRemainingMs = msg.remainingTtlMs(),
                    extra = "fallback after $after: $how · awaiting receipt")
                val queuedS = (System.currentTimeMillis() - since).coerceAtLeast(0L) / 1000L
                lifecycle.recordFallback(msg.id, via = link, after = after, nextHop = next,
                    decision = "Fallback, not an algorithm decision: still queued $queuedS s after this phone buffered it " +
                        "(waiting on $after${if (failures > 0) ", $failures failed write(s)" else ""}), so it went to " +
                        (if (p == DmFallback.Path.HUB_LOCAL) "our LoRa hub, which hands it to $dest8 on the same hub"
                        else "LoRa hub ${next.takeLast(8)}, which floods it over LoRa") +
                        " as well. Delivered only by $dest8's end-to-end receipt.")
            }
            DmFallback.Path.DIRECT -> Unit
        }
        if (msg.originNodeId.value == me) Log.d(TAG, "FALLBACK ${msg.id.take(8)} → $dest8 via $via")
    }

    /**
     * The text of a decrypted message. A DM starts with the sender's routing-algorithm byte
     * ([com.dtn.mesh.routing.AlgorithmCode]); it is noted for the route record and removed.
     */
    private fun text(msg: DtnMessage, plain: ByteArray): String {
        if (msg.destinationNodeId == NodeId.BROADCAST) return String(plain, Charsets.UTF_8)
        val (algorithm, body) = com.dtn.mesh.routing.AlgorithmCode.split(plain)
        if (algorithm != null) senderAlgorithm[msg.id.take(8)] = algorithm
        return String(body, Charsets.UTF_8)
    }

    /** Persist a decrypted inbound message into the user-visible chat log (dedup-by-id via REPLACE). */
    private suspend fun persistInboundChat(id: String, channel: Int, convKey: String, peer: String?, text: String) {
        runCatching {
            chatDao.upsert(
                ChatMessageEntity(
                    id = id, convKey = convKey, peerNodeId = peer, channel = channel,
                    direction = ChatMessageEntity.Direction.IN, text = text,
                    status = ChatMessageEntity.Status.RECEIVED, createdAtMs = System.currentTimeMillis(),
                ),
            )
        }.onFailure { Log.w(TAG, "persist inbound chat failed", it) }
    }

    /** In-memory reassembly of multi-fragment messages, bounded by a 10-minute timeout. */
    private class FragmentReassembler {
        private class Pending(val total: Int) {
            val chunks = HashMap<Int, ByteArray>()
            val firstMs = System.currentTimeMillis()
        }
        private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

        @Synchronized
        fun add(parentKey: String, index: Int, total: Int, chunk: ByteArray): ByteArray? {
            prune()
            val p = pending.getOrPut(parentKey) { Pending(total) }
            p.chunks[index] = chunk
            if (p.chunks.size < p.total) return null
            pending.remove(parentKey)
            val out = java.io.ByteArrayOutputStream()
            for (i in 0 until p.total) out.write(p.chunks[i] ?: return null)
            return out.toByteArray()
        }

        private fun prune() {
            val cutoff = System.currentTimeMillis() - 600_000L
            pending.entries.removeAll { it.value.firstMs < cutoff }
        }
    }

    /** Record a dedup/terminal tombstone so a late re-flood of [id] can't resurrect the message. */
    private suspend fun rememberTombstone(id: String, origin: String, dest: String, status: String, expiresAtMs: Long) {
        runCatching {
            seenBundleDao.upsert(
                SeenBundleEntity(
                    messageId = id, originNodeId = origin, destinationNodeId = dest,
                    status = status, expiresAtMs = expiresAtMs,
                ),
            )
        }.onFailure { Log.w(TAG, "tombstone write failed for ${id.take(8)}", it) }
    }

    // ══════════════════════════════════════════════════════════════════════
    // DELIVERY STATUS (from transport layer)
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun onDeliveryStatus(event: DeliveryStatusEvent) {
        // NOTE: Transport-level delivery status (e.g. BLE write success) is a HOP-level
        // signal, NOT end-to-end delivery proof. We intentionally do NOT clear the buffer
        // from this signal — only from explicit receipts (ROUTING_ACK) or direct delivery
        // confirmation in sendToSingle. This avoids the bug where a relay handoff to a
        // mule was incorrectly treated as final delivery.
        if (event.status == DeliveryOutcome.DELIVERED) {
            Log.d(TAG, "HOP-ACK: pktId=${event.meshPacketId} (informational only)")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Q-ENGINE BATCH UPDATES (research only)
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun processBatchQUpdates(decisions: List<ForwardingDecisionEntity>) {
        val applied = mutableListOf<Long>()
        for (d in decisions) {
            val state = ForwardingState(
                d.stateRssiNorm, d.stateSnrNorm, d.stateEncounterFrequency,
                d.stateEncounterDuration, d.stateDeliveryProbability, d.stateRemainingTtlFraction,
                d.stateBufferOccupancy, d.stateHistoricalSuccessRate, d.stateDuplicateCountNorm,
                diMaxPropCost = d.stateDiMaxPropCost, hopCountNorm = d.stateHopCountNorm,
                relayBufferOccupancy = d.stateRelayBuffer,
            )
            val reward = d.reward ?: continue
            val action = runCatching { ForwardingAction.valueOf(d.action) }.getOrNull() ?: continue
            qEngine.update(state, action, reward, nextState = null)
            applied.add(d.id)
        }
        // Mark consumed so getPendingUpdates() doesn't hand them back next cycle (which would
        // over-train the engine on the same experiences).
        if (applied.isNotEmpty()) runCatching { decisionDao.markApplied(applied) }
        if (applied.isNotEmpty()) scheduleQSave()
        if (applied.isNotEmpty()) lifecycle.console("Q-LEARNING: applied ${applied.size} reward updates " +
            "(ε=%.3f, updates=${qEngine.totalUpdates})".format(qEngine.currentEpsilon))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Q-LEARNING decision + training helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Build the 12-feature [ForwardingState] for deciding whether to forward [msg] to [peer]: the 9
     * link/peer features plus the DiMaxProp paper's three (§VI-A) — the relay's DiMaxProp cost to
     * the destination ([peerCost], +∞ when it knows no path), the bundle's hop count, and the
     * relay's reported buffer occupancy. All features are normalised to [0,1]. Pulls per-peer stats
     * from the contacts table and live buffer occupancy — data only the orchestrator has, which is
     * why the Q decision lives here rather than inside [QLearningStrategy].
     */
    private suspend fun buildForwardingState(msg: DtnMessage, peer: NodeId, peerCost: Double): ForwardingState {
        val c = runCatching { contactDao.getByNodeId(peer.value) }.getOrNull()
        // Feed the CANONICAL PROPHET predictability (not the active strategy's) — under Q-learning
        // the active strategy's getDeliveryProbability is a constant 0.5 stub, which starved this
        // feature. Both PROPHET variants learn on every encounter, so this is always meaningful.
        // Normalisation lives in ForwardingStateBuilder so the UI's Q explanation matches exactly.
        return com.dtn.mesh.learning.ForwardingStateBuilder.build(
            lastRssi = c?.lastRssi ?: -200,
            lastSnr = c?.lastSnr ?: -100f,
            totalEncounters = c?.totalEncounters ?: 0,
            totalEncounterDurationMs = c?.totalEncounterDurationMs ?: 0L,
            deliveryProbability = strategySelector.prophetDeliveryProbability(peer),
            remainingTtlMs = msg.remainingTtlMs(),
            ttlMs = msg.ttlMs,
            bufferOccupancy = runCatching { queueManager.getBufferOccupancy() }.getOrDefault(0.0),
            deliverySuccessCount = c?.deliverySuccessCount ?: 0,
            deliveryAttemptCount = c?.deliveryAttemptCount ?: 0,
            duplicateCount = msg.duplicateCount,
            diMaxPropCost = peerCost,
            hopCount = msg.hopCount,
            relayBufferOccupancy = strategySelector.diMaxProp.peerBufferOccupancy(peer.value),
        )
    }

    /**
     * Q-learning relay decision for [peerId] over [snapshot] (DiMaxProp paper §VI).
     *
     * For each bundle it could hand to this carrier it builds the 12-feature state and asks the
     * Double-Q engine (ε-greedy) whether to FORWARD:
     * - the choice is kept for [SEND_RETRY_INTERVAL_MS] per (bundle, carrier), so the forward that
     *   actually happens is the decision that gets recorded and rewarded;
     * - in a state the engine has never been rewarded in (both values equal) it takes the DiMaxProp
     *   rule's choice, so a fresh table starts from a sensible policy instead of "never forward";
     * - a FORWARD earns the immediate progress reward η·(C(A,D) − C(B,D)) (eq. 13), applied on the
     *   event loop; the delivered / expired outcome rewards follow when known, as before.
     * Each fresh decision is recorded as a [ForwardingDecisionEntity] (research export + reward
     * backfill) and its reasoning goes into [notes] for Message info. Direct delivery and broadcast
     * are always approved (they aren't relay decisions).
     */
    private suspend fun qDecisionApproved(
        snapshot: List<DtnMessage>,
        peerId: NodeId,
        onlinePeerIds: Set<String>,
        notes: MutableMap<String, String>,
    ): Set<String> {
        val now = System.currentTimeMillis()
        val toRecord = mutableListOf<ForwardingDecisionEntity>()
        val shaping = mutableListOf<Pair<ForwardingState, Double>>()
        val peer = peerId.value
        if (qChoice.size > 4_000) qChoice.entries.removeAll { now - it.value.atMs > 2 * SEND_RETRY_INTERVAL_MS }
        // The decisions themselves are pure ([CarrierRules.qLearningPass]); recording them is here.
        val pass = CarrierRules.qLearningPass(
            snapshot, peerId, onlinePeerIds, strategySelector.diMaxProp, qEngine, shapingEta,
            // The peer already has it: it handed us a copy, or took ours this session (no new
            // decision, and no second progress reward, for a hand-over that already happened).
            cameFrom = { id, p -> receivedFrom[id]?.contains(p) == true || handedOver[id]?.contains(p) == true },
            kept = { id ->
                qChoice["$id|$peer"]?.takeIf { now - it.atMs < SEND_RETRY_INTERVAL_MS }
                    ?.let { CarrierRules.QKept(it.action, it.note) }
            },
            stateFor = { msg, carrierCost -> buildForwardingState(msg, peerId, carrierCost) },
        )
        notes.putAll(pass.keptNotes)
        for (d in pass.fresh) {
            val state = d.state
            val note = qNote(state, d.qForward, d.qStore, d.action, d.exploratory, d.diMaxProp, d.reward, d.msg.destinationNodeId.value)
            qChoice["${d.msg.id}|$peer"] = QChoice(d.action, d.exploratory, now, note)
            notes[d.msg.id] = note
            if (d.reward != null) shaping += state to d.reward

            toRecord.add(ForwardingDecisionEntity(
                timestampMs = now, messageId = d.msg.id, peerNodeId = peer,
                stateRssiNorm = state.rssiNorm, stateSnrNorm = state.snrNorm,
                stateEncounterFrequency = state.encounterFrequency,
                stateEncounterDuration = state.encounterDuration,
                stateDeliveryProbability = state.deliveryProbability,
                stateRemainingTtlFraction = state.remainingTtlFraction,
                stateBufferOccupancy = state.bufferOccupancy,
                stateHistoricalSuccessRate = state.historicalSuccessRate,
                stateDuplicateCountNorm = state.duplicateCountNorm,
                stateDiMaxPropCost = state.diMaxPropCost,
                stateHopCountNorm = state.hopCountNorm,
                stateRelayBuffer = state.relayBufferOccupancy,
                action = d.action.name, qValueSelected = qEngine.computeUtility(state),
                epsilon = qEngine.currentEpsilon, wasExploratory = d.exploratory,
                routingStrategy = "Q-LEARNING", shapingReward = d.reward,
            ))
        }
        if (toRecord.isNotEmpty()) runCatching { decisionDao.insertAll(toRecord) }
        if (shaping.isNotEmpty()) eventChannel?.trySend(OrchestratorEvent.QShaping(shaping))
        return pass.approved
    }

    /** Q-learning's reasoning for one decision, saved with the forward for Message info. */
    private fun qNote(
        s: ForwardingState, qf: Double, qs: Double, action: ForwardingAction, exploratory: Boolean,
        dm: DiMaxPropStrategy.Decision, reward: Double?, dest: String,
    ): String {
        fun cost(p: DiMaxPropStrategy.Path) = if (p.reachable) "%.2f".format(p.cost) else "no path"
        return buildString {
            appendLine("Q-LEARNING · ε %.2f · %s".format(qEngine.currentEpsilon,
                if (exploratory) "explored (random pick)" else "greedy on (Q_A+Q_B)/2"))
            appendLine("Q(forward) %.3f · Q(hold) %.3f".format(qf, qs) +
                if (qf == qs && !exploratory) " · state not learned yet: DiMaxProp rule decides" else "")
            appendLine("DiMaxProp cost to ${dest.takeLast(8)}: mine ${cost(dm.mine)} · carrier ${cost(dm.theirs)}")
            appendLine("Inputs: rssi %.2f · enc %.2f · dur %.2f · P %.2f · ttl %.2f · buf %.2f · succ %.2f · dup %.2f"
                .format(s.rssiNorm, s.encounterFrequency, s.encounterDuration, s.deliveryProbability,
                    s.remainingTtlFraction, s.bufferOccupancy, s.historicalSuccessRate, s.duplicateCountNorm))
            appendLine("        cost %.2f · hops %.2f · relay buffer %.2f"
                .format(s.diMaxPropCost, s.hopCountNorm, s.relayBufferOccupancy))
            if (reward != null) appendLine("Progress reward η·(C_mine − C_carrier) = %+.3f".format(reward))
            append("Decision: ${if (action == ForwardingAction.FORWARD) "forward" else "hold"}")
        }
    }

    /**
     * Backfill rewards for a delivered message's recorded decisions (Q-learning credit
     * assignment): a modest baseline for every decision on the message, and full credit for the
     * FORWARD decision that targeted the peer credited with delivery. No-op when there are no
     * recorded decisions (e.g. running a non-Q strategy).
     */
    private suspend fun resolveRewardDelivered(msgId: String, deliveringPeer: String?) {
        runCatching {
            decisionDao.setRewardByMessageId(msgId, REWARD_BASELINE_DELIVERED)
            if (deliveringPeer != null) {
                decisionDao.setDifferentiatedReward(msgId, deliveringPeer, REWARD_FORWARD_DELIVERED)
                // Credit this peer with a successful delivery — feeds the Q-state
                // historicalSuccessRate feature (success/attempt), which was stuck at 0.0 because
                // the success counter was never incremented anywhere.
                contactDao.incrementDeliverySuccess(deliveringPeer)
            }
        }.onFailure { Log.w(TAG, "reward backfill failed for ${msgId.take(8)}", it) }
        // Train the Q-engine on these freshly-assigned rewards NOW rather than waiting for the
        // 15-minute housekeeping worker. Enqueued as a loop event so qEngine mutations stay on the
        // single consumer coroutine (thread-safety). No-op for non-Q strategies (nothing pending).
        eventChannel?.trySend(OrchestratorEvent.DrainQUpdates)
    }
}
