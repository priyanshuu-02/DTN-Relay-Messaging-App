package com.dtn.mesh.ui.devtools

import com.dtn.mesh.learning.BenchmarkStat
import com.dtn.mesh.service.DiagLevel
import com.dtn.mesh.service.DiagLine
import com.dtn.mesh.service.DiagSource
import com.dtn.mesh.service.DiagnosticsFormat
import com.dtn.mesh.service.LifecycleEntry
import com.dtn.mesh.service.LifecycleEvent
import com.dtn.mesh.service.MessageLifecycleLog.RouteRecord
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.queue.BufferHistoryItem
import com.dtn.mesh.ui.queue.MessageTrace
import com.dtn.mesh.ui.queue.QueueText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The Developer Tools pages' pure logic (JVM unit-tested): the six routing algorithms, the Event
 * Log's chips / search / colours / export, the Console's level + source + search filter, the
 * Benchmarks table, the Buffer History chips, the Routing Engine summary and its route labels.
 */

/** One routing algorithm: its [StrategySelector] type, the name its benchmark row uses, labels and a one-line description. */
internal data class AlgorithmChoice(
    val type: String,
    val statName: String,
    val label: String,
    val short: String,
    val description: String,
)

internal object Algorithms {
    val all = listOf(
        AlgorithmChoice("PROPHET", "PROPHET", "PRoPHET", "PRoPHET", "Encounter probability"),
        AlgorithmChoice("STABLE", "STABLE-PROPHET", "Stable PRoPHET", "Stable", "PRoPHET + link quality"),
        AlgorithmChoice("MAXPROP", "MAXPROP", "MaxProp", "MaxProp", "Cheapest meeting path"),
        AlgorithmChoice("DIMAXPROP", "DIMAXPROP", "DiMaxProp", "DiMax", "MaxProp + distance λ"),
        AlgorithmChoice("EPIDEMIC", "EPIDEMIC", "Epidemic", "Epidemic", "A copy to every phone"),
        AlgorithmChoice("QLEARNING", "Q-LEARNING", "Q-learning", "Q-learn", "Learns forward / wait"),
    )

    private fun key(name: String) = name.uppercase().replace("-", "").replace("_", "").replace(" ", "")

    /** By selector type ("QLEARNING") or benchmark / strategy name ("Q-LEARNING", "STABLE-PROPHET"). */
    fun find(name: String): AlgorithmChoice? = key(name).let { k ->
        all.firstOrNull { key(it.type) == k || key(it.statName) == k }
    }

    fun label(name: String): String = find(name)?.label ?: name

    /** What the Routing Engine's score column holds for this algorithm. */
    fun scoreCaption(name: String): String = when (find(name)?.type) {
        "PROPHET", "STABLE" -> "P = delivery predictability toward that peer"
        "MAXPROP" -> "f = meeting likelihood"
        "DIMAXPROP" -> "w = hop cost (1 − f) + λ·d, lower is better"
        "EPIDEMIC" -> "Epidemic copies to every peer: no score"
        "QLEARNING" -> "Q = forward / wait; it forwards when the first is higher"
        else -> "The selected algorithm's score"
    }
}

/** The Event Log's chips. */
internal enum class LifecycleFilter(val label: String) {
    ALL("All"), FLOW("Flow"), DELIVERY("Delivery"), ACK("ACK"), ISSUES("Issues"),
}

/** Journal events matching [filter] (newest first, like [entries]). */
internal fun filterLifecycle(entries: List<LifecycleEntry>, filter: LifecycleFilter): List<LifecycleEntry> =
    entries.filter { entry ->
        when (filter) {
            LifecycleFilter.ALL -> true
            LifecycleFilter.FLOW -> entry.event in setOf(
                LifecycleEvent.CREATED, LifecycleEvent.BUFFERED, LifecycleEvent.FORWARDED, LifecycleEvent.BUFFER_CLEARED,
            )
            LifecycleFilter.DELIVERY -> entry.event == LifecycleEvent.DELIVERED
            LifecycleFilter.ACK -> entry.event in setOf(LifecycleEvent.ACK_GENERATED, LifecycleEvent.ACK_RECEIVED)
            LifecycleFilter.ISSUES -> entry.event in setOf(
                LifecycleEvent.DUPLICATE, LifecycleEvent.IGNORED, LifecycleEvent.EXPIRED, LifecycleEvent.HOP_LIMIT,
                LifecycleEvent.DROPPED,
            )
        }
    }

