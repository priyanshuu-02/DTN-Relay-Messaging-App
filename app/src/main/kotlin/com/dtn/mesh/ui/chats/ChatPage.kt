package com.dtn.mesh.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.ui.ChatMessage
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.groups.GroupInviteDm
import com.dtn.mesh.ui.groups.JoinGroupCard
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.ConfirmDialog
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.MenuAction
import com.dtn.mesh.ui.components.OverflowMenu
import com.dtn.mesh.ui.peers.PeerLists
import com.dtn.mesh.ui.peers.rememberContactActions
import com.dtn.mesh.ui.queue.NodeNames
import com.dtn.mesh.ui.relativeSince
import com.dtn.mesh.ui.rememberBluetoothEnabler
import com.dtn.mesh.ui.rememberQrActions
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.launch

/**
 * One conversation (`chat/{key}`): header (avatar, name, "Online · via BLE" / "Last seen …", ⋮),
 * the "Keys changed" and reachability banners, day separators, bubbles with a status line under
 * each sent one ("✓✓ Delivered via BLE"), the "safety number changed" notice, and the composer.
 * Tap or long-press a bubble for its simple status sheet. Opening the chat marks it read.
 */
@Composable
fun ChatPage(
    viewModel: DtnViewModel,
    chatKey: String,
    onOpenContact: (String) -> Unit,
    onOpenMessage: (String) -> Unit,
    onOpenGroup: (Int) -> Unit,
) {
    val messages by viewModel.filteredMessages.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val routes by viewModel.messageRoutes.collectAsState()
    val notices by viewModel.keyChangeNotices.collectAsState()
    val isBroadcast = chatKey == DtnViewModel.BROADCAST_CHAT_ID
    val groupChannel = chatKey.takeIf { it.startsWith("grp:") }?.removePrefix("grp:")?.toIntOrNull()
    val group = groupChannel?.let { ch -> groups.firstOrNull { it.channel == ch } }
    val isGroup = groupChannel != null
    val peer = peers.firstOrNull { it.nodeId == chatKey }
    val kind = when {
        isBroadcast -> ChatKind.BROADCAST
        isGroup -> ChatKind.GROUP
        else -> ChatKind.DIRECT
    }
    val nameOf = remember(peers) { senderNames(peers) }
    val localNodeId by viewModel.localNodeId.collectAsState()
    // Group members (8-character ids) by name, for the status sheet's receipts.
    val memberNames = remember(peers, localNodeId) { NodeNames(peers, localNodeId?.takeLast(8)) }
    // Distinct senders seen in this group (there's no server-side member list).
    val membersSeen = remember(messages, peers) {
        if (!isGroup) emptyList() else messages.filter { !it.isOutgoing && it.from.isNotBlank() }.map { nameOf(it.from) }.distinct()
    }
    var statusFor by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmAccept by rememberSaveable { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    UserMessageSnackbars(viewModel, snackbars)
    val actions = rememberContactActions(viewModel, onRemoved = { viewModel.openChat(null) })
    val qr = rememberQrActions(
        prompt = "Scan ${peer?.displayName() ?: "their"}'s QR (My identity on their phone)",
        onResult = { text -> viewModel.handleScannedQr(text) { } },
        onProblem = { viewModel.showMessage(it) },
    )

    // Opening the chat marks it read, and so does every message that arrives while it's on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(chatKey, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.filteredMessages.collect { list ->
                if (viewModel.selectedChat.value == chatKey) {
                    val newest = ChatListModel.newestIncoming(list)
                    if (newest > 0) viewModel.markChatRead(chatKey, newest)
                }
            }
        }
    }

    val title = when {
        isBroadcast -> "Broadcast to All"
        group != null -> group.name
        isGroup -> "Group (left)"
        peer != null -> peer.displayName()
        else -> chatKey.takeLast(8)
    }
    val subtitle = when {
        isBroadcast -> "Every phone in reach · not encrypted"
        group != null -> "Encrypted group · " + if (membersSeen.isEmpty()) "tap for invite" else "you + ${membersSeen.size} seen"
        isGroup -> "You left this group — its key is gone"
        peer != null -> PeerLists.headerLine(peer, ::relativeSince)
        else -> ""
    }
    val placeholder = when {
        isBroadcast -> "Broadcast to everyone in reach…"
        group != null -> "Message ${group.name}…"
        peer != null -> "Message ${peer.displayName()}…"
        else -> "Message…"
    }
    val noticeAt = if (kind == ChatKind.DIRECT) notices[chatKey] else null
    val rows = remember(messages, noticeAt) { buildRows(messages, noticeAt, System.currentTimeMillis()).asReversed() }
    val listState = rememberLazyListState()
    val newest = messages.lastOrNull()
    // Follow new messages: always for our own, and for incoming ones while we're at the bottom.
    LaunchedEffect(newest?.msgId) {
        if (newest != null && (newest.isOutgoing || listState.firstVisibleItemIndex <= 2)) listState.animateScrollToItem(0)
    }

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            ChatHeader(
                kind = kind, title = title, subtitle = subtitle, chatKey = chatKey,
                onBack = { viewModel.openChat(null) },
                onTitle = when {
                    group != null -> ({ onOpenGroup(group.channel) })
                    peer != null -> ({ onOpenContact(peer.nodeId) })
                    else -> null
                },
                menu = when {
                    peer != null -> listOf(
                        MenuAction("Contact info") { onOpenContact(peer.nodeId) },
                        MenuAction("Rename") { actions.rename(peer) },
                        MenuAction("Safety number") { onOpenContact(peer.nodeId) },
                        MenuAction("Delete…", destructive = true) { actions.delete(peer) },
                        MenuAction("Block…", destructive = true) { actions.block(peer) },
                    )
                    group != null -> listOf(MenuAction("Group info") { onOpenGroup(group.channel) })
                    else -> emptyList()
                },
            )
        },
    ) { padding ->
        // consumeWindowInsets: the Scaffold padding already holds the navigation bar, so
        // imePadding must only add what the keyboard covers beyond it (no double gap).
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (peer != null && peer.keysChanged) {
                Banner(
                    text = "Keys changed — scan their QR or Accept. ${peer.displayName()}'s phone announced new keys " +
                        "(for example after its data was cleared). Your messages to them wait until you choose.",
                    buttons = listOf("Scan QR" to qr.scan, "Accept" to { confirmAccept = true }),
                )
            }
            if (peer != null) ReachBanner(viewModel, peer)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                reverseLayout = true,
            ) {
                item(key = "bottom-gap") { Spacer(Modifier.height(8.dp)) }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { ChatEmpty(kind, isGroup && group == null, peer) }
                    }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ConvRow.Day -> DaySeparator(row.label)
                        is ConvRow.Notice -> KeyChangeNotice(row.atMs, onClick = { onOpenContact(chatKey) })
                        is ConvRow.Bubble -> {
                            // A group invite sent as a DM shows as a Join group card (never auto-joined).
                            val invite = if (kind == ChatKind.DIRECT) remember(row.msg.text) { GroupInviteDm.detect(row.msg.text) } else null
                            val card: (@Composable (Dp) -> Unit)? = invite?.let { inv ->
                                @Composable { width: Dp ->
                                    JoinGroupCard(
                                        viewModel = viewModel, invite = inv, inviteText = row.msg.text,
                                        outgoing = row.msg.isOutgoing, peerName = peer?.displayName() ?: title, maxWidth = width,
                                        onOpenGroupChat = { ch -> viewModel.openChat("grp:$ch") },
                                        onOpenStatus = { statusFor = row.msg.msgId },
                                    )
                                }
                            }
                            Bubble(
                                msg = row.msg,
                                // Group / broadcast chats label incoming bubbles with their sender (once per run).
                                senderLabel = if (kind != ChatKind.DIRECT && !row.continued && !row.msg.isOutgoing) nameOf(row.msg.from) else null,
                                continued = row.continued,
                                onOpenStatus = { statusFor = row.msg.msgId },
                                card = card,
                            )
                        }
                    }
                }
            }
            val composerError by viewModel.composerError.collectAsState()
            composerError?.let { err ->
                Text(
                    err, color = DtnColors.RedText, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(DtnColors.RedSoft)
                        .clickable(onClickLabel = "Dismiss") { viewModel.clearComposerError() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (isGroup && group == null) {
                Surface(color = DtnColors.Card) {
                    Text("You left this group, so you can't send to it any more. Rejoin with an invite (Groups, top bar).",
                        style = DtnType.RowSubtitle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
            } else {
                Composer(placeholder = placeholder, onSend = { viewModel.sendTestMessage(it) })
            }
        }
    }

    // A message that left this chat (deleted) simply closes its sheet.
    statusFor?.let { id -> messages.firstOrNull { it.msgId == id } }?.let { msg ->
        MessageStatusSheet(
            msg = msg,
            route = routes.firstOrNull { it.msgId == msg.msgId.take(8) },
            onCopy = {
                clipboard.setText(AnnotatedString(msg.text))
                statusFor = null
                // Android 13+ confirms clipboard writes itself.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    scope.launch { snackbars.showSnackbar("Copied") }
                }
            },
            onFullTrace = { statusFor = null; onOpenMessage(msg.msgId.take(8)) },
            onDismiss = { statusFor = null },
            memberName = { id8 -> memberNames.label(id8) },
        )
    }
    if (confirmAccept && peer != null) {
        ConfirmDialog(
            title = "Use ${peer.displayName()}'s new keys?",
            text = "Their safety number changes and is no longer verified. Compare it with their phone, or scan their " +
                "QR, to verify it again. Messages waiting for them are sent with the new keys.",
            confirmLabel = "Accept", destructive = false,
            onConfirm = { confirmAccept = false; viewModel.acceptNewKeys(peer.nodeId) },
            onDismiss = { confirmAccept = false },
        )
    }
}

/** ← avatar, name and status line; tap the name for contact / group info; ⋮ on the right. */
@Composable
private fun ChatHeader(
    kind: ChatKind,
    title: String,
    subtitle: String,
    chatKey: String,
    onBack: () -> Unit,
    onTitle: (() -> Unit)?,
    menu: List<MenuAction>,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(DtnColors.Background).statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(DtnIcons.ArrowBack, contentDescription = "Back", tint = DtnColors.TextTitle) }
        Row(
            modifier = Modifier.weight(1f).clip(DtnShapes.Button)
                .then(if (onTitle != null) Modifier.clickable(onClickLabel = "Show details", role = Role.Button, onClick = onTitle) else Modifier)
                .heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatAvatar(kind, title, chatKey, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = DtnType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = DtnType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (menu.isNotEmpty()) OverflowMenu(menu, contentDescription = "Chat options")
    }
}

/** Amber banner with action buttons. */
@Composable
private fun Banner(text: String, buttons: List<Pair<String, () -> Unit>>) {
    Surface(color = DtnColors.OrangeSoft, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(DtnIcons.Warning, contentDescription = null, tint = DtnColors.Orange, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                Spacer(Modifier.width(8.dp))
                Text(text, fontSize = 13.sp, color = DtnColors.TextTitle, lineHeight = 18.sp)
            }
            if (buttons.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 18.dp)) {
                    buttons.forEach { (label, onClick) ->
                        TextButton(onClick = onClick) { Text(label, color = DtnColors.OrangeText, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

/**
 * Shown only while the contact can't be reached: why, and the fix — turn Bluetooth on (nearby)
 * or use the internet relay (anywhere).
 */
@Composable
private fun ReachBanner(viewModel: DtnViewModel, peer: PeerInfo) {
    if (peer.reachable) return
    val radios by viewModel.radios.collectAsState()
    val status by viewModel.transportStatus.collectAsState()
    val requestBluetoothOn = rememberBluetoothEnabler(viewModel)
    val relayWanted = status.isWanted(TransportKind.INTERNET)
    val bleWanted = status.isWanted(TransportKind.BLE)
    val name = peer.displayName()
    val text = when {
        !radios.bluetoothOn && !relayWanted ->
            "$name isn't reachable. Bluetooth is off and the internet relay isn't on — your message waits in the buffer."
        !relayWanted -> "$name isn't nearby. Turn on the internet relay to reach them anywhere (they need it on too)."
        !peer.hasKeys -> "No keys for $name yet — scan each other's QR so messages can be encrypted."
        else -> "$name isn't nearby or on the relay right now. Your message waits and is delivered when they're reachable."
    }
    val buttons = buildList<Pair<String, () -> Unit>> {
        if (!radios.bluetoothOn) add("Turn on Bluetooth" to {
            requestBluetoothOn()
            if (!bleWanted) viewModel.toggleTransport(TransportKind.BLE)
        })
        if (!relayWanted) add("Use internet relay" to { viewModel.toggleTransport(TransportKind.INTERNET) })
    }
    Banner(text, buttons)
}

/** A row of the conversation (oldest first before reversing): a date separator, a bubble or the key-change notice. */
private sealed interface ConvRow {
    val key: String
    data class Day(val label: String, override val key: String) : ConvRow
    data class Notice(val atMs: Long, override val key: String = "key-notice") : ConvRow

    /** @param continued same sender as the bubble above within a few minutes (tighter, unlabelled). */
    data class Bubble(val msg: ChatMessage, val continued: Boolean) : ConvRow {
        override val key: String get() = msg.msgId
    }
}

/** Oldest-first rows: a date separator before each new day, sender runs, and the notice at its time. */
private fun buildRows(messages: List<ChatMessage>, noticeAtMs: Long?, nowMs: Long): List<ConvRow> {
    val rows = ArrayList<ConvRow>(messages.size + 6)
    var lastDay = Long.MIN_VALUE
    var prev: ChatMessage? = null
    var noticePlaced = noticeAtMs == null
    fun day(atMs: Long) {
        val d = UiText.dayKey(atMs)
        if (d != lastDay) {
            rows += ConvRow.Day(UiText.dayLabel(atMs, nowMs), "day-$d")
            lastDay = d
            prev = null
        }
    }
    for (m in messages) {
        if (!noticePlaced && noticeAtMs != null && m.createdAtMs > noticeAtMs) {
            day(noticeAtMs)
            rows += ConvRow.Notice(noticeAtMs)
            noticePlaced = true
            prev = null
        }
        day(m.createdAtMs)
        val p = prev
        val continued = p != null && p.isOutgoing == m.isOutgoing && p.from == m.from && m.createdAtMs - p.createdAtMs < 5 * 60_000L
        rows += ConvRow.Bubble(m, continued)
        prev = m
    }
    if (!noticePlaced && noticeAtMs != null) {
        day(noticeAtMs)
        rows += ConvRow.Notice(noticeAtMs)
    }
    return rows
}

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = DtnShapes.Pill, color = DtnColors.Card) {
            Text(label, style = DtnType.Meta, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

/** "Safety number changed · 14:05" — tap to see it on the contact page. */
@Composable
private fun KeyChangeNotice(atMs: Long, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = DtnShapes.Pill, color = DtnColors.OrangeSoft,
            modifier = Modifier.clip(DtnShapes.Pill).clickable(onClickLabel = "Show the new safety number", role = Role.Button, onClick = onClick),
        ) {
            Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(DtnIcons.Lock, contentDescription = null, tint = DtnColors.OrangeText, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Safety number changed · ${UiText.listTime(atMs)} · tap to view", fontSize = 12.sp,
                    color = DtnColors.OrangeText, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ChatEmpty(kind: ChatKind, leftGroup: Boolean, peer: PeerInfo?) {
    when {
        kind == ChatKind.BROADCAST -> EmptyState(DtnIcons.Broadcast, "Broadcast to everyone in reach",
            "Every phone that receives it passes it on, hop by hop. Broadcasts are not encrypted — anyone in reach can read them.")
        leftGroup -> EmptyState(DtnIcons.Groups, "You left this group", "Its key is gone from this phone.")
        kind == ChatKind.GROUP -> EmptyState(DtnIcons.Groups, "Encrypted group",
            "Only phones holding this group's invite can read its messages. Share the invite from the group info (tap the title).")
        peer?.hasKeys == false -> EmptyState(DtnIcons.Lock, "No keys yet",
            "Scan each other's QR (My identity, your avatar top right) or wait until ${peer.displayName()}'s phone announces " +
                "itself, so messages can be end-to-end encrypted.")
        else -> EmptyState(DtnIcons.Lock, "End-to-end encrypted",
            "Messages are sealed on this phone and can only be opened on ${peer?.displayName() ?: "theirs"}'s. They travel " +
                "phone to phone, through LoRa hubs or the internet relay, and wait in the buffer until a path appears.")
    }
}

/**
 * One bubble: incoming light grey on the left, outgoing blue on the right (18 dp corners, small
 * tail). Underneath: the time, plus the status line on sent ones ("✓✓ Delivered via BLE") and the
 * link on received ones. Tap or long-press for the status sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    msg: ChatMessage,
    senderLabel: String?,
    continued: Boolean,
    onOpenStatus: () -> Unit,
    /** Replaces the text bubble (a Join group card); gets the bubble's max width. */
    card: (@Composable (Dp) -> Unit)? = null,
) {
    val out = msg.isOutgoing
    val shape = if (out) RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = if (continued) 2.dp else 8.dp)) {
        val maxBubble = maxWidth * 0.8f
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
            if (senderLabel != null) {
                Text(senderLabel, fontSize = 12.sp, color = DtnColors.Primary, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
            }
            if (card != null) card(maxBubble) else Surface(
                shape = shape,
                color = if (out) DtnColors.Primary else DtnColors.BubbleIn,
                modifier = Modifier.widthIn(max = maxBubble).clip(shape).combinedClickable(
                    onClickLabel = "Message status",
                    onLongClickLabel = "Message status",
                    onClick = onOpenStatus,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenStatus()
                    },
                ),
            ) {
                Text(msg.text, fontSize = 15.sp, lineHeight = 20.sp, color = if (out) Color.White else DtnColors.TextTitle,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
            }
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(msg.time, fontSize = 11.sp, color = DtnColors.TextHint)
                val line = if (out) TravelText.bubbleStatus(msg.state, msg.via, msg.group)
                else TravelText.linkName(msg.via)?.let { "via $it" }.orEmpty()
                if (line.isNotBlank()) {
                    Text(" · ", fontSize = 11.sp, color = DtnColors.TextHint)
                    Text(line, fontSize = 11.sp, fontWeight = if (out) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (out) statusColor(msg.state) else DtnColors.TextHint, maxLines = 1)
                }
            }
        }
    }
}

private fun statusColor(state: DeliveryState): Color = when (state) {
    DeliveryState.DELIVERED, DeliveryState.RECEIVED_BY_ALL -> DtnColors.GreenText
    DeliveryState.QUEUED -> DtnColors.OrangeText
    DeliveryState.EXPIRED, DeliveryState.DROPPED -> DtnColors.RedText
    else -> DtnColors.TextSubtitle
}

/**
 * Grey rounded field + 48 dp blue round send button. Send is disabled while the text is blank or
 * over the single-frame byte budget; the counter only appears once the budget starts to matter.
 */
@Composable
private fun Composer(placeholder: String, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val byteLen = remember(text) { text.toByteArray(Charsets.UTF_8).size }
    val max = DtnViewModel.MAX_MSG_BYTES
    val over = byteLen > max
    val canSend = text.isNotBlank() && !over
    Surface(color = DtnColors.Card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (byteLen >= max * 3 / 4) {
                Text(
                    if (over) "Too long — $byteLen/$max bytes. Messages travel in one radio frame; shorten it to send."
                    else "$byteLen/$max bytes",
                    fontSize = 12.sp, color = if (over) DtnColors.RedText else DtnColors.TextSubtitle,
                    modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis, color = DtnColors.TextHint) },
                    textStyle = DtnType.RowSubtitle.copy(fontSize = 15.sp, color = DtnColors.TextTitle),
                    minLines = 1,
                    maxLines = 5,
                    isError = over,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = DtnColors.Track,
                        unfocusedContainerColor = DtnColors.Track,
                        errorContainerColor = DtnColors.RedSoft,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { if (canSend) { onSend(text); text = "" } },
                    enabled = canSend,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = DtnColors.Primary, contentColor = Color.White,
                        disabledContainerColor = DtnColors.Primary.copy(alpha = 0.35f), disabledContentColor = Color.White,
                    ),
                    modifier = Modifier.padding(bottom = 4.dp).size(48.dp),
                ) { Icon(DtnIcons.Send, contentDescription = "Send") }
            }
        }
    }
}
