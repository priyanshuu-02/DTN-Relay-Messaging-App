package com.dtn.mesh.ui

import com.dtn.mesh.database.entity.ChatMessageEntity
import com.dtn.mesh.service.MessageLifecycleLog

/**
 * One delivery state for a message, derived from every source we have (the persistent chat row,
 * the route journal, the broadcast flag, and for our group messages the members' receipts). The
 * chat bubble, the route list and the Message page all use [deliveryState], so they can never
 * disagree again ("details say IN TRANSIT, chat says ✓").
 *
 * @property label full sentence for the Message page.
 * @property short one or two words for the chat bubble.
 * @property pill upper-case badge for the route list / Message page header.
 * @property tick WhatsApp-style glyph next to the time ("" = none).
 */
enum class DeliveryState(val label: String, val short: String, val pill: String, val tick: String) {
    QUEUED("Queued — waiting for a link", "Queued", "QUEUED", "🕓"),
    IN_TRANSIT("Sent — on its way, waiting for the delivery receipt", "Sent", "IN TRANSIT", "✓"),
    DELIVERED("Delivered to the recipient's phone", "Delivered", "DELIVERED", "✓✓"),
    RECEIVED("Received on this phone", "Received", "RECEIVED", ""),
    SPREADING("Spreading to everyone in reach until it expires", "Sent", "SPREADING", "✓"),
    /** Our group message: some of the group's known members sent a receipt (it keeps spreading). */
    RECEIVED_BY_SOME("Received by some of the group's members — still spreading to the rest", "Partly received",
        "PARTLY RECEIVED", "✓"),
    /** Our group message: every known member sent a receipt (it still spreads until its TTL). */
    RECEIVED_BY_ALL("Received by every member this phone knows of", "Received by all", "RECEIVED BY ALL", "✓✓"),
    CARRYING("Carrying it for someone else (store-carry-forward)", "Carrying", "CARRYING", ""),
    EXPIRED("Expired before it could be delivered", "Expired", "EXPIRED", "!"),
    DROPPED("Dropped before it could be delivered", "Dropped", "DROPPED", "!"),
}

/** How many of a group's known members (never this phone) sent a receipt for one of our group messages. */
data class GroupProgress(val received: Int, val known: Int)

/** One known member of a group and their receipt for one of our group messages (null = not yet). */
data class MemberReceipt(val member8: String, val receipt: MessageLifecycleLog.GroupReceipt? = null) {
    val received: Boolean get() = receipt != null
}

/**
 * @param chatStatus persisted ChatMessageEntity status, or null when this phone has no chat row
 *        (we only carried the bundle, or it isn't readable here).
 * @param isOutgoing true = we sent it, false = we received it, null = no chat row.
 * @param isBroadcast broadcast or group message (no single destination → no ROUTING_ACK).
 * @param group our own group message's member receipts ([GroupReceiptStatus.progress]), else null.
 */
fun deliveryState(
    chatStatus: String?,
    isOutgoing: Boolean?,
    isBroadcast: Boolean,
    route: MessageLifecycleLog.RouteRecord?,
    group: GroupProgress? = null,
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
        // Our group message that members' phones confirmed: like delivery, it beats expiry.
        isBroadcast && group != null && group.received > 0 ->
            if (group.received >= group.known) DeliveryState.RECEIVED_BY_ALL else DeliveryState.RECEIVED_BY_SOME
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

/**
 * Group receipts as the UI shows them (pure, unit-tested). Ids are 8 characters.
 *
 * A group's known members = the contacts this phone invited (Create group › Add members) ∪ the
 * members seen posting ∪ the members who sent a receipt for one of our messages — never this
 * phone, never a blocked contact.
 */
object GroupReceiptStatus {

    fun knownMembers(
        invited: Collection<String>,
        posters: Collection<String>,
        receiptSenders: Collection<String>,
        me8: String?,
        blocked: Collection<String>,
    ): Set<String> {
        val block = blocked.mapTo(HashSet()) { it.takeLast(8) }
        return (invited.asSequence() + posters.asSequence() + receiptSenders.asSequence())
            .map { it.takeLast(8) }
            .filter { it.isNotBlank() && it != me8 && it !in block }
            .toCollection(LinkedHashSet())
    }

    /**
     * Known members per group channel.
     *
     * @param invited channel → invited node ids.
     * @param posts (channel, sender id) of every group message received here.
     * @param routes the journal's routes: our own group messages carry the members' receipts.
     */
    fun knownByChannel(
        invited: Map<Int, Set<String>>,
        posts: List<Pair<Int, String>>,
        routes: List<MessageLifecycleLog.RouteRecord>,
        me8: String?,
        blocked: Collection<String>,
    ): Map<Int, Set<String>> {
        val posters = posts.filter { it.first > 0 }.groupBy({ it.first }, { it.second })
        val receipts = routes.filter { it.isBroadcast && it.channel > 0 && it.groupReceipts.isNotEmpty() }
            .groupBy({ it.channel }, { r -> r.groupReceipts.map { it.member } }).mapValues { (_, l) -> l.flatten() }
        val channels = invited.keys + posters.keys + receipts.keys
        return channels.associateWith { ch ->
            knownMembers(invited[ch].orEmpty(), posters[ch].orEmpty(), receipts[ch].orEmpty(), me8, blocked)
        }
    }

    /** Each known member with their receipt for [route]: received first (oldest first), then the rest by id. */
    fun members(route: MessageLifecycleLog.RouteRecord?, known: Set<String>): List<MemberReceipt> {
        val got = route?.groupReceipts.orEmpty().associateBy { it.member }
        return known.map { MemberReceipt(it, got[it]) }
            .sortedWith(compareBy<MemberReceipt> { !it.received }.thenBy { it.receipt?.atMs ?: 0L }.thenBy { it.member8 })
    }

    fun progress(members: List<MemberReceipt>): GroupProgress = GroupProgress(members.count { it.received }, members.size)
}
