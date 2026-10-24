package com.dtn.mesh.ui.devtools

import com.dtn.mesh.service.DiagLevel
import com.dtn.mesh.service.DiagLine
import com.dtn.mesh.service.DiagSource
import com.dtn.mesh.service.DiagnosticsFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ConsoleModelTest {

    private fun line(i: Int, level: DiagLevel, source: String, text: String) = DiagLine(1_000L + i, level, source, text)

    /** Newest first, as the diagnostics log keeps them. */
    private val lines = listOf(
        line(6, DiagLevel.ERROR, DiagSource.BLE, "GATT connect failed: status 133"),
        line(5, DiagLevel.INFO, DiagSource.ROUTING, "flush → 1a2b3c4d: offered 2 · sent 1 · held 1 (algorithm said hold ×1)"),
        line(4, DiagLevel.WARN, DiagSource.HUB, "Hub Wi-Fi unavailable, retry in 8 s"),
        line(3, DiagLevel.INFO, DiagSource.BLE, "Scan started"),
        line(2, DiagLevel.WARN, DiagSource.RELAY, "Relay connect failed via TLS 8883"),
        line(1, DiagLevel.DEBUG, DiagSource.UI, "Refreshing peer status"),
    )

    private fun texts(l: List<DiagLine>) = l.map { it.text }

    @Test
    fun levels_allWarningsErrors() {
        assertEquals(6, ConsoleModel.visible(lines, ConsoleLevel.ALL, emptySet(), "", 0L).size)
        assertEquals(listOf("Hub Wi-Fi unavailable, retry in 8 s", "Relay connect failed via TLS 8883"),
            texts(ConsoleModel.visible(lines, ConsoleLevel.WARNINGS, emptySet(), "", 0L)))
        assertEquals(listOf("GATT connect failed: status 133"), texts(ConsoleModel.visible(lines, ConsoleLevel.ERRORS, emptySet(), "", 0L)))
    }

    @Test
    fun sources_noneIsEverySource_severalAreCombined() {
        val ble = ConsoleModel.visible(lines, ConsoleLevel.ALL, setOf(DiagSource.BLE), "", 0L)
        assertEquals(listOf("GATT connect failed: status 133", "Scan started"), texts(ble))
        val two = ConsoleModel.visible(lines, ConsoleLevel.ALL, setOf(DiagSource.HUB, DiagSource.RELAY), "", 0L)
        assertEquals(listOf(DiagSource.HUB, DiagSource.RELAY), two.map { it.source })
        // Level and source together.
        assertEquals(listOf("Relay connect failed via TLS 8883"),
            texts(ConsoleModel.visible(lines, ConsoleLevel.WARNINGS, setOf(DiagSource.RELAY, DiagSource.BLE), "", 0L)))
    }

    @Test
    fun search_matchesTextSourceAndSourceLabel_ignoringCase() {
        assertEquals(listOf("GATT connect failed: status 133", "Relay connect failed via TLS 8883"),
            texts(ConsoleModel.visible(lines, ConsoleLevel.ALL, emptySet(), "CONNECT FAILED", 0L)))
        // "wi-fi" is in a hub line's text; "bluetooth" is the BLE source's label.
        assertEquals(1, ConsoleModel.visible(lines, ConsoleLevel.ALL, emptySet(), "wi-fi", 0L).size)
        assertEquals(2, ConsoleModel.visible(lines, ConsoleLevel.ALL, emptySet(), "bluetooth", 0L).size)
        assertEquals(1, ConsoleModel.visible(lines, ConsoleLevel.ALL, emptySet(), "routing", 0L).size)
        // Search + source + level.
        assertEquals(listOf("GATT connect failed: status 133"),
            texts(ConsoleModel.visible(lines, ConsoleLevel.ERRORS, setOf(DiagSource.BLE), "gatt", 0L)))
        assertTrue(ConsoleModel.visible(lines, ConsoleLevel.ALL, setOf(DiagSource.HUB), "gatt", 0L).isEmpty())
        assertEquals(6, ConsoleModel.visible(lines, ConsoleLevel.ALL, emptySet(), "   ", 0L).size)
    }

    @Test
    fun clearView_hidesLinesUpToThatTime_only() {
        val after = ConsoleModel.visible(lines, ConsoleLevel.ALL, emptySet(), "", clearedAtMs = 1_004L)
        assertEquals(listOf("GATT connect failed: status 133", "flush → 1a2b3c4d: offered 2 · sent 1 · held 1 (algorithm said hold ×1)"),
            texts(after))
        // Nothing is removed from the log itself.
        assertEquals(6, lines.size)
    }

    @Test
    fun counts_followTheSourcesSearchAndClearView() {
        assertEquals(ConsoleCounts(all = 6, warnings = 2, errors = 1), ConsoleModel.counts(lines, emptySet(), "", 0L))
        assertEquals(ConsoleCounts(all = 2, warnings = 0, errors = 1), ConsoleModel.counts(lines, setOf(DiagSource.BLE), "", 0L))
        assertEquals(ConsoleCounts(all = 2, warnings = 1, errors = 1), ConsoleModel.counts(lines, emptySet(), "failed", 0L))
        assertEquals(ConsoleCounts(all = 3, warnings = 1, errors = 1), ConsoleModel.counts(lines, emptySet(), "", 1_003L))
        val c = ConsoleModel.counts(lines, emptySet(), "", 0L)
        assertEquals(2, c.of(ConsoleLevel.WARNINGS))
    }

    @Test
    fun sourcesIn_followTheChipOrder() {
        assertEquals(listOf(DiagSource.UI, DiagSource.BLE, DiagSource.HUB, DiagSource.RELAY, DiagSource.ROUTING),
            ConsoleModel.sourcesIn(lines))
        val odd = lines + DiagLine(1L, DiagLevel.INFO, "zeta", "new source")
        assertEquals("zeta", ConsoleModel.sourcesIn(odd).last())
        assertEquals("Wi-Fi Direct", ConsoleModel.sourceLabel(DiagSource.WIFID))
        assertEquals("zeta", ConsoleModel.sourceLabel("zeta"))
    }

    @Test
    fun copyOutput_isOldestFirst_inTheOnScreenForm_capped() {
        val shown = ConsoleModel.visible(lines, ConsoleLevel.WARNINGS, emptySet(), "", 0L)
        val copied = ConsoleModel.copyText(shown).lines()
        assertEquals(listOf(DiagnosticsFormat.display(shown[1]), DiagnosticsFormat.display(shown[0])), copied)
        // At most `max` lines: the newest ones.
        assertEquals(listOf(DiagnosticsFormat.display(lines[1]), DiagnosticsFormat.display(lines[0])),
            ConsoleModel.copyText(lines, max = 2).lines())
    }

    @Test
    fun time_isTheClockTime() {
        assertEquals("12:00:01", ConsoleModel.time(1_792_843_201_000L, ZoneOffset.UTC))
    }
}
