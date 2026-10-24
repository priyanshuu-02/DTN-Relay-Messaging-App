package com.dtn.mesh.receiver

/**
 * What the internet relay is doing right now: the one model behind every relay surface (Network
 * tile, status card, Connection links, the Internet relay page, Relay Configuration, Settings).
 * Each state is reported only while it is true: [Connecting] only while an attempt is in flight,
 * [Retrying] only while the next attempt is scheduled.
 */
sealed interface RelayStatus {
    /** The relay is switched off. */
    data object Off : RelayStatus

    /**
     * Switched on, but this phone has no usable internet (no default network with INTERNET +
     * VALIDATED; the hub's SoftAP never counts). Nothing is tried until it is back.
     */
    data object WaitingForInternet : RelayStatus

    /** An attempt to [endpoint] is in flight right now; [attempt] counts this run's attempts. */
    data class Connecting(val endpoint: String, val attempt: Int, val sinceMs: Long) : RelayStatus

    /**
     * Not connected; the next attempt (to [nextEndpoint]) starts at [atMs]. [reason] says why the
     * last attempt failed ([failures] in a row), or why a working session dropped ([sessionLost]).
     */
    data class Retrying(
        val atMs: Long,
        val failures: Int,
        val reason: String,
        val nextEndpoint: String,
        val sessionLost: Boolean = false,
    ) : RelayStatus

    data class Connected(val endpoint: String, val sinceMs: Long) : RelayStatus

    /**
     * Stopped trying after [failures] failed attempts in a row. Retry, a change of network or
     * switching the relay on again starts over.
     */
    data class GaveUp(val reason: String, val failures: Int, val atMs: Long) : RelayStatus
}

/**
 * The relay's connection supervisor as a pure state machine: no clock, threads or sockets. The
 * transport feeds it events (switched on / off, the network, attempt results, a 1 s tick) and
 * carries out the [Action]s it returns. Rules:
 *
 * - No usable internet → [RelayStatus.WaitingForInternet], no attempts. When it returns (or the
 *   default network changes) a fresh run starts at once: DNS again, first endpoint, no back-off.
 * - A failed attempt moves to the next endpoint and waits [Policy.backoffAfter] (2, 4, 8, 15, 30,
 *   then 60 s); after [Policy.maxFailures] in a row, or a refusal that retrying can't fix, it gives up.
 * - A working session that drops is retried on the same endpoint after [Policy.afterDropMs]; it is
 *   not a failed attempt.
 * - Watchdog: an attempt still in flight after [Policy.attemptTimeoutMs] is aborted and counted as
 *   failed, so "Connecting" can never stay up without an attempt behind it.
 * - Every attempt has an id; results of an attempt that is no longer current are ignored.
 */
