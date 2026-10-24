package com.dtn.mesh.ui.devtools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.components.ChipRow
import com.dtn.mesh.ui.components.EmptyState
import com.dtn.mesh.ui.queue.HistoryCard
import com.dtn.mesh.ui.queue.NodeNames
import com.dtn.mesh.ui.queue.deriveBufferHistory
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType

/**
 * Buffer History (`dev/history`): the store-and-forward records (direct messages only) in the
 * Queue's card style — to / from, buffered → left times, how each one left — with All / Delivered
 * / Expired / Dropped chips. A card opens the Message page.
 */
@Composable
internal fun BufferHistoryPage(viewModel: DtnViewModel, onBack: () -> Unit, onOpenMessage: (String) -> Unit) {
    val entries by viewModel.lifecycleEntries.collectAsState()
    val routes by viewModel.messageRoutes.collectAsState()
    val peers by viewModel.peerList.collectAsState()
    val localNodeId by viewModel.localNodeId.collectAsState()
    val myId8 = localNodeId?.takeLast(8)
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    val history = remember(entries) { deriveBufferHistory(entries) }
    val routeById = remember(routes) { routes.associateBy { it.msgId } }
    val names = remember(peers, myId8) { NodeNames(peers, myId8) }
    val counts = remember(history, routeById) { HistoryFilter.entries.associateWith { HistoryFilter.select(history, routeById, it).size } }
    val shown = remember(history, routeById, filter) { HistoryFilter.select(history, routeById, filter) }

    DevPageFrame("Buffer History", onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "chips") {
                ChipRow(HistoryFilter.entries.map { "${it.label} (${counts[it] ?: 0})" }, filter.ordinal, { filter = HistoryFilter.entries[it] })
                Text("Every direct message that went through this phone's buffer, and how it left. Broadcasts and group " +
                    "messages spread until their lifetime ends, so they aren't listed.", style = DtnType.Meta,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp))
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    if (history.isEmpty()) EmptyState(DtnIcons.Storage, "No buffered messages yet",
                        "Every direct message that goes through this phone's buffer is tracked here, with how it left.")
                    else EmptyState(DtnIcons.Storage, "None ${filter.label.lowercase()}", "No record in the buffer history left it this way.")
                }
            } else {
                items(shown, key = { "hist-${it.msgId}" }) { item ->
                    HistoryCard(item, routeById[item.msgId], names, onOpen = { onOpenMessage(item.msgId) })
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
