package com.dtn.mesh.ui.queue

import com.dtn.mesh.receiver.hubLabel
import com.dtn.mesh.service.LifecycleEntry
import com.dtn.mesh.service.LifecycleEvent
import com.dtn.mesh.service.MessageLifecycleLog.RouteRecord
import com.dtn.mesh.ui.BufferedMsgInfo
import com.dtn.mesh.ui.ChatMessage
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.chats.TravelText
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.deliveryState
import com.dtn.mesh.ui.groups.GroupInviteDm

/** The Queue tab's segments, in chip order. */
internal enum class QueueSegment(val label: String) {
    PENDING("Pending"),
    FORWARDED("Forwarded"),
    DELIVERED("Delivered"),
    HISTORY("History"),
    BROADCASTS("Broadcasts & groups"),
}

/** A message the Queue shows: a DM ([DmRow]) or a broadcast / group message ([BroadcastRow]). Ids are 8 characters. */
internal sealed interface QueueItem {
    val msgId8: String
    val origin8: String
    /** This phone wrote it (else it carries someone else's). */
    val own: Boolean
    /** Its text when it's readable here (own messages, received broadcasts / group posts), else null. */
    val text: String?
    /** From `deliveryState()`, seen from the queue: own → sent states, someone else's DM → Carrying. */
    val state: DeliveryState
    /** Hops so far, as far as this phone knows (its stored copy, or its hand-on). */
    val hops: Int
    /** How many times this phone handed a copy on. */
    val forwards: Int
    /** When it entered this phone's buffer (epoch ms). */
    val sinceMs: Long
    /** When the copy here expires (epoch ms); 0 = not in the buffer any more. */
    val expiresAtMs: Long
}

/**
 * One DM in Pending / Forwarded / Delivered.
 *
 * @property nextHop8 who this phone handed it to (the first hand-off), null if nobody yet.
 * @property link the link of that hand-off ("BLE", "LoRa hub", …), null if unknown.
 * @property relayTried published to the internet relay (no custody there, so it stays pending).
 * @property inBuffer still in the buffer: opens the Queued message page (else the Message page).
 */
internal data class DmRow(
    override val msgId8: String,
    override val origin8: String,
    val dest8: String,
    override val own: Boolean,
    override val text: String?,
    override val state: DeliveryState,
    override val hops: Int,
    override val forwards: Int,
    override val sinceMs: Long,
    override val expiresAtMs: Long,
    val nextHop8: String?,
    val link: String?,
    val relayTried: Boolean,
    val inBuffer: Boolean,
    val deliveredAtMs: Long = 0L,
) : QueueItem

/** One broadcast / group message still spreading. [reached]: phones / hubs this phone handed a copy to. */
internal data class BroadcastRow(
    override val msgId8: String,
    override val origin8: String,
    override val own: Boolean,
    override val text: String?,
    override val state: DeliveryState,
    override val hops: Int,
    override val forwards: Int,
    override val sinceMs: Long,
    override val expiresAtMs: Long,
    val channel: Int,
    val reached: List<String>,
) : QueueItem

/** What each segment lists. Pending / Forwarded / Delivered are DMs only (like the "N queued" counters). */
internal data class QueueLists(
    val pending: List<DmRow> = emptyList(),
    val forwarded: List<DmRow> = emptyList(),
    val delivered: List<DmRow> = emptyList(),
    val history: List<BufferHistoryItem> = emptyList(),
    val broadcasts: List<BroadcastRow> = emptyList(),
) {
    fun count(segment: QueueSegment): Int = when (segment) {
        QueueSegment.PENDING -> pending.size
        QueueSegment.FORWARDED -> forwarded.size
        QueueSegment.DELIVERED -> delivered.size
        QueueSegment.HISTORY -> history.size
        QueueSegment.BROADCASTS -> broadcasts.size
    }
}

/**
 * The Queue's segmentation (pure, unit-tested). Only user DATA is ever listed: announces, receipts
 * and routing summaries are control traffic and stay in the logs.
 */
