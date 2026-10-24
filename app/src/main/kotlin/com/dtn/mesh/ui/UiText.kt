package com.dtn.mesh.ui

import java.text.BreakIterator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Small pure text helpers for the UI (JVM unit-tested). */
object UiText {

    /**
     * The first user-perceived character of [name] for an avatar. Works on grapheme clusters, so
     * an emoji (a surrogate pair, possibly with a ZWJ / skin-tone sequence) stays intact instead of
     * being split into an unprintable half. Letters are upper-cased; blank names give "?".
     */
    fun avatarInitial(name: String): String {
        val text = name.trim()
        if (text.isEmpty()) return "?"
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val end = it.next().let { e -> if (e == BreakIterator.DONE || e <= 0) text.length else e }
        val first = text.substring(0, end)
        return if (Character.isLetter(first.codePointAt(0))) first.uppercase() else first
    }

    /** A hex safety number laid out as lines of four 4-character groups (64 hex → 4 lines). */
    fun fingerprintLines(hex: String): List<String> =
        hex.lowercase().chunked(4).chunked(4).map { it.joinToString(" ") }

    /** Every form a broadcast destination takes in records and on the wire. */
    fun isBroadcastId(id: String?): Boolean =
        id != null && (id == "BROADCAST" || id == "^all" ||
            id.equals("!ffffffff", ignoreCase = true) || id.equals("ffffffff", ignoreCase = true))

    private fun localDate(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /** Calendar day of [epochMs] (used to group chat bubbles under date separators). */
    fun dayKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long = localDate(epochMs, zone).toEpochDay()

    /** Date separator text: "Today", "Yesterday", "Wed, 14 Oct", or "14 Oct 2025" for other years. */
    fun dayLabel(
        epochMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val day = localDate(epochMs, zone)
        val today = localDate(nowMs, zone)
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))
            else -> day.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
        }
    }

    /** Chat-list timestamp: "14:05" today, "Yesterday", a weekday within the last week, else "14 Oct". */
    fun listTime(
        epochMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (epochMs <= 0L) return ""
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        val day = at.toLocalDate()
        val today = localDate(nowMs, zone)
        return when {
            day == today -> at.format(DateTimeFormatter.ofPattern("HH:mm", locale))
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today.minusDays(7)) && !day.isAfter(today) -> day.format(DateTimeFormatter.ofPattern("EEE", locale))
            day.year == today.year -> day.format(DateTimeFormatter.ofPattern("d MMM", locale))
            else -> day.format(DateTimeFormatter.ofPattern("d/M/yy", locale))
        }
    }

    /** Full timestamp for Message info, e.g. "Wed, 14 Oct 2026 · 14:05:09". */
    fun fullTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm:ss", locale))

    /** Compact duration between two instants: "45 s", "3 min 05 s", "2 h 10 min", "3 d 4 h". */
    fun duration(fromMs: Long, toMs: Long): String {
        val s = ((toMs - fromMs) / 1000L).coerceAtLeast(0L)
        return when {
            s < 60 -> "$s s"
            s < 3_600 -> "${s / 60} min %02d s".format(s % 60)
            s < 86_400 -> "${s / 3_600} h ${(s % 3_600) / 60} min"
            else -> "${s / 86_400} d ${(s % 86_400) / 3_600} h"
        }
    }
}

/** Compact relative "time since" label for a last-seen timestamp. */
internal fun relativeSince(ms: Long): String {
    if (ms <= 0L) return "never"
    val delta = System.currentTimeMillis() - ms
    return when {
        delta < 10_000L -> "just now"
        delta < 60_000L -> "${delta / 1000}s ago"
        delta < 3_600_000L -> "${delta / 60_000}m ago"
        delta < 86_400_000L -> "${delta / 3_600_000}h ago"
        else -> "${delta / 86_400_000}d ago"
    }
}
