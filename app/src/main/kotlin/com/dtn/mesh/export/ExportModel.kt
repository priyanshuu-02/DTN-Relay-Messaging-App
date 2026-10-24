package com.dtn.mesh.export

import com.dtn.mesh.service.DiagnosticsLog
import com.dtn.mesh.service.LifecycleEntry
import com.dtn.mesh.service.MessageLifecycleLog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/*
 * Developer Tools › Export Data, the pure part (JVM unit-tested): what each switch writes, the
 * date range, the message-events CSV, export folder names, the history list and the zip that Share
 * sends. Nothing here ever holds message text: the CSVs carry ids, sizes, counts and times only.
 */

/** File names inside an export folder, and the folder / zip naming. */
object ExportFiles {
    /** Under the app's external files directory; `res/xml/file_paths.xml` shares it as "exports". */
    const val DIR = "exports"
    const val DIAGNOSTICS = DiagnosticsLog.FILE_NAME
    /** Only there once the diagnostics log has rotated. */
    const val DIAGNOSTICS_BACKUP = DiagnosticsLog.FILE_NAME + ".1"
    const val ENGINE_CONSOLE = "engine_console.txt"
    const val JOURNAL = MessageLifecycleLog.JOURNAL_FILE
    const val LOGCAT = "logcat.txt"
    /** Export all logs: diagnostics (+ backup), engine console, message journal, this app's logcat. */
    val LOG_FILES = listOf(DIAGNOSTICS, DIAGNOSTICS_BACKUP, ENGINE_CONSOLE, JOURNAL, LOGCAT)
    /** Folder suffix of an Export all logs folder. */
    const val LOGS_SUFFIX = "_logs"

    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    /** "20261024_213005", or "20261024_213005_logs" for Export all logs. */
    fun folderName(nowMs: Long, zone: ZoneId, logsOnly: Boolean): String =
        Instant.ofEpochMilli(nowMs).atZone(zone).format(stamp) + if (logsOnly) LOGS_SUFFIX else ""

    /** When an export folder was made, from its name ("20261024_213005…"); null for any other name. */
    fun createdAt(folderName: String, zone: ZoneId): Long? = runCatching {
        LocalDateTime.parse(folderName.take(15), stamp).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    /** The zip that Share sends for one export folder (written next to the folder). */
    fun zipName(folderName: String): String = "dtn-export-$folderName.zip"
}

/** One switch of "Choose data to export". [usesRange]: the date range filters it. */
enum class ExportDataset(val label: String, val files: List<String>, val usesRange: Boolean) {
    ENCOUNTERS("Encounters", listOf("encounters.csv"), usesRange = true),
    CONTACTS("Contacts", listOf("contacts.csv"), usesRange = false),
    DECISIONS("Forwarding decisions", listOf("decisions.csv"), usesRange = true),
    MESSAGES("Messages", listOf("messages.csv"), usesRange = true),
    Q_TABLES("Q-tables + metadata", listOf("q_tables.json", "metadata.json"), usesRange = false),
    MESSAGE_EVENTS("Message events", listOf("message_events.csv"), usesRange = true),
    ALL_LOGS("All logs", ExportFiles.LOG_FILES, usesRange = false),
}

/** The datasets switched on. */
data class ExportSelection(val datasets: Set<ExportDataset>) {
    val isEmpty: Boolean get() = datasets.isEmpty()

    /** In page order. */
    val ordered: List<ExportDataset> get() = ExportDataset.entries.filter { it in datasets }

    /** What an export of this selection writes, in page order (the diagnostics backup only when the log has rotated). */
    fun fileNames(): List<String> = ordered.flatMap { it.files }

    /** Whether the date range applies to anything selected. */
    val usesRange: Boolean get() = datasets.any { it.usesRange }

    fun with(dataset: ExportDataset, on: Boolean): ExportSelection =
        ExportSelection(if (on) datasets + dataset else datasets - dataset)

    companion object {
        /** The research data on, the logs off ("Export all logs" has its own button). */
        val DEFAULT = ExportSelection(ExportDataset.entries.toSet() - ExportDataset.ALL_LOGS)
        val LOGS_ONLY = ExportSelection(setOf(ExportDataset.ALL_LOGS))

        /** Saved form: dataset names joined by commas (unknown names are skipped). */
        fun encode(s: ExportSelection): String = s.ordered.joinToString(",") { it.name }

        fun decode(text: String): ExportSelection =
            ExportSelection(text.split(',').mapNotNull { n -> ExportDataset.entries.firstOrNull { it.name == n } }.toSet())
    }
}

/** An inclusive time range (epoch ms) for the datasets with timestamps. */
data class ExportRange(val fromMs: Long, val toMs: Long) {
    operator fun contains(ms: Long): Boolean = ms in fromMs..toMs

