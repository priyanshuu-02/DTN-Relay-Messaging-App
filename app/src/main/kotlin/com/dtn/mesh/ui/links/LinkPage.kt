package com.dtn.mesh.ui.links

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.receiver.HubBeacon
import com.dtn.mesh.receiver.HubBeacons
import com.dtn.mesh.receiver.LinkInfo
import com.dtn.mesh.receiver.LinkPeer
import com.dtn.mesh.receiver.LinkScanTimes
import com.dtn.mesh.receiver.MeshConnectionState
import com.dtn.mesh.receiver.RelayStatus
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.routing.RadioDistanceEstimator
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.LinkHealth
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.RelayStatusText
import com.dtn.mesh.ui.TransportChipModel
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.DeviceRow
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.FactRow
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.NoteKind
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.components.StatusChip
import com.dtn.mesh.ui.components.StatusDot
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.components.color
import com.dtn.mesh.ui.components.soft
import com.dtn.mesh.ui.components.text
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.relativeSince
import com.dtn.mesh.ui.relayStatusText
import com.dtn.mesh.ui.shell.LinkActionsHost
import com.dtn.mesh.ui.shell.icon
import com.dtn.mesh.ui.shell.purpose
import com.dtn.mesh.ui.shell.tone
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.delay

/** `transport/{kind}` path segment for a link page. */
fun TransportKind.routeName(): String = name.lowercase()

/** The link for a `transport/{kind}` segment (unknown → Bluetooth). */
fun linkKindOf(segment: String): TransportKind =
    TransportKind.entries.firstOrNull { it.routeName() == segment.lowercase() } ?: TransportKind.BLE

internal fun TransportKind.pageTitle(): String = when (this) {
    TransportKind.BLE -> "Bluetooth"
    TransportKind.WIFI_DIRECT -> "Wi-Fi Direct"
    TransportKind.HUB -> "LoRa hub"
    TransportKind.INTERNET -> "Internet relay"
}

/** One read-only fact on a link page or the Relay Configuration page. */
internal data class Fact(val title: String, val detail: String, val value: String? = null, val on: Boolean = true)

/** How the internet relay connects (the relay already works this way; these pages only show it). */
internal fun relayConnectionFacts(): List<Fact> = listOf(
    Fact("Connection type", "The public broker is tried as TLS 8883, then TCP 1883, then WebSocket 8884, every IPv4 " +
        "address before IPv6. A custom broker URL is used exactly as written.", value = "Automatic"),
    Fact("QoS 1", "The broker gets every bundle at least once. Delivered still needs the end-to-end receipt."),
    Fact("Automatic reconnect", "After a failed attempt it waits 2, 4, 8, 15, 30, then 60 s and tries the next " +
        "endpoint; after 10 in a row it stops until you tap Retry or the network changes. A dropped connection is " +
        "retried after 1 s.", value = "2–60 s"),
    Fact("Waits for internet", "Without internet nothing is tried; it reconnects within seconds when the phone is " +
        "back online or moves to another network. The LoRa hub's Wi-Fi has no internet."),
    Fact("End-to-end encrypted", "The broker sees headers and who is online, never the text of DMs or group messages."),
)

/**
 * A link's page (`transport/{kind}`, replaces the old details dialog): the on/off switch, the live
 * state and last scan, who is on the link (Available hubs with Join on the LoRa hub page), the
 * settings that would break the mesh as read-only facts (decision 7), and a fresh scan.
 */
