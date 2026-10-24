package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * DiMaxProp: MaxProp's multi-hop path cost plus a per-hop physical-distance penalty (Kar, Dalai,
 * Raina, Tiwari, Shrivastwa, Upadhyay, "DiMaxProp: A Hybrid Path-Cost Metric for RL-Based Forwarding
 * in Delay-Tolerant Mesh Networks").
 *
 * - Hop cost (eq. 9, 10): `w(u,v) = (1 − f(u,v)) + λ_uv · d_norm(u,v)`, where `λ_uv = λ` when that
 *   link has a distance estimate and 0 otherwise. `f` is MaxProp's meeting likelihood; `d_norm` is
 *   the distance at the last contact divided by that radio's effective range, clamped to [0, 1].
 * - Path cost (eq. 11): the sum of its hop costs. Every `w ≥ 0`, so Dijkstra finds the cheapest path.
 * - Forwarding rule (§V-F): carrier A hands a bundle for D to B when B's own cost to D is lower than
 *   A's best cost to D, or B is the first hop of A's cheapest path.
 *
 * With λ = 0, or no distance on any link, every cost equals MaxProp's `Σ (1 − f)`.
 *
 * Distances in this app: only Bluetooth sightings carry a real signal strength (from scans), so only
 * BLE links get a distance. Wi-Fi Direct reports a fixed placeholder and phones seen through a hub
 * report none: those links use plain MaxProp cost, which is exactly the paper's per-link fallback.
 *
 * Neighbours share their `f` values, distances and buffer occupancy in a compact routing summary
 * ([encodeSummary]) that fits one LoRa frame, plus their own cheapest path cost to nodes further
 * away (distance-vector entries). That is how a phone learns the cost of a path several hops long
 * while only ever talking to its direct neighbours: the MaxProp paper floods every node's `f` vector
 * instead, which doesn't fit one frame. Q-learning uses the same costs as an input (C(B,D)) and for
 * its per-hop progress reward.
 *
 * Pure Kotlin, no Android dependencies, unit-tested.
 */
