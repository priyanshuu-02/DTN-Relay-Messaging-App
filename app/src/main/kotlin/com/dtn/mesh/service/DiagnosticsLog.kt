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
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Severity of a diagnostics line. */
enum class DiagLevel { DEBUG, INFO, WARN, ERROR }

/** Where a diagnostics line comes from (the Console's source filter). Plain strings, so new ones are cheap. */
object DiagSource {
    const val SYSTEM = "system"
    const val UI = "ui"
    const val ENGINE = "engine"
    const val BLE = "ble"
    const val WIFID = "wifid"
    const val HUB = "hub"
    const val RELAY = "relay"
    const val ROUTING = "routing"
    const val BUFFER = "buffer"
    const val RECEIPTS = "receipts"
    const val CRYPTO = "crypto"
    const val PRESENCE = "presence"
    const val QLEARN = "qlearn"
    const val TIMING = "timing"
    const val DB = "db"
}

/** One diagnostics line. Never holds message text or key material: ids, sizes and counts only. */
data class DiagLine(val atMs: Long, val level: DiagLevel, val source: String, val text: String)

/** How many lines of each level are in memory right now. */
data class LevelCounts(val debug: Int = 0, val info: Int = 0, val warn: Int = 0, val error: Int = 0) {
    val total: Int get() = debug + info + warn + error
}

/**
 * The on-disk line format: one line per entry, tab-separated and human-readable, e.g.
 * `2026-10-14 21:04:28.123 +0530<TAB>WARN<TAB>ble<TAB>Scan failed: error 2`.
 */
object DiagnosticsFormat {
    private val fileTime = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US) }
    private val shortTime = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }

    /** Tabs and line breaks would split a line on disk. */
    fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun encode(line: DiagLine): String =
        "${fileTime.get()!!.format(Date(line.atMs))}\t${line.level.name}\t${clean(line.source)}\t${clean(line.text)}"

    /** Parses [encode]'s output; null for anything else (a torn last line, an older format). */
    fun decode(raw: String): DiagLine? {
        val parts = raw.split('\t', limit = 4)
        if (parts.size < 4) return null
        val at = runCatching { fileTime.get()!!.parse(parts[0])?.time }.getOrNull() ?: return null
        val level = DiagLevel.entries.firstOrNull { it.name == parts[1] } ?: return null
        return DiagLine(at, level, parts[2], parts[3])
    }

    /** Short on-screen form: `[21:04:28] WARN ble · Scan failed: error 2`. */
    fun display(line: DiagLine): String =
        "[${shortTime.get()!!.format(Date(line.atMs))}] ${line.level.name} ${line.source} · ${line.text}"
}

/**
 * Fixed-size in-memory ring of the newest lines, with per-level counts that follow evictions.
 * Not thread-safe on its own: [DiagnosticsLog] serialises access.
 */
class DiagnosticsRing(val capacity: Int) {
    init { require(capacity > 0) { "capacity must be positive" } }

    private val slots = arrayOfNulls<DiagLine>(capacity)
    private var head = 0 // index of the oldest line
    private val perLevel = IntArray(DiagLevel.entries.size)

    var size: Int = 0
        private set

    fun add(line: DiagLine) {
        if (size == capacity) {
            slots[head]?.let { perLevel[it.level.ordinal]-- }
            slots[head] = line
            head = (head + 1) % capacity
        } else {
            slots[(head + size) % capacity] = line
            size++
        }
        perLevel[line.level.ordinal]++
    }

    /** Puts lines that are OLDER than everything held (e.g. loaded from the file) in front, oldest first. */
    fun addOlder(older: List<DiagLine>) {
        if (older.isEmpty()) return
        val newer = oldestFirst()
        clear()
        val keep = (older + newer).let { if (it.size > capacity) it.subList(it.size - capacity, it.size) else it }
        keep.forEach(::add)
    }

    fun oldestFirst(): List<DiagLine> = List(size) { slots[(head + it) % capacity]!! }

