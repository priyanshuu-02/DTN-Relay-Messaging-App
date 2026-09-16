package com.dtn.mesh.learning

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-strategy running metrics for A/B comparison of routing strategies.
 *
 * These are LOCAL counters (this node's own observations), which is the honest scope for an
 * on-device benchmark — a node can only directly measure the transmissions it made and the
 * deliveries it confirmed. Attribution is by the strategy that was active at the time of the
 * event, so switching strategies mid-run accumulates a separate column per strategy.
 */
data class BenchmarkStat(
    val strategy: String,
    /** Relay/direct sends we made — a proxy for bandwidth/energy overhead. */
    val forwards: Long = 0,
    /** Deliveries confirmed at this node (we are the destination, or a receipt arrived). */
    val deliveries: Long = 0,
    /** Σ hopCount observed at delivery (for average hop count). */
    val sumHops: Long = 0,
    /** Σ end-to-end latency (deliveredAt − createdAt) in ms. */
    val sumLatencyMs: Long = 0,
) {
    val avgHops: Double get() = if (deliveries > 0) sumHops.toDouble() / deliveries else 0.0
    val avgLatencyMs: Double get() = if (deliveries > 0) sumLatencyMs.toDouble() / deliveries else 0.0

    /** Deliveries per forward — higher means more delivery for less transmission (efficiency). */
    val efficiency: Double get() = if (forwards > 0) deliveries.toDouble() / forwards else 0.0
}

/**
 * Thread-safe accumulator of [BenchmarkStat] keyed by strategy name, exposed as a [StateFlow]
 * for the UI. Injected as a singleton so the orchestrator (writer) and UI (reader) share one.
 */
@Singleton
class BenchmarkTracker @Inject constructor() {

    private val lock = Any()
    private val stats = mutableMapOf<String, BenchmarkStat>()

    private val _flow = MutableStateFlow<List<BenchmarkStat>>(emptyList())
    val flow: StateFlow<List<BenchmarkStat>> = _flow.asStateFlow()

    /** Record a transmission (relay or direct) attributed to [strategy]. */
    fun recordForward(strategy: String) = mutate(strategy) { it.copy(forwards = it.forwards + 1) }

    /** Record a confirmed delivery with its hop count and end-to-end latency. */
    fun recordDelivery(strategy: String, hops: Int, latencyMs: Long) = mutate(strategy) {
        it.copy(
            deliveries = it.deliveries + 1,
            sumHops = it.sumHops + hops.coerceAtLeast(0),
            sumLatencyMs = it.sumLatencyMs + latencyMs.coerceAtLeast(0),
        )
    }

    /** Clear all accumulated metrics (e.g. to start a fresh benchmark run). */
    fun reset() {
        synchronized(lock) {
            stats.clear()
            _flow.value = emptyList()
        }
    }

    private fun mutate(strategy: String, f: (BenchmarkStat) -> BenchmarkStat) {
        synchronized(lock) {
            val current = stats[strategy] ?: BenchmarkStat(strategy)
            stats[strategy] = f(current)
            _flow.value = stats.values.sortedBy { it.strategy }
        }
    }
}
