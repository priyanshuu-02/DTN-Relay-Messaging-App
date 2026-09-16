package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProphetStrategyTest {

    private lateinit var prophet: ProphetStrategy

    @Before
    fun setup() {
        prophet = ProphetStrategy(ProphetConfig(
            pEncounter = 0.75,
            gammaAging = 0.98,
            betaTransitivity = 0.25,
            pDelta = 0.01,                 // RFC 6693 cap: P asymptotes to 1 − δ
            typicalEncounterIntervalMs = 0L, // disable adaptive scaling for deterministic tests
            agingIntervalMs = 1000L,       // 1s for fast test aging
            pMinThreshold = 0.01,
            maxForwards = 2,               // Small values so tests can drive the stage flip
            maxHops = 3,
        ))
    }

    /** ContactRecord helper — distance defaults to unknown so classic PROPHET applies. */
    private fun contact(peer: String, distanceM: Double = -1.0) = ContactRecord(
        peerId = NodeId(peer), startTimeMs = System.currentTimeMillis(),
        rssi = -60, snr = 10f, distanceMeters = distanceM,
    )

    private fun message(
        id: String,
        dest: NodeId,
        hopCount: Int = 0,
        forwardCount: Int = 0,
    ) = DtnMessage(
        id = id,
        originNodeId = NodeId("!local"),
        destinationNodeId = dest,
        payloadBytes = ByteArray(10),
        createdAtMs = System.currentTimeMillis(),
        ttlMs = 3_600_000L,
        hopCount = hopCount,
        forwardCount = forwardCount,
    )

    // ══════════════════════════════════════════════════════════════════════
    // Formula 1 — RFC 6693 direct-encounter update (δ cap + adaptive P_enc)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `first encounter applies P_encounter with the delta cap`() {
        val peer = NodeId("!aabb0001")
        prophet.onEncounter(peer, contact(peer.value))
        // P = 0 + (1 − δ − 0)·P_enc = (1 − 0.01)·0.75 = 0.7425
        assertEquals((1.0 - 0.01) * 0.75, prophet.getDeliveryProbability(peer), 0.001)
    }

    @Test
    fun `P never reaches 1 - it asymptotes to 1 minus delta`() {
        val peer = NodeId("!aabb0004")
        repeat(50) { prophet.onEncounter(peer, contact(peer.value)) }
        val p = prophet.getDeliveryProbability(peer)
        // Structurally capped at 1 − δ = 0.99; must be close but strictly below 1.0.
        assertTrue("P must stay below 1.0 (RFC delta cap)", p < 1.0)
        assertTrue("P should approach the 1 − δ cap after many encounters", p > 0.95)
    }

    @Test
    fun `adaptive scaling damps rapid re-encounters`() {
        // I_typ = 10s: a re-encounter 1s later gets only 10% of the boost.
        val fast = ProphetStrategy(ProphetConfig(
            pEncounter = 0.75, pDelta = 0.01, typicalEncounterIntervalMs = 10_000L,
        ))
        val peer = NodeId("!aabb00a1")
        val t0 = 1_000_000L
        fast.onEncounter(peer, ContactRecord(peer, startTimeMs = t0, rssi = -60, snr = 10f, distanceMeters = -1.0))
        val pAfterFirst = fast.getDeliveryProbability(peer)
        // Re-encounter 1s later: interval/I_typ = 0.1 → boost scaled to 10%.
        fast.onEncounter(peer, ContactRecord(peer, startTimeMs = t0 + 1_000L, rssi = -60, snr = 10f, distanceMeters = -1.0))
        val delta = fast.getDeliveryProbability(peer) - pAfterFirst
        // Full boost would add (0.99 − 0.7425)·0.75 ≈ 0.185; scaled boost is ~10% of that.
        assertTrue("rapid re-encounter boost must be small", delta in 0.0..0.05)
    }

    @Test
    fun `stability-aware variant never saturates from a single close ping`() {
        val stable = ProphetStrategy(ProphetConfig(stabilityAware = true, pDelta = 0.01))
        val peer = NodeId("!aabb00b1")
        stable.onEncounter(peer, ContactRecord(peer, startTimeMs = 1L, rssi = -40, snr = 20f, distanceMeters = 0.5))
        val p = stable.getDeliveryProbability(peer)
        assertTrue(p in 0.0..(1.0 - 0.01))
        assertTrue("one strong ping should give a modest, not maxed, boost", p < 0.75)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Formula 2 — Aging
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `aging reduces probability`() {
        val peer = NodeId("!aabb0005")
        prophet.onEncounter(peer, contact(peer.value))
        val before = prophet.getDeliveryProbability(peer)

        Thread.sleep(1100) // exceed aging interval (1s)
        prophet.onPeriodicAge()

        assertTrue(prophet.getDeliveryProbability(peer) < before)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Formula 3 — Transitivity + peer-P cache
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `transitivity updates our P for peers we've never met directly`() {
        val peerB = NodeId("!aabb0006")
        val peerC = NodeId("!aabb0099")
        prophet.onEncounter(peerB, contact(peerB.value))

        val summary = RoutingSummary("PROPHET", encodeFakePVector(mapOf(peerC.value to 0.9)))
        prophet.onRoutingSummaryReceived(peerB, summary)

        assertTrue(prophet.getDeliveryProbability(peerC) > 0.0)
    }

    @Test
    fun `routing summary populates the peer-P cache for stage-2 lookups`() {
        val peerB = NodeId("!aabb0007")
        val dest = NodeId("!aabb0100")
        val summary = RoutingSummary("PROPHET", encodeFakePVector(mapOf(dest.value to 0.8)))
        prophet.onRoutingSummaryReceived(peerB, summary)

        assertEquals(0.8, prophet.peerDeliveryProbability(peerB, dest), 0.001)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Two-stage routing
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `direct delivery bypasses stage-check but still respects hop-limit`() {
        val peer = NodeId("!aabb0010")
        val exhausted = message("m-runaway-direct", dest = peer, hopCount = 99, forwardCount = 99)
        // The hop-limit guard fires before the direct-delivery bypass — a runaway message
        // that has already ricocheted through too many hops is never re-forwarded.
        assertTrue(prophet.rankForForwarding(listOf(exhausted), peer).isEmpty())
    }

    @Test
    fun `direct delivery goes through when hop limit is intact and forwardCount is high`() {
        val peer = NodeId("!aabb0010b")
        val msg = message("m-direct", dest = peer, hopCount = 0, forwardCount = 99)
        val ranked = prophet.rankForForwarding(listOf(msg), peer)
        assertTrue(ranked.isNotEmpty())
        assertEquals("m-direct", ranked.first().message.id)
    }

    @Test
    fun `stage 1 epidemic - forwards even to peer with unknown P`() {
        val peer = NodeId("!aabb0011")
        val dest = NodeId("!aabb0101")
        // No routing-summary received from peer → peerP=0. In Stage 1 that still forwards.
        val msg = message("m-stage1", dest = dest, hopCount = 1, forwardCount = 0)
        val ranked = prophet.rankForForwarding(listOf(msg), peer)
        assertTrue("Stage 1 must forward regardless of peer-P knowledge", ranked.isNotEmpty())
    }

    @Test
    fun `stage 2 blocks forwarding when peer has no better P than us`() {
        val peer = NodeId("!aabb0012")
        val dest = NodeId("!aabb0102")

        // We have a good P for dest via transitivity through some other peer.
        val other = NodeId("!aabb0013")
        prophet.onEncounter(other, contact(other.value))
        prophet.onRoutingSummaryReceived(other,
            RoutingSummary("PROPHET", encodeFakePVector(mapOf(dest.value to 0.95))))
        val myP = prophet.getDeliveryProbability(dest)

        // Peer j reports lower P(j, dest) than ours.
        prophet.onRoutingSummaryReceived(peer,
            RoutingSummary("PROPHET", encodeFakePVector(mapOf(dest.value to myP * 0.5))))

        // Message has crossed Stage 1 gates (forwardCount > maxForwards).
        val msg = message("m-stage2", dest = dest, hopCount = 1, forwardCount = 5)
        val ranked = prophet.rankForForwarding(listOf(msg), peer)
        assertTrue("Stage 2 must skip peer with lower P(j,D)", ranked.isEmpty())
    }

    @Test
    fun `stage 2 allows forwarding when peer has strictly better P`() {
        val peer = NodeId("!aabb0014")
        val dest = NodeId("!aabb0103")
        prophet.onRoutingSummaryReceived(peer,
            RoutingSummary("PROPHET", encodeFakePVector(mapOf(dest.value to 0.9))))
        val msg = message("m-stage2-ok", dest = dest, hopCount = 1, forwardCount = 5)
        val ranked = prophet.rankForForwarding(listOf(msg), peer)
        assertTrue(ranked.isNotEmpty())
    }

    @Test
    fun `hop-limit drops runaway messages`() {
        val peer = NodeId("!aabb0015")
        val dest = NodeId("!aabb0104")
        val msg = message("m-runaway", dest = dest, hopCount = 3, forwardCount = 0) // hopCount == maxHops
        val ranked = prophet.rankForForwarding(listOf(msg), peer)
        assertTrue("hopCount >= maxHops must be filtered out", ranked.isEmpty())
    }

    @Test
    fun `broadcast is always forwarded (regardless of peer knowledge)`() {
        val peer = NodeId("!aabb0016")
        val msg = message("m-bcast", dest = NodeId.BROADCAST, hopCount = 0, forwardCount = 0)
        val ranked = prophet.rankForForwarding(listOf(msg), peer)
        assertTrue(ranked.isNotEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════
    // State persistence
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `export and import state preserves P-table`() {
        val peer = NodeId("!aabb0020")
        prophet.onEncounter(peer, contact(peer.value))
        val pBefore = prophet.getDeliveryProbability(peer)

        val state = prophet.exportState()
        val restored = ProphetStrategy()
        restored.importState(state)

        assertEquals(pBefore, restored.getDeliveryProbability(peer), 0.0001)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun encodeFakePVector(entries: Map<String, Double>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(bos)
        dos.writeInt(entries.size)
        for ((k, v) in entries) { dos.writeUTF(k); dos.writeDouble(v) }
        dos.flush()
        return bos.toByteArray()
    }
}
