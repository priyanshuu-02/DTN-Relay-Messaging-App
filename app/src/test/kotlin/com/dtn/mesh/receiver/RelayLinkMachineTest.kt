package com.dtn.mesh.receiver

import com.dtn.mesh.receiver.RelayLinkMachine.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay's connection supervisor: "Connecting" only while an attempt is in flight, "Waiting for
 * internet" without internet (no attempts), a fresh run as soon as internet is back, back-off and
 * endpoint rotation, giving up, and the watchdog (fix 1, `_diag/fix1_report.md`).
 */
class RelayLinkMachineTest {

    private val endpoints = listOf("TLS 1.1.1.1:8883", "TLS 2.2.2.2:8883", "TCP 1.1.1.1:1883")
    private val t0 = 1_000_000L

    private fun online() = RelayLinkMachine(internetUsable = true, networkId = 100L)

    private fun connect(step: RelayLinkMachine.Step): Action.Connect =
        step.actions.filterIsInstance<Action.Connect>().single()

    @Test
    fun offlineAtStart_waitsWithoutTrying_thenConnectsAtOnceWhenInternetReturns() {
        val m = RelayLinkMachine(internetUsable = false)
        val start = m.start(t0)
        assertEquals(RelayStatus.WaitingForInternet, start.status)
        assertTrue(start.actions.isEmpty())
        // Ticks while offline never start an attempt.
        repeat(120) { assertTrue(m.onTick(t0 + it * 1_000L).actions.isEmpty()) }
        assertEquals(RelayStatus.WaitingForInternet, m.status)
        // Internet is back: an attempt starts in the same step, with fresh DNS, first endpoint.
        val back = m.onInternet(usable = true, network = 7L, now = t0 + 130_000L)
        val c = connect(back)
        assertTrue(c.resolve)
        assertEquals(0, c.endpointIndex)
        assertTrue(back.status is RelayStatus.Connecting)
        assertEquals(c.attemptId, m.attemptId)
    }

    @Test
    fun connectingOnlyWhileAnAttemptIsInFlight() {
        val m = online()
        val c = connect(m.start(t0))
        m.onEndpoints(c.attemptId, endpoints)
        assertEquals(RelayStatus.Connecting(endpoints[0], 1, t0), m.status)
        val failed = m.onAttemptFailed(c.attemptId, "ConnectException", t0 + 3_000L)!!
        // Between attempts it is Retrying with a due time, never Connecting.
        val retrying = failed.status as RelayStatus.Retrying
        assertEquals(t0 + 3_000L + 2_000L, retrying.atMs)
        assertEquals(1, retrying.failures)
        assertEquals(endpoints[1], retrying.nextEndpoint)
        assertTrue(failed.actions.isEmpty())
        assertTrue(m.onTick(t0 + 4_000L).actions.isEmpty())
        val next = connect(m.onTick(t0 + 5_000L))
        assertEquals(1, next.endpointIndex)
        assertEquals(false, next.resolve)
        assertEquals(RelayStatus.Connecting(endpoints[1], 2, t0 + 5_000L), m.status)
    }