internal object QueueModel {

    /** Only user DATA is a message; everything else is control traffic. */
    fun isUserData(messageType: String): Boolean = messageType == "DATA"

    private val VIA_LINK = Regex("via (Wi-Fi Direct|Internet relay|LoRa hub flood|LoRa hub|BLE|radio)")
    private val TO_PEER = Regex("→ ([0-9a-fA-F]{8})\\b")
    private val HOP_EVENTS = setOf(LifecycleEvent.BUFFERED, LifecycleEvent.DELIVERED, LifecycleEvent.FORWARDED)

    /**
     * Segments every message.
     *
     * @param bundles the buffer's bundles (BUFFERED / FORWARDING).
     * @param entries the journal, newest first (as the ViewModel keeps it).
     */
    fun build(
        bundles: List<BufferedMsgInfo>,
        routes: List<RouteRecord>,
        chats: List<ChatMessage>,
        entries: List<LifecycleEntry>,
        myId8: String?,
    ): QueueLists {
        val routeById = routes.associateBy { it.msgId }
        val chatById = chats.associateBy { it.msgId.take(8) }
        val eventsById = entries.groupBy { it.msgId }
        fun eventsOf(id: String) = eventsById[id].orEmpty().asReversed()

        val pending = ArrayList<DmRow>()
        val forwarded = ArrayList<DmRow>()
        val delivered = LinkedHashMap<String, DmRow>()
        val broadcasts = ArrayList<BroadcastRow>()
        for (b in byMessage(bundles)) {
            when (val row = item(b, routeById[b.msgId], chatById[b.msgId], eventsOf(b.msgId), myId8)) {
                null -> Unit
                is BroadcastRow -> broadcasts += row
                is DmRow -> when {
                    row.state == DeliveryState.DELIVERED -> delivered[row.msgId8] = row
                    handedOn(row) -> forwarded += row
                    else -> pending += row
                }
            }
        }
        // Delivered DMs that already left the buffer: the journal's DMs this phone sent or carried.
        for (r in routes) {
            if (r.msgId in delivered) continue
            deliveredRow(r, chatById[r.msgId], eventsOf(r.msgId))?.let { delivered[r.msgId] = it }
        }
        return QueueLists(
            pending = pending.sortedByDescending { it.sinceMs },
            forwarded = forwarded.sortedByDescending { it.sinceMs },
            delivered = delivered.values.sortedByDescending { if (it.deliveredAtMs > 0) it.deliveredAtMs else it.sinceMs },
            history = deriveBufferHistory(entries),
            broadcasts = broadcasts.sortedByDescending { it.sinceMs },
        )
    }

    /**
     * Fragments of one message share its first 8 id characters: one entry per message (oldest
     * arrival, soonest expiry, most hops / forwards), in first-seen order.
     */
    fun byMessage(bundles: List<BufferedMsgInfo>): List<BufferedMsgInfo> =
        bundles.groupBy { it.msgId }.values.map { parts ->
            if (parts.size == 1) parts[0] else parts[0].copy(
                hopCount = parts.maxOf { it.hopCount },
                forwardCount = parts.maxOf { it.forwardCount },
                receivedAtMs = parts.minOf { it.receivedAtMs },
                expiresAtMs = parts.minOf { it.expiresAtMs },
            )
        }

