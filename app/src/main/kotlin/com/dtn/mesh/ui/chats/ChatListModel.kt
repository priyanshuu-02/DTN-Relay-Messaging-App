package com.dtn.mesh.ui.chats

import com.dtn.mesh.service.ContactRules
import com.dtn.mesh.ui.ChatMessage
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.GroupInfo
import com.dtn.mesh.ui.PeerInfo
import com.dtn.mesh.ui.groups.GroupInviteDm

enum class ChatKind { BROADCAST, GROUP, DIRECT }

/** The Chats filter chips. */
enum class ChatFilter(val label: String) { ALL("All"), UNREAD("Unread"), GROUPS("Groups") }

/**
 * One row of the chat list.
 *
 * @property key chat key: [DtnViewModel.BROADCAST_CHAT_ID], "grp:<channel>" or a node id.
 * @property atMs time of the last message (0 = none yet).
 * @property lastOutgoing state of the last message when it was ours (the row's status), else null.
 */
data class ChatListItem(
    val key: String,
    val kind: ChatKind,
    val title: String,
    val preview: String,
    val atMs: Long,
    val unread: Int,
    val lastOutgoing: DeliveryState?,
    val peer: PeerInfo? = null,
)

/** Chats tab logic (pure, unit-tested): rows, unread counts, search and filters. */
object ChatListModel {
    const val BROADCAST_KEY = DtnViewModel.BROADCAST_CHAT_ID

    /** The chat a message belongs to (broadcast rows have no conversation peer). */
    fun chatKeyOf(m: ChatMessage): String = m.toNodeId ?: BROADCAST_KEY

    /** Incoming messages newer than [lastReadMs]. */
    fun unreadCount(messages: List<ChatMessage>, lastReadMs: Long): Int =
        messages.count { !it.isOutgoing && it.createdAtMs > lastReadMs }

    /** The newest incoming message time in [messages] (0 = none): what "read up to" becomes on open. */
    fun newestIncoming(messages: List<ChatMessage>): Long =
        messages.filter { !it.isOutgoing }.maxOfOrNull { it.createdAtMs } ?: 0L

    /**
     * Every chat: the pinned Broadcast first, then groups and contacts by their last message
     * (then reachable, then last seen). Contacts = added or chatted with; blocked ones and hubs
     * never appear. [lastRead] misses fall back to [baselineMs].
     *
     * @param reachText row subtitle for a contact with no messages yet.
     */
    fun build(
        messages: List<ChatMessage>,
        peers: List<PeerInfo>,
        groups: List<GroupInfo>,
        lastRead: Map<String, Long>,
        baselineMs: Long,
        nameOf: (String) -> String,
        reachText: (PeerInfo) -> String,
    ): List<ChatListItem> {
        val byKey = messages.groupBy(::chatKeyOf)
        fun unread(key: String) = unreadCount(byKey[key].orEmpty(), lastRead[key] ?: baselineMs)
        fun item(key: String, kind: ChatKind, title: String, empty: String, peer: PeerInfo? = null): ChatListItem {
            val last = byKey[key]?.lastOrNull()
            return ChatListItem(
                key = key, kind = kind, title = title,
                preview = last?.let { previewOf(it, kind, nameOf) } ?: empty,
                atMs = last?.createdAtMs ?: 0L,
                unread = unread(key),
                lastOutgoing = last?.takeIf { it.isOutgoing }?.state,
                peer = peer,
            )
        }
        val broadcast = item(BROADCAST_KEY, ChatKind.BROADCAST, "Broadcast to All", "Send to every phone in range")
        val groupItems = groups.map { g -> item("grp:${g.channel}", ChatKind.GROUP, g.name, "Encrypted group · no messages yet") }
        val contactItems = peers
            .filter { ContactRules.inContacts(it.nodeId, it.added, byKey.containsKey(it.nodeId), it.isHub, if (it.blocked) setOf(it.nodeId) else emptySet()) }
            .map { p -> item(p.nodeId, ChatKind.DIRECT, p.displayName(), reachText(p), p) }
        val rest = (groupItems + contactItems).sortedWith(
            compareByDescending<ChatListItem> { it.atMs }
                .thenByDescending { it.peer?.reachable == true }
                .thenByDescending { it.peer?.lastSeenMs ?: 0L },
        )
        return listOf(broadcast) + rest
    }

    /** Search (name, node id, last message) + the All / Unread / Groups chip. Broadcast stays pinned first. */
    fun filter(items: List<ChatListItem>, query: String, filter: ChatFilter): List<ChatListItem> {
        val q = query.trim()
        return items.filter { item ->
            val chip = when (filter) {
                ChatFilter.ALL -> true
                ChatFilter.UNREAD -> item.unread > 0
                ChatFilter.GROUPS -> item.kind == ChatKind.GROUP
            }
            chip && (q.isEmpty() || item.title.contains(q, ignoreCase = true) ||
                item.preview.contains(q, ignoreCase = true) ||
                (item.kind == ChatKind.DIRECT && item.key.contains(q, ignoreCase = true)))
        }
    }

    /** "You: …" for ours, "Name: …" in group / broadcast chats, else the text. One line, 60 characters. */
    fun previewOf(m: ChatMessage, kind: ChatKind, nameOf: (String) -> String): String {
        val prefix = when {
            m.isOutgoing -> "You: "
            kind != ChatKind.DIRECT -> nameOf(m.from) + ": "
            else -> ""
        }
        // A group invite DM shows as "Group invite · Family", never with its key.
        return prefix + (GroupInviteDm.preview(m.text) ?: m.text.replace('\n', ' ').take(60))
    }

    /** Total unread over [items] (the Chats tab badge). */
    fun totalUnread(items: List<ChatListItem>): Int = items.sumOf { it.unread }
}
