package com.dtn.mesh.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dtn.mesh.service.MessageLifecycleLog.RouteRecord
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.GroupInfo
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.chats.ChatAvatar
import com.dtn.mesh.ui.chats.ChatKind
import com.dtn.mesh.ui.chats.tone
import com.dtn.mesh.ui.components.Avatar
import com.dtn.mesh.ui.components.ChipRow
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.MenuAction
import com.dtn.mesh.ui.components.OverflowMenu
import com.dtn.mesh.ui.components.StatusChip
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.delay

/**
 * Queue: Pending · Forwarded · Delivered · History · Broadcasts & groups. The DM segments list
 * direct messages only (like the "N queued" counters); Broadcasts & groups lists the broadcast and
 * group messages this phone is still spreading. Announces and receipts never appear: they're
 * control traffic. A buffered message opens the Queued message page (live routing math); a
 * delivered or past one opens the Message page.
 */
@Composable
fun QueuePage(viewModel: DtnViewModel, onOpenQueued: (String) -> Unit, onOpenMessage: (String) -> Unit) {
    val bundles by viewModel.queueBundles.collectAsState()
    val routes by viewModel.messageRoutes.collectAsState()
    val chats by viewModel.receivedMessages.collectAsState()
    val entries by viewModel.lifecycleEntries.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val myId8 = localNodeId?.takeLast(8)
    var segment by rememberSaveable { mutableStateOf(QueueSegment.PENDING) }
    val now by rememberNow(15_000L)

    val lists = remember(bundles, routes, chats, entries, myId8) { QueueModel.build(bundles, routes, chats, entries, myId8) }
    val names = remember(peers, myId8) { NodeNames(peers, myId8) }
    val routeById = remember(routes) { routes.associateBy { it.msgId } }
    val openChat: (String) -> Unit = { viewModel.openChat(it) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item(key = "segments") {
            ChipRow(
                options = QueueSegment.entries.map { s ->
                    // Counts on the live segments only; Delivered and History are records.
                    val n = lists.count(s)
                    if (n > 0 && s != QueueSegment.DELIVERED && s != QueueSegment.HISTORY) "${s.label} ($n)" else s.label
                },
                selected = segment.ordinal,
                onSelect = { segment = QueueSegment.entries[it] },
            )
            Text(QueueText.segmentNote(segment), style = DtnType.Meta, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 10.dp))
        }
        when (segment) {
            QueueSegment.PENDING -> dmCards("pend", lists.pending, names, now, onOpenQueued, onOpenMessage, openChat)
            QueueSegment.FORWARDED -> dmCards("fwd", lists.forwarded, names, now, onOpenQueued, onOpenMessage, openChat)
            QueueSegment.DELIVERED -> dmCards("dlv", lists.delivered, names, now, onOpenQueued, onOpenMessage, openChat)
            QueueSegment.HISTORY -> historyCards(lists.history, routeById, names, onOpenMessage)
            QueueSegment.BROADCASTS -> broadcastCards(lists.broadcasts, groups, names, now, onOpenQueued, onOpenMessage, openChat)
        }
        if (lists.count(segment) == 0) item(key = "empty-${segment.name}") { SegmentEmpty(segment) }
        item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
    }
}

/** The current time, refreshed every [periodMs] (ages and TTLs count down on screen). */
@Composable
internal fun rememberNow(periodMs: Long): State<Long> = produceState(System.currentTimeMillis(), periodMs) {
    while (true) {
        delay(periodMs)
        value = System.currentTimeMillis()
    }
}

private fun LazyListScope.dmCards(
    prefix: String,
    rows: List<DmRow>,
    names: NodeNames,
    now: Long,
    onOpenQueued: (String) -> Unit,
    onOpenMessage: (String) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    items(rows, key = { "$prefix-${it.msgId8}" }) { row ->
        val menu = buildList {
            if (row.inBuffer) add(MenuAction("Message details") { onOpenMessage(row.msgId8) })
            if (row.own) add(MenuAction("Open chat") { onOpenChat(names.fullId(row.dest8)) })
        }
        DmCard(row, names, now, onOpen = { if (row.inBuffer) onOpenQueued(row.msgId8) else onOpenMessage(row.msgId8) }, menu = menu)
        Spacer(Modifier.height(10.dp))
    }
}

private fun LazyListScope.broadcastCards(
    rows: List<BroadcastRow>,
    groups: List<GroupInfo>,
    names: NodeNames,
    now: Long,
    onOpenQueued: (String) -> Unit,
    onOpenMessage: (String) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    items(rows, key = { "bc-${it.msgId8}" }) { row ->
        val group = groups.firstOrNull { it.channel == row.channel }?.takeIf { row.channel > 0 }
        val chatKey = when {
            row.channel <= 0 -> DtnViewModel.BROADCAST_CHAT_ID
            group != null -> "grp:${group.channel}"
            else -> null
        }
        val menu = buildList {
            add(MenuAction("Message details") { onOpenMessage(row.msgId8) })
            if (chatKey != null) add(MenuAction("Open chat") { onOpenChat(chatKey) })
        }
        BroadcastCard(row, group?.name, names, now, onOpen = { onOpenQueued(row.msgId8) }, menu = menu)
        Spacer(Modifier.height(10.dp))
    }
}

