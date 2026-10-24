package com.dtn.mesh.receiver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix 3 (`_diag/fix3_report.md`): one BLE link per phone pair, used both ways. Role arbitration (the
 * lower id connects, the higher id after 3 s), the link a bundle takes (a link that is up now, else
 * Wi-Fi Direct at once: nothing waits), the connect back-off, indication chunking + reassembly, and
 * the server-link ↔ phone mapping through HELLO.
 */
class BleOneLinkTest {

    // The field pair: the user's Redmi (5c432f0e) and Kunal's OnePlus (c06853fe).
    private val redmi = 0x5c432f0e
    private val kunal = 0xc06853fe.toInt()

    private fun decide(
        iAmLower: Boolean,
        clientLink: Boolean = false,
        clientReady: Boolean = false,
        serverLink: Boolean = false,
        olderApp: Boolean = false,
        noLinkSinceMs: Long = 0L,
        backoffUntilMs: Long = 0L,
        nowMs: Long = 0L,
    ) = BleRoles.decide(iAmLower, clientLink, clientReady, serverLink, olderApp, noLinkSinceMs, backoffUntilMs, nowMs)

    // ── Role arbitration ─────────────────────────────────────────────────

    @Test
    fun idsCompareUnsigned_soOnlyTheRedmiConnectsToKunal() {
        assertTrue(BleRoles.isLower(redmi, kunal))
        assertFalse(BleRoles.isLower(kunal, redmi))
        // The high bit doesn't make an id "negative": 0x0cdc860b < 0x98fa6ade < 0xc06853fe.
        assertTrue(BleRoles.isLower(0x0cdc860b, 0x98fa6ade.toInt()))
        assertTrue(BleRoles.isLower(0x98fa6ade.toInt(), kunal))
        assertFalse(BleRoles.isLower(kunal, kunal))
    }

    @Test
    fun theLowerIdConnectsAtOnce_theHigherIdWaits3s_thenConnectsItself() {
        assertEquals(BleRoles.Decision.CONNECT_LOWER_ID, decide(iAmLower = true, noLinkSinceMs = 1_000L, nowMs = 1_000L))
        // The higher id: the lower id's link should appear within 3 s.
        val seen = 10_000L
        assertEquals(BleRoles.Decision.WAIT_FOR_PEER, decide(iAmLower = false, noLinkSinceMs = seen, nowMs = seen))
        assertEquals(BleRoles.Decision.WAIT_FOR_PEER, decide(iAmLower = false, noLinkSinceMs = seen, nowMs = seen + 2_999L))
        // It didn't (its connect failed, it's backing off, or it can't hear us): connect ourselves.
        val late = decide(iAmLower = false, noLinkSinceMs = seen, nowMs = seen + BleRoles.HIGHER_ID_WAIT_MS)
        assertEquals(BleRoles.Decision.CONNECT_PEER_SILENT, late)
        assertTrue(late.connects)
        assertFalse(BleRoles.Decision.WAIT_FOR_PEER.connects)
    }

    @Test
    fun neverASecondClientLink_toAPhoneLinkedInEitherRole() {
        // It connected to us (the field fix: the OnePlus never opens its own link to the Redmi then).
        assertEquals(BleRoles.Decision.HAVE_LINK, decide(iAmLower = false, serverLink = true, nowMs = 60_000L))
        assertEquals(BleRoles.Decision.HAVE_LINK, decide(iAmLower = true, serverLink = true))
        // We connected (up, or still coming up): no second connect either.
        assertEquals(BleRoles.Decision.HAVE_LINK, decide(iAmLower = true, clientLink = true, clientReady = true))
        assertEquals(BleRoles.Decision.CONNECTING, decide(iAmLower = true, clientLink = true))
        for (d in listOf(BleRoles.Decision.HAVE_LINK, BleRoles.Decision.CONNECTING)) assertFalse(d.connects)
    }

