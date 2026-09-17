package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId

/**
 * Runtime-switchable wrapper around [RoutingStrategy] implementations.
 *
 * Allows toggling between PRoPHET and MaxProp at runtime (e.g. via a settings
 * toggle or experiment script) without any changes to the orchestrator or
 * scheduler code that consumes the routing layer.
 *
 * Both strategies are kept alive simultaneously so state is preserved across
 * switches — useful for A/B comparison experiments within a single run.
 */
class StrategySelector(
    private val prophet: ProphetStrategy = ProphetStrategy(),
    private val maxProp: MaxPropStrategy = MaxPropStrategy(),
    private val epidemic: EpidemicStrategy = EpidemicStrategy(),
    private val stableProphet: ProphetStrategy = ProphetStrategy(ProphetConfig(stabilityAware = true)),
    private val qLearning: QLearningStrategy = QLearningStrategy(),
    initialStrategy: StrategyType = StrategyType.PROPHET,
) : RoutingStrategy {

    enum class StrategyType { PROPHET, MAXPROP, EPIDEMIC, STABLE, QLEARNING }

    var activeType: StrategyType = initialStrategy
        private set

    val active: RoutingStrategy
        get() = when (activeType) {
            StrategyType.PROPHET -> prophet
            StrategyType.MAXPROP -> maxProp
            StrategyType.EPIDEMIC -> epidemic
            StrategyType.STABLE -> stableProphet
            StrategyType.QLEARNING -> qLearning
        }

    override val name: String get() = active.name

    /**
     * Switch to a different strategy at runtime.
     * Both strategies continue maintaining state regardless of which is active.
     */
    fun switchTo(type: StrategyType) {
        activeType = type
    }

    // ── Delegate all calls to BOTH strategies (parallel state maintenance) ──
    // Only the active strategy's results are returned for ranking/forwarding,
    // but both receive encounter/aging updates so either can be activated at any time.

    override fun onEncounter(peerId: NodeId, contactRecord: ContactRecord) {
        prophet.onEncounter(peerId, contactRecord)
        maxProp.onEncounter(peerId, contactRecord)
        epidemic.onEncounter(peerId, contactRecord)
        stableProphet.onEncounter(peerId, contactRecord)
    }

    override fun onRoutingSummaryReceived(peerId: NodeId, summary: RoutingSummary) {
        // Route to the matching strategy based on tag. A summary tagged for a strategy this
        // node isn't running (e.g. a PROPHET peer talking to a MaxProp/Epidemic node) simply
        // doesn't match any case and is ignored — see the cross-algorithm notes.
        when (summary.strategyTag) {
            "PROPHET" -> prophet.onRoutingSummaryReceived(peerId, summary)
            "STABLE-PROPHET" -> stableProphet.onRoutingSummaryReceived(peerId, summary)
            "MAXPROP" -> maxProp.onRoutingSummaryReceived(peerId, summary)
            // EPIDEMIC carries no routing state — nothing to integrate.
        }
    }

    override fun buildRoutingSummary(): RoutingSummary = active.buildRoutingSummary()

    override fun rankForForwarding(candidates: List<DtnMessage>, peerId: NodeId): List<ForwardCandidate> =
        active.rankForForwarding(candidates, peerId)

    override fun rankForDrop(candidates: List<DtnMessage>, bytesToFree: Long): List<DtnMessage> =
        active.rankForDrop(candidates, bytesToFree)

    override fun onPeriodicAge() {
        prophet.onPeriodicAge()
        maxProp.onPeriodicAge()
        epidemic.onPeriodicAge()
        stableProphet.onPeriodicAge()
    }

    override fun getDeliveryProbability(peerId: NodeId): Double =
        active.getDeliveryProbability(peerId)

    override fun exportState(): ByteArray = active.exportState()

    override fun importState(data: ByteArray) = active.importState(data)

    /**
     * Canonical PRoPHET delivery predictability toward a peer, INDEPENDENT of which strategy is
     * currently active. Both PRoPHET variants learn on every encounter, so this is a stable value
     * to persist into the contacts table (and to reflect in the UI) even when MaxProp / Epidemic /
     * Q-Learning is the active router.
     */
    fun prophetDeliveryProbability(peerId: NodeId): Double = prophet.getDeliveryProbability(peerId)

    /** Seed both PRoPHET variants' P-tables from persisted probabilities on startup. */
    fun seedProphetProbabilities(seed: Map<String, Double>) {
        prophet.seedProbabilities(seed)
        stableProphet.seedProbabilities(seed)
    }

    /** Export both strategies' states for full checkpoint. */
    fun exportAllStates(): Pair<ByteArray, ByteArray> =
        prophet.exportState() to maxProp.exportState()

    /** Import both strategies' states from a checkpoint. */
    fun importAllStates(prophetState: ByteArray, maxPropState: ByteArray) {
        prophet.importState(prophetState)
        maxProp.importState(maxPropState)
    }
}
