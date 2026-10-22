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
    fun malformedOrUnknownLines_areSkipped() {
        assertNull(JournalCodec.decode("", fmt))
        assertNull(JournalCodec.decode("X\tsomething new", fmt))
        assertNull(JournalCodec.decode("E\tnot-a-number\tFORWARDED\ta\tb\tc\t1\t1\t1\tx", fmt))
        assertNull(JournalCodec.decode("E\t1\tNO_SUCH_EVENT\ta\tb\tc\t1\t1\t1\tx", fmt))
        assertNull(JournalCodec.decode("R\ttoo\tshort", fmt))
    }
}