    fun newestFirst(): List<DiagLine> = List(size) { slots[(head + size - 1 - it) % capacity]!! }

    fun counts(): LevelCounts = LevelCounts(
        debug = perLevel[DiagLevel.DEBUG.ordinal],
        info = perLevel[DiagLevel.INFO.ordinal],
        warn = perLevel[DiagLevel.WARN.ordinal],
        error = perLevel[DiagLevel.ERROR.ordinal],
    )

    fun clear() {
        slots.fill(null)
        head = 0
        size = 0
        perLevel.fill(0)
    }
}

/**
 * Append-only log file that rotates at [maxBytes]: the full file becomes `<name>.1` (replacing
 * any older backup) and a fresh file starts. At most about 2 × [maxBytes] on disk. Single writer.
 */
class RotatingLogFile(val file: File, private val maxBytes: Long) {
    val backup: File get() = File(file.path + ".1")

    /** Bytes in [file]; read from disk on first use, so constructing this does no I/O. */
    private var size: Long = -1L

    private fun currentSize(): Long {
        if (size < 0L) size = if (file.exists()) file.length() else 0L
        return size
    }

    /** Appends whole lines, rotating first when they would push the file past [maxBytes]. */
    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        val bytes = buildString { lines.forEach { append(it).append('\n') } }.toByteArray(Charsets.UTF_8)
        val before = currentSize()
        if (before > 0 && before + bytes.size > maxBytes) rotate()
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { it.write(bytes) }
        size = currentSize() + bytes.size
    }

    fun rotate() {
        if (backup.exists()) backup.delete()
        if (file.exists() && !file.renameTo(backup)) {
            file.copyTo(backup, overwrite = true)
            file.delete()
        }
        size = 0L
    }

    /** The last [maxLines] lines across the backup and the current file, oldest first. */
    fun tail(maxLines: Int): List<String> {
        val current = if (file.exists()) file.readLines() else emptyList()
        if (current.size >= maxLines) return current.takeLast(maxLines)
        val older = if (backup.exists()) backup.readLines() else emptyList()
        return (older + current).takeLast(maxLines)
    }

    /** Files that exist, oldest first (for "Export all logs"). */
    fun files(): List<File> = listOf(backup, file).filter { it.exists() }
}

/** Why a flush pass did not hand a buffered bundle to a peer. */
enum class HoldReason(val label: String) {
    ALGORITHM("algorithm said hold"),
    HUB_SESSION("already sent this hub session"),
    RECENTLY_SENT("sent under 30 s ago"),
    REACHABLE_ELSEWHERE("destination reachable another way"),
    CAME_FROM_PEER("came from that peer"),
    PEER_IS_ORIGIN("that peer sent it"),
    NO_RELAY_CUSTODY("relay peers carry no one else's DMs"),
    NOT_FOR_HUB_PHONE("not addressed to this hub-only phone"),
    BROADCAST_DONE("broadcast already sent this session"),
    CARRIER_HAS_IT("carrier already got it this session"),
    SEND_FAILED("link failed, rest of the pass skipped"),
}

/** What one flush pass did for one peer: bundles offered, sent, cleared, and held per reason. */
class PeerFlushSummary {
    var offered = 0
    var sent = 0
    /** Cleared from the buffer instead (a receipt already confirmed delivery). */
    var cleared = 0
    private val held = EnumMap<HoldReason, Int>(HoldReason::class.java)

    fun hold(reason: HoldReason, count: Int = 1) {
        if (count > 0) held[reason] = (held[reason] ?: 0) + count
    }

    fun heldCount(reason: HoldReason): Int = held[reason] ?: 0

    /** Stable key: equal summaries ⇒ equal signatures (EnumMap iterates in declaration order). */
    fun signature(): String =
        "o$offered s$sent c$cleared " + held.entries.joinToString(",") { "${it.key.name}=${it.value}" }

