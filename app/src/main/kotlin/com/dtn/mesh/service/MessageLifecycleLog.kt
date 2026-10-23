package com.dtn.mesh.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central message-lifecycle journal. Every state transition of every user message goes through
 * here: the Lifecycle tab shows [entries], the Network tab and "Message info" show [routes].
 *
 * Per-message route as THIS node saw it: `source → prevHop → me → nextHop`, plus the link that
 * carried the last hop, the delivery outcome and timestamps. Each phone only knows what it
 * directly observed — there is no cross-network hop list on the wire.
 *
 * **Persistent.** Events and routes are appended to an app-private journal file and reloaded at
 * startup, so a message's journey (and its "delivered" state) survives app restarts and process
 * death. Writes go through one background writer; the file is compacted when it grows.
 *
 * Only user DATA belongs here. Control traffic (key announces, receipts, routing summaries) goes
 * to the engine [console] instead, so it can't masquerade as "broadcast messages in transit".
 */
@Singleton
class MessageLifecycleLog @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "MsgLifecycle"
        private const val MAX_ENTRIES = 1_000
        private const val MAX_ROUTES = 300
        private const val MAX_CONSOLE = 400
        private const val JOURNAL_FILE = "message_journal_v1.log"
        /** Rewrite the journal from memory once it holds this many lines. */
        private const val COMPACT_AFTER_LINES = 6_000
        /**
         * The same event with the same detail for the same message inside this window is a retry
         * (e.g. the 30 s re-send of a DM awaiting its receipt), not news — it isn't journaled
         * again, so a message's timeline stays readable.
         */
        private const val REPEAT_WINDOW_MS = 10 * 60_000L
    }

    private val _entries = MutableStateFlow<List<LifecycleEntry>>(emptyList())
    val entries: StateFlow<List<LifecycleEntry>> = _entries.asStateFlow()

    /**
     * Free-form engine console — encounter history, per-strategy probability calculations, control
     * traffic and other diagnostics that aren't one message's lifecycle. Shown in the Log tab.
     * Newest-first, capped at [MAX_CONSOLE]. Not persisted (it's high-volume diagnostics).
     */
    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    private val consoleFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val timeFmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)

    /** Append a diagnostic line to the engine console (thread-safe, timestamped). */
    fun console(line: String) {
        val stamped = "[${synchronized(consoleFmt) { consoleFmt.format(Date()) }}] $line"
        synchronized(lock) {
            _console.value = (listOf(stamped) + _console.value).take(MAX_CONSOLE)
        }
        Log.d(TAG, line)
    }

    /**
     * Per-message route as seen from THIS node's perspective.
     *
     * - `msgId`: first 8 chars (display); `fullId`: the full bundle id (matches chat rows/receipts).
     * - `source` / `destination`: last 8 of the node ids; destination "BROADCAST" for broadcast/group.
     * - `channel`: group channel (0 = DM or public broadcast).
     * - `prevHop`: who handed it to us (null if we're the source). `nextHop`: who we last gave it to.
     * - `lastPath`: link used for the last hand-off ("BLE", "Internet relay", "LoRa hub flood"…).
     * - `delivered`: reached its destination (we are it, we delivered directly, or a receipt came back).
     * - `outcome`: "" while live, else "EXPIRED" / "DROPPED".
     */
    data class RouteRecord(
        val msgId: String,
        val source: String,
        val destination: String,
        val prevHop: String? = null,
        val me: String,
        val nextHop: String? = null,
        val delivered: Boolean = false,
        val timestampMs: Long = System.currentTimeMillis(),
        /** Routing algorithm (or "INTERNET RELAY") behind the last forward. */
        val strategy: String = "",
        val fullId: String = "",
        val channel: Int = 0,
        val createdAtMs: Long = System.currentTimeMillis(),
        val lastPath: String = "",
        val outcome: String = "",
        val deliveredAtMs: Long = 0L,
        /**
         * The routing decision this phone made when it handed the message on (algorithm + every
         * number it used), saved at that moment so Message info never recomputes it with whatever
         * algorithm is selected later. Multi-line text; "" when none was recorded.
         */
        val decision: String = "",
        val decisionAtMs: Long = 0L,
        /** For a message we received: the routing algorithm the sender's phone used (from the DM). */
        val senderStrategy: String = "",
    ) {
        val isBroadcast: Boolean get() = destination == "BROADCAST"

        /** Human-readable view: `source → prevHop → me → nextHop`, omitting nulls. */
        fun asPath(): String = buildString {
            append(source)
            if (!prevHop.isNullOrBlank() && prevHop != source && prevHop != me) {
                append(" → "); append(prevHop)
            }
            if (me.isNotBlank() && me != source) {
                append(" → "); append(me)
            }
            if (!nextHop.isNullOrBlank() && nextHop != me) {
                append(" → "); append(nextHop)
            }
        }
    }

    private val _routes = MutableStateFlow<List<RouteRecord>>(emptyList())
    val routes: StateFlow<List<RouteRecord>> = _routes.asStateFlow()

    /**
     * Guards the read-modify-write of [_entries] and [_routes]. Both are updated from multiple
     * threads (GATT callbacks, orchestrator, workers); without this two threads could each miss an
     * existing route and both prepend one — a duplicate LazyColumn key crash.
     */
    private val lock = Any()

    // ── Persistence ────────────────────────────────────────────────────────

    private val file = File(context.filesDir, JOURNAL_FILE)
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writes = Channel<String>(capacity = Channel.UNLIMITED)
    @Volatile private var linesWritten = 0

    init {
        io.launch {
            load()
            for (line in writes) {
                runCatching { file.appendText(line + "\n") }.onFailure { Log.w(TAG, "journal write failed", it) }
                if (++linesWritten >= COMPACT_AFTER_LINES) compact()
            }
        }
    }

    private fun load() {
        if (!file.exists()) return
        val loadedEntries = ArrayList<LifecycleEntry>()
        val loadedRoutes = LinkedHashMap<String, RouteRecord>()
        var lines = 0
        runCatching {
            file.forEachLine { line ->
                lines++
                val d = JournalCodec.decode(line) { formatTime(it) } ?: return@forEachLine
                d.event?.let { loadedEntries += it }
                d.route?.let { loadedRoutes.remove(it.msgId); loadedRoutes[it.msgId] = it } // latest wins
            }
        }.onFailure { Log.w(TAG, "journal load failed", it) }
        linesWritten = lines
        synchronized(lock) {
            // Merge with anything logged while we were loading (it is newer).
            val liveIds = _routes.value.map { it.msgId }.toSet()
            val older = loadedRoutes.values.reversed().filter { it.msgId !in liveIds }
            _routes.value = (_routes.value + older).take(MAX_ROUTES)
            _entries.value = (_entries.value + loadedEntries.asReversed()).take(MAX_ENTRIES)
        }
        Log.i(TAG, "Journal loaded: ${loadedEntries.size} events, ${loadedRoutes.size} routes")
    }

    /** Rewrite the file from the in-memory state (atomic replace). */
    private fun compact() {
        val (es, rs) = synchronized(lock) { _entries.value to _routes.value }
        val tmp = File(context.filesDir, "$JOURNAL_FILE.tmp")
        runCatching {
            tmp.bufferedWriter().use { w ->
                es.asReversed().forEach { w.write(JournalCodec.encodeEvent(it)); w.newLine() }
                rs.asReversed().forEach { w.write(JournalCodec.encodeRoute(it)); w.newLine() }
            }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            linesWritten = es.size + rs.size
        }.onFailure { Log.w(TAG, "journal compaction failed", it) }
    }

    private fun persist(entry: LifecycleEntry) { writes.trySend(JournalCodec.encodeEvent(entry)) }
    private fun persist(route: RouteRecord) { writes.trySend(JournalCodec.encodeRoute(route)) }

    private fun formatTime(epochMs: Long): String = synchronized(timeFmt) { timeFmt.format(Date(epochMs)) }

    // ── Events ─────────────────────────────────────────────────────────────

    fun log(
        event: LifecycleEvent,
        msgId: String,
        origin: String = "",
        dest: String = "",
        hopCount: Int = -1,
        forwardCount: Int = -1,
        ttlRemainingMs: Long = -1,
        extra: String = "",
    ) {
        val now = System.currentTimeMillis()
        val entry = LifecycleEntry(
            timestamp = formatTime(now),
            event = event,
            msgId = msgId.take(8),
            origin = origin.takeLast(8),
            dest = normaliseDest(dest),
            hopCount = hopCount,
            forwardCount = forwardCount,
            ttlRemainingMin = if (ttlRemainingMs > 0) (ttlRemainingMs / 60_000L).toInt() else -1,
            extra = extra,
            epochMs = now,
        )
        synchronized(lock) {
            val isRepeat = _entries.value.asSequence()
                .takeWhile { now - it.epochMs < REPEAT_WINDOW_MS }
                .any { it.msgId == entry.msgId && it.event == entry.event && it.extra == entry.extra }
            if (isRepeat) return
            _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        }
        persist(entry)
        Log.d(TAG, entry.toLogLine())
    }

    /** All journal events for one message, oldest first (matches on the 8-char id). */
    fun eventsFor(msgId: String): List<LifecycleEntry> {
        val key = msgId.take(8)
        return _entries.value.filter { it.msgId == key }.asReversed()
    }

    /** True if we keep a route record for this message (we sent, carried or received it). */
    fun isTracked(msgId: String): Boolean {
        val key = msgId.take(8)
        return _routes.value.any { it.msgId == key }
    }

    // ── Route tracking (source → prevHop → me → nextHop) ──────────────────

    /** Called at the origin when a message is first created locally. */
    fun recordAsOrigin(msgId: String, source: String, dest: String, me: String, channel: Int = 0) {
        upsertRoute(msgId) { existing ->
            (existing ?: newRecord(msgId, source, dest, me, channel)).copy(
                source = source.takeLast(8),
                destination = normaliseDest(dest),
                prevHop = null,
                me = me.takeLast(8),
                channel = channel,
            )
        }
    }

    /**
     * Called when a message arrives at this node (as final recipient or as a carrier). Records the
     * peer we received it from. `delivered=true` means this node IS the destination.
     */
    fun recordIncoming(
        msgId: String, source: String, dest: String, prevHop: String, me: String, delivered: Boolean,
        channel: Int = 0, path: String = "", senderStrategy: String = "",
    ) {
        upsertRoute(msgId) { existing ->
            val base = existing ?: newRecord(msgId, source, dest, me, channel)
            val now = System.currentTimeMillis()
            base.copy(
                source = source.takeLast(8),
                destination = normaliseDest(dest),
                prevHop = prevHop.takeLast(8),
                me = me.takeLast(8),
                delivered = base.delivered || delivered,
                deliveredAtMs = if (!base.delivered && delivered) now else base.deliveredAtMs,
                channel = if (channel != 0) channel else base.channel,
                lastPath = path.ifBlank { base.lastPath },
                senderStrategy = senderStrategy.ifBlank { base.senderStrategy },
            )
        }
    }

    /**
     * Called when we forward or directly deliver a message to a next hop. `delivered=true` means
     * we handed it straight to its final destination over a direct link.
     */
    fun recordOutgoing(
        msgId: String, source: String, dest: String, me: String, nextHop: String, delivered: Boolean,
        strategy: String = "", path: String = "", channel: Int = 0, decision: String = "",
    ) {
        upsertRoute(msgId) { existing ->
            val base = existing ?: newRecord(msgId, source, dest, me, channel)
            // Keep the observed path STABLE: once delivered the next hop is final; before that the
            // FIRST recorded forward wins so periodic re-sends can't keep rewriting the display.
            // A call that flips the route to delivered records the delivering hop.
            val stableNext = when {
                base.delivered -> base.nextHop
                delivered -> nextHop.takeLast(8)
                base.nextHop != null -> base.nextHop
                else -> nextHop.takeLast(8)
            }
            val now = System.currentTimeMillis()
            // The decision follows the same rule as the next hop, so it always explains the hop shown.
            val keepDecision = base.delivered || (!delivered && base.decision.isNotBlank()) || decision.isBlank()
            base.copy(
                source = source.takeLast(8),
                destination = normaliseDest(dest),
                me = me.takeLast(8),
                nextHop = stableNext,
                delivered = base.delivered || delivered,
                deliveredAtMs = if (!base.delivered && delivered) now else base.deliveredAtMs,
                // Freeze the labels once delivered so later re-sends can't flip them.
                strategy = if (base.delivered) base.strategy else strategy.ifBlank { base.strategy },
                lastPath = if (base.delivered) base.lastPath else path.ifBlank { base.lastPath },
                channel = if (channel != 0) channel else base.channel,
                decision = if (keepDecision) base.decision else decision,
                decisionAtMs = if (keepDecision) base.decisionAtMs else now,
            )
        }
    }

    /**
     * Mark a tracked route delivered (an end-to-end receipt arrived, or delivery was confirmed some
     * other way). Never fabricates a record for a message we never tracked.
     */
    fun markRouteDelivered(msgId: String, via: String = "", confirmedBy: String? = null) {
        updateExisting(msgId) {
            if (it.delivered) return@updateExisting null
            // Relay publishes aren't recorded as hand-offs, so a DM delivered over the relay has no
            // link yet: take it from the receipt, and name the destination when it confirmed itself.
            val fromDest = confirmedBy != null && confirmedBy.takeLast(8) == it.destination
            it.copy(
                delivered = true,
                deliveredAtMs = System.currentTimeMillis(),
                lastPath = it.lastPath.ifBlank { via },
                nextHop = it.nextHop ?: if (fromDest) it.destination else null,
            )
        }
    }

    /**
     * Our broadcast / group message was published to every relay subscriber at once — a hand-off
     * with no single next hop, so only the link is recorded.
     */
    fun recordRelayPublish(msgId: String, path: String) {
        updateExisting(msgId) {
            if (it.lastPath == path) null else it.copy(lastPath = path, strategy = it.strategy.ifBlank { "INTERNET RELAY" })
        }
    }

    /** Mark a tracked route as having left the buffer without delivery ("EXPIRED" / "DROPPED"). */
    fun markRouteOutcome(msgId: String, outcome: String) {
        updateExisting(msgId) { if (it.delivered || it.outcome == outcome) null else it.copy(outcome = outcome) }
    }

    private fun updateExisting(msgId: String, change: (RouteRecord) -> RouteRecord?) {
        val key = msgId.take(8)
        var changed: RouteRecord? = null
        synchronized(lock) {
            val current = _routes.value.toMutableList()
            val idx = current.indexOfFirst { it.msgId == key }
            if (idx >= 0) {
                val updated = change(current[idx])?.copy(timestampMs = System.currentTimeMillis())
                if (updated != null) {
                    current[idx] = updated
                    _routes.value = current
                    changed = updated
                }
            }
        }
        changed?.let { persist(it) }
    }

    private fun upsertRoute(msgId: String, mutate: (RouteRecord?) -> RouteRecord) {
        val key = msgId.take(8)
        val updated: RouteRecord
        // Entire read-modify-write must be atomic (see [lock]).
        synchronized(lock) {
            val current = _routes.value.toMutableList()
            val idx = current.indexOfFirst { it.msgId == key }
            updated = mutate(if (idx >= 0) current[idx] else null).copy(
                timestampMs = System.currentTimeMillis(),
                fullId = if (msgId.length > 8) msgId else (if (idx >= 0) current[idx].fullId else ""),
            )
            if (idx >= 0) {
                current[idx] = updated
                _routes.value = current
            } else {
                _routes.value = (listOf(updated) + current).take(MAX_ROUTES)
            }
        }
        persist(updated)
    }

    private fun newRecord(msgId: String, source: String, dest: String, me: String, channel: Int) = RouteRecord(
        msgId = msgId.take(8),
        source = source.takeLast(8),
        destination = normaliseDest(dest),
        me = me.takeLast(8),
        fullId = msgId,
        channel = channel,
    )

    private fun normaliseDest(dest: String): String = when {
        dest.isBlank() -> ""
        dest == "^all" || dest.equals("!ffffffff", true) || dest == "BROADCAST" -> "BROADCAST"
        else -> dest.takeLast(8)
    }
}

