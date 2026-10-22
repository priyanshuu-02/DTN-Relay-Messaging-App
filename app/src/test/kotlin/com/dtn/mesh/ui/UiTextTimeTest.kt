package com.dtn.mesh.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class UiTextTimeTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val locale = Locale.US
    /** "Now" = Wednesday 14 Oct 2026, 15:30 IST. */
    private val now = at(2026, 10, 14, 15, 30)

    private fun at(y: Int, m: Int, d: Int, h: Int = 10, min: Int = 0): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun dayLabel_relativeThenAbsolute() {
        assertEquals("Today", UiText.dayLabel(at(2026, 10, 14, 0, 5), now, zone, locale))
        assertEquals("Yesterday", UiText.dayLabel(at(2026, 10, 13, 23, 59), now, zone, locale))
        assertEquals("Mon, 12 Oct", UiText.dayLabel(at(2026, 10, 12), now, zone, locale))
        assertEquals("31 Dec 2025", UiText.dayLabel(at(2025, 12, 31), now, zone, locale))
    }

    @Test
    fun dayKey_splitsAtLocalMidnight() {
        assertEquals(UiText.dayKey(at(2026, 10, 14, 0, 0), zone), UiText.dayKey(at(2026, 10, 14, 23, 59), zone))
        assertEquals(UiText.dayKey(at(2026, 10, 13, 23, 59), zone) + 1, UiText.dayKey(at(2026, 10, 14, 0, 0), zone))
    }

    @Test
    fun listTime_buckets() {
        assertEquals("09:05", UiText.listTime(at(2026, 10, 14, 9, 5), now, zone, locale))
        assertEquals("Yesterday", UiText.listTime(at(2026, 10, 13), now, zone, locale))
        assertEquals("Fri", UiText.listTime(at(2026, 10, 9), now, zone, locale))
        assertEquals("7 Oct", UiText.listTime(at(2026, 10, 7), now, zone, locale))
        assertEquals("1/1/25", UiText.listTime(at(2025, 1, 1), now, zone, locale))
        assertEquals("", UiText.listTime(0L, now, zone, locale))
    }

    @Test
    fun duration_isCompact() {
        assertEquals("45 s", UiText.duration(0, 45_000))
        assertEquals("3 min 05 s", UiText.duration(0, 185_000))
        assertEquals("2 h 10 min", UiText.duration(0, 7_800_000))
        assertEquals("3 d 4 h", UiText.duration(0, (3 * 86_400L + 4 * 3_600L) * 1000L))
        assertEquals("0 s", UiText.duration(10_000, 0))
    }
}
