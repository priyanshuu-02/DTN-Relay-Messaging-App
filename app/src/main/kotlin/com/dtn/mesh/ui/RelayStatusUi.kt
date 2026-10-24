package com.dtn.mesh.ui

import com.dtn.mesh.receiver.RelayStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One internet-relay state as every surface shows it: the Network tile and Connection links row
 * ([chip]), the relay page / Settings / Relay Configuration headline ([title]) and the line under it
 * ([detail]). Pure, from [RelayStatus] only, so no surface can say "Connecting" while nothing is tried.
 */
data class RelayStatusText(val health: LinkHealth, val chip: String, val title: String, val detail: String)

/** Seconds until [atMs], rounded up (never negative). */
internal fun secondsUntil(atMs: Long, nowMs: Long): Long = ((atMs - nowMs + 999) / 1000).coerceAtLeast(0)

/**
 * The texts for [status]. [contactsOnline] = contacts present on the relay; [nowMs] drives the
 * "Retrying in N s" countdown.
 */
fun relayStatusText(status: RelayStatus, contactsOnline: Int, nowMs: Long): RelayStatusText = when (status) {
    RelayStatus.Off -> RelayStatusText(LinkHealth.OFF, "Off", "Off",
        "The internet relay is switched off.")
    RelayStatus.WaitingForInternet -> RelayStatusText(LinkHealth.PROBLEM, "Waiting for internet", "Waiting for internet",
        "This phone has no internet right now, so nothing is tried. The relay reconnects by itself within seconds " +
            "when it's back.")
    is RelayStatus.Connecting -> RelayStatusText(LinkHealth.BUSY, "Connecting…", "Connecting…",
        "Trying ${status.endpoint}" + if (status.attempt > 1) " · attempt ${status.attempt}" else "")
    is RelayStatus.Retrying -> {
        val secs = secondsUntil(status.atMs, nowMs)
        val headline = if (secs > 0) "Retrying in $secs s" else "Retrying now…"
        RelayStatusText(LinkHealth.BUSY, headline, headline,
            if (status.sessionLost) "Connection lost: ${status.reason} · reconnecting to ${status.nextEndpoint}"
            else "Attempt ${status.failures} failed: ${status.reason} · next: ${status.nextEndpoint}")
    }
    is RelayStatus.Connected -> RelayStatusText(LinkHealth.ON,
        if (contactsOnline > 0) "$contactsOnline contact${if (contactsOnline == 1) "" else "s"} online" else "Connected",
        "Connected · ${status.endpoint}",
        (if (contactsOnline == 1) "1 contact online" else "$contactsOnline contacts online") +
            " · since ${clock(status.sinceMs)}")
    is RelayStatus.GaveUp -> RelayStatusText(LinkHealth.PROBLEM, "Couldn't reach · Retry", "Couldn't reach the relay · Retry",
        "Stopped after ${status.failures} failed attempt${if (status.failures == 1) "" else "s"}: ${status.reason}. " +
            "Tap Retry; it also tries again by itself when the phone's network changes.")
}

private fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