/** Developer Tools › Event Log: search, colours, the card texts and Export events. */
internal object EventLogModel {

    /** [entries] (newest first) matching the chip [filter] and the [query]. */
    fun visible(entries: List<LifecycleEntry>, filter: LifecycleFilter, query: String): List<LifecycleEntry> =
        filterLifecycle(entries, filter).filter { matches(it, query) }

    /**
     * The search: a message id, a node id (with or without "!"), an event ("forwarded", "ack") or
     * any word of the detail ("BLE", "hub"). Case-insensitive; blank matches everything.
     */
    fun matches(e: LifecycleEntry, query: String): Boolean {
        val q = query.trim().removePrefix("!").lowercase()
        if (q.isEmpty()) return true
        return listOf(e.msgId, e.origin, e.dest, e.event.name, MessageTrace.title(e.event), e.extra)
            .any { it.lowercase().contains(q) }
    }

    /** Stripe colour: blue created, orange buffered / handed on, green delivered / receipts / cleared, red problems, grey repeats. */
    fun tone(e: LifecycleEntry): Tone = when (e.event) {
        LifecycleEvent.CREATED -> Tone.BLUE
        LifecycleEvent.BUFFERED -> Tone.ORANGE
        LifecycleEvent.FORWARDED -> if (e.extra.contains("✓ delivered")) Tone.GREEN else Tone.ORANGE
        LifecycleEvent.DELIVERED, LifecycleEvent.ACK_GENERATED, LifecycleEvent.ACK_RECEIVED, LifecycleEvent.BUFFER_CLEARED -> Tone.GREEN
        LifecycleEvent.DUPLICATE -> Tone.GREY
        LifecycleEvent.IGNORED, LifecycleEvent.EXPIRED, LifecycleEvent.HOP_LIMIT, LifecycleEvent.DROPPED -> Tone.RED
    }

    /** "FROM d10ec32d → TO 97ef4bf4" (the ends the journal recorded), null when it recorded neither. */
    fun endpoints(e: LifecycleEntry): String? = when {
        e.origin.isNotEmpty() && e.dest.isNotEmpty() -> "FROM ${e.origin} → TO ${e.dest}"
        e.origin.isNotEmpty() -> "FROM ${e.origin}"
        e.dest.isNotEmpty() -> "TO ${e.dest}"
        else -> null
    }

    /** The grey line: the journal's detail, then hops / forwards / TTL. */
    fun detail(e: LifecycleEntry): String {
        val metrics = listOfNotNull(
            e.hopCount.takeIf { it >= 0 }?.let { "Hops $it" },
            e.forwardCount.takeIf { it >= 0 }?.let { "Fwd $it" },
            e.ttlRemainingMin.takeIf { it >= 0 }?.let { "TTL ${it}m" },
        ).joinToString(" · ")
        return listOf(e.extra, metrics).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** Export events: a header, then [shown] (newest first on screen) one per line, oldest first. Never message text. */
    fun exportText(shown: List<LifecycleEntry>, filter: LifecycleFilter, query: String, exportedAt: String): String = buildString {
        appendLine("DTN Mesh Relay · Event Log")
        appendLine("Exported $exportedAt")
        appendLine("Filter: ${filter.label}" + (if (query.isNotBlank()) " · search \"${query.trim()}\"" else "") +
            " · ${shown.size} event${if (shown.size == 1) "" else "s"}, oldest first")
        appendLine("Ids, links, hops and times only — no message text.")
        appendLine()
        shown.asReversed().forEach { appendLine(it.toLogLine()) }
    }
}

/** The Console's level segments. */
internal enum class ConsoleLevel(val label: String) { ALL("All"), WARNINGS("Warnings"), ERRORS("Errors") }

/** How many lines each level segment shows. */
internal data class ConsoleCounts(val all: Int = 0, val warnings: Int = 0, val errors: Int = 0) {
    fun of(level: ConsoleLevel): Int = when (level) {
        ConsoleLevel.ALL -> all
        ConsoleLevel.WARNINGS -> warnings
        ConsoleLevel.ERRORS -> errors
    }
}

/**
 * Developer Tools › Console: the diagnostics log through the level segments, the source chips
 * (none picked = every source), the search and Clear view (hides what's older on screen only).
 */
internal object ConsoleModel {
    /** At most this many lines go to the clipboard (the newest), so a copy can't overflow it. */
    const val COPY_MAX = 1_000

