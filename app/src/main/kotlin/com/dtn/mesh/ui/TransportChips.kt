package com.dtn.mesh.ui

import com.dtn.mesh.receiver.MeshConnectionState
import com.dtn.mesh.receiver.RadioStatusMonitor
import com.dtn.mesh.receiver.RelayStatus
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.receiver.TransportStatus

/** How a transport chip / the headline is coloured. */
enum class LinkHealth { OFF, BUSY, ON, PROBLEM }

/** One transport's chip: what it is, how healthy, and a short human state. */
data class TransportChipModel(
    val kind: TransportKind,
    val label: String,
    val health: LinkHealth,
    val detail: String,
)

/** Headline for the connection card. */
data class ConnectionHeadline(val health: LinkHealth, val title: String, val detail: String)

/**
 * Pure mapping from live transport + radio state to the chip shown for [kind]. A wanted
 * transport whose radio is off says so ("Bluetooth off") instead of an endless "Connecting…".
 *
 * @param relayPeers contacts present on the internet relay.
 * @param linkPeers devices on each link right now: DTN phones in Bluetooth range, phones linked
 *   in our Wi-Fi Direct group, other phones on our hub (from each link's details).
 * @param wifiDirectSupported false below Android 10, where invitation-free groups can't run.
 * @param relay what the internet relay is doing ([relayStatusText] then names it, the same text
 *   every relay surface shows); null = derive it from [status] alone.
 * @param nowMs the clock for the relay's "Retrying in N s".
 */
fun transportChip(
    kind: TransportKind,
    status: TransportStatus,
    radios: RadioStatusMonitor.Radios,
    relayPeers: Int,
    linkPeers: Map<TransportKind, Int> = emptyMap(),
    wifiDirectSupported: Boolean = true,
    relay: RelayStatus? = null,
    nowMs: Long = 0L,
): TransportChipModel {
    val state = status.stateOf(kind)
    val label = when (kind) {
        TransportKind.BLE -> "BLE"
        TransportKind.WIFI_DIRECT -> "Wi-Fi Direct"
        TransportKind.HUB -> "LoRa hub"
        TransportKind.INTERNET -> "Internet"
    }
    fun chip(h: LinkHealth, d: String) = TransportChipModel(kind, label, h, d)
    fun phones(n: Int) = if (n == 1) "1 phone" else "$n phones"
    // Nothing the user can fix: grey, and never listed as a problem in the headline.
    if (kind == TransportKind.WIFI_DIRECT && !wifiDirectSupported) return chip(LinkHealth.OFF, "Needs Android 10")
    if (!status.isWanted(kind) && state == MeshConnectionState.DISCONNECTED) return chip(LinkHealth.OFF, "Off")
    val n = linkPeers[kind] ?: 0
    return when (kind) {
        TransportKind.BLE -> when {
            !radios.bluetoothOn -> chip(LinkHealth.PROBLEM, "Bluetooth off")
            state == MeshConnectionState.CONNECTED -> chip(LinkHealth.ON, if (n > 0) "Active · $n nearby" else "Active")
            state == MeshConnectionState.CONNECTING -> chip(LinkHealth.BUSY, "Starting…")
            else -> chip(LinkHealth.PROBLEM, "Failed · tap to retry")
        }
        TransportKind.WIFI_DIRECT -> when {
            !radios.wifiOn -> chip(LinkHealth.PROBLEM, "Wi-Fi off")
            state == MeshConnectionState.CONNECTED -> chip(LinkHealth.ON, if (n > 0) "Linked · ${phones(n)}" else "Linked")
            state == MeshConnectionState.CONNECTING -> chip(LinkHealth.BUSY, "Searching for phones to link")
            else -> chip(LinkHealth.PROBLEM, "Unavailable · retry")
        }
        TransportKind.HUB -> when {
            !radios.wifiOn -> chip(LinkHealth.PROBLEM, "Wi-Fi off")
            state == MeshConnectionState.CONNECTED -> chip(LinkHealth.ON, if (n > 0) "Connected · ${phones(n)}" else "Connected")
            state == MeshConnectionState.CONNECTING -> chip(LinkHealth.BUSY, "Joining…")
            else -> chip(LinkHealth.PROBLEM, "Not joined · tap to retry")
        }
        TransportKind.INTERNET -> if (relay != null && relay != RelayStatus.Off) {
            relayStatusText(relay, relayPeers, nowMs).let { chip(it.health, it.chip) }
        } else when {
            state == MeshConnectionState.CONNECTED ->
                chip(LinkHealth.ON, if (relayPeers > 0) "$relayPeers contact${if (relayPeers == 1) "" else "s"} online" else "Connected")
            !radios.internetAvailable -> chip(LinkHealth.PROBLEM, "Waiting for internet")
            state == MeshConnectionState.CONNECTING -> chip(LinkHealth.BUSY, "Connecting…")
            else -> chip(LinkHealth.PROBLEM, "Failed · tap to retry")
        }
    }
}

/** What tapping a link (chip, Network tile, Connection links row) does. */
enum class LinkTapAction { SHOW_DETAILS, ENABLE_BLUETOOTH, OPEN_WIFI_SETTINGS, TOGGLE }

/**
 * The link tap rule, shared by every surface that shows links: a link that's running or coming
 * up shows its details (it can be turned off there); Bluetooth / Wi-Fi off asks for the radio
 * first; anything else (off, or wanted but failed) starts / retries it.
 */
fun linkTapAction(
    kind: TransportKind,
    health: LinkHealth,
    radios: RadioStatusMonitor.Radios,
    wifiDirectSupported: Boolean,
): LinkTapAction = when {
    kind == TransportKind.WIFI_DIRECT && !wifiDirectSupported -> LinkTapAction.SHOW_DETAILS
    kind == TransportKind.BLE && !radios.bluetoothOn -> LinkTapAction.ENABLE_BLUETOOTH
    (kind == TransportKind.HUB || kind == TransportKind.WIFI_DIRECT) && !radios.wifiOn -> LinkTapAction.OPEN_WIFI_SETTINGS
    // A relay waiting for internet can't be retried from here: its page says why and what it does.
    kind == TransportKind.INTERNET && health == LinkHealth.PROBLEM && !radios.internetAvailable -> LinkTapAction.SHOW_DETAILS
    health == LinkHealth.ON || health == LinkHealth.BUSY -> LinkTapAction.SHOW_DETAILS
    else -> LinkTapAction.TOGGLE
}

/** Headline derived from the chips: online over what, coming up, needs attention, or offline. */
fun connectionHeadline(chips: List<TransportChipModel>): ConnectionHeadline {
    val on = chips.filter { it.health == LinkHealth.ON }
    val busy = chips.filter { it.health == LinkHealth.BUSY }
    val problems = chips.filter { it.health == LinkHealth.PROBLEM }
    return when {
        // The chips below already show each link's own state; once something is up the headline
        // just says what we're online over (problem text here only duplicated the chips).
        on.isNotEmpty() -> ConnectionHeadline(
            LinkHealth.ON, "Online",
            "via " + on.joinToString(" · ") { it.label },
        )
        busy.isNotEmpty() -> ConnectionHeadline(
            LinkHealth.BUSY, "Connecting…",
            (busy + problems).joinToString(", ") { "${it.label}: ${it.detail}" },
        )
        problems.isNotEmpty() -> ConnectionHeadline(
            LinkHealth.PROBLEM, "Needs attention",
            problems.joinToString(", ") { "${it.label}: ${it.detail}" },
        )
        else -> ConnectionHeadline(LinkHealth.OFF, "Offline", "Tap Connect, or tap a link below to start just that one")
    }
}
