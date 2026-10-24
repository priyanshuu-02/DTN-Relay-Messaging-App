package com.dtn.mesh.ui.devtools

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.receiver.hubLabel
import com.dtn.mesh.routing.RadioDistanceEstimator
import com.dtn.mesh.service.MessageLifecycleLog.RouteRecord
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.PeerMetric
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.chats.tone
import com.dtn.mesh.ui.components.Avatar
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.StatTile
import com.dtn.mesh.ui.components.StatusChip
import com.dtn.mesh.ui.components.color
import com.dtn.mesh.ui.deliveryState
import com.dtn.mesh.ui.queue.QueueText
import com.dtn.mesh.ui.relativeSince
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType

/** Route observations / engine lines shown before "Show all". */
private const val FIRST_ROUTES = 30
private const val FIRST_ENGINE_LINES = 80

/**
 * Routing Engine (`dev/engine`, the old Network tab's body): the network summary, the
 * active algorithm's score for every peer (with RSSI → distance), this phone's route observations
 * (tap → Message page) and the engine console (encounters and per-algorithm calculations, the
 * calculations in blue).
 */
@Composable
internal fun RoutingEnginePage(
    viewModel: DtnViewModel,
    onBack: () -> Unit,
    onOpenMessage: (String) -> Unit,
    onOpenContact: (String) -> Unit,
) {
    val peers by viewModel.peerList.collectAsState()
    val routes by viewModel.messageRoutes.collectAsState()
    val chats by viewModel.receivedMessages.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val metrics by viewModel.peerMetrics.collectAsState()
    val strategy by viewModel.activeStrategy.collectAsState()
    val buffered by viewModel.bufferedCount.collectAsState()
    val engineLog by viewModel.engineLog.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val myId8 = localNodeId?.takeLast(8)
    val summary = remember(routes, peers, myId8) { EngineSummary.of(routes, peers, myId8) }
    // Route rows show the same state (and colour) as the chat bubble and the Message page.
    val chatById = remember(chats) { chats.associateBy { it.msgId.take(8) } }
    val uniqueRoutes = remember(routes) { routes.distinctBy { it.msgId } }
    var allRoutes by rememberSaveable { mutableStateOf(false) }
    var allEngine by rememberSaveable { mutableStateOf(false) }

    DevPageFrame("Routing Engine", onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") { SummaryCard(summary, buffered, myId8, onRefresh = { viewModel.refreshPeerStatus() }) }

            item(key = "scores") {
                SectionHeader("Per-peer scores")
                ScoreTable(peers, metrics, strategy, onOpenContact)
            }

            item(key = "routes-head") {
                SectionHeader("Route observations · ${summary.resolved} resolved · ${summary.inTransit} in transit")
                Text("As this phone saw them. Tap one for the message's full trace.", style = DtnType.Meta,
                    modifier = Modifier.padding(start = 4.dp))
            }
            if (uniqueRoutes.isEmpty()) {
                item(key = "routes-empty") { EmptyState(DtnIcons.Network, "No route observations yet", "Send a message to start tracking.") }
            } else {
                val shownRoutes = if (allRoutes) uniqueRoutes else uniqueRoutes.take(FIRST_ROUTES)
                // "route-" keys: a message id can also appear in other sections of a list.
                items(shownRoutes, key = { "route-${it.msgId}" }) { r ->
                    val state = chatById[r.msgId]?.state ?: deliveryState(null, null, r.isBroadcast, r)
                    val groupName = if (r.isBroadcast && r.channel > 0) {
                        groups.firstOrNull { it.channel == r.channel }?.name?.let { "Group · $it" } ?: "Group"
                    } else null
                    RouteCard(r, peers, myId8, state, groupName, onOpen = { onOpenMessage(r.msgId) })
                }
                if (uniqueRoutes.size > FIRST_ROUTES) {
                    item(key = "routes-more") { ShowAllToggle(allRoutes, uniqueRoutes.size) { allRoutes = !allRoutes } }
                }
            }

            item(key = "engine-head") {
                SectionHeader("Encounters & calculations · ${engineLog.size}")
                Text("Encounters, routing-table updates and each algorithm's calculations (in blue), newest first.",
                    style = DtnType.Meta, modifier = Modifier.padding(start = 4.dp))
            }
            if (engineLog.isEmpty()) {
                item(key = "engine-empty") {
                    DtnCard { Text("No encounters yet — connect and wait for a peer.", style = DtnType.RowSubtitle) }
                }
            } else {
                val lines = if (allEngine) engineLog else engineLog.take(FIRST_ENGINE_LINES)
                item(key = "engine-lines") {
                    DtnCard(contentPadding = PaddingValues(12.dp)) {
                        lines.forEach { line ->
                            Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
                                color = if (isCalculationLine(line)) DtnColors.Primary else DtnColors.TextTitle,
                                modifier = Modifier.padding(vertical = 1.dp))
                        }
                    }
                }
                if (engineLog.size > FIRST_ENGINE_LINES) {
                    item(key = "engine-more") { ShowAllToggle(allEngine, engineLog.size) { allEngine = !allEngine } }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** "This node · id" with Refresh, the 4 tiles (sent ✓, relayed ✓, received, buffered) and the active peers. */
@Composable
private fun SummaryCard(s: EngineSummary, buffered: Int, myId8: String?, onRefresh: () -> Unit) {
    DtnCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Network summary", style = DtnType.RowTitle)
                Text("This node · ${myId8 ?: "—"} · ${s.activePeers} of ${s.knownPeers} peers active", style = DtnType.Meta)
            }
            IconButton(onClick = onRefresh) { Icon(DtnIcons.Refresh, contentDescription = "Refresh peer status", tint = DtnColors.Primary) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Sent ✓", "${s.sentDelivered}", DtnColors.Green, Modifier.weight(1f), sub = "my messages")
            StatTile("Relayed ✓", "${s.relayedDelivered}", DtnColors.Purple, Modifier.weight(1f), sub = "for others")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Received", "${s.received}", DtnColors.Primary, Modifier.weight(1f), sub = "for me")
            StatTile("Buffered", "$buffered", if (buffered > 0) DtnColors.Orange else DtnColors.TextSubtitle, Modifier.weight(1f),
                sub = "DMs waiting")
        }
    }
}

/** The active algorithm's value for every peer (P / f / w / Q, in its colour), RSSI and distance. A phone's row opens its contact page. */
@Composable
private fun ScoreTable(peers: List<PeerInfo>, metrics: Map<String, PeerMetric>, strategy: String, onOpenContact: (String) -> Unit) {
    val algoColor = DtnColors.algorithm(strategy)
    DtnCard(contentPadding = PaddingValues(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = DtnShapes.Pill, color = algoColor.copy(alpha = 0.12f)) {
                Text(Algorithms.label(strategy), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = algoColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(Algorithms.scoreCaption(strategy), style = DtnType.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
        if (peers.isEmpty()) {
            Text("No peers seen yet. Connect to start discovery.", style = DtnType.RowSubtitle, modifier = Modifier.padding(12.dp))
            return@DtnCard
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("PEER", style = DtnType.Meta, modifier = Modifier.weight(1f))
            Text("SCORE", style = DtnType.Meta, textAlign = TextAlign.End, modifier = Modifier.width(84.dp))
            Text("RSSI · DIST", style = DtnType.Meta, textAlign = TextAlign.End, modifier = Modifier.width(92.dp))
        }
        HorizontalDivider(color = DtnColors.CardBorder)
        peers.forEach { p -> ScoreRow(p, metrics[p.nodeId], algoColor, onOpen = if (p.isHub) null else ({ onOpenContact(p.nodeId) })) }
    }
}

@Composable
private fun ScoreRow(peer: PeerInfo, metric: PeerMetric?, algoColor: androidx.compose.ui.graphics.Color, onOpen: (() -> Unit)?) {
    val name = if (peer.isHub) hubLabel(peer.nodeId) else peer.displayName()
    val rssi = peer.lastRssi.takeIf { it > -200 && it != 0 }
    // The distance is a BLE estimate (hub signal values are placeholders).
    val meters = rssi?.takeIf { !peer.isHub && !peer.hubOnly }?.let { RadioDistanceEstimator.estimateMeters(it) }?.takeIf { it > 0 }
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open the contact page of $name", onClick = onOpen) else Modifier)
            .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (peer.isHub) Avatar("", DtnColors.Grey, size = 36.dp, icon = DtnIcons.LoraHub)
        else Avatar(UiText.avatarInitial(name), DtnColors.avatarFor(peer.nodeId), size = 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text("${peer.nodeId.takeLast(8)} · ${EngineSummary.reach(peer)}", style = DtnType.Meta, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Text("${metric?.label ?: "P"} ${metric?.value ?: "—"}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = algoColor,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(84.dp))
        Column(Modifier.width(92.dp), horizontalAlignment = Alignment.End) {
            Text(rssi?.let { "$it dBm" } ?: "—", style = DtnType.Mono, maxLines = 1)
            if (meters != null) Text("≈ ${"%.1f".format(meters)} m", style = DtnType.Meta, maxLines = 1)
        }
    }
}

/** One route observation: MSG id + status chip, from → to, the path this phone saw, the link and when. */
@Composable
private fun RouteCard(r: RouteRecord, peers: List<PeerInfo>, myId8: String?, state: DeliveryState, groupName: String?, onOpen: () -> Unit) {
    val from = peerLabelFor(r.source, peers, myId8) ?: r.source
    val to = groupName ?: peerLabelFor(r.destination, peers, myId8) ?: r.destination
    StripeCard(stripe = state.tone().color(), onClick = onOpen, onClickLabel = "Show message details") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("MSG ${r.msgId}", style = DtnType.Mono, modifier = Modifier.weight(1f))
                    StatusChip(QueueText.chip(state, broadcast = r.isBroadcast), state.tone())
                }
                Spacer(Modifier.height(4.dp))
                Text("$from → $to", style = DtnType.RowTitle.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatObservedPath(r, peers, myId8), style = DtnType.Mono.copy(color = DtnColors.Primary), maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(r.lastPath.ifBlank { null }?.let { "via $it" }, r.strategy.ifBlank { null },
                        "updated ${relativeSince(r.timestampMs)}").joinToString(" · "),
                    style = DtnType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(DtnIcons.ChevronRight, contentDescription = null, tint = DtnColors.TextHint)
        }
    }
}

@Composable
private fun ShowAllToggle(showingAll: Boolean, total: Int, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(if (showingAll) "Show fewer" else "Show all $total")
    }
}
