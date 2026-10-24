package com.dtn.mesh.routing

import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.ForwardingState
import com.dtn.mesh.learning.ForwardingStateBuilder
import com.dtn.mesh.learning.QLearningConfig
import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.ForwardingAction
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.receiver.DtnWireCodec
import com.dtn.mesh.routing.StrategySelector.StrategyType
import com.dtn.mesh.service.CarrierRules
import com.dtn.mesh.service.DmFallback
import com.dtn.mesh.service.HoldReason
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.random.Random

/**
 * One simulated phone: the app's own routing objects (built like `SchedulerModule` does) plus the
 * bookkeeping `DtnOrchestrator` keeps per phone (send tracking, previous hops, receipts, buffer rows).
 */
class SimPhone(val label: String, val id: NodeId, type: StrategyType, random: Random, epsilon: Double?) {
    val prophet = ProphetStrategy()
    val stableProphet = ProphetStrategy(ProphetConfig(stabilityAware = true))
    val maxProp = MaxPropStrategy()
    val diMaxProp = DiMaxPropStrategy()
    val routing = StrategySelector(prophet, maxProp, EpidemicStrategy(), stableProphet, QLearningStrategy(), diMaxProp, type)
        .apply { setSelfId(id.value) } // as DtnOrchestrator.start()
    val engine = DoubleQLearningEngine(QLearningConfig(), random).apply { if (epsilon != null) setEpsilon(epsilon) }

    enum class Status { BUFFERED, DELIVERED }
    class Row(var msg: DtnMessage, val bufferedAtMs: Long, var status: Status = Status.BUFFERED)

    /** The messages table: every bundle this phone ever stored. Rows stay after delivery, as in Room. */
    val rows = LinkedHashMap<String, Row>()
    /** DeliveryReceiptStore. */
    val receipts = LinkedHashSet<String>()
    val receiptsSentTo = HashMap<String, MutableSet<String>>()
    val sentTo = HashMap<String, HashMap<String, Long>>()
    /** msgId → every phone that handed us a copy (the previous hops). */
    val receivedFrom = HashMap<String, MutableSet<String>>()
    /** Delivered-here tombstones (SeenBundleEntity DELIVERED). */
    val tombstones = HashSet<String>()
    /** DMs for this phone that arrived (its chat). */
    val inbox = LinkedHashMap<String, DtnMessage>()
    val qChoice = HashMap<String, Pair<CarrierRules.QKept, Long>>()

    /** The contacts row of a peer (the Q-learning state's per-peer inputs). */
    class PeerStats {
        var encounters = 0
        var lastRssi = -200
        var durationMs = 0L
        var since = -1L
        var attempts = 0
        var successes = 0
    }
    val peerStats = HashMap<String, PeerStats>()
    fun stats(peer: SimPhone) = peerStats.getOrPut(peer.id.value) { PeerStats() }

    fun holds(msgId: String) = rows[msgId]?.status == Status.BUFFERED

    /** `getBufferedMessages()`: BUFFERED and not expired, oldest first. */
    fun snapshot(now: Long): List<DtnMessage> = rows.values
        .filter { it.status == Status.BUFFERED && it.msg.createdAtMs + it.msg.ttlMs > now }
        .sortedBy { it.msg.createdAtMs }
        .map { it.msg.copy(bufferedAtMs = it.bufferedAtMs) }

    fun forgetSentTo(msgId: String) {
        sentTo.remove(msgId)
        receivedFrom.remove(msgId)
    }

    fun facts(now: Long) = object : CarrierRules.Facts {
        override fun delivered(msgId: String) = msgId in receipts
        override fun cameFrom(msgId: String, peer: String) = receivedFrom[msgId]?.contains(peer) == true
        override fun sentThisSession(msgId: String, peer: String) = sentTo[msgId]?.containsKey(peer) == true
        override fun sentRecently(msgId: String, peer: String) = sentTo[msgId]?.get(peer)?.let { now - it < 30_000L } == true
        // Every simulated hand-over is an acknowledged BLE write: a copy sent this session is held.
        override fun handedOverThisSession(msgId: String, peer: String) = sentThisSession(msgId, peer)
        override fun reachesDirectly(peer: NodeId) = true // every simulated link is Bluetooth
        override fun reachableElsewhere(dest: NodeId) = false
    }
}

