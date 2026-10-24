package com.dtn.mesh.ui.peers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.components.ConfirmDialog
import com.dtn.mesh.ui.components.MenuAction
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType

/** Rename / Delete contact / Block, each behind its own dialog (Delete and Block ask to confirm). */
@Stable
class ContactActions internal constructor(private val open: MutableState<Pending?>) {
    internal sealed interface Pending {
        val peer: PeerInfo
        data class Rename(override val peer: PeerInfo) : Pending
        data class Delete(override val peer: PeerInfo) : Pending
        data class Block(override val peer: PeerInfo) : Pending
    }

    fun rename(peer: PeerInfo) { open.value = Pending.Rename(peer) }
    fun delete(peer: PeerInfo) { open.value = Pending.Delete(peer) }
    fun block(peer: PeerInfo) { open.value = Pending.Block(peer) }

    /**
     * The ⋮ menu of a contact / device row: Open chat · Info · Rename · Delete… · Block… (blocked
     * contacts get Unblock instead).
     */
    fun menu(peer: PeerInfo, onOpenChat: (String) -> Unit, onInfo: (String) -> Unit, onUnblock: (String) -> Unit): List<MenuAction> =
        buildList {
            if (!peer.blocked) add(MenuAction("Open chat") { onOpenChat(peer.nodeId) })
            add(MenuAction("Info") { onInfo(peer.nodeId) })
            add(MenuAction("Rename") { rename(peer) })
            add(MenuAction("Delete…", destructive = true) { delete(peer) })
            if (peer.blocked) add(MenuAction("Unblock") { onUnblock(peer.nodeId) })
            else add(MenuAction("Block…", destructive = true) { block(peer) })
        }
}

/**
 * Hosts the Rename / Delete / Block dialogs for one screen. [onRemoved] runs after a confirmed
 * Delete or Block (e.g. to leave a chat that just went away).
 */
@Composable
fun rememberContactActions(viewModel: DtnViewModel, onRemoved: (String) -> Unit = {}): ContactActions {
    val open = remember { mutableStateOf<ContactActions.Pending?>(null) }
    val removed by rememberUpdatedState(onRemoved)
    when (val p = open.value) {
        is ContactActions.Pending.Rename -> RenameContactDialog(
            peer = p.peer,
            onDismiss = { open.value = null },
            onSave = { name -> viewModel.renamePeer(p.peer.nodeId, name); open.value = null },
        )
        is ContactActions.Pending.Delete -> ConfirmDialog(
            title = "Delete ${p.peer.displayName()}?",
            text = "Your chat with them is deleted from this phone and they leave your chats. Your phone keeps " +
                "relaying messages for them. If they message you later, they're added back. You can also add " +
                "them again by QR or Add.",
            confirmLabel = "Delete",
            onConfirm = { viewModel.deleteContact(p.peer.nodeId); open.value = null; removed(p.peer.nodeId) },
            onDismiss = { open.value = null },
        )
        is ContactActions.Pending.Block -> ConfirmDialog(
            title = "Block ${p.peer.displayName()}?",
            text = "You won't see their messages or group posts, and they no longer get your keys. They still get " +
                "delivery receipts, so their phone shows Delivered. Your phone keeps relaying their messages " +
                "for others. Unblock them any time on their contact page or in Settings › Privacy.",
            confirmLabel = "Block",
            onConfirm = { viewModel.setBlocked(p.peer.nodeId, true); open.value = null; removed(p.peer.nodeId) },
            onDismiss = { open.value = null },
        )
        null -> Unit
    }
    return remember(open) { ContactActions(open) }
}

/** Nickname for a contact; blank goes back to the name from their announce / QR. */
@Composable
fun RenameContactDialog(peer: PeerInfo, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(peer.customName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename contact") },
        text = {
            Column {
                Text(peer.nodeId, style = DtnType.Mono.copy(color = DtnColors.TextSubtitle))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(48) },
                    singleLine = true,
                    label = { Text("Nickname") },
                    placeholder = { Text(peer.announceName ?: "Leave blank to use their own name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = DtnShapes.Field,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save", color = DtnColors.Primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