    @Test
    fun backingOffHoldsEvenTheLowerId_untilItRunsOut() {
        assertEquals(BleRoles.Decision.BACKING_OFF, decide(iAmLower = true, backoffUntilMs = 5_000L, nowMs = 4_999L))
        assertEquals(BleRoles.Decision.CONNECT_LOWER_ID, decide(iAmLower = true, backoffUntilMs = 5_000L, nowMs = 5_000L))
    }

    @Test
    fun anOlderAppIsConnectedToWithoutWaiting_bothSidesConnectAsBefore() {
        // Its server has no link characteristic: it can't receive indications, and it connects to us
        // for its own bundles, so the higher id doesn't wait for it.
        assertEquals(BleRoles.Decision.CONNECT_OLDER_APP, decide(iAmLower = false, olderApp = true, noLinkSinceMs = 0L, nowMs = 10L))
    }

    @Test
    fun aBundleTakesTheLinkTheLowerIdOpened_andAnyLinkThatIsUp() {
        // Both links up (they connected at the same instant): both phones use the lower id's link.
        assertEquals(BleRoles.Via.CENTRAL, BleRoles.sendVia(iAmLower = true, centralReady = true, peripheralReady = true))
        assertEquals(BleRoles.Via.PERIPHERAL, BleRoles.sendVia(iAmLower = false, centralReady = true, peripheralReady = true))
        // One link up: that one, whoever opened it.
        assertEquals(BleRoles.Via.PERIPHERAL, BleRoles.sendVia(iAmLower = true, centralReady = false, peripheralReady = true))
        assertEquals(BleRoles.Via.CENTRAL, BleRoles.sendVia(iAmLower = false, centralReady = true, peripheralReady = false))
        assertNull(BleRoles.sendVia(iAmLower = true, centralReady = false, peripheralReady = false))
    }

    @Test
    fun aDuplicateLinkIsClosedByTheHigherId_theLowerIdsLinkStays() {
        assertTrue(BleRoles.closeOursOnDuplicate(iAmLower = false))
        assertFalse(BleRoles.closeOursOnDuplicate(iAmLower = true))
    }

    @Test
    fun theFieldPair_convergesOnTheRedmisLink() {
        // Kunal (higher) sees the Redmi at t = 0 and waits; the Redmi (lower) connects at once.
        val redmiLower = BleRoles.isLower(redmi, kunal)
        val kunalLower = BleRoles.isLower(kunal, redmi)
        assertEquals(BleRoles.Decision.CONNECT_LOWER_ID, decide(iAmLower = redmiLower, nowMs = 0L))
        assertEquals(BleRoles.Decision.WAIT_FOR_PEER, decide(iAmLower = kunalLower, noLinkSinceMs = 0L, nowMs = 500L))
        // 700 ms later the Redmi's link is up (subscribed + HELLO on Kunal's server).
        assertEquals(BleRoles.Decision.HAVE_LINK, decide(iAmLower = kunalLower, serverLink = true, noLinkSinceMs = 0L, nowMs = 9_000L))
        // Both send over it: the Redmi writes, Kunal indicates.
        assertEquals(BleRoles.Via.CENTRAL, BleRoles.sendVia(redmiLower, centralReady = true, peripheralReady = false))
        assertEquals(BleRoles.Via.PERIPHERAL, BleRoles.sendVia(kunalLower, centralReady = false, peripheralReady = true))
    }

    // ── Link choice: a link that is up now, else Wi-Fi Direct; nothing waits ──

    private val direct = setOf(SendPath.BLE, SendPath.WIFI_DIRECT)

    @Test
    fun aReadyBleLinkGoesFirst_wifiDirectIsTheNextTry() {
        val plan = DirectLinkChoice.plan(direct, bleInRange = true, bleReady = true, wifiDirectLinked = true)
        assertEquals(listOf(SendPath.BLE, SendPath.WIFI_DIRECT), plan.order)
        assertFalse(plan.connectBle)
        assertEquals(listOf(SendPath.BLE), DirectLinkChoice.plan(direct, true, true, wifiDirectLinked = false).order)
    }