enum class LifecycleEvent {
    CREATED,
    BUFFERED,
    DUPLICATE,
    FORWARDED,
    IGNORED,
    ACK_GENERATED,
    ACK_RECEIVED,
    BUFFER_CLEARED,
    DELIVERED,
    EXPIRED,
    HOP_LIMIT,

    /** Removed from the buffer without delivery (low reachability / buffer pressure / manual clear). */
    DROPPED,
}

data class LifecycleEntry(
    /** Display time (e.g. "Oct 22 17:10:59"). */
    val timestamp: String,
    val event: LifecycleEvent,
    val msgId: String,
    val origin: String,
    val dest: String,
    val hopCount: Int,
    val forwardCount: Int,
    val ttlRemainingMin: Int,
    val extra: String,
    val epochMs: Long = System.currentTimeMillis(),
) {
    fun toLogLine(): String = buildString {
        append("[$timestamp] ${event.name.padEnd(14)} msg=$msgId")
        if (origin.isNotEmpty()) append(" from=$origin")
        if (dest.isNotEmpty()) append(" to=$dest")
        if (hopCount >= 0) append(" hop=$hopCount")
        if (forwardCount >= 0) append(" fwd=$forwardCount")
        if (ttlRemainingMin >= 0) append(" ttl=${ttlRemainingMin}m")
        if (extra.isNotEmpty()) append(" [$extra]")
    }
}
