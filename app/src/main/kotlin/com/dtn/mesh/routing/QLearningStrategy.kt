package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import kotlin.math.max

/**
 * Reinforcement-learning routing option backed by the Double Q-Learning engine.
 *
 * IMPORTANT: unlike PRoPHET/MaxProp, the actual FORWARD-vs-STORE decision for this strategy is
 * made in [com.dtn.mesh.service.DtnOrchestrator], NOT here. The reason is that the Q-engine's
 * 9-feature [com.dtn.mesh.learning.ForwardingState] needs live data that only the orchestrator
 * has — per-peer contact statistics (encounter frequency/duration, historical success), current
 * buffer occupancy, and per-message duplicate counts — none of which are available through the
 * transport-agnostic [RoutingStrategy] interface.
 *
 * This class therefore exists so Q-learning is a first-class, selectable strategy (name, routing
 * summary, drop policy). Its [rankForForwarding] is only a safe fallback (behaves epidemic-like)
 * for any code path that doesn't route through the orchestrator's Q-decision branch.
 */
class QLearningStrategy(
    private val maxHops: Int = 10,
) : RoutingStrategy {

    override val name: String = "Q-LEARNING"

    override fun onEncounter(peerId: NodeId, contactRecord: ContactRecord) { /* learning is reward-driven, not encounter-driven */ }

    override fun onRoutingSummaryReceived(peerId: NodeId, summary: RoutingSummary) { /* no summary exchange */ }

    override fun buildRoutingSummary(): RoutingSummary =
        RoutingSummary(strategyTag = name, payload = ByteArray(0))

    /** Fallback only — the orchestrator overrides this with a real Q-engine decision. */
    override fun rankForForwarding(candidates: List<DtnMessage>, peerId: NodeId): List<ForwardCandidate> =
        candidates.mapNotNull { msg ->
            if (msg.hopCount >= maxHops) return@mapNotNull null
            val ttlFraction = msg.remainingTtlMs().toDouble() / max(msg.ttlMs, 1L).toDouble()
            ForwardCandidate(message = msg, priority = ttlFraction)
        }.sortedByDescending { it.priority }

    override fun rankForDrop(candidates: List<DtnMessage>, bytesToFree: Long): List<DtnMessage> =
        candidates.sortedBy { it.remainingTtlMs() }

    override fun onPeriodicAge() { /* Q-tables age via reward updates, not time */ }

    override fun getDeliveryProbability(peerId: NodeId): Double = 0.5

    override fun exportState(): ByteArray = ByteArray(0)
    override fun importState(data: ByteArray) { /* Q-tables persisted separately via the engine snapshot */ }
}