    /** Source chip order; sources not listed here follow, alphabetically. */
    val SOURCE_ORDER = listOf(
        DiagSource.SYSTEM, DiagSource.UI, DiagSource.BLE, DiagSource.WIFID, DiagSource.HUB, DiagSource.RELAY,
        DiagSource.ROUTING, DiagSource.BUFFER, DiagSource.RECEIPTS, DiagSource.CRYPTO, DiagSource.PRESENCE,
        DiagSource.QLEARN, DiagSource.TIMING, DiagSource.ENGINE, DiagSource.DB,
    )

    fun sourceLabel(source: String): String = when (source) {
        DiagSource.SYSTEM -> "System"
        DiagSource.UI -> "App"
        DiagSource.BLE -> "Bluetooth"
        DiagSource.WIFID -> "Wi-Fi Direct"
        DiagSource.HUB -> "LoRa hub"
        DiagSource.RELAY -> "Relay"
        DiagSource.ROUTING -> "Routing"
        DiagSource.BUFFER -> "Buffer"
        DiagSource.RECEIPTS -> "Receipts"
        DiagSource.CRYPTO -> "Crypto"
        DiagSource.PRESENCE -> "Presence"
        DiagSource.QLEARN -> "Q-learning"
        DiagSource.TIMING -> "Timing"
        DiagSource.ENGINE -> "Engine"
        DiagSource.DB -> "Database"
        else -> source
    }

    /** The sources that occur in [lines], in chip order. */
    fun sourcesIn(lines: List<DiagLine>): List<String> {
        val present = lines.mapTo(HashSet()) { it.source }
        return SOURCE_ORDER.filter { it in present } + (present - SOURCE_ORDER.toSet()).sorted()
    }

    fun matchesLevel(line: DiagLine, level: ConsoleLevel): Boolean = when (level) {
        ConsoleLevel.ALL -> true
        ConsoleLevel.WARNINGS -> line.level == DiagLevel.WARN
        ConsoleLevel.ERRORS -> line.level == DiagLevel.ERROR
    }

    /** Matches the text, the source or its label; blank matches everything. */
    fun matchesSearch(line: DiagLine, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return line.text.contains(q, ignoreCase = true) || line.source.contains(q, ignoreCase = true) ||
            sourceLabel(line.source).contains(q, ignoreCase = true)
    }

    /** Lines newer than [clearedAtMs], from one of [sources] (empty = all) and matching [query]: before the level split. */
    fun inView(lines: List<DiagLine>, sources: Set<String>, query: String, clearedAtMs: Long): List<DiagLine> =
        lines.filter { it.atMs > clearedAtMs && (sources.isEmpty() || it.source in sources) && matchesSearch(it, query) }

    /** What the terminal shows (same order as [lines], newest first). */
    fun visible(lines: List<DiagLine>, level: ConsoleLevel, sources: Set<String>, query: String, clearedAtMs: Long): List<DiagLine> =
        inView(lines, sources, query, clearedAtMs).filter { matchesLevel(it, level) }

