package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.max

/**
 * MaxProp routing strategy implementation.
 *
 * Reference: Burgess et al., "MaxProp: Routing for Vehicle-Based Disruption-
 * Tolerant Networks", IEEE INFOCOM 2006.
 *
 * ## Core concepts
 * - **Path likelihoods f(i,j):** estimated probability of meeting peer j,
 *   normalised across all known peers (sum to 1.0).
 * - **Cost estimation:** shortest-path cost to destination via known peers,
 *   computed from path likelihoods (lower f = higher cost = less likely path).
 * - **TTL-aware prioritised drop:** under buffer pressure, drop messages with
 *   highest estimated delivery cost first (least likely to be deliverable).
 * - **Complementary to PRoPHET:** MaxProp reasons about path costs while
 *   PRoPHET reasons about encounter probability.
 *
 * ## Differences from the paper
 * - The paper floods every node's f-vector, so each node runs Dijkstra over the whole graph. A
 *   summary here is one frame to a direct neighbour, so it carries the neighbour's f-vector plus its
 *   own cheapest path cost to nodes further away (distance vectors): the graph is learnt hop by hop.
 * - The paper hands every bundle to every peer, ordered by cost; here the cost is a forwarding
 *   filter: a peer gets a mule copy when it is the first hop of the cheapest path among the phones
 *   in range ([rankForForwardingWithTopology]), one copy per contact instead of a flood.
 *
 * Zero Android dependencies. Pure Kotlin.
 */