    /**
     * The row for one buffered bundle, or null when the Queue doesn't list it: control traffic, or
     * a DM addressed to this phone (received, not queued). [events] are its journal events, oldest first.
     */
    fun item(b: BufferedMsgInfo, route: RouteRecord?, chat: ChatMessage?, events: List<LifecycleEntry>, myId8: String?): QueueItem? {
        if (!isUserData(b.messageType)) return null
        val own = (route != null && route.me.isNotBlank() && route.source == route.me) ||
            (myId8 != null && b.origin == myId8) || chat?.isOutgoing == true
        val hops = maxOf(b.hopCount, hopsOf(events))
        if (UiText.isBroadcastId(b.dest)) {
            return BroadcastRow(
                msgId8 = b.msgId, origin8 = b.origin, own = own, text = chat?.text,
                // Seen from the queue: ours waits for its first phone (or shows its members'
                // receipts, as in the chat), anyone else's is spreading.
                state = chat?.takeIf { own && it.isOutgoing }?.state ?: deliveryState(null, if (own) true else null, true, route),
                hops = hops, forwards = b.forwardCount, sinceMs = b.receivedAtMs, expiresAtMs = b.expiresAtMs,
                channel = b.channel, reached = reached(events, route),
            )
        }
        if ((myId8 != null && b.dest == myId8) || (route != null && route.me.isNotBlank() && route.destination == route.me)) return null
        val ownChat = chat?.takeIf { it.isOutgoing }
        val next = route?.let { r -> r.nextHop?.takeIf { it.isNotBlank() && it != r.me } }
        return DmRow(
            msgId8 = b.msgId, origin8 = b.origin, dest8 = b.dest, own = own, text = ownChat?.text,
            state = if (own) ownChat?.state ?: deliveryState(null, true, false, route) else deliveryState(null, null, false, route),
            hops = hops, forwards = b.forwardCount, sinceMs = b.receivedAtMs, expiresAtMs = b.expiresAtMs,
            nextHop8 = next, link = route?.takeIf { next != null }?.let { forwardLink(it, events) },
            relayTried = events.any { it.event == LifecycleEvent.FORWARDED && it.extra.contains("Internet relay") },
            inBuffer = true,
        )
    }

    /**
     * Forwarded = handed on and waiting for the receipt. Our own DM: a carrier or hub took a copy
     * (`deliveryState()` says Sent; a relay publish doesn't count, the relay stores nothing).
     * Someone else's: this phone handed a copy on.
     */
    fun handedOn(row: DmRow): Boolean =
        if (row.own) row.state == DeliveryState.IN_TRANSIT else row.nextHop8 != null || row.forwards > 0

    /** A delivered DM from the journal that this phone sent or carried (not one addressed to it). */
    private fun deliveredRow(r: RouteRecord, chat: ChatMessage?, events: List<LifecycleEntry>): DmRow? {
        if (r.isBroadcast || r.destination.isBlank() || r.destination == r.me) return null
        val own = r.source == r.me
        val ownChat = chat?.takeIf { it.isOutgoing }
        val state = ownChat?.state ?: deliveryState(null, if (own) true else null, false, r)
        if (state != DeliveryState.DELIVERED) return null
        val next = r.nextHop?.takeIf { it.isNotBlank() && it != r.me }
        return DmRow(
            msgId8 = r.msgId, origin8 = r.source, dest8 = r.destination, own = own, text = ownChat?.text, state = state,
            hops = hopsOf(events), forwards = events.count { it.event == LifecycleEvent.FORWARDED },
            sinceMs = ownChat?.createdAtMs?.takeIf { it > 0 } ?: r.createdAtMs, expiresAtMs = 0L,
            nextHop8 = next, link = if (next != null) forwardLink(r, events) else null, relayTried = false,
            inBuffer = false, deliveredAtMs = if (r.deliveredAtMs > 0) r.deliveredAtMs else r.timestampMs,
        )
    }

    /** The highest hop count in the journal events of one message (0 when none was recorded). */
    fun hopsOf(events: List<LifecycleEntry>): Int =
        events.filter { it.event in HOP_EVENTS && it.hopCount >= 0 }.maxOfOrNull { it.hopCount } ?: 0

    /** The phones / hubs this phone handed a copy to, from its FORWARDED events (first hand-off first). */
    fun reached(events: List<LifecycleEntry>, route: RouteRecord?): List<String> {
        val ids = LinkedHashSet<String>()
        events.filter { it.event == LifecycleEvent.FORWARDED }.forEach { e ->
            TO_PEER.findAll(e.extra).forEach { ids += it.groupValues[1] }
        }
        route?.let { r -> r.nextHop?.takeIf { it.isNotBlank() && it != r.me } }?.let { ids += it }
        route?.me?.let { ids -= it }
        return ids.toList()
    }

