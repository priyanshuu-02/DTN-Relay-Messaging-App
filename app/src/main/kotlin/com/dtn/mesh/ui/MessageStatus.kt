package com.dtn.mesh.ui

import com.dtn.mesh.database.entity.ChatMessageEntity
import com.dtn.mesh.service.MessageLifecycleLog

/**
 * One delivery state for a message, derived from every source we have (the persistent chat row,
 * the route journal, the broadcast flag). The chat bubble, the route list and Message info all
 * use [deliveryState], so they can never disagree again ("details say IN TRANSIT, chat says ✓").
 *
 * @property label full sentence for Message info.
 * @property short one or two words for the chat bubble.
 * @property pill upper-case badge for the route list / Message info header.
 * @property tick WhatsApp-style glyph next to the time ("" = none).
 */
enum class DeliveryState(val label: String, val short: String, val pill: String, val tick: String) {
    QUEUED("Queued — waiting for a link", "Queued", "QUEUED", "🕓"),
    IN_TRANSIT("Sent — on its way, waiting for the delivery receipt", "Sent", "IN TRANSIT", "✓"),
    DELIVERED("Delivered to the recipient's phone", "Delivered", "DELIVERED", "✓✓"),
    RECEIVED("Received on this phone", "Received", "RECEIVED", ""),
    SPREADING("Spreading to everyone in reach (group and broadcast messages have no receipts)", "Sent", "SPREADING", "✓"),
    CARRYING("Carrying it for someone else (store-carry-forward)", "Carrying", "CARRYING", ""),
    EXPIRED("Expired before it could be delivered", "Expired", "EXPIRED", "!"),
    DROPPED("Dropped before it could be delivered", "Dropped", "DROPPED", "!"),
}

/**
 * @param chatStatus persisted ChatMessageEntity status, or null when this phone has no chat row
 *        (we only carried the bundle, or it isn't readable here).
 * @param isOutgoing true = we sent it, false = we received it, null = no chat row.
 * @param isBroadcast broadcast or group message (no single destination → no receipt).
 */
fun deliveryState(
    chatStatus: String?,
    isOutgoing: Boolean?,
    isBroadcast: Boolean,
    route: MessageLifecycleLog.RouteRecord?,
): DeliveryState {
    // Delivery always wins over a stale "in transit": either source proving it is enough.
    val delivered = chatStatus == ChatMessageEntity.Status.DELIVERED || route?.delivered == true
    // Did a carrier take a copy (a nearby phone, a hub)? A DM published to the internet relay
    // doesn't count: the relay stores nothing, so until the receipt arrives only our copy exists.
    // (Current builds don't record relay DM publishes; older journals marked them "INTERNET RELAY".)
    val relayOnly = route != null && !route.isBroadcast && route.strategy == "INTERNET RELAY"
    val handedOff = route != null && !relayOnly && (route.nextHop != null || route.lastPath.isNotBlank())
    return when {
        isOutgoing == false -> DeliveryState.RECEIVED
        delivered && !isBroadcast -> DeliveryState.DELIVERED
        chatStatus == ChatMessageEntity.Status.EXPIRED || route?.outcome == "EXPIRED" -> DeliveryState.EXPIRED
        chatStatus == ChatMessageEntity.Status.DROPPED || chatStatus == ChatMessageEntity.Status.FAILED ||
            route?.outcome == "DROPPED" -> DeliveryState.DROPPED
        // Our own group / broadcast message that hasn't reached anyone yet is still queued. (No
        // journal record at all = sent by an older version: trust the chat row's "sent".)
        isBroadcast && isOutgoing == true && route != null && !handedOff -> DeliveryState.QUEUED
        isBroadcast -> DeliveryState.SPREADING
        isOutgoing == null -> DeliveryState.CARRYING
        handedOff -> DeliveryState.IN_TRANSIT
        else -> DeliveryState.QUEUED
    }
}
