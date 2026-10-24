package com.dtn.mesh.ui.shell

import androidx.compose.runtime.Composable
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.chats.ChatsPage
import com.dtn.mesh.ui.network.NetworkPage
import com.dtn.mesh.ui.peers.PeersPage
import com.dtn.mesh.ui.queue.QueuePage

/**
 * Network: the 4 link tiles (today's tap rules; long-press = details), Connect / Sync / Clear
 * buffer…, the phones in reach and Add contact by QR. The old Network body (overview, per-peer
 * scores, route observations) is in Developer Tools › Routing Engine, linked at the bottom.
 */
@Composable
internal fun NetworkTabPage(
    viewModel: DtnViewModel,
    onOpenContact: (String) -> Unit,
    onOpenRoutingEngine: () -> Unit,
    onOpenLinkPage: (TransportKind) -> Unit,
) {
    NetworkPage(viewModel, onOpenContact = onOpenContact, onOpenRoutingEngine = onOpenRoutingEngine, onOpenLinkPage = onOpenLinkPage)
}

/** Chats: search, All / Unread / Groups, pinned Broadcast, the chats, ✎ New message. */
@Composable
internal fun ChatsTabPage(viewModel: DtnViewModel) {
    ChatsPage(viewModel)
}

/**
 * Queue: Pending / Forwarded / Delivered / History (DMs only) and Broadcasts & groups. A buffered
 * message opens the Queued message page (live routing math), the rest the Message page.
 */
@Composable
internal fun QueueTabPage(viewModel: DtnViewModel, onOpenQueued: (String) -> Unit, onOpenMessage: (String) -> Unit) {
    QueuePage(viewModel, onOpenQueued = onOpenQueued, onOpenMessage = onOpenMessage)
}

/** Peers: Contacts | Nearby devices (Discover, discovered phones with Add, phones in range). */
@Composable
internal fun PeersTabPage(viewModel: DtnViewModel, onOpenContact: (String) -> Unit, onShowMyQr: () -> Unit) {
    PeersPage(viewModel, onOpenContact = onOpenContact, onShowMyQr = onShowMyQr)
}
