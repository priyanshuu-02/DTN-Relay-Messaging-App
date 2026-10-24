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
 * on-device benchmark — a node can only directly measure the messages it took on, the copies it
 * handed on and the deliveries it saw confirmed. They live in memory since the app started (or the
 * last reset); nothing is persisted.
 *
 * - [handled]: direct messages (bundles) this phone sent or carried — created here, accepted to
 *   carry, or first handed on since the start — once per bundle id. DMs addressed to this phone
 *   aren't counted: the sender's and the carriers' algorithms routed them, not this phone's.
 * - [deliveries]: of those, the ones this phone saw delivered — handed straight to the destination
 *   or confirmed by an end-to-end receipt — once per bundle id. So the delivery rate
 *   (deliveries ÷ handled) is real and never passes 100 %.
 * - A delivery is credited to the strategy that was active when the message was handled, so each
 *   row compares like with like after a switch. Forwards go to the strategy that made them.
 */
data class BenchmarkStat(
    val strategy: String,
    /** Direct messages this phone sent or carried since the start. */
    val handled: Long = 0,
    /** DM copies we handed on over a radio link (relay or direct) — a proxy for bandwidth/energy overhead. */
    val forwards: Long = 0,
    /** Handled messages this phone saw delivered (handed straight to the destination, or a receipt). */
    val deliveries: Long = 0,
    /** Σ hopCount observed at delivery, over the [hopSamples] deliveries whose hop count is known. */
    val sumHops: Long = 0,
    val hopSamples: Long = 0,
    /** Σ time from creation until this phone saw the delivery (ms), over [latencySamples] deliveries. */
    val sumLatencyMs: Long = 0,
    val latencySamples: Long = 0,
) {
    val avgHops: Double get() = if (hopSamples > 0) sumHops.toDouble() / hopSamples else 0.0
    val avgLatencyMs: Double get() = if (latencySamples > 0) sumLatencyMs.toDouble() / latencySamples else 0.0

    /** deliveries ÷ handled, 0..1; null until something was handled. */
    val deliveryRate: Double? get() = if (handled > 0) (deliveries.toDouble() / handled).coerceAtMost(1.0) else null

    /** Forwards per delivery (the overhead of each delivery); null until the first delivery. */
    val overhead: Double? get() = if (deliveries > 0) forwards.toDouble() / deliveries else null

    /** Deliveries per forward — higher means more delivery for less transmission (efficiency). */
    val efficiency: Double get() = if (forwards > 0) deliveries.toDouble() / forwards else 0.0
}

/**
 * Thread-safe accumulator of [BenchmarkStat] keyed by strategy name, exposed as a [StateFlow]
 * for the UI. Injected as a singleton so the orchestrator (writer) and UI (reader) share one.
 */
@Singleton
class BenchmarkTracker @Inject constructor() {

    companion object {
        /** Message ids remembered for the once-per-message counts (the oldest are forgotten first). */
        const val MAX_TRACKED = 4_096
    }

    /** A message this phone handled: the strategy it was handled under, its creation time, delivered yet. */
    private class Tracked(val strategy: String, val createdAtMs: Long, var delivered: Boolean = false)

    private val lock = Any()
    private val stats = mutableMapOf<String, BenchmarkStat>()
    private val tracked = object : LinkedHashMap<String, Tracked>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tracked>?): Boolean = size > MAX_TRACKED
    }

    private val _flow = MutableStateFlow<List<BenchmarkStat>>(emptyList())
    val flow: StateFlow<List<BenchmarkStat>> = _flow.asStateFlow()

    /**
     * A direct message this phone took on under [strategy]: created here, accepted to carry, or
     * handed on for the first time since the start. Counted once per [msgId]. @return true if it was new.
     */
    fun recordHandled(strategy: String, msgId: String, createdAtMs: Long = 0L): Boolean {
        synchronized(lock) {
            if (tracked.containsKey(msgId)) return false
            tracked[msgId] = Tracked(strategy, createdAtMs)
            mutate(strategy) { it.copy(handled = it.handled + 1) }
            return true
        }
    }

    /** Record a DM transmission (relay or direct) attributed to [strategy]. */
    fun recordForward(strategy: String) {
        synchronized(lock) { mutate(strategy) { it.copy(forwards = it.forwards + 1) } }
    }

    /**
     * Record a confirmed delivery with its hop count and end-to-end latency: this phone handed the
     * message straight to its destination.
     *
     * With a [msgId] it counts once per message, for the strategy that handled it; a message not
     * handled since the start (or the last reset) is counted as handled now, so the rate stays
     * ≤ 100 %. @return true if it was counted.
     */
    fun recordDelivery(strategy: String, hops: Int, latencyMs: Long, msgId: String? = null): Boolean {
        synchronized(lock) {
            val credit = if (msgId == null) strategy else claim(msgId, strategy) ?: return false
            mutate(credit) {
                it.copy(
                    deliveries = it.deliveries + 1,
                    sumHops = it.sumHops + hops.coerceAtLeast(0),
                    hopSamples = it.hopSamples + 1,
                    sumLatencyMs = it.sumLatencyMs + latencyMs.coerceAtLeast(0),
                    latencySamples = it.latencySamples + 1,
                )
            }
            return true
        }
    }

    /**
     * An end-to-end receipt for [msgId] arrived: a delivery if this phone handled that message and
     * hadn't seen it delivered yet (receipts spread to everyone, so most are about other people's
     * messages and are ignored). The hop count isn't known from a receipt. @return true if counted.
     */
    fun recordReceipt(msgId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            val t = tracked[msgId] ?: return false
            if (t.delivered) return false
            t.delivered = true
            val known = t.createdAtMs > 0
            mutate(t.strategy) {
                it.copy(
                    deliveries = it.deliveries + 1,
                    sumLatencyMs = it.sumLatencyMs + if (known) (nowMs - t.createdAtMs).coerceAtLeast(0) else 0L,
                    latencySamples = it.latencySamples + if (known) 1 else 0,
                )
            }
            return true
        }
    }

    /** Clear all accumulated metrics (e.g. to start a fresh benchmark run). */
    fun reset() {
        synchronized(lock) {
            stats.clear()
            tracked.clear()
            _flow.value = emptyList()
        }
    }

    /** The strategy a delivery of [msgId] is credited to, or null if it was already counted. Under [lock]. */
    private fun claim(msgId: String, strategy: String): String? {
        val t = tracked[msgId]
        if (t == null) {
            tracked[msgId] = Tracked(strategy, 0L, delivered = true)
            mutate(strategy) { it.copy(handled = it.handled + 1) }
            return strategy
        }
        if (t.delivered) return null
        t.delivered = true
        return t.strategy
    }

    /** Under [lock]. */
    private fun mutate(strategy: String, f: (BenchmarkStat) -> BenchmarkStat) {
        val current = stats[strategy] ?: BenchmarkStat(strategy)
        stats[strategy] = f(current)
        _flow.value = stats.values.sortedBy { it.strategy }
    }
}
