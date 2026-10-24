package com.dtn.mesh.service

import com.dtn.mesh.learning.DoubleQLearningEngine
import com.dtn.mesh.learning.ForwardingState
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.ForwardingAction
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.routing.DiMaxPropStrategy
import com.dtn.mesh.routing.EpidemicStrategy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The carrier rules [DtnOrchestrator.sendToSingle] applies per bundle, in their order. */
class CarrierRulesTest {

    private val me = NodeId("!0000000a")
    private val peer = NodeId("!0000000b")
    private val other = NodeId("!0000000c")
    private val dest = NodeId("!0000000d")

    private fun dm(to: NodeId = dest, from: NodeId = me, hops: Int = 0, type: DtnMessageType = DtnMessageType.DATA) = DtnMessage(
        id = "m-${to.value}-${from.value}-$hops", originNodeId = from, destinationNodeId = to,
        payloadBytes = ByteArray(8), createdAtMs = 1_000L, ttlMs = 3_600_000L, hopCount = hops, messageType = type,
    )

    private class Facts(
        val delivered: Set<String> = emptySet(),
        val cameFrom: Map<String, Set<String>> = emptyMap(),
        val session: Set<String> = emptySet(),
        val recent: Set<String> = emptySet(),
        val direct: Boolean = true,
        val elsewhere: Boolean = false,
        val handed: Set<String> = emptySet(),
    ) : CarrierRules.Facts {
        override fun delivered(msgId: String) = msgId in delivered
        override fun cameFrom(msgId: String, peer: String) = peer in cameFrom[msgId].orEmpty()
        override fun sentThisSession(msgId: String, peer: String) = "$msgId|$peer" in session
        override fun sentRecently(msgId: String, peer: String) = "$msgId|$peer" in recent
        override fun handedOverThisSession(msgId: String, peer: String) = "$msgId|$peer" in handed
        override fun reachesDirectly(peer: NodeId) = direct
        override fun reachableElsewhere(dest: NodeId) = elsewhere
    }

    private fun verdict(msg: DtnMessage, p: CarrierRules.Peer = CarrierRules.Peer(peer), facts: Facts = Facts(), approved: Set<String> = setOf(msg.id)) =
        CarrierRules.decide(msg, p, facts, approved)

    private fun hold(reason: HoldReason) = CarrierRules.Verdict.Hold(reason)

    @Test
    fun aMuleCopyNeedsTheStrategy_directAndBroadcastDont() {
        assertEquals(CarrierRules.Verdict.Send, verdict(dm()))
        assertEquals(hold(HoldReason.ALGORITHM), verdict(dm(), approved = emptySet()))
        assertEquals(CarrierRules.Verdict.Send, verdict(dm(to = peer), approved = emptySet()))
        assertEquals(CarrierRules.Verdict.Send, verdict(dm(to = NodeId.BROADCAST), approved = emptySet()))
        // The hub bypasses the strategy (it floods; it sends no summaries to rank it by).
        assertEquals(CarrierRules.Verdict.Send, verdict(dm(), CarrierRules.Peer(peer, isHub = true), approved = emptySet()))
    }

    @Test
    fun neverBackToTheOriginOrAPreviousHop() {
        assertEquals(hold(HoldReason.PEER_IS_ORIGIN), verdict(dm(from = peer)))
        val m = dm(from = other)
        assertEquals(hold(HoldReason.CAME_FROM_PEER), verdict(m, facts = Facts(cameFrom = mapOf(m.id to setOf(peer.value)))))
        // Every phone that handed us a copy counts, not just the last one.
        assertEquals(hold(HoldReason.CAME_FROM_PEER), verdict(m, facts = Facts(cameFrom = mapOf(m.id to setOf(peer.value, dest.value)))))
        // ...except when that phone is the destination: it gets it.
        val forPeer = dm(to = peer, from = other)
        assertEquals(CarrierRules.Verdict.Send, verdict(forPeer, facts = Facts(cameFrom = mapOf(forPeer.id to setOf(peer.value)))))
    }

    @Test
    fun aReceiptClearsTheCopy_butNotABroadcast() {
        val m = dm()
        assertEquals(CarrierRules.Verdict.Clear, verdict(m, facts = Facts(delivered = setOf(m.id))))
        val b = dm(to = NodeId.BROADCAST)
        assertEquals(CarrierRules.Verdict.Send, verdict(b, facts = Facts(delivered = setOf(b.id))))
    }

    @Test
    fun theThirtySecondWindow_spareOnlyADirectDeliveryOverItsOwnLink() {
        val m = dm()
        val recent = Facts(recent = setOf("${m.id}|${peer.value}"))
        assertEquals(hold(HoldReason.RECENTLY_SENT), verdict(m, facts = recent))
        val forPeer = dm(to = peer)
        val again = Facts(recent = setOf("${forPeer.id}|${peer.value}"))
        assertEquals(CarrierRules.Verdict.Send, verdict(forPeer, facts = again))
        // Through the hub (no link of ours ends at it) it waits for the receipt: 30 s.
        assertEquals(hold(HoldReason.RECENTLY_SENT), verdict(forPeer, facts = Facts(recent = again.recent, direct = false)))
        // A broadcast goes once per session instead.
        val b = dm(to = NodeId.BROADCAST)
        assertEquals(hold(HoldReason.BROADCAST_DONE), verdict(b, facts = Facts(session = setOf("${b.id}|${peer.value}"))))
    }

