package com.dtn.mesh.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dtn.mesh.ui.ChatMessage
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.DtnSearchField
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.components.StatusChip
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.chats.ChatAvatar
import com.dtn.mesh.ui.chats.ChatKind
import com.dtn.mesh.ui.rememberQrActions
import com.dtn.mesh.ui.relativeSince
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType

/** Members seen in a group (distinct senders of its messages) and its last message time. */
internal data class GroupActivity(val membersSeen: List<String>, val lastMs: Long)

/** Per channel: who has posted (sender ids) and when the group last had a message. */
internal fun groupActivity(messages: List<ChatMessage>): Map<Int, GroupActivity> =
    messages.filter { it.toNodeId?.startsWith("grp:") == true }
        .groupBy { it.toNodeId!!.removePrefix("grp:").toIntOrNull() ?: -1 }
        .mapValues { (_, list) ->
            GroupActivity(
                membersSeen = list.filter { !it.isOutgoing && it.from.isNotBlank() }.map { it.from }.distinct(),
                lastMs = list.maxOf { it.createdAtMs },
            )
        }

/**
 * Groups (top bar Groups icon, Settings › Groups): search, one card per group (members seen,
 * active), and fixed at the bottom Create group, Scan invite QR and Upload invite.
 */
@Composable
fun GroupsPage(viewModel: DtnViewModel, onBack: () -> Unit, onOpenGroup: (Int) -> Unit, onCreate: () -> Unit) {
    val groups by viewModel.groups.collectAsState()
    val messages by viewModel.receivedMessages.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val activity = remember(messages) { groupActivity(messages) }
    val shown = remember(groups, query) {
        groups.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }.sortedBy { it.name.lowercase() }
    }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val qr = rememberQrActions(
        prompt = "Scan a group invite QR",
        onResult = { text -> viewModel.handleScannedQr(text) { key -> viewModel.openChat(key) } },
        onProblem = { viewModel.showMessage(it) },
    )

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            InnerTopBar("Groups", onBack = onBack, subtitle = "End-to-end encrypted with a shared group key") {
                IconButton(onClick = onCreate) { Icon(DtnIcons.Add, contentDescription = "Create group", tint = DtnColors.Primary) }
            }
        },
        bottomBar = {
            Surface(color = DtnColors.Background) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("＋ Create group", onClick = onCreate, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlineButton("Scan invite QR", onClick = qr.scan, modifier = Modifier.weight(1f))
                        OutlineButton(if (qr.decoding) "Reading…" else "Upload invite", onClick = qr.upload,
                            enabled = !qr.decoding, modifier = Modifier.weight(1f))
                    }
                    Text("Joining needs the group's invite: a QR, an image of it, or a Join card in a chat.",
                        style = DtnType.Meta, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item(key = "search") {
                Spacer(Modifier.height(4.dp))
                DtnSearchField(query, { query = it }, "Search groups")
                Spacer(Modifier.height(12.dp))
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        DtnIcons.Groups,
                        if (groups.isEmpty()) "No groups yet" else "No matching groups",
                        if (groups.isEmpty()) "Create one and invite contacts, or join with an invite QR."
                        else "Try another name.",
                    )
                }
            } else {
                item(key = "list") {
                    DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        shown.forEachIndexed { i, g ->
                            if (i > 0) HorizontalDivider(color = DtnColors.CardBorder)
                            val a = activity[g.channel]
                            val seen = a?.membersSeen?.size ?: 0
                            val active = a != null && System.currentTimeMillis() - a.lastMs < 24 * 3_600_000L
                            GroupRow(
                                name = g.name, channel = g.channel,
                                subtitle = "$seen seen · " + (a?.let { "last message ${relativeSince(it.lastMs)}" } ?: "no messages yet"),
                                active = active,
                                onClick = { onOpenGroup(g.channel) },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** Group avatar (lavender circle with the group icon). */
@Composable
internal fun GroupAvatar(name: String, channel: Int, size: androidx.compose.ui.unit.Dp = 44.dp) {
    ChatAvatar(ChatKind.GROUP, name, "grp:$channel", size = size)
}

/** A group card row: avatar, name, "3 seen · last message 2h ago", Active / Quiet, ›. */
@Composable
private fun GroupRow(name: String, channel: Int, subtitle: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClickLabel = "Open group", role = Role.Button, onClick = onClick)
            .heightIn(min = 68.dp).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatar(name, channel)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = DtnType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = DtnType.RowSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (active) StatusChip("Active", Tone.GREEN) else StatusChip("Quiet", Tone.GREY)
        Icon(DtnIcons.ChevronRight, contentDescription = null, tint = DtnColors.TextHint)
    }
}
