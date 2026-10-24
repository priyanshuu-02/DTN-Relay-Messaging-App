package com.dtn.mesh.export

import android.content.Context
import android.util.Log
import com.dtn.mesh.service.DiagLevel
import com.dtn.mesh.service.DiagSource
import com.dtn.mesh.service.DiagnosticsLog
import com.dtn.mesh.service.MessageLifecycleLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What an export produced: the folder, or why it failed. [failed] names datasets that couldn't be written. */
data class ExportResult(val folder: ExportFolder?, val error: String? = null, val failed: List<String> = emptyList())

/**
 * Developer Tools › Export Data: writes the selected datasets ([ResearchExporter]'s CSV / JSON,
 * the message events) and "Export all logs" (the diagnostics log + its backup, the engine console,
 * the message journal and this app's own logcat) into one folder under `exports/` in the app's
 * external files directory, lists the history and zips a folder for the share sheet.
 *
 * Everything runs on the IO dispatcher. Nothing is uploaded: files leave the phone only through
 * Share. No message text is ever written (the CSVs carry ids, sizes, counts and times).
 */
@Singleton
class DataExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val research: ResearchExporter,
    private val diagnostics: DiagnosticsLog,
    private val lifecycle: MessageLifecycleLog,
) {
    private companion object {
        const val TAG = "DataExport"
        /** Cap on the logcat dump (our own process only). */
        const val MAX_LOGCAT_BYTES = 8L * 1024 * 1024
        const val LOGCAT_TIMEOUT_S = 15L
    }

    /** `exports/` (shared through the FileProvider); null when the storage isn't available. Touches the disk. */
    private fun root(): File? = context.getExternalFilesDir(null)?.let { File(it, ExportFiles.DIR) }

    /** Writes [selection] into a new export folder; the timestamped datasets are limited to [range]. */
    suspend fun generate(selection: ExportSelection, range: ExportRange): ExportResult = withContext(Dispatchers.IO) {
        if (selection.isEmpty) return@withContext ExportResult(null, "Choose at least one kind of data to export")
        write(selection, range, logsOnly = selection == ExportSelection.LOGS_ONLY)
    }

    /** Export all logs: the logs only, whole, into their own folder. */
    suspend fun exportAllLogs(nowMs: Long = System.currentTimeMillis()): ExportResult = withContext(Dispatchers.IO) {
        write(ExportSelection.LOGS_ONLY, ExportRange.allTime(nowMs), logsOnly = true)
    }

    /** The export folders, newest first. */
    suspend fun history(): List<ExportFolder> = withContext(Dispatchers.IO) {
        runCatching { ExportHistory.list(root()) }.getOrDefault(emptyList())
    }

    /** Zips one export folder (next to it, replacing an older zip) for Share; null if it couldn't be written. */
    suspend fun zip(folder: ExportFolder): File? = withContext(Dispatchers.IO) {
        val dir = File(folder.path)
        val base = root()
        // Only folders of our own exports/ directory are ever zipped.
        if (base == null || dir.parentFile?.canonicalPath != base.canonicalPath || !dir.isDirectory) return@withContext null
        runCatching {
            val zip = File(base, ExportFiles.zipName(dir.name))
            val entries = ExportZip.zip(dir, zip)
            diagnostics.i(DiagSource.UI, "Export ${dir.name}: zipped ${entries.size} file(s), ${zip.length()} B, for sharing")
            zip
        }.onFailure {
            Log.w(TAG, "zip failed", it)
            diagnostics.w(DiagSource.UI, "Export ${dir.name}: zip failed (${it.javaClass.simpleName})")
        }.getOrNull()
    }

    private suspend fun write(selection: ExportSelection, range: ExportRange, logsOnly: Boolean): ExportResult {
        val base = root() ?: return ExportResult(null, "The phone's app storage isn't available")
        val dir = newFolder(base, logsOnly) ?: return ExportResult(null, "Couldn't create the export folder")
        val failed = ArrayList<String>()
        for (d in selection.ordered) {
            runCatching { writeDataset(d, dir, range) }.onFailure {
                Log.w(TAG, "export of ${d.name} failed", it)
                failed += d.label
            }
        }
        val folder = ExportHistory.describe(dir)
        diagnostics.log(
            if (failed.isEmpty()) DiagLevel.INFO else DiagLevel.WARN, DiagSource.UI,
            "Export ${dir.name}: ${folder.files.size} file(s), ${folder.bytes} B" +
                (if (selection.usesRange && range.fromMs > 0) " · range ${range.fromMs}..${range.toMs}" else "") +
                (if (failed.isNotEmpty()) " · failed: ${failed.joinToString()}" else ""),
        )
        if (folder.files.isEmpty()) {
            dir.delete() // an empty folder isn't an export
            return ExportResult(null, "Nothing could be written", failed)
        }
        return ExportResult(folder, failed = failed)
    }

    /** A fresh folder named after the time ("…_2" if that name is taken). */
    private fun newFolder(base: File, logsOnly: Boolean): File? {
        val name = ExportFiles.folderName(System.currentTimeMillis(), ZoneId.systemDefault(), logsOnly)
        var dir = File(base, name)
        var n = 2
        while (dir.exists()) dir = File(base, "${name}_${n++}")
        return dir.takeIf { it.mkdirs() || it.isDirectory }
    }

    private suspend fun writeDataset(d: ExportDataset, dir: File, range: ExportRange) {
        when (d) {
            ExportDataset.ENCOUNTERS -> File(dir, "encounters.csv").writeText(research.exportEncountersCsv(range.fromMs, range.toMs))
            ExportDataset.CONTACTS -> File(dir, "contacts.csv").writeText(research.exportContactsCsv())
            ExportDataset.DECISIONS -> File(dir, "decisions.csv").writeText(research.exportDecisionsCsv(range.fromMs, range.toMs))
            // Ids, hops, TTL, status and size — the messages table holds no text (it's sealed), and none is exported.
            ExportDataset.MESSAGES -> File(dir, "messages.csv").writeText(research.exportMessagesCsv(range.fromMs, range.toMs))
            ExportDataset.Q_TABLES -> {
                File(dir, "q_tables.json").writeText(research.exportQTableJson())
                File(dir, "metadata.json").writeText(research.exportMetadata(range.fromMs, range.toMs))
            }
            ExportDataset.MESSAGE_EVENTS ->
                File(dir, "message_events.csv").writeText(MessageEventsCsv.build(lifecycle.entries.value, range))
            ExportDataset.ALL_LOGS -> writeLogs(dir)
        }
    }

    /** The diagnostics log (+ its `.1` backup), the engine console, the message journal and our own logcat. */
    private fun writeLogs(dir: File) {
        // Each file on its own: the diagnostics log may rotate (rename its backup) mid-copy.
        diagnostics.files().forEach { f ->
            runCatching { f.copyTo(File(dir, f.name), overwrite = true) }.onFailure { Log.w(TAG, "copy of ${f.name} failed", it) }
        }
        File(dir, ExportFiles.ENGINE_CONSOLE).writeText(buildString {
            appendLine("DTN Mesh Relay · engine console (encounters, calculations, control traffic), oldest first")
            lifecycle.console.value.asReversed().forEach { appendLine(it) }
        })
        runCatching {
            File(context.filesDir, MessageLifecycleLog.JOURNAL_FILE).takeIf { it.exists() }
                ?.copyTo(File(dir, ExportFiles.JOURNAL), overwrite = true)
        }.onFailure { Log.w(TAG, "journal copy failed", it) }
        captureLogcat(File(dir, ExportFiles.LOGCAT))
    }

    /** `logcat -d --pid=<ours>`: this app's own Android log only (no permission needed for our own lines). */
    private fun captureLogcat(target: File) {
        val pid = android.os.Process.myPid()
        runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid").redirectErrorStream(true).start()
            var written = 0L
            try {
                target.outputStream().buffered().use { out ->
                    process.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (written < MAX_LOGCAT_BYTES) {
                            val n = input.read(buf)
                            if (n < 0) break
                            val keep = minOf(n.toLong(), MAX_LOGCAT_BYTES - written).toInt()
                            out.write(buf, 0, keep)
                            written += keep
                        }
                    }
                    if (written >= MAX_LOGCAT_BYTES) out.write("\n[cut at ${MAX_LOGCAT_BYTES / (1024 * 1024)} MB]\n".toByteArray())
                }
            } finally {
                // `-d` dumps and exits; one cut short (or stuck) is stopped.
                if (written >= MAX_LOGCAT_BYTES || !process.waitFor(LOGCAT_TIMEOUT_S, TimeUnit.SECONDS)) process.destroy()
            }
        }.onFailure {
            Log.w(TAG, "logcat capture failed", it)
            target.writeText("Couldn't read this app's system log: ${it.javaClass.simpleName}\n")
        }
    }
}
