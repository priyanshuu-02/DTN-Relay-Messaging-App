package com.dtn.mesh.service

import android.util.Log
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.dao.EncounterDao
import com.dtn.mesh.database.dao.ForwardingDecisionDao
import com.dtn.mesh.database.entity.ContactEntity
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
    private val encounterDao: EncounterDao,
    private val decisionDao: ForwardingDecisionDao,
    private val strategySelector: StrategySelector,
    private val lifecycle: MessageLifecycleLog,
    private val benchmark: com.dtn.mesh.learning.BenchmarkTracker,
) {
    companion object {
        private const val TAG = "DtnOrchestrator"
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
         * links and made peers appear to go offline. Serialising sends is the stable choice; the
         * head-of-line latency it reintroduces is bounded by the per-peer connect timeout plus the
         * [CONNECT_BACKOFF_MS] skip for peers that just failed. (Coalescing, backoff, and receipt
         * de-duplication from the multi-phone pass are retained.)
         */
        private const val MAX_CONCURRENT_SENDS = 1

        /**
         * After a failed send to a peer, skip it for this long instead of paying the ~8s connect
         * timeout again on every 4s flush. Cleared the moment any send to that peer succeeds or
         * the peer re-announces online.
         */
        private const val CONNECT_BACKOFF_MS = 10_000L

        // Q-learning reward shaping (backfilled when a message's outcome becomes known).
        private const val REWARD_BASELINE_DELIVERED = 0.3  // any decision on a delivered msg
        private const val REWARD_FORWARD_DELIVERED = 1.0   // the FORWARD that reached the deliverer
        const val REWARD_EXPIRED = -0.3                    // message expired before delivery
    }

    private sealed class OrchestratorEvent {
        data class Encounter(val event: NodeEncounterEvent) : OrchestratorEvent()
        data class Message(val packet: InboundPacket) : OrchestratorEvent()
        data class Status(val event: DeliveryStatusEvent) : OrchestratorEvent()
        data class QUpdateBatch(val decisions: List<ForwardingDecisionEntity>) : OrchestratorEvent()
        data object FlushBuffer : OrchestratorEvent()
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
     * msgId → the immediate peer we received that bundle from (the real previous hop, as
     * reported by the transport). Used to suppress the reverse-echo bug: in an A→B→C→D
     * chain, C must not hand the bundle straight back to B just because B isn't the wire
     * header's origin (A). Entries are cleared alongside [sentTo] when the message leaves
     * our buffer.
     */
    private val receivedFrom = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** "$msgId|$peer" → last time we recorded a Q-learning decision, to throttle table growth. */
    private val qLogged = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
        receivedFrom.remove(msgId)
    }

    /**
     * Drop tracking for every message we sent to [peer]. Called when a peer transitions
     * offline so that a subsequent reconnection retries immediately instead of waiting
     * out the SEND_RETRY_INTERVAL_MS — receipts might have been lost with the drop, and a
     * duplicate send is safe (the peer's ingest deduplicates by message id).
     */
    private fun forgetSentToPeer(peer: String) {
        for ((_, peers) in sentTo) peers.remove(peer)
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
        // Proactive short-interval flush so buffered messages reach online peers promptly
        // instead of waiting for the next (debounced/suppressed) encounter event.
        sc.launch {
            while (isActive) {
                kotlinx.coroutines.delay(PERIODIC_FLUSH_MS)
                ch.trySend(OrchestratorEvent.FlushBuffer)
            }
        }
        sc.launch {
            for (ev in ch) {
                try {
                    when (ev) {
                        is OrchestratorEvent.Encounter -> onPeerEvent(ev.event)
                        is OrchestratorEvent.Message -> onMessageReceived(ev.packet)
                        is OrchestratorEvent.Status -> onDeliveryStatus(ev.event)
                        is OrchestratorEvent.QUpdateBatch -> processBatchQUpdates(ev.decisions)
                        is OrchestratorEvent.FlushBuffer -> requestFlush()
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
        Log.i(TAG, "Stopped")
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

        if (!event.isOnline) {
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
        // so without this, the UI's signal indicator would stick to a stale value.
        contactDao.updateSignal(peerId.value, event.rssi, event.snr)
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

        // Feed the routing strategy so PROPHET's P-table (and MaxProp's path likelihoods)
        // learn from this contact. Distance is derived from the observed RSSI via the
        // log-distance path-loss model — if RSSI is unavailable the estimator returns the
        // "unknown" sentinel and the distance-boost term is skipped.
        val distanceM = RadioDistanceEstimator.estimateMeters(
            rssi = event.rssi,
            transport = RadioDistanceEstimator.Transport.BLE, // TODO: source per-encounter when multi-transport metadata lands
        )
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
            ),
        )
        // ── Encounter-history + probability logging ──────────────────────────────────────
        // Show exactly what each routing model consumed to (re)compute this peer's score:
        // the encounter-history signals (novelty, gap since last contact, RSSI/SNR, distance)
        // and the resulting per-strategy value with the formula that produced it.
        val peer8 = peerId.value.takeLast(8)
        val sinceLastMs = if (lastEnc != null) now - lastEnc.startTimeMs else -1L
        lifecycle.console("ENCOUNTER $peer8: new=$isNew sinceLast=${sinceLastMs}ms " +
            "rssi=${event.rssi} snr=${event.snr} dist≈%.1fm".format(distanceM))
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
        }

        // Exchange routing summaries so both sides can drive Stage-2 (smart-spread) decisions
        // based on the peer's own predictability table. Launched OFF the event loop (so a slow
        // write to this peer can't stall other peers' events) but under this peer's lock so it
        // serialises with the flush's writes to the same GATT link.
        scope?.launch {
            peerLock(peerId.value).withLock {
                runCatching { sendRoutingSummary(peerId) }
                    .onFailure { Log.w(TAG, "routing summary to ${peerId.value.takeLast(8)} failed", it) }
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
        runCatching { transport.sendMessage(bundle, peerId) }
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
     * One flush pass: transmit receipts + buffered messages to every online peer, running peers
     * CONCURRENTLY (bounded by [sendSemaphore]) so one slow/unreachable peer can't stall the
     * others. Each peer's transmissions are serialised by a per-peer lock so writes to a single
     * GATT link never interleave.
     */
    private suspend fun flushOnce() {
        val peers = contactDao.getOnlineContacts()
        if (peers.isEmpty()) return
        val now = System.currentTimeMillis()
        val onlineIds = peers.map { it.nodeId }.toSet()
        val snapshot = queueManager.getAllBuffered()
        // msgId → set of peers we successfully fanned a broadcast to this pass (thread-safe).
        val broadcastsSentTo = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

        coroutineScope {
            for (peer in peers) {
                val pid = peer.nodeId
                if (isBackedOff(pid, now)) continue // skip recently-failed peers (no 8s stall)
                launch {
                    sendSemaphore.withPermit {
                        peerLock(pid).withLock {
                            runCatching { sendDeliveryReceipts(NodeId(pid)) }
                                .onFailure { Log.w(TAG, "receipt push to ${pid.takeLast(8)} failed", it) }
                            if (snapshot.isNotEmpty()) {
                                sendToSingle(NodeId(pid), snapshot, onlineIds, broadcastsSentTo)
                            }
                        }
                    }
                }
            }
        }

        // Clear a broadcast only once it has actually been fanned to EVERY currently-online peer
        // (fixes the gap where a mid-loop failure cleared a broadcast a peer never received).
        for ((msgId, sentPeers) in broadcastsSentTo) {
            if (sentPeers.containsAll(onlineIds)) {
                queueManager.markDelivered(msgId)
                lifecycle.log(LifecycleEvent.BUFFER_CLEARED, msgId,
                    extra = "broadcast fan-out complete (${onlineIds.size} peers)")
                Log.d(TAG, "BROADCAST cleared: $msgId (sent to ${onlineIds.size} peers)")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SEND: deliver or relay buffered messages
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Send applicable messages from [snapshot] to a single peer.
     * - Direct delivery (dest == peer): send + mark delivered immediately (receipt generated).
     * - Broadcast: send; [broadcastsSentTo] tracks which peers got it so the caller clears only
     *   after a full fan-out.
     * - Custody transfer (dest is someone else): send to mule, keep our copy for receipt.
     */
    private suspend fun sendToSingle(
        peerId: NodeId,
        snapshot: List<DtnMessage>,
        onlinePeerIds: Set<String>,
        broadcastsSentTo: java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>,
    ) {
        val relayApproved: Set<String> = try {
            when {
                // Q-learning: the engine decides FORWARD vs STORE per (message, peer) from the
                // live 9-feature state, and each decision is recorded for training.
                strategySelector.activeType == StrategySelector.StrategyType.QLEARNING ->
                    qDecisionApproved(snapshot, peerId)
                strategySelector.active is com.dtn.mesh.routing.ProphetStrategy ->
                    (strategySelector.active as com.dtn.mesh.routing.ProphetStrategy)
                        .rankForForwardingWithTopology(snapshot, peerId, onlinePeerIds)
                        .map { it.message.id }.toSet()
                strategySelector.active is com.dtn.mesh.routing.MaxPropStrategy ->
                    (strategySelector.active as com.dtn.mesh.routing.MaxPropStrategy)
                        .rankForForwardingWithTopology(snapshot, peerId, onlinePeerIds)
                        .map { it.message.id }.toSet()
                else ->
                    strategySelector.rankForForwarding(snapshot, peerId).map { it.message.id }.toSet()
            }
        } catch (e: Exception) {
            Log.e(TAG, "rankForForwarding threw, falling back to unfiltered", e)
            snapshot.map { it.id }.toSet()
        }

        // Active routing algorithm name — stamped onto each forward for the lifecycle feed and
        // the message-details view so it's clear which strategy made the decision.
        val strat = strategySelector.name

        var sentCount = 0
        for (msg in snapshot) {
            // If a receipt already confirmed delivery elsewhere, clear locally.
            if (receiptStore.isDelivered(msg.id) && msg.destinationNodeId != NodeId.BROADCAST) {
                queueManager.markDelivered(msg.id)
                forgetSentTo(msg.id)
                continue
            }
            // Don't echo back to the original sender.
            if (msg.originNodeId.value == peerId.value) continue

            val isBroadcast = msg.destinationNodeId == NodeId.BROADCAST
            val isDirectDelivery = msg.destinationNodeId.value == peerId.value

            // Don't echo back to the immediate previous hop either. The wire header only
            // carries the ORIGIN, so without this an A→B→C→D chain has C bounce the bundle
            // straight back to B (B isn't the origin A), tying up the half-duplex BLE radio
            // with a duplicate instead of advancing toward D. Direct delivery and broadcast
            // are exempt: direct means the previous hop IS the destination, and broadcasts
            // are meant to fan out to everyone (dedup handles the echo cheaply).
            if (!isBroadcast && !isDirectDelivery && receivedFrom[msg.id] == peerId.value) continue

            // Rate-limit check — but NEVER for direct delivery (dest is RIGHT HERE, send it!)
            if (!isDirectDelivery && !isBroadcast && recentlySentTo(msg.id, peerId.value)) continue

            // Strategy veto applies ONLY to relay (mule) traffic. Direct and broadcast bypass.
            if (!isDirectDelivery && !isBroadcast && msg.id !in relayApproved) {
                continue
            }

            // Send with hop incremented.
            val wireMsg = msg.copy(hopCount = (msg.hopCount + 1).coerceAtMost(255))
            val ok = transport.sendMessage(wireMsg, peerId)
            if (ok == null) {
                Log.w(TAG, "FAIL: ${peerId.value.takeLast(8)} unreachable")
                markSendFailed(peerId.value) // back off so we don't pay the 8s connect again next flush
                break // transport failed — stop trying this peer for now
            }
            markSendOk(peerId.value) // reachable again — clear any backoff

            markSentTo(msg.id, peerId.value)
            runCatching { queueManager.incrementForwardCount(msg.id) }
            benchmark.recordForward(strat)
            sentCount++

            val myId = transport.localNodeId?.value ?: ""
            when {
                isDirectDelivery -> {
                    // BLE direct delivery: the GATT link is established and the write
                    // succeeded — reliable connection, so mark delivered immediately.
                    queueManager.markDelivered(msg.id)
                    forgetSentTo(msg.id)
                    benchmark.recordDelivery(strat, wireMsg.hopCount, System.currentTimeMillis() - msg.createdAtMs)
                    // Q-learning credit: this forward delivered the message to its destination.
                    resolveRewardDelivered(msg.id, peerId.value)
                    lifecycle.log(LifecycleEvent.FORWARDED, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        hopCount = wireMsg.hopCount, forwardCount = msg.forwardCount + 1,
                        ttlRemainingMs = msg.remainingTtlMs(),
                        extra = "DIRECT→${peerId.value.takeLast(8)} ✓delivered [$strat]")
                    // Also emit a buffer-clear so the buffer-history view stops showing this
                    // message as "still buffered" — direct delivery removes it from our buffer.
                    lifecycle.log(LifecycleEvent.BUFFER_CLEARED, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        extra = "delivered directly to ${peerId.value.takeLast(8)}")
                    lifecycle.recordOutgoing(
                        msgId = msg.id, source = msg.originNodeId.value,
                        dest = msg.destinationNodeId.value, me = myId,
                        nextHop = peerId.value, delivered = true, strategy = strat,
                    )
                    Log.d(TAG, "DELIVERED-DIRECT: ${msg.id.take(8)} → ${peerId.value.takeLast(8)}")
                }
                isBroadcast -> {
                    broadcastsSentTo.getOrPut(msg.id) {
                        java.util.Collections.synchronizedSet(mutableSetOf())
                    }.add(peerId.value)
                    lifecycle.log(LifecycleEvent.FORWARDED, msg.id,
                        origin = msg.originNodeId.value, dest = "BROADCAST",
                        hopCount = wireMsg.hopCount, forwardCount = msg.forwardCount + 1,
                        extra = "BCAST→${peerId.value.takeLast(8)} [$strat]")
                    lifecycle.recordOutgoing(
                        msgId = msg.id, source = msg.originNodeId.value,
                        dest = "^all", me = myId,
                        nextHop = peerId.value, delivered = false, strategy = strat,
                    )
                    Log.d(TAG, "BCAST → ${peerId.value.takeLast(8)}: ${msg.id.take(8)}")
                }
                else -> {
                    lifecycle.log(LifecycleEvent.FORWARDED, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        hopCount = wireMsg.hopCount, forwardCount = msg.forwardCount + 1,
                        extra = "RELAY→${peerId.value.takeLast(8)} [$strat]")
                    lifecycle.recordOutgoing(
                        msgId = msg.id, source = msg.originNodeId.value,
                        dest = msg.destinationNodeId.value, me = myId,
                        nextHop = peerId.value, delivered = false, strategy = strat,
                    )
                    Log.d(TAG, "RELAYED: ${msg.id.take(8)} → mule ${peerId.value.takeLast(8)}")
                }
            }
        }
        if (sentCount > 0) Log.d(TAG, "$sentCount msgs sent to ${peerId.value.takeLast(8)}")
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
            if (transport.sendMessage(receiptBundle, peerId) == null) break
            chunk.forEach { alreadySent.add(it.first) }
            sentUids += chunk.size
        }
        if (sentUids > 0) Log.d(TAG, "Sent $sentUids new receipts to ${peerId.value.takeLast(8)}")
    }

    /**
     * Parse a receipt bundle payload — a concatenation of 16-byte UUIDs.
     * For each UID: mark it delivered locally and clear from buffer if present.
     */
    private suspend fun processInboundReceipts(msg: DtnMessage) {
        val payload = msg.payloadBytes
        if (payload.size < UUID_BYTES) return
        val count = payload.size / UUID_BYTES

        var cleared = 0
        for (i in 0 until count) {
            val off = i * UUID_BYTES
            val bb = java.nio.ByteBuffer.wrap(payload, off, UUID_BYTES)
            val uuid = UUID(bb.long, bb.long).toString()
            lifecycle.log(LifecycleEvent.ACK_RECEIVED, uuid,
                origin = msg.originNodeId.value, extra = "from=${msg.originNodeId.value.takeLast(8)}")
            receiptStore.markDelivered(uuid)
            if (queueManager.existsInBuffer(uuid)) {
                queueManager.markDelivered(uuid)
                lifecycle.log(LifecycleEvent.BUFFER_CLEARED, uuid)
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

        // Delivery receipt bundle — handle and return.
        if (msg.messageType == DtnMessageType.ROUTING_ACK) {
            processInboundReceipts(msg)
            return
        }

        // Routing summary from a peer — feed to strategy for transitivity + peer-P cache.
        if (msg.messageType == DtnMessageType.ROUTING_SUMMARY) {
            runCatching {
                // The wire ROUTING_SUMMARY frame doesn't carry the producing strategy's tag, so
                // reconstruct it with OUR active strategy's tag. Two nodes running the same
                // strategy interoperate; a summary from a peer running a different strategy is
                // harmlessly ignored (its payload format wouldn't match anyway).
                strategySelector.onRoutingSummaryReceived(
                    peerId = msg.originNodeId,
                    summary = RoutingSummary(
                        strategyTag = strategySelector.name,
                        payload = msg.payloadBytes,
                    ),
                )
                Log.d(TAG, "ROUTING-SUMMARY-IN from ${msg.originNodeId.value.takeLast(8)}")
            }.onFailure { Log.w(TAG, "routing-summary decode failed from ${msg.originNodeId.value}", it) }
            return
        }

        if (!msg.messageType.isUserData) return

        // Remember the real previous hop (from the transport, not the wire origin) so the
        // relay logic never echoes this bundle straight back to whoever just gave it to us.
        packet.viaPeer?.let { receivedFrom[msg.id] = it.value }

        val myId = transport.localNodeId?.value
        val isForMe = myId != null && msg.destinationNodeId.value == myId
        val isBroadcast = msg.destinationNodeId == NodeId.BROADCAST

        if (isForMe || isBroadcast) {
            // MESSAGE IS FOR US — ingest and mark delivered locally.
            val stored = queueManager.ingest(msg)
            if (stored) {
                queueManager.markDelivered(msg.id)
                receiptStore.markDelivered(msg.id) // we now have a receipt to propagate
                benchmark.recordDelivery(strategySelector.name, msg.hopCount,
                    System.currentTimeMillis() - msg.createdAtMs)
                resolveRewardDelivered(msg.id, packet.viaPeer?.value)
                lifecycle.log(LifecycleEvent.DELIVERED, msg.id,
                    origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                    hopCount = msg.hopCount, ttlRemainingMs = msg.remainingTtlMs())
                lifecycle.recordIncoming(
                    msgId = msg.id, source = msg.originNodeId.value,
                    dest = msg.destinationNodeId.value,
                    // Real immediate sender (from the transport), NOT the wire origin — origin
                    // is only correct for a 1-hop message. Falls back to origin if unknown.
                    prevHop = packet.viaPeer?.value ?: msg.originNodeId.value,
                    me = transport.localNodeId?.value ?: "",
                    delivered = true,
                )
                lifecycle.log(LifecycleEvent.ACK_GENERATED, msg.id,
                    extra = "will push to ${contactDao.getOnlineContacts().size} online peers")
                Log.d(TAG, "RX-MINE: ${msg.id.take(8)} from ${msg.originNodeId.value.takeLast(8)}")
                // Propagate the fresh receipt immediately (non-blocking; keeps the loop hot).
                requestFlush()
            } else {
                // DUPLICATE — we already have this message. But the sender clearly never
                // got our ACK (otherwise they wouldn't re-send). If we have a receipt for
                // this msgId, push it again immediately so the sender's buffer clears.
                if (receiptStore.isDelivered(msg.id)) {
                    lifecycle.log(LifecycleEvent.DUPLICATE, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        extra = "already delivered — re-pushing ACK")
                    Log.d(TAG, "DUP-MINE: ${msg.id.take(8)} — re-sending ACK to clear sender buffer")
                    requestFlush()
                } else {
                    lifecycle.log(LifecycleEvent.DUPLICATE, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        extra = "already ingested")
                }
            }
            return
        }

        // MESSAGE FOR SOMEONE ELSE — we're a mule.
        // If we already have a receipt saying it's delivered, ignore.
        if (receiptStore.isDelivered(msg.id)) {
            Log.d(TAG, "RX-MULE ignored (already delivered): ${msg.id.take(8)}")
            return
        }
        val stored = queueManager.ingest(msg)
        if (stored) {
            Log.d(TAG, "RX-MULE: ${msg.id.take(8)} → dest ${msg.destinationNodeId.value.takeLast(8)}")
            lifecycle.recordIncoming(
                msgId = msg.id, source = msg.originNodeId.value,
                dest = msg.destinationNodeId.value,
                // Real immediate sender (from the transport), NOT the wire origin.
                prevHop = packet.viaPeer?.value ?: msg.originNodeId.value,
                me = transport.localNodeId?.value ?: "",
                delivered = false,
            )
            // Try to deliver onward immediately if destination is online (non-blocking).
            requestFlush()
        }
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
            )
            val reward = d.reward ?: continue
            qEngine.update(state, ForwardingAction.valueOf(d.action), reward, nextState = null)
            applied.add(d.id)
        }
        // Mark consumed so getPendingUpdates() doesn't hand them back next cycle (which would
        // over-train the engine on the same experiences).
        if (applied.isNotEmpty()) runCatching { decisionDao.markApplied(applied) }
        if (applied.isNotEmpty()) lifecycle.console("Q-LEARNING: applied ${applied.size} reward updates " +
            "(ε=%.3f, updates=${qEngine.totalUpdates})".format(qEngine.currentEpsilon))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Q-LEARNING decision + training helpers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Build the 9-feature [ForwardingState] for deciding whether to forward [msg] to [peer].
     * All features are normalised to [0,1]. Pulls per-peer stats from the contacts table and
     * live buffer occupancy — data only the orchestrator has, which is why the Q decision lives
     * here rather than inside [QLearningStrategy].
     */
    private suspend fun buildForwardingState(msg: DtnMessage, peer: NodeId): ForwardingState {
        val c = runCatching { contactDao.getByNodeId(peer.value) }.getOrNull()
        val rssi = c?.lastRssi ?: -200
        val rssiNorm = if (rssi <= -200) 0.0 else ((rssi + 100.0) / 60.0).coerceIn(0.0, 1.0) // [-100,-40]→[0,1]
        val snr = (c?.lastSnr ?: -100f).toDouble()
        val snrNorm = ((snr + 20.0) / 40.0).coerceIn(0.0, 1.0) // [-20,20]→[0,1]
        val total = c?.totalEncounters ?: 0
        val encFreq = (total.toDouble() / 20.0).coerceIn(0.0, 1.0)
        val avgDurMs = if (c != null && c.totalEncounters > 0)
            c.totalEncounterDurationMs.toDouble() / c.totalEncounters else 0.0
        val encDur = (avgDurMs / 60_000.0).coerceIn(0.0, 1.0) // normalise by 60s
        val deliveryProb = strategySelector.getDeliveryProbability(peer).coerceIn(0.0, 1.0)
        val ttlFrac = (msg.remainingTtlMs().toDouble() / msg.ttlMs.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        val bufOcc = runCatching { queueManager.getBufferOccupancy() }.getOrDefault(0.0)
        val succ = if (c != null && c.deliveryAttemptCount > 0)
            c.deliverySuccessCount.toDouble() / c.deliveryAttemptCount else 0.0
        val dupNorm = (msg.duplicateCount.toDouble() / 10.0).coerceIn(0.0, 1.0)
        return ForwardingState(
            rssiNorm = rssiNorm, snrNorm = snrNorm, encounterFrequency = encFreq,
            encounterDuration = encDur, deliveryProbability = deliveryProb,
            remainingTtlFraction = ttlFrac, bufferOccupancy = bufOcc,
            historicalSuccessRate = succ, duplicateCountNorm = dupNorm,
        )
    }

    /**
     * Q-learning relay decision for [peerId] over [snapshot]. For each relay candidate it builds
     * the state, asks the Double-Q engine (ε-greedy) whether to FORWARD, and records a
     * [ForwardingDecisionEntity] for later reward backfill + batch training. Direct delivery and
     * broadcast are always approved (they aren't relay decisions). Recording is throttled per
     * (message, peer) so the table doesn't flood on every 4s flush.
     */
    private suspend fun qDecisionApproved(snapshot: List<DtnMessage>, peerId: NodeId): Set<String> {
        val approved = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        val toRecord = mutableListOf<ForwardingDecisionEntity>()
        for (msg in snapshot) {
            if (msg.hopCount >= 10) continue
            if (msg.destinationNodeId == NodeId.BROADCAST || msg.destinationNodeId.value == peerId.value) {
                approved.add(msg.id) // handled as broadcast/direct by the send loop
                continue
            }
            val state = buildForwardingState(msg, peerId)
            val (action, exploratory) = qEngine.selectActionEpsilonGreedy(state)
            if (action == ForwardingAction.FORWARD) approved.add(msg.id)

            // Throttle decision logging to once per (msg,peer) per retry interval.
            val logKey = "${msg.id}|${peerId.value}"
            if (now - (qLogged[logKey] ?: 0L) >= SEND_RETRY_INTERVAL_MS) {
                qLogged[logKey] = now
                toRecord.add(ForwardingDecisionEntity(
                    timestampMs = now, messageId = msg.id, peerNodeId = peerId.value,
                    stateRssiNorm = state.rssiNorm, stateSnrNorm = state.snrNorm,
                    stateEncounterFrequency = state.encounterFrequency,
                    stateEncounterDuration = state.encounterDuration,
                    stateDeliveryProbability = state.deliveryProbability,
                    stateRemainingTtlFraction = state.remainingTtlFraction,
                    stateBufferOccupancy = state.bufferOccupancy,
                    stateHistoricalSuccessRate = state.historicalSuccessRate,
                    stateDuplicateCountNorm = state.duplicateCountNorm,
                    action = action.name, qValueSelected = qEngine.computeUtility(state),
                    epsilon = qEngine.currentEpsilon, wasExploratory = exploratory,
                    routingStrategy = "Q-LEARNING",
                ))
            }
        }
        if (toRecord.isNotEmpty()) runCatching { decisionDao.insertAll(toRecord) }
        return approved
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
            }
        }.onFailure { Log.w(TAG, "reward backfill failed for ${msgId.take(8)}", it) }
    }
}
