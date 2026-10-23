package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DiMaxProp: the paper's metric (eq. 9–11), its fallback, the forwarding rule and the summary format. */
class DiMaxPropStrategyTest {

    private val me = "!00000001"
    private val b = "!0000000b"
    private val c = "!0000000c"
    private val d = "!0000000d"

    private fun meet(s: RoutingStrategy, peer: String, times: Int = 1, distanceM: Double = -1.0) = repeat(times) {
        s.onEncounter(NodeId(peer), ContactRecord(
            peerId = NodeId(peer), startTimeMs = 0L, rssi = -70, snr = 0f, distanceMeters = distanceM,
            radio = if (distanceM >= 0) RadioDistanceEstimator.Transport.BLE else null,
        ))
    }

    /** A neighbour that has met [contacts] (node → times) and reports them in its summary. */
    private fun neighbourSummary(vararg contacts: Pair<String, Int>, distances: Map<String, Double> = emptyMap()): RoutingSummary {
        val n = DiMaxPropStrategy()
        for ((id, times) in contacts) meet(n, id, times, distances[id] ?: -1.0)
        return n.buildRoutingSummary()
    }

    private fun dm(dest: String, hops: Int = 0) = DtnMessage(
        id = "m-$dest-$hops", originNodeId = NodeId(me), destinationNodeId = NodeId(dest),
        payloadBytes = ByteArray(10), createdAtMs = System.currentTimeMillis(), ttlMs = 3_600_000L,
        messageType = DtnMessageType.DATA, hopCount = hops,
    )

    // ── The metric ───────────────────────────────────────────────────────

    @Test
    fun edgeWeight_isMaxPropsCostWithoutDistanceOrWithLambdaZero() {
        assertEquals(0.3, DiMaxPropStrategy.edgeWeight(0.7, null, 0.5), 1e-9)
        assertEquals(0.3, DiMaxPropStrategy.edgeWeight(0.7, 0.9, 0.0), 1e-9)
    }

    @Test
    fun edgeWeight_addsTheDistancePenalty() {
        assertEquals(0.3 + 0.5 * 0.4, DiMaxPropStrategy.edgeWeight(0.7, 0.4, 0.5), 1e-9)
    }

    @Test
    fun edgeWeight_isNeverNegativeAndBoundedByOnePlusLambda() {
        for (f in listOf(-1.0, 0.0, 0.5, 1.0, 2.0)) for (dn in listOf(null, -1.0, 0.0, 0.5, 1.0, 3.0)) {
            val w = DiMaxPropStrategy.edgeWeight(f, dn, 0.7)
            assertTrue("w=$w for f=$f d=$dn", w >= 0.0 && w <= 1.7 + 1e-9)
        }
    }

    // ── λ = 0 reproduces MaxProp ─────────────────────────────────────────

    @Test
    fun lambdaZero_givesMaxPropsCostAndFirstHop() {
        val maxProp = MaxPropStrategy()
        val diMaxProp = DiMaxPropStrategy(DiMaxPropConfig(lambda = 0.0)).apply { selfId = me }
        // We meet B often and C rarely; B has met D a lot, C a little. Distances present but λ = 0.
        for (s in listOf(maxProp, diMaxProp)) {
            meet(s, b, 3, distanceM = 30.0)
            meet(s, c, 1, distanceM = 2.0)
        }
        val mpPeer = MaxPropStrategy().apply { meet(this, d, 4); meet(this, c, 1) }
        maxProp.onRoutingSummaryReceived(NodeId(b), mpPeer.buildRoutingSummary())
        diMaxProp.onRoutingSummaryReceived(NodeId(b), neighbourSummary(d to 4, c to 1))

        val online = setOf(b, c)
        val (mpHop, mpCost) = maxProp.findMinCostNextHop(d, online)
        val path = diMaxProp.costFromMe(d)
        assertEquals(mpHop, path.firstHop)
        // Summary values travel as bytes (f × 255), so allow that rounding.
        assertEquals(mpCost, path.cost, 0.01)
    }

    // ── Distance changes the choice ──────────────────────────────────────

