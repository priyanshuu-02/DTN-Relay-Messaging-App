package com.dtn.mesh.ui.queue

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.chats.TravelText
import com.dtn.mesh.ui.chats.tone
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.StatusPill
import com.dtn.mesh.ui.components.TimelineItem
import com.dtn.mesh.ui.components.color
import com.dtn.mesh.ui.findActivity
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.launch
import java.io.File

/**
 * The Message page (`message/{id}`, the full trace; replaces the old Message info dialog). Every
 * section is expanded: status + explanation, the text (own and received messages), the route path
 * hop by hop as seen from this phone, the routing decision SAVED when it was handed on (never
 * recomputed), the journey timeline, details with Copy all, and Export event details. It updates
 * live, e.g. it flips to Delivered when the receipt arrives while it's open.
 *
 * @param msgId8 the first 8 characters of the message id (the key shared by chat rows and the journal).
 */
@Composable
fun MessagePage(viewModel: DtnViewModel, msgId8: String, onBack: () -> Unit) {
    val routes by viewModel.messageRoutes.collectAsState()
    val chats by viewModel.receivedMessages.collectAsState()
    val entries by viewModel.lifecycleEntries.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val strategy by viewModel.activeStrategy.collectAsState()
    val myId8 = localNodeId?.takeLast(8)

    val route = routes.firstOrNull { it.msgId == msgId8 }
    val chat = chats.firstOrNull { it.msgId.take(8) == msgId8 }
    // Journal entries are newest-first; the page reads oldest-first.
    val events = remember(entries, msgId8) { entries.filter { it.msgId == msgId8 }.asReversed() }
    val names = remember(peers, myId8) { NodeNames(peers, myId8) }
    val channel = chat?.toNodeId?.takeIf { it.startsWith("grp:") }?.removePrefix("grp:")?.toIntOrNull()
        ?: route?.takeIf { it.isBroadcast && it.channel > 0 }?.channel
    val groupName = channel?.let { ch -> groups.firstOrNull { it.channel == ch }?.name ?: "channel $ch" }
    val model = remember(msgId8, route, chat, events, names, groupName, strategy) {
        MessageTrace.build(msgId8, route, chat, events, names, groupName, strategy)
    }

    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var exporting by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { InnerTopBar("Message details", onBack = onBack, subtitle = "MSG $msgId8") },
    ) { padding ->
        if (!model.known) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(DtnIcons.Queue, "No journey recorded",
                    "This phone has nothing on MSG $msgId8: it was sent before this version of the app, or it's older than the journal keeps.")
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "note") {
                Spacer(Modifier.height(4.dp))
                InfoNote("Forwarding is not final delivery: a message is Delivered only when the recipient's phone got it over a " +
                    "direct link, or its delivery receipt came back.")
            }
            item(key = "status") { StatusCard(model) }
            item(key = "text") {
                SectionHeader(if (model.text != null) "Text" else "Content")
                DtnCard {
                    if (model.text != null) {
                        val out = chat?.isOutgoing == true
                        Surface(shape = DtnShapes.Button, color = if (out) DtnColors.PrimarySoft else DtnColors.BubbleIn, modifier = Modifier.fillMaxWidth()) {
                            Text(model.text, fontSize = 15.sp, lineHeight = 20.sp, color = DtnColors.TextTitle,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    } else {
                        Text(model.contentNote.orEmpty(), style = DtnType.RowSubtitle)
                    }
                }
            }
            item(key = "path") {
                SectionHeader("Route path")
                DtnCard { RouteCard(model) }
            }
            // Our group message: which known members' phones sent a group receipt.
            model.groupProgress?.let { progress ->
                item(key = "members") {
                    SectionHeader("Received by · ${TravelText.progressLine(progress)}")
                    DtnCard { MembersCard(model, names) }
                }
            }
            item(key = "decision") {
                SectionHeader("Routing decision")
                DtnCard { DecisionCard(model.decision) }
            }
            item(key = "journey") {
                SectionHeader("Journey")
                DtnCard {
                    if (model.journey.isEmpty()) {
                        Text("No journal events kept for this message.", style = DtnType.RowSubtitle)
                    } else {
                        model.journey.forEachIndexed { i, s ->
                            TimelineItem(title = s.title, time = s.time, color = s.tone.color(), isLast = i == model.journey.lastIndex,
                                detail = s.detail.ifBlank { null }, pill = s.pill, pillTone = s.pillTone)
                        }
                    }
                }
            }
            item(key = "details") {
                DtnCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Details", style = DtnType.RowTitle, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(MessageTrace.copyAllText(model)))
                            // Android 13+ confirms clipboard writes itself.
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                                scope.launch { snackbars.showSnackbar("Copied") }
                            }
                        }) {
                            Icon(DtnIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy all")
                        }
                    }
                    model.details.forEach { DetailLine(it.label, it.value, it.mono) }
                }
            }
            item(key = "export") {
                OutlineButton(
                    if (exporting) "Exporting…" else "Export event details",
                    enabled = !exporting,
                    onClick = {
                        exporting = true
                        scope.launch {
                            val text = MessageTrace.exportText(model, UiText.fullTime(System.currentTimeMillis()))
                            val file = viewModel.writeMessageEventsFile(msgId8, text)
                            exporting = false
                            if (file == null || !shareFile(context, file, "DTN message $msgId8 · event details")) {
                                snackbars.showSnackbar("Couldn't export the event details")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("A text file with this message's journal events, ids, links and the saved decision — never its text.",
                    style = DtnType.Meta, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Each known member of the group: ✓ + time + the link the receipt came over, or "Not yet". */
@Composable
private fun MembersCard(model: MessageTraceModel, names: NodeNames) {
    if (model.members.isEmpty()) {
        Text("No members known yet. A member's phone sends a group receipt the first time it reads the message; " +
            "members also become known when they post in the group or were invited from this phone.", style = DtnType.RowSubtitle)
        return
    }
    model.members.forEachIndexed { i, m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(names.label(m.member8), style = DtnType.RowTitle, maxLines = 1, modifier = Modifier.width(132.dp))
            Text(MessageTrace.memberLine(m) { UiText.fullTime(it) }, style = DtnType.RowSubtitle,
                color = if (m.received) DtnColors.GreenText else DtnColors.TextSubtitle, modifier = Modifier.weight(1f))
        }
        if (i < model.members.lastIndex) Spacer(Modifier.height(2.dp))
    }
    Spacer(Modifier.height(6.dp))
    Text("Group messages keep spreading until their lifetime ends: a receipt never removes them from any buffer.",
        style = DtnType.Meta)
}

/** The big status pill and what it means. */
@Composable
private fun StatusCard(model: MessageTraceModel) {
    DtnCard {
        val state = model.state
        StatusPill(listOf(state.pill, state.tick).filter { it.isNotBlank() }.joinToString(" "), state.tone())
        Spacer(Modifier.height(8.dp))
        Text(state.label, style = DtnType.RowTitle.copy(fontSize = 15.sp))
        Spacer(Modifier.height(4.dp))
        Text(model.explanation, style = DtnType.RowSubtitle)
    }
}

/** The chain of stops ("You —LoRa hub→ hub A9B0 ⋯ raushan"), the hop count and, for broadcasts, who got a copy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteCard(model: MessageTraceModel) {
    val path = model.path
    if (path == null || path.stops.isEmpty()) {
        Text("No route recorded for this message on this phone.", style = DtnType.RowSubtitle)
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Route: " + path.asText() },
    ) {
        path.stops.forEachIndexed { i, stop ->
            if (i > 0) HopArrow(path.hops[i - 1], Modifier.align(Alignment.CenterVertically))
            StopChip(stop, Modifier.align(Alignment.CenterVertically))
        }
    }
    Spacer(Modifier.height(10.dp))
    val hops = if (model.hops == 1) "1 hop" else "${model.hops} hops"
    Text(
        buildString {
            if (model.hops > 0) append(hops).append(" as far as this phone knows · ")
            append("⋯ = a hop this phone didn't see")
        },
        style = DtnType.Meta,
    )
    if (model.isBroadcast) Text(MessageTrace.copiesLine(model.reached.size), style = DtnType.Meta)
}

@Composable
private fun StopChip(stop: PathStop, modifier: Modifier = Modifier) {
    val (bg, fg) = when {
        stop.reached -> DtnColors.GreenSoft to DtnColors.GreenText
        stop.isYou -> DtnColors.PrimarySoft to DtnColors.Primary
        else -> DtnColors.Track to DtnColors.TextTitle
    }
    Surface(shape = DtnShapes.Pill, color = bg, modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stop.isHub) {
                Icon(DtnIcons.LoraHub, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(stop.label + if (stop.reached) " ✓" else "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
        }
    }
}

/** The link name over an arrow ("BLE →"), or ⋯ for a hop this phone didn't see. */
@Composable
private fun HopArrow(hop: PathHop, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val label = when {
            hop.waiting -> "not sent yet"
            !hop.seen -> "not seen here"
            else -> hop.link.orEmpty()
        }
        Text(label, fontSize = 10.sp, color = if (hop.seen) DtnColors.Primary else DtnColors.TextHint, maxLines = 1)
        Text(if (hop.seen) "→" else "⋯", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = if (hop.seen) DtnColors.Primary else DtnColors.TextHint)
    }
}

/** Routed by (algorithm chip, saved time), the saved numbers in a monospace box, the sender's algorithm. */
@Composable
private fun DecisionCard(d: DecisionInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (d.routedBy != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Routed by", style = DtnType.RowSubtitle, modifier = Modifier.width(132.dp))
                AlgorithmChip(d.routedBy)
                Spacer(Modifier.weight(1f))
                if (d.savedAtMs > 0) Text("saved ${UiText.listTime(d.savedAtMs)}", style = DtnType.Meta)
            }
        }
        if (d.numbers != null) {
            if (d.savedAtMs > 0) Text("The numbers as they were at ${UiText.fullTime(d.savedAtMs)}:", style = DtnType.Meta)
            Surface(shape = DtnShapes.Button, color = DtnColors.GreySoft, modifier = Modifier.fillMaxWidth()) {
                Text(d.numbers, style = DtnType.Mono, modifier = Modifier.padding(12.dp))
            }
        }
        if (d.senderAlgorithm != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sender's algorithm", style = DtnType.RowSubtitle, modifier = Modifier.width(132.dp))
                AlgorithmChip(d.senderAlgorithm)
            }
        }
        if (d.note != null) Text(d.note, style = DtnType.RowSubtitle)
    }
}

/** An algorithm name in its colour (PRoPHET blue, Stable green, MaxProp orange, DiMaxProp purple, Epidemic red, Q-learning teal). */
@Composable
private fun AlgorithmChip(name: String) {
    val c: Color = DtnColors.algorithm(name.replace(" ", ""))
    Surface(shape = DtnShapes.Pill, color = c.copy(alpha = 0.12f)) {
        Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c, maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** A label / value row of the Details card (ids in monospace). */
@Composable
private fun DetailLine(label: String, value: String, mono: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = DtnType.RowSubtitle, modifier = Modifier.width(132.dp))
        Text(value, style = if (mono) DtnType.Mono else DtnType.RowSubtitle.copy(color = DtnColors.TextTitle),
            modifier = Modifier.weight(1f))
    }
}

/**
 * Shares [file] as an attachment through the share sheet. It must be under one of the FileProvider
 * paths in file_paths.xml (cache/events/ for event details and Export events, exports/ for Export Data).
 */
internal fun shareFile(
    context: Context,
    file: File,
    subject: String,
    mimeType: String = "text/plain",
    chooserTitle: String = "Share event details",
): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        clipData = ClipData.newRawUri(subject, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, chooserTitle)
    if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}.isSuccess
