package com.dtn.mesh.ui.groups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.crypto.GroupInvite
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnSpacing
import com.dtn.mesh.ui.theme.DtnType

/** Room the button takes, kept while the invite's status loads (no jump). */
private val ButtonRoom = DtnSpacing.MainButtonHeight

/**
 * A group invite DM in a chat (decision 10): the group's name and who it's from, and Join group.
 * Joining is only ever this tap ([DtnViewModel.joinGroupFromInvite], so today's conflict rule
 * applies: a different local group on the same channel slot is never overwritten). Once this phone
 * holds the group's key the card offers Open group instead. Our own sent invite shows who it went
 * to. Tap or long-press opens the message's status sheet, like a bubble.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JoinGroupCard(
    viewModel: DtnViewModel,
    invite: GroupInvite.Parsed,
    inviteText: String,
    outgoing: Boolean,
    peerName: String,
    maxWidth: Dp,
    onOpenGroupChat: (Int) -> Unit,
    onOpenStatus: () -> Unit,
) {
    val groups by viewModel.groups.collectAsState()
    // Re-checked whenever this phone's groups change (joined, left); no button until it's known.
    val check by produceState<InviteCheck?>(null, inviteText, groups) { value = viewModel.inviteStatus(inviteText) }
    Surface(
        shape = DtnShapes.Card,
        color = DtnColors.Card,
        border = BorderStroke(1.dp, if (outgoing) DtnColors.Primary.copy(alpha = 0.4f) else DtnColors.CardBorder),
        modifier = Modifier.widthIn(max = maxWidth).clip(DtnShapes.Card).combinedClickable(
            onClickLabel = "Message status",
            onLongClickLabel = "Message status",
            onClick = onOpenStatus,
            onLongClick = onOpenStatus,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupAvatar(invite.name, invite.channel, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text("Group invite", style = DtnType.Meta)
                    Text(invite.name, style = DtnType.RowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (outgoing) "Sent to $peerName · encrypted" else "From $peerName · end-to-end encrypted",
                        style = DtnType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val c = check
            when {
                c == null -> Spacer(Modifier.height(ButtonRoom))
                c.status == InviteStatus.MEMBER ->
                    OutlineButton("Open group", onClick = { onOpenGroupChat(invite.channel) }, modifier = Modifier.fillMaxWidth())
                outgoing -> Text("They join by tapping Join group on their phone.", style = DtnType.Meta)
                c.status == InviteStatus.INVALID -> Text("This invite is damaged.", color = DtnColors.RedText, fontSize = 13.sp)
                else -> {
                    if (c.status == InviteStatus.CONFLICT) {
                        Text(
                            "Your group “${c.otherGroup}” uses the same channel slot. Leave it first, or ask for a newly created group.",
                            color = DtnColors.OrangeText, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    PrimaryButton("Join group", onClick = { viewModel.joinGroupFromInvite(inviteText) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