    @Test
    fun noBleLinkUpYet_wifiDirectAtOnce_whileBleConnectsInTheBackground() {
        // The field case: Kunal's DMs waited 25–44 s for BLE connects before Wi-Fi Direct got a turn.
        val plan = DirectLinkChoice.plan(direct, bleInRange = true, bleReady = false, wifiDirectLinked = true)
        assertEquals(listOf(SendPath.WIFI_DIRECT), plan.order)
        assertTrue(plan.connectBle)
    }

    @Test
    fun neitherLinkUp_nothingToWaitOn_theBleConnectStarts() {
        // An empty order = "not now": the send returns at once; the link coming up flushes, and the
        // 5 s fallback (hub / relay) takes over if it doesn't.
        val plan = DirectLinkChoice.plan(direct, bleInRange = true, bleReady = false, wifiDirectLinked = false)
        assertTrue(plan.order.isEmpty())
        assertTrue(plan.connectBle)
        // BLE never goes in the order unless its link is up.
        for (wfd in listOf(true, false)) for (inRange in listOf(true, false)) {
            assertFalse(SendPath.BLE in DirectLinkChoice.plan(direct, inRange, bleReady = false, wifiDirectLinked = wfd).order)
        }
    }

    @Test
    fun outOfBluetoothRange_orNotAllowed_noConnect() {
        val away = DirectLinkChoice.plan(direct, bleInRange = false, bleReady = false, wifiDirectLinked = true)
        assertEquals(listOf(SendPath.WIFI_DIRECT), away.order)
        assertFalse(away.connectBle)
        // A send restricted to Wi-Fi Direct never touches BLE; one restricted to BLE never Wi-Fi Direct.
        val wfdOnly = DirectLinkChoice.plan(setOf(SendPath.WIFI_DIRECT), bleInRange = true, bleReady = true, wifiDirectLinked = true)
        assertEquals(listOf(SendPath.WIFI_DIRECT), wfdOnly.order)
        assertFalse(wfdOnly.connectBle)
        val bleOnly = DirectLinkChoice.plan(setOf(SendPath.BLE), bleInRange = true, bleReady = false, wifiDirectLinked = true)
        assertTrue(bleOnly.order.isEmpty())
        assertTrue(bleOnly.connectBle)
    }

    // ── Back-off ─────────────────────────────────────────────────────────

