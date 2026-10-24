package com.dtn.mesh.ui.groups

import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.crypto.GroupInvite
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.Avatar
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.DtnSearchField
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.peers.PeerLists
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType

/**
 * Create group (`groups/new`, decision 10): the name (capped in bytes so the invite fits one
 * encrypted DM in one LoRa frame; the counter shows the limit) and optionally members: contacts
 * whose keys this phone holds. Each picked contact gets the invite as an ordinary encrypted DM and
 * a Join group card; nobody is added automatically. [onCreated] gets the new channel.
 */
@Composable
fun CreateGroupPage(viewModel: DtnViewModel, onBack: () -> Unit, onCreated: (Int) -> Unit) {
    val peers by viewModel.peerList.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    var creating by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    // Only contacts whose keys we hold can get an encrypted invite (never hubs or blocked ones).
    val candidates = remember(peers, query) { PeerLists.contacts(peers, query).filter { it.hasKeys } }
    val withoutKeys = remember(peers) { PeerLists.contacts(peers, "").count { !it.hasKeys } }

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { InnerTopBar("Create group", onBack = onBack, subtitle = "Groups") },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(DtnColors.PurpleSoft), contentAlignment = Alignment.Center) {
                    Icon(DtnIcons.Groups, contentDescription = null, tint = DtnColors.Purple, modifier = Modifier.size(48.dp))
                }
                Box(Modifier.size(96.dp), contentAlignment = Alignment.BottomEnd) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(DtnColors.Primary), contentAlignment = Alignment.Center) {
                        Icon(DtnIcons.Add, contentDescription = null, tint = DtnColors.Card, modifier = Modifier.size(20.dp))
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = GroupInvite.truncateUtf8(it.replace('\n', ' '), GroupInvite.MAX_NAME_BYTES) },
                singleLine = true,
                label = { Text("Group name") },
                supportingText = {
                    Text("${GroupInviteDm.nameCounter(name)} · the invite has to fit one LoRa frame", style = DtnType.Meta)
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                shape = DtnShapes.Field,
                modifier = Modifier.fillMaxWidth(),
            )

            DtnCard(contentPadding = PaddingValues(top = 12.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Add members (optional)", style = DtnType.RowTitle, modifier = Modifier.weight(1f))
                    Text("Selected (${selected.size})", color = DtnColors.Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                DtnSearchField(query, { query = it }, "Search contacts", modifier = Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(4.dp))
                if (candidates.isEmpty()) {
                    Text(
                        if (query.isNotBlank()) "No matching contacts with keys."
                        else "No contacts with keys yet. You can still create the group and share its invite QR.",
                        style = DtnType.RowSubtitle, modifier = Modifier.padding(16.dp),
                    )
                }
                candidates.forEach { p ->
                    HorizontalDivider(color = DtnColors.CardBorder)
                    MemberRow(p, checked = p.nodeId in selected) { on ->
                        selected = ArrayList(if (on) selected + p.nodeId else selected - p.nodeId)
                    }
                }
                if (withoutKeys > 0) {
                    Text("$withoutKeys contact${if (withoutKeys == 1) "" else "s"} without keys can't be invited by message: " +
                        "share the QR with them.", style = DtnType.Meta, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
                }
            }

            InfoNote(
                "Each selected contact gets the invite as an end-to-end encrypted message and joins by tapping Join group; " +
                    "nobody is added automatically. With no one selected, share the invite QR from the group's page.",
            )
            PrimaryButton(
                if (creating) "Creating…" else "Create group",
                onClick = {
                    creating = true
                    viewModel.createGroupWithInvites(name, selected.toList()) { channel ->
                        creating = false
                        if (channel != null) onCreated(channel)
                    }
                },
                enabled = name.isNotBlank() && !creating,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A contact with a checkbox; the whole row toggles. */
@Composable
private fun MemberRow(peer: PeerInfo, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(UiText.avatarInitial(peer.displayName()), DtnColors.avatarFor(peer.nodeId), size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.displayName(), style = DtnType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (peer.keyVerified) "Verified · ${peer.nodeId.takeLast(8)}" else peer.nodeId.takeLast(8), style = DtnType.Meta)
        }
        Checkbox(checked = checked, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = DtnColors.Primary))
    }
}