@Composable
fun LinkPage(
    viewModel: DtnViewModel,
    kind: TransportKind,
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenRelay: () -> Unit,
    onOpenLinkPage: (TransportKind) -> Unit,
) {
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val peers by viewModel.peerList.collectAsState()
    val now by rememberClock()
    LinkActionsHost(viewModel, onOpenLinkPage = { if (it != kind) onOpenLinkPage(it) }) { links ->
        val chip = links.snapshot.chip(kind)
        val info = links.snapshot.linkInfo[kind] ?: LinkInfo()
        val supported = kind != TransportKind.WIFI_DIRECT || viewModel.wifiDirectSupported
        val wanted = links.snapshot.status.isWanted(kind)
        val times = remember(now) { viewModel.linkScanTimes() }
        val byId = remember(peers) { peers.associateBy { it.nodeId } }
        Scaffold(
            containerColor = DtnColors.Background,
            snackbarHost = { SnackbarHost(snackbars) },
            topBar = { InnerTopBar(kind.pageTitle(), onBack = onBack, subtitle = "Connection links") },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") {
                    Spacer(Modifier.height(4.dp))
                    HeaderCard(kind, chip, checked = wanted && supported, enabled = supported) { on -> links.onSwitch(kind, on) }
                }
                val relayText = if (kind == TransportKind.INTERNET) {
                    relayStatusText(links.snapshot.relay, info.peers.size, now)
                } else null
                item(key = "status") { StateCard(kind, chip, info, times, wanted, supported, viewModel, relayText) }
                when (kind) {
                    TransportKind.BLE -> item(key = "devices") {
                        PeopleCard("Nearby devices", info.peers, byId, onOpenContact, emptyText =
                            if (wanted) "No DTN phones in Bluetooth range right now." else "Bluetooth is off for the mesh. Switch it on to find nearby phones.") { p, peer ->
                            val rssi = peer?.lastRssi?.takeIf { it > -200 && it != 0 }
                            val dist = rssi?.let { RadioDistanceEstimator.estimateMeters(it) }?.takeIf { it > 0 }
                            listOfNotNull(
                                peer?.let { "Seen ${relativeSince(it.lastSeenMs)}" },
                                rssi?.let { "$it dBm" } ?: p.detail.takeIf { it.isNotBlank() },
                                dist?.let { "~%.0f m".format(it) },
                            ).joinToString(" · ")
                        }
                    }
                    TransportKind.WIFI_DIRECT -> item(key = "devices") {
                        PeopleCard("Linked phones", info.peers, byId, onOpenContact, emptyText = when {
                            !supported -> "Wi-Fi Direct groups need Android 10 or newer on this phone."
                            wanted -> "No phones linked yet. DTN phones in range form one group by themselves."
                            else -> "Wi-Fi Direct is off for the mesh."
                        }) { p, _ -> listOf(p.role, p.detail).filter { it.isNotBlank() }.joinToString(" · ") }
                    }
                    TransportKind.HUB -> {
                        item(key = "connected-hub") { ConnectedHubCard(viewModel, info, byId, onOpenContact) }
                        item(key = "hubs") { AvailableHubsCard(viewModel, wanted, links.snapshot.radios.bluetoothOn, now) }
                    }
                    TransportKind.INTERNET -> {
                        item(key = "devices") {
                            PeopleCard("Contacts on the relay", info.peers, byId, onOpenContact, emptyText =
                                if (wanted) "None of your contacts is on the relay right now." else "The internet relay is off.") { _, _ ->
                                "Online on the relay"
                            }
                        }
                        item(key = "server") { RelayServerCard(viewModel, onOpenRelay) }
                    }
                }
                item(key = "facts") { FactsCard(kind, info, viewModel) }
                item(key = "refresh") {
                    val relay = links.snapshot.relay
                    val label = when (kind) {
                        TransportKind.HUB -> "Retry scan"
                        TransportKind.INTERNET -> "Retry now"
                        else -> "Refresh scan"
                    }
                    // The relay retries by itself; Retry now only means something while it waits to retry or gave up.
                    val canRun = if (kind == TransportKind.INTERNET) relay is RelayStatus.Retrying || relay is RelayStatus.GaveUp else true
                    PrimaryButton(label, onClick = { viewModel.refreshLink(kind) }, enabled = wanted && supported && canRun,
                        modifier = Modifier.fillMaxWidth())
                    val hint = when {
                        !supported -> null
                        !wanted -> if (kind == TransportKind.INTERNET) "Switch the internet relay on to connect."
                            else "Switch ${kind.pageTitle()} on to scan."
                        kind != TransportKind.INTERNET -> null
                        relay is RelayStatus.WaitingForInternet -> "Nothing to retry until this phone has internet; then it reconnects by itself."
                        relay is RelayStatus.Connecting -> "An attempt is running now."
                        relay is RelayStatus.Connected -> "Connected: nothing to retry."
                        else -> null
                    }
                    if (hint != null) {
                        Text(hint, style = DtnType.Meta, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
                    }
                }
                item(key = "note") {
                    InfoNote(kind.note(supported))
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** Ticks once a second, so "last scan 12 s ago" stays current. */
@Composable
private fun rememberClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(1_000L)
        value = System.currentTimeMillis()
    }
}

/** Big icon, name, purpose and the on/off switch. */
@Composable
private fun HeaderCard(kind: TransportKind, chip: TransportChipModel, checked: Boolean, enabled: Boolean, onSwitch: (Boolean) -> Unit) {
    val tone = chip.health.tone()
    DtnCard(contentPadding = PaddingValues(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(tone.soft()), contentAlignment = Alignment.Center) {
                Icon(kind.icon(), contentDescription = null, tint = tone.color(), modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(kind.pageTitle(), style = DtnType.RowTitle.copy(fontSize = 18.sp))
                Text(kind.purpose(), style = DtnType.RowSubtitle)
            }
            Switch(
                checked = checked, onCheckedChange = onSwitch, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "${kind.pageTitle()} on or off" },
            )
        }
    }
}

/**
 * Dot + bold state, the link's own summary, and when it last looked around. Red-tinted on a problem.
 * The relay's state, its detail line and the countdown come from [relayText] (the same model every
 * relay surface uses).
 */
@Composable
private fun StateCard(
    kind: TransportKind,
    chip: TransportChipModel,
    info: LinkInfo,
    times: LinkScanTimes,
    wanted: Boolean,
    supported: Boolean,
    viewModel: DtnViewModel,
    relayText: RelayStatusText? = null,
) {
    if (relayText != null) {
        val tone = relayText.health.tone()
        DtnCard(color = if (relayText.health == LinkHealth.PROBLEM) DtnColors.RedSoft else DtnColors.Card) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(tone.color())
                Spacer(Modifier.width(8.dp))
                Text(relayText.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tone.text(), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Text(relayText.detail, style = DtnType.RowSubtitle.copy(color = DtnColors.TextTitle))
        }
        return
    }
    val tone = chip.health.tone()
    val relayDiagnostics by viewModel.relayDiagnostics.collectAsState()
    val requestedSsid by viewModel.requestedHubSsid.collectAsState()
    val scanLine = when {
        !supported -> "Not available on this phone"
        !wanted -> "Off · not scanning"
        kind == TransportKind.BLE ->
            if (times.bleLastResultMs > 0) "Scanning continuously · last result ${relativeSince(times.bleLastResultMs)}"
            else "Scanning continuously · nothing heard yet"
        kind == TransportKind.WIFI_DIRECT ->
            (if (times.wifiDirectLastDiscoveryMs > 0) "Last scan ${relativeSince(times.wifiDirectLastDiscoveryMs)}" else "No scan yet") +
                " · every ${times.wifiDirectIntervalMs / 1000} s while not linked"
        kind == TransportKind.HUB -> listOf(
            if (times.hubLastBeaconMs > 0) "Last hub beacon ${relativeSince(times.hubLastBeaconMs)}" else "No hub beacon heard yet",
            if (times.hubLastJoinRequestMs > 0) "asked for ${requestedSsid ?: "any DTN-HUB-*"} ${relativeSince(times.hubLastJoinRequestMs)}" else null,
        ).filterNotNull().joinToString(" · ")
        else -> relayDiagnostics.ifBlank { "No relay attempts yet" }
    }
    DtnCard(color = if (chip.health == LinkHealth.PROBLEM) DtnColors.RedSoft else DtnColors.Card) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(tone.color())
            Spacer(Modifier.width(8.dp))
            Text(chip.detail, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tone.text(), modifier = Modifier.weight(1f))
        }
        if (info.summary.isNotBlank() && info.summary != chip.detail) {
            Spacer(Modifier.height(4.dp))
            Text(info.summary, style = DtnType.RowSubtitle.copy(color = DtnColors.TextTitle))
        }
        Spacer(Modifier.height(4.dp))
        Text(scanLine, style = DtnType.Meta)
    }
}