    fun describe(peer8: String): String = buildString {
        append("flush → ").append(peer8).append(": offered ").append(offered).append(" · sent ").append(sent)
        val total = held.values.sum()
        if (total > 0) {
            append(" · held ").append(total).append(" (")
            append(held.entries.joinToString(", ") { "${it.key.label} ×${it.value}" })
            append(")")
        }
        if (cleared > 0) append(" · cleared ").append(cleared).append(" (receipt already in)")
    }
}

/**
 * Turns per-pass [PeerFlushSummary]s into log lines only when a peer's summary changes, so the
 * 4 s flush can't flood the log with the same "held because…" line.
 */
class HeldReasonTracker {
    private val last = ConcurrentHashMap<String, String>()

    /** The line to log for [peer], or null when it matches the last one logged for that peer. */
    fun lineFor(peer: String, summary: PeerFlushSummary): String? {
        val sig = summary.signature()
        val previous = last.put(peer, sig)
        return if (previous == sig) null else summary.describe(peer.takeLast(8))
    }

    /** The peer went away: its next pass logs again. */
    fun forget(peer: String) {
        last.remove(peer)
    }
}

/**
 * App-wide diagnostics log. Every line has a time, a level and a source. The newest
 * [CAPACITY] lines stay in memory ([lines], newest first); everything goes to
 * `filesDir/[FILE_NAME]`, rotating at [MAX_FILE_BYTES] with one `.1` backup. File I/O runs on one
 * background writer; the tail of the file is loaded back on start, so the log survives restarts.
 *
 * Never log message text or key material: ids, sizes and counts only.
 */
@Singleton
class DiagnosticsLog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "Diagnostics"
        const val FILE_NAME = "diagnostics_v1.log"
        const val CAPACITY = 2_000
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }

    private val ring = DiagnosticsRing(CAPACITY)
    /** Created on the writer's IO thread: `filesDir` may touch the disk. */
    private val file by lazy { RotatingLogFile(File(context.filesDir, FILE_NAME), MAX_FILE_BYTES) }
    private val writes = Channel<String>(capacity = Channel.UNLIMITED)
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _lines = MutableStateFlow<List<DiagLine>>(emptyList())
    /** In-memory lines, newest first. */
    val lines: StateFlow<List<DiagLine>> = _lines.asStateFlow()

    private val _counts = MutableStateFlow(LevelCounts())
    /** Per-level counts of [lines]. */
    val counts: StateFlow<LevelCounts> = _counts.asStateFlow()

    init {
        io.launch {
            loadTail()
            for (first in writes) {
                val batch = ArrayList<String>().apply { add(first) }
                while (true) batch += writes.tryReceive().getOrNull() ?: break
                runCatching { file.append(batch) }.onFailure { Log.w(TAG, "diagnostics write failed", it) }
            }
        }
    }

    fun log(level: DiagLevel, source: String, text: String) {
        val line = DiagLine(System.currentTimeMillis(), level, source, DiagnosticsFormat.clean(text))
        synchronized(ring) {
            ring.add(line)
            publishLocked()
            writes.trySend(DiagnosticsFormat.encode(line))
        }
    }

    fun d(source: String, text: String) = log(DiagLevel.DEBUG, source, text)
    fun i(source: String, text: String) = log(DiagLevel.INFO, source, text)
    fun w(source: String, text: String) = log(DiagLevel.WARN, source, text)
    fun e(source: String, text: String) = log(DiagLevel.ERROR, source, text)

    /** The diagnostics files on disk, oldest first (for "Export all logs"). */
    fun files(): List<File> = file.files()

    private fun loadTail() {
        val loaded = runCatching { file.tail(CAPACITY).mapNotNull(DiagnosticsFormat::decode) }
            .onFailure { Log.w(TAG, "diagnostics load failed", it) }
            .getOrDefault(emptyList())
        if (loaded.isEmpty()) return
        synchronized(ring) {
            ring.addOlder(loaded)
            publishLocked()
        }
    }

    private fun publishLocked() {
        _lines.value = ring.newestFirst()
        _counts.value = ring.counts()
    }
}
