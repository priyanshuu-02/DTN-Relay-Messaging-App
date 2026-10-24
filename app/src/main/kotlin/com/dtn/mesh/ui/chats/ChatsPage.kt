package com.dtn.mesh.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.components.Avatar
import com.dtn.mesh.ui.components.ChipRow
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.DtnSearchField
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.peers.PeerLists
import com.dtn.mesh.ui.relativeSince
import com.dtn.mesh.ui.rememberQrActions
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType

/**
 * Chats: search, All / Unread / Groups, the pinned Broadcast row, then groups and contacts by
 * their last message. Each row shows the last sent message's status or an unread badge. ✎ opens
 * the New message sheet. Blocked contacts never appear; phones that only announced themselves are
 * under Peers › Nearby devices.
 */
@Composable
fun ChatsPage(viewModel: DtnViewModel) {
    val peers by viewModel.peerList.collectAsState()
    val messages by viewModel.receivedMessages.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val lastRead by viewModel.lastRead.collectAsState()
    val baseline by viewModel.unreadBaseline.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ChatFilter.ALL) }
    var composeNew by rememberSaveable { mutableStateOf(false) }

    val nameOf = remember(peers) { senderNames(peers) }
    val items = remember(messages, peers, groups, lastRead, baseline) {
        ChatListModel.build(messages, peers, groups, lastRead, baseline, nameOf) { PeerLists.headerLine(it, ::relativeSince) }
    }
    val shown = remember(items, query, filter) { ChatListModel.filter(items, query, filter) }
    val unreadTotal = ChatListModel.totalUnread(items)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                DtnSearchField(query, { query = it }, placeholder = "Search chats")
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = ChatFilter.entries.map { if (it == ChatFilter.UNREAD && unreadTotal > 0) "${it.label} ($unreadTotal)" else it.label },
                    selected = filter.ordinal,
                    onSelect = { filter = ChatFilter.entries[it] },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (shown.isEmpty()) {
                item {
                    EmptyState(
                        DtnIcons.Chats,
                        when {
                            query.isNotBlank() -> "No matching chats"
                            filter == ChatFilter.UNREAD -> "You're all caught up"
                            filter == ChatFilter.GROUPS -> "No groups yet"
                            else -> "No chats yet"
                        },
                        when (filter) {
                            ChatFilter.GROUPS -> "Create or join one from Groups (top bar)."
                            else -> "Add a contact by QR (Peers or Network), or tap ✎ to start a message."
                        },
                    )
                }
            } else {
                item {
                    DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        shown.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = DtnColors.CardBorder, modifier = Modifier.padding(start = 72.dp))
                            ChatListRow(item, onClick = { viewModel.openChat(item.key) })
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
        FloatingActionButton(
            onClick = { composeNew = true },
            containerColor = DtnColors.Primary,
            contentColor = Color.White,
            shape = DtnShapes.Pill,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        ) { Icon(DtnIcons.Edit, contentDescription = "New message") }
    }

    if (composeNew) {
        NewMessageSheet(viewModel, peers, onPick = { key -> composeNew = false; viewModel.openChat(key) }) { composeNew = false }
    }
}

/** Avatar, bold name, grey last message; time + status or unread badge on the right. */
@Composable
private fun ChatListRow(item: ChatListItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${item.title}", role = Role.Button, onClick = onClick)
            .heightIn(min = 68.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(item.kind, item.title, item.key)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = DtnType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.preview, style = DtnType.RowSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (item.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (item.unread > 0) DtnColors.TextTitle else DtnColors.TextSubtitle)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (item.atMs > 0) Text(UiText.listTime(item.atMs), style = DtnType.Meta,
                color = if (item.unread > 0) DtnColors.Primary else DtnColors.TextSubtitle)
            Spacer(Modifier.height(4.dp))
            when {
                item.unread > 0 -> UnreadBadge(item.unread)
                item.lastOutgoing != null -> RowStatus(item.lastOutgoing)
            }
        }
    }
}

/** "🕓 Queued" orange · "✓ Sent" grey · "✓✓ Delivered" green · "! Expired" red. */
@Composable
private fun RowStatus(state: DeliveryState) {
    val color = when (state) {
        DeliveryState.DELIVERED, DeliveryState.RECEIVED_BY_ALL -> DtnColors.GreenText
        DeliveryState.QUEUED -> DtnColors.OrangeText
        DeliveryState.EXPIRED, DeliveryState.DROPPED -> DtnColors.RedText
        else -> DtnColors.TextSubtitle
    }
    Text(listOf(state.tick, state.short).filter { it.isNotBlank() }.joinToString(" "), fontSize = 12.sp, color = color,
        fontWeight = FontWeight.Medium, maxLines = 1)
}

