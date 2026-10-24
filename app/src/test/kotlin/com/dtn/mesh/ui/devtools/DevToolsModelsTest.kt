package com.dtn.mesh.ui.devtools

import com.dtn.mesh.learning.BenchmarkStat
import com.dtn.mesh.service.LifecycleEntry
import com.dtn.mesh.service.LifecycleEvent
import com.dtn.mesh.service.MessageLifecycleLog.RouteRecord
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.queue.BufferHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevToolsModelsTest {

    private val me = "0cdc860b"
    private val bob = "1a2b3c4d"
    private val carrier = "2b3c4d5e"

    private fun event(id: String, event: LifecycleEvent, extra: String = "", origin: String = me, dest: String = bob, at: Long = 1_000L) =
        LifecycleEntry(timestamp = "Oct 24 10:00:00", event = event, msgId = id, origin = origin, dest = dest, hopCount = 1,
            forwardCount = -1, ttlRemainingMin = 239, extra = extra, epochMs = at)

    /** Newest first, as the journal keeps them. */
    private val entries = listOf(
        event("aaaa0001", LifecycleEvent.ACK_RECEIVED, "delivery receipt from $bob via BLE", at = 5_000L),
        event("aaaa0001", LifecycleEvent.FORWARDED, "handed to carrier $carrier via Wi-Fi Direct · awaiting receipt [PROPHET]", at = 4_000L),
        event("bbbb0002", LifecycleEvent.EXPIRED, "TTL ran out", origin = carrier, at = 3_000L),
        event("aaaa0001", LifecycleEvent.BUFFERED, "queued — waiting for a link toward the recipient", at = 2_000L),
        event("aaaa0001", LifecycleEvent.CREATED, "direct message (end-to-end encrypted)", at = 1_000L),
    )

    // ── Event Log ───────────────────────────────────────────────────────────

    @Test
    fun search_byMessageId_nodeId_eventOrDetail() {
        assertEquals(4, EventLogModel.visible(entries, LifecycleFilter.ALL, "aaaa").size)
        assertEquals(listOf("bbbb0002"), EventLogModel.visible(entries, LifecycleFilter.ALL, "BBBB0002").map { it.msgId })
        // A node id, with or without "!", as origin, destination or in the detail.
        assertEquals(5, EventLogModel.visible(entries, LifecycleFilter.ALL, "!$bob").size)
        assertEquals(2, EventLogModel.visible(entries, LifecycleFilter.ALL, carrier).size)
        // The event's name or its title, and words of the detail.
        assertEquals(listOf(LifecycleEvent.EXPIRED), EventLogModel.visible(entries, LifecycleFilter.ALL, "expired").map { it.event })
        assertEquals(listOf(LifecycleEvent.ACK_RECEIVED), EventLogModel.visible(entries, LifecycleFilter.ALL, "receipt received").map { it.event })
        assertEquals(1, EventLogModel.visible(entries, LifecycleFilter.ALL, "wi-fi direct").size)
        assertEquals(5, EventLogModel.visible(entries, LifecycleFilter.ALL, "  ").size)
    }

    @Test
    fun search_combinesWithTheChips() {
        assertEquals(listOf(LifecycleEvent.FORWARDED, LifecycleEvent.BUFFERED, LifecycleEvent.CREATED),
            EventLogModel.visible(entries, LifecycleFilter.FLOW, "aaaa0001").map { it.event })
        assertEquals(listOf(LifecycleEvent.ACK_RECEIVED), EventLogModel.visible(entries, LifecycleFilter.ACK, "").map { it.event })
        assertEquals(listOf("bbbb0002"), EventLogModel.visible(entries, LifecycleFilter.ISSUES, "").map { it.msgId })
        assertTrue(EventLogModel.visible(entries, LifecycleFilter.ISSUES, "aaaa0001").isEmpty())
    }

    @Test
    fun stripes_blueCreated_orangeBufferedOrHandedOn_greenDelivered_redIssues() {
        assertEquals(Tone.BLUE, EventLogModel.tone(event("a", LifecycleEvent.CREATED)))
        assertEquals(Tone.ORANGE, EventLogModel.tone(event("a", LifecycleEvent.BUFFERED)))
        assertEquals(Tone.ORANGE, EventLogModel.tone(event("a", LifecycleEvent.FORWARDED, "handed to carrier $carrier via BLE")))
        assertEquals(Tone.GREEN, EventLogModel.tone(event("a", LifecycleEvent.FORWARDED, "direct to $bob via BLE ✓ delivered [PROPHET]")))
        assertEquals(Tone.GREEN, EventLogModel.tone(event("a", LifecycleEvent.ACK_GENERATED)))
        assertEquals(Tone.GREEN, EventLogModel.tone(event("a", LifecycleEvent.BUFFER_CLEARED)))
        assertEquals(Tone.RED, EventLogModel.tone(event("a", LifecycleEvent.EXPIRED)))
        assertEquals(Tone.RED, EventLogModel.tone(event("a", LifecycleEvent.DROPPED)))
        assertEquals(Tone.GREY, EventLogModel.tone(event("a", LifecycleEvent.DUPLICATE)))
    }

    @Test
    fun cardTexts() {
        assertEquals("FROM $me → TO $bob", EventLogModel.endpoints(entries.last()))
        assertEquals("FROM $me", EventLogModel.endpoints(event("a", LifecycleEvent.ACK_RECEIVED, dest = "")))
        assertNull(EventLogModel.endpoints(event("a", LifecycleEvent.ACK_GENERATED, origin = "", dest = "")))
        assertEquals("direct message (end-to-end encrypted) · Hops 1 · TTL 239m", EventLogModel.detail(entries.last()))
    }

    @Test
    fun exportEvents_isTheFilteredList_oldestFirst() {
        val shown = EventLogModel.visible(entries, LifecycleFilter.FLOW, "aaaa0001")
        val text = EventLogModel.exportText(shown, LifecycleFilter.FLOW, "aaaa0001", "Sat, 24 Oct 2026 · 10:00:00")
        val body = text.trimEnd().lines().filter { it.startsWith("[") }
        assertEquals(3, body.size)
        assertTrue(body[0].contains("CREATED"))
        assertTrue(body[2].contains("FORWARDED"))
        assertTrue(text.contains("Filter: Flow · search \"aaaa0001\" · 3 events, oldest first"))
        assertFalse(text.contains("EXPIRED"))
    }

    // ── Benchmarks ──────────────────────────────────────────────────────────

    @Test
    fun benchmarkTable_bestRateFirst_thenDeliveries_rowsWithoutARateLast() {
        val rows = BenchmarkModel.sorted(listOf(
            BenchmarkStat("PROPHET", handled = 10, deliveries = 5),
            BenchmarkStat("EPIDEMIC", handled = 10, deliveries = 9),
            BenchmarkStat("MAXPROP", handled = 0, forwards = 3),
            BenchmarkStat("DIMAXPROP", handled = 20, deliveries = 10),
        ))
        assertEquals(listOf("EPIDEMIC", "DIMAXPROP", "PROPHET", "MAXPROP"), rows.map { it.strategy })
        assertEquals("90%", BenchmarkModel.rate(rows[0]))
        assertEquals("—", BenchmarkModel.rate(rows[3]))
        assertEquals("—", BenchmarkModel.delay(rows[3]))
        assertEquals("—", BenchmarkModel.hops(rows[3]))
        assertEquals("—", BenchmarkModel.overhead(rows[3]))
    }

    @Test
    fun benchmarkCells() {
        val s = BenchmarkStat("Q-LEARNING", handled = 3, forwards = 9, deliveries = 2, sumHops = 5, hopSamples = 2,
            sumLatencyMs = 8_400, latencySamples = 2)
        assertEquals("67%", BenchmarkModel.rate(s))
        assertEquals("%.1f s".format(4.2), BenchmarkModel.delay(s))
        assertEquals("%.1f".format(2.5), BenchmarkModel.hops(s))
        assertEquals("%.1f".format(4.5), BenchmarkModel.overhead(s))
        assertEquals("850 ms", BenchmarkModel.delay(BenchmarkStat("X", sumLatencyMs = 850, latencySamples = 1)))
        assertEquals(listOf("Q-learning"), BenchmarkModel.needMoreData(listOf(s, BenchmarkStat("PROPHET", handled = 12))))
    }

    @Test
    fun algorithms_matchBothTheSelectorAndTheStrategyNames() {
        assertEquals(6, Algorithms.all.size)
        assertEquals("Q-learning", Algorithms.label("Q-LEARNING"))
        assertEquals("Q-learning", Algorithms.label("QLEARNING"))
        assertEquals("Stable PRoPHET", Algorithms.label("STABLE-PROPHET"))
        assertEquals("Stable PRoPHET", Algorithms.label("STABLE"))
        assertEquals("DIMAXPROP", Algorithms.find("DiMaxProp")?.type)
        assertEquals("INTERNET RELAY", Algorithms.label("INTERNET RELAY"))
        assertTrue(Algorithms.scoreCaption("STABLE").startsWith("P ="))
        assertTrue(Algorithms.scoreCaption("Q-LEARNING").startsWith("Q ="))
    }

    // ── Buffer History ──────────────────────────────────────────────────────

    @Test
    fun historyChips_splitByHowEachRecordLeft() {
        fun item(id: String, ev: LifecycleEvent?) = BufferHistoryItem(id, me, bob, "Oct 24 10:00:00", null, ev, "")
        val records = listOf(
            item("d1", LifecycleEvent.DELIVERED), item("d2", LifecycleEvent.BUFFER_CLEARED), item("e1", LifecycleEvent.EXPIRED),
            item("h1", LifecycleEvent.HOP_LIMIT), item("x1", LifecycleEvent.DROPPED), item("s1", null), item("r1", null),
        )
        // A record with no clearing event whose route was delivered (e.g. after Clear buffer) counts as delivered.
        val routes = mapOf("r1" to RouteRecord(msgId = "r1", source = me, destination = bob, me = me, delivered = true))
        assertEquals(listOf("d1", "d2", "r1"), HistoryFilter.select(records, routes, HistoryFilter.DELIVERED).map { it.msgId })
        assertEquals(listOf("e1"), HistoryFilter.select(records, routes, HistoryFilter.EXPIRED).map { it.msgId })
        assertEquals(listOf("h1", "x1"), HistoryFilter.select(records, routes, HistoryFilter.DROPPED).map { it.msgId })
        assertEquals(7, HistoryFilter.select(records, routes, HistoryFilter.ALL).size)
    }

    // ── Routing Engine ──────────────────────────────────────────────────────

    @Test
    fun engineSummary_splitsSentRelayedReceived() {
        val routes = listOf(
            RouteRecord(msgId = "s1", source = me, destination = bob, me = me, delivered = true),
            RouteRecord(msgId = "s2", source = me, destination = bob, me = me),
            RouteRecord(msgId = "r1", source = carrier, destination = bob, me = me, delivered = true),
            RouteRecord(msgId = "i1", source = bob, destination = me, me = me, delivered = true),
        )
        val peers = listOf(PeerInfo(nodeId = "!$bob", isOnline = true, lastSeenMs = 1L), PeerInfo(nodeId = "!$carrier", isOnline = false, lastSeenMs = 1L))
        val s = EngineSummary.of(routes, peers, me)
        assertEquals(1, s.sentDelivered)
        assertEquals(1, s.relayedDelivered)
        assertEquals(1, s.received)
        assertEquals(3, s.resolved)
        assertEquals(1, s.inTransit)
        assertEquals(1, s.activePeers)
        assertEquals(2, s.knownPeers)
        assertEquals("Not reachable now", EngineSummary.reach(peers[1]))
    }

    @Test
    fun calculationLines_areTheAlgorithmMath() {
        assertTrue(isCalculationLine("  P(me→1a2b3c4d) 0.42 → 0.61"))
        assertTrue(isCalculationLine("  C(me,1a2b3c4d)=0.83 via 2b3c4d5e"))
        assertFalse(isCalculationLine("ENCOUNTER 1a2b3c4d via BLE"))
    }
}
