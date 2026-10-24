package com.dtn.mesh.ui.queue

import com.dtn.mesh.service.LifecycleEntry
import com.dtn.mesh.service.LifecycleEvent
import com.dtn.mesh.service.MessageLifecycleLog.RouteRecord
import com.dtn.mesh.ui.ChatMessage
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.GroupProgress
import com.dtn.mesh.ui.MemberReceipt
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.chats.TravelText
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.deliveryState
import com.dtn.mesh.ui.groups.GroupInviteDm

/** One stop on a message's path as seen from this phone. [reached]: the destination, and it got there. */
internal data class PathStop(val label: String, val isYou: Boolean = false, val isHub: Boolean = false, val reached: Boolean = false)

/**
 * The hop between two stops. [seen] = this phone saw it (and [link] names it when known): its own
 * hand-on, or the one that brought the message here. [waiting] = not sent yet.
 */
internal data class PathHop(val link: String?, val seen: Boolean, val waiting: Boolean = false)

/** The stops and the hops between them (`hops.size == stops.size - 1`). */
internal data class RoutePath(val stops: List<PathStop>, val hops: List<PathHop>) {
    /** One line: "You —LoRa hub→ hub A9B0 ⋯ raushan ✓" (⋯ = a hop this phone didn't see). */
    fun asText(): String = buildString {
        stops.forEachIndexed { i, s ->
            if (i > 0) {
                val h = hops[i - 1]
                append(
                    when {
                        !h.seen -> " ⋯ "
                        h.link != null -> " —${h.link}→ "
                        else -> " → "
                    },
                )
            }
            append(s.label)
            if (s.reached) append(" ✓")
        }
    }
}

/**
 * The routing decision as it was SAVED when this phone handed the message on — never recomputed
 * with the algorithm selected now.
 *
 * @property routedBy the algorithm behind that hand-on, null when none applies.
 * @property senderAlgorithm the sender's algorithm (a received DM carries it inside the encryption).
 * @property numbers the saved decision text ([RouteRecord.decision]) verbatim, null when none.
 * @property note why there's no saved decision, or what this one means.
 */
internal data class DecisionInfo(
    val routedBy: String?,
    val senderAlgorithm: String?,
    val numbers: String?,
    val savedAtMs: Long,
    val note: String?,
)

/** One journey step: coloured circle ([tone]), title, time, detail line and a pill on the right. */
internal data class JourneyStep(val title: String, val time: String, val detail: String, val tone: Tone, val pill: String, val pillTone: Tone)

internal data class DetailItem(val label: String, val value: String, val mono: Boolean = false)

/** Everything the Message page shows (all sections expanded). */
internal data class MessageTraceModel(
    val msgId8: String,
    val fullId: String,
    val state: DeliveryState,
    val explanation: String,
    /** The text, for own and received messages only. */
    val text: String?,
    /** When there's no text: why ("Encrypted for the recipient — this phone only carries it"). */
    val contentNote: String?,
    val path: RoutePath?,
    /** Hops as far as this phone knows (0 = none recorded). */
    val hops: Int,
    /** Broadcast / group: phones and hubs this phone handed a copy to (8-character ids). */
    val reached: List<String>,
    val isBroadcast: Boolean,
    val decision: DecisionInfo,
    val journey: List<JourneyStep>,
    val details: List<DetailItem>,
    /** This message's journal events in the journal's own line format (ids, hops, TTL, detail). */
    val eventLines: List<String>,
    /** Anything at all recorded for it on this phone. */
    val known: Boolean,
    /** Our own group message: each known member and their receipt (empty otherwise). */
    val members: List<MemberReceipt> = emptyList(),
    /** Our own group message's receipt count; null for every other message. */
    val groupProgress: GroupProgress? = null,
)

/** The Message page's content, built from the chat row, the route journal and the events (pure, unit-tested). */
internal object MessageTrace {