/**
 * A scripted mesh of [SimPhone]s running the app's real routing code: one [StrategySelector] and one
 * Q-learning engine per phone, encounters + routing summaries through the entry points
 * `DtnOrchestrator` uses (BLE distance, the real summary codecs over [DtnWireCodec]), and every
 * hand-over decided by [CarrierRules]. Simulated: the clock, the radio (contacts are scripted, every
 * write succeeds) and storage (a map per phone). No hub and no relay, so the 5 s fallback
 * ([DmFallback]) must never add a path.
 *
 * A contact lasts [contact]'s `seconds`: an encounter event + summary exchange every 8 s (the BLE
 * encounter cooldown) and a flush round every 4 s (the orchestrator's periodic flush). The
 * housekeeping worker's aging ([StrategySelector.onPeriodicAge]) runs every 15 simulated minutes.
 */
class MeshSim(
    val type: StrategyType,
    labels: List<String> = LABELS,
    qSeed: Long = 1L,
    qEpsilon: Double? = 0.0,
    private val agingEveryMs: Long = AGING_MS,
) {
    companion object {
        val LABELS = listOf("A", "B", "C", "D", "E", "F", "G", "H")
        const val AGING_MS = 15 * 60_000L
        const val FLUSH_MS = 4_000L
        const val ENCOUNTER_MS = 8_000L
        const val RETRY_MS = 30_000L
    }

    val phones: Map<String, SimPhone> = labels.mapIndexed { i, l ->
        l to SimPhone(l, NodeId("!%08x".format(0x0A00_0001 + i)), type, Random(qSeed * 1_000 + i), qEpsilon)
    }.toMap()
    private val byId = phones.values.associateBy { it.id.value }
    fun label(id: String): String = byId[id]?.label ?: id.takeLast(4)
    operator fun get(label: String): SimPhone = phones.getValue(label)

    /** The simulated clock. Starts at the real time, as ProphetStrategy ages from its construction. */
    var now: Long = System.currentTimeMillis()
        private set
    private var nextAgingAt = now + agingEveryMs
    private var uuids = 0L
    var step: String = "training"

    // ── What happened to the watched messages ──────────────────────────────

    /**
     * The numbers a strategy decided (carrier X, candidate Y) with: PRoPHET P(X,D) / P(Y,D) and the live
     * path's next hop; MaxProp the cheapest next hop and its cost; DiMaxProp / Q-learning C(X,D) / C(Y,D).
     */
    data class Metric(
        val mine: Double? = null,
        val theirs: Double? = null,
        val nextHop: String? = null,
        val verdict: String = "",
        val copiesSoFar: Int = 0,
        val text: String = "",
    )

    data class Send(
        val step: String, val from: String, val to: String, val atMs: Long, val hop: Int, val ttlMin: Int,
        /** The receiver stored it (false = it already had the message). */
        val stored: Boolean,
        val direct: Boolean,
        val metric: Metric?,
    ) {
        val why: String get() = metric?.text.orEmpty()
    }
    data class Held(val step: String, val at: String, val peer: String, val reason: HoldReason, val metric: Metric?) {
        val why: String get() = metric?.text.orEmpty()
    }
    data class Cleared(val step: String, val at: String, val by: String)

    val watched = mutableSetOf<String>()
    val sends = mutableListOf<Send>()
    val held = mutableListOf<Held>()
    val cleared = mutableListOf<Cleared>()
    var fallbackPaths = 0
        private set
    /** The last Q-learning choice per "carrier|peer|msg". */
    val qChoices = LinkedHashMap<String, CarrierRules.QDecision>()

    // ── Scripting ─────────────────────────────────────────────────────────

    /** A DM [from] → [to], created now (TTL [ttlMin] minutes), watched. */
    fun dm(from: String, to: String, ttlMin: Int = 240, hopCount: Int = 0): String {
        val id = UUID(0x5157_0000L, ++uuids).toString()
        val msg = DtnMessage(
            id = id, originNodeId = get(from).id, destinationNodeId = get(to).id,
            payloadBytes = ByteArray(60) { it.toByte() }, createdAtMs = now, ttlMs = ttlMin * 60_000L,
            messageType = DtnMessageType.DATA, flags = 1, hopCount = hopCount,
        )
        get(from).rows[id] = SimPhone.Row(msg, now)
        watched += id
        created[id] = msg
        return id
    }

    /** The watched DMs as their origin created them. */
    val created = HashMap<String, DtnMessage>()

    /** [times] meetings of the same group, [gapMs] apart (training history). */
    fun meet(vararg labels: String, times: Int = 1, seconds: Int = 40, gapMs: Long = 60_000L, rssi: Int = -65) {
        repeat(times) { i ->
            contact(*labels, seconds = seconds, rssi = rssi)
            if (i < times - 1) advance(gapMs)
        }
    }

    /**
     * Everyone in [labels] is in Bluetooth range of everyone else for [seconds]: encounter events and
     * summary exchanges every 8 s, a flush round (every phone, every online peer) every 4 s. Then they
     * part (each side's offline handling). [rssi] can be set per phone.
     */
    fun contact(vararg labels: String, seconds: Int = 24, rssi: Int = -65, rssiOf: Map<String, Int> = emptyMap(), step: String? = null) {
        if (step != null) this.step = step
        val group = labels.map(::get)
        val pairs = group.flatMapIndexed { i, x -> group.drop(i + 1).map { y -> x to y } }
        fun rssiFor(x: SimPhone, y: SimPhone) = minOf(rssiOf[x.label] ?: rssi, rssiOf[y.label] ?: rssi)
        var t = 0L
        while (t < seconds * 1_000L) {
            if (t % ENCOUNTER_MS == 0L) {
                for ((x, y) in pairs) { encounter(x, y, rssiFor(x, y)); encounter(y, x, rssiFor(x, y)) }
                for ((x, y) in pairs) { summary(x, y); summary(y, x) }
            }
            for (x in group) flush(x, group - x)
            t += FLUSH_MS
            advance(FLUSH_MS)
        }
        for ((x, y) in pairs) { offline(x, y); offline(y, x) }
    }

    /** Let [ms] pass (the aging worker runs every 15 min). */
    fun advance(ms: Long) {
        val until = now + ms
        while (agingEveryMs > 0 && nextAgingAt <= until) {
            now = nextAgingAt
            for (p in phones.values) { // ForwardingWorker → StrategySelector.onPeriodicAge()
                p.prophet.ageTo(now)
                p.stableProphet.ageTo(now)
                p.maxProp.onPeriodicAge()
                p.diMaxProp.onPeriodicAge()
            }
            nextAgingAt += agingEveryMs
        }
        now = until
    }

    fun minutes(n: Int) = advance(n * 60_000L)

    // ── The orchestrator's steps ──────────────────────────────────────────

    /** onPeerEvent (online, BLE): contact stats, then the strategies learn the encounter with its distance. */
    private fun encounter(x: SimPhone, y: SimPhone, rssi: Int) {
        val s = x.stats(y)
        if (s.since < 0) { s.encounters++; s.since = now }
        s.lastRssi = rssi
        val d = RadioDistanceEstimator.estimateMeters(rssi, RadioDistanceEstimator.Transport.BLE)
        x.routing.onEncounter(y.id, ContactRecord(
            peerId = y.id, startTimeMs = now, rssi = rssi, snr = 0f, distanceMeters = d,
            radio = RadioDistanceEstimator.Transport.BLE,
        ))
    }

    /** sendRoutingSummary → the peer's ROUTING_SUMMARY handling (tag from the payload's first byte). */
    private fun summary(x: SimPhone, y: SimPhone) {
        x.diMaxProp.localBufferOccupancy = 0.0
        val s = x.routing.buildRoutingSummary()
        if (s.payload.isEmpty() || s.payload.size > DtnMessage.MAX_SINGLE_PACKET_PAYLOAD) return
        val bundle = DtnMessage(
            id = UUID(0x5357_0000L, ++uuids).toString(), originNodeId = x.id, destinationNodeId = y.id,
            payloadBytes = s.payload, createdAtMs = now, ttlMs = 300_000L, messageType = DtnMessageType.ROUTING_SUMMARY,
        )
        val got = DtnWireCodec.decode(DtnWireCodec.encode(bundle, now), nowMs = now)!!
        val tag = if (DiMaxPropStrategy.isSummary(got.payloadBytes)) DiMaxPropStrategy.TAG else y.routing.name
        y.routing.onRoutingSummaryReceived(got.originNodeId, RoutingSummary(tag, got.payloadBytes))
    }

    /** onPeerEvent (offline): the encounter closes and the per-peer send state is dropped. */
    private fun offline(x: SimPhone, y: SimPhone) {
        val s = x.stats(y)
        if (s.since >= 0) { s.durationMs += now - s.since; s.since = -1 }
        for ((_, peers) in x.sentTo) peers.remove(y.id.value)
        x.receiptsSentTo.remove(y.id.value)
    }

    /** flushOnce for [x]: online peers by PRoPHET P (the contacts query's order), one buffer snapshot. */
    private fun flush(x: SimPhone, peers: List<SimPhone>) {
        val ordered = peers.sortedByDescending { x.routing.prophetDeliveryProbability(it.id) }
        val online = LinkedHashSet(ordered.map { it.id.value })
        val snapshot = x.snapshot(now)
        for (y in ordered) {
            sendReceipts(x, y)
            if (snapshot.isNotEmpty()) pass(x, y, snapshot, online)
        }
        // fallbackPass: with no hub and no relay the 5 s rule never opens another path.
        for (m in snapshot) {
            if (m.messageType != DtnMessageType.DATA || m.destinationNodeId == NodeId.BROADCAST) continue
            val plan = DmFallback.plan(
                broadcast = false, delivered = m.id in x.receipts, queuedSinceMs = m.bufferedAtMs, nowMs = now,
                links = DmFallback.Links(direct = m.destinationNodeId.value in online),
            )
            fallbackPaths += plan.fallback.size
        }
    }

    /** sendToSingle for one peer. */
    private fun pass(x: SimPhone, y: SimPhone, snapshot: List<DtnMessage>, online: Set<String>) {
        val active = x.routing.active
        val approved = if (active is QLearningStrategy) qPass(x, y, snapshot, online)
        else CarrierRules.approvedByStrategy(active, snapshot, y.id, online)
        val peer = CarrierRules.Peer(y.id)
        val facts = x.facts(now)
        for (msg in snapshot) {
            when (val v = CarrierRules.decide(msg, peer, facts, approved)) {
                CarrierRules.Verdict.Clear -> { x.rows[msg.id]?.status = SimPhone.Status.DELIVERED; x.forgetSentTo(msg.id) }
                is CarrierRules.Verdict.Hold -> if (msg.id in watched) noteHold(x, y, msg, v.reason, online)
                CarrierRules.Verdict.Send -> transmit(x, y, msg, online)
            }
        }
    }

    /** qDecisionApproved without the database: decide, keep the choice 30 s, apply the progress reward. */
    private fun qPass(x: SimPhone, y: SimPhone, snapshot: List<DtnMessage>, online: Set<String>): Set<String> = runBlocking {
        val peer = y.id.value
        val pass = CarrierRules.qLearningPass(
            snapshot, y.id, online, x.diMaxProp, x.engine, x.engine.config.shapingEta,
            // As the orchestrator: the peer handed us a copy, or took ours this session.
            cameFrom = { id, p -> x.receivedFrom[id]?.contains(p) == true || x.sentTo[id]?.containsKey(p) == true },
            kept = { id -> x.qChoice["$id|$peer"]?.takeIf { now - it.second < RETRY_MS }?.first },
            stateFor = { msg, cost -> state(x, y, msg, cost) },
        )
        for (d in pass.fresh) {
            x.qChoice["${d.msg.id}|$peer"] = CarrierRules.QKept(d.action, "") to now
            qChoices["${x.label}|${y.label}|${d.msg.id}"] = d
            // OrchestratorEvent.QShaping: the immediate progress reward of a FORWARD.
            if (d.reward != null) x.engine.update(d.state, ForwardingAction.FORWARD, d.reward, nextState = null)
        }
        pass.approved
    }

    /** buildForwardingState from the simulated contacts row. */
    private fun state(x: SimPhone, y: SimPhone, msg: DtnMessage, cost: Double): ForwardingState {
        val s = x.stats(y)
        return ForwardingStateBuilder.build(
            lastRssi = s.lastRssi, lastSnr = 0f, totalEncounters = s.encounters, totalEncounterDurationMs = s.durationMs,
            deliveryProbability = x.routing.prophetDeliveryProbability(y.id),
            remainingTtlMs = msg.remainingTtlMs(now), ttlMs = msg.ttlMs, bufferOccupancy = 0.0,
            deliverySuccessCount = s.successes, deliveryAttemptCount = s.attempts, duplicateCount = msg.duplicateCount,
            diMaxPropCost = cost, hopCount = msg.hopCount, relayBufferOccupancy = x.diMaxProp.peerBufferOccupancy(y.id.value),
        )
    }

    /** A hand-over: one more hop, through the real wire codec (TTL = what's left), then the receiver. */
    private fun transmit(x: SimPhone, y: SimPhone, msg: DtnMessage, online: Set<String>) {
        val why = if (msg.id in watched && msg.destinationNodeId != y.id) metric(x, y, msg, online) else null
        val wire = CarrierRules.onWire(msg)
        val arrived = DtnWireCodec.decode(DtnWireCodec.encode(wire, now), rssi = -65, nowMs = now)!!
        x.sentTo.getOrPut(msg.id) { HashMap() }[y.id.value] = now
        val direct = msg.destinationNodeId == y.id
        if (msg.messageType == DtnMessageType.DATA) {
            x.rows[msg.id]?.let { it.msg = it.msg.copy(forwardCount = it.msg.forwardCount + 1) }
            x.stats(y).attempts++
            if (direct) {
                // Delivered over a link that ends at the destination.
                x.rows[msg.id]?.status = SimPhone.Status.DELIVERED
                x.forgetSentTo(msg.id)
                x.stats(y).successes++
            }
        }
        val stored = receive(y, arrived, x)
        if (msg.id in watched) {
            sends += Send(step, x.label, y.label, now, arrived.hopCount, (arrived.ttlMs / 60_000L).toInt(), stored, direct, why)
        }
    }

    /** onMessageReceived for a DATA bundle (no crypto here: every DM opens). @return true if stored. */
    private fun receive(y: SimPhone, msg: DtnMessage, from: SimPhone): Boolean {
        if (msg.originNodeId == y.id) return false // our own bundle coming back: ignored
        y.receivedFrom.getOrPut(msg.id) { mutableSetOf() }.add(from.id.value)
        if (msg.id in y.tombstones) return false
        if (msg.destinationNodeId == y.id) {
            if (!ingest(y, msg)) return false
            y.rows.getValue(msg.id).status = SimPhone.Status.DELIVERED
            y.receipts += msg.id
            y.tombstones += msg.id
            y.inbox[msg.id] = msg
            return true
        }
        if (msg.id in y.receipts) return false // RX-MULE ignored (already delivered)
        return ingest(y, msg)
    }

    /** MessageQueueManager.ingest: dedup by id, expired bundles refused. */
    private fun ingest(y: SimPhone, msg: DtnMessage): Boolean {
        y.rows[msg.id]?.let { it.msg = it.msg.copy(duplicateCount = it.msg.duplicateCount + 1); return false }
        if (msg.isExpired(now)) return false
        y.rows[msg.id] = SimPhone.Row(msg, now)
        return true
    }

    /** sendDeliveryReceipts → processInboundReceipts. */
    private fun sendReceipts(x: SimPhone, y: SimPhone) {
        val sent = x.receiptsSentTo.getOrPut(y.id.value) { mutableSetOf() }
        val fresh = x.receipts.filter { it !in sent }
        if (fresh.isEmpty()) return
        sent += fresh
        for (u in fresh) {
            y.receipts += u
            val row = y.rows[u]
            if (row != null && row.status == SimPhone.Status.BUFFERED) {
                row.status = SimPhone.Status.DELIVERED
                if (u in watched) cleared += Cleared(step, y.label, x.label)
                y.stats(x).successes++
            }
            y.forgetSentTo(u)
        }
    }

    // ── Explanations (the metric behind each decision) ────────────────────

    private fun noteHold(x: SimPhone, y: SimPhone, msg: DtnMessage, reason: HoldReason, online: Set<String>) {
        if (held.any { it.step == step && it.at == x.label && it.peer == y.label && it.reason == reason }) return
        held += Held(step, x.label, y.label, reason, if (reason == HoldReason.ALGORITHM) metric(x, y, msg, online) else null)
    }

    private fun f3(v: Double) = "%.3f".format(v)

    /** The numbers the active strategy used for (x → y, msg). */
    fun metric(x: SimPhone, y: SimPhone, msg: DtnMessage, online: Set<String>): Metric {
        val dest = msg.destinationNodeId
        val d = label(dest.value)
        return when (val s = x.routing.active) {
            is ProphetStrategy -> {
                val next = s.findNextHopToward(dest.value, online)?.let(::label)
                val mine = s.getDeliveryProbability(dest)
                val theirs = s.peerDeliveryProbability(y.id, dest)
                Metric(mine, theirs, next, "", msg.forwardCount,
                    "P(${x.label},$d)=${f3(mine)} P(${y.label},$d)=${f3(theirs)} live next hop=${next ?: "none"} copies=${msg.forwardCount}")
            }
            is MaxPropStrategy -> {
                val (hop, cost) = s.findMinCostNextHop(dest.value, online)
                val c = cost.takeIf { it != Double.MAX_VALUE }
                Metric(c, null, hop?.let(::label), "", msg.forwardCount,
                    "cheapest next hop=${hop?.let(::label) ?: "none"} cost=${c?.let(::f3) ?: "∞"}")
            }
            is DiMaxPropStrategy -> {
                val dm = s.decide(dest.value, y.id.value, online)
                Metric(dm.mine.cost, dm.theirs.cost, dm.mine.firstHop?.let(::label), dm.verdict.name, msg.forwardCount,
                    "C(${x.label},$d)=${cost(dm.mine)} C(${y.label},$d)=${cost(dm.theirs)} → ${dm.verdict}")
            }
            is QLearningStrategy -> {
                val q = qChoices["${x.label}|${y.label}|${msg.id}"]
                if (q == null) Metric(text = "kept choice") else Metric(
                    q.diMaxProp.mine.cost, q.diMaxProp.theirs.cost, q.diMaxProp.mine.firstHop?.let(::label), q.action.name, msg.forwardCount,
                    "${q.action}${if (q.exploratory) " (explored)" else ""} Q(fwd)=${f3(q.qForward)} Q(hold)=${f3(q.qStore)} " +
                        "prior=${q.diMaxProp.verdict} C(${x.label},$d)=${cost(q.diMaxProp.mine)} C(${y.label},$d)=${cost(q.diMaxProp.theirs)}",
                )
            }
            else -> Metric(verdict = "COPY", copiesSoFar = msg.forwardCount, text = "copy to every carrier")
        }
    }

    private fun cost(p: DiMaxPropStrategy.Path) = if (p.reachable) f3(p.cost) else "∞"

    // ── Results ───────────────────────────────────────────────────────────

    fun delivered(msgId: String, to: String) = msgId in get(to).inbox

    /** The chain of hand-overs that delivered [msgId] to [to], origin first (empty if not delivered). */
    fun path(msgId: String, to: String): List<Send> {
        if (!delivered(msgId, to)) return emptyList()
        val chain = ArrayList<Send>()
        var hop: Send? = sends.firstOrNull { it.to == to && it.stored }
        while (hop != null) {
            chain.add(0, hop)
            val from = hop.from
            hop = sends.firstOrNull { it.to == from && it.stored && it.atMs <= chain.first().atMs }
        }
        return chain
    }

    /** The phones (not origin, not destination) that stored a copy. */
    fun carriers(from: String, to: String): List<String> =
        sends.filter { it.stored && it.to != to && it.to != from }.map { it.to }.distinct()

    fun algorithmDeclines(): List<Held> = held.filter { it.reason == HoldReason.ALGORITHM }

    fun trace(msgId: String, from: String, to: String): String = buildString {
        appendLine("── ${type.name}: $from → $to delivered=${delivered(msgId, to)}")
        for (s in sends) appendLine("  [${s.step}] ${s.from}→${s.to} hop=${s.hop} ttl=${s.ttlMin}m " +
            (if (s.stored) "stored" else "dup") + (if (s.direct) " DIRECT" else "") + (if (s.why.isNotBlank()) "  | ${s.why}" else ""))
        for (h in held) appendLine("  [${h.step}] ${h.at}↛${h.peer} ${h.reason}" + (if (h.why.isNotBlank()) "  | ${h.why}" else ""))
        for (c in cleared) appendLine("  [${c.step}] ${c.at} cleared by receipt from ${c.by}")
    }
}