    /** Each segment's count under the current source chips, search and Clear view. */
    fun counts(lines: List<DiagLine>, sources: Set<String>, query: String, clearedAtMs: Long): ConsoleCounts {
        val view = inView(lines, sources, query, clearedAtMs)
        return ConsoleCounts(
            all = view.size,
            warnings = view.count { it.level == DiagLevel.WARN },
            errors = view.count { it.level == DiagLevel.ERROR },
        )
    }

    /** Copy output: the shown lines ([shown] is newest first) oldest first, in the on-screen form, at most [max]. */
    fun copyText(shown: List<DiagLine>, max: Int = COPY_MAX): String =
        shown.take(max).asReversed().joinToString("\n") { DiagnosticsFormat.display(it) }

    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    /** "21:04:28", the terminal's time column. */
    fun time(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(atMs).atZone(zone).format(clock)
}

/** Developer Tools › Benchmarks: the table's order and cells. */
internal object BenchmarkModel {
    /** Below this many messages handled an algorithm's rate isn't meaningful yet. */
    const val MIN_HANDLED = 10

    /** Best delivery rate first (rows without a rate last), then the most deliveries, then the name. */
    fun sorted(stats: List<BenchmarkStat>): List<BenchmarkStat> = stats.sortedWith(
        compareByDescending<BenchmarkStat> { it.deliveryRate ?: -1.0 }
            .thenByDescending { it.deliveries }
            .thenBy { it.strategy },
    )

    fun rate(s: BenchmarkStat): String = s.deliveryRate?.let { "${(it * 100).roundToInt()}%" } ?: "—"

    /** "850 ms", "4.2 s", "3.5 min", "1.2 h"; "—" before the first delivery. */
    fun delay(s: BenchmarkStat): String {
        if (s.latencySamples <= 0) return "—"
        val ms = s.avgLatencyMs
        return when {
            ms < 1_000 -> "${ms.roundToInt()} ms"
            ms < 60_000 -> "%.1f s".format(ms / 1_000)
            ms < 3_600_000 -> "%.1f min".format(ms / 60_000)
            else -> "%.1f h".format(ms / 3_600_000)
        }
    }

    fun hops(s: BenchmarkStat): String = if (s.hopSamples > 0) "%.1f".format(s.avgHops) else "—"

    fun overhead(s: BenchmarkStat): String = s.overhead?.let { "%.1f".format(it) } ?: "—"

    /** The algorithms (labels) that handled fewer than [MIN_HANDLED] messages. */
    fun needMoreData(stats: List<BenchmarkStat>): List<String> =
        stats.filter { it.handled < MIN_HANDLED }.map { Algorithms.label(it.strategy) }
}

/** Buffer History's chips: how a store-and-forward record left the buffer. */
internal enum class HistoryFilter(val label: String) {
    ALL("All"), DELIVERED("Delivered"), EXPIRED("Expired"), DROPPED("Dropped");

    fun matches(item: BufferHistoryItem, route: RouteRecord?): Boolean = when (this) {
        ALL -> true
        DELIVERED -> QueueText.historyOutcome(item, route) == QueueText.HistoryOutcome.DELIVERED
        EXPIRED -> QueueText.historyOutcome(item, route) == QueueText.HistoryOutcome.EXPIRED
        DROPPED -> QueueText.historyOutcome(item, route).let {
            it == QueueText.HistoryOutcome.DROPPED || it == QueueText.HistoryOutcome.HOP_LIMIT
        }
    }

