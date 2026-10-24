package com.dtn.mesh.ui.chats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dtn.mesh.service.MessageLifecycleLog
import com.dtn.mesh.ui.ChatMessage
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.components.StatusPill
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType

/** Status colour family of a delivery state. */
internal fun DeliveryState.tone(): Tone = when (this) {
    DeliveryState.DELIVERED, DeliveryState.RECEIVED, DeliveryState.RECEIVED_BY_ALL -> Tone.GREEN
    DeliveryState.IN_TRANSIT, DeliveryState.CARRYING, DeliveryState.RECEIVED_BY_SOME -> Tone.BLUE
    DeliveryState.SPREADING -> Tone.PURPLE
    DeliveryState.QUEUED -> Tone.ORANGE
    DeliveryState.EXPIRED, DeliveryState.DROPPED -> Tone.RED
}

/**
 * The simple status sheet (decision 9, long-press a bubble): the status, when it was sent /
 * delivered (or received), how it travelled ("Delivered via BLE", "Received via LoRa hub (hub
 * A9B0)", "Sent via Internet relay (fallback after BLE)"), for our group messages each known
 * member with ✓ + time + link or "Not yet", Copy text, and "Full trace in Developer Tools ›".
 *
 * @param memberName an 8-character member id → name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageStatusSheet(
    msg: ChatMessage,
    route: MessageLifecycleLog.RouteRecord?,
    onCopy: () -> Unit,
    onFullTrace: () -> Unit,
    onDismiss: () -> Unit,
    memberName: (String) -> String = { it },
) {
    val state = msg.state
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DtnShapes.Sheet,
        containerColor = DtnColors.Background,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Message status", style = DtnType.PageTitle)
            DtnCard {
                StatusPill(listOf(state.short, state.tick).filter { it.isNotBlank() }.joinToString(" "), state.tone())
                Spacer(Modifier.height(8.dp))
                Text(state.label, style = DtnType.RowSubtitle)
                Spacer(Modifier.height(12.dp))
                val sentAt = msg.createdAtMs
                if (sentAt > 0) StatusLine(if (msg.isOutgoing) "Sent" else "Received", UiText.fullTime(sentAt))
                val deliveredAt = route?.deliveredAtMs ?: 0L
                if (msg.isOutgoing && state == DeliveryState.DELIVERED && deliveredAt > 0) {
                    StatusLine("Delivered", UiText.fullTime(deliveredAt) +
                        if (sentAt in 1..deliveredAt) " · took ${UiText.duration(sentAt, deliveredAt)}" else "")
                }
                TravelText.travelLine(msg.isOutgoing, state, route)?.let { StatusLine("How it travelled", it) }
                msg.group?.let { StatusLine("Received by", TravelText.progressLine(it)) }
            }
            // Our group message: who has it (a member's phone sends a receipt the first time it reads it).
            if (msg.group != null) {
                DtnCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Members", style = DtnType.RowTitle)
                    Spacer(Modifier.height(4.dp))
                    if (msg.members.isEmpty()) {
                        Text("No members known yet. They appear once they post in the group or their phone sends a receipt.",
                            style = DtnType.RowSubtitle)
                    }
                    msg.members.forEach { m ->
                        StatusLine(memberName(m.member8), TravelText.memberLine(m) { UiText.listTime(it) },
                            color = if (m.received) DtnColors.GreenText else DtnColors.TextSubtitle)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlineButton("Copy text", onClick = onCopy, modifier = Modifier.weight(1f))
            }
            DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow("Full trace in Developer Tools", icon = DtnIcons.DevTools,
                    subtitle = "Route path, routing decision and the whole journey",
                    iconTint = DtnColors.TextTitle, iconBackground = DtnColors.GreySoft, onClick = onFullTrace)
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String, color: Color = DtnColors.TextTitle) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = DtnType.RowSubtitle, modifier = Modifier.width(120.dp))
        Text(value, style = DtnType.RowSubtitle, color = color, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f))
    }
}