    /**
     * @param events this message's journal events, oldest first.
     * @param groupName the group's name when it's a group message ("channel N" once left), else null.
     * @param activeStrategy the algorithm selected now: only named for a message nobody routed yet.
     */
    fun build(
        msgId8: String,
        route: RouteRecord?,
        chat: ChatMessage?,
        events: List<LifecycleEntry>,
        names: NodeNames,
        groupName: String?,
        activeStrategy: String,
        time: (Long) -> String = { UiText.fullTime(it) },
    ): MessageTraceModel {
        val isMultiParty = chat?.isMultiParty ?: (route?.isBroadcast == true)
        // No chat row (deleted, blocked, carried): the route says whether we sent or received it.
        val outgoing: Boolean? = chat?.isOutgoing ?: route?.let {
            when {
                it.me.isBlank() -> null
                it.source == it.me -> true
                it.destination == it.me -> false
                else -> null
            }
        }
        val state = chat?.state ?: deliveryState(null, outgoing, isMultiParty, route)
        val sentAt = chat?.createdAtMs?.takeIf { it > 0 } ?: route?.createdAtMs?.takeIf { it > 0 } ?: events.firstOrNull()?.epochMs ?: 0L
        val deliveredAt = route?.deliveredAtMs ?: 0L
        val hops = QueueModel.hopsOf(events)
        val reached = if (isMultiParty) QueueModel.reached(events, route) else emptyList()

        val conversation = when {
            groupName != null -> "Group message — encrypted with the group key"
            isMultiParty -> "Public broadcast — not encrypted, anyone in reach can read it"
            else -> "Direct message — end-to-end encrypted"
        }
        val from = when {
            outgoing == true -> "You"
            route != null -> names.withId(route.source)
            chat != null && chat.from.isNotBlank() -> names.withId(chat.from.takeLast(8))
            else -> "Unknown"
        }
        val to = when {
            groupName != null -> "Group · $groupName"
            isMultiParty -> "Everyone in reach"
            outgoing == false -> "You"
            route != null -> names.withId(route.destination)
            else -> chat?.toNodeId?.let { names.withId(it.takeLast(8)) } ?: "Unknown"
        }
        val carriedDm = route != null && !route.isBroadcast && outgoing == null

        val details = buildList {
            add(DetailItem("Message ID", route?.fullId?.takeIf { it.isNotBlank() } ?: chat?.msgId ?: msgId8, mono = true))
            add(DetailItem("Status", listOf(state.short, state.tick).filter { it.isNotBlank() }.joinToString(" ")))
            add(DetailItem("Conversation", conversation))
            add(DetailItem("From", from))
            add(DetailItem("To", to))
            if (sentAt > 0) {
                add(DetailItem(when (outgoing) { true -> "Sent"; false -> "Received"; null -> "First seen here" }, time(sentAt)))
            }
            if (deliveredAt > 0 && outgoing != false) add(DetailItem("Delivered", time(deliveredAt)))
            route?.lastPath?.takeIf { it.isNotBlank() }?.let { add(DetailItem(if (outgoing == false) "Arrived over" else "Last link used", it)) }
            if (route != null) {
                route.prevHop?.takeIf { it.isNotBlank() && it != route.me }?.let { add(DetailItem("Handed over by", names.withId(it))) }
                route.nextHop?.takeIf { it.isNotBlank() && it != route.me }?.let { next ->
                    val toDest = state == DeliveryState.DELIVERED && next == route.destination
                    add(DetailItem(if (toDest) "Delivered to" else "Handed to", names.withId(next)))
                }
            }
            initialTtl(events)?.let { add(it) }
            if (hops > 0) add(DetailItem("Hops", if (hops == 1 && outgoing == false) "1 (came straight from the sender)" else "$hops"))
            if (reached.isNotEmpty()) add(DetailItem("Handed a copy to", reached.joinToString(", ") { names.label(it) }))
            chat?.group?.let { add(DetailItem("Received by", TravelText.progressLine(it))) }
            route?.takeIf { it.fallbackLinks.isNotEmpty() }?.let { r ->
                add(DetailItem("Fallback", r.fallbackLinks.joinToString(" + ") +
                    (if (r.fallbackAfter.isNotBlank()) " after ${r.fallbackAfter}" else "") + " (still queued 5 s after it was buffered)"))
            }
        }

        return MessageTraceModel(
            msgId8 = msgId8,
            fullId = route?.fullId?.takeIf { it.isNotBlank() } ?: chat?.msgId ?: msgId8,
            state = state,
            explanation = explanation(state, outgoing, isMultiParty, route, events, sentAt, deliveredAt, names, time, chat?.group),
            // A group invite DM: its name only (the key stays in the chat's Join card).
            text = chat?.text?.let { GroupInviteDm.preview(it) ?: it },
            contentNote = when {
                chat != null -> null
                carriedDm -> "Encrypted for the recipient — this phone only carries it."
                else -> "Not readable on this phone."
            },
            path = route?.let { routePath(it, events, names) },
            hops = hops,
            reached = reached,
            isBroadcast = isMultiParty,
            decision = decision(route, activeStrategy),
            journey = journey(events),
            details = details,
            eventLines = events.map { it.toLogLine() },
            known = route != null || chat != null || events.isNotEmpty(),
            members = chat?.members.orEmpty(),
            groupProgress = chat?.group,
        )
    }

