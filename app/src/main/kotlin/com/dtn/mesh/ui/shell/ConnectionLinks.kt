package com.dtn.mesh.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.receiver.LinkInfo
import com.dtn.mesh.receiver.RadioStatusMonitor
import com.dtn.mesh.receiver.RelayStatus
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.receiver.TransportStatus
import com.dtn.mesh.ui.CLEAR_BUFFER_TITLE
import com.dtn.mesh.ui.ConnectionHeadline
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.LinkHealth
import com.dtn.mesh.ui.LinkTapAction
import com.dtn.mesh.ui.OnResume
import com.dtn.mesh.ui.TransportChipModel
import com.dtn.mesh.ui.clearBufferConfirmText
import com.dtn.mesh.ui.components.ConfirmDialog
import com.dtn.mesh.ui.components.DestructiveButton
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.NoteKind
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.components.StatusDot
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.components.TonalButton
import com.dtn.mesh.ui.components.color
import com.dtn.mesh.ui.components.soft
import com.dtn.mesh.ui.components.text
import com.dtn.mesh.ui.connectionHeadline
import com.dtn.mesh.ui.linkTapAction
import com.dtn.mesh.ui.openWifiSettings
import com.dtn.mesh.ui.rememberBluetoothEnabler
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType
import com.dtn.mesh.ui.transportChip
import kotlinx.coroutines.delay

/** Health → status colour family. */
internal fun LinkHealth.tone(): Tone = when (this) {
    LinkHealth.ON -> Tone.GREEN
    LinkHealth.BUSY -> Tone.ORANGE
    LinkHealth.PROBLEM -> Tone.RED
    LinkHealth.OFF -> Tone.GREY
}

internal fun TransportKind.icon(): ImageVector = when (this) {
    TransportKind.BLE -> DtnIcons.Bluetooth
    TransportKind.WIFI_DIRECT -> DtnIcons.WifiDirect
    TransportKind.HUB -> DtnIcons.LoraHub
    TransportKind.INTERNET -> DtnIcons.Internet
}

internal fun TransportKind.purpose(): String = when (this) {
    TransportKind.BLE -> "Phones nearby, phone to phone"
    TransportKind.WIFI_DIRECT -> "Faster phone-to-phone groups (Android 10+)"
    TransportKind.HUB -> "Long range over LoRa, through the DTN hubs"
    TransportKind.INTERNET -> "Contacts anywhere, over the MQTT relay (optional)"
}

/** Pill text for the status card. */
internal fun ConnectionHeadline.pill(): String = when (health) {
    LinkHealth.ON -> "Online · Store & forward"
    else -> title
}

/** Every link's live chip + the headline, from the same pure model the old chips used. */
internal data class LinkSnapshot(
    val chips: List<TransportChipModel>,
    val headline: ConnectionHeadline,
    val status: TransportStatus,
    val radios: RadioStatusMonitor.Radios,
    val linkInfo: Map<TransportKind, LinkInfo>,
    val relay: RelayStatus = RelayStatus.Off,
) {
    fun chip(kind: TransportKind): TransportChipModel = chips.first { it.kind == kind }
}

@Composable
internal fun rememberLinkSnapshot(viewModel: DtnViewModel): LinkSnapshot {
    val status by viewModel.transportStatus.collectAsState()
    val radios by viewModel.radios.collectAsState()
    val relayPeers by viewModel.internetPeers.collectAsState()
    val linkInfo by viewModel.linkInfo.collectAsState()
    val relay by viewModel.relayStatus.collectAsState()
    val nowMs = rememberRelayClock(relay)
    val linkPeers = linkInfo.mapValues { it.value.peers.size }
    val chips = TransportKind.entries.map {
        transportChip(it, status, radios, relayPeers.size, linkPeers, viewModel.wifiDirectSupported, relay, nowMs)
    }
    return LinkSnapshot(chips, connectionHeadline(chips), status, radios, linkInfo, relay)
}

/**
 * The time for the relay's "Retrying in N s": ticks once a second only while a retry is scheduled
 * (nothing else on these surfaces changes with the clock).
 */
@Composable
internal fun rememberRelayClock(relay: RelayStatus): Long {
    val retrying = relay is RelayStatus.Retrying
    val tick by produceState(System.currentTimeMillis(), retrying) {
        value = System.currentTimeMillis()
        while (retrying) {
            delay(1_000L)
            value = System.currentTimeMillis()
        }
    }
    return if (retrying) maxOf(tick, System.currentTimeMillis()) else tick
}

/** What a link surface can do: today's tap rule, the on/off switch, and the details sheet. */
internal class LinkActions(
    val snapshot: LinkSnapshot,
    val onTap: (TransportKind) -> Unit,
    val onSwitch: (TransportKind, Boolean) -> Unit,
    val showDetails: (TransportKind) -> Unit,
)

/**
 * Hosts the link rules for [content] (Network tiles, Connection links rows, the link pages). A
 * link's details are its page ([onOpenLinkPage], `transport/{kind}`). Radios are re-read whenever
 * the app comes back to the foreground.
 */
