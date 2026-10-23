package com.dtn.mesh.learning

import com.dtn.mesh.model.ForwardingAction

/**
 * Hyperparameter bundle for the Double Q-Learning engine.
 * Injectable/configurable for ablation studies.
 *
 * ## State (12 features, DiMaxProp paper §VI-A)
 * The original 9 link/peer features plus the three the DiMaxProp paper adds to the state vector:
 * the relay's DiMaxProp path cost to the destination C(B,D), the bundle's hop count, and the
 * relay's reported buffer occupancy.
 *
 * ## State space sizing rationale
 * DTN encounters are sparse: a typical research deployment generates ~3,000 forwarding
 * decisions. Bucket counts are chosen so the effective state space stays around 10k states (as
 * before the three new features) and can still converge:
 * - 3 buckets: deliveryProbability, remainingTtl, encounterFrequency, DiMaxProp cost
 * - 2 buckets: rssi, encounterDuration, bufferOccupancy, historicalSuccess, duplicateCount,
 *   hopCount, relayBufferOccupancy
 * - 1 bucket (ignored): snr — none of our links measures SNR (BLE reports none; Wi-Fi Direct and
 *   the hub report fixed placeholders), so it only split the table.
 *
 * Effective state space: 2·1·3·2·3·3·2·2·2·3·2·2 = 10,368
 */
data class QLearningConfig(
    /** Step size for Q-value updates. */
    val learningRate: Double = 0.1,
    /** Discount factor γ for future rewards. */
    val discountFactor: Double = 0.9,
    /** Initial exploration probability. */
    val epsilonInitial: Double = 1.0,
    /** Minimum exploration probability (floor). */
    val epsilonMin: Double = 0.05,
    /** Multiplicative decay applied to epsilon after each update. */
    val epsilonDecay: Double = 0.995,

    /**
     * Actions the engine chooses between. The orchestrator only ever forwards a bundle or keeps
     * holding it, so WAIT / DROP (no-ops there) are left out: exploring them wasted 3 of every 4
     * random picks and split the learning across three equivalent "hold" actions.
     */
    val actions: List<ForwardingAction> = listOf(ForwardingAction.STORE, ForwardingAction.FORWARD),

    /** η (DiMaxProp paper eq. 13): scale of the per-hop progress reward η·(C(A,D) − C(B,D)). */
    val shapingEta: Double = 0.2,

    // ── Per-feature discretisation granularity ──────────────────────────

    /** Buckets for deliveryProbability — core routing signal. */
    val bucketsDeliveryProbability: Int = 3,
    /** Buckets for remainingTtlFraction — urgency drives forward/wait. */
    val bucketsRemainingTtl: Int = 3,
    /** Buckets for bufferOccupancy (this phone) — low/high pressure. */
    val bucketsBufferOccupancy: Int = 2,
    /** Buckets for historicalSuccessRate — low/high reliability. */
    val bucketsHistoricalSuccess: Int = 2,
    /** Buckets for encounterFrequency — rare/occasional/frequent. */
    val bucketsEncounterFrequency: Int = 3,
    /** Buckets for rssiNorm — good/poor signal (binary). */
    val bucketsRssi: Int = 2,
    /** Buckets for snrNorm — 1 = ignored (no link here measures SNR). */
    val bucketsSnr: Int = 1,
    /** Buckets for encounterDuration — brief/sustained (binary). */
    val bucketsEncounterDuration: Int = 2,
    /** Buckets for duplicateCountNorm — novel/already-spread (binary). */
    val bucketsDuplicateCount: Int = 2,
    /** Buckets for the relay's DiMaxProp cost to the destination — near / far / unreachable. */
    val bucketsDiMaxPropCost: Int = 3,
    /** Buckets for hopCountNorm — fresh / well travelled. */
    val bucketsHopCount: Int = 2,
    /** Buckets for the relay's reported buffer occupancy — has room / nearly full. */
    val bucketsRelayBuffer: Int = 2,
) {
    /**
     * Effective state space size with current bucket configuration.
     * Default: 2·1·3·2·3·3·2·2·2·3·2·2 = 10,368
     */
    val effectiveStateSpace: Int
        get() = bucketsDeliveryProbability * bucketsRemainingTtl *
            bucketsBufferOccupancy * bucketsHistoricalSuccess * bucketsEncounterFrequency *
            bucketsRssi * bucketsSnr * bucketsEncounterDuration * bucketsDuplicateCount *
            bucketsDiMaxPropCost * bucketsHopCount * bucketsRelayBuffer

    /**
     * Identifies the state encoding. A saved Q-table is only loaded back when this matches, so a
     * change in features or buckets starts a fresh table instead of mis-reading old keys.
     */
    val stateSchema: String
        get() = "dq2:" + listOf(
            bucketsRssi, bucketsSnr, bucketsEncounterFrequency, bucketsEncounterDuration,
            bucketsDeliveryProbability, bucketsRemainingTtl, bucketsBufferOccupancy,
            bucketsHistoricalSuccess, bucketsDuplicateCount, bucketsDiMaxPropCost, bucketsHopCount,
            bucketsRelayBuffer,
        ).joinToString(".") + ":" + actions.joinToString(",") { it.name }
}
