package com.dtn.mesh.learning

import com.dtn.mesh.model.ForwardingAction
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Double Q-Learning forwarding engine for DTN store-carry-forward decisions.
 *
 * Maintains two independent Q-tables (QA and QB) to reduce overestimation bias
 * (van Hasselt, 2010). On each update step one table is chosen at random to update;
 * the other evaluates the bootstrapped target action.
 *
 * ## State discretisation
 * Continuous [ForwardingState] features in [0,1] are bucketed per feature (see [QLearningConfig]).
 * The state key is the concatenation of all bucket indices, e.g. "1-0-2-1-2-1-0-1-0-2-0-1"
 * (12 dimensions, including the DiMaxProp path cost, hop count and relay buffer).
 *
 * ## Action space
 * [QLearningConfig.actions]: STORE (keep holding) and FORWARD — the two things the orchestrator
 * can do with a bundle for a given carrier.
 *
 * ## Thread safety
 * Not internally synchronised. All calls must be serialised by the caller
 * (ForwardingWorker runs on a single sequential coroutine).
 *
 * ## Dependencies
 * Zero Android framework dependencies. Independently unit-testable with synthetic data.
 */
class DoubleQLearningEngine(
    val config: QLearningConfig = QLearningConfig(),
    private val random: Random = Random.Default,
) {
    private val tableA = QTable()
    private val tableB = QTable()

    private var _epsilon: Double = config.epsilonInitial
    private var _episodeCount: Long = 0
    private var _totalUpdates: Long = 0

    /** Current exploration epsilon (read-only, for logging/export). */
    val currentEpsilon: Double get() = _epsilon

    /** Total Q-update steps performed. */
    val totalUpdates: Long get() = _totalUpdates

    /** Number of states visited in table A. */
    val tableASize: Int get() = tableA.size

    /** Number of states visited in table B. */
    val tableBSize: Int get() = tableB.size

    // ──────────────────────────────────────────────────────────────────────
    // Action selection
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Greedy action selection using averaged Q-values: Q̄ = (QA + QB) / 2, over [QLearningConfig.actions].
     * Used for inference (exploitation only, no exploration). Ties (e.g. a state never seen) go to
     * the first configured action (STORE).
     */
    fun selectAction(state: ForwardingState): ForwardingAction {
        val key = discretise(state)
        return bestActionByAverage(key, tieBreak = null)
    }

    /**
     * ε-greedy action selection for training.
     * With probability [epsilon]: choose uniformly among [QLearningConfig.actions] (exploration).
     * Otherwise: greedy w.r.t. averaged Q-values (exploitation).
     *
     * @param tieBreak action to take when every action has the same value — typically a state the
     *   engine has never been rewarded in. The orchestrator passes the DiMaxProp rule's choice, so a
     *   fresh table starts from a sensible policy instead of "never forward". It is a prior, not an
     *   override: once the state has learned values, they decide.
     * @return Pair of (action chosen, whether it was exploratory)
     */
    fun selectActionEpsilonGreedy(state: ForwardingState, tieBreak: ForwardingAction? = null): Pair<ForwardingAction, Boolean> {
        val explore = random.nextDouble() < _epsilon
        return if (explore) {
            val actions = config.actions
            actions[random.nextInt(actions.size)] to true
        } else {
            bestActionByAverage(discretise(state), tieBreak) to false
        }
    }

    /**
     * Averaged `(Q_A + Q_B) / 2` for FORWARD and STORE in [state] — read-only, for explaining a
     * decision in the UI. Unseen states return (0.0, 0.0).
     */
    fun qValues(state: ForwardingState): Pair<Double, Double> {
        val key = discretise(state)
        val forward = (tableA.get(key, ForwardingAction.FORWARD) + tableB.get(key, ForwardingAction.FORWARD)) / 2.0
        val store = (tableA.get(key, ForwardingAction.STORE) + tableB.get(key, ForwardingAction.STORE)) / 2.0
        return forward to store
    }

    private fun bestActionByAverage(stateKey: String, tieBreak: ForwardingAction?): ForwardingAction {
        val actions = config.actions
        var bestAction = actions.first()
        var bestValue = Double.NEGATIVE_INFINITY
        var allEqual = true
        var firstValue: Double? = null

        for (action in actions) {
            val avg = (tableA.get(stateKey, action) + tableB.get(stateKey, action)) / 2.0
            if (firstValue == null) firstValue = avg else if (avg != firstValue) allEqual = false
            if (avg > bestValue) {
                bestValue = avg
                bestAction = action
            }
        }
        return if (allEqual && tieBreak != null && tieBreak in actions) tieBreak else bestAction
    }

    // ──────────────────────────────────────────────────────────────────────
    // Q-update (Double Q-Learning core)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Perform one Double Q-Learning update step.
     *
     * Algorithm (van Hasselt 2010):
     * 1. Flip a fair coin to choose the "update" table (A or B).
     * 2. Use the OTHER table to evaluate the best next action (argmax).
     * 3. Update the chosen table with the TD target:
     *    Q_update(s,a) ← Q_update(s,a) + α * [r + γ * Q_other(s', argmax_a' Q_update(s',a')) - Q_update(s,a)]
     *
     * @param state      Observation before the action.
     * @param action     Action that was taken.
     * @param reward     Scalar reward signal.
     * @param nextState  Observation after action (null if terminal/episode end).
     */
    fun update(
        state: ForwardingState,
        action: ForwardingAction,
        reward: Double,
        nextState: ForwardingState?,
    ) {
        val stateKey = discretise(state)
        val nextStateKey = nextState?.let { discretise(it) }

        val updateA = random.nextBoolean()

        if (updateA) {
            updateTable(
                updateTable = tableA,
                evalTable = tableB,
                stateKey = stateKey,
                action = action,
                reward = reward,
                nextStateKey = nextStateKey,
            )
        } else {
            updateTable(
                updateTable = tableB,
                evalTable = tableA,
                stateKey = stateKey,
                action = action,
                reward = reward,
                nextStateKey = nextStateKey,
            )
        }

        _totalUpdates++
        decayEpsilon()
    }

    private fun updateTable(
        updateTable: QTable,
        evalTable: QTable,
        stateKey: String,
        action: ForwardingAction,
        reward: Double,
        nextStateKey: String?,
    ) {
        val currentQ = updateTable.get(stateKey, action)

        val target = if (nextStateKey == null) {
            // Terminal state — no future reward
            reward
        } else {
            // Double Q trick: argmax from update table, value from eval table
            val bestNextAction = updateTable.argmax(nextStateKey)
            reward + config.discountFactor * evalTable.get(nextStateKey, bestNextAction)
        }

        val newQ = currentQ + config.learningRate * (target - currentQ)
        updateTable.set(stateKey, action, newQ)
    }

    private fun decayEpsilon() {
        _epsilon = (_epsilon * config.epsilonDecay).coerceAtLeast(config.epsilonMin)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Utility computation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Compute a utility score in [0.0, 1.0] representing the desirability
     * of forwarding a message given the current state.
     *
     * Uses the averaged Q-value of FORWARD normalised against all actions.
     * Used by routing strategies as a tie-breaker between candidates.
     */
    fun computeUtility(state: ForwardingState): Double {
        val key = discretise(state)
        val forwardQ = (tableA.get(key, ForwardingAction.FORWARD) +
            tableB.get(key, ForwardingAction.FORWARD)) / 2.0

        // Normalise against max possible Q to get [0,1]
        val allQs = config.actions.map { action ->
            (tableA.get(key, action) + tableB.get(key, action)) / 2.0
        }
        val maxQ = allQs.maxOrNull() ?: 0.0
        val minQ = allQs.minOrNull() ?: 0.0
        val range = maxQ - minQ

        return if (range == 0.0) 0.5 // No information yet — neutral utility
        else ((forwardQ - minQ) / range).coerceIn(0.0, 1.0)
    }

    // ──────────────────────────────────────────────────────────────────────
    // State discretisation
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Discretise a continuous [ForwardingState] into a string key.
     *
     * Each dimension uses its own bucket count from [QLearningConfig], producing
     * a key like "1-0-2-1-2-1-0-1-0-2-0-1" (12 positions) where each position has a range
     * determined by its feature's granularity.
     *
     * Effective state space with defaults: [QLearningConfig.effectiveStateSpace] = 10,368
     */
    fun discretise(state: ForwardingState): String {
        return buildString {
            append(bucket(state.rssiNorm, config.bucketsRssi)); append('-')
            append(bucket(state.snrNorm, config.bucketsSnr)); append('-')
            append(bucket(state.encounterFrequency, config.bucketsEncounterFrequency)); append('-')
            append(bucket(state.encounterDuration, config.bucketsEncounterDuration)); append('-')
            append(bucket(state.deliveryProbability, config.bucketsDeliveryProbability)); append('-')
            append(bucket(state.remainingTtlFraction, config.bucketsRemainingTtl)); append('-')
            append(bucket(state.bufferOccupancy, config.bucketsBufferOccupancy)); append('-')
            append(bucket(state.historicalSuccessRate, config.bucketsHistoricalSuccess)); append('-')
            append(bucket(state.duplicateCountNorm, config.bucketsDuplicateCount)); append('-')
            append(bucket(state.diMaxPropCost, config.bucketsDiMaxPropCost)); append('-')
            append(bucket(state.hopCountNorm, config.bucketsHopCount)); append('-')
            append(bucket(state.relayBufferOccupancy, config.bucketsRelayBuffer))
        }
    }

    private fun bucket(value: Double, numBuckets: Int): Int =
        if (numBuckets <= 1) 0 else (value.coerceIn(0.0, 1.0) * (numBuckets - 1)).roundToInt()

    // ──────────────────────────────────────────────────────────────────────
    // Snapshot / persistence
    // ──────────────────────────────────────────────────────────────────────

    /** Export a snapshot of both Q-tables for research export or persistence. */
    fun exportSnapshot(): QTableSnapshot = QTableSnapshot(
        tableA = tableA.export(),
        tableB = tableB.export(),
        episodeCount = _episodeCount,
        totalUpdates = _totalUpdates,
        capturedAtMs = System.currentTimeMillis(),
        epsilon = _epsilon,
    )

    /** Restore Q-tables (and the exploration rate) from a previously exported snapshot. Warm start. */
    fun importSnapshot(snapshot: QTableSnapshot) {
        tableA.import(snapshot.tableA)
        tableB.import(snapshot.tableB)
        _episodeCount = snapshot.episodeCount
        _totalUpdates = snapshot.totalUpdates
        _epsilon = snapshot.epsilon.coerceIn(config.epsilonMin, 1.0)
    }

    /** Serialised tables for saving to disk ([QTableCodec], tagged with the state schema). */
    fun toBytes(): ByteArray = QTableCodec.encode(exportSnapshot(), config.stateSchema)

    /** Load tables saved by [toBytes]. False (nothing changed) when the bytes don't match this schema. */
    fun loadBytes(bytes: ByteArray): Boolean {
        val snapshot = QTableCodec.decode(bytes, config.stateSchema) ?: return false
        importSnapshot(snapshot)
        return true
    }

    /** Reset both tables to zero. Useful for ablation experiments. */
    fun reset() {
        tableA.clear()
        tableB.clear()
        _epsilon = config.epsilonInitial
        _episodeCount = 0
        _totalUpdates = 0
    }

    /** Increment episode counter (called when a full encounter window concludes). */
    fun endEpisode() {
        _episodeCount++
    }

    /** Set epsilon directly (for experiment scripting). */
    fun setEpsilon(value: Double) {
        _epsilon = value.coerceIn(0.0, 1.0)
    }
}