    /** The link a journal detail names ("… via BLE …"; a hub hand-off says "LoRa hub"), null if none. */
    fun linkIn(extra: String): String? =
        VIA_LINK.find(extra)?.groupValues?.get(1)?.let { TravelText.linkName(it) }
            ?: if (extra.contains("LoRa hub")) "LoRa hub" else null

    /** The link this phone handed the message on over (to [RouteRecord.nextHop]). */
    fun forwardLink(route: RouteRecord, events: List<LifecycleEntry>): String? {
        val next = route.nextHop?.takeIf { it.isNotBlank() } ?: return null
        return events.firstOrNull { it.event == LifecycleEvent.FORWARDED && it.extra.contains(next) }?.let { linkIn(it.extra) }
            ?: TravelText.linkName(route.lastPath)
    }

    /** The link the message reached this phone over (received or carried). */
    fun arrivalLink(route: RouteRecord, events: List<LifecycleEntry>): String? =
        events.firstOrNull {
            (it.event == LifecycleEvent.BUFFERED || it.event == LifecycleEvent.DELIVERED) && it.extra.contains("received from")
        }?.let { linkIn(it.extra) }
            // Not handed on yet: the last link recorded is still the one it arrived over.
            ?: if (route.nextHop.isNullOrBlank() || route.nextHop == route.me) TravelText.linkName(route.lastPath) else null
}

/** One message's journey through the buffer (Queue › History, Developer Tools › Buffer History), from the journal. */
internal data class BufferHistoryItem(
    val msgId: String,
    val origin: String,
    val dest: String,
    val bufferedAt: String,
    val clearedAt: String?,
    val clearEvent: LifecycleEvent?,
    val note: String,
)

/**
 * Reconstruct per-message buffer history from the (newest-first) lifecycle feed. A message
 * qualifies once it has a BUFFERED event; its disposition is the most recent terminal event
 * that removes it from the buffer. FORWARDED is intentionally NOT terminal — relaying to a
 * mule does not clear our buffer, only a receipt / direct delivery / expiry does.
 */
internal fun deriveBufferHistory(entries: List<LifecycleEntry>): List<BufferHistoryItem> {
    val clearing = setOf(
        LifecycleEvent.DELIVERED,
        LifecycleEvent.BUFFER_CLEARED,
        LifecycleEvent.EXPIRED,
        LifecycleEvent.HOP_LIMIT,
        LifecycleEvent.DROPPED,
    )
    val byId = LinkedHashMap<String, MutableList<LifecycleEntry>>()
    for (e in entries) byId.getOrPut(e.msgId) { mutableListOf() }.add(e)
    val out = mutableListOf<BufferHistoryItem>()
    for ((id, list) in byId) {
        // list is newest-first: lastOrNull match = earliest BUFFERED, firstOrNull = latest clear.
        val buffered = list.lastOrNull { it.event == LifecycleEvent.BUFFERED } ?: continue
        // Broadcasts and group messages ride the buffer epidemically until their TTL by design;
        // they never wait for anyone, so they're not store-and-forward records (the event log
        // still shows them).
        if (UiText.isBroadcastId(buffered.dest)) continue
        val cleared = list.firstOrNull { it.event in clearing }
        out.add(BufferHistoryItem(
            msgId = id,
            origin = buffered.origin,
            dest = buffered.dest,
            bufferedAt = buffered.timestamp,
            clearedAt = cleared?.timestamp,
            clearEvent = cleared?.event,
            note = cleared?.extra.orEmpty(),
        ))
    }
    return out
}

