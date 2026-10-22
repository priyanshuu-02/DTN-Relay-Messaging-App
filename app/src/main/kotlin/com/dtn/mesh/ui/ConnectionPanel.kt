package com.dtn.mesh.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dtn.mesh.receiver.LinkInfo
import com.dtn.mesh.receiver.TransportKind

private data class HealthColors(val dot: Color, val soft: Color)

private fun colorsFor(h: LinkHealth) = when (h) {
    LinkHealth.ON -> HealthColors(Palette.Accent, Palette.AccentSoft)
    LinkHealth.BUSY -> HealthColors(Palette.Warning, Palette.WarningSoft)
    LinkHealth.PROBLEM -> HealthColors(Palette.Error, Palette.ErrorSoft)
    LinkHealth.OFF -> HealthColors(Palette.TextMuted, Palette.Surface)
}

/** Re-read radios + permissions whenever the app comes back to the foreground. */
@Composable
private fun OnResume(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) action() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Returns a function that asks the system to turn Bluetooth on (the standard in-app dialog),
 * falling back to Bluetooth settings when the request isn't possible (no permission / OEM block).
 */
@Composable
fun rememberBluetoothEnabler(viewModel: DtnViewModel): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshRadios()
    }
    return remember(launcher) {
        {
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val launched = allowed && runCatching { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.isSuccess
            if (!launched) runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
    }
}

internal fun openWifiSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
    else Intent(Settings.ACTION_WIFI_SETTINGS)
    runCatching { context.startActivity(intent) }
}

private fun openLocationSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
}

/**
 * Connection card: a live headline, one tappable chip per transport (BLE · Wi-Fi Direct · LoRa
 * hub · Internet), and the Connect / Sync / Clear row. Also hosts the preflight checklist and the
 * Clear confirmation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionPanel(viewModel: DtnViewModel) {
    val status by viewModel.transportStatus.collectAsState()
    val radios by viewModel.radios.collectAsState()
    val relayPeers by viewModel.internetPeers.collectAsState()
    val nodeId by viewModel.localNodeId.collectAsState()
    val strategy by viewModel.activeStrategy.collectAsState()
    val buffered by viewModel.bufferedCount.collectAsState()
    val showPreflight by viewModel.showPreflight.collectAsState()
    val linkInfo by viewModel.linkInfo.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<TransportKind?>(null) }
    val requestBluetoothOn = rememberBluetoothEnabler(viewModel)

    OnResume { viewModel.refreshRadios() }

    val linkPeers = linkInfo.mapValues { it.value.peers.size }
    val chips = TransportKind.entries.map {
        transportChip(it, status, radios, relayPeers.size, linkPeers, viewModel.wifiDirectSupported)
    }
    val headline = connectionHeadline(chips)
    val headColors = colorsFor(headline.health)

    val onChipTap: (TransportKind) -> Unit = { kind ->
        val health = chips.first { it.kind == kind }.health
        when {
            kind == TransportKind.WIFI_DIRECT && !viewModel.wifiDirectSupported -> detailsFor = kind
            kind == TransportKind.BLE && !radios.bluetoothOn -> {
                requestBluetoothOn()
                // Keep BLE wanted so it starts by itself the moment Bluetooth comes on.
                if (!status.isWanted(TransportKind.BLE)) viewModel.toggleTransport(TransportKind.BLE)
            }
            (kind == TransportKind.HUB || kind == TransportKind.WIFI_DIRECT) && !radios.wifiOn -> openWifiSettings(context)
            // Running or coming up: show what it's doing and who's on it (it can be turned off there).
            health == LinkHealth.ON || health == LinkHealth.BUSY -> detailsFor = kind
            else -> viewModel.toggleTransport(kind)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.CardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(38.dp).clip(CircleShape).background(headColors.soft),
                    contentAlignment = Alignment.Center,
                ) { Box(modifier = Modifier.size(13.dp).clip(CircleShape).background(headColors.dot)) }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(headline.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Palette.TextPrimary)
                    Text(headline.detail, fontSize = 11.sp, color = Palette.TextSecondary, maxLines = 2)
                    Text(
                        "Node ${nodeId?.takeLast(8) ?: "—"}  ·  $strategy  ·  Buffer $buffered",
                        fontSize = 10.sp, color = Palette.TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEach { chip -> TransportChip(chip) { onChipTap(chip.kind) } }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (status.wanted.isEmpty()) {
            Button(
                onClick = { viewModel.requestConnect() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Primary),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Connect", fontWeight = FontWeight.SemiBold) }
        } else {
            OutlinedButton(
                onClick = { viewModel.disconnectAll() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Error),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Disconnect", fontWeight = FontWeight.SemiBold) }
        }
        FilledTonalButton(onClick = { viewModel.syncBuffer() }, shape = RoundedCornerShape(10.dp)) { Text("Sync") }
        OutlinedButton(
            onClick = { confirmClear = true },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Error),
        ) { Text("Clear…") }
    }

    if (showPreflight) PreflightDialog(viewModel, requestBluetoothOn)

    detailsFor?.let { kind ->
        val chip = chips.first { it.kind == kind }
        LinkDetailsDialog(
            chip = chip,
            info = linkInfo[kind] ?: LinkInfo(),
            supported = kind != TransportKind.WIFI_DIRECT || viewModel.wifiDirectSupported,
            canTurnOff = status.isWanted(kind),
            nameOf = { id -> peers.firstOrNull { it.nodeId == id }?.displayName() },
            onTurnOff = { viewModel.turnOffTransport(kind); detailsFor = null },
            onDismiss = { detailsFor = null },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the buffer?") },
            text = {
                Text(
                    if (buffered == 0) "There are no queued messages. Clearing also drops any queued control " +
                        "traffic (key announces, receipts)."
                    else "This permanently drops $buffered queued message${if (buffered == 1) "" else "s"} — " +
                        "your unsent messages AND messages you're carrying for other people. They won't be " +
                        "delivered. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearBuffer(); confirmClear = false }) {
                    Text("Clear", color = Palette.Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Link details (tap a running chip): what the link is doing, every device on it right now, and
 * a way to turn it off. Live — it follows the link while open.
 */