    companion object {
        fun allTime(nowMs: Long) = ExportRange(0L, nowMs)

        fun lastHours(hours: Int, nowMs: Long) = ExportRange((nowMs - hours * 3_600_000L).coerceAtLeast(0L), nowMs)

        /** Whole local days: [from] 00:00 to the end of [to] (swapped when reversed). */
        fun days(from: LocalDate, to: LocalDate, zone: ZoneId): ExportRange {
            val (first, last) = if (from <= to) from to to else to to from
            return ExportRange(
                fromMs = first.atStartOfDay(zone).toInstant().toEpochMilli(),
                toMs = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
            )
        }

        /** The day a Material date picker selected: it reports UTC midnight of that day. */
        fun pickerDay(utcMs: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(utcMs, 86_400_000L))

        /** The date picker's value for [day] (UTC midnight). */
        fun pickerMillis(day: LocalDate): Long = day.toEpochDay() * 86_400_000L
    }
}

/** The Date range chips. */
enum class RangePreset(val label: String) {
    ALL("All time"), DAY("Last 24 h"), WEEK("Last 7 days"), MONTH("Last 30 days"), CUSTOM("Custom"),
}

/** The page's date-range choice; resolved to an [ExportRange] when the export runs. */
data class RangeChoice(val preset: RangePreset = RangePreset.ALL, val fromDay: LocalDate? = null, val toDay: LocalDate? = null) {
    fun resolve(nowMs: Long, zone: ZoneId): ExportRange = when (preset) {
        RangePreset.ALL -> ExportRange.allTime(nowMs)
        RangePreset.DAY -> ExportRange.lastHours(24, nowMs)
        RangePreset.WEEK -> ExportRange.lastHours(24 * 7, nowMs)
        RangePreset.MONTH -> ExportRange.lastHours(24 * 30, nowMs)
        RangePreset.CUSTOM -> {
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            ExportRange.days(fromDay ?: toDay ?: today, toDay ?: today, zone)
        }
    }

    companion object {
        /** Saved form: "PRESET,fromEpochDay,toEpochDay" (empty = not set). */
        fun encode(c: RangeChoice): String = listOf(c.preset.name, c.fromDay?.toEpochDay()?.toString().orEmpty(),
            c.toDay?.toEpochDay()?.toString().orEmpty()).joinToString(",")

        fun decode(text: String): RangeChoice {
            val p = text.split(',')
            return RangeChoice(
                preset = RangePreset.entries.firstOrNull { it.name == p.getOrNull(0) } ?: RangePreset.ALL,
                fromDay = p.getOrNull(1)?.toLongOrNull()?.let(LocalDate::ofEpochDay),
                toDay = p.getOrNull(2)?.toLongOrNull()?.let(LocalDate::ofEpochDay),
            )
        }
    }
}

/** `message_events.csv`: the message journal's events. Ids, hops, TTL, times and the journal's detail — never message text. */
object MessageEventsCsv {
    const val HEADER = "time_ms,time,event,msg_id,origin,destination,hop_count,forward_count,ttl_remaining_min,detail"

    /** The events of [entries] (newest first, as the journal keeps them) inside [range], oldest first. */
    fun build(entries: List<LifecycleEntry>, range: ExportRange): String = buildString {
        appendLine(HEADER)
        entries.asReversed().filter { it.epochMs in range }.sortedBy { it.epochMs }.forEach { e ->
            appendLine(
                listOf(
                    e.epochMs.toString(), e.timestamp, e.event.name, e.msgId, e.origin, e.dest,
                    e.hopCount.toString(), e.forwardCount.toString(), e.ttlRemainingMin.toString(), e.extra,
                ).joinToString(",") { field(it) },
            )
        }
    }

    /** RFC 4180: quote a field holding a comma, a quote or a line break. */
    fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}

/** One export folder in the history. */
data class ExportFolder(val name: String, val path: String, val createdAtMs: Long, val files: List<String>, val bytes: Long) {
    /** Made by Export all logs. */
    val logsOnly: Boolean get() = name.endsWith(ExportFiles.LOGS_SUFFIX)
}

/** The export history: the folders under `exports/`, newest first. */
object ExportHistory {
    fun describe(dir: File, zone: ZoneId = ZoneId.systemDefault()): ExportFolder {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }
        return ExportFolder(
            name = dir.name, path = dir.absolutePath,
            createdAtMs = ExportFiles.createdAt(dir.name, zone) ?: dir.lastModified(),
            files = files.map { it.name }, bytes = files.sumOf { it.length() },
        )
    }

    /** Folders only: the zips Share leaves next to them (and stray files) aren't exports. */
    fun list(root: File?, zone: ZoneId = ZoneId.systemDefault()): List<ExportFolder> =
        root?.listFiles()?.filter { it.isDirectory }.orEmpty().map { describe(it, zone) }
            .sortedWith(compareByDescending<ExportFolder> { it.createdAtMs }.thenByDescending { it.name })
}

/** The zip Share sends: every file of one export folder, under "<folder>/". */
object ExportZip {
    /** The entries a zip of [folder] holds, sorted: "<folder>/<file>". */
    fun entryNames(folder: File): List<String> = filesOf(folder).map { "${folder.name}/${it.name}" }

    /** Zips [folder]'s files into [target] (replacing it). @return the entry names written. */
    fun zip(folder: File, target: File): List<String> {
        val files = filesOf(folder)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { out ->
            for (f in files) {
                out.putNextEntry(ZipEntry("${folder.name}/${f.name}").apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return files.map { "${folder.name}/${it.name}" }
    }

    /** The entry names inside an existing zip. */
    fun list(zip: File): List<String> = ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toList() }

    private fun filesOf(folder: File): List<File> = folder.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.name }
}