/** How the Queue and the message pages show an 8-character node id (pure). */
internal class NodeNames(peers: List<PeerInfo>, private val myId8: String?) {
    private val by8 = peers.associateBy { it.nodeId.takeLast(8) }

    fun peer(id8: String?): PeerInfo? = id8?.let { by8[it] }

    fun isHub(id8: String?): Boolean = peer(id8)?.isHub == true

    /** The full node id (avatars, the routing math): the contact's, else "!" + the 8 hex digits. */
    fun fullId(id8: String): String = peer(id8)?.nodeId ?: "!$id8"

    /** "You", "Everyone", "hub A9B0", the contact's name, else the id itself. */
    fun label(id8: String?): String = when {
        id8.isNullOrBlank() -> "Unknown"
        id8 == myId8 -> "You"
        UiText.isBroadcastId(id8) -> "Everyone"
        else -> peer(id8)?.let { if (it.isHub) hubLabel(it.nodeId) else it.displayName() } ?: id8
    }

    /** [label] plus the id, for traceability: "vrishank (0cdc860b)". "You" and bare ids stay as they are. */
    fun withId(id8: String?): String {
        val l = label(id8)
        return if (id8.isNullOrBlank() || l == id8 || l == "You" || l == "Everyone") l else "$l ($id8)"
    }
}

/** The Queue's row texts (pure, unit-tested). */
internal object QueueText {

    /** How urgent a queued message is, from its TTL left. */
    enum class Urgency(val label: String, val tone: Tone) {
        QUEUED("QUEUED", Tone.BLUE),
        TIME_SENSITIVE("TIME SENSITIVE", Tone.ORANGE),
        EXPIRING_SOON("EXPIRING SOON", Tone.RED),
    }

    fun ttlMinutes(expiresAtMs: Long, nowMs: Long): Long = ((expiresAtMs - nowMs) / 60_000L).coerceAtLeast(0)

    fun urgency(ttlMin: Long): Urgency = when {
        ttlMin <= 5 -> Urgency.EXPIRING_SOON
        ttlMin <= 30 -> Urgency.TIME_SENSITIVE
        else -> Urgency.QUEUED
    }

    /** TTL left: "45m", "3h 59m", "<1m" (about to expire), "0m". */
    fun ttl(expiresAtMs: Long, nowMs: Long): String {
        val left = expiresAtMs - nowMs
        if (left <= 0) return "0m"
        val min = left / 60_000L
        return when {
            min < 1 -> "<1m"
            min < 60 -> "${min}m"
            else -> "${min / 60}h ${min % 60}m"
        }
    }

