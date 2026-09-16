package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import kotlin.math.max

/**
 * Epidemic routing — classic flooding store-carry-forward.
 *
 * Reference: Vahdat & Becker, "Epidemic Routing for Partially-Connected Ad Hoc Networks", 2000.
 *
 * The simplest, most robust DTN baseline: forward every buffered bundle to every peer we
 * encounter (subject only to a hop limit). There is no probability model and no routing
 * summary exchange — a node holds a copy and hands one to everyone it meets. Duplicate
 * suppression happens at ingest via message-id dedup, so re-flooding is safe.
 *
 * Useful as an A/B comparison point: epidemic maximises delivery ratio and minimises latency
 * at the cost of the most bandwidth/buffer, so it bounds the "best case" that PROPHET / MaxProp
 * try to approach with far fewer copies.
 *
 * Pure Kotlin, no Android dependencies, unit-testable.
 */
class EpidemicStrategy(
    /** Hard hop limit; a bundle at or beyond this many hops is not forwarded again. */
    private val maxHops: Int = 10,
) : RoutingStrategy {

    override val name: String = "EPIDEMIC"

    // Epidemic keeps no per-peer state.
    override fun onEncounter(peerId: NodeId, contactRecord: ContactRecord) { /* stateless */ }

    // No routing knowledge is exchanged; summaries from peers are irrelevant.
    override fun onRoutingSummaryReceived(peerId: NodeId, summary: RoutingSummary) { /* ignored */ }

    override fun buildRoutingSummary(): RoutingSummary =
        RoutingSummary(strategyTag = name, payload = ByteArray(0))

    /**
     * Forward EVERY candidate to this peer (flooding), skipping only hop-limited bundles.
     * Direct delivery is prioritised, then all other bundles get an equal relay priority so
     * they fan out to every encountered peer.
     */
    override fun rankForForwarding(candidates: List<DtnMessage>, peerId: NodeId): List<ForwardCandidate> =
        candidates.mapNotNull { msg ->
            if (msg.hopCount >= maxHops) return@mapNotNull null
            val ttlFraction = msg.remainingTtlMs().toDouble() / max(msg.ttlMs, 1L).toDouble()
            val priority = if (msg.destinationNodeId == peerId) 1.0 * ttlFraction else 0.5 * ttlFraction
            ForwardCandidate(message = msg, priority = priority)
        }.sortedByDescending { it.priority }

    /** Under buffer pressure, drop the shortest-TTL bundles first. */
    override fun rankForDrop(candidates: List<DtnMessage>, bytesToFree: Long): List<DtnMessage> =
        candidates.sortedBy { it.remainingTtlMs() }

    override fun onPeriodicAge() { /* nothing to age */ }

    /** Epidemic treats every peer as an equally good carrier. */
    override fun getDeliveryProbability(peerId: NodeId): Double = 1.0

    override fun exportState(): ByteArray = ByteArray(0)
    override fun importState(data: ByteArray) { /* stateless */ }
}
