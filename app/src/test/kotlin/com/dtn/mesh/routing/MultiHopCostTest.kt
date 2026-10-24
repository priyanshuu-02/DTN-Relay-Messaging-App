package com.dtn.mesh.routing

import com.dtn.mesh.model.ContactRecord
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-hop costs learnt one summary at a time (Phase 9): MaxProp and DiMaxProp summaries carry the
 * sender's own cheapest cost to nodes further away, and nothing a neighbour reports is copied into a
 * phone's own meeting likelihoods any more (that "seeding" made every node look one hop away).
 */
class MultiHopCostTest {

    private val a = "!0000000a"
    private val b = "!0000000b"
    private val c = "!0000000c"
    private val d = "!0000000d"

    private fun meet(s: RoutingStrategy, peer: String, times: Int) = repeat(times) {
        s.onEncounter(NodeId(peer), ContactRecord(peerId = NodeId(peer), startTimeMs = 0L, rssi = -70, snr = 0f))
    }

    /** The chain a–b–c–d, each phone told about its neighbours' summaries from d's end first. */
    private fun <S : RoutingStrategy> chain(make: (String) -> S): Map<String, S> {
        val s = listOf(a, b, c, d).associateWith(make)
        meet(s.getValue(a), b, 3)
        meet(s.getValue(b), a, 3); meet(s.getValue(b), c, 3)
        meet(s.getValue(c), b, 1); meet(s.getValue(c), d, 3)
        meet(s.getValue(d), c, 3)
        // d → c → b → a: each summary already holds what the previous one taught.
        s.getValue(c).onRoutingSummaryReceived(NodeId(d), s.getValue(d).buildRoutingSummary())
        s.getValue(b).onRoutingSummaryReceived(NodeId(c), s.getValue(c).buildRoutingSummary())
        s.getValue(a).onRoutingSummaryReceived(NodeId(b), s.getValue(b).buildRoutingSummary())
        return s
    }

    @Test
    fun diMaxProp_learnsAThreeHopPathFromItsNeighboursSummary() {
        val s = chain { DiMaxPropStrategy(DiMaxPropConfig(lambda = 0.0)).apply { selfId = it } }
        val sa = s.getValue(a)
        val path = sa.costFromMe(d)
        assertEquals(listOf(b, d), path.nodes.drop(1)) // b, then b's own path cost to d
        // w(a,b) + C(b,d), where C(b,d) = w(b,c) + w(c,d) as b computed it (sent as C × 64).
        val wAB = 1.0 - sa.getDeliveryProbability(NodeId(b))
        val cB = s.getValue(b).costFromMe(d).cost
        assertEquals(wAB + cB, path.cost, 1.0 / DiMaxPropStrategy.COST_SCALE)
        // b met a and c equally; c met d 3 times of 4 (c's f travels as f × 255).
        assertEquals((1.0 - 0.5) + (1.0 - 0.75), cB, 1.0 / 255)
        // a never met c or d: nothing of theirs was copied into its own table.
        assertEquals(0.0, sa.getDeliveryProbability(NodeId(c)), 0.0)
        assertEquals(0.0, sa.getDeliveryProbability(NodeId(d)), 0.0)
        // b is closer to d than a: the forwarding rule hands it over.
        assertEquals(DiMaxPropStrategy.Verdict.FORWARD, sa.decide(d, b).verdict)
    }

    @Test
    fun maxProp_learnsTheSameThreeHopCost() {
        val s = chain { MaxPropStrategy().apply { selfId = it } }
        val (hop, cost) = s.getValue(a).findMinCostNextHop(d, setOf(b))
        assertEquals(b, hop)
        // (1 − f(a,b)) + (1 − f(b,c)) + (1 − f(c,d)) = 0 + 0.5 + 0.25
        assertEquals(0.0 + 0.5 + 0.25, cost, 1e-9)
        assertEquals(0.0, s.getValue(a).getDeliveryProbability(NodeId(d)), 0.0)
    }

    @Test
    fun lambdaZero_diMaxPropAndMaxPropAgreeOnTheMultiHopCost() {
        val mp = chain { MaxPropStrategy().apply { selfId = it } }
        val dmp = chain { DiMaxPropStrategy(DiMaxPropConfig(lambda = 0.0)).apply { selfId = it } }
        assertEquals(mp.getValue(a).findMinCostNextHop(d, setOf(b)).second, dmp.getValue(a).costFromMe(d).cost,
            1.0 / DiMaxPropStrategy.COST_SCALE + 1.0 / 255)
    }

    @Test
    fun aNeighboursPathCostNeverLeadsBackThroughUs() {
        val s = chain { DiMaxPropStrategy(DiMaxPropConfig(lambda = 0.0)).apply { selfId = it } }
        // b reports a path cost to a (through nobody else) only if it isn't its direct contact: it is.
        val summary = DiMaxPropStrategy.decodeSummary(s.getValue(b).buildRoutingSummary().payload)!!
        assertFalse(a in summary.cost)
        assertTrue(d in summary.cost) // d is two hops from b
        assertFalse(d in summary.f)
    }

    @Test
    fun diMaxPropSummary_costEntriesRoundTrip_andOldReadersSkipThem() {
        val bytes = DiMaxPropStrategy.encodeSummary(0.2, mapOf(b to 0.6), mapOf(b to 0.1), costs = mapOf(c to 1.25, d to 9.0))
        assertEquals(4 + 2 * 6, bytes.size) // d's cost (≥ 4) doesn't fit the cost byte: left out
        val s = DiMaxPropStrategy.decodeSummary(bytes)!!
        assertEquals(1.25, s.cost.getValue(c), 1.0 / DiMaxPropStrategy.COST_SCALE)
        assertEquals(setOf(b), s.f.keys) // a cost entry (f byte 0) is never read as a contact
        assertEquals(0, bytes[4 + 6 + 4].toInt()) // its f byte, which pre-Phase 9 readers skip
    }

    @Test
    fun maxPropSummary_alwaysFitsOneFrame() {
        val s = MaxPropStrategy()
        for (i in 1..40) meet(s, "!%08x".format(i), i)
        val payload = s.buildRoutingSummary().payload
        assertTrue("${payload.size} B", payload.size <= DtnMessage.MAX_SINGLE_PACKET_PAYLOAD)
    }
}