    /** One member row of "Received by": "✓ <time> · via LoRa hub (hub BDB8)" or "Not yet". */
    fun memberLine(m: MemberReceipt, time: (Long) -> String): String = TravelText.memberLine(m, time)

    /** "Initial TTL" for our own messages, else the TTL it had when it reached this phone. */
    private fun initialTtl(events: List<LifecycleEntry>): DetailItem? {
        events.firstOrNull { it.event == LifecycleEvent.CREATED && it.ttlRemainingMin >= 0 }
            ?.let { return DetailItem("Initial TTL", "${it.ttlRemainingMin} min") }
        return events.firstOrNull {
            (it.event == LifecycleEvent.BUFFERED || it.event == LifecycleEvent.DELIVERED) && it.ttlRemainingMin >= 0
        }?.let { DetailItem("TTL when it reached this phone", "${it.ttlRemainingMin} min") }
    }

    /** The status explanation under the big pill. */
    fun explanation(
        state: DeliveryState,
        outgoing: Boolean?,
        isMultiParty: Boolean,
        route: RouteRecord?,
        events: List<LifecycleEntry>,
        sentAt: Long,
        deliveredAt: Long,
        names: NodeNames,
        time: (Long) -> String,
        group: GroupProgress? = null,
    ): String = when (state) {
        DeliveryState.DELIVERED -> buildString {
            append(if (deliveredAt > 0) "Delivered ${time(deliveredAt)}" else "Delivered")
            if (outgoing == true && sentAt > 0 && deliveredAt >= sentAt) append(" — took ${UiText.duration(sentAt, deliveredAt)}")
            append(".")
            TravelText.linkName(route?.lastPath)?.let { append(" Last link: $it.") }
        }
        DeliveryState.IN_TRANSIT -> {
            val next = route?.nextHop?.let { names.label(it) } ?: "the next hop"
            val link = route?.let { QueueModel.forwardLink(it, events) }?.let { " via $it" }.orEmpty()
            "Handed to $next$link. It shows Delivered once the recipient's phone sends its receipt back."
        }
        DeliveryState.QUEUED -> {
            val relayTry = events.lastOrNull { it.event == LifecycleEvent.FORWARDED && it.extra.contains("Internet relay") }
            when {
                isMultiParty -> "Waiting in this phone's buffer until it meets someone — a nearby phone, a LoRa hub or the internet relay."
                relayTry != null -> "Published to the internet relay (${relayTry.timestamp}), but no delivery receipt yet. The relay " +
                    "doesn't store messages, so this phone keeps it and sends it again whenever the recipient is online."
                else -> "Waiting in this phone's buffer for a link toward the recipient — a nearby phone, a LoRa hub or the internet relay."
            }
        }
        DeliveryState.SPREADING -> when {
            group == null && (route?.channel ?: 0) > 0 -> "Every phone that gets a copy passes it on until its lifetime " +
                "ends. Members' phones send their group receipts to the sender."
            group == null ->
                "Every phone that gets a copy passes it on until its lifetime ends. Public broadcasts have no delivery receipts."
            group.known == 0 -> "Every phone that gets a copy passes it on until its lifetime ends. A member's phone sends " +
                "a group receipt the first time it reads it; no member of this group is known here yet."
            else -> "Every phone that gets a copy passes it on until its lifetime ends. None of the ${group.known} known " +
                "member${if (group.known == 1) "" else "s"} has sent a group receipt yet."
        }
        DeliveryState.RECEIVED_BY_SOME -> "Received by ${group?.received ?: 0} of ${group?.known ?: 0} known members (their " +
            "phones sent a group receipt). It keeps spreading to the others until its lifetime ends."
        DeliveryState.RECEIVED_BY_ALL -> "Every known member's phone (${group?.known ?: 0}) sent a group receipt. It still " +
            "spreads until its lifetime ends, for members this phone doesn't know of."
        DeliveryState.CARRYING ->
            "This phone carries an encrypted copy for someone else and hands it on when it meets a better carrier."
        DeliveryState.RECEIVED -> {
            val link = route?.let { QueueModel.arrivalLink(it, events) }?.let { " via $it" }.orEmpty()
            val prev = route?.let { r -> r.prevHop?.takeIf { it.isNotBlank() && it != r.me } }
                ?.let { " from ${names.label(it)}" }.orEmpty()
            "Received$link$prev."
        }
        DeliveryState.EXPIRED -> "Its lifetime ran out before a path to the recipient appeared."
        DeliveryState.DROPPED -> "It left the buffer before delivery (cleared, or evicted while the buffer was full)."
    }

