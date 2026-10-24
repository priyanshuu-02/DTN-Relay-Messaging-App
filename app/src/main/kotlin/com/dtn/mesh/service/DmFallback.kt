package com.dtn.mesh.service

/**
 * Which links a buffered DM takes right now (pure, unit-tested; the orchestrator applies it).
 *
 * The usual rules: a destination a link of ours ends at (BLE, Wi-Fi Direct) is only tried over
 * that link; one on our LoRa hub's roster gets the same-hub hand-off; a contact present on the
 * internet relay (and on no radio of ours) gets a relay publish; anything else goes to our hub,
 * which floods it over LoRa.
 *
 * The fallback: once a DM has been queued [AFTER_MS] on this phone without being delivered, it
 * keeps trying the direct links and is ALSO handed to every other path that is up — the hub (the
 * same-hub hand-off when the destination is on it, else a flood, once per hub session) and the
 * relay (destination present; it stays buffered until the end-to-end receipt, [RETRY_MS] window).
 * It never switches a link on, and broadcasts / group messages are never affected.
 */
object DmFallback {

    /** A DM still queued this long gets every other path that's up too. */
    const val AFTER_MS = 5_000L

    /** Relay publishes and same-hub hand-offs wait for the receipt: at most one per this window. */
    const val RETRY_MS = 30_000L

    enum class Path(val label: String) {
        /** BLE, then Wi-Fi Direct: a link that ends at the destination (delivery on a write). */
        DIRECT("direct link"),
        /** Our hub, for a phone registered at it (two hops: waits for the receipt). */
        HUB_LOCAL("LoRa hub (same hub)"),
        /** Our hub, which floods it over LoRa (once per hub session). */
        HUB_FLOOD("LoRa hub flood"),
        /** The internet relay, to the destination's own topic (waits for the receipt). */
        RELAY("Internet relay"),
    }

    /** What reaches the destination right now. */
    data class Links(
        /** BLE or Wi-Fi Direct reaches the destination itself. */
        val direct: Boolean = false,
        /** Our LoRa hub link is up. */
        val hub: Boolean = false,
        /** The destination is another phone registered at our hub (its roster). */
        val onOurHub: Boolean = false,
        /** The internet relay is up and the destination is present on it. */
        val relay: Boolean = false,
        /**
         * The destination is a contact of ours, so the flush's own relay pass publishes to it. A
         * carried DM for a phone that isn't a contact here goes over the relay with the fallback only.
         */
        val relayContact: Boolean = relay,
    )

    /** What this phone already did with the DM (the retry windows). 0 = never. */
    data class Attempts(
        val relayAtMs: Long = 0L,
        val hubLocalAtMs: Long = 0L,
        /** Already handed to our hub this hub session (it keeps a copy and floods it). */
        val hubThisSession: Boolean = false,
    )

    /**
     * @property paths the links to use now (pacing applied).
     * @property fallback the ones among [paths] that only the 5 s rule opened.
     */
    data class Plan(val paths: Set<Path>, val fallback: Set<Path>) {
        fun uses(p: Path): Boolean = p in paths

        companion object {
            val NONE = Plan(emptySet(), emptySet())
        }
    }

    /** Queued at least [AFTER_MS] (0 = unknown buffering time: not late). */
    fun isLate(queuedSinceMs: Long, nowMs: Long): Boolean = queuedSinceMs > 0 && nowMs - queuedSinceMs >= AFTER_MS

    /** When the fallback becomes due for a DM buffered at [queuedSinceMs]. */
    fun dueAtMs(queuedSinceMs: Long): Long = queuedSinceMs + AFTER_MS

    /** The links a DM may take, ignoring the retry windows. */
    fun allowed(links: Links, late: Boolean): Set<Path> {
        val radio = links.direct || links.onOurHub
        return buildSet {
            if (links.direct) add(Path.DIRECT)
            if (links.hub && links.onOurHub && (!links.direct || late)) add(Path.HUB_LOCAL)
            if (links.hub && !links.onOurHub && ((!radio && !links.relay) || late)) add(Path.HUB_FLOOD)
            if (links.relay && ((!radio && links.relayContact) || late)) add(Path.RELAY)
        }
    }

    /** True when waiting [AFTER_MS] would open a path the usual rules hold back (worth a flush then). */
    fun opensLater(links: Links): Boolean = (allowed(links, late = true) - allowed(links, late = false)).isNotEmpty()

    /**
     * The links to use now for a DM buffered at [queuedSinceMs]. Nothing for a broadcast / group
     * message (they spread on their own) or a delivered DM.
     */
    fun plan(
        broadcast: Boolean,
        delivered: Boolean,
        queuedSinceMs: Long,
        nowMs: Long,
        links: Links,
        attempts: Attempts = Attempts(),
    ): Plan {
        if (broadcast || delivered) return Plan.NONE
        val usual = allowed(links, late = false)
        val now = allowed(links, isLate(queuedSinceMs, nowMs))
        val due = now.filterTo(LinkedHashSet()) { p ->
            when (p) {
                Path.DIRECT -> true
                Path.RELAY -> nowMs - attempts.relayAtMs >= RETRY_MS
                Path.HUB_LOCAL -> nowMs - attempts.hubLocalAtMs >= RETRY_MS
                Path.HUB_FLOOD -> !attempts.hubThisSession
            }
        }
        return Plan(due, due - usual)
    }

    /**
     * What the DM was waiting on, for "(fallback after BLE)": the direct links that reach the
     * destination ("BLE", "BLE + Wi-Fi Direct"), else our hub, else the relay (a contact there).
     */
    fun waitingOn(directLinks: List<String>, links: Links): String = when {
        directLinks.isNotEmpty() -> directLinks.joinToString(" + ")
        links.direct -> "a direct link"
        links.onOurHub -> "LoRa hub"
        links.relay && links.relayContact -> "Internet relay"
        else -> "a carrier"
    }

    /** The diagnostics line: "fallback 1a2b3c4d → Internet relay after 6 s queued (BLE failed ×2)". */
    fun logLine(msgId8: String, via: String, queuedForMs: Long, after: String, directFailures: Int): String {
        val secs = (queuedForMs.coerceAtLeast(0L) / 1000L)
        val why = if (directFailures > 0) "$after failed ×$directFailures" else "no delivery over $after yet"
        return "fallback $msgId8 → $via after $secs s queued ($why)"
    }
}
