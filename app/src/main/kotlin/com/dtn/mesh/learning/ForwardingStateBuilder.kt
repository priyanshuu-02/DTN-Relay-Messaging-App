package com.dtn.mesh.learning

/**
 * Builds the 12-feature [ForwardingState] from raw per-peer statistics. Shared by the orchestrator
 * (real forwarding decisions) and the UI (Q-learning explanations), so both see exactly the same
 * normalisation. All features land in [0, 1].
 */
object ForwardingStateBuilder {

    /** A DiMaxProp path cost at or above this counts as "far" (the top bucket). */
    const val COST_CAP = 2.0
    /** Shared hop ceiling (phones and hubs). */
    const val HOP_LIMIT = 8

    fun build(
        lastRssi: Int,
        lastSnr: Float,
        totalEncounters: Int,
        totalEncounterDurationMs: Long,
        deliveryProbability: Double,
        remainingTtlMs: Long,
        ttlMs: Long,
        bufferOccupancy: Double,
        deliverySuccessCount: Int,
        deliveryAttemptCount: Int,
        duplicateCount: Int,
        /** The relay's DiMaxProp cost to the destination; +∞ when it knows no path. */
        diMaxPropCost: Double = Double.POSITIVE_INFINITY,
        hopCount: Int = 0,
        /** The relay's reported buffer occupancy, or null if it never reported one. */
        relayBufferOccupancy: Double? = null,
    ): ForwardingState {
        val rssiNorm = if (lastRssi <= -200) 0.0 else ((lastRssi + 100.0) / 60.0).coerceIn(0.0, 1.0) // [-100,-40]→[0,1]
        val snrNorm = ((lastSnr.toDouble() + 20.0) / 40.0).coerceIn(0.0, 1.0) // [-20,20]→[0,1]
        val encFreq = (totalEncounters.toDouble() / 20.0).coerceIn(0.0, 1.0)
        val avgDurMs = if (totalEncounters > 0) totalEncounterDurationMs.toDouble() / totalEncounters else 0.0
        val encDur = (avgDurMs / 60_000.0).coerceIn(0.0, 1.0) // normalise by 60 s
        val ttlFrac = (remainingTtlMs.toDouble() / ttlMs.coerceAtLeast(1L)).coerceIn(0.0, 1.0)
        val succ = if (deliveryAttemptCount > 0) deliverySuccessCount.toDouble() / deliveryAttemptCount else 0.0
        val dupNorm = (duplicateCount.toDouble() / 10.0).coerceIn(0.0, 1.0)
        val costNorm = if (diMaxPropCost.isFinite()) (diMaxPropCost / COST_CAP).coerceIn(0.0, 1.0) else 1.0
        return ForwardingState(
            rssiNorm = rssiNorm,
            snrNorm = snrNorm,
            encounterFrequency = encFreq,
            encounterDuration = encDur,
            deliveryProbability = deliveryProbability.coerceIn(0.0, 1.0),
            remainingTtlFraction = ttlFrac,
            bufferOccupancy = bufferOccupancy.coerceIn(0.0, 1.0),
            historicalSuccessRate = succ.coerceIn(0.0, 1.0),
            duplicateCountNorm = dupNorm,
            diMaxPropCost = costNorm,
            hopCountNorm = (hopCount.toDouble() / HOP_LIMIT).coerceIn(0.0, 1.0),
            relayBufferOccupancy = (relayBufferOccupancy ?: 0.0).coerceIn(0.0, 1.0),
        )
    }
}