@Composable
private fun LinkDetailsDialog(
    chip: TransportChipModel,
    info: LinkInfo,
    supported: Boolean,
    canTurnOff: Boolean,
    nameOf: (String) -> String?,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = colorsFor(chip.health)
    val heading = when (chip.kind) {
        TransportKind.BLE -> "DTN phones in Bluetooth range"
        TransportKind.WIFI_DIRECT -> "Phones linked in this group"
        TransportKind.HUB -> "Other phones on this hub"
        TransportKind.INTERNET -> "Contacts on the relay"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(c.dot))
                Spacer(Modifier.width(8.dp))
                Text(chip.label)
            }
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!supported) {
                    Text(
                        "Wi-Fi Direct needs Android 10 or newer on this phone. Bluetooth, the LoRa hub and " +
                            "the internet relay still work.",
                        fontSize = 13.sp, color = Palette.TextPrimary,
                    )
                } else {
                    Text(
                        info.summary.ifBlank { chip.detail },
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Palette.TextPrimary,
                    )
                    Text("$heading (${info.peers.size})", fontSize = 12.sp, color = Palette.TextSecondary)
                    if (info.peers.isEmpty()) {
                        Text("None right now.", fontSize = 12.sp, color = Palette.TextMuted)
                    }
                }
                if (supported) info.peers.forEach { p ->
                    val name = nameOf(p.nodeId)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, Palette.Divider, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Palette.Accent))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name ?: p.nodeId, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.TextPrimary)
                            val sub = listOfNotNull(
                                p.role.takeIf { it.isNotBlank() },
                                p.detail.takeIf { it.isNotBlank() },
                                p.nodeId.takeLast(8).takeIf { name != null },
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = Palette.TextSecondary)
                        }
                    }
                }
                if (supported) info.facts.forEach { Text(it, fontSize = 11.sp, color = Palette.TextMuted) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (supported && canTurnOff) {
            { TextButton(onClick = onTurnOff) { Text("Turn off", color = Palette.Error) } }
        } else null,
    )
}

@Composable
private fun TransportChip(chip: TransportChipModel, onClick: () -> Unit) {
    val c = colorsFor(chip.health)
    val action = when (chip.health) {
        LinkHealth.ON, LinkHealth.BUSY -> "show details"
        LinkHealth.OFF -> "turn on"
        LinkHealth.PROBLEM -> "fix or retry"
    }
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = "${chip.label}: $action", onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = c.soft,
        border = BorderStroke(1.dp, c.dot.copy(alpha = 0.35f)),
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(c.dot))
            Spacer(Modifier.width(6.dp))
            Column {
                Text(chip.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
                Text(chip.detail, fontSize = 10.sp, color = Palette.TextSecondary, maxLines = 1)
            }
        }
    }
}