    companion object {
        /** The [records] the chip [filter] shows ([routes] by 8-character id fill in when no event said how one left). */
        fun select(records: List<BufferHistoryItem>, routes: Map<String, RouteRecord>, filter: HistoryFilter): List<BufferHistoryItem> =
            records.filter { filter.matches(it, routes[it.msgId]) }
    }
}

/** The Routing Engine's summary tiles, from this phone's route observations. */
internal data class EngineSummary(
    val activePeers: Int,
    val knownPeers: Int,
    /** Our own messages delivered. */
    val sentDelivered: Int,
    /** Other people's messages this phone carried that were delivered. */
    val relayedDelivered: Int,
    /** Messages delivered to this phone. */
    val received: Int,
    val resolved: Int,
    val inTransit: Int,
) {
    companion object {
        fun of(routes: List<RouteRecord>, peers: List<PeerInfo>, myId8: String?): EngineSummary {
            val resolved = routes.count { it.delivered }
            return EngineSummary(
                activePeers = peers.count { it.reachable },
                knownPeers = peers.size,
                sentDelivered = routes.count { it.delivered && it.destination != myId8 && it.source == myId8 },
                relayedDelivered = routes.count { it.delivered && it.destination != myId8 && it.source != myId8 },
                received = routes.count { it.delivered && it.destination == myId8 },
                resolved = resolved,
                inTransit = routes.size - resolved,
            )
        }

        /** How a peer is reachable right now, for its row in the score table. */
        fun reach(peer: PeerInfo): String = when {
            peer.isOnline && peer.hubOnly -> "Active now · via LoRa hub"
            peer.isOnline -> "Active now · nearby"
            peer.internetOnline -> "Online via the internet relay"
            peer.viaOtherHub -> "Active now · via LoRa (${peer.meshHubLabel})"
            else -> "Not reachable now"
        }
    }
}

/** Engine-console lines that are calculations (shown in blue). */
internal fun isCalculationLine(line: String): Boolean =
    line.contains("P(me") || line.contains("f(me") || line.contains("C(me") || line.contains("EPIDEMIC") || line.contains("Q(")

/**
 * Map a truncated (last-8) node id — as stored on route/lifecycle records — back to a
 * peer's saved nickname / long name for display. Returns null when there's no match so
 * callers can fall back to the raw id.
 */
internal fun peerLabelFor(idSuffix: String?, peers: List<PeerInfo>, myId8: String? = null): String? {
    if (idSuffix.isNullOrBlank()) return null
    // Every broadcast form ("BROADCAST", "^all", "!ffffffff", its last-8 "ffffffff") reads the same.
    if (UiText.isBroadcastId(idSuffix)) return "Broadcast"
    if (myId8 != null && idSuffix == myId8) return "YOU"
    val match = peers.firstOrNull { it.nodeId.takeLast(8) == idSuffix } ?: return null
    val label = match.displayName()
    // displayName() falls back to the last-8 id; if that's all we have it isn't a real
    // nickname, so treat it as "no friendly name".
    return if (label == idSuffix) null else label
}

/**
 * Render this node's observed path for a route, using friendly labels and marking the
 * origin→previous-hop segment with an ellipsis (⋯) because those intermediate hops are NOT
 * observed from here — we only directly know who we received from, who we are, and who we
 * forwarded to. Segments we actually saw use a solid arrow (→).
 *
 * Examples:  `vrishank → YOU`            (received directly from the origin)
 *            `vrishank ⋯ priya → YOU`    (originated at vrishank, reached us via priya)
 *            `YOU → priya`               (we forwarded onward)
 */
internal fun formatObservedPath(r: RouteRecord, peers: List<PeerInfo>, myId8: String?): String {
    fun lbl(id: String?): String = when {
        id.isNullOrBlank() -> "?"
        id == myId8 || id == r.me -> "YOU"
        else -> peerLabelFor(id, peers, myId8) ?: id
    }
    val sb = StringBuilder(lbl(r.source))
    val prev = r.prevHop
    if (!prev.isNullOrBlank() && prev != r.source && prev != r.me) {
        sb.append("  ⋯  ").append(lbl(prev)) // unobserved hops between origin and the peer we got it from
    }
    if (r.me.isNotBlank() && r.me != r.source) sb.append("  →  ").append("YOU")
    val next = r.nextHop
    if (!next.isNullOrBlank() && next != r.me) sb.append("  →  ").append(lbl(next))
    // Published to every relay subscriber at once: there's no single next hop to name.
    else if (r.isBroadcast && r.lastPath.isNotBlank()) sb.append("  →  everyone (").append(r.lastPath).append(")")
    return sb.toString()
}