class RelayLinkMachine(
    val policy: Policy = Policy(),
    internetUsable: Boolean = false,
    networkId: Long = 0L,
) {
    data class Policy(
        /** Wait after the n-th failed attempt in a row (the last value repeats). */
        val backoffMs: List<Long> = listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L),
        /** Give up after this many failed attempts in a row (while the phone has internet). */
        val maxFailures: Int = 10,
        /** An attempt still in flight after this long is aborted and counts as failed. */
        val attemptTimeoutMs: Long = 30_000L,
        /** Reconnect this soon after a working session drops (same endpoint). */
        val afterDropMs: Long = 1_000L,
    ) {
        init {
            require(backoffMs.isNotEmpty()) { "backoffMs must not be empty" }
            require(maxFailures > 0) { "maxFailures must be positive" }
        }

        /** The wait after the [failures]-th failed attempt in a row. */
        fun backoffAfter(failures: Int): Long = backoffMs[(failures - 1).coerceIn(0, backoffMs.lastIndex)]
    }

    sealed interface Action {
        /** Start attempt [attemptId] on endpoint [endpointIndex]; [resolve] = look the broker up (DNS) first. */
        data class Connect(val attemptId: Int, val endpointIndex: Int, val resolve: Boolean) : Action

        /** Drop the attempt in flight and the session, if any (no goodbye: a new attempt follows or we wait). */
        data object Abort : Action

        /** Switched off: say goodbye on the session and close everything. */
        data object Close : Action
    }

    /** The status after an event, and what to do about it (in order). */
    data class Step(val status: RelayStatus, val actions: List<Action> = emptyList())

    var status: RelayStatus = RelayStatus.Off
        private set
    var wanted: Boolean = false
        private set
    var internet: Boolean = internetUsable
        private set

    /** The attempt in flight (0 = none). */
    var attemptId: Int = 0
        private set

    /** The endpoint the current / next attempt uses. */
    var endpointIndex: Int = 0
        private set

    private var networkId: Long = if (internetUsable) networkId else 0L
    private var failures = 0
    private var attempts = 0
    private var endpoints: List<String> = emptyList()
    private var lastAttemptId = 0

    /** Switched on. A relay that gave up starts over; one that is running carries on. */
    fun start(now: Long): Step {
        if (wanted && status !is RelayStatus.GaveUp) return Step(status)
        wanted = true
        return freshRun(now)
    }

    /** Switched off. */
    fun stop(): Step {
        wanted = false
        attemptId = 0
        failures = 0
        attempts = 0
        status = RelayStatus.Off
        return Step(status, listOf(Action.Close))
    }

    /** Retry now (Retry / Reconnect now). Nothing to do while connected, connecting or without internet. */
    fun retryNow(now: Long): Step {
        if (!wanted) return Step(status)
        return when (status) {
            is RelayStatus.Connected, is RelayStatus.Connecting -> Step(status)
            else -> freshRun(now)
        }
    }

    /**
     * The default network changed: [usable] = it has validated internet, [network] = its handle
     * (another handle = another network, e.g. mobile data → Wi-Fi).
     */
    fun onInternet(usable: Boolean, network: Long, now: Long): Step {
        val id = if (usable) network else 0L
        val changed = usable != internet || id != networkId
        internet = usable
        networkId = id
        if (!wanted || !changed) return Step(status)
        if (!usable) {
            return when (status) {
                is RelayStatus.Connecting -> {
                    attemptId = 0
                    status = RelayStatus.WaitingForInternet
                    Step(status, listOf(Action.Abort))
                }
                is RelayStatus.Retrying, is RelayStatus.GaveUp -> {
                    status = RelayStatus.WaitingForInternet
                    Step(status)
                }
                // A session reports its own loss (socket closed, keep-alive timeout).
                else -> Step(status)
            }
        }
        // Internet is back, or the phone moved to another network: start over now (fresh DNS).
        return if (status is RelayStatus.Connected) Step(status) else freshRun(now)
    }

    /** DNS answered for attempt [id]: the endpoints in the order they are tried. */
    fun onEndpoints(id: Int, labels: List<String>): Step {
        if (id == 0 || id != attemptId || labels.isEmpty()) return Step(status)
        endpoints = labels
        if (endpointIndex >= labels.size) endpointIndex = 0
        val s = status
        if (s is RelayStatus.Connecting) status = s.copy(endpoint = labels[endpointIndex])
        return Step(status)
    }

    /** Attempt [id] connected. Null = it isn't the current attempt (the caller closes that client). */
    fun onConnected(id: Int, now: Long): Step? {
        if (id == 0 || id != attemptId || !wanted) return null
        attemptId = 0
        failures = 0
        attempts = 0
        status = RelayStatus.Connected(labelAt(endpointIndex), now)
        return Step(status)
    }

    /**
     * Attempt [id] failed for [reason]; [fatal] = retrying can't help (e.g. the broker refused our
     * credentials). Null = it isn't the current attempt.
     */
    fun onAttemptFailed(id: Int, reason: String, now: Long, fatal: Boolean = false): Step? {
        if (id == 0 || id != attemptId || !wanted) return null
        attemptId = 0
        failures++
        if (!internet) {
            status = RelayStatus.WaitingForInternet
            return Step(status)
        }
        if (fatal || failures >= policy.maxFailures) {
            status = RelayStatus.GaveUp(reason, failures, now)
            return Step(status)
        }
        endpointIndex = if (endpoints.isEmpty()) 0 else (endpointIndex + 1) % endpoints.size
        status = RelayStatus.Retrying(now + policy.backoffAfter(failures), failures, reason, labelAt(endpointIndex))
        return Step(status)
    }

    /** The connected session dropped (server DISCONNECT, socket closed, no PUBACK / PINGRESP). */
    fun onSessionLost(reason: String, now: Long): Step {
        if (!wanted || status !is RelayStatus.Connected) return Step(status)
        failures = 0
        status = if (!internet) RelayStatus.WaitingForInternet
        else RelayStatus.Retrying(now + policy.afterDropMs, 0, reason, labelAt(endpointIndex), sessionLost = true)
        return Step(status)
    }

    /** Once a second: start a retry that is due, and abort an attempt that ran too long (watchdog). */
    fun onTick(now: Long): Step {
        val s = status
        if (!wanted) return Step(s)
        return when {
            s is RelayStatus.Retrying && now >= s.atMs -> beginAttempt(now, resolve = endpointIndex == 0 && !s.sessionLost)
            s is RelayStatus.Connecting && now - s.sinceMs >= policy.attemptTimeoutMs -> {
                val failed = onAttemptFailed(attemptId, "no answer within ${policy.attemptTimeoutMs / 1000} s", now)
                Step(failed?.status ?: status, listOf(Action.Abort) + failed?.actions.orEmpty())
            }
            else -> Step(s)
        }
    }

    private fun freshRun(now: Long): Step {
        val busy = attemptId != 0 || status is RelayStatus.Connected
        failures = 0
        attempts = 0
        endpointIndex = 0
        attemptId = 0
        val abort = if (busy) listOf(Action.Abort) else emptyList()
        if (!internet) {
            status = RelayStatus.WaitingForInternet
            return Step(status, abort)
        }
        val begin = beginAttempt(now, resolve = true)
        return Step(begin.status, abort + begin.actions)
    }

    private fun beginAttempt(now: Long, resolve: Boolean): Step {
        attempts++
        attemptId = ++lastAttemptId
        status = RelayStatus.Connecting(labelAt(endpointIndex), attempts, now)
        return Step(status, listOf(Action.Connect(attemptId, endpointIndex, resolve)))
    }

    private fun labelAt(i: Int): String = endpoints.getOrNull(i) ?: "the relay server"
}

