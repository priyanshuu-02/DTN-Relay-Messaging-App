package com.dtn.mesh.ui.peers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.components.DeviceRow
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.DtnSearchField
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.MenuAction
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.SegmentedControl
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.components.StatusChip
import com.dtn.mesh.ui.components.Tone
import com.dtn.mesh.ui.relativeSince
import com.dtn.mesh.ui.rememberQrActions
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType

/**
 * Peers: Contacts | Nearby devices. Contacts are the added (not blocked) ones, searchable, with
 * their reachability chip and ⋮ (Open chat / Info / Rename / Delete / Block). Nearby devices has
 * the Discover switch, the phones that announced themselves (Add), and every phone in reach right
 * now. The add-person button scans / uploads a QR or shows My QR.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeersPage(
    viewModel: DtnViewModel,
    onOpenContact: (String) -> Unit,
    onShowMyQr: () -> Unit,
) {
    val peers by viewModel.peerList.collectAsState()
    val deleted by viewModel.deletedContacts.collectAsState()
    val discoverable by viewModel.discoverable.collectAsState()
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var addSheet by rememberSaveable { mutableStateOf(false) }
    val actions = rememberContactActions(viewModel)
    val qr = rememberQrActions(
        prompt = "Scan a contact's QR (My identity on their phone)",
        onResult = { text -> viewModel.handleScannedQr(text) { key -> viewModel.openChat(key) } },
        onProblem = { viewModel.showMessage(it) },
    )
    val deletedIds = deleted.keys
    val contacts = remember(peers, query) { PeerLists.contacts(peers, query) }
    val discovered = remember(peers, deletedIds, query) {
        PeerLists.discovered(peers, deletedIds).filter { PeerLists.matches(it, query) }
    }
    val inRange = remember(peers, deletedIds, query, discovered) {
        val shown = discovered.mapTo(HashSet()) { it.nodeId }
        PeerLists.nearby(peers, deletedIds).filter { it.nodeId !in shown && PeerLists.matches(it, query) }
    }
    fun menu(p: PeerInfo) = actions.menu(p, onOpenChat = { viewModel.openChat(it) }, onInfo = onOpenContact,
        onUnblock = { viewModel.setBlocked(it, false) })

    Box(Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Text("Peers", style = DtnType.PageTitle, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp, start = 4.dp))
                SegmentedControl(listOf("Contacts", "Nearby devices"), segment, { segment = it })
                Spacer(Modifier.height(10.dp))
                DtnSearchField(query, { query = it }, placeholder = if (segment == 0) "Search contacts" else "Search nearby devices")
                Spacer(Modifier.height(10.dp))
            }
            if (segment == 0) {
                if (contacts.isEmpty()) {
                    item {
                        EmptyState(DtnIcons.Peers, if (query.isBlank()) "No contacts yet" else "No matching contacts",
                            "Add someone by scanning their QR (the add-person button), or add a phone that announced itself under Nearby devices.")
                    }
                } else {
                    item {
                        DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                            contacts.forEachIndexed { i, p ->
                                if (i > 0) HorizontalDivider(color = DtnColors.CardBorder)
                                PeerRow(p, onClick = { onOpenContact(p.nodeId) }, menu = menu(p))
                            }
                        }
                    }
                }
            } else {
                item {
                    DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                        SettingRow(
                            "Discover", icon = DtnIcons.Broadcast,
                            subtitle = if (discoverable) "On — phones nearby and on the internet relay can see and add you"
                            else "Off — only your contacts see you; others add you by your QR",
                            checked = discoverable, onCheckedChange = { viewModel.setDiscoverable(it) },
                        )
                    }
                }
                item { SectionHeader("Discovered · ${discovered.size}", Modifier.padding(top = 8.dp)) }
                if (discovered.isEmpty()) {
                    item {
                        Text("Phones that make themselves discoverable show up here, ready to add.", style = DtnType.Meta,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    }
                } else {
                    item {
                        DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                            discovered.forEachIndexed { i, p ->
                                if (i > 0) HorizontalDivider(color = DtnColors.CardBorder)
                                DeviceRow(
                                    name = p.displayName(), subtitle = PeerLists.subtitle(p, ::relativeSince),
                                    initial = UiText.avatarInitial(p.displayName()), avatarColor = DtnColors.avatarFor(p.nodeId),
                                    onClick = { onOpenContact(p.nodeId) }, onClickLabel = "Show ${p.displayName()}",
                                    chip = { PrimaryButton("Add", onClick = { viewModel.addContact(p.nodeId) }) },
                                    menu = menu(p),
                                )
                            }
                        }
                    }
                }
                item { SectionHeader("In range · ${inRange.size}", Modifier.padding(top = 8.dp)) }
                if (inRange.isEmpty()) {
                    item {
                        Text("No other phones in reach right now. Connect on the Network tab to look for them.",
                            style = DtnType.Meta, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                    }
                } else {
                    item {
                        DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                            inRange.forEachIndexed { i, p ->
                                if (i > 0) HorizontalDivider(color = DtnColors.CardBorder)
                                PeerRow(p, onClick = { onOpenContact(p.nodeId) }, menu = menu(p))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
        FloatingActionButton(
            onClick = { addSheet = true },
            containerColor = DtnColors.Primary,
            contentColor = Color.White,
            shape = DtnShapes.Pill,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
        ) { Icon(DtnIcons.PersonAdd, contentDescription = "Add a contact") }
    }

    if (addSheet) {
        ModalBottomSheet(
            onDismissRequest = { addSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = DtnShapes.Sheet,
            containerColor = DtnColors.Background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Add a contact", style = DtnType.PageTitle, modifier = Modifier.padding(bottom = 12.dp))
                DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow("Scan QR", icon = DtnIcons.QrCode, subtitle = "Their QR is under My identity on their phone",
                        onClick = { addSheet = false; qr.scan() })
                    SettingRow("Upload QR", icon = DtnIcons.Copy, subtitle = "A screenshot or shared image of their QR",
                        onClick = { addSheet = false; qr.upload() })
                    SettingRow("My QR", icon = DtnIcons.Peers, subtitle = "Show yours so they can add you",
                        onClick = { addSheet = false; onShowMyQr() })
                }
                Spacer(Modifier.height(12.dp))
                InfoNote("Scanning each other's QR in person also verifies the safety number.")
            }
        }
    }
}

/** A contact / device row with its reachability chip and ⋮ menu. */
@Composable
internal fun PeerRow(p: PeerInfo, onClick: () -> Unit, menu: List<MenuAction>) {
    val reach = p.reach()
    DeviceRow(
        name = p.displayName(),
        subtitle = PeerLists.subtitle(p, ::relativeSince),
        initial = UiText.avatarInitial(p.displayName()),
        avatarColor = DtnColors.avatarFor(p.nodeId),
        onClick = onClick,
        onClickLabel = "Show ${p.displayName()}",
        chip = {
            when {
                p.blocked -> StatusChip("Blocked", Tone.RED)
                p.keysChanged -> StatusChip("Keys changed", Tone.ORANGE)
                else -> StatusChip(reach.chip, reach.tone, icon = reachIcon(reach.kind))
            }
        },
        menu = menu,
    )
}

/** Wi-Fi / tower / globe icon on the chips (a dot otherwise). */
internal fun reachIcon(kind: ReachKind) = when (kind) {
    ReachKind.WIFI_DIRECT -> DtnIcons.WifiDirect
    ReachKind.LORA_HUB, ReachKind.OTHER_HUB -> DtnIcons.LoraHub
    ReachKind.INTERNET -> DtnIcons.Internet
    else -> null
}