class DiMaxPropStrategy(
    val config: DiMaxPropConfig = DiMaxPropConfig(),
) : RoutingStrategy {

    override val name: String = TAG

    /** λ: weight of the distance penalty, 0..1 (adjustable at runtime from Settings). */
    @Volatile var lambda: Double = config.lambda.coerceIn(0.0, 1.0)
        set(value) { field = value.coerceIn(0.0, 1.0) }

    /** This phone's buffer occupancy, advertised in our summary (kept current by the orchestrator). */
    @Volatile var localBufferOccupancy: Double = 0.0

    /** This phone's node id, so a neighbour's vector can't route a bundle back through us. */
    @Volatile var selfId: String? = null

    private val encounterCounts = ConcurrentHashMap<String, Int>()
    @Volatile private var totalEncounters = 0

    /** f(me, peer): meeting likelihood, normalised to sum to 1 (MaxProp eq. 5, 6). */
    private val fTable = ConcurrentHashMap<String, Double>()

    /** d_norm(me, peer) from the most recent contact that had a distance estimate. */
    private val ownDistance = ConcurrentHashMap<String, Double>()

    /** Reported by each neighbour: f(peer, x), d_norm(peer, x) where known, and its buffer use. */
    private val peerF = ConcurrentHashMap<String, Map<String, Double>>()
    private val peerD = ConcurrentHashMap<String, Map<String, Double>>()
    private val peerBuffer = ConcurrentHashMap<String, Double>()

    /**
     * Reported by each neighbour: its own cheapest cost C(peer, x) to nodes further away (the path-cost
     * entries of its summary). They carry multi-hop reach one summary at a time, as distance vectors:
     * x's cost is what the neighbour computed from its own neighbours' reports.
     */
    private val peerCost = ConcurrentHashMap<String, Map<String, Double>>()

    /** A cheapest path: its cost (+∞ when unreachable) and its nodes, source first. */
    data class Path(val cost: Double, val nodes: List<String>) {
        val reachable: Boolean get() = cost.isFinite()
        /** The node right after the source (the hop to hand the bundle to), or null. */
        val firstHop: String? get() = nodes.getOrNull(1)

        companion object {
            val NONE = Path(Double.POSITIVE_INFINITY, emptyList())
        }
    }

    /** One link of the contact graph as the metric sees it. */
    data class Edge(val f: Double, val dNorm: Double?, val weight: Double)

    enum class Verdict { FORWARD, EXPLORE, HOLD }

    /** The forwarding decision for one (destination, carrier) pair, with both costs. */
    data class Decision(val mine: Path, val theirs: Path, val verdict: Verdict)

    /** A decoded neighbour summary: its contacts (f, d) and its path costs to nodes further away. */
    data class Summary(
        val bufferOccupancy: Double,
        val f: Map<String, Double>,
        val d: Map<String, Double>,
        val cost: Map<String, Double> = emptyMap(),
    )

    // ── Encounters + summaries ────────────────────────────────────────────

    override fun onEncounter(peerId: NodeId, contactRecord: ContactRecord) {
        val key = peerId.value
        encounterCounts[key] = (encounterCounts[key] ?: 0) + 1
        totalEncounters++
        recomputeLikelihoods()
        val d = contactRecord.distanceMeters
        if (d >= 0.0) ownDistance[key] = (d / config.rangeFor(contactRecord.radio)).coerceIn(0.0, 1.0)
    }

    override fun onRoutingSummaryReceived(peerId: NodeId, summary: RoutingSummary) {
        if (summary.strategyTag != TAG) return
        val s = decodeSummary(summary.payload) ?: return
        val key = peerId.value
        peerF[key] = s.f
        peerD[key] = s.d
        peerCost[key] = s.cost
        peerBuffer[key] = s.bufferOccupancy
        // Nothing is copied into our own f-table: f(me, x) is only ever a meeting this phone saw. (A
        // neighbour's f used to be "seeded" in, discounted, which made every node look one hop away
        // at a cost of about 1 and hid the real multi-hop paths; their path costs carry that now.)
    }

    override fun buildRoutingSummary(): RoutingSummary =
        RoutingSummary(strategyTag = TAG, payload = encodeSummary(localBufferOccupancy, fTable, ownDistance, pathCosts()))

    /**
     * Our cheapest cost to each node we know of further away than a direct link (or cheaper than our
     * direct link to it): the summary's path-cost entries, so neighbours see our multi-hop reach.
     */
    private fun pathCosts(): Map<String, Double> {
        val me = selfId
        return costsFromMe().filter { (node, cost) ->
            node != me && cost.isFinite() && (edge(ME, node)?.weight ?: Double.POSITIVE_INFINITY) > cost
        }
    }

    private fun recomputeLikelihoods() {
        val total = totalEncounters
        if (total == 0) return
        for ((key, count) in encounterCounts) fTable[key] = count.toDouble() / total.toDouble()
        normalise()
    }

    private fun normalise() {
        val sum = fTable.values.sum()
        if (sum <= 0.0) return
        for (key in fTable.keys) fTable[key] = (fTable[key] ?: 0.0) / sum
    }

    // ── The metric ────────────────────────────────────────────────────────

    private fun edge(from: String, to: String): Edge? {
        val f = if (from == ME) fTable[to] else peerF[from]?.get(to)
        if (f == null || f <= 0.0) return null
        val d = if (from == ME) ownDistance[to] else peerD[from]?.get(to)
        return Edge(f, d, edgeWeight(f, d, lambda))
    }

    /** The link [from] → [to] as the metric sees it ([from] = [ME_LABEL] for this phone). */
    fun edgeInfo(from: String, to: String): Edge? = edge(if (from == ME_LABEL) ME else from, to)

    /** [from]'s own cheapest cost to [to] as it reported it (a path-cost entry), or null. */
    fun reportedCost(from: String, to: String): Double? = if (from == ME || from == ME_LABEL) null else peerCost[from]?.get(to)

    /** The cheapest way from [u] to [v] in one step: their link, or [u]'s reported path cost to [v]. */
    private fun step(u: String, v: String): Double? {
        val link = edge(u, v)?.weight
        val reported = reportedCost(u, v)
        return if (link == null) reported else if (reported == null) link else minOf(link, reported)
    }

    /** Dijkstra from [source]; stops early at [dest] (null = every node, for our summary). */
    private fun dijkstra(source: String, dest: String?, meNeighbours: Collection<String>): Pair<Map<String, Double>, Map<String, String>> {
        val me = selfId
        val dist = HashMap<String, Double>().apply { put(source, 0.0) }
        val prev = HashMap<String, String>()
        val done = HashSet<String>()
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        queue.add(source to 0.0)
        while (queue.isNotEmpty()) {
            val (u, du) = queue.poll() ?: break
            if (!done.add(u)) continue
            if (u == dest) break
            val next = if (u == ME) meNeighbours else (peerF[u]?.keys.orEmpty() + peerCost[u]?.keys.orEmpty())
            for (v in next) {
                if (v == source || v == ME || v == me || v in done) continue
                val w = step(u, v) ?: continue
                val nd = du + w
                if (nd < (dist[v] ?: Double.POSITIVE_INFINITY)) {
                    dist[v] = nd
                    prev[v] = u
                    queue.add(v to nd)
                }
            }
        }
        return dist to prev
    }

    private fun cheapest(source: String, dest: String, meNeighbours: Collection<String>): Path {
        if (source == dest) return Path(0.0, listOf(source))
        val (dist, prev) = dijkstra(source, dest, meNeighbours)
        val cost = dist[dest] ?: return Path.NONE
        val nodes = ArrayList<String>()
        var n: String? = dest
        while (n != null) { nodes.add(n); n = prev[n] }
        nodes.reverse()
        return Path(cost, nodes)
    }

    /** C(me, D): this phone's cheapest cost to [dest] over every link it knows. */
    fun costFromMe(dest: String): Path = cheapest(ME, dest, fTable.keys)

    /** C(me, x) for every node this phone can reach. */
    private fun costsFromMe(): Map<String, Double> = dijkstra(ME, null, fTable.keys).first - ME

    /** C(peer, D): [peer]'s cheapest cost to [dest] from the vectors it reported (never through us). */
    fun costFromPeer(peer: String, dest: String): Path = cheapest(peer, dest, emptyList())

    /** Buffer occupancy [peer] last reported, or null if it never sent a DiMaxProp summary. */
    fun peerBufferOccupancy(peer: String): Double? = peerBuffer[peer]

    /** The forwarding rule (§V-F) for handing a bundle for [dest] to [peer]. */
    fun decide(dest: String, peer: String, onlinePeers: Set<String> = emptySet()): Decision {
        val mine = costFromMe(dest)
        val theirs = costFromPeer(peer, dest)
        val verdict = when {
            // The destination is reachable right now: the orchestrator hands it over directly.
            dest in onlinePeers -> Verdict.HOLD
            theirs.reachable && (!mine.reachable || theirs.cost < mine.cost || mine.firstHop == peer) -> Verdict.FORWARD
            // Nobody knows any path yet (cold start): a bounded low-priority copy, as in MaxProp.
            !mine.reachable && !theirs.reachable -> Verdict.EXPLORE
            else -> Verdict.HOLD
        }
        return Decision(mine, theirs, verdict)
    }

    // ── Forwarding / dropping ─────────────────────────────────────────────

    fun rankForForwardingWithTopology(
        candidates: List<DtnMessage>,
        peerId: NodeId,
        onlinePeers: Set<String>,
    ): List<ForwardCandidate> = candidates.mapNotNull { msg ->
        val dest = msg.destinationNodeId.value
        val ttlFraction = msg.remainingTtlMs().toDouble() / max(msg.ttlMs, 1L).toDouble()
        val headStart = if (msg.hopCount <= config.newPacketHopThreshold) config.headStartBoost else 1.0
        when {
            msg.hopCount >= config.maxHops -> null
            dest == peerId.value -> ForwardCandidate(msg, priority = headStart * ttlFraction)
            msg.destinationNodeId == NodeId.BROADCAST -> ForwardCandidate(msg, priority = 0.9 * ttlFraction)
            else -> {
                val d = decide(dest, peerId.value, onlinePeers)
                when (d.verdict) {
                    Verdict.FORWARD -> ForwardCandidate(msg, priority = (1.0 / (1.0 + d.theirs.cost)) * headStart * ttlFraction)
                    Verdict.EXPLORE -> ForwardCandidate(msg, priority = config.exploratoryFloor * ttlFraction)
                    Verdict.HOLD -> null
                }
            }
        }
    }.sortedByDescending { it.priority }

    override fun rankForForwarding(candidates: List<DtnMessage>, peerId: NodeId): List<ForwardCandidate> =
        rankForForwardingWithTopology(candidates, peerId, emptySet())

    /** Under buffer pressure, drop the bundles that are most expensive to deliver first. */
    override fun rankForDrop(candidates: List<DtnMessage>, bytesToFree: Long): List<DtnMessage> =
        candidates.sortedWith(
            compareByDescending<DtnMessage> { costFromMe(it.destinationNodeId.value).cost }
                .thenBy { it.remainingTtlMs() },
        )

    override fun getDeliveryProbability(peerId: NodeId): Double = fTable[peerId.value] ?: 0.0

    override fun onPeriodicAge() {
        if (!config.enableRecencyDecay) return
        val it = encounterCounts.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val decayed = (e.value * config.recencyDecayFactor).toInt()
            if (decayed <= 0) it.remove() else e.setValue(decayed)
        }
        totalEncounters = encounterCounts.values.sum()
        recomputeLikelihoods()
    }

    // ── Explanation (saved with each forward for Message info) ────────────

    /** What this strategy decided for handing [msg] to [peerId], with every number it used. */
    fun explain(msg: DtnMessage, peerId: NodeId, onlinePeers: Set<String>): String {
        val dest = msg.destinationNodeId.value
        val peer = peerId.value
        val d = decide(dest, peer, onlinePeers)
        val l = lambda
        return buildString {
            appendLine("DiMaxProp · λ ${f2(l)} · hop cost = (1 − f) + λ × distance")
            appendLine("My cost to ${short(dest)}: ${costText(d.mine)}")
            hopLines(d.mine, l).forEach { appendLine(it) }
            appendLine("${short(peer)}'s cost to ${short(dest)}: ${costText(d.theirs)}")
            hopLines(d.theirs, l).forEach { appendLine(it) }
            append("Decision: ")
            append(
                when (d.verdict) {
                    Verdict.FORWARD -> when {
                        !d.mine.reachable -> "forward — ${short(peer)} knows a path and this phone doesn't"
                        d.mine.firstHop == peer && d.theirs.cost >= d.mine.cost -> "forward — ${short(peer)} is the first hop of my cheapest path"
                        else -> "forward — ${short(peer)}'s cost ${f2(d.theirs.cost)} < mine ${f2(d.mine.cost)}"
                    }
                    Verdict.EXPLORE -> "exploratory copy — no path to ${short(dest)} is known yet on either side"
                    Verdict.HOLD -> "hold — ${short(peer)} is not closer to ${short(dest)}"
                },
            )
        }
    }

    private fun hopLines(path: Path, l: Double): List<String> {
        if (!path.reachable || path.nodes.size < 2) return emptyList()
        return path.nodes.zipWithNext().map { (u, v) ->
            val e = edge(u, v)
            val reported = reportedCost(u, v)
            val label = "  ${short(u)} → ${short(v)}"
            when {
                reported != null && (e == null || reported < e.weight) -> "$label  ${f2(reported)} (${short(u)}'s own path cost)"
                e == null -> "$label  ?"
                e.dNorm != null -> "$label  ${f2(e.weight)} = (1 − f ${f2(e.f)}) + ${f2(l)} × d ${f2(e.dNorm)}"
                else -> "$label  ${f2(e.weight)} = 1 − f ${f2(e.f)} (no distance)"
            }
        }
    }

    private fun costText(p: Path): String = if (p.reachable) f2(p.cost) else "no known path"

    private fun short(id: String): String = if (id == ME) "me" else id.takeLast(8)

    private fun f2(v: Double): String = "%.2f".format(v)

    // ── State persistence ─────────────────────────────────────────────────

    override fun exportState(): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(encounterCounts.size)
            for ((k, c) in encounterCounts) { out.writeUTF(k); out.writeInt(c) }
            out.writeInt(totalEncounters)
            out.writeInt(fTable.size)
            for ((k, v) in fTable) { out.writeUTF(k); out.writeDouble(v) }
            out.writeInt(ownDistance.size)
            for ((k, v) in ownDistance) { out.writeUTF(k); out.writeDouble(v) }
        }
        return bos.toByteArray()
    }

    override fun importState(data: ByteArray) {
        if (data.isEmpty()) return
        runCatching {
            DataInputStream(ByteArrayInputStream(data)).use { inp ->
                encounterCounts.clear()
                repeat(inp.readInt()) { encounterCounts[inp.readUTF()] = inp.readInt() }
                totalEncounters = inp.readInt()
                fTable.clear()
                repeat(inp.readInt()) { fTable[inp.readUTF()] = inp.readDouble() }
                ownDistance.clear()
                repeat(inp.readInt()) { ownDistance[inp.readUTF()] = inp.readDouble() }
            }
        }
    }

    companion object {
        const val TAG = "DIMAXPROP"
        /** How this phone appears in [edgeInfo] / explanations. */
        const val ME_LABEL = "me"
        private const val ME = "__self__"
        private const val MARKER = 0xD1
        private const val VERSION = 1
        /** Summary entries kept so it fits one LoRa frame: 4 header bytes + 6 per entry ≤ 223. */
        const val MAX_ENTRIES = 36
        private const val NO_DISTANCE = 0xFF
        /** A path-cost entry's cost byte = C × 64 (resolution 1/64), up to 254 (C < 4). */
        const val COST_SCALE = 64.0
        private const val MAX_COST_BYTE = 254

        /** Paper eq. (9) + (10): `(1 − f) + λ·d` when the link has a distance, else MaxProp's `1 − f`. */
        fun edgeWeight(f: Double, dNorm: Double?, lambda: Double): Double =
            (1.0 - f.coerceIn(0.0, 1.0)) +
                if (dNorm != null) lambda.coerceIn(0.0, 1.0) * dNorm.coerceIn(0.0, 1.0) else 0.0

        /** True for a DiMaxProp summary payload (first byte 0xD1; PRoPHET/MaxProp ones start with 0x00). */
        fun isSummary(payload: ByteArray): Boolean =
            payload.size >= 4 && (payload[0].toInt() and 0xFF) == MARKER && (payload[1].toInt() and 0xFF) == VERSION

        /**
         * `[0xD1][version 1][buffer occupancy ×255][n]` then n × `[4B node id][f ×255][d ×254, 255 = none]`:
         * the most likely contacts first, then path-cost entries `[4B node id][0][C ×64]` for nodes further
         * away ([costs], cheapest first, C < 4), at most [MAX_ENTRIES] in all. Readers that predate the
         * cost entries skip them: they already dropped entries with f = 0.
         */
        fun encodeSummary(
            bufferOccupancy: Double,
            f: Map<String, Double>,
            d: Map<String, Double>,
            costs: Map<String, Double> = emptyMap(),
        ): ByteArray {
            fun valid(id: String) = id.startsWith("!") && id.length == 9
            val contacts = f.entries
                .filter { valid(it.key) && (it.value.coerceIn(0.0, 1.0) * 255).roundToInt() > 0 }
                .sortedByDescending { it.value }
                .take(MAX_ENTRIES)
                .map { Triple(it.key, (it.value.coerceIn(0.0, 1.0) * 255).roundToInt(), d[it.key]?.let { v -> (v.coerceIn(0.0, 1.0) * 254).roundToInt() } ?: NO_DISTANCE) }
            val far = costs.entries
                .filter { valid(it.key) && it.value.isFinite() && it.value >= 0.0 && (it.value * COST_SCALE).roundToInt() <= MAX_COST_BYTE }
                .sortedBy { it.value }
                .take(MAX_ENTRIES - contacts.size)
                .map { Triple(it.key, 0, (it.value * COST_SCALE).roundToInt()) }
            val entries = contacts + far
            val out = ByteArrayOutputStream(4 + entries.size * 6)
            out.write(MARKER)
            out.write(VERSION)
            out.write((bufferOccupancy.coerceIn(0.0, 1.0) * 255).roundToInt())
            out.write(entries.size)
            for ((id, fByte, dByte) in entries) {
                val num = NodeId(id).toNodeNum32()
                out.write(num ushr 24); out.write(num ushr 16); out.write(num ushr 8); out.write(num)
                out.write(fByte)
                out.write(dByte)
            }
            return out.toByteArray()
        }

        fun decodeSummary(payload: ByteArray): Summary? {
            if (!isSummary(payload)) return null
            val n = payload[3].toInt() and 0xFF
            if (payload.size < 4 + n * 6) return null
            val f = HashMap<String, Double>()
            val d = HashMap<String, Double>()
            val cost = HashMap<String, Double>()
            for (i in 0 until n) {
                val o = 4 + i * 6
                val num = ((payload[o].toInt() and 0xFF) shl 24) or ((payload[o + 1].toInt() and 0xFF) shl 16) or
                    ((payload[o + 2].toInt() and 0xFF) shl 8) or (payload[o + 3].toInt() and 0xFF)
                val id = NodeId.fromNodeNum(num)
                if (id == NodeId.BROADCAST) continue
                val fByte = payload[o + 4].toInt() and 0xFF
                val dv = payload[o + 5].toInt() and 0xFF
                if (fByte == 0) {
                    // A path-cost entry: the sender's own cheapest cost to that node.
                    if (dv <= MAX_COST_BYTE) cost[id.value] = dv / COST_SCALE
                    continue
                }
                f[id.value] = fByte / 255.0
                if (dv != NO_DISTANCE) d[id.value] = dv / 254.0
            }
            return Summary((payload[2].toInt() and 0xFF) / 255.0, f, d, cost)
        }
    }
}