@Composable
private fun UnreadBadge(n: Int) {
    Surface(shape = DtnShapes.Pill, color = DtnColors.Primary) {
        Text(if (n > 99) "99+" else "$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.widthIn(min = 22.dp).padding(horizontal = 7.dp, vertical = 2.dp), maxLines = 1,
            textAlign = TextAlign.Center)
    }
}

/** Broadcast: purple megaphone · group: lavender group icon · contact: their colour + initial. */
@Composable
internal fun ChatAvatar(kind: ChatKind, title: String, key: String, size: Dp = 44.dp) {
    when (kind) {
        ChatKind.BROADCAST -> Avatar("", DtnColors.Purple, size = size, icon = DtnIcons.Broadcast)
        ChatKind.GROUP -> Avatar("", DtnColors.GroupAvatar, size = size, icon = DtnIcons.Groups)
        ChatKind.DIRECT -> Avatar(UiText.avatarInitial(title), DtnColors.avatarFor(key), size = size)
    }
}

/** Sender id → name for previews and group bubbles ("Unknown sender" when there's none). */
internal fun senderNames(peers: List<PeerInfo>): (String) -> String {
    val byId = peers.associateBy { it.nodeId }
    return { id ->
        when {
            id.isBlank() || id.startsWith("grp:") || UiText.isBroadcastId(id) -> "Unknown sender"
            else -> byId[id]?.displayName() ?: id.takeLast(8)
        }
    }
}

/** Pick who to write to: Broadcast, a group or a contact (search by name / id), or add one by QR. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMessageSheet(viewModel: DtnViewModel, peers: List<PeerInfo>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val groups by viewModel.groups.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val qr = rememberQrActions(
        prompt = "Scan a contact's QR (My identity on their phone)",
        onResult = { text -> viewModel.handleScannedQr(text) { key -> onPick(key) } },
        onProblem = { viewModel.showMessage(it) },
    )
    val contacts = remember(peers, query) { PeerLists.contacts(peers, query) }
    val shownGroups = groups.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    val showBroadcast = query.isBlank() || "broadcast to all".contains(query.trim(), ignoreCase = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DtnShapes.Sheet,
        containerColor = DtnColors.Background,
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("New message", style = DtnType.PageTitle)
                Spacer(Modifier.height(8.dp))
                DtnSearchField(query, { query = it }, placeholder = "Name or node id")
            }
            if (showBroadcast) {
                item {
                    DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        PickRow(ChatKind.BROADCAST, "Broadcast to All", "Everyone in reach · not encrypted", ChatListModel.BROADCAST_KEY, onPick)
                    }
                }
            }
            if (shownGroups.isNotEmpty()) {
                item { SectionHeader("Groups") }
                item {
                    DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        shownGroups.forEach { g -> PickRow(ChatKind.GROUP, g.name, "Encrypted group", "grp:${g.channel}", onPick) }
                    }
                }
            }
            item { SectionHeader("Contacts") }
            if (contacts.isEmpty()) {
                item { Text(if (query.isBlank()) "No contacts yet." else "No matching contacts.", style = DtnType.RowSubtitle) }
            } else {
                items(contacts, key = { it.nodeId }) { p ->
                    DtnCard(contentPadding = PaddingValues(vertical = 2.dp)) {
                        PickRow(ChatKind.DIRECT, p.displayName(),
                            if (p.hasKeys) PeerLists.headerLine(p, ::relativeSince) else "No keys yet — scan their QR",
                            p.nodeId, onPick)
                    }
                }
            }
            item {
                DtnCard(color = DtnColors.PrimarySoft, contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow("Add contact by QR", icon = DtnIcons.QrCode, iconBackground = DtnColors.Card,
                        subtitle = "Scan their QR to add them", onClick = qr.scan)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PickRow(kind: ChatKind, title: String, subtitle: String, key: String, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Write to $title", role = Role.Button) { onPick(key) }
            .heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(kind, title, key, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = DtnType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = DtnType.RowSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