private fun LazyListScope.historyCards(
    records: List<BufferHistoryItem>,
    routeById: Map<String, RouteRecord>,
    names: NodeNames,
    onOpenMessage: (String) -> Unit,
) {
    items(records, key = { "hist-${it.msgId}" }) { item ->
        HistoryCard(item, routeById[item.msgId], names, onOpen = { onOpenMessage(item.msgId) })
        Spacer(Modifier.height(10.dp))
    }
}

/** Avatar, "To: Nisha", the text or "Carrying for …", age, "Hops 1 · TTL 3h 59m · → hub A9B0 (LoRa hub)", the status chip, ⋮. */
@Composable
private fun DmCard(row: DmRow, names: NodeNames, now: Long, onOpen: () -> Unit, menu: List<MenuAction>) {
    val dest = names.label(row.dest8)
    DtnCard(
        onClick = onOpen,
        onClickLabel = if (row.inBuffer) "Show the queued message" else "Show message details",
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = if (menu.isEmpty()) 12.dp else 0.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Avatar(UiText.avatarInitial(dest), DtnColors.avatarFor(names.fullId(row.dest8)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("To: $dest", style = DtnType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(QueueText.age(if (row.deliveredAtMs > 0) row.deliveredAtMs else row.sinceMs, now), style = DtnType.Meta)
                }
                Text(QueueText.dmPreview(row, names::label), style = DtnType.RowSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(QueueText.dmMeta(row, now, names::label), style = DtnType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    StatusChip(QueueText.chip(row.state), row.state.tone())
                }
            }
            if (menu.isNotEmpty()) OverflowMenu(menu, contentDescription = "More options for the message to $dest")
        }
    }
}

/** Lavender group / purple 📢 avatar, the group or "Broadcast to All", the text when readable, TTL and phones reached. */
@Composable
private fun BroadcastCard(row: BroadcastRow, groupName: String?, names: NodeNames, now: Long, onOpen: () -> Unit, menu: List<MenuAction>) {
    val title = QueueText.broadcastTitle(row, groupName)
    DtnCard(
        onClick = onOpen,
        onClickLabel = "Show the queued message",
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 0.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            ChatAvatar(if (row.channel > 0) ChatKind.GROUP else ChatKind.BROADCAST, title, key = "")
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = DtnType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(QueueText.age(row.sinceMs, now), style = DtnType.Meta)
                }
                Text(QueueText.broadcastPreview(row, member = groupName != null, label = names::label), style = DtnType.RowSubtitle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(QueueText.broadcastMeta(row, now, names::isHub), style = DtnType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    StatusChip(QueueText.chip(row.state, broadcast = true), row.state.tone())
                }
            }
            OverflowMenu(menu, contentDescription = "More options for $title")
        }
    }
}

/** A store-and-forward record: to / from, buffered → left times, how it left, the journal's note (Queue › History, Buffer History). */
@Composable
internal fun HistoryCard(item: BufferHistoryItem, route: RouteRecord?, names: NodeNames, onOpen: () -> Unit) {
    val dest = names.label(item.dest.ifBlank { null })
    val (result, tone) = QueueText.historyResult(item, route)
    DtnCard(onClick = onOpen, onClickLabel = "Show message details", contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Avatar(UiText.avatarInitial(dest), DtnColors.avatarFor(names.fullId(item.dest)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("To: $dest", style = DtnType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    StatusChip(result, tone)
                }
                Text("From: ${names.label(item.origin.ifBlank { null })}", style = DtnType.RowSubtitle, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("Buffered ${item.bufferedAt}" + (item.clearedAt?.let { " → left $it" } ?: ""), style = DtnType.Meta)
                if (item.note.isNotBlank()) Text(item.note, style = DtnType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SegmentEmpty(segment: QueueSegment) {
    when (segment) {
        QueueSegment.PENDING -> EmptyState(DtnIcons.Queue, "No more pending messages",
            "New direct messages wait here until a link toward the recipient appears.")
        QueueSegment.FORWARDED -> EmptyState(DtnIcons.Schedule, "Nothing waiting for a receipt",
            "Messages handed to a carrier phone or a LoRa hub wait here until the recipient's delivery receipt comes back.")
        QueueSegment.DELIVERED -> EmptyState(DtnIcons.Check, "No deliveries yet",
            "Direct messages this phone sent or carried show here once a direct link or a delivery receipt confirms them.")
        QueueSegment.HISTORY -> EmptyState(DtnIcons.Queue, "No buffered messages yet",
            "Every direct message that goes through this phone's buffer is tracked here, with how it left.")
        QueueSegment.BROADCASTS -> EmptyState(DtnIcons.Broadcast, "Nothing spreading",
            "Broadcasts and group messages this phone passes on show here until their lifetime ends.")
    }
}