    @Test
    fun aCarrierThatGotTheCopyOverAnAcknowledgedLinkIsNotReOffered_untilItsNextSession() {
        // Field log: a carried DM went to the same carrier every ~30 s while it stayed in range (fwd 1 → 27).
        val m = dm()
        val handed = "${m.id}|${peer.value}"
        assertEquals(hold(HoldReason.CARRIER_HAS_IT), verdict(m, facts = Facts(handed = setOf(handed), recent = setOf(handed))))
        // 30 s later, same session: still held.
        assertEquals(hold(HoldReason.CARRIER_HAS_IT), verdict(m, facts = Facts(handed = setOf(handed))))
        // A new session (the peer went offline and came back): offered again.
        assertEquals(CarrierRules.Verdict.Send, verdict(m))
        // Never for a direct delivery or a broadcast.
        val forPeer = dm(to = peer)
        assertEquals(CarrierRules.Verdict.Send, verdict(forPeer, facts = Facts(handed = setOf("${forPeer.id}|${peer.value}"))))
        val b = dm(to = NodeId.BROADCAST)
        assertEquals(CarrierRules.Verdict.Send, verdict(b, facts = Facts(handed = setOf("${b.id}|${peer.value}"))))
    }

    @Test
    fun relayHubAndHubOnlyPeers() {
        val m = dm()
        assertEquals(hold(HoldReason.NO_RELAY_CUSTODY), verdict(m, CarrierRules.Peer(peer, relay = true)))
        assertEquals(CarrierRules.Verdict.Send, verdict(dm(to = peer), CarrierRules.Peer(peer, relay = true)))
        assertEquals(hold(HoldReason.NOT_FOR_HUB_PHONE), verdict(m, CarrierRules.Peer(peer, hubOnly = true)))
        val hub = CarrierRules.Peer(peer, isHub = true)
        assertEquals(hold(HoldReason.HUB_SESSION), verdict(m, hub, Facts(session = setOf("${m.id}|${peer.value}"))))
        assertEquals(hold(HoldReason.REACHABLE_ELSEWHERE), verdict(m, hub, Facts(elsewhere = true)))
    }

    @Test
    fun onWire_addsOneHop_andKeepsTheTtl() {
        val m = dm(hops = 3)
        val w = CarrierRules.onWire(m)
        assertEquals(4, w.hopCount)
        assertEquals(m.createdAtMs, w.createdAtMs)
        assertEquals(m.ttlMs, w.ttlMs)
        assertEquals(255, CarrierRules.onWire(dm(hops = 255)).hopCount)
    }

    @Test
    fun approvedByStrategy_epidemicStopsAtItsHopLimit() {
        val ok = dm(hops = 9)
        val tooFar = dm(to = other, hops = 10)
        assertEquals(setOf(ok.id), CarrierRules.approvedByStrategy(EpidemicStrategy(), listOf(ok, tooFar), peer, setOf(peer.value)))
    }

    @Test
    fun qLearningPass_freshTableFollowsTheDiMaxPropRule_andKeepsChoices() = runBlocking {
        val dmp = DiMaxPropStrategy().apply { selfId = me.value }
        val engine = DoubleQLearningEngine(random = Random(1)).apply { setEpsilon(0.0) }
        val state = ForwardingState(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.0, 0.0, 0.0)
        val mule = dm()
        val back = dm(to = other, from = NodeId("!0000000e"))
        val pass = CarrierRules.qLearningPass(
            listOf(mule, dm(to = peer), dm(to = NodeId.BROADCAST), dm(from = peer), back, dm(to = other, hops = 10)),
            peer, setOf(peer.value), dmp, engine, eta = 0.2,
            cameFrom = { id, p -> id == back.id && p == peer.value },
            kept = { null },
            stateFor = { _, _ -> state },
        )
        // Nobody knows a path to D yet: the DiMaxProp rule explores, so the fresh table forwards.
        assertEquals(setOf(mule.id, dm(to = peer).id, dm(to = NodeId.BROADCAST).id), pass.approved)
        assertEquals(listOf(mule.id), pass.fresh.map { it.msg.id })
        assertEquals(ForwardingAction.FORWARD, pass.fresh.single().action)
        assertEquals(null, pass.fresh.single().reward) // neither side knows a path: nothing to measure

        val kept = CarrierRules.qLearningPass(listOf(mule), peer, setOf(peer.value), dmp, engine, 0.2,
            cameFrom = { _, _ -> false }, kept = { CarrierRules.QKept(ForwardingAction.STORE, "held earlier") },
            stateFor = { _, _ -> state })
        assertTrue(kept.approved.isEmpty())
        assertEquals("held earlier", kept.keptNotes[mule.id])
        assertTrue(kept.fresh.isEmpty())
    }

    @Test
    fun progressReward_isEtaTimesTheCostDrop() {
        val near = DiMaxPropStrategy.Path(0.4, listOf("a", "b"))
        val far = DiMaxPropStrategy.Path(1.4, listOf("a", "b", "c"))
        assertEquals(0.2 * (1.4 - 0.4), CarrierRules.progressReward(far, near, 0.2)!!, 1e-9)
        assertEquals(0.2 * (CarrierRules.UNREACHABLE_COST - 0.4), CarrierRules.progressReward(DiMaxPropStrategy.Path.NONE, near, 0.2)!!, 1e-9)
        assertEquals(null, CarrierRules.progressReward(DiMaxPropStrategy.Path.NONE, DiMaxPropStrategy.Path.NONE, 0.2))
    }

    @Test
    fun aReceiptIsNewsOnlyOnce() {
        val store = DeliveryReceiptStore()
        assertTrue(store.markDelivered("m1"))
        assertEquals(false, store.markDelivered("m1"))
        assertTrue(store.isDelivered("m1"))
    }
}
