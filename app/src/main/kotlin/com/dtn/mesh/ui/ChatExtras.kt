package com.dtn.mesh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.receiver.MeshConnectionState

/** Latest user notice while a dialog is open (snackbars are hidden behind dialog windows). */
@Composable
private fun rememberDialogNotice(viewModel: DtnViewModel): String? {
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) { viewModel.userMessages.collect { notice = it } }
    return notice
}

@Composable
private fun NoticeLine(notice: String?) {
    if (notice == null) return
    Surface(color = Palette.PrimarySoft, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(notice, fontSize = 12.sp, color = Palette.TextPrimary, modifier = Modifier.padding(10.dp))
    }
    Spacer(Modifier.height(8.dp))
}

/** Full safety number: all 64 hex characters, four groups per line. */
@Composable
fun SafetyNumber(hex: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        UiText.fingerprintLines(hex).forEach {
            Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Palette.TextPrimary)
        }
    }
}

/* ==========================================================================
 *  Identity / key-exchange dialog
 * ========================================================================== */
@Composable
fun IdentityDialog(viewModel: DtnViewModel, onOpenChat: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val qr = remember { viewModel.identityQrString() }
    val notice = rememberDialogNotice(viewModel)
    val qrActions = rememberQrActions(
        prompt = "Scan a contact or group QR",
        onResult = { text -> viewModel.handleScannedQr(text) { key -> onOpenChat(key) } },
        onProblem = { viewModel.showMessage(it) },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("My identity") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NoticeLine(notice)
                QrImage(qr)
                Spacer(Modifier.height(8.dp))
                Text(viewModel.myNodeId, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text("Safety number (full 256-bit fingerprint)", fontSize = 11.sp, color = Palette.TextMuted)
                Spacer(Modifier.height(2.dp))
                SafetyNumber(viewModel.myFingerprintHex)
                Spacer(Modifier.height(12.dp))
                QrInputButtons(qrActions)
                TextButton(onClick = {
                    if (!QrShare.share(context, qr, "Share my DTN contact QR")) viewModel.showMessage("Couldn't share the QR image")
                }) { Text("Share my QR as an image") }
                Text(
                    "Scan each other's QR in person to exchange keys — or share it as an image and the " +
                        "other phone uploads it. A scanned contact can then be reached nearby or over the " +
                        "internet relay. Group invites scan the same way.",
                    fontSize = 11.sp, color = Palette.TextMuted,
                )
            }
        },
    )
}

/* ==========================================================================
 *  Settings dialog — every change is staged until Save (Cancel discards all)
 * ========================================================================== */
@Composable
fun SettingsDialog(viewModel: DtnViewModel, onDismiss: () -> Unit) {
    val savedName by viewModel.displayNameSetting.collectAsState()
    val savedInternet by viewModel.internetEnabled.collectAsState()
    val savedBroker by viewModel.brokerUrl.collectAsState()
    val status by viewModel.transportStatus.collectAsState()
    val relayPeers by viewModel.internetPeers.collectAsState()
    val relayDiagnostics by viewModel.relayDiagnostics.collectAsState()

    val savedLambda by viewModel.diMaxPropLambda.collectAsState()
    val savedEta by viewModel.qShapingEta.collectAsState()

    var name by remember(savedName) { mutableStateOf(savedName) }
    var broker by remember(savedBroker) { mutableStateOf(savedBroker) }
    var internet by remember(savedInternet) { mutableStateOf(savedInternet) }
    var lambda by remember(savedLambda) { mutableStateOf(savedLambda.toFloat()) }
    var eta by remember(savedEta) { mutableStateOf(savedEta.toFloat()) }

    val relayLine = when (status.internet) {
        MeshConnectionState.CONNECTED -> "Relay connected · ${relayPeers.size} contact${if (relayPeers.size == 1) "" else "s"} online"
        MeshConnectionState.CONNECTING -> "Relay connecting…"
        else -> "Relay off"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                viewModel.saveSettings(name.trim(), broker.trim(), internet)
                if (lambda.toDouble() != savedLambda || eta.toDouble() != savedEta) {
                    viewModel.saveRoutingTuning(lambda.toDouble(), eta.toDouble())
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Settings") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("Display name", fontSize = 12.sp, color = Palette.TextMuted)
                OutlinedTextField(value = name, onValueChange = { name = it.take(48) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("shown to contacts") })
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Palette.Divider)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Internet relay", fontWeight = FontWeight.SemiBold)
                        Text("Reach contacts anywhere over MQTT when both phones have internet. " +
                            "Messages stay end-to-end encrypted.", fontSize = 11.sp, color = Palette.TextMuted)
                    }
                    Switch(checked = internet, onCheckedChange = { internet = it })
                }
                Text(relayLine, fontSize = 11.sp, color = Palette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                // Which endpoint it's on / why the last attempt failed — the first thing to check
                // when the relay won't link on some network.
                if (status.isWanted(com.dtn.mesh.receiver.TransportKind.INTERNET) && relayDiagnostics.isNotBlank()) {
                    Text(relayDiagnostics, fontSize = 10.sp, color = Palette.TextMuted, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Text("MQTT broker", fontSize = 12.sp, color = Palette.TextMuted)
                OutlinedTextField(value = broker, onValueChange = { broker = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(com.dtn.mesh.receiver.RelayEndpoints.DEFAULT_URL) })
                Text(
                    "Keep the default unless you run your own broker. On the public broker the app tries TLS " +
                        "(8883) first and falls back to 1883 and WebSocket (8884) where networks block ports. " +
                        "The broker is shared by everyone using it: it never sees the text of encrypted messages, " +
                        "but it does see who is online and message headers.",
                    fontSize = 10.sp, color = Palette.TextMuted, modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Palette.Divider)
                Spacer(Modifier.height(12.dp))
                Text("Routing tuning", fontWeight = FontWeight.SemiBold)
                Text("For experiments. Both apply right away to the running algorithms.",
                    fontSize = 11.sp, color = Palette.TextMuted)
                Spacer(Modifier.height(6.dp))
                Text("DiMaxProp distance weight λ = %.2f".format(lambda), fontSize = 12.sp, color = Palette.TextSecondary)
                androidx.compose.material3.Slider(
                    value = lambda, onValueChange = { lambda = (it * 20).roundToInt() / 20f },
                    valueRange = 0f..1f, steps = 19,
                    modifier = Modifier.semantics { contentDescription = "DiMaxProp distance weight" },
                )
                Text("0 = plain MaxProp. Higher = prefer carriers that were physically closer (BLE distance).",
                    fontSize = 10.sp, color = Palette.TextMuted)
                Spacer(Modifier.height(8.dp))
                Text("Q-learning progress reward η = %.2f".format(eta), fontSize = 12.sp, color = Palette.TextSecondary)
                androidx.compose.material3.Slider(
                    value = eta, onValueChange = { eta = (it * 20).roundToInt() / 20f },
                    valueRange = 0f..1f, steps = 19,
                    modifier = Modifier.semantics { contentDescription = "Q-learning progress reward" },
                )
                Text("Reward per forward for moving a message closer to its destination (DiMaxProp cost). 0 = off.",
                    fontSize = 10.sp, color = Palette.TextMuted)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Palette.Divider)
                Spacer(Modifier.height(12.dp))
                AboutAndPrivacy()
            }
        },
    )
}

