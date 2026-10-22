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
        /** Apply any reward-assigned-but-unapplied Q decisions now (real-time training). */
        data object DrainQUpdates : OrchestratorEvent()
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

    /** Reassembles multi-fragment messages for THIS node, keyed by canonical parent id. */
    private val reassembler = FragmentReassembler()

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

    /**
     * [peer] is another phone on our LoRa hub and no link of ours ends at it (no BLE / Wi-Fi
     * Direct). The hub routes on the bundle's destination, so the only thing worth handing over
     * "for" such a phone is a bundle addressed to it; broadcasts and receipts reach it through the
     * hub's own fan-out, and custody for a third party would just be a hub flood.
     */
    private fun isHubOnly(peer: NodeId): Boolean = transport.isHubPeer(peer) && !transport.reachesDirectly(peer)

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
                    }
                }
                if ((present - previous).isNotEmpty()) ch.trySend(OrchestratorEvent.FlushBuffer)
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
        sc.launch {
            // Restore learned PROPHET predictability from the DB BEFORE processing events, so a
            // restart doesn't begin from a blank routing table. Runs on this single consumer
            // coroutine so it can't race the pTable mutations that onEncounter performs.
            seedRoutingProbabilitiesFromDb()
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
        val radioPeers = contactDao.getOnlineContacts().map { it.nodeId }
        // Contacts present on the internet relay and NOT reachable by a local radio. A radio link
        // always wins (normal DTN semantics incl. custody); relay-only peers get relay semantics.
        val myId = transport.localNodeId?.value
        val radioSet = radioPeers.toSet()
        val relayPeers = transport.internetPeers.value
            .filter { it != myId && it !in radioSet }
            .filter { runCatching { contactDao.getByNodeId(it) }.getOrNull() != null }
        if (radioPeers.isEmpty() && relayPeers.isEmpty()) return
        val now = System.currentTimeMillis()
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

        // Broadcasts / group messages are epidemic: they are NOT cleared after fan-out. They keep
        // propagating to peers met later (store-carry-forward for broadcast) until their TTL
        // expires, bounded by per-peer send throttling (recentlySentTo) + receiver-side dedup, and
        // reclaimed by TTL expiry or buffer pressure. Clearing a broadcast once every *currently*
        // online peer had it (the old behaviour) stranded peers that were offline at that instant.
        if (broadcastsSentTo.isNotEmpty()) {
            Log.d(TAG, "Broadcast fan-out this pass: ${broadcastsSentTo.size} msg(s) kept for epidemic spread")
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
        val relayApproved: Set<String> = if (relay || peerIsHub || hubOnly) emptySet() else try {
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
                lifecycle.markRouteDelivered(msg.id) // route observation → RESOLVED, matches chat
                if (msg.messageType == DtnMessageType.DATA && lifecycle.isTracked(msg.id)) {
                    lifecycle.log(LifecycleEvent.BUFFER_CLEARED, msg.id,
                        origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                        extra = "buffer copy cleared — delivery already confirmed by a receipt")
                }
                forgetSentTo(msg.id)
                continue
            }
            // Don't echo back to the original sender.
            if (msg.originNodeId.value == peerId.value) continue

            val isBroadcast = msg.destinationNodeId == NodeId.BROADCAST
            val isDirectDelivery = msg.destinationNodeId.value == peerId.value

            // Relay-only peers never take custody of someone else's DM over the internet.
            if (relay && !isDirectDelivery && !isBroadcast) continue

            // A phone reached only through our hub: just the DMs addressed to it ([isHubOnly]).
            if (hubOnly && !isDirectDelivery) continue

            // The hub: each DM once per hub session (it keeps a copy and replays it to the phone
            // when it registers, so re-sending adds nothing), and never one whose destination we
            // reach ourselves right now — that phone's own pass delivers it (directly, through the
            // hub, or over the relay) without flooding it over LoRa.
            if (peerIsHub && !isBroadcast && !isDirectDelivery) {
                if (sentTo[msg.id]?.containsKey(peerId.value) == true) continue
                val dest = msg.destinationNodeId
                if (transport.isRadioReachable(dest) || dest.value in transport.internetPeers.value) continue
            }

            // Don't echo back to the immediate previous hop either. The wire header only
            // carries the ORIGIN, so without this an A→B→C→D chain has C bounce the bundle
            // straight back to B (B isn't the origin A), tying up the half-duplex BLE radio
            // with a duplicate instead of advancing toward D. Direct delivery is exempt (the
            // previous hop IS the destination).
            if (!isDirectDelivery && receivedFrom[msg.id] == peerId.value) continue

            // Broadcasts (incl. group messages and announces) go to each peer ONCE per encounter
            // or presence session. Previously they were exempt from every throttle, so each
            // buffered broadcast was re-sent to every online peer on every 4 s flush for its whole
            // TTL — a GATT connect + write per bundle per peer, starving real traffic. The session
            // ends when the peer goes offline (forgetSentToPeer), so a re-encounter re-syncs.
            if (isBroadcast && sentTo[msg.id]?.containsKey(peerId.value) == true) continue

            // Rate-limit: never for a direct delivery over a link that ends at the destination
            // (it's RIGHT HERE — send it). Anything that has to wait for an end-to-end receipt
            // (same-hub hand-off, relay, hub flood, mule) retries on the 30 s window instead.
            val directOverRadio = isDirectDelivery && transport.reachesDirectly(peerId)
            if (!directOverRadio && !isBroadcast && recentlySentTo(msg.id, peerId.value)) continue

            // Strategy veto applies ONLY to relay (mule) traffic. Direct, broadcast and hub
            // hand-offs bypass it.
            if (!isDirectDelivery && !isBroadcast && !peerIsHub && msg.id !in relayApproved) {
                continue
            }

            // Send with hop incremented.
            val wireMsg = msg.copy(hopCount = (msg.hopCount + 1).coerceAtMost(255))
            val outcome = transport.sendMessageVia(wireMsg, peerId)
            if (outcome == null) {
                Log.w(TAG, "FAIL: ${peerId.value.takeLast(8)} unreachable")
                markSendFailed(peerId.value) // back off so we don't pay the 8s connect again next flush
                break // transport failed — stop trying this peer for now
            }
            markSendOk(peerId.value) // reachable again — clear any backoff

            markSentTo(msg.id, peerId.value)
            sentCount++

            val myId = transport.localNodeId?.value ?: ""
            val path = outcome.path
            val peer8 = peerId.value.takeLast(8)

            // Control traffic (key announces etc.) is not a user message: engine console only, so
            // it never shows up as mystery "broadcast messages in transit".
            if (msg.messageType != DtnMessageType.DATA) {
                lifecycle.console("CTRL ${msg.messageType.name} ${msg.id.take(8)} → $peer8 via ${path.label}")
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
            benchmark.recordForward(strat)

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
                    benchmark.recordDelivery(strat, wireMsg.hopCount, System.currentTimeMillis() - msg.createdAtMs)
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
                    lifecycle.recordOutgoing(
                        msgId = msg.id, source = msg.originNodeId.value,
                        dest = msg.destinationNodeId.value, me = myId,
                        nextHop = peerId.value, delivered = false, strategy = strat,
                        path = path.label, channel = msg.channel,
                    )
                    Log.d(TAG, "RELAYED: ${msg.id.take(8)} → $peer8 via ${path.label}")
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
            // Receipts spread epidemically, so most are for other people's messages: only the ones
            // about a message we hold or track belong in the message journal.
            if (lifecycle.isTracked(uuid) || queueManager.existsInBuffer(uuid)) {
                lifecycle.log(LifecycleEvent.ACK_RECEIVED, uuid,
                    origin = msg.originNodeId.value, extra = "delivery receipt from $from8 via ${via.journalLabel()}")
            } else {
                lifecycle.console("RECEIPT ${uuid.take(8)} from $from8 via ${via.journalLabel()} (not ours, relaying)")
            }
            receiptStore.markDelivered(uuid)
            // An end-to-end receipt confirms delivery — upgrade our route observation to RESOLVED
            // whether or not we still hold a buffer copy (no-op if we never tracked this route).
            lifecycle.markRouteDelivered(uuid, via = via?.journalLabel().orEmpty(), confirmedBy = msg.originNodeId.value)
            // …and our own chat row, even if our buffer copy is mid-forward (FORWARDING) or gone.
            runCatching { chatDao.markOutgoingDelivered(uuid) }
            if (queueManager.existsInBuffer(uuid)) {
                queueManager.markDelivered(uuid)
                lifecycle.log(LifecycleEvent.BUFFER_CLEARED, uuid,
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
                runCatching {
                    strategySelector.onRoutingSummaryReceived(
                        peerId = msg.originNodeId,
                        summary = RoutingSummary(strategyTag = strategySelector.name, payload = msg.payloadBytes),
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
            if (!processAnnounce(msg)) return
            val stored = queueManager.ingest(msg)
            // The peer that handed it to us already has it — don't push it straight back.
            packet.viaPeer?.let { markSentTo(msg.id, it.value) }
            if (stored) {
                lifecycle.console("ANNOUNCE from ${msg.originNodeId.value.takeLast(8)} via ${packet.transport.journalLabel()} — keys pinned, relaying")
                requestFlush() // epidemic relay of the announce
            }
            return
        }

        if (!msg.messageType.isUserData) return // unknown control type — drop (never shown as chat)

        // Remember the real previous hop (from the transport, not the wire origin) so the
        // relay logic never echoes this bundle straight back to whoever just gave it to us.
        packet.viaPeer?.let { receivedFrom[msg.id] = it.value }

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
                val opened = openAndPersistInbound(msg, convKey = msg.originNodeId.value, peer = msg.originNodeId.value)
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
                rememberTombstone(msg.id, msg.originNodeId.value, msg.destinationNodeId.value,
                    SeenBundleEntity.Status.DELIVERED, msg.createdAtMs + msg.ttlMs)
                benchmark.recordDelivery(strategySelector.name, msg.hopCount,
                    System.currentTimeMillis() - msg.createdAtMs)
                resolveRewardDelivered(msg.id, packet.viaPeer?.value)
                val via = packet.transport.journalLabel()
                val prev8 = (packet.viaPeer ?: msg.originNodeId).value.takeLast(8)
                lifecycle.log(LifecycleEvent.DELIVERED, msg.id,
                    origin = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                    hopCount = msg.hopCount, ttlRemainingMs = msg.remainingTtlMs(),
                    extra = "received from $prev8 via $via · decrypted")
                lifecycle.recordIncoming(
                    msgId = msg.id, source = msg.originNodeId.value, dest = msg.destinationNodeId.value,
                    prevHop = packet.viaPeer?.value ?: msg.originNodeId.value,
                    me = transport.localNodeId?.value ?: "", delivered = true, path = via,
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
                )
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
     * Verify a signed ANNOUNCE and pin the peer's public keys (trust-on-first-use).
     *
     * @return false when the announce must be neither pinned nor relayed: it was issued before
     *   this phone's identity existed. Clearing the app's data (or reinstalling) creates a new
     *   identity while the old one's announces still circulate for their TTL — in other phones'
     *   buffers and in hub replays — and pinning them showed this phone's former self as a
     *   contact ("I can see myself"). Live peers re-announce every 5 minutes, so a genuine
     *   announce is only delayed, never lost.
     */
    private suspend fun processAnnounce(msg: DtnMessage): Boolean {
        val announce = Announce.parseAndVerify(msg.payloadBytes) ?: run {
            Log.w(TAG, "ANNOUNCE failed signature verification from ${msg.originNodeId.value.takeLast(8)}")
            return true
        }
        if (NodeId.fromNodeNum(announce.nodeNum) != msg.originNodeId) {
            Log.w(TAG, "ANNOUNCE node-id binding mismatch; rejecting ${msg.originNodeId.value.takeLast(8)}")
            return true
        }
        val bornAtSec = localIdentity.createdAtMs / 1000L
        if (bornAtSec > 0 && announce.issuedAtSec.toLong() < bornAtSec) {
            lifecycle.console("ANNOUNCE from ${msg.originNodeId.value.takeLast(8)} issued before this phone's " +
                "identity was created — ignored (likely this phone's identity before its data was cleared)")
            return false
        }
        runCatching {
            contactDao.insertIfNew(ContactEntity(nodeId = msg.originNodeId.value))
            contactDao.updateKeys(msg.originNodeId.value, announce.xPub, announce.edPub, announce.name, System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "pinning announce keys failed", it) }
        Log.i(TAG, "ANNOUNCE pinned keys for ${msg.originNodeId.value.takeLast(8)} (${announce.name})")
        return true
    }

    /**
     * Decrypt an inbound DATA bundle addressed to us (or a group we're in) and persist it to the
     * chat log, reassembling multi-fragment messages by their canonical parent id first. Byte-exact
     * reassembly (decode to text only after joining all chunks) keeps multi-byte UTF-8 chars intact
     * when a character straddles a fragment boundary.
     */
    /** @return true if the bundle decrypted (a fragment counts even before reassembly completes). */
    private suspend fun openAndPersistInbound(msg: DtnMessage, convKey: String, peer: String?): Boolean {
        val bytes = envelope.openBytes(msg) ?: run {
            Log.w(TAG, "RX undecryptable ${msg.id.take(8)} (no key / not a group member?)")
            return false
        }
        if (msg.fragmentTotal <= 1) {
            persistInboundChat(msg.id, msg.channel, convKey, peer, String(bytes, Charsets.UTF_8))
            return true
        }
        val parentKey = com.dtn.mesh.crypto.Fragmenter.canonicalParent(msg.id)
        val full = reassembler.add(parentKey, msg.fragmentIndex, msg.fragmentTotal, bytes) ?: return true
        persistInboundChat(parentKey, msg.channel, convKey, peer, String(full, Charsets.UTF_8))
        return true
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