class MaxPropStrategy(
    val config: MaxPropConfig = MaxPropConfig(),
) : RoutingStrategy {

    override val name: String = "MAXPROP"

    private companion object {
        /** Synthetic source node key for the Dijkstra cost graph. */
        const val ME = "__self__"
        /** An encoded summary stays below the 223 B single-frame payload (as PRoPHET's). */
        const val SUMMARY_BYTE_BUDGET = 200
    }

    /**
     * Path likelihood table: f(local, peer_i).
     * Updated on each encounter. Normalised to sum = 1.0 across all peers.
     */
    private val fTable: MutableMap<String, Double> = java.util.concurrent.ConcurrentHashMap()

    /** Raw encounter counts per peer — used to compute path likelihoods. */
    private val encounterCounts: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap()

    /** Total encounters observed (denominator for likelihood). */
    private var totalEncounters: Int = 0

    /**
     * Per-peer likelihood vectors reported by neighbours: `peerFTables[j][k] = f(j, k)`.
     * These are the input to the MaxProp minimum-cost path search — without a neighbour's own
     * f-vector we can only reason one hop. Refreshed on each routing-summary exchange.
     */
    private val peerFTables: MutableMap<String, MutableMap<String, Double>> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Per-neighbour path costs: `peerCosts[j][k]` = j's own cheapest cost to k, for nodes j doesn't meet
     * itself (the second part of its summary). Distance vectors: they carry multi-hop reach one summary
     * at a time, where the MaxProp paper floods every node's f-vector (too big for one LoRa frame).
     */
    private val peerCosts: MutableMap<String, Map<String, Double>> = java.util.concurrent.ConcurrentHashMap()

    /** This phone's node id: never a node of its own cost graph. */
    @Volatile var selfId: String? = null

    // ──────────────────────────────────────────────────────────────────────
    // Encounter handling
    // ──────────────────────────────────────────────────────────────────────

    override fun onEncounter(peerId: NodeId, contactRecord: ContactRecord) {
        val key = peerId.value
        encounterCounts[key] = (encounterCounts[key] ?: 0) + 1
        totalEncounters++
        recomputeLikelihoods()
    }

    override fun onRoutingSummaryReceived(peerId: NodeId, summary: RoutingSummary) {
        if (summary.strategyTag != "MAXPROP") return

        val (peerLikelihoods, costs) = decodeSummary(summary.payload)
        if (peerLikelihoods.isEmpty() && costs.isEmpty()) return
        // Cache the neighbour's f-vector and path costs for the minimum-cost path search. Nothing is
        // copied into our own f-table: f(me, k) is only ever a meeting this phone saw. (A neighbour's
        // f used to be "seeded" in, discounted, which made every node look one hop away at a cost of
        // about 1 and hid the real multi-hop paths.)
        peerFTables[peerId.value] = peerLikelihoods.toMutableMap()
        peerCosts[peerId.value] = costs
    }

    override fun buildRoutingSummary(): RoutingSummary {
        val me = selfId
        // Our own cheapest cost to the nodes further away (or cheaper than our direct link to them).
        val far = costsFromMe().filter { (node, cost) ->
            node != me && cost.isFinite() && cost < (fTable[node]?.takeIf { it > 0.0 }?.let { 1.0 - it } ?: Double.POSITIVE_INFINITY)
        }
        return RoutingSummary(
            strategyTag = "MAXPROP",
            payload = encodeSummary(fTable, far),
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Likelihood computation
    // ──────────────────────────────────────────────────────────────────────

    private fun recomputeLikelihoods() {
        if (totalEncounters == 0) return
        for ((key, count) in encounterCounts) {
            fTable[key] = count.toDouble() / totalEncounters.toDouble()
        }
        normalise()
    }

    private fun normalise() {
        val sum = fTable.values.sum()
        if (sum <= 0.0) return
        for (key in fTable.keys) {
            fTable[key] = (fTable[key] ?: 0.0) / sum
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Forwarding / Dropping
    // ──────────────────────────────────────────────────────────────────────

    override fun rankForForwarding(candidates: List<DtnMessage>, peerId: NodeId): List<ForwardCandidate> {
        val peerF = fTable.getOrDefault(peerId.value, 0.0)

        return candidates.map { msg ->
            val destKey = msg.destinationNodeId.value

            // Cost to destination via this peer: lower f = higher cost.
            // Priority = f(peer, dest) for direct delivery, or f(peer) as proxy.
            val deliveryCost = if (destKey == peerId.value) {
                1.0 // Direct delivery — maximum priority
            } else {
                // Simplified: use peer's likelihood as proxy for reachability
                peerF
            }

            val ttlFraction = msg.remainingTtlMs().toDouble() / max(msg.ttlMs, 1L).toDouble()
            val priority = deliveryCost * ttlFraction

            ForwardCandidate(message = msg, priority = priority)
        }
            .filter { it.priority > 0.0 }
            .sortedByDescending { it.priority }
    }

    // ──────────────────────────────────────────────────────────────────────
    // MaxProp minimum-cost path routing (Burgess et al., INFOCOM 2006)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Find the minimum-cost next hop toward [dest] over the known likelihood graph.
     *
     * MaxProp models each hop's cost as `1 − f(a, b)` (the complement of the delivery
     * likelihood), and the cost of a path as the sum of its edge costs. The best route is the
     * minimum-total-cost path, found here with Dijkstra. Nodes: `ME` + every node any cached
     * f-vector mentions; `ME`'s neighbours are the currently-online peers, deeper edges come
     * from neighbours' reported f-vectors and their reported path costs to nodes further away.
     *
     * @return (firstHop, pathCost). firstHop is null when no path to [dest] exists; pathCost is
     *   [Double.MAX_VALUE] in that case.
     */
    fun findMinCostNextHop(dest: String, onlinePeers: Set<String>): Pair<String?, Double> {
        if (dest in onlinePeers) return dest to (1.0 - (fTable[dest] ?: 0.0)).coerceIn(0.0, 1.0)

        val (dist, prev) = dijkstra(dest, onlinePeers)
        val cost = dist[dest] ?: return null to Double.MAX_VALUE
        // Walk back to the hop whose predecessor is ME — that's the first hop to forward to.
        var node = dest
        while (prev[node] != null && prev[node] != ME) node = prev[node]!!
        val firstHop = if (prev[node] == ME) node else return null to Double.MAX_VALUE
        return (if (firstHop in onlinePeers) firstHop else null) to cost
    }

    /** One step of the cost graph: the link's `1 − f`, or [from]'s reported path cost to [to] when cheaper. */
    private fun stepCost(from: String, to: String): Double? {
        val f = if (from == ME) fTable[to] else peerFTables[from]?.get(to)
        val link = if (f != null && f > 0.0) (1.0 - f).coerceIn(0.0, 1.0) else null
        val reported = if (from == ME) null else peerCosts[from]?.get(to)
        return if (link == null) reported else if (reported == null) link else minOf(link, reported)
    }

    /**
     * Dijkstra from this phone, whose first hops are [meNeighbours]; stops early at [dest] (null = every
     * node). Deeper steps come from the neighbours' reported vectors and path costs.
     */
    private fun dijkstra(dest: String?, meNeighbours: Collection<String>): Pair<Map<String, Double>, Map<String, String>> {
        val me = selfId
        val dist = mutableMapOf(ME to 0.0)
        val prev = mutableMapOf<String, String>()
        val visited = mutableSetOf<String>()
        val pq = java.util.PriorityQueue<Pair<String, Double>>(compareBy { it.second })
        pq.add(ME to 0.0)
        while (pq.isNotEmpty()) {
            val (u, du) = pq.poll() ?: break
            if (!visited.add(u)) continue
            if (u == dest) break
            val next = if (u == ME) meNeighbours else (peerFTables[u]?.keys.orEmpty() + peerCosts[u]?.keys.orEmpty())
            for (v in next) {
                if (v == ME || v == me || v in visited) continue
                val w = stepCost(u, v) ?: continue
                val nd = du + w
                if (nd < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = nd; prev[v] = u; pq.add(v to nd)
                }
            }
        }
        return dist to prev
    }

    /** This phone's cheapest cost to every node it can reach, through any of its contacts. */
    private fun costsFromMe(): Map<String, Double> = dijkstra(null, fTable.keys).first - ME

    /**
     * Topology-aware ranking used by the orchestrator: forward to [peerId] only if it lies on
     * the minimum-cost path to each message's destination (or as a single-hop fallback when no
     * multi-hop path is known). New (low-hop) packets get a head-start, per MaxProp.
     */
    fun rankForForwardingWithTopology(
        candidates: List<DtnMessage>,
        peerId: NodeId,
        onlinePeers: Set<String>,
    ): List<ForwardCandidate> = candidates.mapNotNull { msg ->
        val destKey = msg.destinationNodeId.value
        val ttlFraction = msg.remainingTtlMs().toDouble() / max(msg.ttlMs, 1L).toDouble()
        val headStart = if (msg.hopCount <= config.newPacketHopThreshold) config.headStartBoost else 1.0
        when {
            msg.hopCount >= config.maxHops -> null
            destKey == peerId.value -> ForwardCandidate(msg, priority = 1.0 * headStart * ttlFraction)
            msg.destinationNodeId == NodeId.BROADCAST -> ForwardCandidate(msg, priority = 0.9 * ttlFraction)
            else -> {
                val (nextHop, cost) = findMinCostNextHop(destKey, onlinePeers)
                when {
                    // This peer is the min-cost next hop: priority falls as path cost rises.
                    nextHop == peerId.value -> ForwardCandidate(msg,
                        priority = (1.0 / (1.0 + cost)) * headStart * ttlFraction)
                    // A cheaper path exists through a different peer — hold for it.
                    nextHop != null -> null
                    // No known path. Prefer this peer's own reported f(peer,dest); if it has none
                    // either, still allow a BOUNDED low-priority exploratory forward so bundles can
                    // reach freshly-met mules during bootstrap instead of dead-ending (parity with
                    // PROPHET's relayBaseFloor). hopCount>=maxHops above + the orchestrator's
                    // rate-limit keep this from turning into an unbounded flood.
                    else -> {
                        val peerF = peerFTables[peerId.value]?.get(destKey) ?: 0.0
                        val basePriority = if (peerF > 0.0) peerF else config.exploratoryFloor
                        ForwardCandidate(msg, priority = basePriority * ttlFraction)
                    }
                }
            }
        }
    }.sortedByDescending { it.priority }

    /**
     * Why [rankForForwardingWithTopology] hands [msg] to [peerId], with the numbers it used. Saved
     * with the forward so Message info shows the decision as it was made, not today's values.
     */
    fun explain(msg: DtnMessage, peerId: NodeId, onlinePeers: Set<String>): String {
        val dest = msg.destinationNodeId.value
        val peer = peerId.value
        val (nextHop, cost) = findMinCostNextHop(dest, onlinePeers)
        val peerF = peerFTables[peer]?.get(dest)
        val f3 = { v: Double -> "%.3f".format(v) }
        val costText = if (cost == Double.MAX_VALUE) "no known path" else f3(cost)
        val why = when {
            nextHop == peer -> "${peer.takeLast(8)} is the first hop of the cheapest path"
            nextHop != null -> "the cheapest path starts at ${nextHop.takeLast(8)} (not this carrier)"
            peerF != null && peerF > 0.0 -> "no known path; ${peer.takeLast(8)} reports f→dest ${f3(peerF)}"
            else -> "no known path yet: low-priority exploratory copy"
        }
        return "MAXPROP · cheapest path cost from here $costText (Σ 1 − f per hop)\n" +
            "f(me→${peer.takeLast(8)}) ${f3(fTable[peer] ?: 0.0)} · f(me→${dest.takeLast(8)}) ${f3(fTable[dest] ?: 0.0)}\n" +
            "Decision: $why"
    }

    override fun rankForDrop(candidates: List<DtnMessage>, bytesToFree: Long): List<DtnMessage> {
        // MaxProp drop policy: highest delivery cost first (least likely to succeed).
        // Cost = 1 - f(dest). Messages to rarely-seen destinations are dropped first.
        // Tie-break: shortest remaining TTL dropped first (least time to recover).
        return candidates.sortedWith(
            compareByDescending<DtnMessage> { deliveryCost(it) }
                .thenBy { it.remainingTtlMs() }
        )
    }

    /**
     * Estimated delivery cost for a message.
     * Cost = 1 - f(destination). Higher cost = less likely to deliver.
     */
    private fun deliveryCost(msg: DtnMessage): Double {
        val destF = fTable.getOrDefault(msg.destinationNodeId.value, 0.0)
        return 1.0 - destF
    }

    override fun getDeliveryProbability(peerId: NodeId): Double =
        fTable.getOrDefault(peerId.value, 0.0)

    // ──────────────────────────────────────────────────────────────────────
    // Aging
    // ──────────────────────────────────────────────────────────────────────

    override fun onPeriodicAge() {
        // MaxProp doesn't explicitly age like PRoPHET.
        // Likelihoods naturally dilute as totalEncounters grows (frequency-based).
        // Optional: apply a recency window by decaying old encounter counts.
        if (!config.enableRecencyDecay) return

        val iterator = encounterCounts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val decayed = (entry.value * config.recencyDecayFactor).toInt()
            if (decayed <= 0) {
                iterator.remove()
            } else {
                entry.setValue(decayed)
            }
        }
        totalEncounters = encounterCounts.values.sum()
        recomputeLikelihoods()
    }

    // ──────────────────────────────────────────────────────────────────────
    // State persistence
    // ──────────────────────────────────────────────────────────────────────

    override fun exportState(): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        // Write encounter counts
        dos.writeInt(encounterCounts.size)
        for ((key, count) in encounterCounts) {
            dos.writeUTF(key)
            dos.writeInt(count)
        }
        dos.writeInt(totalEncounters)
        // Write f-table
        dos.writeInt(fTable.size)
        for ((key, value) in fTable) {
            dos.writeUTF(key)
            dos.writeDouble(value)
        }
        dos.flush()
        return bos.toByteArray()
    }

    override fun importState(data: ByteArray) {
        if (data.isEmpty()) return
        val dis = DataInputStream(ByteArrayInputStream(data))

        encounterCounts.clear()
        val countSize = dis.readInt()
        repeat(countSize) {
            val key = dis.readUTF()
            val count = dis.readInt()
            encounterCounts[key] = count
        }
        totalEncounters = dis.readInt()

        fTable.clear()
        val fSize = dis.readInt()
        repeat(fSize) {
            val key = dis.readUTF()
            val value = dis.readDouble()
            fTable[key] = value
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Serialisation helpers for routing summary exchange
    // ──────────────────────────────────────────────────────────────────────

    /**
     * `[count][count × (UTF id, f)]` (the likeliest contacts first) then `[count][count × (UTF id, cost)]`
     * (our path costs to nodes further away, cheapest first), within [SUMMARY_BYTE_BUDGET] so it always
     * fits one frame (the whole f-table overflowed the 223 B budget past ~11 known nodes, and an
     * oversized summary is never sent). Readers that predate the cost part stop after the f entries.
     */
    private fun encodeSummary(f: Map<String, Double>, costs: Map<String, Double>): ByteArray {
        var bytes = 8 // the two counts
        fun fits(key: String): Boolean {
            val size = 2 + key.toByteArray(Charsets.UTF_8).size + 8
            if (bytes + size > SUMMARY_BYTE_BUDGET) return false
            bytes += size
            return true
        }
        val contacts = f.entries.filter { it.value > 0.0 }.sortedByDescending { it.value }.filter { fits(it.key) }
        val far = costs.entries.sortedBy { it.value }.filter { fits(it.key) }
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        for (part in listOf(contacts, far)) {
            dos.writeInt(part.size)
            for ((key, value) in part) {
                dos.writeUTF(key)
                dos.writeDouble(value)
            }
        }
        dos.flush()
        return bos.toByteArray()
    }

    /** A neighbour's summary: its f-vector, and its path costs (empty from older builds). */
    private fun decodeSummary(data: ByteArray): Pair<Map<String, Double>, Map<String, Double>> {
        if (data.isEmpty()) return emptyMap<String, Double>() to emptyMap()
        val dis = DataInputStream(ByteArrayInputStream(data))
        fun part(): Map<String, Double> {
            val count = dis.readInt()
            val map = mutableMapOf<String, Double>()
            repeat(count) {
                val key = dis.readUTF()
                val value = dis.readDouble()
                map[key] = value
            }
            return map
        }
        val f = part()
        val costs = if (dis.available() >= 4) part().filterValues { it.isFinite() && it >= 0.0 } else emptyMap()
        return f to costs
    }
}

/**
 * MaxProp tunable parameters.
 */
data class MaxPropConfig(
    /** Whether to enable recency-weighted decay of encounter counts. */
    val enableRecencyDecay: Boolean = true,
    /** Multiplicative factor for decaying old encounter counts each aging cycle. */
    val recencyDecayFactor: Double = 0.9,
    /** Hard hop limit; a message at or beyond this many hops is never forwarded again. */
    val maxHops: Int = 10,
    /**
     * MaxProp "head-start" for new packets: messages at or below this hop count are prioritised
     * so freshly-injected bundles spread before older ones (Burgess §III-C).
     */
    val newPacketHopThreshold: Int = 1,
    /** Priority multiplier applied to new (low-hop) packets for the head-start. */
    val headStartBoost: Double = 1.5,
    /**
     * Low-priority exploratory forward used when Dijkstra finds NO known path to the destination
     * and the candidate peer reports no `f(peer,dest)` either (cold start, or a freshly-met mule
     * we haven't exchanged summaries with). Without it MaxProp refused to hand a bundle to any
     * exploratory carrier, so nothing spread until a complete cost-path already existed — a
     * bootstrap dead-end. Mirrors PROPHET's `relayBaseFloor`; [maxHops] and the orchestrator's
     * per-peer rate-limit bound the resulting spread.
     */
    val exploratoryFloor: Double = 0.02,
)