    /** Compact age for the row corner: "now", "45s", "12m", "3h", "2d" ("" when unknown). */
    fun age(sinceMs: Long, nowMs: Long): String {
        if (sinceMs <= 0) return ""
        val s = ((nowMs - sinceMs) / 1000L).coerceAtLeast(0)
        return when {
            s < 5 -> "now"
            s < 60 -> "${s}s"
            s < 3_600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3_600}h"
            else -> "${s / 86_400}d"
        }
    }

    /** A stat-tile span: "45s", "12m", "3h 10m", "2d 4h". */
    fun span(fromMs: Long, toMs: Long): String {
        if (fromMs <= 0) return "—"
        val s = ((toMs - fromMs) / 1000L).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3_600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3_600}h ${(s % 3_600) / 60}m"
            else -> "${s / 86_400}d ${(s % 86_400) / 3_600}h"
        }
    }

    /** The status chip's words for the state `deliveryState()` gave the row. */
    fun chip(state: DeliveryState, broadcast: Boolean = false): String = when (state) {
        DeliveryState.QUEUED -> if (broadcast) "Waiting for phones" else "Waiting for a route"
        DeliveryState.IN_TRANSIT -> "Awaiting receipt"
        DeliveryState.CARRYING -> "Carrying"
        DeliveryState.SPREADING -> "Spreading"
        DeliveryState.RECEIVED_BY_SOME -> "Partly received · spreading"
        DeliveryState.RECEIVED_BY_ALL -> "Received by all ✓✓"
        DeliveryState.DELIVERED -> "Delivered ✓✓"
        DeliveryState.RECEIVED -> "Received"
        DeliveryState.EXPIRED -> "Expired"
        DeliveryState.DROPPED -> "Dropped"
    }

    /** "Reached 3 phones", "Reached 2 phones · 1 hub", "Not handed to anyone yet". */
    fun reached(phones: Int, hubs: Int): String {
        if (phones <= 0 && hubs <= 0) return "Not handed to anyone yet"
        val p = if (phones == 1) "1 phone" else "$phones phones"
        val h = if (hubs == 1) "1 hub" else "$hubs hubs"
        return when {
            hubs <= 0 -> "Reached $p"
            phones <= 0 -> "Reached $h"
            else -> "Reached $p · $h"
        }
    }

    /** Under "To: …": our own text, or "Carrying for vrishank · encrypted" for someone else's DM. */
    fun dmPreview(row: DmRow, label: (String) -> String): String = when {
        row.own -> row.text?.let { GroupInviteDm.preview(it) ?: it.replace('\n', ' ').take(80) } ?: "Your message (its chat was deleted)"
        row.state == DeliveryState.DELIVERED -> "Carried for ${label(row.origin8)} · encrypted"
        else -> "Carrying for ${label(row.origin8)} · encrypted"
    }

    /** The DM row's grey line: "Hops 1 · TTL 3h 59m · → hub A9B0 (LoRa hub)". */
    fun dmMeta(row: DmRow, nowMs: Long, label: (String) -> String): String {
        val parts = ArrayList<String>()
        parts += "Hops ${row.hops}"
        if (row.inBuffer) parts += "TTL ${ttl(row.expiresAtMs, nowMs)}"
        else if (row.deliveredAtMs > 0) parts += "delivered ${age(row.deliveredAtMs, nowMs).let { if (it == "now") "just now" else "$it ago" }}"
        val next = row.nextHop8
        parts += when {
            next != null -> "→ ${label(next)}" + (row.link?.let { " ($it)" } ?: "")
            row.relayTried -> "on the internet relay, waiting for the receipt"
            row.own -> "not handed on yet"
            else -> "no carrier yet"
        }
        return parts.joinToString(" · ")
    }

    /** The broadcast card's title: the group name, "Group message" (not a member), "Broadcast to All". */
    fun broadcastTitle(row: BroadcastRow, groupName: String?): String = when {
        row.channel <= 0 -> "Broadcast to All"
        groupName != null -> groupName
        else -> "Group message"
    }

    /** "You: …" / "vrishank: …" when readable here, else who it's from and why it can't be read. */
    fun broadcastPreview(row: BroadcastRow, member: Boolean, label: (String) -> String): String {
        val text = row.text?.replace('\n', ' ')?.take(80)
        return when {
            text != null -> (if (row.own) "You: " else "${label(row.origin8)}: ") + text
            row.channel > 0 && !member -> "From ${label(row.origin8)} · encrypted, this phone isn't in the group"
            else -> "From ${label(row.origin8)}"
        }
    }

    /** The broadcast card's grey line: "Hops 2 · TTL 3h 59m · Reached 3 phones". */
    fun broadcastMeta(row: BroadcastRow, nowMs: Long, isHub: (String) -> Boolean): String {
        val hubs = row.reached.count(isHub)
        return listOf("Hops ${row.hops}", "TTL ${ttl(row.expiresAtMs, nowMs)}", reached(row.reached.size - hubs, hubs))
            .joinToString(" · ")
    }

    /** How a History record left the buffer. */
    enum class HistoryOutcome { DELIVERED, EXPIRED, HOP_LIMIT, DROPPED, STILL_BUFFERED }

    /** How a History record left the buffer; the route's outcome fills in when no event said. */
    fun historyOutcome(item: BufferHistoryItem, route: RouteRecord?): HistoryOutcome = when (item.clearEvent) {
        LifecycleEvent.DELIVERED, LifecycleEvent.BUFFER_CLEARED -> HistoryOutcome.DELIVERED
        LifecycleEvent.EXPIRED -> HistoryOutcome.EXPIRED
        LifecycleEvent.HOP_LIMIT -> HistoryOutcome.HOP_LIMIT
        LifecycleEvent.DROPPED -> HistoryOutcome.DROPPED
        else -> when {
            route?.delivered == true -> HistoryOutcome.DELIVERED
            route?.outcome == "EXPIRED" -> HistoryOutcome.EXPIRED
            route?.outcome == "DROPPED" -> HistoryOutcome.DROPPED
            else -> HistoryOutcome.STILL_BUFFERED
        }
    }

    /** The coloured result of a History record. */
    fun historyResult(item: BufferHistoryItem, route: RouteRecord?): Pair<String, Tone> = when (historyOutcome(item, route)) {
        HistoryOutcome.DELIVERED -> "Delivered" to Tone.GREEN
        HistoryOutcome.EXPIRED -> "Expired" to Tone.ORANGE
        HistoryOutcome.HOP_LIMIT -> "Dropped · hop limit" to Tone.RED
        HistoryOutcome.DROPPED -> "Dropped" to Tone.RED
        HistoryOutcome.STILL_BUFFERED -> "Still buffered" to Tone.BLUE
    }

    /** One line under the chips saying what the segment holds. */
    fun segmentNote(segment: QueueSegment): String = when (segment) {
        QueueSegment.PENDING -> "Direct messages not handed on yet: waiting for a link toward the recipient or a better carrier."
        QueueSegment.FORWARDED -> "Handed to a carrier or a hub. This phone keeps its copy until the delivery receipt comes back."
        QueueSegment.DELIVERED -> "Confirmed by a direct link to the recipient or an end-to-end delivery receipt."
        QueueSegment.HISTORY -> "Every direct message that went through this phone's buffer, and how it left."
        QueueSegment.BROADCASTS -> "Broadcast and group messages spreading until their lifetime ends (a group member's " +
            "receipt doesn't stop them). Announces and receipts aren't messages: they stay in the logs."
    }

    /** "Why it's here" on the Queued message page. */
    fun whyHere(item: QueueItem, label: (String) -> String): String = when (item) {
        is BroadcastRow -> "Broadcasts and group messages go to every phone this one meets, once each, until their " +
            "lifetime ends. No delivery receipt removes them (a group member's receipt only tells the sender who " +
            "got it), so they stay in the buffer until then." +
            if (item.own && item.reached.isEmpty()) " Not handed to anyone yet." else ""
        is DmRow -> {
            val dest = label(item.dest8)
            val next = item.nextHop8?.let(label)
            when {
                item.state == DeliveryState.DELIVERED -> "Delivered to $dest: this copy leaves the buffer at the next clean-up."
                item.own && next != null -> "Handed to $next" + (item.link?.let { " over $it" } ?: "") + ". This phone keeps its " +
                    "copy until $dest's delivery receipt comes back, so it can hand it on again if that carrier never gets through."
                item.own && item.relayTried -> "Published to the internet relay, but no delivery receipt yet. The relay " +
                    "doesn't store messages, so this phone keeps it and sends it again whenever $dest is online."
                item.own -> "Waiting in this phone's buffer for a link toward $dest: a nearby phone, a LoRa hub or the " +
                    "internet relay. It goes straight to $dest when they're in reach, or to a carrier the routing algorithm picks."
                next != null -> "Carrying an encrypted DM from ${label(item.origin8)} to $dest. A copy went to $next; this phone " +
                    "keeps its own copy until a delivery receipt clears it or its lifetime ends."
                else -> "Carrying an encrypted DM from ${label(item.origin8)} to $dest (store-carry-forward). It's handed on " +
                    "when $dest or a better carrier comes in reach."
            }
        }
    }
}
