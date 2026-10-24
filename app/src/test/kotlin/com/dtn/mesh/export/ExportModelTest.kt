package com.dtn.mesh.export

import com.dtn.mesh.service.LifecycleEntry
import com.dtn.mesh.service.LifecycleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ExportModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val utc: ZoneId = ZoneOffset.UTC
    private val ist: ZoneId = ZoneId.of("Asia/Kolkata")
    /** 2026-10-24 12:00:00 UTC. */
    private val now = 1_792_843_200_000L

    private fun event(id: String, at: Long, event: LifecycleEvent = LifecycleEvent.FORWARDED, extra: String = "") =
        LifecycleEntry(timestamp = "Oct 24 12:00:00", event = event, msgId = id, origin = "0cdc860b", dest = "1a2b3c4d",
            hopCount = 1, forwardCount = 2, ttlRemainingMin = 239, extra = extra, epochMs = at)

    // ── Dataset selection ───────────────────────────────────────────────────

    @Test
    fun defaultSelection_isTheResearchDataWithoutTheLogs() {
        val s = ExportSelection.DEFAULT
        assertFalse(ExportDataset.ALL_LOGS in s.datasets)
        assertEquals(
            listOf("encounters.csv", "contacts.csv", "decisions.csv", "messages.csv", "q_tables.json", "metadata.json", "message_events.csv"),
            s.fileNames(),
        )
        assertTrue(s.usesRange)
    }

    @Test
    fun selection_writesOnlyTheSwitchedOnFiles_inPageOrder() {
        val s = ExportSelection(emptySet())
            .with(ExportDataset.MESSAGE_EVENTS, true)
            .with(ExportDataset.CONTACTS, true)
            .with(ExportDataset.ENCOUNTERS, true)
            .with(ExportDataset.ENCOUNTERS, false)
        assertEquals(listOf(ExportDataset.CONTACTS, ExportDataset.MESSAGE_EVENTS), s.ordered)
        assertEquals(listOf("contacts.csv", "message_events.csv"), s.fileNames())
        assertFalse(s.isEmpty)
    }

    @Test
    fun selection_usesTheRangeOnlyWhenSomethingDatedIsOn() {
        assertFalse(ExportSelection(setOf(ExportDataset.CONTACTS, ExportDataset.Q_TABLES, ExportDataset.ALL_LOGS)).usesRange)
        assertTrue(ExportSelection(setOf(ExportDataset.CONTACTS, ExportDataset.DECISIONS)).usesRange)
        assertTrue(ExportSelection(emptySet()).isEmpty)
    }

    @Test
    fun allLogs_coverTheDiagnosticsFileAndBackup_console_journal_logcat() {
        assertEquals(
            listOf("diagnostics_v1.log", "diagnostics_v1.log.1", "engine_console.txt", "message_journal_v1.log", "logcat.txt"),
            ExportSelection.LOGS_ONLY.fileNames(),
        )
    }

    @Test
    fun selection_roundTripsThroughItsSavedForm() {
        val s = ExportSelection(setOf(ExportDataset.Q_TABLES, ExportDataset.ENCOUNTERS, ExportDataset.ALL_LOGS))
        assertEquals("ENCOUNTERS,Q_TABLES,ALL_LOGS", ExportSelection.encode(s))
        assertEquals(s, ExportSelection.decode(ExportSelection.encode(s)))
        assertTrue(ExportSelection.decode("").isEmpty)
        // An unknown name (an older or newer build) is skipped.
        assertEquals(setOf(ExportDataset.CONTACTS), ExportSelection.decode("CONTACTS,TELEMETRY").datasets)
    }

    // ── Date range ──────────────────────────────────────────────────────────

    @Test
    fun days_coverWholeLocalDays_inclusive() {
        val r = ExportRange.days(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 24), ist)
        assertEquals(LocalDate.of(2026, 10, 17).atStartOfDay(ist).toInstant().toEpochMilli(), r.fromMs)
        assertEquals(LocalDate.of(2026, 10, 25).atStartOfDay(ist).toInstant().toEpochMilli() - 1, r.toMs)
        assertTrue(r.fromMs in r)
        assertTrue(r.toMs in r)
        assertFalse(r.fromMs - 1 in r)
        assertFalse(r.toMs + 1 in r)
    }

    @Test
    fun days_swapAReversedRange() {
        val a = ExportRange.days(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 20), utc)
        val b = ExportRange.days(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 24), utc)
        assertEquals(b, a)
    }

    @Test
    fun presets_resolveAgainstNow() {
        assertEquals(ExportRange(0L, now), RangeChoice(RangePreset.ALL).resolve(now, utc))
        assertEquals(ExportRange(now - 24 * 3_600_000L, now), RangeChoice(RangePreset.DAY).resolve(now, utc))
        assertEquals(ExportRange(now - 7 * 24 * 3_600_000L, now), RangeChoice(RangePreset.WEEK).resolve(now, utc))
        assertEquals(ExportRange(now - 30 * 24 * 3_600_000L, now), RangeChoice(RangePreset.MONTH).resolve(now, utc))
    }

    @Test
    fun custom_withoutDays_isToday_andWithOneDayIsThatDayToToday() {
        val today = ExportRange.days(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 24), utc)
        assertEquals(today, RangeChoice(RangePreset.CUSTOM).resolve(now, utc))
        val fromOnly = RangeChoice(RangePreset.CUSTOM, fromDay = LocalDate.of(2026, 10, 1)).resolve(now, utc)
        assertEquals(ExportRange.days(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 24), utc), fromOnly)
    }

    @Test
    fun rangeChoice_roundTripsThroughItsSavedForm() {
        val c = RangeChoice(RangePreset.CUSTOM, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 9))
        assertEquals(c, RangeChoice.decode(RangeChoice.encode(c)))
        assertEquals(RangeChoice(RangePreset.WEEK), RangeChoice.decode(RangeChoice.encode(RangeChoice(RangePreset.WEEK))))
        assertEquals(RangeChoice(), RangeChoice.decode("garbage"))
    }

    @Test
    fun pickerDays_areUtcMidnights() {
        val day = LocalDate.of(2026, 10, 24)
        assertEquals(day, ExportRange.pickerDay(ExportRange.pickerMillis(day)))
        // Any time on that UTC day is that day.
        assertEquals(day, ExportRange.pickerDay(ExportRange.pickerMillis(day) + 23 * 3_600_000L))
    }

    // ── Message events CSV ──────────────────────────────────────────────────

    @Test
    fun messageEvents_keepOnlyTheRange_oldestFirst() {
        // The journal keeps events newest first.
        val entries = listOf(event("cccc0003", 3_000L), event("bbbb0002", 2_000L), event("aaaa0001", 1_000L))
        val csv = MessageEventsCsv.build(entries, ExportRange(1_500L, 3_000L)).trimEnd().lines()
        assertEquals(MessageEventsCsv.HEADER, csv[0])
        assertEquals(listOf("bbbb0002", "cccc0003"), csv.drop(1).map { it.split(',')[3] })
        assertEquals("2000,Oct 24 12:00:00,FORWARDED,bbbb0002,0cdc860b,1a2b3c4d,1,2,239,", csv[1])
    }

    @Test
    fun messageEvents_quoteDetailsWithCommasOrQuotes_andHaveNoTextColumn() {
        val csv = MessageEventsCsv.build(listOf(event("aaaa0001", 1_000L, extra = "handed to 1a2b3c4d, via \"BLE\"")),
            ExportRange.allTime(now)).trimEnd().lines()
        assertTrue(csv[1].endsWith(",\"handed to 1a2b3c4d, via \"\"BLE\"\"\""))
        assertFalse(MessageEventsCsv.HEADER.split(',').any { it.contains("text") || it.contains("payload") })
        assertEquals("plain", MessageEventsCsv.field("plain"))
    }

    // ── Folders, history, zip ───────────────────────────────────────────────

    @Test
    fun folderNames_carryTheTime_andALogsSuffix() {
        assertEquals("20261024_120000", ExportFiles.folderName(now, utc, logsOnly = false))
        assertEquals("20261024_120000_logs", ExportFiles.folderName(now, utc, logsOnly = true))
        assertEquals(now, ExportFiles.createdAt("20261024_120000_logs", utc))
        assertNull(ExportFiles.createdAt("notes", utc))
        assertEquals("dtn-export-20261024_120000.zip", ExportFiles.zipName("20261024_120000"))
    }

    @Test
    fun history_listsFoldersOnly_newestFirst() {
        val root = tmp.newFolder(ExportFiles.DIR)
        File(root, "20261020_090000").apply { mkdirs(); File(this, "contacts.csv").writeText("node_id\n") }
        File(root, "20261024_120000_logs").apply { mkdirs(); File(this, "logcat.txt").writeText("x"); File(this, "engine_console.txt").writeText("yz") }
        File(root, "dtn-export-20261020_090000.zip").writeText("zip")
        val list = ExportHistory.list(root, utc)
        assertEquals(listOf("20261024_120000_logs", "20261020_090000"), list.map { it.name })
        val logs = list.first()
        assertTrue(logs.logsOnly)
        assertEquals(listOf("engine_console.txt", "logcat.txt"), logs.files)
        assertEquals(3L, logs.bytes)
        assertEquals(now, logs.createdAtMs)
        assertFalse(list.last().logsOnly)
        assertTrue(ExportHistory.list(File(tmp.root, "missing"), utc).isEmpty())
    }

    @Test
    fun zip_holdsEveryFileOfTheFolder_underItsName() {
        val root = tmp.newFolder("exports-zip")
        val folder = File(root, "20261024_120000").apply { mkdirs() }
        File(folder, "messages.csv").writeText("id\n")
        File(folder, "contacts.csv").writeText("node_id\n")
        File(folder, "q_tables.json").writeText("{}")
        File(folder, "nested").mkdirs() // not a file: not zipped
        val expected = listOf("20261024_120000/contacts.csv", "20261024_120000/messages.csv", "20261024_120000/q_tables.json")
        assertEquals(expected, ExportZip.entryNames(folder))

        val zip = File(root, ExportFiles.zipName(folder.name))
        assertEquals(expected, ExportZip.zip(folder, zip))
        assertEquals(expected, ExportZip.list(zip))

        // Sharing again replaces the zip with the folder's current contents.
        File(folder, "metadata.json").writeText("{}")
        ExportZip.zip(folder, zip)
        assertEquals(expected.take(2) + "20261024_120000/metadata.json" + expected.last(), ExportZip.list(zip))
        assertFalse(File(zip.path + ".tmp").exists())
    }
}
