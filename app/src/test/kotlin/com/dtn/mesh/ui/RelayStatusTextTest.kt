package com.dtn.mesh.ui

import com.dtn.mesh.receiver.MeshConnectionState
import com.dtn.mesh.receiver.RadioStatusMonitor
import com.dtn.mesh.receiver.RelayStatus
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.receiver.TransportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The relay's texts: one state model on every surface, never a false "Connecting" (fix 1). */
class RelayStatusTextTest {

    private val t0 = 5_000_000L
    private val online = RadioStatusMonitor.Radios(bluetoothOn = true, wifiOn = true, locationOn = true, internetAvailable = true)

    @Test
    fun eachStateHasItsOwnText() {
        val waiting = relayStatusText(RelayStatus.WaitingForInternet, 0, t0)
        assertEquals("Waiting for internet", waiting.chip)
        assertEquals(LinkHealth.PROBLEM, waiting.health)
        assertTrue(waiting.detail.contains("nothing is tried"))

        val connecting = relayStatusText(RelayStatus.Connecting("TLS 1.1.1.1:8883", 2, t0), 0, t0)
        assertEquals("Connecting…", connecting.chip)
        assertEquals(LinkHealth.BUSY, connecting.health)
        assertEquals("Trying TLS 1.1.1.1:8883 · attempt 2", connecting.detail)

        val connected = relayStatusText(RelayStatus.Connected("TLS 1.1.1.1:8883", t0), 2, t0)
        assertEquals("2 contacts online", connected.chip)
        assertEquals("Connected · TLS 1.1.1.1:8883", connected.title)
        assertEquals(LinkHealth.ON, connected.health)

        val gave = relayStatusText(RelayStatus.GaveUp("Server refused the connection: banned (0x8A)", 10, t0), 0, t0)
        assertEquals("Couldn't reach the relay · Retry", gave.title)
        assertEquals("Couldn't reach · Retry", gave.chip)
        assertTrue(gave.detail.startsWith("Stopped after 10 failed attempts: Server refused"))

        assertEquals("Off", relayStatusText(RelayStatus.Off, 0, t0).chip)
    }

    @Test
    fun retryingCountsDown() {
        val r = RelayStatus.Retrying(t0 + 8_000L, 3, "SocketTimeoutException", "TCP 2.2.2.2:1883")
        assertEquals("Retrying in 8 s", relayStatusText(r, 0, t0).chip)
        assertEquals("Retrying in 7 s", relayStatusText(r, 0, t0 + 1_000L).chip)
        assertEquals("Retrying in 1 s", relayStatusText(r, 0, t0 + 7_500L).chip)
        assertEquals("Retrying now…", relayStatusText(r, 0, t0 + 8_000L).chip)
        assertEquals("Attempt 3 failed: SocketTimeoutException · next: TCP 2.2.2.2:1883", relayStatusText(r, 0, t0).detail)
        val lost = RelayStatus.Retrying(t0 + 1_000L, 0, "Server sent DISCONNECT", "TLS 1.1.1.1:8883", sessionLost = true)
        assertEquals("Connection lost: Server sent DISCONNECT · reconnecting to TLS 1.1.1.1:8883", relayStatusText(lost, 0, t0).detail)
    }

    @Test
    fun theChipFollowsTheRelayStatus_notJustTheCoarseState() {
        val status = TransportStatus(internet = MeshConnectionState.CONNECTING, wanted = setOf(TransportKind.INTERNET))
        val retrying = RelayStatus.Retrying(t0 + 4_000L, 1, "x", "y")
        val chip = transportChip(TransportKind.INTERNET, status, online, 0, relay = retrying, nowMs = t0)
        assertEquals("Retrying in 4 s", chip.detail)
        assertEquals(LinkHealth.BUSY, chip.health)
        // Waiting for internet is not "Connecting…", and the headline says it needs attention.
        val waiting = transportChip(TransportKind.INTERNET, TransportStatus(wanted = setOf(TransportKind.INTERNET)),
            online.copy(internetAvailable = false), 0, relay = RelayStatus.WaitingForInternet, nowMs = t0)
        assertEquals("Waiting for internet", waiting.detail)
        assertEquals("Needs attention", connectionHeadline(listOf(waiting)).title)
    }

    @Test
    fun tapRule_waitingOpensThePage_gaveUpRetries() {
        val offline = online.copy(internetAvailable = false)
        assertEquals(LinkTapAction.SHOW_DETAILS, linkTapAction(TransportKind.INTERNET, LinkHealth.PROBLEM, offline, true))
        assertEquals(LinkTapAction.TOGGLE, linkTapAction(TransportKind.INTERNET, LinkHealth.PROBLEM, online, true))
        // Switching it on without internet is fine (it waits).
        assertEquals(LinkTapAction.TOGGLE, linkTapAction(TransportKind.INTERNET, LinkHealth.OFF, offline, true))
    }
}
