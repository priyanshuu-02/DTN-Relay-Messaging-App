package com.dtn.mesh.ui.peers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.routing.RadioDistanceEstimator
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.Avatar
import com.dtn.mesh.ui.components.ConfirmDialog
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.NoteKind
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.components.StatTile
import com.dtn.mesh.ui.components.StatusChip
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.relativeSince
import com.dtn.mesh.ui.rememberQrActions
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType

/**
 * Contact page (`contact/{node}`, replaces the old contact-info dialog): avatar + name, how they're
 * reachable, the safety number and whether it's verified, the active algorithm's routing scores
 * for them, and Rename / Delete contact / Block (or Unblock).
 *
 * @param onClose leave after the contact was deleted or blocked (their chat is gone too).
 */
@Composable
fun ContactPage(
    viewModel: DtnViewModel,
    nodeId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onClose: () -> Unit,
) {
    val peers by viewModel.peerList.collectAsState()
    val metrics by viewModel.peerMetrics.collectAsState()
    val strategy by viewModel.activeStrategy.collectAsState()
    val peer = peers.firstOrNull { it.nodeId == nodeId }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val actions = rememberContactActions(viewModel, onRemoved = { onClose() })
    val qr = rememberQrActions(
        prompt = "Scan ${peer?.displayName() ?: "their"}'s QR (My identity on their phone)",
        onResult = { text -> viewModel.handleScannedQr(text) { } },
        onProblem = { viewModel.showMessage(it) },
    )
    var confirmAccept by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { InnerTopBar("Contact", onBack = onBack) },
    ) { padding ->
        if (peer == null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(DtnIcons.Peers, "Contact not found", "This phone has no record of ${nodeId.takeLast(8)}.")
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(peer)
            if (peer.blocked) {
                InfoNote("You blocked ${peer.displayName()}: their messages and group posts aren't shown and they don't get " +
                    "your keys. They still get delivery receipts.", kind = NoteKind.DANGER)
                PrimaryButton("Unblock", onClick = { viewModel.setBlocked(peer.nodeId, false) }, modifier = Modifier.fillMaxWidth())
            } else {
                if (peer.keysChanged) {
                    InfoNote("Keys changed — ${peer.displayName()}'s phone announced new keys (for example after its data " +
                        "was cleared). Scan their QR to verify them, or Accept. Messages to them wait until then.",
                        kind = NoteKind.CAUTION)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Scan their QR", onClick = qr.scan, modifier = Modifier.weight(1f))
                        OutlineButton("Accept", onClick = { confirmAccept = true }, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Message", onClick = { onOpenChat(peer.nodeId) }, modifier = Modifier.weight(1f))
                    if (!peer.added) {
                        OutlineButton("Add to chats", onClick = { viewModel.addContact(peer.nodeId) }, modifier = Modifier.weight(1f))
                    }
                }
            }

            SectionHeader("Safety number")
            SafetyNumberCard(
                peer = peer,
                onVerifyByQr = qr.scan,
                onToggleVerified = { viewModel.setContactVerified(peer.nodeId, !peer.keyVerified) },
            )

            SectionHeader("Routing")
            RoutingCard(peer, metrics[peer.nodeId], strategy)

            SectionHeader("Actions")
            DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow("Rename", icon = DtnIcons.Edit, subtitle = peer.customName?.let { "Nickname: $it" } ?: "Set a nickname",
                    onClick = { actions.rename(peer) })
                SettingRow("Delete contact…", icon = DtnIcons.Delete, iconTint = DtnColors.Red, iconBackground = DtnColors.RedSoft,
                    titleColor = DtnColors.RedText, subtitle = "Deletes the chat; relaying for them continues",
                    onClick = { actions.delete(peer) })
                if (peer.blocked) {
                    SettingRow("Unblock", icon = DtnIcons.Block, subtitle = "Show their messages again",
                        onClick = { viewModel.setBlocked(peer.nodeId, false) })
                } else {
                    SettingRow("Block…", icon = DtnIcons.Block, iconTint = DtnColors.Red, iconBackground = DtnColors.RedSoft,
                        titleColor = DtnColors.RedText, subtitle = "Hide their messages; they still get receipts",
                        onClick = { actions.block(peer) })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmAccept && peer != null) {
        ConfirmDialog(
            title = "Use ${peer.displayName()}'s new keys?",
            text = "Their safety number changes and is no longer verified. Compare it with their phone, or scan " +
                "their QR, to verify it again. Messages waiting for them are sent with the new keys.",
            confirmLabel = "Accept",
            destructive = false,
            onConfirm = { confirmAccept = false; viewModel.acceptNewKeys(peer.nodeId) },
            onDismiss = { confirmAccept = false },
        )
    }
}

@Composable
private fun Header(peer: PeerInfo) {
    val reach = peer.reach()
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(UiText.avatarInitial(peer.displayName()), DtnColors.avatarFor(peer.nodeId), size = 72.dp)
        Spacer(Modifier.height(10.dp))
        Text(peer.displayName(), style = DtnType.PageTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (peer.blocked) StatusChip("Blocked", Tone.RED, icon = DtnIcons.Block)
            else StatusChip(reach.chip, reach.tone)
            if (peer.added && !peer.blocked) StatusChip("In your chats", Tone.BLUE, icon = DtnIcons.Check)
        }
        Spacer(Modifier.height(6.dp))
        Text(peer.nodeId, style = DtnType.Mono.copy(color = DtnColors.TextSubtitle))
        Text(PeerLists.headerLine(peer, ::relativeSince), style = DtnType.RowSubtitle, textAlign = TextAlign.Center)
    }
}

/** 4 × 4 monospace grid, with "Verified ✓" or "Verify by QR". */
@Composable
private fun SafetyNumberCard(peer: PeerInfo, onVerifyByQr: () -> Unit, onToggleVerified: () -> Unit) {
    DtnCard {
        val fp = peer.fingerprintHex
        if (fp == null) {
            Text("No keys yet — scan their QR or wait for their announce. Until then direct messages can't be encrypted.",
                style = DtnType.RowSubtitle)
            Spacer(Modifier.height(10.dp))
            OutlineButton("Scan their QR", onClick = onVerifyByQr, modifier = Modifier.fillMaxWidth())
            return@DtnCard
        }
        Surface(shape = DtnShapes.Button, color = DtnColors.GreySoft, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                UiText.fingerprintLines(fp).forEach { line ->
                    Text(line, style = DtnType.Mono.copy(fontSize = 14.sp), modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (peer.keyVerified) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip("Verified", Tone.GREEN, icon = DtnIcons.Check)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onToggleVerified) { Text("Clear verification") }
            }
        } else {
            Text("Not verified — compare it with My identity on their phone, or scan their QR.", style = DtnType.RowSubtitle)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Verify by QR", onClick = onVerifyByQr, modifier = Modifier.weight(1f))
                OutlineButton("Mark verified", onClick = onToggleVerified, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The active algorithm's score for this peer, plus encounters, contact time, deliveries, RSSI → distance. */
@Composable
private fun RoutingCard(peer: PeerInfo, metric: com.dtn.mesh.ui.PeerMetric?, strategy: String) {
    DtnCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Active algorithm", style = DtnType.RowSubtitle, modifier = Modifier.weight(1f))
            StatusChip(strategy, Tone.BLUE)
        }
        Spacer(Modifier.height(10.dp))
        val tileMod = Modifier
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Score ${metric?.label ?: ""}".trim(), metric?.value ?: "—", DtnColors.algorithm(strategy),
                tileMod.weight(1f), sub = metric?.hint?.takeIf { it.isNotBlank() })
            StatTile("Encounters", "${peer.totalEncounters}", DtnColors.Primary, tileMod.weight(1f),
                sub = if (peer.lastSeenMs > 0) "last ${relativeSince(peer.lastSeenMs)}" else "none yet")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Contact time", if (peer.totalEncounterDurationMs > 0) UiText.duration(0, peer.totalEncounterDurationMs) else "—",
                DtnColors.Purple, tileMod.weight(1f), sub = "time in range")
            StatTile("Deliveries", "${peer.deliverySuccessCount}/${peer.deliveryAttemptCount}", DtnColors.Green, tileMod.weight(1f),
                sub = "via them / tried")
        }
        Spacer(Modifier.height(8.dp))
        val rssi = peer.lastRssi
        val meters = if (rssi > -200 && rssi != 0) RadioDistanceEstimator.estimateMeters(rssi) else RadioDistanceEstimator.UNKNOWN_DISTANCE
        StatTile("RSSI → distance", if (rssi > -200 && rssi != 0) "$rssi dBm" else "—", DtnColors.Orange, Modifier.fillMaxWidth(),
            sub = if (meters > 0) "≈ ${"%.1f".format(meters)} m (BLE estimate)" else "no BLE signal yet")
        Spacer(Modifier.height(6.dp))
        Text("Per-peer scores for every algorithm: Developer Tools › Routing Engine.", style = DtnType.Meta)
    }
}
