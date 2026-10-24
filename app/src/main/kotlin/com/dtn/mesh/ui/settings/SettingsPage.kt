package com.dtn.mesh.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.BuildConfig
import com.dtn.mesh.receiver.RelayStatus
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.ui.CLEAR_BUFFER_TITLE
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.StorageInfo
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.clearBufferConfirmText
import com.dtn.mesh.ui.relayStatusText
import com.dtn.mesh.ui.shell.rememberRelayClock
import com.dtn.mesh.ui.components.Avatar
import com.dtn.mesh.ui.components.ConfirmDialog
import com.dtn.mesh.ui.components.DestructiveButton
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.devtools.DevPage
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType

/**
 * Settings: Profile (display name, My identity), Connectivity (internet relay, Relay server ›),
 * Privacy (Discover, Blocked contacts with Unblock), Groups, Data & storage (buffer use, Clear
 * buffer…, journal / diagnostics sizes), Developer Tools and About.
 *
 * Save semantics: the display name is staged in its dialog (Save applies it once and announces
 * it; Cancel discards it) and the broker address on Relay Configuration (Save changes). Switches
 * apply at once, exactly like the same switches on Connection links and Peers.
 */
@Composable
fun SettingsPage(
    viewModel: DtnViewModel,
    onBack: () -> Unit,
    onOpenDevTools: () -> Unit,
    onOpenDevPage: (String) -> Unit,
    onOpenIdentity: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenRelay: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val name by viewModel.displayNameSetting.collectAsState()
    val status by viewModel.transportStatus.collectAsState()
    val relayPeers by viewModel.internetPeers.collectAsState()
    val relay by viewModel.relayStatus.collectAsState()
    val relayNow = rememberRelayClock(relay)
    val broker by viewModel.brokerUrl.collectAsState()
    val discoverable by viewModel.discoverable.collectAsState()
    val blocked by viewModel.blockedNodes.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val buffered by viewModel.bufferedCount.collectAsState()
    val bundles by viewModel.queueBundles.collectAsState()
    val storage by produceState<StorageInfo?>(null, buffered, bundles.size) { value = viewModel.storageInfo() }
    var editName by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var unblockId by rememberSaveable { mutableStateOf<String?>(null) }
    val nameOf: (String) -> String = { id -> peers.firstOrNull { it.nodeId == id }?.displayName() ?: id.takeLast(8) }

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { InnerTopBar("Settings", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader("Profile")
            DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(onClickLabel = "Edit display name", role = Role.Button) { editName = true }
                        .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(UiText.avatarInitial(name), DtnColors.Primary, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Display name", style = DtnType.Meta)
                        Text(name.ifBlank { "Not set · contacts see node-${viewModel.myNodeId.takeLast(4)}" },
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(DtnIcons.ChevronRight, contentDescription = null, tint = DtnColors.TextHint)
                }
                SettingRow("My identity", icon = DtnIcons.QrCode,
                    subtitle = "QR, node id ${viewModel.myNodeId.removePrefix("!")} and safety number · scan a QR",
                    onClick = onOpenIdentity)
            }

            SectionHeader("Connectivity")
            val wanted = status.isWanted(TransportKind.INTERNET)
            // The same state model as the Network tile, the relay page and Relay Configuration.
            val relayText = relayStatusText(relay, relayPeers.size, relayNow)
            val relayLine = if (relay == RelayStatus.Off) "Off · reach contacts anywhere over MQTT when both phones have internet"
            else relayText.title
            DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow("Internet relay", icon = DtnIcons.Internet, subtitle = relayLine, checked = wanted,
                    onCheckedChange = { viewModel.setInternetRelay(it) })
                if (wanted && relay != RelayStatus.Off) {
                    Text(relayText.detail, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = DtnColors.TextSubtitle,
                        modifier = Modifier.padding(start = 64.dp, end = 12.dp, bottom = 8.dp))
                }
                SettingRow("Relay server", icon = DtnIcons.Settings, subtitle = broker, onClick = onOpenRelay)
            }

            SectionHeader("Privacy")
            DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow("Discover", icon = DtnIcons.Peers,
                    subtitle = if (discoverable) "On: phones nearby and on the relay can see and add you"
                    else "Off: only your contacts get your keys; others add you by QR",
                    checked = discoverable, onCheckedChange = { viewModel.setDiscoverable(it) })
                HorizontalDivider(color = DtnColors.CardBorder, modifier = Modifier.padding(horizontal = 12.dp))
                Text("Blocked contacts · ${blocked.size}", style = DtnType.RowTitle.copy(fontSize = 15.sp),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
                if (blocked.isEmpty()) {
                    Text("No one is blocked. Block someone from their contact page or a ⋮ menu.", style = DtnType.RowSubtitle,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                }
                blocked.sortedBy { nameOf(it).lowercase() }.forEach { id ->
                    BlockedRow(nameOf(id), id, onOpen = { onOpenContact(id) }, onUnblock = { unblockId = id })
                }
            }

            SectionHeader("Groups")
            DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow("Groups", icon = DtnIcons.Groups,
                    subtitle = (if (groups.isEmpty()) "No groups yet" else "${groups.size} group${if (groups.size == 1) "" else "s"}") +
                        " · create, join and invite",
                    iconTint = DtnColors.Purple, iconBackground = DtnColors.PurpleSoft, onClick = onOpenGroups)
            }

            SectionHeader("Data & storage")
            DtnCard {
                StorageSection(storage, buffered, bundles.count { UiText.isBroadcastId(it.dest) })
                Spacer(Modifier.height(12.dp))
                DestructiveButton("Clear buffer…", onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth())
            }

            SectionHeader("Advanced")
            DtnCard(contentPadding = PaddingValues(4.dp)) {
                SettingRow("Developer Tools", icon = DtnIcons.DevTools,
                    subtitle = "Event log, routing engine, buffer history, tuning, benchmarks, export, console",
                    onClick = onOpenDevTools, highlighted = true)
                SettingRow("Routing tuning (λ / η)", icon = DtnIcons.Network,
                    subtitle = "Developer Tools › Routing & Tuning",
                    onClick = { onOpenDevPage(DevPage.TUNING) })
            }

            SectionHeader("About")
            DtnCard { AboutSection() }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editName) {
        DisplayNameDialog(current = name, onDismiss = { editName = false }, onSave = {
            viewModel.saveDisplayName(it)
            editName = false
        })
    }
    if (confirmClear) {
        ConfirmDialog(
            title = CLEAR_BUFFER_TITLE,
            text = clearBufferConfirmText(buffered),
            confirmLabel = "Clear",
            onConfirm = { viewModel.clearBuffer(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
    unblockId?.let { id ->
        ConfirmDialog(
            title = "Unblock ${nameOf(id)}?",
            text = "Their messages, group posts and announces are accepted again. If they're one of your contacts, " +
                "they get your keys again.",
            confirmLabel = "Unblock", destructive = false,
            onConfirm = { viewModel.setBlocked(id, false); unblockId = null },
            onDismiss = { unblockId = null },
        )
    }
}

/** A blocked contact: avatar, name, node id, and a red-outline Unblock. */
@Composable
private fun BlockedRow(name: String, nodeId: String, onOpen: () -> Unit, onUnblock: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Open contact", role = Role.Button, onClick = onOpen)
            .heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(UiText.avatarInitial(name), DtnColors.avatarFor(nodeId), size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = DtnType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(nodeId, style = DtnType.Mono.copy(color = DtnColors.TextSubtitle))
        }
        OutlinedButton(
            onClick = onUnblock, shape = DtnShapes.Button, border = BorderStroke(1.dp, DtnColors.Red),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DtnColors.RedText),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Unblock $name" },
        ) { Text("Unblock", fontWeight = FontWeight.SemiBold) }
    }
}

/** Buffer use (a bar), what is in it, and the sizes of the message journal and the diagnostics log. */
@Composable
private fun StorageSection(storage: StorageInfo?, dms: Int, others: Int) {
    val used = storage?.bufferBytes ?: 0L
    val max = storage?.bufferMaxBytes ?: 0L
    val frac = if (max > 0) (used.toFloat() / max).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Buffer", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle, modifier = Modifier.weight(1f))
        Text(if (storage == null) "…" else "${formatBytes(used)} of ${formatBytes(max)}", style = DtnType.RowSubtitle)
    }
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth().height(8.dp).clip(DtnShapes.Pill).background(DtnColors.Track)
        .semantics { contentDescription = "Buffer ${(frac * 100).toInt()} percent full" }) {
        Box(Modifier.fillMaxWidth(frac.coerceAtLeast(if (used > 0) 0.02f else 0f)).height(8.dp).clip(DtnShapes.Pill)
            .background(if (frac > 0.85f) DtnColors.Red else DtnColors.Primary))
    }
    Spacer(Modifier.height(4.dp))
    Text("${if (dms == 1) "1 DM" else "$dms DMs"} queued · ${if (others == 1) "1 broadcast or group message" else "$others broadcasts and group messages"} " +
        "spreading · announces and receipts are counted in the bytes", style = DtnType.Meta)
    Spacer(Modifier.height(12.dp))
    SizeRow("Message journal", storage?.journalBytes, "Every message's events and routes (Developer Tools › Event Log)")
    SizeRow("Diagnostics log", storage?.diagnosticsBytes, "Levels and sources, rotates at 2 MB (Developer Tools › Console)")
}