@Composable
internal fun LinkActionsHost(
    viewModel: DtnViewModel,
    onOpenLinkPage: (TransportKind) -> Unit,
    content: @Composable (LinkActions) -> Unit,
) {
    val snapshot = rememberLinkSnapshot(viewModel)
    val context = LocalContext.current
    val requestBluetoothOn = rememberBluetoothEnabler(viewModel)
    OnResume { viewModel.refreshRadios() }

    val run: (TransportKind, LinkTapAction) -> Unit = { kind, action ->
        when (action) {
            LinkTapAction.SHOW_DETAILS -> onOpenLinkPage(kind)
            LinkTapAction.ENABLE_BLUETOOTH -> {
                requestBluetoothOn()
                // Keep BLE wanted so it starts by itself the moment Bluetooth comes on.
                if (!snapshot.status.isWanted(TransportKind.BLE)) viewModel.toggleTransport(TransportKind.BLE)
            }
            LinkTapAction.OPEN_WIFI_SETTINGS -> openWifiSettings(context)
            LinkTapAction.TOGGLE -> viewModel.toggleTransport(kind)
        }
    }
    val actions = LinkActions(
        snapshot = snapshot,
        onTap = { kind -> run(kind, linkTapAction(kind, snapshot.chip(kind).health, snapshot.radios, viewModel.wifiDirectSupported)) },
        onSwitch = { kind, on ->
            if (!on) {
                viewModel.turnOffTransport(kind)
            } else {
                // Switching on follows the tap rule for a link that's off (radio first, then start).
                val action = linkTapAction(kind, LinkHealth.OFF, snapshot.radios, viewModel.wifiDirectSupported)
                run(kind, action)
            }
        },
        showDetails = onOpenLinkPage,
    )
    content(actions)
}

/** The Network tab's 4 link tiles: tap = today's chip rule, long-press = details. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LinkTilesCard(actions: LinkActions) {
    DtnCard(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            actions.snapshot.chips.forEach { chip ->
                val tone = chip.health.tone()
                val hint = when (chip.health) {
                    LinkHealth.ON, LinkHealth.BUSY -> "show details"
                    LinkHealth.OFF -> "turn on"
                    LinkHealth.PROBLEM -> "fix or retry"
                }
                Column(
                    modifier = Modifier.weight(1f).clip(DtnShapes.Button)
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = "${chip.label}: $hint",
                            onLongClickLabel = "${chip.label}: show details",
                            onClick = { actions.onTap(chip.kind) },
                            onLongClick = { actions.showDetails(chip.kind) },
                        )
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(tone.soft()), contentAlignment = Alignment.Center) {
                        Icon(chip.kind.icon(), contentDescription = null, tint = tone.color())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${chip.label} ›", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DtnColors.TextTitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chip.detail, fontSize = 11.sp, color = tone.text(), textAlign = TextAlign.Center, maxLines = 2,
                        lineHeight = 13.sp, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Connect / Disconnect, Sync and Clear buffer… (with today's confirmation). */
@Composable
internal fun LinkButtonsRow(viewModel: DtnViewModel, modifier: Modifier = Modifier) {
    val status by viewModel.transportStatus.collectAsState()
    val buffered by viewModel.bufferedCount.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status.wanted.isEmpty()) {
            PrimaryButton("Connect", onClick = { viewModel.requestConnect() }, modifier = Modifier.weight(1f))
        } else {
            PrimaryButton("Disconnect", onClick = { viewModel.disconnectAll() }, modifier = Modifier.weight(1f))
        }
        TonalButton("Sync", onClick = { viewModel.syncBuffer() }, modifier = Modifier.weight(0.8f))
        DestructiveButton("Clear buffer…", onClick = { confirmClear = true }, modifier = Modifier.weight(1.3f))
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
}

/**
 * The Connection links bottom sheet: each link with its state, purpose, switch and › to its page
 * ([onOpenLinkPage] closes the sheet first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionLinksSheet(viewModel: DtnViewModel, onDismiss: () -> Unit, onOpenLinkPage: (TransportKind) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = DtnShapes.Sheet,
        containerColor = DtnColors.Background,
    ) {
        LinkActionsHost(viewModel, onOpenLinkPage = { kind -> onDismiss(); onOpenLinkPage(kind) }) { actions ->
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Connection links", style = DtnType.PageTitle, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(DtnIcons.Close, contentDescription = "Close", tint = DtnColors.TextTitle)
                    }
                }
                actions.snapshot.chips.forEach { chip -> LinkSheetRow(chip, viewModel.wifiDirectSupported, actions) }
                LinkButtonsRow(viewModel)
                InfoNote(
                    "Clearing the buffer removes queued messages — your unsent ones and the ones you carry for " +
                        "others. They won't be delivered.",
                    kind = NoteKind.DANGER,
                )
                InfoNote("Tip: › opens a link's page: what it's doing, who's on it, and a fresh scan. On the Network tab, " +
                    "long-press a tile for the same.")
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LinkSheetRow(chip: TransportChipModel, wifiDirectSupported: Boolean, actions: LinkActions) {
    val tone = chip.health.tone()
    val supported = chip.kind != TransportKind.WIFI_DIRECT || wifiDirectSupported
    val wanted = actions.snapshot.status.isWanted(chip.kind)
    DtnCard(onClick = { actions.onTap(chip.kind) }, onClickLabel = "${chip.label}: details or retry",
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(tone.soft()), contentAlignment = Alignment.Center) {
                Icon(chip.kind.icon(), contentDescription = null, tint = tone.color())
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(chip.label, style = DtnType.RowTitle)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(tone.color())
                    Spacer(Modifier.width(6.dp))
                    Text(chip.detail, fontSize = 13.sp, color = tone.text(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(chip.kind.purpose(), style = DtnType.Meta, maxLines = 2)
            }
            Switch(
                checked = wanted && supported,
                onCheckedChange = { on -> actions.onSwitch(chip.kind, on) },
                enabled = supported,
                modifier = Modifier.semantics { contentDescription = "${chip.label} on or off" },
            )
            IconButton(onClick = { actions.showDetails(chip.kind) }) {
                Icon(DtnIcons.ChevronRight, contentDescription = "${chip.label} details", tint = DtnColors.TextHint)
            }
        }
    }
}
