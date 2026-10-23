package com.dtn.mesh.learning

import com.dtn.mesh.model.ForwardingAction

/**
 * Serialisable snapshot of both Q-tables for persistence and research export.
 */
data class QTableSnapshot(
    val tableA: Map<String, Map<ForwardingAction, Double>>,
    val tableB: Map<String, Map<ForwardingAction, Double>>,
    val episodeCount: Long,
    val totalUpdates: Long,
    val capturedAtMs: Long,
    /** Exploration rate when captured, so a restart doesn't go back to fully random choices. */
    val epsilon: Double = 1.0,
)
