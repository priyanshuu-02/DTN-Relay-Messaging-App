package com.dtn.mesh.ui.chats

import com.dtn.mesh.receiver.hubLabel
import com.dtn.mesh.service.MessageLifecycleLog
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.GroupProgress
import com.dtn.mesh.ui.MemberReceipt

/**
 * How a message travelled, in words, from what the route journal recorded
 * ([MessageLifecycleLog.RouteRecord.lastPath], prev / next hop, the 5 s fallback, group receipts).
 * Pure, unit-tested. The state itself always comes from `deliveryState()`.
 */
object TravelText {
    /** The link of a hand-off for people: "LoRa hub flood" reads as "LoRa hub"; unknown → null. */
    fun linkName(lastPath: String?): String? = when {
        lastPath.isNullOrBlank() || lastPath == "radio" -> null
        lastPath.startsWith("LoRa hub") -> "LoRa hub"
        else -> lastPath
    }

    /**
     * The coloured line under a sent bubble: "✓✓ Delivered via BLE", "🕓 Queued" ("" for received
     * ones). Our group message ([group] set): "✓ Received by 1 of 2", "✓✓ Received by all (2)", or
     * "✓ Spreading" while no member is known yet.
     */
    fun bubbleStatus(state: DeliveryState, via: String, group: GroupProgress? = null): String {
        if (group != null) groupLine(state, group)?.let { return it }
        val link = linkName(via)?.let { " via $it" }.orEmpty()
        return when (state) {
            DeliveryState.DELIVERED -> "${state.tick} Delivered$link"
            DeliveryState.IN_TRANSIT, DeliveryState.SPREADING -> "${state.tick} Sent$link"
            DeliveryState.RECEIVED_BY_SOME, DeliveryState.RECEIVED_BY_ALL -> "${state.tick} ${state.short}"
            DeliveryState.QUEUED -> "${state.tick} Queued"
            DeliveryState.EXPIRED -> "${state.tick} Expired"
            DeliveryState.DROPPED -> "${state.tick} Dropped"
            DeliveryState.RECEIVED, DeliveryState.CARRYING -> ""
        }
    }

    /** Our group message's receipt line; null for the states that read as usual (Queued, Expired, …). */
    fun groupLine(state: DeliveryState, g: GroupProgress): String? = when (state) {
        DeliveryState.RECEIVED_BY_ALL -> "${state.tick} Received by all (${g.known})"
        DeliveryState.RECEIVED_BY_SOME -> "${state.tick} Received by ${g.received} of ${g.known}"
        DeliveryState.SPREADING -> if (g.known > 0) "${state.tick} Received by 0 of ${g.known}" else "${state.tick} Spreading"
        else -> null
    }

    /**
     * The status sheet's "how it travelled" line. Incoming: "Received via BLE / Wi-Fi Direct /
     * LoRa hub (hub A9B0) / Internet relay" (the hub that handed it over, by [hubLabel]).
     * Outgoing: "Delivered via …" / "Sent via …" — "(fallback after BLE)" when the 5 s fallback
     * took that link — or why it hasn't gone. Null when unknown.
     */
    fun travelLine(isOutgoing: Boolean, state: DeliveryState, route: MessageLifecycleLog.RouteRecord?): String? {
        val link = linkName(route?.lastPath)
        if (!isOutgoing) {
            if (link == null) return null
            val prev = route?.prevHop
            val hub = if (link == "LoRa hub" && !prev.isNullOrBlank() && prev != route?.source) " (${hubLabel(prev)})" else ""
            return "Received via $link$hub"
        }
        val next = route?.nextHop
        // Handed to a hub (it floods it on), not to the destination's phone on the same hub.
        val hub = if (link == "LoRa hub" && !next.isNullOrBlank() && next != route?.destination) hubLabel(next) else null
        val fallback = fallbackNote(route, link)
        val notes = listOfNotNull(hub, fallback)
        val via = link?.let { " via $it" + if (notes.isEmpty()) "" else notes.joinToString(", ", " (", ")") }.orEmpty()
        return when (state) {
            DeliveryState.DELIVERED -> "Delivered$via"
            DeliveryState.IN_TRANSIT, DeliveryState.SPREADING,
            DeliveryState.RECEIVED_BY_SOME, DeliveryState.RECEIVED_BY_ALL -> "Sent$via"
            DeliveryState.QUEUED -> fallbackNote(route, "Internet relay")
                ?.let { "Sent via Internet relay ($it) — no delivery receipt yet" }
                ?: "Not sent yet — waiting in this phone's buffer for a link"
            DeliveryState.EXPIRED -> "Expired before it could be delivered"
            DeliveryState.DROPPED -> "Dropped before it could be delivered"
            DeliveryState.RECEIVED, DeliveryState.CARRYING -> null
        }
    }

    /** "fallback after BLE" when the 5 s fallback used [link] for this message, else null. */
    fun fallbackNote(route: MessageLifecycleLog.RouteRecord?, link: String?): String? {
        if (route == null || link == null || route.fallbackAfter.isBlank()) return null
        return if (link in route.fallbackLinks) "fallback after ${route.fallbackAfter}" else null
    }

    /** One member's line in the status sheet / Message page: "✓ 14:05 · via LoRa hub (hub BDB8)", or "Not yet". */
    fun memberLine(m: MemberReceipt, time: (Long) -> String): String {
        val r = m.receipt ?: return "Not yet"
        val link = linkName(r.path)
        val hub = if (link == "LoRa hub" && r.hop.isNotBlank()) " (${hubLabel(r.hop)})" else ""
        return "✓ ${time(r.atMs)}" + (link?.let { " · via $it$hub" } ?: "")
    }

    /** "Received by 1 of 2 members", "No members known yet". */
    fun progressLine(g: GroupProgress): String = when {
        g.known <= 0 -> "No members known yet"
        g.received >= g.known -> "All ${g.known} member${if (g.known == 1) "" else "s"}"
        else -> "${g.received} of ${g.known} members"
    }
}