/** Version + the plain-language privacy summary (the full policy ships with the store listing). */
@Composable
private fun AboutAndPrivacy() {
    Text("About & privacy", fontWeight = FontWeight.SemiBold)
    Text(
        "DTN Mesh Relay ${com.dtn.mesh.BuildConfig.VERSION_NAME} (build ${com.dtn.mesh.BuildConfig.VERSION_CODE})",
        fontSize = 11.sp, color = Palette.TextSecondary,
    )
    Spacer(Modifier.height(6.dp))
    listOf(
        "No account, no ads, no analytics or tracking.",
        "Your keys, contacts and chats stay on this phone and are excluded from cloud backups.",
        "Direct and group messages are end-to-end encrypted: phones, LoRa hubs and the relay that carry them can't read them.",
        "Broadcasts are public — anyone in reach can read them.",
        "With the internet relay on, the broker sees your node id, when you're online and message headers.",
        "Bluetooth, Wi-Fi and (on Android 12 and older) location access are used only to find nearby phones and hubs — never to track you.",
    ).forEach { line ->
        Text("•  $line", fontSize = 11.sp, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 2.dp))
    }
}

/* ==========================================================================
 *  Groups dialog (create / join / invite / leave)
 * ========================================================================== */
@Composable
fun GroupsDialog(viewModel: DtnViewModel, onOpenChat: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val groups by viewModel.groups.collectAsState()
    val notice = rememberDialogNotice(viewModel)
    var newName by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<Pair<String, String>?>(null) } // name → invite text
    var leaveTarget by remember { mutableStateOf<GroupInfo?>(null) }

    val qrActions = rememberQrActions(
        prompt = "Scan a group invite QR",
        onResult = { text -> viewModel.handleScannedQr(text) { key -> onOpenChat(key) } },
        onProblem = { viewModel.showMessage(it) },
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Groups") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                NoticeLine(notice)
                if (groups.isEmpty()) {
                    Text("No groups yet. Create one and share its invite, or join with an invite QR.",
                        fontSize = 12.sp, color = Palette.TextMuted)
                } else {
                    groups.forEach { g ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onOpenChat("grp:${g.channel}") }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("👥 ${g.name}", fontWeight = FontWeight.Medium, maxLines = 1)
                                Text("channel ${g.channel} · tap to open", fontSize = 10.sp, color = Palette.TextMuted)
                            }
                            TextButton(onClick = {
                                viewModel.groupInvite(g.channel) { inv -> invite = inv?.let { g.name to it } }
                            }) { Text("Invite") }
                            TextButton(onClick = { leaveTarget = g }) { Text("Leave", color = Palette.Error) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = Palette.Divider)
                Spacer(Modifier.height(10.dp))
                Text("Create a group", fontSize = 12.sp, color = Palette.TextMuted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it.take(32) }, singleLine = true,
                        modifier = Modifier.weight(1f), placeholder = { Text("group name") })
                    Button(onClick = {
                        val n = newName.trim().ifBlank { "group" }
                        viewModel.createGroup(n) { inv -> invite = n to inv; newName = "" }
                    }) { Text("Create") }
                }
                Spacer(Modifier.height(12.dp))
                Text("Join a group", fontSize = 12.sp, color = Palette.TextMuted)
                Spacer(Modifier.height(4.dp))
                QrInputButtons(qrActions, scanLabel = "📷 Scan invite")
                invite?.let { (gName, inv) ->
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Palette.Divider)
                    Spacer(Modifier.height(10.dp))
                    Text("Invite to “$gName” — anyone with this QR can read and post in the group.",
                        fontSize = 12.sp, color = Palette.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrImage(inv)
                        TextButton(onClick = {
                            if (!QrShare.share(context, inv, "Share group invite")) viewModel.showMessage("Couldn't share the invite")
                        }) { Text("Share invite as an image") }
                    }
                }
            }
        },
    )

    leaveTarget?.let { g ->
        ConfirmLeaveGroupDialog(g.name, onConfirm = { viewModel.leaveGroup(g.channel); leaveTarget = null }) { leaveTarget = null }
    }
}

