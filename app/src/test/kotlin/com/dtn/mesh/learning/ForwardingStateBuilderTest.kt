package com.dtn.mesh.learning

import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardingStateBuilderTest {

    private fun build(
        rssi: Int = -70,
        snr: Float = 0f,
        enc: Int = 10,
        durMs: Long = 300_000,
        p: Double = 0.5,
        remaining: Long = 50,
        ttl: Long = 100,
        occ: Double = 0.25,
        succ: Int = 1,
        att: Int = 4,
        dup: Int = 5,
    ) = ForwardingStateBuilder.build(rssi, snr, enc, durMs, p, remaining, ttl, occ, succ, att, dup)

    @Test
    fun normalisesEveryFeatureToUnitRange() {
        val s = build()
        assertEquals(0.5, s.rssiNorm, 1e-9)            // -70 on [-100,-40]
        assertEquals(0.5, s.snrNorm, 1e-9)             // 0 on [-20,20]
        assertEquals(0.5, s.encounterFrequency, 1e-9)  // 10 / 20
        assertEquals(0.5, s.encounterDuration, 1e-9)   // 30 s avg / 60 s
        assertEquals(0.5, s.deliveryProbability, 1e-9)
        assertEquals(0.5, s.remainingTtlFraction, 1e-9)
        assertEquals(0.25, s.bufferOccupancy, 1e-9)
        assertEquals(0.25, s.historicalSuccessRate, 1e-9)
        assertEquals(0.5, s.duplicateCountNorm, 1e-9)
    }

    @Test
    fun unknownAndOutOfRangeInputsClamp() {
        val s = build(rssi = -200, snr = 99f, enc = 0, durMs = 0, p = 7.0, remaining = 500, ttl = 0, occ = -1.0, att = 0, dup = 99)
        assertEquals(0.0, s.rssiNorm, 1e-9)            // unknown RSSI sentinel
        assertEquals(1.0, s.snrNorm, 1e-9)
        assertEquals(0.0, s.encounterDuration, 1e-9)   // no encounters → no division by zero
        assertEquals(1.0, s.deliveryProbability, 1e-9)
        assertEquals(1.0, s.remainingTtlFraction, 1e-9)
        assertEquals(0.0, s.bufferOccupancy, 1e-9)
        assertEquals(0.0, s.historicalSuccessRate, 1e-9)
        assertEquals(1.0, s.duplicateCountNorm, 1e-9)
    }
}