    @Test
    fun backoffGrowsAndTheEndpointsRotate_thenDnsAgainOnWrap() {
        val m = online()
        var c = connect(m.start(t0))
        m.onEndpoints(c.attemptId, endpoints)
        var now = t0
        val waits = mutableListOf<Long>()
        val indexes = mutableListOf(c.endpointIndex)
        val resolves = mutableListOf(c.resolve)
        repeat(5) {
            now += 1_000L
            val r = m.onAttemptFailed(c.attemptId, "timeout", now)!!.status as RelayStatus.Retrying
            waits += r.atMs - now
            now = r.atMs
            c = connect(m.onTick(now))
            indexes += c.endpointIndex
            resolves += c.resolve
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L), waits)
        assertEquals(listOf(0, 1, 2, 0, 1, 2), indexes)
        // Back at the first endpoint: look the broker up again.
        assertEquals(listOf(true, false, false, true, false, false), resolves)
    }

    @Test
    fun givesUpAfterTenFailuresInARow_andRetryStartsOver() {
        val m = online()
        var c = connect(m.start(t0))
        var now = t0
        repeat(9) {
            now += 1_000L
            val r = m.onAttemptFailed(c.attemptId, "no route", now)!!.status as RelayStatus.Retrying
            now = r.atMs
            c = connect(m.onTick(now))
        }
        val gave = m.onAttemptFailed(c.attemptId, "no route", now + 1_000L)!!
        assertEquals(RelayStatus.GaveUp("no route", 10, now + 1_000L), gave.status)
        // It stays given up: no more attempts by itself.
        repeat(300) { assertTrue(m.onTick(now + it * 1_000L).actions.isEmpty()) }
        // Retry: a fresh run (DNS, first endpoint).
        val again = connect(m.retryNow(now + 400_000L))
        assertTrue(again.resolve)
        assertEquals(0, again.endpointIndex)
        assertEquals(1, (m.status as RelayStatus.Connecting).attempt)
    }

    @Test
    fun aFatalRefusalGivesUpAtOnce_andANewNetworkTriesAgain() {
        val m = online()
        val c = connect(m.start(t0))
        val gave = m.onAttemptFailed(c.attemptId, "not authorized", t0 + 500L, fatal = true)!!
        assertTrue(gave.status is RelayStatus.GaveUp)
        val moved = m.onInternet(usable = true, network = 200L, now = t0 + 10_000L)
        assertTrue(connect(moved).resolve)
    }

    @Test
    fun watchdog_anAttemptThatNeverAnswersIsAbortedAndCountedAsFailed() {
        val m = online()
        val c = connect(m.start(t0))
        assertTrue(m.onTick(t0 + 29_000L).actions.isEmpty())
        val stuck = m.onTick(t0 + 30_000L)
        assertTrue(Action.Abort in stuck.actions)
        val r = stuck.status as RelayStatus.Retrying
        assertEquals("no answer within 30 s", r.reason)
        assertEquals(1, r.failures)
        // The late result of the aborted attempt is ignored.
        assertNull(m.onConnected(c.attemptId, t0 + 31_000L))
        assertNull(m.onAttemptFailed(c.attemptId, "late", t0 + 31_000L))
    }

    @Test
    fun aDroppedSessionIsRetriedOnTheSameEndpoint_withoutCountingAFailure() {
        val m = online()
        var c = connect(m.start(t0))
        m.onEndpoints(c.attemptId, endpoints)
        c = connect(m.onTick(m.onAttemptFailed(c.attemptId, "x", t0 + 1_000L).let { (it!!.status as RelayStatus.Retrying).atMs }))
        assertEquals(1, c.endpointIndex)
        assertEquals(RelayStatus.Connected(endpoints[1], t0 + 4_000L), m.onConnected(c.attemptId, t0 + 4_000L)!!.status)
        val lost = m.onSessionLost("Server sent DISCONNECT: server busy (0x89)", t0 + 60_000L)
        val r = lost.status as RelayStatus.Retrying
        assertTrue(r.sessionLost)
        assertEquals(0, r.failures)
        assertEquals(endpoints[1], r.nextEndpoint)
        assertEquals(t0 + 61_000L, r.atMs)
        val again = connect(m.onTick(t0 + 61_000L))
        assertEquals(1, again.endpointIndex)
        assertEquals(false, again.resolve)
    }

    @Test
    fun internetLostWhileConnecting_abortsAndWaits_thenAFreshRun() {
        val m = online()
        connect(m.start(t0))
        val lost = m.onInternet(usable = false, network = 0L, now = t0 + 2_000L)
        assertEquals(RelayStatus.WaitingForInternet, lost.status)
        assertEquals(listOf<Action>(Action.Abort), lost.actions)
        val back = m.onInternet(usable = true, network = 101L, now = t0 + 60_000L)
        assertTrue(connect(back).resolve)
    }

    @Test
    fun aDroppedSessionWithoutInternetWaits() {
        val m = online()
        val c = connect(m.start(t0))
        m.onConnected(c.attemptId, t0 + 1_000L)
        // Connected stays connected until the session itself reports the loss.
        assertTrue(m.onInternet(usable = false, network = 0L, now = t0 + 2_000L).status is RelayStatus.Connected)
        assertEquals(RelayStatus.WaitingForInternet, m.onSessionLost("socket closed", t0 + 3_000L).status)
    }

    @Test
    fun anotherNetworkRestartsAFailingRelay_butNotAConnectedOne() {
        val m = online()
        val c = connect(m.start(t0))
        m.onAttemptFailed(c.attemptId, "timeout", t0 + 1_000L)
        // Mobile data → Wi-Fi while waiting to retry: start over now instead of waiting.
        val moved = m.onInternet(usable = true, network = 555L, now = t0 + 1_500L)
        val c2 = connect(moved)
        assertTrue(c2.resolve)
        assertEquals(0, c2.endpointIndex)
        m.onConnected(c2.attemptId, t0 + 2_000L)
        // Connected over the old network: keep it (it reports its own loss if that network goes).
        assertTrue(m.onInternet(usable = true, network = 777L, now = t0 + 3_000L).actions.isEmpty())
        assertTrue(m.status is RelayStatus.Connected)
        // The same network again changes nothing.
        assertTrue(m.onInternet(usable = true, network = 777L, now = t0 + 4_000L).actions.isEmpty())
    }

    @Test
    fun stopClosesEverything_andLaterEventsAreIgnored() {
        val m = online()
        val c = connect(m.start(t0))
        val off = m.stop()
        assertEquals(RelayStatus.Off, off.status)
        assertEquals(listOf<Action>(Action.Close), off.actions)
        assertNull(m.onConnected(c.attemptId, t0 + 1_000L))
        assertTrue(m.onTick(t0 + 60_000L).actions.isEmpty())
        assertTrue(m.onInternet(usable = true, network = 9L, now = t0 + 61_000L).actions.isEmpty())
        assertEquals(RelayStatus.Off, m.status)
        // On again: a fresh run.
        assertTrue(connect(m.start(t0 + 70_000L)).resolve)
    }

    @Test
    fun startWhileRunningChangesNothing_startAfterGivingUpStartsOver() {
        val m = online()
        val c = connect(m.start(t0))
        assertTrue(m.start(t0 + 100L).actions.isEmpty())
        m.onAttemptFailed(c.attemptId, "denied", t0 + 200L, fatal = true)
        assertTrue(connect(m.start(t0 + 300L)).resolve)
    }

    @Test
    fun retryNow_doesNothingWhileConnectingOrConnected_orWithoutInternet() {
        val m = online()
        val c = connect(m.start(t0))
        assertTrue(m.retryNow(t0 + 100L).actions.isEmpty())
        m.onConnected(c.attemptId, t0 + 200L)
        assertTrue(m.retryNow(t0 + 300L).actions.isEmpty())
        val offline = RelayLinkMachine(internetUsable = false)
        offline.start(t0)
        assertEquals(RelayStatus.WaitingForInternet, offline.retryNow(t0 + 1_000L).status)
        assertTrue(offline.retryNow(t0 + 1_000L).actions.isEmpty())
    }

    @Test
    fun theFieldCase_aStuckReconnectNeverShowsConnectingForMinutes() {
        // 17:37:07 the broker sent DISCONNECT; the reconnect never answered and the relay showed
        // "Connecting" until 18:20. Now: retried after 1 s, aborted after 30 s, retried again.
        val m = online()
        val c = connect(m.start(t0))
        m.onConnected(c.attemptId, t0 + 1_000L)
        m.onSessionLost("Server sent DISCONNECT", t0 + 100_000L)
        val retry = connect(m.onTick(t0 + 101_000L))
        var now = t0 + 101_000L
        var longest = 0L
        var attempts = 1
        while (now < t0 + 900_000L) {
            now += 1_000L
            val step = m.onTick(now)
            attempts += step.actions.count { it is Action.Connect }
            val s = m.status
            if (s is RelayStatus.Connecting) longest = maxOf(longest, now - s.sinceMs)
        }
        assertTrue("Connecting for $longest ms", longest < m.policy.attemptTimeoutMs)
        // The silent attempts were aborted and retried until it gave up honestly.
        assertTrue(attempts >= 2)
        assertTrue(m.status is RelayStatus.GaveUp)
        assertTrue(retry.attemptId > 0)
    }

    @Test
    fun theLogLineSaysWhatHappens() {
        assertEquals("Waiting for internet: no attempts until this phone has internet again",
            RelayStatusLog.describe(RelayStatus.WaitingForInternet, 0L))
        assertEquals("Retrying in 4 s · attempt 2 failed: timeout · next: TCP 1.1.1.1:1883",
            RelayStatusLog.describe(RelayStatus.Retrying(t0 + 4_000L, 2, "timeout", "TCP 1.1.1.1:1883"), t0))
        assertEquals("Retrying in 1 s · connection lost: closed · reconnecting to TLS 1.1.1.1:8883",
            RelayStatusLog.describe(RelayStatus.Retrying(t0 + 1_000L, 0, "closed", "TLS 1.1.1.1:8883", sessionLost = true), t0))
        assertEquals("Couldn't reach the relay: stopped after 10 failed attempts · last: x",
            RelayStatusLog.describe(RelayStatus.GaveUp("x", 10, t0), t0))
        assertEquals("connecting", RelayStatusLog.name(RelayStatus.Connecting("a", 1, 0L)))
    }
}
