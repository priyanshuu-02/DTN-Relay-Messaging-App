package com.dtn.mesh.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun line(i: Int, level: DiagLevel = DiagLevel.INFO) =
        DiagLine(atMs = 1_700_000_000_000L + i, level = level, source = DiagSource.SYSTEM, text = "line $i")

    // ── Ring ────────────────────────────────────────────────────────────────

    @Test
    fun ring_keepsOnlyTheNewestCapacityLines() {
        val ring = DiagnosticsRing(DiagnosticsLog.CAPACITY)
        repeat(DiagnosticsLog.CAPACITY + 5) { ring.add(line(it)) }
        assertEquals(2_000, ring.size)
        assertEquals("line 2004", ring.newestFirst().first().text)
        assertEquals("line 5", ring.oldestFirst().first().text)
        assertEquals("line 5", ring.newestFirst().last().text)
    }

    @Test
    fun ring_belowCapacityKeepsEverythingInOrder() {
        val ring = DiagnosticsRing(10)
        repeat(3) { ring.add(line(it)) }
        assertEquals(listOf("line 0", "line 1", "line 2"), ring.oldestFirst().map { it.text })
        assertEquals(listOf("line 2", "line 1", "line 0"), ring.newestFirst().map { it.text })
    }

    @Test
    fun ring_levelCountsFollowEvictions() {
        val ring = DiagnosticsRing(4)
        ring.add(line(0, DiagLevel.ERROR))
        ring.add(line(1, DiagLevel.WARN))
        ring.add(line(2, DiagLevel.INFO))
        ring.add(line(3, DiagLevel.DEBUG))
        assertEquals(LevelCounts(debug = 1, info = 1, warn = 1, error = 1), ring.counts())
        // Two more INFO lines push the ERROR and the WARN out.
        ring.add(line(4))
        ring.add(line(5))
        val c = ring.counts()
        assertEquals(0, c.error)
        assertEquals(0, c.warn)
        assertEquals(3, c.info)
        assertEquals(1, c.debug)
        assertEquals(4, c.total)
    }

    @Test
    fun ring_addOlderGoesInFrontAndRespectsCapacity() {
        val ring = DiagnosticsRing(4)
        ring.add(line(10))
        ring.add(line(11))
        ring.addOlder(listOf(line(1), line(2), line(3)))
        // Room for 4: the newest of the loaded lines, then everything logged live.
        assertEquals(listOf("line 2", "line 3", "line 10", "line 11"), ring.oldestFirst().map { it.text })
        assertEquals(4, ring.counts().info)
    }

    // ── File rotation ───────────────────────────────────────────────────────

    @Test
    fun file_rotatesAtTheLimitAndKeepsOneBackup() {
        val f = RotatingLogFile(tmp.newFile("diag.log").also { it.delete() }, maxBytes = 100)
        val lineText = "x".repeat(19) // 20 bytes with the newline
        repeat(5) { f.append(listOf(lineText)) } // exactly 100 bytes: no rotation yet
        assertFalse(f.backup.exists())
        assertEquals(100L, f.file.length())

        f.append(listOf("first-after-rotation"))
        assertTrue(f.backup.exists())
        assertEquals(100L, f.backup.length())
        assertEquals(listOf("first-after-rotation"), f.file.readLines())

        // A second rotation replaces the backup with the file that just filled up (21 + 3 × 20 bytes).
        repeat(3) { f.append(listOf(lineText)) }
        f.append(listOf("second-after-rotation"))
        assertEquals("first-after-rotation", f.backup.readLines().first())
        assertEquals(listOf("second-after-rotation"), f.file.readLines())
        assertTrue(f.file.length() + f.backup.length() <= 200)
    }

    @Test
    fun file_tailSpansBackupAndCurrentFile() {
        val f = RotatingLogFile(tmp.newFile("tail.log").also { it.delete() }, maxBytes = 30)
        (1..6).forEach { f.append(listOf("l$it-....")) } // 8 bytes each → rotates after 3
        assertTrue(f.backup.exists())
        assertEquals(listOf("l3-....", "l4-....", "l5-....", "l6-...."), f.tail(4))
        assertEquals(listOf("l6-...."), f.tail(1))
        assertEquals(2, f.files().size)
    }

    @Test
    fun file_survivesARestart() {
        val path = tmp.newFile("restart.log").also { it.delete() }
        RotatingLogFile(path, maxBytes = 1_000).append(listOf("a", "b"))
        val reopened = RotatingLogFile(path, maxBytes = 1_000)
        reopened.append(listOf("c"))
        assertEquals(listOf("a", "b", "c"), reopened.tail(10))
    }

    // ── Line format ─────────────────────────────────────────────────────────

    @Test
    fun format_roundTripsAndFlattensLineBreaks() {
        val l = DiagLine(1_760_000_000_123L, DiagLevel.WARN, DiagSource.BLE, "Scan failed:\terror 2\nretrying")
        val decoded = DiagnosticsFormat.decode(DiagnosticsFormat.encode(l))
        assertNotNull(decoded)
        assertEquals(l.atMs, decoded!!.atMs)
        assertEquals(DiagLevel.WARN, decoded.level)
        assertEquals("ble", decoded.source)
        assertEquals("Scan failed: error 2 retrying", decoded.text)
        assertFalse(DiagnosticsFormat.encode(l).contains('\n'))
    }

    @Test
    fun format_rejectsTornOrForeignLines() {
        assertNull(DiagnosticsFormat.decode(""))
        assertNull(DiagnosticsFormat.decode("2026-10-14 21:04:28.123 +0530\tINFO"))
        assertNull(DiagnosticsFormat.decode("not a date\tINFO\tble\ttext"))
        assertNull(DiagnosticsFormat.decode("2026-10-14 21:04:28.123 +0530\tLOUD\tble\ttext"))
    }

    // ── Held-reason aggregation ─────────────────────────────────────────────

    private fun summary(offered: Int, sent: Int, vararg held: Pair<HoldReason, Int>) = PeerFlushSummary().apply {
        this.offered = offered
        this.sent = sent
        held.forEach { (r, n) -> hold(r, n) }
    }

    @Test
    fun held_countsPerReasonInOneLine() {
        val s = summary(5, 1, HoldReason.ALGORITHM to 2, HoldReason.RECENTLY_SENT to 1, HoldReason.CAME_FROM_PEER to 1)
        s.hold(HoldReason.ALGORITHM)
        assertEquals(3, s.heldCount(HoldReason.ALGORITHM))
        val text = s.describe("1a2b3c4d")
        assertTrue(text, text.startsWith("flush → 1a2b3c4d: offered 5 · sent 1 · held 5 ("))
        assertTrue(text, text.contains("algorithm said hold ×3"))
        assertTrue(text, text.contains("sent under 30 s ago ×1"))
        assertTrue(text, text.contains("came from that peer ×1"))
    }

    @Test
    fun held_logsOnlyWhenAPeersSummaryChanges() {
        val t = HeldReasonTracker()
        val peer = "!99a9769a"
        assertNotNull(t.lineFor(peer, summary(3, 0, HoldReason.ALGORITHM to 3)))
        // The same pass every 4 s: silent.
        repeat(10) { assertNull(t.lineFor(peer, summary(3, 0, HoldReason.ALGORITHM to 3))) }
        // Something changed: logged once, then silent again.
        assertNotNull(t.lineFor(peer, summary(3, 1, HoldReason.ALGORITHM to 2)))
        assertNull(t.lineFor(peer, summary(3, 1, HoldReason.ALGORITHM to 2)))
        // Another peer has its own history.
        assertNotNull(t.lineFor("!0000beef", summary(3, 1, HoldReason.ALGORITHM to 2)))
        // After the peer leaves, its next pass is logged again.
        t.forget(peer)
        assertNotNull(t.lineFor(peer, summary(3, 1, HoldReason.ALGORITHM to 2)))
    }

    @Test
    fun held_signatureIgnoresTheOrderReasonsWereCounted() {
        val a = PeerFlushSummary().apply { hold(HoldReason.RECENTLY_SENT); hold(HoldReason.ALGORITHM) }
        val b = PeerFlushSummary().apply { hold(HoldReason.ALGORITHM); hold(HoldReason.RECENTLY_SENT) }
        assertEquals(a.signature(), b.signature())
    }
}