/**
 * "Before connecting" checklist: lists exactly what would stop a transport and offers the fix
 * for each. Re-evaluates live (radio toggles, permission results, returning from settings) and
 * continues on its own once everything is resolved.
 */
@Composable
private fun PreflightDialog(viewModel: DtnViewModel, requestBluetoothOn: () -> Unit) {
    val context = LocalContext.current
    val radios by viewModel.radios.collectAsState()
    var recheck by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck++
        viewModel.refreshRadios()
    }
    OnResume { recheck++; viewModel.refreshRadios() }
    val issues = remember(radios, recheck) { viewModel.preflightIssues() }
    LaunchedEffect(issues) { if (issues.isEmpty()) viewModel.connectAll() }

    AlertDialog(
        onDismissRequest = { viewModel.dismissPreflight() },
        title = { Text("Before connecting") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                issues.forEach { issue ->
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .border(1.dp, Palette.Divider, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    ) {
                        Text(issue.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Palette.TextPrimary)
                        Text(issue.detail, fontSize = 12.sp, color = Palette.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (issue.kind) {
                                PreflightIssue.Kind.PERMISSIONS -> {
                                    FilledTonalButton(onClick = { permissions.launch(PermissionHelper.requiredPermissions()) }) { Text("Allow") }
                                    TextButton(onClick = { openAppSettings(context) }) { Text("App settings") }
                                }
                                PreflightIssue.Kind.BLUETOOTH_OFF -> {
                                    FilledTonalButton(onClick = requestBluetoothOn) { Text("Turn on") }
                                    TextButton(onClick = { viewModel.useInternetRelayInstead() }) { Text("Use internet instead") }
                                }
                                PreflightIssue.Kind.WIFI_OFF ->
                                    FilledTonalButton(onClick = { openWifiSettings(context) }) { Text("Turn on Wi-Fi") }
                                PreflightIssue.Kind.LOCATION_OFF ->
                                    FilledTonalButton(onClick = { openLocationSettings(context) }) { Text("Location settings") }
                            }
                        }
                    }
                }
                Text(
                    "Or connect anyway — every link that can start will, and the rest show what they need.",
                    fontSize = 11.sp, color = Palette.TextMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.connectAll() }) { Text("Connect anyway") } },
        dismissButton = { TextButton(onClick = { viewModel.dismissPreflight() }) { Text("Cancel") } },
    )
}

/**
 * Banner for a 1:1 chat whose contact isn't reachable right now: explains why and offers the
 * fix — turn Bluetooth on (nearby), or use the internet relay (anywhere).
 */
@Composable
fun ReachabilityBanner(viewModel: DtnViewModel, peer: PeerInfo) {
    if (peer.reachable) return
    val radios by viewModel.radios.collectAsState()
    val status by viewModel.transportStatus.collectAsState()
    val requestBluetoothOn = rememberBluetoothEnabler(viewModel)
    val relayWanted = status.isWanted(TransportKind.INTERNET)
    val bleWanted = status.isWanted(TransportKind.BLE)
    val message = when {
        !radios.bluetoothOn && !relayWanted ->
            "${peer.displayName()} isn't reachable. Bluetooth is off and the internet relay isn't on — your message will wait in the buffer."
        !relayWanted ->
            "${peer.displayName()} isn't nearby. Turn on the internet relay to reach them anywhere (they need it on too)."
        !peer.hasKeys ->
            "No keys for ${peer.displayName()} yet — scan each other's QR (🔑) so messages can be encrypted."
        else ->
            "${peer.displayName()} isn't nearby or on the relay right now. Your message waits and is delivered when they're reachable."
    }
    Surface(color = Palette.WarningSoft, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(message, fontSize = 12.sp, color = Palette.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!radios.bluetoothOn) {
                    TextButton(onClick = {
                        requestBluetoothOn()
                        if (!bleWanted) viewModel.toggleTransport(TransportKind.BLE)
                    }) { Text("Turn on Bluetooth") }
                }
                if (!relayWanted) {
                    TextButton(onClick = { viewModel.toggleTransport(TransportKind.INTERNET) }) { Text("Use internet relay") }
                }
            }
        }
    }
}
