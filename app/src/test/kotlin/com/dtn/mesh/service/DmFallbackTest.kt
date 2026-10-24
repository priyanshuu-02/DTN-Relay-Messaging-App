package com.dtn.mesh.service

import com.dtn.mesh.service.DmFallback.Attempts
import com.dtn.mesh.service.DmFallback.Links
import com.dtn.mesh.service.DmFallback.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DmFallbackTest {

    private val queuedAt = 100_000L
    private val everything = Links(direct = true, hub = true, onOurHub = false, relay = true)

    private fun plan(links: Links, queuedForMs: Long, attempts: Attempts = Attempts(), broadcast: Boolean = false, delivered: Boolean = false) =
        DmFallback.plan(broadcast, delivered, queuedAt, queuedAt + queuedForMs, links, attempts)

    @Test
    fun underFiveSeconds_aDirectlyReachedDestination_getsTheDirectLinksOnly() {
        val p = plan(everything, queuedForMs = 4_999L)
        assertEquals(setOf(Path.DIRECT), p.paths)
        assertTrue(p.fallback.isEmpty())
    }

    @Test
    fun afterFiveSeconds_directPlusHubPlusRelay() {
        val p = plan(everything, queuedForMs = 5_000L)
        assertEquals(setOf(Path.DIRECT, Path.HUB_FLOOD, Path.RELAY), p.paths)
        // The direct links keep being tried; the hub and the relay are what the fallback added.
        assertEquals(setOf(Path.HUB_FLOOD, Path.RELAY), p.fallback)
    }

    @Test
    fun theRelayIsUsedOnlyWhenTheDestinationIsPresentThere() {
        val noRelay = plan(everything.copy(relay = false), queuedForMs = 60_000L)
        assertFalse(noRelay.uses(Path.RELAY))
        assertEquals(setOf(Path.DIRECT, Path.HUB_FLOOD), noRelay.paths)
        // Nothing else switched on: nothing to fall back to.
        assertEquals(setOf(Path.DIRECT), plan(Links(direct = true), queuedForMs = 60_000L).paths)
    }

    @Test
    fun nothingAfterDelivery_andBroadcastsAreUnaffected() {
        assertEquals(DmFallback.Plan.NONE, plan(everything, queuedForMs = 60_000L, delivered = true))
        assertEquals(DmFallback.Plan.NONE, plan(everything, queuedForMs = 60_000L, broadcast = true))
    }

    @Test
    fun aDestinationOnOurHub_getsTheSameHubHandOff_neverAFlood() {
        val links = Links(direct = true, hub = true, onOurHub = true, relay = true)
        assertEquals(setOf(Path.DIRECT), plan(links, 1_000L).paths)
        val late = plan(links, 5_000L)
        assertEquals(setOf(Path.DIRECT, Path.HUB_LOCAL, Path.RELAY), late.paths)
        assertFalse(late.uses(Path.HUB_FLOOD))
    }

    @Test
    fun theUsualRules_whenNoLinkEndsAtTheDestination() {
        // Only on the relay: published at once; after 5 s our hub floods it too.
        val relayOnly = Links(hub = true, relay = true)
        assertEquals(setOf(Path.RELAY), plan(relayOnly, 0L).paths)
        assertEquals(setOf(Path.RELAY, Path.HUB_FLOOD), plan(relayOnly, 5_000L).paths)
        assertEquals(setOf(Path.HUB_FLOOD), plan(relayOnly, 5_000L).fallback)
        // Only on our hub: the same-hub hand-off at once; after 5 s the relay too.
        val hubOnly = Links(hub = true, onOurHub = true, relay = true)
        assertEquals(setOf(Path.HUB_LOCAL), plan(hubOnly, 0L).paths)
        assertEquals(setOf(Path.RELAY), plan(hubOnly, 5_000L).fallback)
        // Nowhere in reach: the hub floods it at once (no fallback involved).
        val nowhere = Links(hub = true)
        assertEquals(setOf(Path.HUB_FLOOD), plan(nowhere, 0L).paths)
        assertTrue(plan(nowhere, 60_000L).fallback.isEmpty())
    }

    @Test
    fun aCarriedDmForAStrangerOnTheRelay_goesOverTheRelayWithTheFallback() {
        // Present on the relay but not a contact here: no relay pass of its own publishes it, and
        // the hub holds it back because the relay reaches it. After 5 s both take it.
        val stranger = Links(hub = true, relay = true, relayContact = false)
        assertTrue(plan(stranger, 1_000L).paths.isEmpty())
        assertTrue(DmFallback.opensLater(stranger))
        assertEquals(setOf(Path.RELAY, Path.HUB_FLOOD), plan(stranger, 5_000L).fallback)
        assertEquals("a carrier", DmFallback.waitingOn(emptyList(), stranger))
        // Reached over BLE as well: BLE first, the relay after 5 s, as for a contact.
        assertEquals(setOf(Path.RELAY, Path.HUB_FLOOD), plan(stranger.copy(direct = true), 5_000L).fallback)
    }

    @Test
    fun retryWindows_andTheHubSession() {
        val now = queuedAt + 60_000L
        // Published to the relay 10 s ago: waits for its receipt (30 s window).
        assertFalse(plan(everything, 60_000L, Attempts(relayAtMs = now - 10_000L)).uses(Path.RELAY))
        assertTrue(plan(everything, 60_000L, Attempts(relayAtMs = now - 30_000L)).uses(Path.RELAY))
        // The hub keeps its copy: once per hub session.
        assertFalse(plan(everything, 60_000L, Attempts(hubThisSession = true)).uses(Path.HUB_FLOOD))
        val onHub = everything.copy(onOurHub = true)
        assertFalse(plan(onHub, 60_000L, Attempts(hubLocalAtMs = now - 29_000L)).uses(Path.HUB_LOCAL))
        assertTrue(plan(onHub, 60_000L, Attempts(hubLocalAtMs = now - 31_000L)).uses(Path.HUB_LOCAL))
    }

    @Test
    fun lateness_dueTime_andWhetherWaitingHelps() {
        assertFalse(DmFallback.isLate(0L, 1_000_000L)) // unknown buffering time
        assertFalse(DmFallback.isLate(queuedAt, queuedAt + 4_999L))
        assertTrue(DmFallback.isLate(queuedAt, queuedAt + 5_000L))
        assertEquals(queuedAt + 5_000L, DmFallback.dueAtMs(queuedAt))
        assertTrue(DmFallback.opensLater(everything))
        assertFalse(DmFallback.opensLater(Links(direct = true)))
        assertFalse(DmFallback.opensLater(Links(hub = true))) // flooded at once anyway
    }

    @Test
    fun labelsAndTheDiagnosticsLine() {
        assertEquals("BLE", DmFallback.waitingOn(listOf("BLE"), everything))
        assertEquals("BLE + Wi-Fi Direct", DmFallback.waitingOn(listOf("BLE", "Wi-Fi Direct"), everything))
        assertEquals("LoRa hub", DmFallback.waitingOn(emptyList(), Links(hub = true, onOurHub = true, relay = true)))
        assertEquals("Internet relay", DmFallback.waitingOn(emptyList(), Links(hub = true, relay = true)))
        assertEquals("fallback 1a2b3c4d → Internet relay after 6 s queued (BLE failed ×2)",
            DmFallback.logLine("1a2b3c4d", "Internet relay", 6_400L, "BLE", 2))
        assertEquals("fallback 1a2b3c4d → LoRa hub (hub A9B0) after 5 s queued (no delivery over BLE yet)",
            DmFallback.logLine("1a2b3c4d", "LoRa hub (hub A9B0)", 5_000L, "BLE", 0))
    }
}