@Composable
private fun SizeRow(title: String, bytes: Long?, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle)
            Text(detail, style = DtnType.Meta)
        }
        Spacer(Modifier.width(8.dp))
        Text(bytes?.let(::formatBytes) ?: "…", style = DtnType.RowSubtitle)
    }
}

/** "0 B", "12.4 KB", "1.0 MB". */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** Version + the plain-language privacy summary (the full policy ships with the store listing). */
@Composable
private fun AboutSection() {
    Text("DTN Mesh Relay ${BuildConfig.VERSION_NAME}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle)
    Text("Build ${BuildConfig.VERSION_CODE} · off-grid store-carry-forward messenger", style = DtnType.Meta)
    Spacer(Modifier.height(8.dp))
    listOf(
        "No account, no ads, no analytics or tracking.",
        "Your keys, contacts and chats stay on this phone and are excluded from cloud backups.",
        "Direct and group messages are end-to-end encrypted: phones, LoRa hubs and the relay that carry them can't read them.",
        "Broadcasts are public: anyone in reach can read them.",
        "With the internet relay on, the broker sees your node id, when you're online and message headers.",
        "Bluetooth, Wi-Fi and (on Android 12 and older) location access are used only to find nearby phones and hubs, never to track you.",
    ).forEach { line -> Text("•  $line", style = DtnType.Meta, modifier = Modifier.padding(bottom = 2.dp)) }
}

/** The display name, staged until Save (a change is announced once, to contacts or everyone). */
@Composable
private fun DisplayNameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Display name") },
        text = {
            Column {
                Text("Shown to your contacts and in your QR.", style = DtnType.RowSubtitle)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(DtnViewModel.MAX_DISPLAY_NAME) },
                    singleLine = true, label = { Text("Display name") },
                    modifier = Modifier.fillMaxWidth(), shape = DtnShapes.Field,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("Save", color = DtnColors.Primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