    /**
     * The path hop by hop as seen from this phone: who it came from (the hops before the previous
     * one aren't visible here, ⋯), this phone, who it was handed to, and the destination.
     */
    fun routePath(route: RouteRecord, events: List<LifecycleEntry>, names: NodeNames): RoutePath {
        val me = route.me
        fun stop(id: String, reached: Boolean = false) = PathStop(
            label = if (id == me) "You" else names.label(id),
            isYou = id == me,
            isHub = id != me && names.isHub(id),
            reached = reached,
        )
        val stops = ArrayList<PathStop>()
        val hops = ArrayList<PathHop>()
        val own = route.source == me
        val forUs = !route.isBroadcast && route.destination == me
        if (own) {
            stops += stop(me)
        } else {
            stops += stop(route.source)
            route.prevHop?.takeIf { it.isNotBlank() && it != route.source && it != me }?.let { prev ->
                hops += PathHop(null, seen = false)
                stops += stop(prev)
            }
            hops += PathHop(QueueModel.arrivalLink(route, events), seen = true)
            stops += stop(me, reached = forUs)
        }
        if (forUs) return RoutePath(stops, hops)

        val next = route.nextHop?.takeIf { it.isNotBlank() && it != me }
        when {
            next != null -> {
                val toDest = !route.isBroadcast && next == route.destination
                hops += PathHop(QueueModel.forwardLink(route, events), seen = true)
                stops += stop(next, reached = toDest && route.delivered)
                if (!route.isBroadcast && !toDest && route.destination.isNotBlank()) {
                    hops += PathHop(null, seen = false)
                    stops += stop(route.destination, reached = route.delivered)
                }
            }
            route.isBroadcast -> if (own && route.lastPath.isNotBlank()) {
                // Published to every relay subscriber at once: no single next hop.
                hops += PathHop(TravelText.linkName(route.lastPath), seen = true)
                stops += PathStop("Everyone")
            }
            route.destination.isNotBlank() -> {
                hops += PathHop(null, seen = false, waiting = !route.delivered)
                stops += stop(route.destination, reached = route.delivered)
            }
        }
        return RoutePath(stops, hops)
    }

    /** The routing decision SAVED at forward time ([RouteRecord.decision]); [activeStrategy] only names who will decide. */
    fun decision(route: RouteRecord?, activeStrategy: String): DecisionInfo {
        if (route == null) {
            return DecisionInfo(null, null, null, 0L, "No routing decision recorded: this phone has no journey for this message.")
        }
        val sender = route.senderStrategy.takeIf { it.isNotBlank() }
        val forUs = !route.isBroadcast && route.destination == route.me
        // A relay hand-off isn't a routing decision, and broadcasts go to everyone met.
        val routedBy = route.strategy.takeIf { it.isNotBlank() && it != "INTERNET RELAY" && !route.isBroadcast && !forUs }
        val numbers = route.decision.takeIf { it.isNotBlank() && !route.isBroadcast && !forUs }
        val note = when {
            route.isBroadcast -> "Broadcasts and group messages go to every phone met, once each: no routing algorithm picks a carrier."
            forUs -> "This phone is the destination, so it made no routing decision for it."
            numbers != null -> null
            route.nextHop == null && !route.delivered -> "Not handed to anyone yet. The algorithm selected when it is " +
                "forwarded decides (now: $activeStrategy), and that decision will show here."
            else -> "The routing numbers weren't recorded for this message — it was handed on by an older version of the app."
        }
        return DecisionInfo(routedBy, sender, numbers, if (numbers != null) route.decisionAtMs else 0L, note)
    }