/** A titled card of phones on a link: name, a detail line, and a tap to their contact page. */
@Composable
private fun PeopleCard(
    title: String,
    linkPeers: List<LinkPeer>,
    byId: Map<String, PeerInfo>,
    onOpenContact: (String) -> Unit,
    emptyText: String,
    detail: (LinkPeer, PeerInfo?) -> String,
) {
    DtnCard(contentPadding = PaddingValues(top = 12.dp, bottom = 4.dp)) {
        Text("$title · ${linkPeers.size}", style = DtnType.RowTitle, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(4.dp))
        if (linkPeers.isEmpty()) {
            Text(emptyText, style = DtnType.RowSubtitle, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
        linkPeers.forEach { lp -> PersonRow(lp, byId[lp.nodeId], onOpenContact, detail(lp, byId[lp.nodeId])) }
    }
}

@Composable
private fun PersonRow(lp: LinkPeer, peer: PeerInfo?, onOpenContact: (String) -> Unit, subtitle: String) {
    HorizontalDivider(color = DtnColors.CardBorder)
    val name = peer?.displayName() ?: lp.nodeId.takeLast(8)
    DeviceRow(
        name = name,
        subtitle = subtitle.ifBlank { lp.nodeId.takeLast(8) },
        initial = UiText.avatarInitial(name),
        avatarColor = DtnColors.avatarFor(lp.nodeId),
        chip = { StatusChip(lp.role.ifBlank { "Linked" }.substringBefore(" · "), Tone.GREEN) },
        onClick = if (peer != null && !peer.isHub) ({ onOpenContact(lp.nodeId) }) else null,
        onClickLabel = "Open contact",
        // Bluetooth: what sending to this phone did lately (sent, last OK, last error).
        detail = lp.stats.ifBlank { null },
    )
}

/** The hub we're on: its id and Wi-Fi name, the phones on it and the phones on the hubs it hears. */
@Composable
private fun ConnectedHubCard(viewModel: DtnViewModel, info: LinkInfo, byId: Map<String, PeerInfo>, onOpenContact: (String) -> Unit) {
    val hubId by viewModel.connectedHubId.collectAsState()
    val onHub = info.peers.filter { it.role == "On this hub" }
    val overLoRa = info.peers - onHub.toSet()
    DtnCard(contentPadding = PaddingValues(top = 12.dp, bottom = 4.dp)) {
        Text("Connected hub", style = DtnType.RowTitle, modifier = Modifier.padding(horizontal = 16.dp))
        val id = hubId
        if (id == null) {
            Text("Not on a hub right now.", style = DtnType.RowSubtitle, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
            return@DtnCard
        }
        SettingRow(
            com.dtn.mesh.receiver.hubLabel(id), icon = DtnIcons.LoraHub,
            subtitle = "Wi-Fi ${HubBeacons.ssidFor(id)} · id ${id.takeLast(8)}",
            iconTint = DtnColors.Green, iconBackground = DtnColors.GreenSoft,
        )
        Text("On this hub · ${onHub.size}", style = DtnType.Meta.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        onHub.forEach { PersonRow(it, byId[it.nodeId], onOpenContact, "Through this hub") }
        Text("On other hubs over LoRa · ${overLoRa.size}", style = DtnType.Meta.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp))
        overLoRa.forEach { PersonRow(it, byId[it.nodeId], onOpenContact, it.role) }
        if (onHub.isEmpty() && overLoRa.isEmpty()) {
            Text("No other phones on this hub or on the hubs it hears.", style = DtnType.RowSubtitle,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
    }
}

/**
 * Available hubs (decision 8): every hub whose Bluetooth beacon this phone hears, strongest first,
 * the best one marked, with its phones, DMs waiting, its LoRa link to the other hubs and when it
 * was last heard. Join asks Android for exactly that hub's Wi-Fi.
 */
@Composable
private fun AvailableHubsCard(viewModel: DtnViewModel, hubWanted: Boolean, bluetoothOn: Boolean, now: Long) {
    val hubs by viewModel.availableHubs.collectAsState()
    val connected by viewModel.connectedHubId.collectAsState()
    val requested by viewModel.requestedHubSsid.collectAsState()
    val status by viewModel.transportStatus.collectAsState()
    val best = remember(hubs, now) { HubBeacons.strongest(hubs, now)?.hubId }
    val bleScanning = status.stateOf(TransportKind.BLE) == MeshConnectionState.CONNECTED
    DtnCard(contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
            Text("Available hubs · ${hubs.size}", style = DtnType.RowTitle, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.refreshLink(TransportKind.HUB) }) {
                Icon(DtnIcons.Refresh, contentDescription = "Retry scan for hubs", tint = DtnColors.Primary)
            }
        }
        if (hubs.isEmpty()) {
            Text(
                when {
                    !bluetoothOn -> "Bluetooth is off. Hubs announce themselves over Bluetooth, so none can be listed. " +
                        "The phone still joins any DTN-HUB network."
                    !bleScanning -> "Switch Bluetooth on (Connection links) to hear the hubs' beacons. Without one, the " +
                        "phone joins any DTN-HUB network."
                    else -> "No hub beacon heard. Hubs with firmware v4 announce themselves over Bluetooth; move closer " +
                        "to one. Without a beacon the phone joins any DTN-HUB network."
                },
                style = DtnType.RowSubtitle, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        hubs.forEach { hub ->
            HorizontalDivider(color = DtnColors.CardBorder)
            HubRow(
                hub = hub, best = hub.hubId == best, connected = hub.hubId == connected,
                joining = hubWanted && connected != hub.hubId && requested == hub.ssid,
                onJoin = { viewModel.joinHub(hub.hubId) },
            )
        }
    }
}

@Composable
private fun HubRow(hub: HubBeacon, best: Boolean, connected: Boolean, joining: Boolean, onJoin: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignalBars(HubBeacons.bars(hub.rssi), modifier = Modifier.clearAndSetSemantics {
            contentDescription = "Signal ${HubBeacons.bars(hub.rssi)} of 4"
        })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hub.label, style = DtnType.RowTitle, maxLines = 1)
                if (best) {
                    Spacer(Modifier.width(6.dp))
                    StatusChip("Best signal", Tone.GREEN)
                }
            }
            val phones = if (hub.phones == 1) "1 phone" else "${hub.phones} phones"
            val dms = if (hub.dmsWaiting == 1) "1 DM waiting" else "${hub.dmsWaiting} DMs waiting"
            Text("${hub.rssi} dBm · $phones · $dms", style = DtnType.RowSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (hub.links.isEmpty()) "LoRa: hears no other hub"
                else "LoRa: " + hub.links.joinToString { l ->
                    l.label + (l.rssiDbm?.let { " $it dBm" } ?: "") + " · SNR " + "%.1f".format(l.snrDb) + " dB"
                },
                style = DtnType.Meta, maxLines = 2,
            )
            Text("Heard ${relativeSince(hub.heardAtMs)} · firmware v${hub.firmware} · Wi-Fi ${hub.ssid}", style = DtnType.Meta, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        when {
            connected -> StatusChip("Connected", Tone.GREEN)
            joining -> StatusChip("Joining…", Tone.ORANGE)
            else -> Button(
                onClick = onJoin,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DtnColors.Primary),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Join ${hub.label}" },
            ) { Text("Join", fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Four rising bars in a 40 dp soft square; [level] of them filled. */
@Composable
private fun SignalBars(level: Int, modifier: Modifier = Modifier) {
    val tint = when {
        level >= 3 -> DtnColors.Green
        level == 2 -> DtnColors.Orange
        else -> DtnColors.Red
    }
    Box(modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(DtnColors.PrimarySoft), contentAlignment = Alignment.Center) {
        Row(Modifier.height(20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (1..4).forEach { i ->
                Box(Modifier.width(4.dp).fillMaxHeight(i / 4f).clip(RoundedCornerShape(1.dp))
                    .background(if (i <= level) tint else DtnColors.CardBorder))
            }
        }
    }
}

/** The relay server in use, with the way to its settings. */
@Composable
private fun RelayServerCard(viewModel: DtnViewModel, onOpenRelay: () -> Unit) {
    val broker by viewModel.brokerUrl.collectAsState()
    DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        SettingRow("Relay server", icon = DtnIcons.Internet, subtitle = broker)
        SettingRow("Manage relay settings", icon = DtnIcons.Settings, subtitle = "Broker address, Test connection, how it connects",
            onClick = onOpenRelay)
    }
}

/** The link's behaviour as read-only facts. */
@Composable
private fun FactsCard(kind: TransportKind, info: LinkInfo, viewModel: DtnViewModel) {
    val hubs by viewModel.availableHubs.collectAsState()
    val facts = when (kind) {
        TransportKind.BLE -> listOf(
            Fact("Scanning", "Always on while Bluetooth runs, at low latency: finds DTN phones and the LoRa hubs' beacons."),
            Fact("Advertising", "This phone's id and Wi-Fi Direct hint, so nearby DTN phones find it without pairing."),
            Fact("Receiving bundles", "Phones write bundles to this phone directly; nobody has to pair or accept anything."),
            Fact("Sending", "One Bluetooth link per phone, reused for every bundle while it is up. A failed connect is " +
                "retried once at once; each phone's line shows what was sent and the last error."),
            Fact("Offline after 20 s", "A phone not heard for 20 s is marked offline; Refresh checks with a 3 s limit.", on = false),
            Fact("Hub beacons", "${hubs.size} heard. Hubs are listed on the LoRa hub page; they are never phone peers.", on = false),
        )
        TransportKind.WIFI_DIRECT -> buildList {
            add(Fact("Discover", "Looks for DTN phones every 12 s while not in a group (a linked phone doesn't scan)."))
            add(Fact("Be found", "Advertises over Wi-Fi Direct service discovery and the Bluetooth hint."))
            add(Fact("No invitations", "Groups are joined with credentials derived from the hint, so nobody taps Accept."))
            info.facts.firstOrNull { it.startsWith("Group ") }?.let { add(Fact("Group", it.removePrefix("Group "), on = false)) }
        }
        TransportKind.HUB -> listOf(
            Fact("Auto-join", "Asks for the strongest hub heard over Bluetooth; with none heard, any DTN-HUB-* network. " +
                "Join picks one yourself."),
            Fact("Hub Wi-Fi", "WPA2 network without internet, so mobile data stays on. Hub at 192.168.4.1:9740.", on = false),
            Fact("Keep-alive", "A ping every 30 s keeps the hub link up."),
            Fact("Store and forward", "The hub keeps bundles for phones that aren't connected and floods them over LoRa to other hubs."),
        )
        TransportKind.INTERNET -> relayConnectionFacts()
    }
    SectionHeader("How it works")
    DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        facts.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(color = DtnColors.CardBorder, modifier = Modifier.padding(horizontal = 12.dp))
            FactRow(f.title, f.detail, value = f.value, on = f.on)
        }
    }
}

private fun TransportKind.note(supported: Boolean): String = when (this) {
    TransportKind.BLE -> "Bluetooth carries messages phone to phone over a few tens of metres. These behaviours can't be " +
        "switched off one by one: without them nearby phones couldn't find each other."
    TransportKind.WIFI_DIRECT -> if (!supported) "Wi-Fi Direct groups need Android 10 or newer. Bluetooth, the LoRa hub and the " +
        "internet relay still work." else "Wi-Fi Direct links nearby DTN phones in one fast group. It needs Wi-Fi switched on, " +
        "not a Wi-Fi network."
    TransportKind.HUB -> "Join asks Android for that hub's own Wi-Fi (DTN-HUB-XXXX, the last 4 hex digits of its id). " +
        "Android may ask you to confirm the first time."
    TransportKind.INTERNET -> "When this is useful: a contact is far away and both phones have internet. Nearby phones and " +
        "LoRa hubs work without it, and messages stay end-to-end encrypted."
}
