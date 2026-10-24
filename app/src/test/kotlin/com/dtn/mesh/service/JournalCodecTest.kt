package com.dtn.mesh.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class JournalCodecTest {

    private val fmt: (Long) -> String = { "t$it" }

    private val event = LifecycleEntry(
        timestamp = "t1700000000000",
        event = LifecycleEvent.FORWARDED,
        msgId = "1a2b3c4d",
        origin = "58fe1be9",
        dest = "ffbd889b",
        hopCount = 2,
        forwardCount = 3,
        ttlRemainingMin = 230,
        extra = "DM → ffbd889b via Internet relay\twith a tab\nand a newline \\ and a backslash",
        epochMs = 1_700_000_000_000L,
    )

    @Test
    fun event_roundTrips_includingTabsNewlinesAndBackslashes() {
        val d = JournalCodec.decode(JournalCodec.encodeEvent(event), fmt)
        assertEquals(event, d?.event)
        assertNull(d?.route)
    }

    @Test
    fun encodedRecords_stayOnOneLine() {
        val line = JournalCodec.encodeEvent(event)
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
    }

    @Test
    fun route_roundTrips_includingNullHops() {
        val r = MessageLifecycleLog.RouteRecord(
            msgId = "1a2b3c4d", source = "58fe1be9", destination = "ffbd889b", prevHop = null,
            me = "58fe1be9", nextHop = "ffbd889b", delivered = true, timestampMs = 5L, strategy = "PROPHET",
            fullId = "1a2b3c4d-0000-4000-8000-000000000001", channel = 0, createdAtMs = 1L,
            lastPath = "Internet relay", outcome = "", deliveredAtMs = 4L,
        )
        val d = JournalCodec.decode(JournalCodec.encodeRoute(r), fmt)
        assertEquals(r, d?.route)
        assertNull(d?.event)

        val carried = r.copy(prevHop = "aabbccdd", nextHop = null, delivered = false, channel = 7, outcome = "EXPIRED")
        assertEquals(carried, JournalCodec.decode(JournalCodec.encodeRoute(carried), fmt)?.route)
    }

    @Test
    fun route_keepsTheSavedDecisionAndSenderAlgorithm() {
        val r = MessageLifecycleLog.RouteRecord(
            msgId = "1a2b3c4d", source = "58fe1be9", destination = "ffbd889b", me = "58fe1be9",
            nextHop = "0000000b", timestampMs = 5L, strategy = "DIMAXPROP", createdAtMs = 1L,
            decision = "DiMaxProp · λ 0.50\n  me → 0000000b  0.42 = (1 − f 0.70)\tx\nDecision: forward",
            decisionAtMs = 3L, senderStrategy = "Q-learning",
        )
        assertEquals(r, JournalCodec.decode(JournalCodec.encodeRoute(r), fmt)?.route)
    }

    @Test
    fun route_keepsGroupReceiptsAndTheFallback() {
        val group = MessageLifecycleLog.RouteRecord(
            msgId = "1a2b3c4d", source = "58fe1be9", destination = "BROADCAST", me = "58fe1be9", channel = 7,
            timestampMs = 5L, createdAtMs = 1L,
            groupReceipts = listOf(
                MessageLifecycleLog.GroupReceipt("aaaa0001", 1_700_000_000_000L, "LoRa hub", "da65bdb8"),
                MessageLifecycleLog.GroupReceipt("bbbb0002", 1_700_000_001_000L, "BLE"),
            ),
        )
        assertEquals(group, JournalCodec.decode(JournalCodec.encodeRoute(group), fmt)?.route)

        val fallback = MessageLifecycleLog.RouteRecord(
            msgId = "2b3c4d5e", source = "58fe1be9", destination = "ffbd889b", me = "58fe1be9", nextHop = "da64a9b0",
            lastPath = "LoRa hub", timestampMs = 5L, createdAtMs = 1L,
            fallbackVia = "Internet relay,LoRa hub", fallbackAfter = "BLE",
        )
        val decoded = JournalCodec.decode(JournalCodec.encodeRoute(fallback), fmt)?.route
        assertEquals(fallback, decoded)
        assertEquals(listOf("Internet relay", "LoRa hub"), decoded?.fallbackLinks)
    }

    @Test
    fun routeLinesFromBeforeGroupReceipts_stillLoad() {
        val r = MessageLifecycleLog.RouteRecord(
            msgId = "1a2b3c4d", source = "58fe1be9", destination = "BROADCAST", me = "58fe1be9", channel = 7,
            timestampMs = 5L, createdAtMs = 1L, senderStrategy = "",
        )
        // A line as the previous build wrote it: 19 fields, no receipts / fallback.
        val old = JournalCodec.encodeRoute(r).split('\t').take(19).joinToString("\t")
        val route = JournalCodec.decode(old, fmt)?.route
        assertEquals(r, route)
        assertEquals(emptyList<MessageLifecycleLog.GroupReceipt>(), route?.groupReceipts)
        assertEquals("", route?.fallbackVia)
        // Damaged receipt entries are skipped, the rest kept.
        assertEquals(listOf("aaaa0001"), JournalCodec.decodeReceipts("aaaa0001,5,BLE,;bad;,7,BLE").map { it.member })
    }

    @Test
    fun routeLinesFromBeforeDecisionsWereSaved_stillLoad() {
        val old = listOf("R", "1a2b3c4d", "full", "src", "dst", "0", "", "me", "nxt", "0", "5", "PROPHET",
            "1", "BLE", "", "0").joinToString("\t")
        val route = JournalCodec.decode(old, fmt)?.route
        assertEquals("PROPHET", route?.strategy)
        assertEquals("", route?.decision)
        assertEquals(0L, route?.decisionAtMs)
        assertEquals("", route?.senderStrategy)
    }

    @Test
    fun malformedOrUnknownLines_areSkipped() {
        assertNull(JournalCodec.decode("", fmt))
        assertNull(JournalCodec.decode("X\tsomething new", fmt))
        assertNull(JournalCodec.decode("E\tnot-a-number\tFORWARDED\ta\tb\tc\t1\t1\t1\tx", fmt))
        assertNull(JournalCodec.decode("E\t1\tNO_SUCH_EVENT\ta\tb\tc\t1\t1\t1\tx", fmt))
        assertNull(JournalCodec.decode("R\ttoo\tshort", fmt))
    }
}
