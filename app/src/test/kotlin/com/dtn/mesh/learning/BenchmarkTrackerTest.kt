package com.dtn.mesh.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkTrackerTest {

    private fun BenchmarkTracker.stat(strategy: String): BenchmarkStat = flow.value.single { it.strategy == strategy }

    @Test
    fun deliveryRate_isDeliveriesOverHandled() {
        val t = BenchmarkTracker()
        repeat(4) { t.recordHandled("PROPHET", "msg-$it", createdAtMs = 1_000L) }
        t.recordDelivery("PROPHET", hops = 1, latencyMs = 2_000L, msgId = "msg-0")
        t.recordReceipt("msg-1", nowMs = 5_000L)
        val s = t.stat("PROPHET")
        assertEquals(4L, s.handled)
        assertEquals(2L, s.deliveries)
        assertEquals(0.5, s.deliveryRate!!, 1e-9)
    }

    @Test
    fun aMessageCountsOnce_handledAndDelivered() {
        val t = BenchmarkTracker()
        assertTrue(t.recordHandled("MAXPROP", "m1", 1_000L))
        assertFalse(t.recordHandled("MAXPROP", "m1", 1_000L)) // carried again / re-forwarded
        assertTrue(t.recordDelivery("MAXPROP", 2, 1_000L, msgId = "m1"))
        assertFalse(t.recordReceipt("m1", 9_000L)) // the receipt for a direct delivery
        assertFalse(t.recordDelivery("MAXPROP", 2, 1_000L, msgId = "m1"))
        val s = t.stat("MAXPROP")
        assertEquals(1L, s.handled)
        assertEquals(1L, s.deliveries)
        assertEquals(1.0, s.deliveryRate!!, 1e-9)
    }

    @Test
    fun receiptsForMessagesThisPhoneNeverHandled_areIgnored() {
        val t = BenchmarkTracker()
        assertFalse(t.recordReceipt("someone-elses", 5_000L))
        assertTrue(t.flow.value.isEmpty())
    }

    @Test
    fun aDirectDeliveryOfAnUntrackedMessage_countsAsHandledToo_soTheRateNeverPasses100() {
        val t = BenchmarkTracker()
        t.recordDelivery("EPIDEMIC", 1, 500L, msgId = "from-before-start")
        val s = t.stat("EPIDEMIC")
        assertEquals(1L, s.handled)
        assertEquals(1L, s.deliveries)
        assertTrue(s.deliveryRate!! <= 1.0)
    }

    @Test
    fun deliveriesGoToTheStrategyThatHandledTheMessage_forwardsToTheOneThatMadeThem() {
        val t = BenchmarkTracker()
        t.recordHandled("PROPHET", "m1", 1_000L)
        t.recordForward("PROPHET")
        // Switched to DiMaxProp before it was delivered.
        t.recordForward("DIMAXPROP")
        t.recordDelivery("DIMAXPROP", 3, 4_000L, msgId = "m1")
        val prophet = t.stat("PROPHET")
        assertEquals(1L, prophet.handled)
        assertEquals(1L, prophet.deliveries)
        assertEquals(1L, prophet.forwards)
        val dimax = t.stat("DIMAXPROP")
        assertEquals(0L, dimax.handled)
        assertEquals(0L, dimax.deliveries)
        assertEquals(1L, dimax.forwards)
        assertNull(dimax.deliveryRate)
    }

    @Test
    fun averages_hopsFromDirectDeliveriesOnly_delayFromBoth() {
        val t = BenchmarkTracker()
        t.recordHandled("STABLE-PROPHET", "a", 1_000L)
        t.recordHandled("STABLE-PROPHET", "b", 1_000L)
        t.recordDelivery("STABLE-PROPHET", hops = 3, latencyMs = 2_000L, msgId = "a")
        t.recordReceipt("b", nowMs = 5_000L) // 4 s after it was written; no hop count in a receipt
        val s = t.stat("STABLE-PROPHET")
        assertEquals(3.0, s.avgHops, 1e-9)
        assertEquals(1L, s.hopSamples)
        assertEquals(3_000.0, s.avgLatencyMs, 1e-9)
        assertEquals(2L, s.latencySamples)
    }

    @Test
    fun aReceiptWithoutACreationTime_addsNoDelaySample() {
        val t = BenchmarkTracker()
        t.recordHandled("Q-LEARNING", "x", createdAtMs = 0L)
        t.recordReceipt("x", nowMs = 9_000L)
        val s = t.stat("Q-LEARNING")
        assertEquals(1L, s.deliveries)
        assertEquals(0L, s.latencySamples)
        assertEquals(0.0, s.avgLatencyMs, 1e-9)
    }

    @Test
    fun overhead_isForwardsPerDelivery() {
        val t = BenchmarkTracker()
        t.recordHandled("EPIDEMIC", "m", 1_000L)
        repeat(6) { t.recordForward("EPIDEMIC") }
        assertNull(t.stat("EPIDEMIC").overhead)
        t.recordDelivery("EPIDEMIC", 2, 100L, msgId = "m")
        assertEquals(6.0, t.stat("EPIDEMIC").overhead!!, 1e-9)
    }

    @Test
    fun reset_clearsTheCountersAndWhatWasTracked() {
        val t = BenchmarkTracker()
        t.recordHandled("PROPHET", "m", 1_000L)
        t.reset()
        assertTrue(t.flow.value.isEmpty())
        // The receipt of a message handled before the reset isn't a delivery of this run.
        assertFalse(t.recordReceipt("m", 5_000L))
        assertTrue(t.recordHandled("PROPHET", "m", 1_000L))
        assertEquals(1L, t.stat("PROPHET").handled)
    }

    @Test
    fun noRate_untilSomethingWasHandled() {
        val t = BenchmarkTracker()
        t.recordForward("MAXPROP")
        assertNull(t.stat("MAXPROP").deliveryRate)
    }
}