/** One-line descriptions of a [RelayStatus] for the diagnostics log and the relay's detail line. */
object RelayStatusLog {
    /** Short state name, e.g. "retrying". */
    fun name(s: RelayStatus): String = when (s) {
        RelayStatus.Off -> "off"
        RelayStatus.WaitingForInternet -> "waiting for internet"
        is RelayStatus.Connecting -> "connecting"
        is RelayStatus.Retrying -> "retrying"
        is RelayStatus.Connected -> "connected"
        is RelayStatus.GaveUp -> "gave up"
    }

    /** What the relay is doing, with the endpoint, attempt and reason, e.g. "Retrying in 4 s · attempt 2 failed: …". */
    fun describe(s: RelayStatus, nowMs: Long): String = when (s) {
        RelayStatus.Off -> "Relay off"
        RelayStatus.WaitingForInternet -> "Waiting for internet: no attempts until this phone has internet again"
        is RelayStatus.Connecting -> "Connecting · ${s.endpoint} (attempt ${s.attempt})"
        is RelayStatus.Retrying -> {
            val secs = ((s.atMs - nowMs + 999) / 1000).coerceAtLeast(0)
            if (s.sessionLost) "Retrying in $secs s · connection lost: ${s.reason} · reconnecting to ${s.nextEndpoint}"
            else "Retrying in $secs s · attempt ${s.failures} failed: ${s.reason} · next: ${s.nextEndpoint}"
        }
        is RelayStatus.Connected -> "Connected · ${s.endpoint}"
        is RelayStatus.GaveUp -> "Couldn't reach the relay: stopped after ${s.failures} failed attempt" +
            (if (s.failures == 1) "" else "s") + " · last: ${s.reason}"
    }
}