    @Test
    fun theNearerOfTwoEqualRelaysWins_whenLambdaIsPositive() {
        // No transitive seeding here, so the only paths to D are the two relays.
        val s = DiMaxPropStrategy(DiMaxPropConfig(lambda = 0.5, transitivityDiscount = 0.0)).apply { selfId = me }
        meet(s, b, 2, distanceM = 40.0) // far: d_norm 0.8
        meet(s, c, 2, distanceM = 2.0)  // near: d_norm 0.04
        s.onRoutingSummaryReceived(NodeId(b), neighbourSummary(d to 1))
        s.onRoutingSummaryReceived(NodeId(c), neighbourSummary(d to 1))
        assertEquals(c, s.costFromMe(d).firstHop)

        s.lambda = 0.0
        val viaB = s.edgeInfo(DiMaxPropStrategy.ME_LABEL, b)!!.weight
        val viaC = s.edgeInfo(DiMaxPropStrategy.ME_LABEL, c)!!.weight
        assertEquals(viaB, viaC, 1e-9) // no distance term: the two relays cost the same
    }

    @Test
    fun aLinkWithoutDistance_usesPlainMaxPropCost() {
        val s = DiMaxPropStrategy().apply { selfId = me }
        meet(s, b, 1) // no distance (e.g. met over Wi-Fi Direct)
        val e = s.edgeInfo(DiMaxPropStrategy.ME_LABEL, b)!!
        assertNull(e.dNorm)
        assertEquals(1.0 - e.f, e.weight, 1e-9)
    }

    @Test
    fun mixedPath_appliesTheDistanceTermOnlyWhereKnown() {
        val s = DiMaxPropStrategy(DiMaxPropConfig(lambda = 0.5, transitivityDiscount = 0.0)).apply { selfId = me }
        meet(s, b, 1, distanceM = 25.0) // me → B has a distance (0.5)
        s.onRoutingSummaryReceived(NodeId(b), neighbourSummary(d to 1)) // B → D has none
        val p = s.costFromMe(d)
        val meB = s.edgeInfo(DiMaxPropStrategy.ME_LABEL, b)!!
        val bD = s.edgeInfo(b, d)!!
        assertNotNull(meB.dNorm)
        assertNull(bD.dNorm)
        assertEquals(meB.weight + bD.weight, p.cost, 1e-9)
        assertEquals(listOf(b, d), p.nodes.drop(1))
    }

    // ── Forwarding rule ──────────────────────────────────────────────────

    @Test
    fun forwardsToACarrierThatIsCloserToTheDestination() {
        val s = DiMaxPropStrategy().apply { selfId = me }
        meet(s, b, 1)
        s.onRoutingSummaryReceived(NodeId(b), neighbourSummary(d to 5))
        val decision = s.decide(d, b)
        assertEquals(DiMaxPropStrategy.Verdict.FORWARD, decision.verdict)
        assertTrue(decision.theirs.cost < decision.mine.cost)
        assertEquals(1, s.rankForForwardingWithTopology(listOf(dm(d)), NodeId(b), setOf(b)).size)
    }

    @Test
    fun holdsWhenThisPhoneIsTheBetterCarrier() {
        val s = DiMaxPropStrategy().apply { selfId = me }
        meet(s, d, 5) // we meet D a lot
        meet(s, b, 1)
        s.onRoutingSummaryReceived(NodeId(b), neighbourSummary(c to 3)) // B never met D
        assertEquals(DiMaxPropStrategy.Verdict.HOLD, s.decide(d, b).verdict)
        assertTrue(s.rankForForwardingWithTopology(listOf(dm(d)), NodeId(b), setOf(b)).isEmpty())
    }

    @Test
    fun coldStart_sendsALowPriorityExploratoryCopy() {
        val s = DiMaxPropStrategy().apply { selfId = me }
        meet(s, b, 1)
        assertEquals(DiMaxPropStrategy.Verdict.EXPLORE, s.decide(d, b).verdict)
        val ranked = s.rankForForwardingWithTopology(listOf(dm(d)), NodeId(b), setOf(b))
        assertEquals(1, ranked.size)
        assertTrue(ranked.single().priority < 0.05)
    }