@Composable
private fun ConfirmLeaveGroupDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave “$name”?") },
        text = { Text("This phone forgets the group key and stops receiving its messages. Chat history stays here. " +
            "You can rejoin later with an invite.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Leave", color = Palette.Error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/* ==========================================================================
 *  Group info (from the group chat header)
 * ========================================================================== */
@Composable
fun GroupInfoDialog(
    viewModel: DtnViewModel,
    group: GroupInfo,
    membersSeen: List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var invite by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(group.channel) { viewModel.groupInvite(group.channel) { invite = it } }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = { confirmLeave = true }) { Text("Leave group", color = Palette.Error) } },
        title = { Text("👥 ${group.name}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("End-to-end encrypted with the group key · channel ${group.channel}",
                    fontSize = 11.sp, color = Palette.TextMuted)
                Spacer(Modifier.height(10.dp))
                Text("MEMBERS SEEN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.TextSecondary)
                Text(
                    if (membersSeen.isEmpty()) "Only you so far — members appear here once they post."
                    else "You, " + membersSeen.joinToString(", "),
                    fontSize = 12.sp, color = Palette.TextPrimary,
                )
                Text("There's no server, so membership is whoever holds the invite.",
                    fontSize = 10.sp, color = Palette.TextMuted)
                Spacer(Modifier.height(12.dp))
                val inv = invite
                if (inv != null) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrImage(inv)
                        TextButton(onClick = {
                            if (!QrShare.share(context, inv, "Share group invite")) viewModel.showMessage("Couldn't share the invite")
                        }) { Text("Share invite as an image") }
                    }
                }
            }
        },
    )
    if (confirmLeave) {
        ConfirmLeaveGroupDialog(group.name, onConfirm = {
            confirmLeave = false
            viewModel.leaveGroup(group.channel)
            onDismiss()
        }) { confirmLeave = false }
    }
}

/* ==========================================================================
 *  Contact info (from the 1:1 chat header): reachability + safety number
 * ========================================================================== */
@Composable
fun ContactInfoDialog(
    viewModel: DtnViewModel,
    peer: PeerInfo,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reach = when {
        peer.isOnline -> "Nearby (radio)"
        peer.internetOnline -> "Online via the internet relay"
        peer.viaOtherHub -> "Online via LoRa: on ${peer.meshHubLabel}, reached through the hubs"
        else -> "Not reachable right now — messages wait in the buffer"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onRename) { Text("Rename") } },
        title = { Text(peer.displayName()) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(peer.nodeId, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Text(reach, fontSize = 13.sp, color = if (peer.reachable) Palette.Accent else Palette.TextSecondary)
                Spacer(Modifier.height(12.dp))
                Text("SAFETY NUMBER", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.TextSecondary)
                val fp = peer.fingerprintHex
                if (fp == null) {
                    Text("No keys yet — scan their QR (🔑) or wait for their announce. Until then DMs can't be encrypted.",
                        fontSize = 12.sp, color = Palette.TextMuted)
                } else {
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { SafetyNumber(fp) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (peer.keyVerified) "✓ Verified in person" else "Not verified — compare with “My identity” on their phone",
                        fontSize = 12.sp, color = if (peer.keyVerified) Palette.Accent else Palette.Warning,
                    )
                    FilledTonalButton(onClick = { viewModel.setContactVerified(peer.nodeId, !peer.keyVerified) }) {
                        Text(if (peer.keyVerified) "Clear verification" else "Mark as verified")
                    }
                }
            }
        },
    )
}
