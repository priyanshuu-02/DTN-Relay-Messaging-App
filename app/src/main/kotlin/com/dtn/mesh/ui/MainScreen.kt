package com.dtn.mesh.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.receiver.MeshConnectionState

/* ==========================================================================
 *  Design palette
 * ========================================================================== */
internal object Palette {
    val Primary = Color(0xFF0B7BC0)      // main brand blue
    val PrimaryDark = Color(0xFF075A8F)
    val PrimaryLight = Color(0xFF12A0D8)  // lighter blue for gradient end
    val Accent = Color(0xFF2E7D32)       // online / delivered / success
    val Warning = Color(0xFFF9A825)      // buffered / weak signal
    val Error = Color(0xFFC62828)        // offline / failed
    val Broadcast = Color(0xFF7E57C2)    // broadcast tint
    val BubbleOut = Color(0xFFDCF8C6)    // WhatsApp-like sent bubble
    val BubbleIn = Color(0xFFFFFFFF)     // received bubble
    val Surface = Color(0xFFF0F3F7)      // app background (soft cool gray)
    val CardSurface = Color(0xFFFFFFFF)  // card fill
    val Divider = Color(0xFFE1E4E8)
    val TextPrimary = Color(0xFF1B1F23)
    val TextSecondary = Color(0xFF6A737D)
    val TextMuted = Color(0xFF959DA5)

    // Small text on chat bubbles: darker tones keep ≥4.5:1 contrast on the green sent bubble.
    val BubbleMeta = Color(0xFF5A6169)
    val DeliveredText = Color(0xFF1B5E20)
    val QueuedText = Color(0xFF7A5C00)

    // Soft status tints — used for subtle card accents / pills (backgrounds behind status text).
    val AccentSoft = Color(0xFFE7F4EA)
    val WarningSoft = Color(0xFFFDF3DD)
    val ErrorSoft = Color(0xFFFBE9E9)
    val PrimarySoft = Color(0xFFE4F1FA)
    val BroadcastSoft = Color(0xFFEDE7F6)

    // Header gradient (app bars). Left→right deep-to-bright blue for a bit more depth than a flat fill.
    val TopBarGradient = Brush.horizontalGradient(listOf(Primary, PrimaryLight))
    // Broadcast avatar gradient.
    val BroadcastGradient = Brush.linearGradient(listOf(Color(0xFF9575CD), Color(0xFF7E57C2)))

    // Deterministic per-nodeId avatar palette. Picks by hash of the id.
    private val avatarColors = listOf(
        Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784),
        Color(0xFFFFB74D), Color(0xFFBA68C8), Color(0xFF4DB6AC),
        Color(0xFF7986CB), Color(0xFFF06292), Color(0xFF4FC3F7),
        Color(0xFFAED581),
    )
    fun avatarFor(nodeId: String): Color =
        avatarColors[Math.floorMod(nodeId.hashCode(), avatarColors.size)]
}

/* ==========================================================================
 *  Root
 * ========================================================================== */

@Composable
fun MainScreen(viewModel: DtnViewModel) {
    val selectedChat by viewModel.selectedChat.collectAsState()
    if (selectedChat == null) {
        HomeScreen(viewModel)
    } else {
        // Route Android system-back to "go back to chat list" instead of exiting the app.
        BackHandler(enabled = true) { viewModel.openChat(null) }
        ChatDetailScreen(viewModel, chatKey = selectedChat!!)
    }
}

/* ==========================================================================
 *  Home screen — chats / network / lifecycle / log
 * ========================================================================== */

/** Show the ViewModel's one-shot notices (joined group, invalid QR, …) as snackbars. */
@Composable
private fun UserMessageSnackbars(viewModel: DtnViewModel, host: SnackbarHostState) {
    LaunchedEffect(viewModel, host) {
        viewModel.userMessages.collect { msg ->
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(msg)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(viewModel: DtnViewModel) {
    val logEntries by viewModel.logEntries.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)

    // System back: if we're on a non-Chats tab, return to Chats. Only exit from Chats tab.
    BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }

    Scaffold(
        containerColor = Palette.Surface,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.background(Palette.TopBarGradient),
                title = {
                    Text(
                        "DTN Mesh Relay",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = Color.White,
                    )
                },
                actions = {
                    IconButton(onClick = { overlay = "groups" }) { Text("👥", fontSize = 18.sp) }
                    IconButton(onClick = { overlay = "identity" }) { Text("🔑", fontSize = 18.sp) }
                    IconButton(onClick = { overlay = "settings" }) { Text("⚙", fontSize = 18.sp) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionPanel(viewModel)

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Palette.CardSurface,
                contentColor = Palette.Primary,
            ) {
                TabItem("Chats", selectedTab == 0) { selectedTab = 0 }
                TabItem("Network", selectedTab == 1) { selectedTab = 1 }
                TabItem("Lifecycle", selectedTab == 2) { selectedTab = 2 }
                TabItem("Log", selectedTab == 3) { selectedTab = 3 }
            }
            HorizontalDivider(color = Palette.Divider)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    0 -> ChatListTab(viewModel)
                    1 -> NetworkTab(viewModel, onOpenChat = { viewModel.openChat(it) })
                    2 -> LifecycleTab(viewModel)
                    3 -> LogTab(viewModel, logEntries)
                }
            }
        }
    }

    when (overlay) {
        "settings" -> SettingsDialog(viewModel) { overlay = null }
        "identity" -> IdentityDialog(viewModel, onOpenChat = { viewModel.openChat(it); overlay = null }) { overlay = null }
        "groups" -> GroupsDialog(viewModel, onOpenChat = { viewModel.openChat(it); overlay = null }) { overlay = null }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        selectedContentColor = Palette.Primary,
        unselectedContentColor = Palette.TextSecondary,
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 12.dp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontSize = 13.sp,
        )
    }
}

/* ==========================================================================
 *  Chats tab
 * ========================================================================== */

