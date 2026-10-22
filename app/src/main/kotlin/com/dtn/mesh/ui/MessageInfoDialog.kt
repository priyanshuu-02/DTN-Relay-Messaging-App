package com.dtn.mesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.service.LifecycleEntry

/** Colour for a delivery state (bubble ticks, pills, route rails). */
internal fun stateColor(state: DeliveryState): Color = when (state) {
    DeliveryState.DELIVERED, DeliveryState.RECEIVED -> Palette.Accent
    DeliveryState.IN_TRANSIT -> Palette.Primary
    DeliveryState.SPREADING -> Palette.Broadcast
    DeliveryState.QUEUED -> Palette.Warning
    DeliveryState.CARRYING -> Palette.PrimaryDark
    DeliveryState.EXPIRED, DeliveryState.DROPPED -> Palette.Error
}

/**
 * Everything this phone knows about one message, live: the unified delivery state, content,
 * who it's from / to, when it was sent and delivered, the link and hops it used, and its full
 * journey from the persistent journal. Opened from a chat bubble, a route row or the Lifecycle
 * tab; it updates in place (e.g. flips to Delivered when the receipt arrives while it's open).
 *
 * @param msgId8 first 8 characters of the message id (the key shared by chat rows and journal).
 */
@Composable
fun MessageInfoDialog(viewModel: DtnViewModel, msgId8: String, onDismiss: () -> Unit) {
    val routes by viewModel.messageRoutes.collectAsState()
    val chats by viewModel.receivedMessages.collectAsState()
    val entries by viewModel.lifecycleEntries.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val myId8 = localNodeId?.takeLast(8)

    val route = routes.firstOrNull { it.msgId == msgId8 }
    val chat = chats.firstOrNull { it.msgId.take(8) == msgId8 }
    // Journal entries are newest-first; the timeline reads oldest-first.
    val events = remember(entries, msgId8) { entries.filter { it.msgId == msgId8 }.asReversed() }

    val channel = chat?.toNodeId?.takeIf { it.startsWith("grp:") }?.removePrefix("grp:")?.toIntOrNull()
        ?: route?.channel?.takeIf { it > 0 }
    val isMultiParty = chat?.isMultiParty ?: (route?.isBroadcast == true)
    val state = chat?.state ?: deliveryState(null, null, isMultiParty, route)
    val color = stateColor(state)

    val fromText = when {
        chat?.isOutgoing == true -> "You"
        route != null -> labelWithId(peerLabelFor(route.source, peers, myId8), route.source)
        chat != null && chat.from.isNotBlank() ->
            labelWithId(peers.firstOrNull { it.nodeId == chat.from }?.displayName(), chat.from.takeLast(8))
        else -> "Unknown"
    }
    val groupName = channel?.let { ch -> groups.firstOrNull { it.channel == ch }?.name ?: "channel $ch" }
    val toText = when {
        groupName != null -> "Group · $groupName"
        isMultiParty -> "Everyone in reach"
        chat?.isOutgoing == false -> "You"
        route != null -> labelWithId(peerLabelFor(route.destination, peers, myId8), route.destination)
        else -> chat?.toNodeId?.let { to -> labelWithId(peers.firstOrNull { it.nodeId == to }?.displayName(), to.takeLast(8)) }
            ?: "Unknown"
    }
    val conversation = when {
        groupName != null -> "Group message — encrypted with the group key"
        isMultiParty -> "Public broadcast — not encrypted, anyone in reach can read it"
        else -> "Direct message — end-to-end encrypted"
    }

    val sentAt = chat?.createdAtMs?.takeIf { it > 0 } ?: route?.createdAtMs ?: 0L
    val deliveredAt = route?.deliveredAtMs?.takeIf { it > 0 } ?: 0L
    val nextLabel = route?.nextHop?.let { peerLabelFor(it, peers, myId8) ?: it }
    val prevLabel = route?.prevHop?.let { peerLabelFor(it, peers, myId8) ?: it }
    val hops = events.filter { it.event == com.dtn.mesh.service.LifecycleEvent.DELIVERED ||
        it.event == com.dtn.mesh.service.LifecycleEvent.BUFFERED }.maxOfOrNull { it.hopCount } ?: -1

    val statusDetail = when (state) {
        DeliveryState.DELIVERED -> buildString {
            append(if (deliveredAt > 0) "Delivered ${UiText.fullTime(deliveredAt)}" else "Delivered")
            if (chat?.isOutgoing == true && sentAt > 0 && deliveredAt >= sentAt) {
                append(" — took ${UiText.duration(sentAt, deliveredAt)}")
            }
            append(".")
            route?.lastPath?.takeIf { it.isNotBlank() }?.let { append(" Last link: $it.") }
        }
        DeliveryState.IN_TRANSIT ->
            "Handed to ${nextLabel ?: "the next hop"}" + (route?.lastPath?.takeIf { it.isNotBlank() }?.let { " via $it" } ?: "") +
                ". It shows Delivered once the recipient's phone sends its receipt back."
        DeliveryState.QUEUED -> {
            val relayTry = events.lastOrNull {
                it.event == com.dtn.mesh.service.LifecycleEvent.FORWARDED && it.extra.contains("Internet relay")
            }
            when {
                isMultiParty ->
                    "Waiting in this phone's buffer until it meets someone — a nearby phone, a LoRa hub or the internet relay."
                relayTry != null ->
                    "Published to the internet relay (${relayTry.timestamp}), but no delivery receipt yet. The relay " +
                        "doesn't store messages, so this phone keeps it and sends it again whenever the recipient is online."
                else ->
                    "Waiting in this phone's buffer for a link toward the recipient — a nearby phone, a LoRa hub or the internet relay."
            }
        }
        DeliveryState.SPREADING ->
            "Every phone that gets a copy passes it on until its lifetime ends. Group and broadcast messages have no delivery receipts."
        DeliveryState.CARRYING ->
            "This phone carries an encrypted copy for someone else and hands it on when it meets a better carrier."
        DeliveryState.RECEIVED ->
            "Received" + (route?.lastPath?.takeIf { it.isNotBlank() }?.let { " via $it" } ?: "") +
                (prevLabel?.let { " from $it" } ?: "") + "."
        DeliveryState.EXPIRED -> "Its lifetime ran out before a path to the recipient appeared."
        DeliveryState.DROPPED -> "It left the buffer before delivery (cleared, or evicted while the buffer was full)."
    }

    // Forwarding math is only meaningful for a DM this phone routes onward (not one addressed to
    // us, and not a broadcast, which floods without per-carrier scoring).
    val explanation = remember(route?.destination, peers, myId8) {
        val dest = route?.destination
        if (route == null || route.isBroadcast || dest == null || dest == myId8) null
        else peers.firstOrNull { it.nodeId.takeLast(8) == dest }?.nodeId?.let { viewModel.explainRoute(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Column {
                Text("Message info", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.TextPrimary)
                Text("MSG $msgId8", fontSize = 11.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.15f)) {
                    Text(
                        listOf(state.pill, state.tick).filter { it.isNotBlank() }.joinToString("  "),
                        fontSize = 11.sp, color = color, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(statusDetail, fontSize = 12.sp, color = Palette.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))

                if (chat != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (chat.isOutgoing) Palette.BubbleOut else Palette.Surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(chat.text, fontSize = 14.sp, color = Palette.TextPrimary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    val carriedDm = route != null && !route.isBroadcast && route.destination != myId8
                    DetailRow("Content", if (carriedDm) "Encrypted for the recipient — this phone only carries it"
                        else "Not readable on this phone")
                }

                DetailRow("Conversation", conversation)
                DetailRow("From", fromText)
                DetailRow("To", toText)
                if (sentAt > 0) {
                    DetailRow(
                        when {
                            chat?.isOutgoing == true -> "Sent"
                            chat != null -> "Received"
                            else -> "First seen here"
                        },
                        UiText.fullTime(sentAt),
                    )
                }
                if (deliveredAt > 0 && chat?.isOutgoing != false) DetailRow("Delivered", UiText.fullTime(deliveredAt))
                if (route != null) {
                    if (route.lastPath.isNotBlank()) {
                        DetailRow(if (chat?.isOutgoing == false) "Arrived over" else "Last link used", route.lastPath)
                    }
                    if (!route.prevHop.isNullOrBlank() && route.prevHop != route.me) {
                        DetailRow("Handed over by", labelWithId(peerLabelFor(route.prevHop, peers, myId8), route.prevHop))
                    }
                    if (!route.nextHop.isNullOrBlank() && route.nextHop != route.me) {
                        DetailRow(if (state == DeliveryState.DELIVERED && chat?.isOutgoing == true) "Delivered to" else "Handed to",
                            labelWithId(peerLabelFor(route.nextHop, peers, myId8), route.nextHop))
                    }
                    if (hops > 0) DetailRow("Hops travelled", if (hops == 1) "1 (came straight from the sender)" else "$hops")
                    // A relay hand-off isn't a routing decision (and the link line already says it).
                    if (route.strategy.isNotBlank() && route.strategy != "INTERNET RELAY") DetailRow("Routed by", route.strategy)
                    DetailRow("Path seen from this phone", formatObservedPath(route, peers, myId8), mono = true)
                } else {
                    Text("No journey recorded for this message — it was sent before this version of the app, " +
                        "or is older than the journal keeps.", fontSize = 11.sp, color = Palette.TextMuted,
                        modifier = Modifier.padding(bottom = 8.dp))
                }

                if (events.isNotEmpty()) {
                    HorizontalDivider(color = Palette.Divider, modifier = Modifier.padding(vertical = 8.dp))
                    Text("JOURNEY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.Primary, letterSpacing = 0.8.sp)
                    Spacer(Modifier.height(6.dp))
                    events.forEachIndexed { i, e -> JourneyRow(e, isLast = i == events.lastIndex) }
                }

                if (explanation != null && route != null) {
                    HorizontalDivider(color = Palette.Divider, modifier = Modifier.padding(vertical = 8.dp))
                    RoutingMathSection(
                        exp = explanation,
                        destLabel = peerLabelFor(route.destination, peers, myId8) ?: route.destination,
                        retrospective = route.delivered,
                        prevHop = route.prevHop,
                        nextHop = route.nextHop,
                    )
                }
            }
        },
    )
}

/** One step of the journey: coloured dot + rail, time, what happened, and the detail line. */
@Composable
private fun JourneyRow(e: LifecycleEntry, isLast: Boolean) {
    val c = lifecycleColor(e.event)
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp).fillMaxHeight()) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.size(9.dp).clip(CircleShape).background(c))
            if (!isLast) Box(Modifier.width(2.dp).weight(1f).background(Palette.Divider))
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f).padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(lifecycleLabel(e.event), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c)
                Text(e.timestamp, fontSize = 10.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
            }
            if (e.extra.isNotBlank()) Text(e.extra, fontSize = 11.sp, color = Palette.TextSecondary)
        }
    }
}
