package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRoPHET against RFC 6693 (Phase 9): eq. 3's max form, and GRTR on top of the live-path tier. */
class ProphetRfc6693Test {

    private val me = "!0000000a"
    private val b = "!0000000b"
    private val c = "!0000000c"
    private val d = "!0000000d"

    private fun summary(vararg entries: Pair<String, Double>): RoutingSummary {
        val bos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(bos)
        dos.writeInt(entries.size)
        for ((k, v) in entries) { dos.writeUTF(k); dos.writeDouble(v) }
        return RoutingSummary("PROPHET", bos.toByteArray())
    }

    private fun meet(p: ProphetStrategy, peer: String, at: Long) =
        p.onEncounter(NodeId(peer), ContactRecord(peerId = NodeId(peer), startTimeMs = at, rssi = -60, snr = 0f))

    private fun dm(to: String, copies: Int = 0) = DtnMessage(
        id = "m-$to-$copies", originNodeId = NodeId(me), destinationNodeId = NodeId(to), payloadBytes = ByteArray(8),
        createdAtMs = System.currentTimeMillis(), ttlMs = 3_600_000L, forwardCount = copies,
    )

    @Test
    fun transitivity_isTheMaxOfOldAndNew_soRepeatedExchangesDontInflateIt() {
        val p = ProphetStrategy().apply { selfId = me }
        meet(p, b, 0L)
        val pAB = p.getDeliveryProbability(NodeId(b))
        // Phones in range trade summaries every 8 s: the same report twenty times.
        repeat(20) { p.onRoutingSummaryReceived(NodeId(b), summary(c to 0.9)) }
        assertEquals(pAB * 0.9 * p.config.betaTransitivity, p.getDeliveryProbability(NodeId(c)), 1e-12)
        // A weaker report never lowers it; a stronger one raises it to exactly the new product.
        p.onRoutingSummaryReceived(NodeId(b), summary(c to 0.2))
        assertEquals(pAB * 0.9 * p.config.betaTransitivity, p.getDeliveryProbability(NodeId(c)), 1e-12)
        p.onRoutingSummaryReceived(NodeId(b), summary(c to 0.99))
        assertEquals(pAB * 0.99 * p.config.betaTransitivity, p.getDeliveryProbability(NodeId(c)), 1e-12)
    }

    @Test
    fun aNeighboursVector_neverGivesUsAPredictabilityForOurselves() {
        val p = ProphetStrategy().apply { selfId = me }
        meet(p, b, 0L)
        p.onRoutingSummaryReceived(NodeId(b), summary(me to 0.9, c to 0.5))
        assertFalse(me in p.getPTable())
        assertTrue(c in p.getPTable())
    }

    @Test
    fun aBetterCarrier_isNotTurnedAway_whenTheLivePathGoesThroughAnotherPeer() {
        val p = ProphetStrategy(ProphetConfig(typicalEncounterIntervalMs = 0L)).apply { selfId = me }
        meet(p, b, 0L); meet(p, c, 0L)
        // b reports d just above the path floor, c reports it much higher; we know d only a little.
        p.onRoutingSummaryReceived(NodeId(b), summary(d to 0.12))
        p.onRoutingSummaryReceived(NodeId(c), summary(d to 0.8))
        val online = linkedSetOf(b, c) // b is reached first by the path search
        assertEquals(b, p.findNextHopToward(d, online))
        val msg = dm(d, copies = 9) // past stage 1: GRTR decides
        assertEquals(1, p.rankForForwardingWithTopology(listOf(msg), NodeId(b), online).size)
        assertEquals(1, p.rankForForwardingWithTopology(listOf(msg), NodeId(c), online).size) // P(c,d) > P(me,d)
        assertTrue(p.explain(msg, NodeId(c), online).contains("better carrier too"))
    }

    @Test
    fun theDestinationInRange_getsItDirectly_noCopyForAnyoneElse() {
        val p = ProphetStrategy().apply { selfId = me }
        meet(p, b, 0L); meet(p, d, 0L)
        p.onRoutingSummaryReceived(NodeId(b), summary(d to 0.99))
        val online = linkedSetOf(b, d)
        assertTrue(p.rankForForwardingWithTopology(listOf(dm(d)), NodeId(b), online).isEmpty())
        assertEquals(1, p.rankForForwardingWithTopology(listOf(dm(d)), NodeId(d), online).size)
    }
}