@Composable
private fun ChatListTab(viewModel: DtnViewModel) {
    val peers by viewModel.peerList.collectAsState()
    val messages by viewModel.receivedMessages.collectAsState()
    val groups by viewModel.groups.collectAsState()
    var renameTarget by remember { mutableStateOf<PeerInfo?>(null) }

    val nameOf = rememberSenderNames(peers)
    // One pass over the history → latest message per conversation; chats with recent messages
    // come first (like any messenger), the rest by who's reachable now.
    val summaries = remember(messages, nameOf) { summarizeChats(messages, nameOf) }
    val sortedGroups = remember(groups, summaries) {
        groups.sortedByDescending { summaries["grp:${it.channel}"]?.atMs ?: 0L }
    }
    val discoverable by viewModel.discoverable.collectAsState()
    // Contacts (added by QR / Add / a message either way) are the chats; phones that only
    // announced themselves wait in "Discovered" until added.
    val sortedPeers = remember(peers, summaries) {
        peers.filter { it.added || summaries.containsKey(it.nodeId) }.sortedWith(
            compareByDescending<PeerInfo> { summaries[it.nodeId]?.atMs ?: 0L }
                .thenByDescending { it.reachable }
                .thenByDescending { it.lastSeenMs },
        )
    }
    val discovered = remember(peers, summaries) {
        peers.filter { !it.added && !summaries.containsKey(it.nodeId) && it.hasKeys }
            .sortedWith(compareByDescending<PeerInfo> { it.reachable }.thenByDescending { it.lastSeenMs })
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        item { DiscoverCard(discoverable = discoverable, onChange = { viewModel.setDiscoverable(it) }) }
        item {
            val s = summaries[BROADCAST_CHAT_KEY]
            BroadcastCard(
                lastPreview = s?.preview,
                time = s?.let { UiText.listTime(it.atMs) }.orEmpty(),
                onClick = { viewModel.openChat(DtnViewModel.BROADCAST_CHAT_ID) },
            )
        }
        item { Spacer(Modifier.height(4.dp)) }
        if (sortedGroups.isNotEmpty()) {
            items(sortedGroups, key = { "grp-${it.channel}" }) { g ->
                val s = summaries["grp:${g.channel}"]
                GroupCard(
                    name = g.name,
                    subtitle = s?.preview ?: "Encrypted group · no messages yet",
                    time = s?.let { UiText.listTime(it.atMs) }.orEmpty(),
                    onClick = { viewModel.openChat("grp:${g.channel}") },
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        if (sortedPeers.isEmpty()) {
            item {
                EmptyState(
                    "No contacts yet.",
                    "Scan a contact's QR (🔑) to add them, or turn on Discover so phones nearby and on the " +
                        "internet relay can find each other.",
                )
            }
        } else {
            items(sortedPeers, key = { it.nodeId }) { peer ->
                val s = summaries[peer.nodeId]
                PeerCard(
                    peer = peer,
                    subtitle = s?.preview ?: reachabilityText(peer),
                    time = s?.let { UiText.listTime(it.atMs) }.orEmpty(),
                    onClick = { viewModel.openChat(peer.nodeId) },
                    onEdit = { renameTarget = peer },
                )
            }
        }
        if (discovered.isNotEmpty()) {
            item {
                Text(
                    "DISCOVERED · ${discovered.size}",
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.Primary, letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp, start = 4.dp),
                )
                Text(
                    "Phones that made themselves discoverable. Add one to chat with it.",
                    fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(discovered, key = { "disc-${it.nodeId}" }) { peer ->
                DiscoveredRow(peer = peer, onAdd = { viewModel.addContact(peer.nodeId) })
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    renameTarget?.let { peer ->
        RenameDialog(peer = peer, onDismiss = { renameTarget = null },
            onSave = { name -> viewModel.renamePeer(peer.nodeId, name); renameTarget = null })
    }
}

/** Resolve a sender node id to a friendly name (contact name, else short id). */
@Composable
private fun rememberSenderNames(peers: List<PeerInfo>): (String) -> String =
    remember(peers) {
        val byId = peers.associateBy { it.nodeId }
        val fn: (String) -> String = { id ->
            when {
                id.isBlank() || id.startsWith("grp:") || UiText.isBroadcastId(id) -> "Unknown sender"
                else -> byId[id]?.displayName() ?: id.takeLast(8)
            }
        }
        fn
    }

private fun reachabilityText(peer: PeerInfo): String = when {
    peer.isOnline && peer.hubOnly -> "📡 Online via LoRa hub"
    peer.isOnline -> "Nearby · ${peer.nodeId.takeLast(8)}"
    peer.internetOnline -> "🌐 Online via internet relay"
    peer.viaOtherHub -> "📡 Online via LoRa · ${peer.meshHubLabel}"
    else -> "Offline · last seen ${relativeSince(peer.lastSeenMs)}"
}

/** Conversation key of the broadcast chat in [summarizeChats] (broadcast rows have no toNodeId). */
private const val BROADCAST_CHAT_KEY = "__broadcast__"

/** The latest message of one conversation, ready for the chat list. */
private data class ChatSummary(val preview: String, val atMs: Long)

/**
 * Latest message per conversation (key: peer node id, "grp:<ch>", or [BROADCAST_CHAT_KEY]).
 * [all] is oldest-first, so the last write per key wins. Outgoing previews carry their tick.
 */
private fun summarizeChats(all: List<ChatMessage>, nameOf: (String) -> String): Map<String, ChatSummary> {
    val latest = HashMap<String, ChatMessage>()
    for (m in all) latest[m.toNodeId ?: BROADCAST_CHAT_KEY] = m
    return latest.mapValues { (key, m) ->
        val multiParty = key == BROADCAST_CHAT_KEY || key.startsWith("grp:")
        val prefix = when {
            m.isOutgoing -> m.state.tick.takeIf { it.isNotBlank() }?.let { "$it You: " } ?: "You: "
            multiParty -> nameOf(m.from) + ": "
            else -> ""
        }
        ChatSummary(prefix + m.text.replace('\n', ' ').take(60), m.createdAtMs)
    }
}

/** Title row shared by the chat-list cards: name on the left, last-message time on the right. */
@Composable
private fun ChatTitleRow(title: String, time: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Palette.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (time.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(time, fontSize = 11.sp, color = Palette.TextSecondary)
        }
    }
}

@Composable
private fun BroadcastCard(lastPreview: String?, time: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape)
                    .background(Palette.BroadcastGradient),
                contentAlignment = Alignment.Center,
            ) {
                Text("📢", fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ChatTitleRow("Broadcast to All", time)
                Text(
                    lastPreview ?: "Send to every peer in range",
                    fontSize = 12.sp, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Discover on/off: whether this phone broadcasts its name + keys to everyone it can reach. */
@Composable
private fun DiscoverCard(discoverable: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = if (discoverable) Palette.AccentSoft else Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📣", fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Discover", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Palette.TextPrimary)
                Text(
                    if (discoverable) "On — phones nearby and on the internet relay can see and add you"
                    else "Off — only your contacts see you; others add you by your QR (🔑)",
                    fontSize = 11.sp, color = Palette.TextSecondary,
                )
            }
            androidx.compose.material3.Switch(
                checked = discoverable,
                onCheckedChange = onChange,
                modifier = Modifier.semantics { contentDescription = "Discover" },
            )
        }
    }
}

/** A phone that announced itself (Discover) but isn't in the chat list yet. */
@Composable
private fun DiscoveredRow(peer: PeerInfo, onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(peer = peer, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.displayName(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Palette.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(reachabilityText(peer), fontSize = 11.sp, color = Palette.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(onClick = onAdd) { Text("Add") }
        }
    }
}

@Composable
private fun GroupCard(name: String, subtitle: String, time: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape).background(Palette.Broadcast),
                contentAlignment = Alignment.Center,
            ) { Text("👥", fontSize = 20.sp) }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ChatTitleRow(name, time)
                Text(subtitle, fontSize = 12.sp, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: PeerInfo,
    subtitle: String,
    time: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(peer = peer, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                ChatTitleRow(peer.displayName(), time)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(subtitle, fontSize = 12.sp, color = Palette.TextSecondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    ReachIndicator(peer)
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Edit, contentDescription = "Rename ${peer.displayName()}",
                    modifier = Modifier.size(16.dp),
                    tint = Palette.TextMuted,
                )
            }
        }
    }
}

/** Signal bars for a radio-online peer, a globe for one reachable only over the internet relay. */
@Composable
private fun ReachIndicator(peer: PeerInfo) {
    if (peer.isOnline && peer.hubOnly) {
        // Reached through the hub: there's no radio signal of our own to show as bars.
        Text("📡", fontSize = 14.sp, modifier = Modifier.semantics { contentDescription = "Online via LoRa hub" })
    } else if (!peer.isOnline && peer.internetOnline) {
        Text("🌐", fontSize = 14.sp, modifier = Modifier.semantics { contentDescription = "Online via internet relay" })
    } else if (peer.viaOtherHub) {
        // On another hub, reached over LoRa through ours: no radio signal of our own either.
        Text("📡", fontSize = 14.sp, modifier = Modifier.semantics { contentDescription = "Online via LoRa, on ${peer.meshHubLabel}" })
    } else {
        SignalBars(rssi = peer.lastRssi, isOnline = peer.isOnline)
    }
}

/** Circular colored avatar with the first character of the display name. Ring: green = reachable
 *  over radio (nearby, or over LoRa through the hubs), blue = online via the internet relay,
 *  red = not reachable. */
@Composable
private fun PeerAvatar(peer: PeerInfo, size: androidx.compose.ui.unit.Dp) {
    val name = peer.displayName()
    // Grapheme-safe: an emoji nickname ("🚗 Car Mule") keeps its emoji instead of turning into "?".
    val initial = UiText.avatarInitial(name)
    val ringColor = when {
        peer.isOnline -> Palette.Accent
        peer.internetOnline -> Palette.PrimaryLight
        peer.viaOtherHub -> Palette.Accent
        else -> Palette.Error
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Palette.avatarFor(peer.nodeId))
            .border(2.dp, ringColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

@Composable
private fun SignalBars(rssi: Int, isOnline: Boolean, modifier: Modifier = Modifier) {
    val level = when {
        !isOnline -> 0
        rssi == -200 || rssi == 0 -> 0
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
    val filledColor = when {
        !isOnline -> Color(0xFFBDBDBD)
        level >= 3 -> Palette.Accent
        level == 2 -> Palette.Warning
        else -> Palette.Error
    }
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            val h = (4 + i * 3).dp
            Box(
                modifier = Modifier.width(3.dp).height(h)
                    .background(if (i <= level) filledColor else Color(0xFFE0E0E0)),
            )
        }
    }
}

@Composable
private fun RenameDialog(peer: PeerInfo, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(peer.customName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename peer") },
        text = {
            Column {
                Text("${peer.nodeId.takeLast(8)}", fontSize = 12.sp, color = Palette.TextSecondary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("Nickname (leave blank to clear)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = Palette.TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, fontSize = 12.sp, color = Palette.TextMuted, textAlign = TextAlign.Center)
    }
}

/* ==========================================================================
 *  Chat detail
 * ========================================================================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailScreen(viewModel: DtnViewModel, chatKey: String) {
    val messages by viewModel.filteredMessages.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val isBroadcast = chatKey == DtnViewModel.BROADCAST_CHAT_ID
    val groupChannel = chatKey.takeIf { it.startsWith("grp:") }?.removePrefix("grp:")?.toIntOrNull()
    val group = groupChannel?.let { ch -> groups.firstOrNull { it.channel == ch } }
    val isGroup = groupChannel != null
    val peer = peers.firstOrNull { it.nodeId == chatKey }
    val nameOf = rememberSenderNames(peers)
    // Distinct senders seen in this group (there's no server-side member list).
    val membersSeen = remember(messages, peers) {
        if (!isGroup) emptyList()
        else messages.filter { !it.isOutgoing && it.from.isNotBlank() }.map { nameOf(it.from) }.distinct()
    }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var infoFor by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    UserMessageSnackbars(viewModel, snackbars)

    val title = when {
        isBroadcast -> "Broadcast to All"
        group != null -> group.name
        isGroup -> "Group (left)"
        peer != null -> peer.displayName()
        else -> chatKey.takeLast(8)
    }
    val subtitle = when {
        isBroadcast -> "Fans out to every peer in range (not encrypted)"
        group != null -> "Encrypted group · " +
            if (membersSeen.isEmpty()) "tap for invite" else "you + ${membersSeen.size} seen · tap for info"
        isGroup -> "You left this group — its key is gone"
        peer?.isOnline == true && peer.hubOnly -> "Online · via LoRa hub"
        peer?.isOnline == true -> "Online · nearby"
        peer?.internetOnline == true -> "Online · via internet relay"
        peer?.viaOtherHub == true -> "Online · via LoRa (${peer.meshHubLabel})"
        peer != null -> "Offline · messages wait in the buffer"
        else -> ""
    }
    val placeholder = when {
        isBroadcast -> "Broadcast to everyone in range…"
        group != null -> "Message ${group.name}…"
        peer != null -> "Message ${peer.displayName()}…"
        else -> "Message…"
    }

    // Day separators + sender grouping, newest first for the bottom-anchored (reversed) list.
    val rows = remember(messages) { buildChatRows(messages, System.currentTimeMillis()).asReversed() }
    val listState = rememberLazyListState()
    val newest = messages.lastOrNull()
    // Follow new messages: always for our own, and for incoming ones while we're at the bottom.
    LaunchedEffect(newest?.msgId) {
        if (newest != null && (newest.isOutgoing || listState.firstVisibleItemIndex <= 2)) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
        containerColor = Palette.Surface,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            Surface(
                color = Palette.Primary,
                shadowElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Palette.TopBarGradient)
                        .statusBarsPadding()
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.openChat(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                            tint = Color.White)
                    }
                    // Tapping the avatar/title opens group or contact info.
                    val canShowInfo = group != null || peer != null
                    Row(
                        modifier = Modifier.weight(1f)
                            .then(if (canShowInfo) Modifier.clickable(onClickLabel = "Show details") { showInfo = true } else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isBroadcast || isGroup) {
                            Box(
                                modifier = Modifier.size(38.dp).clip(CircleShape)
                                    .background(Palette.Broadcast),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (isBroadcast) "📢" else "👥", fontSize = 18.sp) }
                        } else if (peer != null) {
                            PeerAvatar(peer = peer, size = 38.dp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (peer != null) {
                        ReachIndicator(peer)
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }
        },
    ) { padding ->
        // consumeWindowInsets: the Scaffold padding already holds the navigation bar, so
        // imePadding must only add what the keyboard covers beyond it (no double gap).
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (peer != null) ReachabilityBanner(viewModel, peer)
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
                reverseLayout = true,
            ) {
                item(key = "bottom-gap") { Spacer(Modifier.height(8.dp)) }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            when {
                                isBroadcast -> ChatEmptyState("📢", "Broadcast to everyone in range",
                                    "Every phone that receives it passes it on, hop by hop. Broadcasts are not " +
                                        "encrypted — anyone in reach can read them.")
                                isGroup -> ChatEmptyState("👥", "Encrypted group",
                                    "Only phones holding this group's invite can read its messages. Share the " +
                                        "invite from the group info (tap the title).")
                                peer?.hasKeys == false -> ChatEmptyState("🔑", "No keys yet",
                                    "Scan each other's QR (🔑 on the home screen) or wait until ${peer.displayName()}'s " +
                                        "phone announces itself, so messages can be end-to-end encrypted.")
                                else -> ChatEmptyState("🔒", "End-to-end encrypted",
                                    "Messages are sealed on this phone and can only be opened on " +
                                        "${peer?.displayName() ?: "theirs"}'s. They travel phone to phone, through " +
                                        "LoRa hubs or the internet relay, and wait in the buffer until a path appears.")
                            }
                        }
                    }
                }
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ChatRow.Day -> DaySeparator(row.label)
                        is ChatRow.Bubble -> MessageBubble(
                            msg = row.msg,
                            // Multi-party chats label incoming bubbles with their sender (once per run).
                            senderLabel = if ((isBroadcast || isGroup) && !row.continued) nameOf(row.msg.from) else null,
                            continued = row.continued,
                            onInfo = { infoFor = row.msg.msgId.take(8) },
                            onCopy = {
                                clipboard.setText(AnnotatedString(row.msg.text))
                                // Android 13+ confirms clipboard writes itself.
                                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                                    scope.launch { snackbars.showSnackbar("Copied") }
                                }
                            },
                        )
                    }
                }
            }
            val composerError by viewModel.composerError.collectAsState()
            composerError?.let { err ->
                Text(
                    err,
                    color = Palette.Error,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                        .background(Palette.ErrorSoft)
                        .clickable(onClickLabel = "Dismiss") { viewModel.clearComposerError() }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (isGroup && group == null) {
                Surface(color = Palette.CardSurface, shadowElevation = 4.dp) {
                    Text("You left this group, so you can't send to it any more. Rejoin with an invite (👥).",
                        fontSize = 12.sp, color = Palette.TextSecondary, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
            } else {
                MessageComposer(placeholder = placeholder, onSend = { viewModel.sendTestMessage(it) })
            }
        }
    }

    if (showInfo) {
        when {
            group != null -> GroupInfoDialog(viewModel, group, membersSeen) { showInfo = false }
            peer != null -> ContactInfoDialog(
                viewModel, peer,
                onRename = { showInfo = false; renaming = true },
                onDismiss = { showInfo = false },
            )
            else -> showInfo = false
        }
    }
    if (renaming && peer != null) {
        RenameDialog(peer = peer, onDismiss = { renaming = false },
            onSave = { name -> viewModel.renamePeer(peer.nodeId, name); renaming = false })
    }
    infoFor?.let { id -> MessageInfoDialog(viewModel, id) { infoFor = null } }
}

/** A row of the conversation: a date separator or a message bubble. */
private sealed interface ChatRow {
    val key: String

    data class Day(val label: String, override val key: String) : ChatRow

    /** @param continued same sender as the bubble above within a few minutes (tighter, unlabeled). */
    data class Bubble(val msg: ChatMessage, val continued: Boolean) : ChatRow {
        override val key: String get() = msg.msgId
    }
}

/** Oldest-first rows: a date separator before each new day, and sender runs marked as continued. */
private fun buildChatRows(messages: List<ChatMessage>, nowMs: Long): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 4)
    var lastDay = Long.MIN_VALUE
    var prev: ChatMessage? = null
    for (m in messages) {
        val day = UiText.dayKey(m.createdAtMs)
        if (day != lastDay) {
            rows += ChatRow.Day(UiText.dayLabel(m.createdAtMs, nowMs), "day-$day")
            lastDay = day
            prev = null
        }
        val p = prev
        val continued = p != null && p.isOutgoing == m.isOutgoing && p.from == m.from &&
            m.createdAtMs - p.createdAtMs < 5 * 60_000L
        rows += ChatRow.Bubble(m, continued)
        prev = m
    }
    return rows
}

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(10.dp), color = Palette.CardSurface, shadowElevation = 1.dp) {
            Text(label, fontSize = 11.sp, color = Palette.TextSecondary, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun ChatEmptyState(icon: String, title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Palette.CardSurface,
        shadowElevation = 1.dp,
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(icon, fontSize = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(body, fontSize = 12.sp, color = Palette.TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

/**
 * One chat bubble. Tap or long-press opens Message info / Copy. Outgoing bubbles show the unified
 * delivery state (🕓 Queued · ✓ Sent · ✓✓ Delivered · ! Expired); incoming ones show the link the
 * message arrived over.
 *
 * @param senderLabel shown above incoming bubbles in group / broadcast chats (null in 1:1).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    senderLabel: String?,
    continued: Boolean,
    onInfo: () -> Unit,
    onCopy: () -> Unit,
) {
    val isOutgoing = msg.isOutgoing
    val bgColor = if (isOutgoing) Palette.BubbleOut else Palette.BubbleIn
    val shape = if (isOutgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }
    // Darker-than-palette tones so the small status text stays readable on the green bubble.
    val stateTone = when (msg.state) {
        DeliveryState.DELIVERED -> Palette.DeliveredText
        DeliveryState.QUEUED -> Palette.QueuedText
        DeliveryState.EXPIRED, DeliveryState.DROPPED -> Palette.Error
        else -> Palette.BubbleMeta
    }
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(top = if (continued) 2.dp else 6.dp)) {
        val maxBubble = maxWidth * 0.8f
        Box(modifier = Modifier.align(if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart)) {
            Surface(
                shape = shape,
                color = bgColor,
                shadowElevation = 1.dp,
                modifier = Modifier.widthIn(max = maxBubble)
                    .clip(shape)
                    .combinedClickable(
                        onClickLabel = "Message options",
                        onLongClickLabel = "Message options",
                        onClick = { menuOpen = true },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                    ),
            ) {
                Column(modifier = Modifier.padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 5.dp)) {
                    if (!isOutgoing && senderLabel != null) {
                        Text(senderLabel, fontSize = 12.sp, color = Palette.PrimaryDark,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(msg.text, fontSize = 15.sp, color = Palette.TextPrimary, lineHeight = 20.sp)
                    Row(
                        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(msg.time, fontSize = 11.sp, color = Palette.BubbleMeta)
                        if (isOutgoing) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                listOf(msg.state.short, msg.state.tick).filter { it.isNotBlank() }.joinToString(" "),
                                fontSize = 11.sp, color = stateTone, fontWeight = FontWeight.Medium,
                            )
                        } else if (msg.via.isNotBlank()) {
                            Text(" · via ${msg.via}", fontSize = 11.sp, color = Palette.BubbleMeta)
                        }
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Message info") }, onClick = { menuOpen = false; onInfo() })
                DropdownMenuItem(text = { Text("Copy text") }, onClick = { menuOpen = false; onCopy() })
            }
        }
    }
}

/**
 * Multi-line composer. Send is disabled while the text is blank or over the single-frame byte
 * budget; the counter only appears once the budget starts to matter.
 */
@Composable
private fun MessageComposer(placeholder: String, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val byteLen = remember(text) { text.toByteArray(Charsets.UTF_8).size }
    val max = DtnViewModel.MAX_MSG_BYTES
    val over = byteLen > max
    val canSend = text.isNotBlank() && !over
    Surface(
        color = Palette.CardSurface,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (byteLen >= max * 3 / 4) {
                Text(
                    if (over) "Too long — $byteLen/$max bytes. Messages travel in one radio frame; shorten it to send."
                    else "$byteLen/$max bytes",
                    fontSize = 11.sp,
                    color = if (over) Palette.Error else Palette.TextSecondary,
                    modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(placeholder, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    minLines = 1,
                    maxLines = 5,
                    isError = over,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Palette.Surface,
                        unfocusedContainerColor = Palette.Surface,
                        errorContainerColor = Palette.ErrorSoft,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                FilledIconButton(
                    onClick = { if (canSend) { onSend(text); text = "" } },
                    enabled = canSend,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Palette.Primary, contentColor = Color.White,
                        disabledContainerColor = Palette.Primary.copy(alpha = 0.35f),
                        disabledContentColor = Color.White,
                    ),
                    modifier = Modifier.size(52.dp).padding(bottom = 2.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}

/* ==========================================================================
 *  Network tab — refresh, peers+P, routes, buffer
 * ========================================================================== */

@Composable
private fun NetworkTab(viewModel: DtnViewModel, onOpenChat: (String) -> Unit) {
    val peers by viewModel.peerList.collectAsState()
    val messages by viewModel.receivedMessages.collectAsState()
    val buffered by viewModel.bufferedMessages.collectAsState()
    val bufferedCount by viewModel.bufferedCount.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val routes by viewModel.messageRoutes.collectAsState()
    val activeStrategy by viewModel.activeStrategy.collectAsState()
    val benchmarks by viewModel.benchmarks.collectAsState()
    val metrics by viewModel.peerMetrics.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val onlinePeers = peers.count { it.reachable }
    val viaInternet = peers.count { !it.isOnline && it.internetOnline }
    val myId8 = localNodeId?.takeLast(8)
    // A resolved route reached its destination. Split three ways so our OWN delivered messages
    // aren't counted as relays: sent-by-me ✓, relayed-for-others ✓, and received-for-me.
    val resolvedRoutes = routes.count { it.delivered }
    val receivedRoutes = routes.count { it.delivered && it.destination == myId8 }
    val sentDelivered = routes.count { it.delivered && it.destination != myId8 && it.source == myId8 }
    val relayedDelivered = routes.count { it.delivered && it.destination != myId8 && it.source != myId8 }
    // Group messages travel as broadcasts; resolve their destination to the group name.
    val groupLabelFor: (String) -> String? = { msgId8 ->
        messages.firstOrNull { it.msgId.take(8) == msgId8 }?.toNodeId
            ?.takeIf { it.startsWith("grp:") }
            ?.let { key -> groups.firstOrNull { "grp:${it.channel}" == key }?.let { "Group · ${it.name}" } ?: "Group" }
    }
    // Route rows show the same unified state as the chat bubble (chat row + journal).
    val chatById = remember(messages) { messages.associateBy { it.msgId.take(8) } }
    var selectedMsg by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedBuffer by remember { mutableStateOf<BufferedMsgInfo?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item { Spacer(Modifier.height(10.dp)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("NETWORK OVERVIEW", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = Palette.Primary, letterSpacing = 1.sp)
                        Text("This node · ${localNodeId?.takeLast(8) ?: "—"}", fontSize = 11.sp,
                            color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
                    }
                    FilledTonalIconButton(
                        onClick = { viewModel.refreshPeerStatus() },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Palette.Primary.copy(alpha = 0.10f),
                            contentColor = Palette.Primary,
                        ),
                    ) { Icon(Icons.Default.Refresh, contentDescription = "Refresh peer status") }
                }
            }
            item {
                NetworkSummaryCard(
                    onlinePeers = onlinePeers,
                    totalPeers = peers.size,
                    bufferedCount = bufferedCount,
                    sentDelivered = sentDelivered,
                    relayedDelivered = relayedDelivered,
                    receivedRoutes = receivedRoutes,
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                StrategySwitchRow(
                    active = activeStrategy,
                    onSelect = { viewModel.setStrategy(it) },
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                BenchmarksCard(benchmarks, onReset = { viewModel.resetBenchmarks() })
            }
            item {
                Spacer(Modifier.height(14.dp))
                AnalyticsSectionHeader(
                    "Peers",
                    "${peers.size} known · $onlinePeers active" + if (viaInternet > 0) " ($viaInternet via internet)" else "",
                )
                Text("Tap a peer to open the chat.", fontSize = 11.sp, color = Palette.TextMuted,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            if (peers.isEmpty()) {
                item { EmptyState("No peers seen yet", "Connect to start discovery") }
            } else {
                // Keys are namespaced per section ("peer-"/"route-"/"buf-") because a single
                // LazyColumn requires globally-unique keys: a message can be BOTH buffered and
                // present in route observations, and both use the same 8-char msgId — an
                // un-namespaced key would collide across the two items() blocks and crash the tab.
                items(peers, key = { "peer-${it.nodeId}" }) { peer ->
                    NetworkPeerRow(peer, metrics[peer.nodeId], onClick = { onOpenChat(peer.nodeId) })
                }
            }

            item {
                Spacer(Modifier.height(14.dp))
                AnalyticsSectionHeader("Local route observations", "$resolvedRoutes resolved · ${routes.size - resolvedRoutes} in transit")
                Text("Observed at this phone — tap any route for message details.",
                    fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
            if (routes.isEmpty()) {
                item { EmptyState("No route observations yet", "Send a message to start tracking") }
            } else {
                // distinctBy guards against duplicate route records; the "route-" prefix keeps
                // these keys distinct from the buffered section (same msgId, different section).
                items(routes.distinctBy { it.msgId }, key = { "route-${it.msgId}" }) { r ->
                    val state = chatById[r.msgId]?.state ?: deliveryState(null, null, r.isBroadcast, r)
                    RouteRow(r, peers, myId8, state, destOverride = groupLabelFor(r.msgId)) { selectedMsg = r.msgId }
                }
            }

            item {
                Spacer(Modifier.height(14.dp))
                AnalyticsSectionHeader("Buffered messages", "$bufferedCount awaiting a delivery opportunity")
            }
            if (buffered.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Palette.Accent.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("✓ No messages are waiting to be forwarded", modifier = Modifier.padding(12.dp),
                            fontSize = 12.sp, color = Palette.Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                items(buffered, key = { "buf-${it.msgId}" }) { msg ->
                    BufferRow(msg, onClick = { selectedBuffer = msg })
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        // Live "Message info" — the same dialog the chat bubble opens.
        selectedMsg?.let { id -> MessageInfoDialog(viewModel, id) { selectedMsg = null } }

        selectedBuffer?.let { buf ->
            // A buffered message is one still awaiting an onward forwarding decision, so the
            // routing-math breakdown (candidate carriers / best next hop toward its destination)
            // is exactly the relevant view here. Resolve the full dest id from the peer list
            // (BufferedMsgInfo only carries the 8-char suffix); broadcast/unknown → no math.
            val fullDest = peers.firstOrNull { it.nodeId.takeLast(8) == buf.dest }?.nodeId
            val explanation = fullDest?.let { viewModel.explainRoute(it) }
            BufferDetailDialog(
                buf = buf,
                explanation = explanation,
                onDismiss = { selectedBuffer = null },
            )
        }
    }
}

@Composable
private fun StrategySwitchRow(active: String, onSelect: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("ROUTING PROTOCOL", fontSize = 9.sp, color = Palette.TextMuted, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StrategyChip("PROPHET", "Probabilistic", active == "PROPHET") { onSelect("PROPHET") }
                    StrategyChip("STABLE", "Stability-aware", active == "STABLE") { onSelect("STABLE") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StrategyChip("MAXPROP", "Path-cost", active == "MAXPROP") { onSelect("MAXPROP") }
                    StrategyChip("DIMAXPROP", "Path-cost + distance", active == "DIMAXPROP") { onSelect("DIMAXPROP") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StrategyChip("EPIDEMIC", "Flood-all", active == "EPIDEMIC") { onSelect("EPIDEMIC") }
                    StrategyChip("QLEARNING", "Reinforcement-learning", active == "QLEARNING") { onSelect("QLEARNING") }
                }
            }
        }
    }
}

/** Per-strategy benchmark comparison (local counters), with a reset control. */
@Composable
private fun BenchmarksCard(
    stats: List<com.dtn.mesh.learning.BenchmarkStat>,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BENCHMARKS", fontSize = 9.sp, color = Palette.TextMuted, letterSpacing = 0.8.sp)
                Spacer(Modifier.weight(1f))
                Text("reset", fontSize = 10.sp, color = Palette.Primary,
                    modifier = Modifier.clickable(onClick = onReset))
            }
            Text("Local per-strategy comparison — switch strategies to accumulate columns.",
                fontSize = 10.sp, color = Palette.TextMuted, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            if (stats.isEmpty()) {
                Text("No data yet — send/relay some messages.", fontSize = 11.sp, color = Palette.TextMuted)
            } else {
                // Fixed column widths inside a horizontal scroller: full strategy names
                // (STABLE-PROPHET, Q-LEARNING) never truncate and numbers never collide with
                // headers, whatever the screen width or font scale.
                val strategyCol = 124.dp
                val numCol = 58.dp
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Column {
                        Row {
                            BenchCell("STRATEGY", strategyCol, header = true)
                            listOf("FWD", "DELIV", "HOPS", "LAT (s)", "EFF").forEach { BenchCell(it, numCol, header = true) }
                        }
                        HorizontalDivider(color = Palette.Divider, modifier = Modifier.width(strategyCol + numCol * 5))
                        stats.forEach { s ->
                            Row(modifier = Modifier.padding(vertical = 3.dp)) {
                                BenchCell(s.strategy, strategyCol, bold = true)
                                BenchCell("${s.forwards}", numCol, mono = true)
                                BenchCell("${s.deliveries}", numCol, mono = true, color = Palette.Accent)
                                BenchCell("%.1f".format(s.avgHops), numCol, mono = true)
                                BenchCell("%.1f".format(s.avgLatencyMs / 1000.0), numCol, mono = true)
                                BenchCell("%.2f".format(s.efficiency), numCol, mono = true, color = Palette.Primary)
                            }
                        }
                    }
                }
                Text("Swipe sideways if columns don't fit.", fontSize = 9.sp, color = Palette.TextMuted,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun BenchCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    header: Boolean = false,
    bold: Boolean = false,
    mono: Boolean = false,
    color: Color = if (header) Palette.TextMuted else Palette.TextSecondary,
) {
    Text(
        text,
        modifier = Modifier.width(width).padding(end = 6.dp),
        fontSize = if (header) 9.sp else 11.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = if (bold) Palette.TextPrimary else color,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        maxLines = 1,
    )
}

@Composable
private fun RowScope.StrategyChip(name: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Palette.Primary else Palette.Surface
    val fg = if (selected) Color.White else Palette.TextSecondary
    Surface(
        modifier = Modifier.weight(1f).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = if (selected) null
            else androidx.compose.foundation.BorderStroke(1.dp, Palette.Divider),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp)) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = fg)
            Text(subtitle, fontSize = 9.sp, color = if (selected) Color.White.copy(alpha = 0.8f) else Palette.TextMuted)
        }
    }
}

/** Tappable header that reveals/hides [content]. Local, transient expand state. */
@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Primary)
                if (subtitle != null) Text(subtitle, fontSize = 10.sp, color = Palette.TextMuted)
            }
            Text(if (expanded) "▾" else "▸", fontSize = 14.sp, color = Palette.Primary)
        }
        if (expanded) content()
    }
}

@Composable
private fun NetworkSummaryCard(
    onlinePeers: Int,
    totalPeers: Int,
    bufferedCount: Int,
    sentDelivered: Int,
    relayedDelivered: Int,
    receivedRoutes: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(Palette.PrimaryDark, Palette.Primary)))
                .padding(vertical = 16.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SummaryMetric("ACTIVE", "$onlinePeers/$totalPeers", "peers", Color.White)
            SummaryMetric("BUFFER", "$bufferedCount", "waiting", if (bufferedCount == 0) Color.White else Color(0xFFFFE082))
            SummaryMetric("SENT ✓", "$sentDelivered", "my msgs", Color.White)
            SummaryMetric("RELAYED", "$relayedDelivered", "for others", Color.White)
            SummaryMetric("RECEIVED", "$receivedRoutes", "for me", Color.White)
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, detail: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.72f), letterSpacing = 0.8.sp)
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        Text(detail, fontSize = 10.sp, color = Color.White.copy(alpha = 0.72f))
    }
}

@Composable
private fun AnalyticsSectionHeader(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Primary,
            letterSpacing = 1.sp)
        Text(detail, fontSize = 10.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Primary,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
    )
}

@Composable
private fun NetworkPeerRow(peer: PeerInfo, metric: PeerMetric?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clickable(onClickLabel = "Open chat with ${peer.displayName()}", onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(peer = peer, size = 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(peer.displayName(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = Palette.TextPrimary)
                Text(
                    "${peer.nodeId.takeLast(8)} · RSSI ${if (peer.lastRssi == -200) "—" else peer.lastRssi}",
                    fontSize = 11.sp, color = Palette.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    when {
                        peer.isOnline && peer.hubOnly -> "active now · via LoRa hub"
                        peer.isOnline -> "active now · nearby"
                        peer.internetOnline -> "online via internet relay"
                        peer.viaOtherHub -> "active now · via LoRa (${peer.meshHubLabel})"
                        else -> "last seen ${relativeSince(peer.lastSeenMs)}"
                    },
                    fontSize = 10.sp,
                    color = when {
                        peer.isOnline -> Palette.Accent
                        peer.internetOnline -> Palette.Primary
                        peer.viaOtherHub -> Palette.Accent
                        else -> Palette.TextMuted
                    },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val (chipText, chipColor) = when {
                    peer.isOnline -> "ACTIVE" to Palette.Accent
                    peer.internetOnline -> "INTERNET" to Palette.Primary
                    peer.viaOtherHub -> "LORA" to Palette.Accent
                    else -> "INACTIVE" to Palette.Error
                }
                Surface(shape = RoundedCornerShape(8.dp), color = chipColor.copy(alpha = 0.15f)) {
                    Text(chipText, fontSize = 10.sp, color = chipColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                Spacer(Modifier.height(4.dp))
                // The ACTIVE strategy's own score: P (PRoPHET), f (MaxProp), Q fwd/wait (Q-learning).
                Text(
                    "${metric?.label ?: "P"} = ${metric?.value ?: "—"}   ${estimateDistStr(peer.lastRssi)}",
                    fontSize = 10.sp, color = Palette.TextMuted,
                    fontFamily = FontFamily.Monospace,
                )
                if (metric != null && metric.hint.isNotBlank()) {
                    Text(metric.hint, fontSize = 9.sp, color = Palette.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun RouteRow(
    r: com.dtn.mesh.service.MessageLifecycleLog.RouteRecord,
    peers: List<PeerInfo>,
    myId8: String?,
    state: DeliveryState,
    destOverride: String? = null,
    onClick: () -> Unit,
) {
    // Same state (and colour) as the chat bubble and Message info.
    val statusColor = stateColor(state)
    val statusText = state.pill
    val fromLabel = peerLabelFor(r.source, peers, myId8)
    val toLabel = destOverride ?: peerLabelFor(r.destination, peers, myId8)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Colored status rail — instant delivered/in-transit read.
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(statusColor))
            Column(modifier = Modifier.weight(1f).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MSG ${r.msgId}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(8.dp), color = statusColor.copy(alpha = 0.15f)) {
                        Text(statusText, fontSize = 9.sp, color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Highlight the human-readable endpoints first, node ids second.
                Text(
                    "${fromLabel ?: r.source}  →  ${toLabel ?: r.destination}",
                    fontSize = 13.sp, color = Palette.TextPrimary, fontWeight = FontWeight.SemiBold,
                )
                Text("${r.source} → ${r.destination}", fontSize = 9.sp,
                    color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
                Text("OBSERVED PATH", fontSize = 8.sp, color = Palette.TextMuted,
                    letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 6.dp))
                Text(formatObservedPath(r, peers, myId8), fontSize = 12.sp, color = Palette.Primary,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        if (r.lastPath.isNotBlank()) append("via ${r.lastPath} · ")
                        append("updated ${relativeSince(r.timestampMs)}")
                        append(" · tap for message info")
                    },
                    fontSize = 9.sp, color = Palette.Primary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Map a truncated (last-8) node id — as stored on route/lifecycle records — back to a
 * peer's saved nickname / long name for display. Returns null when there's no match so
 * callers can fall back to the raw id.
 */
internal fun peerLabelFor(idSuffix: String?, peers: List<PeerInfo>, myId8: String? = null): String? {
    if (idSuffix.isNullOrBlank()) return null
    // Every broadcast form ("BROADCAST", "^all", "!ffffffff", its last-8 "ffffffff") reads the same.
    if (UiText.isBroadcastId(idSuffix)) return "Broadcast"
    if (myId8 != null && idSuffix == myId8) return "YOU"
    val match = peers.firstOrNull { it.nodeId.takeLast(8) == idSuffix } ?: return null
    val label = match.displayName()
    // displayName() falls back to the last-8 id; if that's all we have it isn't a real
    // nickname, so treat it as "no friendly name".
    return if (label == idSuffix) null else label
}

/**
 * Render this node's observed path for a route, using friendly labels and marking the
 * origin→previous-hop segment with an ellipsis (⋯) because those intermediate hops are NOT
 * observed from here — we only directly know who we received from, who we are, and who we
 * forwarded to. Segments we actually saw use a solid arrow (→).
 *
 * Examples:  `vrishank → YOU`            (received directly from the origin)
 *            `vrishank ⋯ priya → YOU`    (originated at vrishank, reached us via priya)
 *            `YOU → priya`               (we forwarded onward)
 */
internal fun formatObservedPath(
    r: com.dtn.mesh.service.MessageLifecycleLog.RouteRecord,
    peers: List<PeerInfo>,
    myId8: String?,
): String {
    fun lbl(id: String?): String = when {
        id.isNullOrBlank() -> "?"
        id == myId8 || id == r.me -> "YOU"
        else -> peerLabelFor(id, peers, myId8) ?: id
    }
    val sb = StringBuilder(lbl(r.source))
    val prev = r.prevHop
    if (!prev.isNullOrBlank() && prev != r.source && prev != r.me) {
        sb.append("  ⋯  ").append(lbl(prev)) // unobserved hops between origin and the peer we got it from
    }
    if (r.me.isNotBlank() && r.me != r.source) sb.append("  →  ").append("YOU")
    val next = r.nextHop
    if (!next.isNullOrBlank() && next != r.me) sb.append("  →  ").append(lbl(next))
    // Published to every relay subscriber at once: there's no single next hop to name.
    else if (r.isBroadcast && r.lastPath.isNotBlank()) sb.append("  →  everyone (").append(r.lastPath).append(")")
    return sb.toString()
}

/** Compact relative "time since" label for a last-seen timestamp. */
private fun relativeSince(ms: Long): String {
    if (ms <= 0L) return "never"
    val delta = System.currentTimeMillis() - ms
    return when {
        delta < 10_000L -> "just now"
        delta < 60_000L -> "${delta / 1000}s ago"
        delta < 3_600_000L -> "${delta / 60_000}m ago"
        delta < 86_400_000L -> "${delta / 3_600_000}h ago"
        else -> "${delta / 86_400_000}d ago"
    }
}

/**
 * Detail view for a buffered message. Unlike a delivered/received message, a buffered one is
 * still awaiting an onward forwarding opportunity — so the routing-math breakdown (candidate
 * carriers / best next hop toward its destination) is the relevant, sensible view and is shown
 * when available.
 */
@Composable
private fun BufferDetailDialog(
    buf: BufferedMsgInfo,
    explanation: RoutingExplanation?,
    onDismiss: () -> Unit,
) {
    val urgencyColor = when {
        buf.ttlMin <= 5L -> Palette.Error
        buf.ttlMin <= 30L -> Palette.Warning
        else -> Palette.Primary
    }
    val urgencyText = when {
        buf.ttlMin <= 5L -> "EXPIRING SOON"
        buf.ttlMin <= 30L -> "TIME SENSITIVE"
        else -> "QUEUED"
    }
    val isBroadcast = buf.dest == "BROADCAST"
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Column {
                Text("Buffered message", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    color = Palette.TextPrimary)
                Text("MSG ${buf.msgId}", fontSize = 11.sp, color = Palette.TextMuted,
                    fontFamily = FontFamily.Monospace)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Surface(shape = RoundedCornerShape(8.dp), color = urgencyColor.copy(alpha = 0.15f)) {
                    Text(urgencyText, fontSize = 10.sp, color = urgencyColor, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.height(10.dp))

                DetailRow("From", buf.origin)
                DetailRow("To", if (isBroadcast) "Broadcast (all peers)" else buf.dest)
                DetailRow("Status", buf.status)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile(modifier = Modifier.weight(1f), label = "TTL LEFT", value = "${buf.ttlMin}m", tint = urgencyColor)
                    StatTile(modifier = Modifier.weight(1f), label = "IN BUFFER", value = "${buf.bufferedFor}s", tint = Palette.Primary)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatTile(modifier = Modifier.weight(1f), label = "HOPS", value = "${buf.hopCount}", tint = Palette.TextSecondary)
                    StatTile(modifier = Modifier.weight(1f), label = "FORWARDS", value = "${buf.forwardCount}", tint = Palette.TextSecondary)
                }

                HorizontalDivider(color = Palette.Divider, modifier = Modifier.padding(vertical = 10.dp))
                Text("WHY IT'S HERE", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = Palette.Primary, letterSpacing = 0.8.sp)
                Text(
                    if (isBroadcast)
                        "Held until it has been fanned out to every online peer, then cleared."
                    else
                        "Held (store-carry-forward) until a good delivery opportunity toward the " +
                            "destination appears — a direct encounter or a higher-scoring carrier.",
                    fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )

                if (explanation != null) {
                    Spacer(Modifier.height(6.dp))
                    // Reuse the forward-scoring breakdown: for a buffered message this is the
                    // live "who should carry it next" decision, which is genuinely meaningful.
                    RoutingMathSection(exp = explanation, destLabel = buf.dest)
                } else if (!isBroadcast) {
                    Spacer(Modifier.height(6.dp))
                    Text("No per-carrier scores yet (destination not seen, or a strategy without a " +
                        "probability model).", fontSize = 11.sp, color = Palette.TextMuted)
                }
            }
        },
    )
}

/**
 * Expandable probability-math breakdown for how the active strategy scores routes toward the
 * message's destination. Shows my own belief, each candidate carrier's belief, the 2-hop path
 * likelihood, and the transitive gain — annotating the previous/next hop on this route.
 */
@Composable
internal fun RoutingMathSection(
    exp: RoutingExplanation,
    destLabel: String,
    retrospective: Boolean = false,
    prevHop: String? = null,
    nextHop: String? = null,
) {
    ExpandableSection(
        title = "Routing math · ${exp.strategy}",
        subtitle = if (retrospective)
            "Retrospective — how the path toward $destLabel was scored · tap to expand"
        else
            "How the path toward $destLabel is scored — tap to expand",
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            // ── Headline scores in two highlighted stat tiles ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = exp.myScoreLabel.uppercase(),
                    value = "%.3f".format(exp.myScore),
                    tint = Palette.Primary,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = "BEST NEXT HOP",
                    value = exp.bestNextHop?.let { exp.bestNextHopName ?: it.takeLast(8) } ?: "none",
                    subValue = exp.bestNextHop?.let { "${exp.bestScoreLabel} ${"%.3f".format(exp.bestScore)}" },
                    tint = if (exp.bestNextHop != null) Palette.Accent else Palette.TextMuted,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("PATH MAP", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = Palette.TextSecondary, letterSpacing = 0.8.sp)
            Text("YOU → chosen next hop (green). Grey = online peers not selected.",
                fontSize = 10.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
            RoutingMapView(exp)

            Spacer(Modifier.height(14.dp))
            Text("CANDIDATE CARRIERS", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = Palette.TextSecondary, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            if (exp.beliefs.isEmpty()) {
                Text("No candidate carriers known yet.", fontSize = 12.sp, color = Palette.TextMuted,
                    modifier = Modifier.padding(vertical = 6.dp))
            } else {
                exp.beliefs.take(8).forEachIndexed { i, b ->
                    val role = when (b.nodeId.takeLast(8)) {
                        prevHop -> "prev hop"
                        nextHop -> "next hop"
                        else -> null
                    }
                    CarrierBeliefRow(b, role, exp.bestScoreLabel, isBest = i == 0 && b.score > 0.0)
                }
            }

            Spacer(Modifier.height(14.dp))
            ExpandableSection(title = "Formulas & constants") {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    exp.formulas.forEach { FormulaLine(it.label, it.formula) }
                    Spacer(Modifier.height(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = Palette.Surface,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(exp.constantsLine, fontSize = 11.sp, color = Palette.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    subValue: String? = null,
    tint: Color,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.10f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Text(label, fontSize = 9.sp, color = Palette.TextMuted, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
            if (subValue != null) {
                Text(subValue, fontSize = 10.sp, color = Palette.TextSecondary,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/**
 * A tiny "next-hop map": YOU on the left, each online candidate carrier on the right. The
 * chosen next hop is drawn in green with a thick solid line; the other online peers (offered
 * but not selected) are drawn in grey. Purely a visualisation of the current [exp] scoring.
 */
@Composable
private fun RoutingMapView(exp: RoutingExplanation) {
    val online = exp.beliefs.filter { it.isOnline }.take(6)
    if (online.isEmpty()) {
        Text("No online peers to route through right now.", fontSize = 11.sp,
            color = Palette.TextMuted, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val chosenColor = Palette.Accent
    val greyColor = Color(0xFFBDBDBD)
    val youColor = Palette.Primary
    // Rows grow with the user's font scale so two lines of label always fit.
    val rowHeight = with(LocalDensity.current) { 22.sp.toDp() * 2 }.coerceAtLeast(40.dp)

    // Lines and dots are drawn on a canvas; every label is real Compose text in its own row
    // (ellipsized), so nothing clips or overlaps on narrow screens or with large text.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(rowHeight * online.size + 8.dp)) {
        // Keep the carrier dots at ~40% of the width (bounded) so the labels get the rest.
        val dotX = (maxWidth * 0.40f).coerceIn(72.dp, 150.dp)
        val leftX = 22.dp
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nodeR = 7.dp.toPx()
            val rowPx = rowHeight.toPx()
            val youCenter = Offset(leftX.toPx(), (rowPx * online.size) / 2f + 4.dp.toPx())
            online.forEachIndexed { i, b ->
                val target = Offset(dotX.toPx(), 4.dp.toPx() + rowPx * (i + 0.5f))
                val chosen = b.nodeId == exp.bestNextHop
                drawLine(
                    color = if (chosen) chosenColor else greyColor,
                    start = youCenter, end = target,
                    strokeWidth = if (chosen) 3.dp.toPx() else 1.dp.toPx(),
                )
                drawCircle(if (chosen) chosenColor else greyColor, radius = nodeR, center = target)
            }
            drawCircle(youColor, radius = nodeR + 2.dp.toPx(), center = youCenter)
        }
        Text(
            "YOU", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = youColor,
            modifier = Modifier.padding(start = 6.dp, top = (rowHeight * online.size) / 2f - 18.dp),
        )
        Column(modifier = Modifier.fillMaxSize().padding(start = dotX + 14.dp, top = 4.dp)) {
            online.forEach { b ->
                val chosen = b.nodeId == exp.bestNextHop
                Column(modifier = Modifier.height(rowHeight), verticalArrangement = Arrangement.Center) {
                    Text(
                        b.displayName + if (chosen) "  ✓" else "",
                        fontSize = 12.sp,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (chosen) chosenColor else Palette.TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${exp.bestScoreLabel} %.2f".format(b.score),
                        fontSize = 10.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CarrierBeliefRow(
    b: RoutingNodeBelief,
    role: String?,
    scoreLabel: String,
    isBest: Boolean,
) {
    val dot = if (b.isOnline) Palette.Accent else Palette.Error
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (isBest) Palette.Accent.copy(alpha = 0.08f) else Palette.Surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(b.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = Palette.TextPrimary)
                if (role != null) {
                    Spacer(Modifier.width(6.dp))
                    Surface(shape = RoundedCornerShape(6.dp), color = Palette.Primary.copy(alpha = 0.12f)) {
                        Text(role, fontSize = 8.sp, color = Palette.Primary, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("$scoreLabel ${"%.3f".format(b.score)}", fontSize = 12.sp, color = Palette.Primary,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(b.detail, fontSize = 10.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun FormulaLine(label: String, formula: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label, fontSize = 10.sp, color = Palette.TextSecondary, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(formula, fontSize = 12.sp, color = Palette.TextPrimary, fontFamily = FontFamily.Monospace)
    }
}

/** Prefer the friendly label, then append the raw id in parentheses for traceability. */
internal fun labelWithId(label: String?, id: String?): String = when {
    id.isNullOrBlank() -> "—"
    label == null -> id
    else -> "$label ($id)"
}

@Composable
internal fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(label.uppercase(), fontSize = 9.sp, color = Palette.TextMuted, letterSpacing = 0.6.sp)
        Text(
            value,
            fontSize = 13.sp,
            color = Palette.TextPrimary,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun BufferRow(msg: BufferedMsgInfo, onClick: () -> Unit = {}) {
    val urgencyColor = when {
        msg.ttlMin <= 5L -> Palette.Error
        msg.ttlMin <= 30L -> Palette.Warning
        else -> Palette.Primary
    }
    val urgencyText = when {
        msg.ttlMin <= 5L -> "EXPIRING SOON"
        msg.ttlMin <= 30L -> "TIME SENSITIVE"
        else -> "QUEUED"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = urgencyColor.copy(alpha = 0.06f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MSG ${msg.msgId}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(8.dp), color = urgencyColor.copy(alpha = 0.16f)) {
                    Text(urgencyText, fontSize = 9.sp, color = urgencyColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("${msg.origin}  →  ${msg.dest}", fontSize = 10.sp, color = Palette.TextSecondary,
                fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BufferMetric("TTL", "${msg.ttlMin}m", urgencyColor)
                BufferMetric("AGE", "${msg.bufferedFor}s", Palette.TextSecondary)
                BufferMetric("HOPS", "${msg.hopCount}", Palette.TextSecondary)
                BufferMetric("FORWARDS", "${msg.forwardCount}", Palette.TextSecondary)
            }
        }
    }
}

@Composable
private fun BufferMetric(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 8.sp, color = Palette.TextMuted, letterSpacing = 0.5.sp)
        Text(value, fontSize = 11.sp, color = color, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold)
    }
}

private fun estimateDistStr(rssi: Int): String {
    if (rssi == -200 || rssi == 0) return ""
    val d = Math.pow(10.0, ((-59.0) - rssi.coerceIn(-120, -20).toDouble()) / 25.0)
    return "· %.1fm".format(d.coerceIn(0.5, 500.0))
}

/* ==========================================================================
 *  Lifecycle tab — color-coded state feed
 * ========================================================================== */

private enum class LifecycleFilter(val label: String) {
    ALL("All"), FLOW("Flow"), DELIVERY("Delivery"), ACK("ACK"), ISSUES("Issues"),
}

@Composable
private fun LifecycleFilterChip(
    option: LifecycleFilter,
    selectedFilter: LifecycleFilter,
    onClick: () -> Unit,
) {
    val selected = selectedFilter == option
    AssistChip(
        onClick = onClick,
        label = { Text(option.label, fontSize = 10.sp) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) Palette.Primary.copy(alpha = 0.14f) else Palette.CardSurface,
            labelColor = if (selected) Palette.Primary else Palette.TextSecondary,
        ),
    )
}

@Composable
private fun LifecycleTab(viewModel: DtnViewModel) {
    val entries by viewModel.lifecycleEntries.collectAsState()
    var filter by remember { mutableStateOf(LifecycleFilter.ALL) }
    var infoFor by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleEntries = entries.filter { entry ->
        when (filter) {
            LifecycleFilter.ALL -> true
            LifecycleFilter.FLOW -> entry.event in setOf(
                com.dtn.mesh.service.LifecycleEvent.CREATED,
                com.dtn.mesh.service.LifecycleEvent.BUFFERED,
                com.dtn.mesh.service.LifecycleEvent.FORWARDED,
                com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED,
            )
            LifecycleFilter.DELIVERY -> entry.event == com.dtn.mesh.service.LifecycleEvent.DELIVERED
            LifecycleFilter.ACK -> entry.event in setOf(
                com.dtn.mesh.service.LifecycleEvent.ACK_GENERATED,
                com.dtn.mesh.service.LifecycleEvent.ACK_RECEIVED,
            )
            LifecycleFilter.ISSUES -> entry.event in setOf(
                com.dtn.mesh.service.LifecycleEvent.DUPLICATE,
                com.dtn.mesh.service.LifecycleEvent.IGNORED,
                com.dtn.mesh.service.LifecycleEvent.EXPIRED,
                com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT,
                com.dtn.mesh.service.LifecycleEvent.DROPPED,
            )
        }
    }
    val bufferHistory = deriveBufferHistory(entries)

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ── Buffer history: store-and-forward outcomes ──
        item {
            Spacer(Modifier.height(10.dp))
            AnalyticsSectionHeader("Buffer history", "${bufferHistory.size} store-and-forward records")
            Text("Messages that entered the buffer and how each one left it.",
                fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (bufferHistory.isEmpty()) {
            item { EmptyState("No buffered messages yet", "Carried messages will be tracked here") }
        } else {
            items(bufferHistory, key = { "buf-${it.msgId}" }) { BufferHistoryRow(it) { infoFor = it.msgId } }
        }

        // ── Live event log with filters ──
        item {
            Spacer(Modifier.height(16.dp))
            AnalyticsSectionHeader("Event log", "${entries.size} recent events")
            Text("Filter the live event stream to follow progress or investigate exceptions.",
                fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    LifecycleFilter.values().take(3).forEach { option -> LifecycleFilterChip(option, filter) { filter = option } }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    LifecycleFilter.values().drop(3).forEach { option -> LifecycleFilterChip(option, filter) { filter = option } }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (visibleEntries.isEmpty()) {
            item {
                EmptyState(if (entries.isEmpty()) "No events yet" else "No matching events",
                    "Send a message to see lifecycle tracking")
            }
        } else {
            // Index in the key guarantees uniqueness even if two events share the same
            // timestamp/msg/event triple, which the string-only key could otherwise collide on.
            itemsIndexed(
                visibleEntries,
                key = { i, e -> "$i-${e.timestamp}-${e.msgId}-${e.event}" },
            ) { _, e -> LifecycleEntryRow(e) { infoFor = e.msgId } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    infoFor?.let { id -> MessageInfoDialog(viewModel, id) { infoFor = null } }
}

/** One message's journey through the buffer, reconstructed from the lifecycle event feed. */
private data class BufferHistoryItem(
    val msgId: String,
    val origin: String,
    val dest: String,
    val bufferedAt: String,
    val clearedAt: String?,
    val clearEvent: com.dtn.mesh.service.LifecycleEvent?,
    val note: String,
)

/**
 * Reconstruct per-message buffer history from the (newest-first) lifecycle feed. A message
 * qualifies once it has a BUFFERED event; its disposition is the most recent terminal event
 * that removes it from the buffer. FORWARDED is intentionally NOT terminal — relaying to a
 * mule does not clear our buffer, only a receipt / direct delivery / expiry does.
 */
private fun deriveBufferHistory(
    entries: List<com.dtn.mesh.service.LifecycleEntry>,
): List<BufferHistoryItem> {
    val clearing = setOf(
        com.dtn.mesh.service.LifecycleEvent.DELIVERED,
        com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED,
        com.dtn.mesh.service.LifecycleEvent.EXPIRED,
        com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT,
        com.dtn.mesh.service.LifecycleEvent.DROPPED,
    )
    val byId = LinkedHashMap<String, MutableList<com.dtn.mesh.service.LifecycleEntry>>()
    for (e in entries) byId.getOrPut(e.msgId) { mutableListOf() }.add(e)
    val out = mutableListOf<BufferHistoryItem>()
    for ((id, list) in byId) {
        // list is newest-first: lastOrNull match = earliest BUFFERED, firstOrNull = latest clear.
        val buffered = list.lastOrNull { it.event == com.dtn.mesh.service.LifecycleEvent.BUFFERED } ?: continue
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

@Composable
private fun BufferHistoryRow(item: BufferHistoryItem, onInfo: () -> Unit) {
    val (accent, disposition) = when (item.clearEvent) {
        com.dtn.mesh.service.LifecycleEvent.DELIVERED -> Palette.Accent to "Delivered to destination"
        com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED -> Palette.Accent to "Cleared by delivery receipt"
        com.dtn.mesh.service.LifecycleEvent.EXPIRED -> Palette.Error to "Expired before delivery"
        com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT -> Palette.Error to "Dropped — hop limit"
        com.dtn.mesh.service.LifecycleEvent.DROPPED -> Palette.Error to "Dropped — low reachability"
        else -> Palette.Warning to "Still buffered"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clickable(onClickLabel = "Message info", onClick = onInfo),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(accent))
            Column(modifier = Modifier.weight(1f).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MSG ${item.msgId}", fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
                    Spacer(Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = 0.15f)) {
                        Text(disposition, fontSize = 9.sp, color = accent, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
                if (item.origin.isNotEmpty() || item.dest.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("${item.origin.ifEmpty { "—" }}  →  ${item.dest.ifEmpty { "—" }}",
                        fontSize = 10.sp, color = Palette.TextSecondary, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BufferMetric("BUFFERED", item.bufferedAt, Palette.TextSecondary)
                    BufferMetric("CLEARED", item.clearedAt ?: "—", accent)
                }
                if (item.note.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(item.note, fontSize = 10.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun LifecycleEntryRow(entry: com.dtn.mesh.service.LifecycleEntry, onInfo: () -> Unit) {
    val eventColor = lifecycleColor(entry.event)
    var expanded by remember(entry) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(eventColor))
            Column(modifier = Modifier.weight(1f).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lifecycleLabel(entry.event), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = eventColor)
                    Spacer(Modifier.weight(1f))
                    Text(entry.timestamp, fontSize = 10.sp, color = Palette.TextMuted,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(6.dp))
                    Text(if (expanded) "▾" else "▸", fontSize = 12.sp, color = Palette.TextMuted)
                }
                Text(buildString {
                    append("MSG ${entry.msgId}")
                    if (entry.origin.isNotEmpty()) append(" · FROM ${entry.origin}")
                    if (entry.dest.isNotEmpty()) append(" · TO ${entry.dest}")
                }, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Palette.TextSecondary)

                if (!expanded) {
                    // Collapsed: compact one-liner of the key metrics.
                    val metrics = buildString {
                        if (entry.hopCount >= 0) append("Hops ${entry.hopCount}")
                        if (entry.forwardCount >= 0) append(if (isNotEmpty()) " · " else "").append("Fwd ${entry.forwardCount}")
                        if (entry.ttlRemainingMin >= 0) append(if (isNotEmpty()) " · " else "").append("TTL ${entry.ttlRemainingMin}m")
                    }
                    if (metrics.isNotEmpty()) Text(metrics, fontSize = 10.sp, color = Palette.TextMuted,
                        fontFamily = FontFamily.Monospace, maxLines = 1)
                } else {
                    // Expanded: full labeled breakdown of every populated field.
                    Spacer(Modifier.height(6.dp))
                    LifecycleDetail("Event", lifecycleLabel(entry.event))
                    LifecycleDetail("Time", entry.timestamp)
                    LifecycleDetail("Message", entry.msgId)
                    if (entry.origin.isNotEmpty()) LifecycleDetail("From", entry.origin)
                    if (entry.dest.isNotEmpty()) LifecycleDetail("To", entry.dest)
                    if (entry.hopCount >= 0) LifecycleDetail("Hop count", entry.hopCount.toString())
                    if (entry.forwardCount >= 0) LifecycleDetail("Forward count", entry.forwardCount.toString())
                    if (entry.ttlRemainingMin >= 0) LifecycleDetail("TTL remaining", "${entry.ttlRemainingMin} min")
                    if (entry.extra.isNotEmpty()) LifecycleDetail("Detail", entry.extra)
                    TextButton(onClick = onInfo, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                        Text("Message info →", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LifecycleDetail(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, fontSize = 10.sp, color = Palette.TextMuted, modifier = Modifier.width(96.dp))
        Text(value, fontSize = 10.sp, color = Palette.TextSecondary, fontFamily = FontFamily.Monospace)
    }
}

internal fun lifecycleColor(event: com.dtn.mesh.service.LifecycleEvent): Color = when (event) {
    com.dtn.mesh.service.LifecycleEvent.CREATED -> Palette.Primary
    com.dtn.mesh.service.LifecycleEvent.BUFFERED, com.dtn.mesh.service.LifecycleEvent.DUPLICATE -> Palette.Warning
    com.dtn.mesh.service.LifecycleEvent.FORWARDED, com.dtn.mesh.service.LifecycleEvent.DELIVERED,
    com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED -> Palette.Accent
    com.dtn.mesh.service.LifecycleEvent.ACK_GENERATED, com.dtn.mesh.service.LifecycleEvent.ACK_RECEIVED -> Palette.Broadcast
    com.dtn.mesh.service.LifecycleEvent.IGNORED -> Palette.TextMuted
    com.dtn.mesh.service.LifecycleEvent.EXPIRED, com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT,
    com.dtn.mesh.service.LifecycleEvent.DROPPED -> Palette.Error
}

internal fun lifecycleLabel(event: com.dtn.mesh.service.LifecycleEvent): String = when (event) {
    com.dtn.mesh.service.LifecycleEvent.CREATED -> "Created"
    com.dtn.mesh.service.LifecycleEvent.BUFFERED -> "Buffered"
    com.dtn.mesh.service.LifecycleEvent.DUPLICATE -> "Duplicate received"
    com.dtn.mesh.service.LifecycleEvent.FORWARDED -> "Forwarded"
    com.dtn.mesh.service.LifecycleEvent.IGNORED -> "Ignored"
    com.dtn.mesh.service.LifecycleEvent.ACK_GENERATED -> "Acknowledgement generated"
    com.dtn.mesh.service.LifecycleEvent.ACK_RECEIVED -> "Acknowledgement received"
    com.dtn.mesh.service.LifecycleEvent.BUFFER_CLEARED -> "Buffer cleared"
    com.dtn.mesh.service.LifecycleEvent.DELIVERED -> "Delivered"
    com.dtn.mesh.service.LifecycleEvent.EXPIRED -> "Expired"
    com.dtn.mesh.service.LifecycleEvent.HOP_LIMIT -> "Hop limit reached"
    com.dtn.mesh.service.LifecycleEvent.DROPPED -> "Dropped (housekeeping)"
}

/* ==========================================================================
 *  Log tab
 * ========================================================================== */

@Composable
private fun LogTab(viewModel: DtnViewModel, logEntries: List<String>) {
    val engineLog by viewModel.engineLog.collectAsState()
    val exportPath by viewModel.exportPath.collectAsState()
    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        item {
            ResearchExportCard(lastPath = exportPath, onExport = { viewModel.exportData() })
            Spacer(Modifier.height(14.dp))
        }
        item {
            AnalyticsSectionHeader("Routing engine", "${engineLog.size} events")
            Text("Encounter history + per-strategy probability calculations.",
                fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (engineLog.isEmpty()) {
            item {
                Text("No encounters yet — connect and wait for a peer.",
                    fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(vertical = 4.dp))
            }
        } else {
            items(engineLog) { line ->
                val isDetail = line.contains("P(me") || line.contains("f(me") || line.contains("EPIDEMIC")
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = if (isDetail) Palette.Primary else Palette.TextPrimary,
                    modifier = Modifier.padding(vertical = 1.dp))
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            AnalyticsSectionHeader("Console", "${logEntries.size} lines")
        }
        items(logEntries) { entry ->
            Text(entry, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = Palette.TextSecondary, modifier = Modifier.padding(vertical = 1.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/**
 * Research-data export control. Wires the (previously UI-inaccessible) [DtnViewModel.exportData]
 * into the Log tab: one tap writes encounters/contacts/decisions/messages CSVs + q_tables/metadata
 * JSON via ResearchExporter/FileExportHelper, and the resulting path is shown once complete.
 */
@Composable
private fun ResearchExportCard(lastPath: String?, onExport: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("RESEARCH DATA EXPORT", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                color = Palette.Primary, letterSpacing = 0.8.sp)
            Text(
                "Writes encounters, contacts, forwarding decisions, messages (CSV) plus Q-tables " +
                    "and run metadata (JSON) to app storage.",
                fontSize = 11.sp, color = Palette.TextMuted,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Primary),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Export telemetry", fontWeight = FontWeight.SemiBold) }
            if (!lastPath.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Saved to", fontSize = 9.sp, color = Palette.TextMuted, letterSpacing = 0.6.sp)
                Text(lastPath, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = Palette.Accent)
            }
        }
    }
}