    /** The journey timeline, oldest first (Created blue, Buffered amber, Forwarded orange, Receipt green, Cleared grey). */
    fun journey(events: List<LifecycleEntry>): List<JourneyStep> = events.map { e ->
        val metrics = listOfNotNull(
            e.hopCount.takeIf { it >= 0 }?.let { "hop $it" },
            e.forwardCount.takeIf { it >= 0 }?.let { "forward $it" },
            e.ttlRemainingMin.takeIf { it >= 0 }?.let { "TTL $it min" },
        ).joinToString(" · ")
        val (tone, pill, pillTone) = when (e.event) {
            LifecycleEvent.CREATED -> Triple(Tone.BLUE, "Internal event", Tone.GREY)
            LifecycleEvent.BUFFERED, LifecycleEvent.DUPLICATE -> Triple(Tone.ORANGE, "Internal event", Tone.GREY)
            LifecycleEvent.IGNORED -> Triple(Tone.GREY, "Internal event", Tone.GREY)
            LifecycleEvent.FORWARDED -> if (e.extra.contains("✓ delivered")) Triple(Tone.GREEN, "Delivered", Tone.GREEN)
                else Triple(Tone.ORANGE, "Handed on", Tone.ORANGE)
            LifecycleEvent.ACK_GENERATED, LifecycleEvent.ACK_RECEIVED -> Triple(Tone.GREEN, "Receipt", Tone.GREEN)
            LifecycleEvent.BUFFER_CLEARED -> Triple(Tone.GREY, "Cleared", Tone.GREY)
            LifecycleEvent.DELIVERED -> Triple(Tone.GREEN, "Delivered", Tone.GREEN)
            LifecycleEvent.EXPIRED -> Triple(Tone.RED, "Expired", Tone.RED)
            LifecycleEvent.HOP_LIMIT, LifecycleEvent.DROPPED -> Triple(Tone.RED, "Dropped", Tone.RED)
        }
        JourneyStep(title(e.event), e.timestamp, listOf(e.extra, metrics).filter { it.isNotBlank() }.joinToString(" · "), tone, pill, pillTone)
    }

    /** Under a broadcast's route: how many phones / hubs this phone handed a copy to. */
    fun copiesLine(n: Int): String = when {
        n <= 0 -> "This phone hasn't handed it to anyone yet."
        n == 1 -> "This phone handed a copy to 1 phone or hub."
        else -> "This phone handed a copy to $n phones or hubs."
    }

    fun title(event: LifecycleEvent): String = when (event) {
        LifecycleEvent.CREATED -> "Created"
        LifecycleEvent.BUFFERED -> "Buffered"
        LifecycleEvent.DUPLICATE -> "Duplicate received"
        LifecycleEvent.FORWARDED -> "Forwarded"
        LifecycleEvent.IGNORED -> "Ignored"
        LifecycleEvent.ACK_GENERATED -> "Receipt created"
        LifecycleEvent.ACK_RECEIVED -> "Receipt received"
        LifecycleEvent.BUFFER_CLEARED -> "Cleared from the buffer"
        LifecycleEvent.DELIVERED -> "Delivered"
        LifecycleEvent.EXPIRED -> "Expired"
        LifecycleEvent.HOP_LIMIT -> "Hop limit reached"
        LifecycleEvent.DROPPED -> "Dropped"
    }

    /** "Copy all" on the Details card: the details, the route path and the routing labels. Never the message text. */
    fun copyAllText(m: MessageTraceModel): String = buildString {
        m.details.forEach { appendLine("${it.label}: ${it.value}") }
        m.path?.let { appendLine("Route (seen from this phone): ${it.asText()}") }
        m.decision.routedBy?.let { appendLine("Routed by: $it") }
        m.decision.senderAlgorithm?.let { appendLine("Sender's algorithm: $it") }
    }.trimEnd()

    /**
     * "Export event details": everything on the page that isn't the message text — details, route,
     * the saved decision with its numbers, and this message's journal events.
     */
    fun exportText(m: MessageTraceModel, exportedAt: String, time: (Long) -> String = { UiText.fullTime(it) }): String = buildString {
        appendLine("DTN Mesh Relay · message event details")
        appendLine("Exported $exportedAt")
        appendLine("Ids, links, hops and times only — no message text.")
        appendLine()
        appendLine("Status: ${m.state.pill}${if (m.state.tick.isNotBlank()) " ${m.state.tick}" else ""}")
        appendLine(m.explanation)
        appendLine()
        appendLine(copyAllText(m))
        appendLine()
        val d = m.decision
        if (d.numbers != null) {
            appendLine("Saved routing decision" + (if (d.savedAtMs > 0) " (${time(d.savedAtMs)})" else "") + ":")
            d.numbers.lines().forEach { appendLine("  $it") }
        } else {
            appendLine("Saved routing decision: none")
        }
        d.note?.let { appendLine(it) }
        appendLine()
        appendLine("Journey: ${m.eventLines.size} event${if (m.eventLines.size == 1) "" else "s"}, oldest first")
        m.eventLines.forEach { appendLine(it) }
    }.trimEnd() + "\n"
}
