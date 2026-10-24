package com.dtn.mesh.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BLE client-link rules: one link per peer phone (an older one is handed back to be closed, its
 * callbacks find nothing), one quick connect retry within the budget, torn inbound transfers reset
 * (fix 1, `_diag/fix1_report.md`).
 */
class BleLinkRulesTest {

    private class Gatt(val name: String)
    private class Link(val name: String)

    @Test
    fun oneLinkPerPhone_aNewAddressReplacesTheOldLink() {
        val table = LinkTable<Gatt, Link>()
        val g1 = Gatt("aa:01")
        val l1 = Link("first")
        assertNull(table.open("!c06853fe", g1, l1))
        // The phone changed its address: the new link replaces the old one, which must be closed.
        val g2 = Gatt("aa:02")
        val l2 = Link("second")
        assertSame(l1, table.open("!c06853fe", g2, l2))
        assertSame(l2, table.current("!c06853fe"))
        // A late callback from the old GATT object finds nothing: it can't touch the new link.
        assertNull(table.linkOf(g1))
        assertSame(l2, table.linkOf(g2))
        assertEquals(1, table.size())
        // Dropping the old one again is harmless; dropping the current one forgets it.
        assertFalse(table.drop("!c06853fe", l1))
        assertTrue(table.drop("!c06853fe", l2))
        assertNull(table.current("!c06853fe"))
        assertEquals(0, table.size())
    }

    @Test
    fun linksOfDifferentPhonesStayApart_andClearHandsBackAll() {
        val table = LinkTable<Gatt, Link>()
        val a = Link("a")
        val b = Link("b")
        table.open("!0000000a", Gatt("1"), a)
        table.open("!0000000b", Gatt("2"), b)
        assertSame(a, table.current("!0000000a"))
        assertEquals(setOf(a, b), table.clear().toSet())
        assertEquals(0, table.size())
    }

    @Test
    fun aFailedConnectIsRetriedOnce_withinTheBudget() {
        val start = 0L
        val deadline = start + BleLinkRules.CONNECT_BUDGET_MS
        assertTrue(BleLinkRules.retryConnect(triesSoFar = 1, nowMs = start + 1_600L, deadlineMs = deadline))
        assertFalse(BleLinkRules.retryConnect(triesSoFar = 2, nowMs = start + 3_000L, deadlineMs = deadline))
        // No time left: no retry (the 5 s fallback takes over for a DM).
        assertFalse(BleLinkRules.retryConnect(triesSoFar = 1, nowMs = deadline - 100L, deadlineMs = deadline))
    }

    @Test
    fun onlyAReadyOpenLinkIsReused() {
        assertTrue(BleLinkRules.reuse(ready = true, closed = false))
        assertFalse(BleLinkRules.reuse(ready = false, closed = false))
        assertFalse(BleLinkRules.reuse(ready = true, closed = true))
    }

    @Test
    fun aLongGapStartsANewInboundTransfer() {
        assertFalse(BleLinkRules.startsNewTransfer(lastChunkMs = 0L, nowMs = 10_000L))
        assertFalse(BleLinkRules.startsNewTransfer(lastChunkMs = 10_000L, nowMs = 10_050L))
        assertTrue(BleLinkRules.startsNewTransfer(lastChunkMs = 10_000L, nowMs = 12_500L))
    }

    @Test
    fun statusNamesAndThePerPhoneLine() {
        assertEquals("status 133 (connect failed)", BleLinkRules.statusName(133))
        assertEquals("status 8 (link timeout: out of range)", BleLinkRules.statusName(8))
        assertEquals("status 99", BleLinkRules.statusName(99))
        val clock: (Long) -> String = { "t$it" }
        val s = BlePeerStats()
            .connectFailed("status 133 (connect failed)", 5L)
            .connectFailed("status 133 (connect failed)", 6L)
            .connected()
            .sent(7L)
        assertEquals("GATT: 1 sent · 3 connects (2 failed) · last OK t7 · last error status 133 (connect failed) t6", s.describe(clock))
        val failing = BlePeerStats().connectFailed("status 133 (connect failed)", 1L).connectFailed("status 133 (connect failed)", 2L)
        assertTrue(failing.describe(clock).endsWith("· 2 in a row"))
    }
}
