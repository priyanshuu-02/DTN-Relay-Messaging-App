package com.dtn.mesh.ui.groups

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.QrImage
import com.dtn.mesh.ui.QrShare
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.chats.senderNames
import com.dtn.mesh.ui.components.ConfirmDialog
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.NoteKind
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.launch

/**
 * One group (`group/{channel}`, replaces the group info dialog): its invite QR with Share, Open
 * chat, the channel, the members seen so far, and Leave group (asks first).
 */
@Composable
fun GroupPage(viewModel: DtnViewModel, channel: Int, onBack: () -> Unit, onOpenChat: (Int) -> Unit, onLeft: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by viewModel.groups.collectAsState()
    val messages by viewModel.receivedMessages.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val group = groups.firstOrNull { it.channel == channel }
    var invite by remember { mutableStateOf<String?>(null) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    LaunchedEffect(channel, group?.name) { viewModel.groupInvite(channel) { invite = it } }
    val nameOf = remember(peers) { senderNames(peers) }
    val seen = remember(messages, peers) { groupActivity(messages)[channel]?.membersSeen.orEmpty().map(nameOf) }

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { InnerTopBar(group?.name ?: "Group", onBack = onBack, subtitle = "Encrypted group · channel $channel") },
    ) { padding ->
        if (group == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(DtnIcons.Groups, "Not in this group", "Its key isn't on this phone. Rejoin with an invite.")
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DtnCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GroupAvatar(group.name, channel, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = DtnType.RowTitle.copy(fontSize = 18.sp))
                        Text("Invite QR · anyone holding it can read and post", style = DtnType.Meta)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val inv = invite
                    if (inv != null) QrImage(inv, size = 220.dp)
                    else Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                Spacer(Modifier.height(12.dp))
                OutlineButton("Share invite", onClick = {
                    val inv = invite ?: return@OutlineButton
                    scope.launch {
                        if (!QrShare.shareAsync(context, inv, "Share group invite")) viewModel.showMessage("Couldn't share the invite")
                    }
                }, enabled = invite != null, modifier = Modifier.fillMaxWidth())
            }

            PrimaryButton("Open chat", onClick = { onOpenChat(channel) }, modifier = Modifier.fillMaxWidth())

            SectionHeader("Group")
            DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                InfoRow("Channel", "$channel")
                HorizontalDivider(color = DtnColors.CardBorder, modifier = Modifier.padding(horizontal = 12.dp))
                InfoRow("Members seen", if (seen.isEmpty()) "Only you so far" else "You, " + seen.joinToString(", "))
                Text("There's no server, so membership is whoever holds the invite. Members appear here once they post.",
                    style = DtnType.Meta, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            }

            DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(onClickLabel = "Leave group", role = Role.Button) { confirmLeave = true }
                        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(DtnIcons.Leave, contentDescription = null, tint = DtnColors.RedText)
                    Spacer(Modifier.width(12.dp))
                    Text("Leave group", color = DtnColors.RedText, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
            InfoNote("Messages in this group are end-to-end encrypted with the group key. Hubs and phones that carry " +
                "them can't read them.", kind = NoteKind.INFO)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmLeave && group != null) {
        ConfirmDialog(
            title = "Leave “${group.name}”?",
            text = "This phone forgets the group key and stops receiving its messages. Chat history stays here. " +
                "You can rejoin later with an invite.",
            confirmLabel = "Leave",
            onConfirm = {
                confirmLeave = false
                viewModel.leaveGroup(channel)
                onLeft()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = DtnType.RowSubtitle, modifier = Modifier.width(120.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle, modifier = Modifier.weight(1f))
    }
}