    @Test
    fun hopLimitAndDirectAndBroadcastRules() {
        val s = DiMaxPropStrategy().apply { selfId = me }
        meet(s, b, 1)
        assertTrue(s.rankForForwardingWithTopology(listOf(dm(d, hops = 10)), NodeId(b), setOf(b)).isEmpty())
        assertEquals(1, s.rankForForwardingWithTopology(listOf(dm(b)), NodeId(b), setOf(b)).size)
        val bcast = dm(d).copy(destinationNodeId = NodeId.BROADCAST)
        assertEquals(1, s.rankForForwardingWithTopology(listOf(bcast), NodeId(b), setOf(b)).size)
    }

    @Test
    fun aNeighbourCannotRouteABundleBackThroughUs() {
        val s = DiMaxPropStrategy().apply { selfId = me }
        meet(s, b, 1)
        // B only knows us; we have met D. From B's side there must be no path "B → me → D".
        meet(s, d, 1)
        s.onRoutingSummaryReceived(NodeId(b), neighbourSummary(me to 3))
        assertFalse(s.costFromPeer(b, d).reachable)
    }

    @Test
    fun explain_namesTheNumbersAndTheDecision() {
        val s = DiMaxPropStrategy().apply { selfId = me }
        meet(s, b, 1, distanceM = 10.0)
        s.onRoutingSummaryReceived(NodeId(b), neighbourSummary(d to 5))
        val text = s.explain(dm(d), NodeId(b), setOf(b))
        assertTrue(text, text.startsWith("DiMaxProp · λ 0.50"))
        assertTrue(text, text.contains("Decision: forward"))
        assertTrue(text, text.contains("me → 0000000b"))
    }

    // ── Summary format ───────────────────────────────────────────────────

    @Test
    fun summary_roundTrips_withinOneByteOfPrecision() {
        val f = mapOf(b to 0.6, c to 0.3, d to 0.1)
        val dist = mapOf(b to 0.25)
        val bytes = DiMaxPropStrategy.encodeSummary(0.4, f, dist)
        assertEquals(4 + 3 * 6, bytes.size)
        assertTrue(DiMaxPropStrategy.isSummary(bytes))
        val s = DiMaxPropStrategy.decodeSummary(bytes)!!
        assertEquals(0.4, s.bufferOccupancy, 0.005)
        for ((k, v) in f) assertEquals(v, s.f[k]!!, 0.005)
        assertEquals(0.25, s.d[b]!!, 0.005)
        assertNull(s.d[c])
    }

    @Test
    fun summary_fitsOneLoRaFrame_andKeepsTheLikeliestContacts() {
        val f = (1..60).associate { "!%08x".format(it) to it / 100.0 }
        val bytes = DiMaxPropStrategy.encodeSummary(0.0, f, emptyMap())
        assertTrue(bytes.size <= DtnMessage.MAX_SINGLE_PACKET_PAYLOAD)
        val s = DiMaxPropStrategy.decodeSummary(bytes)!!
        assertEquals(DiMaxPropStrategy.MAX_ENTRIES, s.f.size)
        assertTrue("!%08x".format(60) in s.f)
        assertFalse("!%08x".format(1) in s.f)
    }

    @Test
    fun otherSummaryFormats_areNotMistakenForDiMaxProp() {
        val prophetLike = ProphetStrategy().apply { meet(this, b, 2) }.buildRoutingSummary().payload
        val maxPropLike = MaxPropStrategy().apply { meet(this, b, 2) }.buildRoutingSummary().payload
        assertFalse(DiMaxPropStrategy.isSummary(prophetLike))
        assertFalse(DiMaxPropStrategy.isSummary(maxPropLike))
        assertNull(DiMaxPropStrategy.decodeSummary(byteArrayOf(0xD1.toByte(), 1, 0, 5, 1, 2)))
    }

    @Test
    fun selector_qLearningSharesTheDiMaxPropSummary() {
        val sel = StrategySelector()
        sel.switchTo(StrategySelector.StrategyType.QLEARNING)
        assertEquals(DiMaxPropStrategy.TAG, sel.buildRoutingSummary().strategyTag)
        sel.switchTo(StrategySelector.StrategyType.DIMAXPROP)
        assertEquals("DIMAXPROP", sel.name)
        sel.switchTo(StrategySelector.StrategyType.PROPHET)
        assertEquals("PROPHET", sel.buildRoutingSummary().strategyTag)
    }
}
