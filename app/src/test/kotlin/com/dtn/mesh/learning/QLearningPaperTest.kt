package com.dtn.mesh.learning

import com.dtn.mesh.model.ForwardingAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The Q-learning changes from the review + DiMaxProp paper §VI: the three new inputs, the
 * forward/hold-only action set, the DiMaxProp tie-break prior, and saving the tables.
 */
class QLearningPaperTest {

    private fun state(cost: Double = 1.0, hop: Double = 0.0, relayBuf: Double = 0.0, snr: Double = 0.5) =
        ForwardingState(0.5, snr, 0.5, 0.5, 0.5, 0.5, 0.2, 0.5, 0.0,
            diMaxPropCost = cost, hopCountNorm = hop, relayBufferOccupancy = relayBuf)

    @Test
    fun defaultStateSpace_staysAroundTenThousand() {
        assertEquals(10_368, QLearningConfig().effectiveStateSpace)
    }

    @Test
    fun theThreePaperInputs_changeTheStateKey_andSnrDoesNot() {
        val e = DoubleQLearningEngine()
        val base = e.discretise(state())
        assertEquals(12, base.split('-').size)
        assertNotEquals(base, e.discretise(state(cost = 0.0)))
        assertNotEquals(base, e.discretise(state(hop = 1.0)))
        assertNotEquals(base, e.discretise(state(relayBuf = 1.0)))
        assertEquals(base, e.discretise(state(snr = 0.0))) // no link here measures SNR
    }

    @Test
    fun exploration_onlyEverForwardsOrHolds() {
        val e = DoubleQLearningEngine(random = Random(7)) // ε starts at 1: every pick is random
        val picks = (1..400).map { e.selectActionEpsilonGreedy(state()).first }.toSet()
        assertEquals(setOf(ForwardingAction.STORE, ForwardingAction.FORWARD), picks)
    }

    @Test
    fun anUnlearnedState_followsTheTieBreak_aLearnedOneFollowsItsValues() {
        val e = DoubleQLearningEngine(random = Random(1)).apply { setEpsilon(0.0) }
        val s = state(cost = 0.1)
        assertEquals(ForwardingAction.FORWARD, e.selectActionEpsilonGreedy(s, tieBreak = ForwardingAction.FORWARD).first)
        assertEquals(ForwardingAction.STORE, e.selectActionEpsilonGreedy(s).first) // no prior: hold
        repeat(30) { e.update(s, ForwardingAction.STORE, 1.0, nextState = null) }
        e.setEpsilon(0.0)
        assertEquals(ForwardingAction.STORE, e.selectActionEpsilonGreedy(s, tieBreak = ForwardingAction.FORWARD).first)
    }

    @Test
    fun savedTables_comeBackWithTheirValuesAndExplorationRate() {
        val e = DoubleQLearningEngine(random = Random(3))
        val s = state(cost = 0.2)
        repeat(50) { e.update(s, ForwardingAction.FORWARD, 1.0, nextState = null) }
        val bytes = e.toBytes()

        val restored = DoubleQLearningEngine(random = Random(4))
        assertTrue(restored.loadBytes(bytes))
        assertEquals(e.qValues(s).first, restored.qValues(s).first, 1e-12)
        assertEquals(e.currentEpsilon, restored.currentEpsilon, 1e-12)
        assertEquals(e.totalUpdates, restored.totalUpdates)
    }

    @Test
    fun aTableSavedWithADifferentStateEncoding_isNotLoaded() {
        val old = DoubleQLearningEngine(QLearningConfig(bucketsRssi = 3))
        old.update(state(), ForwardingAction.FORWARD, 1.0, nextState = null)
        val fresh = DoubleQLearningEngine()
        assertFalse(fresh.loadBytes(old.toBytes()))
        assertFalse(fresh.loadBytes(byteArrayOf(1, 2, 3)))
        assertEquals(0L, fresh.totalUpdates)
    }

    @Test
    fun stateBuilder_normalisesThePaperInputs() {
        fun build(cost: Double, hops: Int, relay: Double?) = ForwardingStateBuilder.build(
            -70, 0f, 4, 40_000L, 0.3, 30_000L, 60_000L, 0.1, 1, 2, 0,
            diMaxPropCost = cost, hopCount = hops, relayBufferOccupancy = relay,
        )
        val unknown = build(Double.POSITIVE_INFINITY, 0, null)
        assertEquals(1.0, unknown.diMaxPropCost, 1e-9)
        assertEquals(0.0, unknown.relayBufferOccupancy, 1e-9)
        val known = build(1.0, 4, 0.8)
        assertEquals(1.0 / ForwardingStateBuilder.COST_CAP, known.diMaxPropCost, 1e-9)
        assertEquals(4.0 / ForwardingStateBuilder.HOP_LIMIT, known.hopCountNorm, 1e-9)
        assertEquals(0.8, known.relayBufferOccupancy, 1e-9)
        assertEquals(1.0, build(9.0, 99, 2.0).diMaxPropCost, 1e-9)
    }
}