/** DiMaxProp parameters. */
data class DiMaxPropConfig(
    /** λ (paper eq. 9): weight of the per-hop distance penalty. 0 makes DiMaxProp plain MaxProp. */
    val lambda: Double = 0.5,
    /** Effective ranges that turn a distance estimate into `d_norm = d / range` (clamped to 1). */
    val bleRangeM: Double = 50.0,
    val wifiDirectRangeM: Double = 100.0,
    val loraRangeM: Double = 5_000.0,
    val enableRecencyDecay: Boolean = true,
    val recencyDecayFactor: Double = 0.9,
    val maxHops: Int = 10,
    /** MaxProp head-start: bundles at or below this hop count get [headStartBoost]. */
    val newPacketHopThreshold: Int = 1,
    val headStartBoost: Double = 1.5,
    /** Low-priority exploratory copy when neither side knows any path (cold start), as in MaxProp. */
    val exploratoryFloor: Double = 0.02,
) {
    fun rangeFor(radio: RadioDistanceEstimator.Transport?): Double = when (radio) {
        RadioDistanceEstimator.Transport.WIFI_DIRECT -> wifiDirectRangeM
        RadioDistanceEstimator.Transport.LORA -> loraRangeM
        else -> bleRangeM
    }.coerceAtLeast(1.0)
}