    @Test
    fun backoffDoubles_1_2_4_8_16_thenStaysAt30s() {
        val b = ConnectBackoff()
        val waits = (1..8).map { b.failed("!5c432f0e", nowMs = 0L) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), waits)
        assertEquals(8, b.failuresInARow("!5c432f0e"))
        assertEquals(0L, b.delayMs(0))
        assertEquals(30_000L, b.delayMs(500))
    }

    @Test
    fun backoffIsPerPhone_andResetByALink() {
        val b = ConnectBackoff()
        assertEquals(1_000L, b.failed("!a", nowMs = 100L))
        assertEquals(1_100L, b.untilMs("!a"))
        assertEquals(2_000L, b.failed("!a", nowMs = 1_200L))
        assertEquals(3_200L, b.untilMs("!a"))
        // Another phone has its own count.
        assertEquals(1_000L, b.failed("!b", nowMs = 0L))
        // A link came up (ours, or it linked to us): the next failure starts at 1 s again.
        b.reset("!a")
        assertEquals(0L, b.untilMs("!a"))
        assertEquals(0, b.failuresInARow("!a"))
        assertEquals(1_000L, b.failed("!a", nowMs = 5_000L))
    }

    // ── Indication chunking + reassembly ─────────────────────────────────

    private fun wire(size: Int, seed: Int = 0) = ByteArray(size) { (it * 31 + seed).toByte() }

    @Test
    fun chunksAreMtuMinus3_within20To512() {
        assertEquals(20, BleFraming.chunkFor(23))
        assertEquals(182, BleFraming.chunkFor(185))
        assertEquals(244, BleFraming.chunkFor(247))
        assertEquals(512, BleFraming.chunkFor(517)) // an ATT value is at most 512 bytes
        assertEquals(20, BleFraming.chunkFor(0))
    }

    @Test
    fun aBundleIndicatedInMtuChunks_reassemblesExactly() {
        val bundle = wire(133)
        val framed = BleFraming.frame(bundle)
        assertEquals(135, framed.size)
        assertEquals(0, framed[0].toInt())
        assertEquals(133, framed[1].toInt() and 0xFF)
        for (mtu in listOf(23, 64, 185, 517)) {
            val chunks = BleFraming.chunks(framed, BleFraming.chunkFor(mtu))
            assertEquals((135 + BleFraming.chunkFor(mtu) - 1) / BleFraming.chunkFor(mtu), chunks.size)
            assertTrue(chunks.all { it.size <= BleFraming.chunkFor(mtu) })
            val rx = FrameAssembler()
            val out = chunks.dropLast(1).flatMap { rx.accept(it, nowMs = 1_000L) }
            assertTrue("no bundle before its last chunk (MTU $mtu)", out.isEmpty())
            val done = rx.accept(chunks.last(), nowMs = 1_010L)
            assertEquals(1, done.size)
            assertArrayEquals(bundle, done[0])
        }
    }

    @Test
    fun twoBundlesBackToBack_bothComeOut() {
        val a = wire(40, 1)
        val b = wire(255, 2) // the largest frame
        val stream = BleFraming.frame(a) + BleFraming.frame(b)
        val rx = FrameAssembler()
        val out = BleFraming.chunks(stream, 50).flatMap { rx.accept(it, nowMs = 5L) }
        assertEquals(2, out.size)
        assertArrayEquals(a, out[0])
        assertArrayEquals(b, out[1])
    }

    @Test
    fun aTornTransfer_isDroppedAfterTheGap_theNextBundleIsClean() {
        val torn = BleFraming.chunks(BleFraming.frame(wire(100, 3)), 20)
        val rx = FrameAssembler()
        rx.accept(torn[0], nowMs = 1_000L)
        rx.accept(torn[1], nowMs = 1_020L) // the link died here
        val next = wire(60, 4)
        val out = BleFraming.chunks(BleFraming.frame(next), 20).flatMap { rx.accept(it, nowMs = 4_000L) }
        assertEquals(1, out.size)
        assertArrayEquals(next, out[0])
    }

    @Test
    fun aCorruptLength_resetsTheBuffer_andTheNextBundleDecodes() {
        val rx = FrameAssembler()
        assertTrue(rx.accept(byteArrayOf(0x7F, 0x7F, 1, 2, 3), nowMs = 0L).isEmpty()) // 32639 B: not a bundle
        assertEquals(1, rx.badFrames)
        assertEquals(0x7F7F, rx.lastBadLength)
        assertTrue(rx.accept(byteArrayOf(0, 0), nowMs = 1L).isEmpty()) // length 0
        assertEquals(2, rx.badFrames)
        val ok = wire(10, 5)
        assertArrayEquals(ok, rx.accept(BleFraming.frame(ok), nowMs = 2L).single())
    }

    // ── Server link ↔ phone, via HELLO ───────────────────────────────────

    @Test
    fun helloCarriesTheNodeId_andRejectsWhatItCantRead() {
        val hello = BleHello.encode(kunal)
        assertEquals(BleHello.SIZE, hello.size)
        assertEquals(kunal, BleHello.decode(hello))
        assertEquals(redmi, BleHello.decode(BleHello.encode(redmi) + byteArrayOf(9, 9))) // later fields ignored
        assertNull(BleHello.decode(byteArrayOf(2) + hello.copyOfRange(1, 5))) // another version
        assertNull(BleHello.decode(hello.copyOfRange(0, 4)))
        assertNull(BleHello.decode(null))
        assertNull(BleHello.decode(BleHello.encode(0)))
        assertNull(BleHello.decode(BleHello.encode(-1)))
    }

    @Test
    fun aServerConnectionIsALink_onceSubscribedAndHelloed_inEitherOrder() {
        val t = ServerLinkTable()
        t.connected("AA:01")
        assertNull(t.readyAddress("!5c432f0e"))
        assertNull(t.subscribed("AA:01", on = true)) // subscribed, but who is it?
        assertEquals("!5c432f0e", t.hello("AA:01", "!5c432f0e"))
        assertEquals("AA:01", t.readyAddress("!5c432f0e"))
        assertEquals("!5c432f0e", t.nodeOf("AA:01"))
        // The other order.
        t.connected("BB:02")
        assertNull(t.hello("BB:02", "!0cdc860b"))
        assertEquals("!0cdc860b", t.subscribed("BB:02", on = true))
        assertEquals(setOf("!5c432f0e", "!0cdc860b"), t.readyNodes())
        // A repeated HELLO / subscription changes nothing (no second "link up").
        assertNull(t.hello("AA:01", "!5c432f0e"))
        assertNull(t.subscribed("AA:01", on = true))
    }

    @Test
    fun aBareConnection_neverCountsAsALink() {
        // Android reports our own client links to the server callback too, and an older app's client
        // never subscribes or says HELLO.
        val t = ServerLinkTable()
        t.connected("CC:03")
        t.mtu("CC:03", 517)
        assertTrue(t.readyNodes().isEmpty())
        assertNull(t.nodeOf("CC:03"))
        assertNull(t.disconnected("CC:03"))
    }

    @Test
    fun theMtuIsWhatTheCentralNegotiated_defaultUntilThen() {
        val t = ServerLinkTable()
        t.connected("AA:01")
        assertEquals(ServerLinkTable.DEFAULT_MTU, t.mtuOf("AA:01"))
        t.mtu("AA:01", 517)
        assertEquals(517, t.mtuOf("AA:01"))
        assertEquals(ServerLinkTable.DEFAULT_MTU, t.mtuOf("unknown"))
    }

    @Test
    fun aHelloFromANewerAddressTakesOver_theOldOnesDropIsSilent() {
        val t = ServerLinkTable()
        t.subscribed("AA:01", on = true)
        assertEquals("!5c432f0e", t.hello("AA:01", "!5c432f0e"))
        // The phone reconnected from a new address before the old connection was reported gone.
        t.subscribed("AA:02", on = true)
        assertEquals("!5c432f0e", t.hello("AA:02", "!5c432f0e"))
        assertEquals("AA:02", t.readyAddress("!5c432f0e"))
        assertNull(t.disconnected("AA:01")) // not its link any more: no "link down"
        assertEquals("AA:02", t.readyAddress("!5c432f0e"))
        assertEquals("!5c432f0e", t.disconnected("AA:02"))
        assertNull(t.readyAddress("!5c432f0e"))
    }

    @Test
    fun unsubscribing_endsTheLink() {
        val t = ServerLinkTable()
        t.subscribed("AA:01", on = true)
        t.hello("AA:01", "!5c432f0e")
        assertTrue(t.isSubscribed("AA:01"))
        assertNull(t.subscribed("AA:01", on = false))
        assertFalse(t.isSubscribed("AA:01"))
        assertNull(t.readyAddress("!5c432f0e"))
        // Subscribing again brings it back.
        assertEquals("!5c432f0e", t.subscribed("AA:01", on = true))
        t.clear()
        assertTrue(t.readyNodes().isEmpty())
    }
}
