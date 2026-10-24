package com.dtn.mesh.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.RoutingExplanation
import com.dtn.mesh.ui.chats.tone
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.components.StatTile
import com.dtn.mesh.ui.components.StatusChip
import com.dtn.mesh.ui.components.StatusPill
import com.dtn.mesh.ui.components.color
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.delay

/** The live routing math: not worked out yet, or the explanation (null = none for this message). */
private sealed interface LiveMath {
    data object Pending : LiveMath
    data class Ready(val exp: RoutingExplanation?) : LiveMath
}

/**
 * Queued message (`queued/{id}`, replaces the old buffered-message dialog): urgency, TTL left,
 * time in the buffer, hops, forwards, why it's here, and — for a DM — the live routing math for
 * who should carry it next (re-scored every few seconds with the algorithm selected now). A
 * broadcast shows the phones it reached instead. "Message details" opens the Message page.
 */
@Composable
fun QueuedMessagePage(viewModel: DtnViewModel, msgId8: String, onBack: () -> Unit, onOpenMessage: (String) -> Unit) {
    val bundles by viewModel.queueBundles.collectAsState()
    val routes by viewModel.messageRoutes.collectAsState()
    val chats by viewModel.receivedMessages.collectAsState()
    val entries by viewModel.lifecycleEntries.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val strategy by viewModel.activeStrategy.collectAsState()
    val myId8 = localNodeId?.takeLast(8)
    val now by rememberNow(5_000L)

    val bundle = remember(bundles, msgId8) { QueueModel.byMessage(bundles.filter { it.msgId == msgId8 }).firstOrNull() }
    val route = routes.firstOrNull { it.msgId == msgId8 }
    val chat = chats.firstOrNull { it.msgId.take(8) == msgId8 }
    val events = remember(entries, msgId8) { entries.filter { it.msgId == msgId8 }.asReversed() }
    val queued = remember(bundle, route, chat, events, myId8) { bundle?.let { QueueModel.item(it, route, chat, events, myId8) } }
    val names = remember(peers, myId8) { NodeNames(peers, myId8) }

    val destFull = (queued as? DmRow)?.let { names.fullId(it.dest8) }
    val math by produceState<LiveMath>(LiveMath.Pending, destFull, strategy) {
        while (true) {
            value = LiveMath.Ready(destFull?.let { viewModel.explainRoute(it) })
            delay(3_000)
        }
    }

    Scaffold(
        containerColor = DtnColors.Background,
        topBar = { InnerTopBar("Queued message", onBack = onBack, subtitle = "MSG $msgId8") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val q = queued
            if (q == null) {
                item(key = "gone") {
                    EmptyState(
                        DtnIcons.Queue, "Not in the buffer any more",
                        if (route?.delivered == true) "It was delivered. Message details has its whole journey."
                        else "It was delivered, expired or cleared. Message details has its whole journey.",
                    )
                }
            } else {
                item(key = "header") { Header(q, names, groups.firstOrNull { it.channel == (q as? BroadcastRow)?.channel }?.name, now) }
                item(key = "tiles") { Tiles(q, now) }
                item(key = "why") {
                    SectionHeader("Why it's here")
                    DtnCard { Text(QueueText.whyHere(q, names::label), style = DtnType.RowSubtitle.copy(color = DtnColors.TextTitle)) }
                }
                when (q) {
                    is DmRow -> item(key = "math") {
                        val dest = names.label(q.dest8)
                        SectionHeader("Routing math")
                        DtnCard {
                            Text("Live: who should carry it next toward $dest, scored by the algorithm selected now ($strategy). " +
                                "It's worked out again every few seconds.", style = DtnType.Meta)
                            Spacer(Modifier.height(8.dp))
                            when (val m = math) {
                                LiveMath.Pending -> Unit
                                is LiveMath.Ready -> if (m.exp != null) {
                                    RoutingMathSection(exp = m.exp, destLabel = dest, prevHop = route?.prevHop, nextHop = q.nextHop8,
                                        initiallyExpanded = true)
                                } else {
                                    Text("No routing math for this message with $strategy.", style = DtnType.RowSubtitle)
                                }
                            }
                        }
                    }
                    is BroadcastRow -> item(key = "spread") {
                        val hubs = q.reached.count(names::isHub)
                        SectionHeader("Spreading")
                        DtnCard {
                            Text(QueueText.reached(q.reached.size - hubs, hubs), style = DtnType.RowTitle)
                            if (q.reached.isNotEmpty()) {
                                Text(q.reached.joinToString(", ") { names.label(it) }, style = DtnType.RowSubtitle)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("No routing math: broadcasts and group messages go to every phone this one meets, once per " +
                                "encounter, whatever the algorithm.", style = DtnType.Meta)
                        }
                    }
                }
            }
            item(key = "details") {
                DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow("Message details", icon = DtnIcons.Info, subtitle = "Route path, saved routing decision and the whole journey",
                        onClick = { onOpenMessage(msgId8) })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Urgency pill + status chip, who it's to / from and its text when it's readable here. */
@Composable
private fun Header(q: QueueItem, names: NodeNames, groupName: String?, now: Long) {
    val urgency = QueueText.urgency(QueueText.ttlMinutes(q.expiresAtMs, now))
    Column(Modifier.padding(top = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(urgency.label, urgency.tone)
            StatusChip(QueueText.chip(q.state, broadcast = q is BroadcastRow), q.state.tone())
        }
        Spacer(Modifier.height(10.dp))
        val (title, preview) = when (q) {
            is DmRow -> "To: ${names.withId(q.dest8)}" to QueueText.dmPreview(q, names::label)
            is BroadcastRow -> QueueText.broadcastTitle(q, groupName) to QueueText.broadcastPreview(q, groupName != null, names::label)
        }
        Text(title, style = DtnType.RowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(preview, style = DtnType.RowSubtitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text("From: ${if (q.own) "You" else names.withId(q.origin8)}", style = DtnType.Meta)
    }
}

/** TTL LEFT · IN BUFFER · HOPS · FORWARDS. */
@Composable
private fun Tiles(q: QueueItem, now: Long) {
    val urgency = QueueText.urgency(QueueText.ttlMinutes(q.expiresAtMs, now))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("TTL left", QueueText.ttl(q.expiresAtMs, now), urgency.tone.color(), Modifier.weight(1f))
            StatTile("In buffer", QueueText.span(q.sinceMs, now), DtnColors.Primary, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Hops", "${q.hops}", DtnColors.TextSubtitle, Modifier.weight(1f))
            StatTile("Forwards", "${q.forwards}", DtnColors.Purple, Modifier.weight(1f))
        }
    }
}
