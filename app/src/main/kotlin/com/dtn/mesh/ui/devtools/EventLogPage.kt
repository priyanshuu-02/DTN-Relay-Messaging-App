package com.dtn.mesh.ui.devtools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.service.LifecycleEntry
import com.dtn.mesh.service.LifecycleEvent
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.ChipRow
import com.dtn.mesh.ui.components.DtnSearchField
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.color
import com.dtn.mesh.ui.components.soft
import com.dtn.mesh.ui.queue.MessageTrace
import com.dtn.mesh.ui.queue.shareFile
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.launch

/**
 * Event Log (`dev/events`): every journal event of every message this phone sent, carried or
 * received, newest first. Search (message id, node id, event or detail), the All / Flow /
 * Delivery / ACK / Issues chips, cards with a coloured stripe per event type (tap → Message page)
 * and Export events (the filtered list as a text file for the share sheet).
 */
@Composable
internal fun EventLogPage(viewModel: DtnViewModel, onBack: () -> Unit, onOpenMessage: (String) -> Unit) {
    val entries by viewModel.lifecycleEntries.collectAsState()
    var filter by rememberSaveable { mutableStateOf(LifecycleFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(entries, filter, query) { EventLogModel.visible(entries, filter, query) }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var exporting by remember { mutableStateOf(false) }

    DevPageFrame(
        "Event Log", onBack, snackbars, subtitle = "Advanced diagnostic screen",
        bottomBar = {
            DevBottomBar {
                OutlineButton(
                    if (exporting) "Exporting…" else "Export events (${shown.size})",
                    enabled = !exporting && shown.isNotEmpty(),
                    onClick = {
                        exporting = true
                        scope.launch {
                            val text = EventLogModel.exportText(shown, filter, query, UiText.fullTime(System.currentTimeMillis()))
                            val file = viewModel.writeEventLogFile(text, shown.size)
                            exporting = false
                            if (file == null || !shareFile(context, file, "DTN Event Log · ${shown.size} events", chooserTitle = "Share events")) {
                                snackbars.showSnackbar("Couldn't export the events")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "note") {
                Spacer(Modifier.height(4.dp))
                InfoNote("Every event of the messages this phone sent, carried or received, newest first. Tap one for that " +
                    "message's full trace. Announces and receipts that aren't about these messages are in the Console.")
            }
            item(key = "search") { DtnSearchField(query, { query = it }, "Message ID, node ID or event") }
            item(key = "chips") {
                ChipRow(LifecycleFilter.entries.map { it.label }, filter.ordinal, { filter = LifecycleFilter.entries[it] })
                Text(
                    if (shown.size == entries.size) "${entries.size} events" else "${shown.size} of ${entries.size} events",
                    style = DtnType.Meta, modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    if (entries.isEmpty()) EmptyState(DtnIcons.EventLog, "No events yet", "Send a message: its journey is logged here step by step.")
                    else EmptyState(DtnIcons.Search, "No matching events", "Try another filter, or search a message or node id.")
                }
            } else {
                // The index keeps keys unique even for two events with the same time, message and type.
                itemsIndexed(shown, key = { i, e -> "$i-${e.epochMs}-${e.msgId}-${e.event}" }) { _, e ->
                    EventCard(e, onOpen = { onOpenMessage(e.msgId) })
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/** Stripe + icon circle in the event's colour, the title, time, "MSG …", "FROM … → TO …", the detail and ›. */
@Composable
private fun EventCard(e: LifecycleEntry, onOpen: () -> Unit) {
    val tone = EventLogModel.tone(e)
    StripeCard(stripe = tone.color(), onClick = onOpen, onClickLabel = "Show message details") {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(tone.soft()), contentAlignment = Alignment.Center) {
                Icon(eventIcon(e.event), contentDescription = null, tint = tone.color(), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(MessageTrace.title(e.event), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tone.color(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(e.timestamp, style = DtnType.Meta, maxLines = 1)
                }
                Text("MSG ${e.msgId}", style = DtnType.Mono)
                EventLogModel.endpoints(e)?.let { Text(it, style = DtnType.Mono.copy(color = DtnColors.TextSubtitle)) }
                val detail = EventLogModel.detail(e)
                if (detail.isNotBlank()) {
                    Text(detail, style = DtnType.Meta, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Icon(DtnIcons.ChevronRight, contentDescription = null, tint = DtnColors.TextHint,
                modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

private fun eventIcon(event: LifecycleEvent): ImageVector = when (event) {
    LifecycleEvent.CREATED -> DtnIcons.Edit
    LifecycleEvent.BUFFERED -> DtnIcons.Storage
    LifecycleEvent.FORWARDED -> DtnIcons.Send
    LifecycleEvent.DELIVERED, LifecycleEvent.ACK_GENERATED, LifecycleEvent.ACK_RECEIVED, LifecycleEvent.BUFFER_CLEARED -> DtnIcons.Check
    LifecycleEvent.DUPLICATE -> DtnIcons.Copy
    LifecycleEvent.IGNORED -> DtnIcons.Block
    LifecycleEvent.EXPIRED -> DtnIcons.Schedule
    LifecycleEvent.HOP_LIMIT -> DtnIcons.Warning
    LifecycleEvent.DROPPED -> DtnIcons.Delete
}
