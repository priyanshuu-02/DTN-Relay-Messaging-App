package com.dtn.mesh.ui.chats

import com.dtn.mesh.ui.ChatMessage
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.GroupInfo
import com.dtn.mesh.ui.PeerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListModelTest {

    private fun msg(id: String, conv: String?, out: Boolean, at: Long, text: String = "hi $id", from: String = conv ?: "",
                    state: DeliveryState = if (out) DeliveryState.QUEUED else DeliveryState.RECEIVED) =
        ChatMessage(msgId = id, text = text, from = if (out) "me" else from, toNodeId = conv, isOutgoing = out,
            status = state.label, time = "", createdAtMs = at, state = state,
            isMultiParty = conv == null || conv.startsWith("grp:"))

    private val alice = PeerInfo(nodeId = "!0000000a", isOnline = false, lastSeenMs = 0, customName = "Alice", added = true)
    private val bob = PeerInfo(nodeId = "!0000000b", isOnline = false, lastSeenMs = 0, customName = "Bob", added = true)
    private val carol = PeerInfo(nodeId = "!0000000c", isOnline = false, lastSeenMs = 0, customName = "Carol", added = true, blocked = true)
    private val stranger = PeerInfo(nodeId = "!0000000d", isOnline = true, lastSeenMs = 0, announceName = "Dave")
    private val hub = PeerInfo(nodeId = "!da64a9b0", isOnline = true, lastSeenMs = 0, longName = "LoRa Hub", added = true, isHub = true)
    private val names: (String) -> String = { id -> listOf(alice, bob, carol, stranger).firstOrNull { it.nodeId == id }?.displayName() ?: id }

    private fun build(messages: List<ChatMessage>, lastRead: Map<String, Long> = emptyMap(), baseline: Long = 0L) =
        ChatListModel.build(messages, listOf(alice, bob, carol, stranger, hub), listOf(GroupInfo(7, "Hikers")),
            lastRead, baseline, names) { "offline" }

    // ── Unread ──────────────────────────────────────────────────────────────

    @Test
    fun unread_countsOnlyIncomingNewerThanLastRead() {
        val list = listOf(msg("1", "!0000000a", out = false, at = 100), msg("2", "!0000000a", out = true, at = 200),
            msg("3", "!0000000a", out = false, at = 300))
        assertEquals(2, ChatListModel.unreadCount(list, lastReadMs = 0))
        assertEquals(1, ChatListModel.unreadCount(list, lastReadMs = 100))
        assertEquals(0, ChatListModel.unreadCount(list, lastReadMs = 300))
        assertEquals(300, ChatListModel.newestIncoming(list))
    }

    @Test
    fun unread_usesTheBaselineWhenAChatWasNeverOpened_andItsOwnLastReadOtherwise() {
        val messages = listOf(msg("1", "!0000000a", false, 100), msg("2", "!0000000a", false, 500), msg("3", "!0000000b", false, 50))
        val items = build(messages, lastRead = mapOf("!0000000b" to 10), baseline = 200).associateBy { it.key }
        assertEquals(1, items.getValue("!0000000a").unread) // only the one after the baseline
        assertEquals(1, items.getValue("!0000000b").unread) // after its own last-read time
    }

    @Test
    fun openingAChat_marksEverythingSoFarRead() {
        val messages = listOf(msg("1", "!0000000a", false, 100), msg("2", "!0000000a", false, 500))
        val read = ChatListModel.newestIncoming(messages)
        assertEquals(0, build(messages, lastRead = mapOf("!0000000a" to read)).first { it.key == "!0000000a" }.unread)
    }

    // ── Rows ────────────────────────────────────────────────────────────────

    @Test
    fun broadcastIsPinned_contactsByLastMessage_blockedHubsAndStrangersLeftOut() {
        val messages = listOf(
            msg("1", "!0000000b", true, 100),
            msg("2", "grp:7", false, 300, from = "!0000000a"),
            msg("3", "!0000000c", false, 400), // blocked
            msg("4", null, false, 50, from = "!0000000d"), // a broadcast
        )
        val keys = build(messages).map { it.key }
        assertEquals(listOf(ChatListModel.BROADCAST_KEY, "grp:7", "!0000000b", "!0000000a"), keys)
    }

    @Test
    fun rowStatus_isTheLastSentMessage_andPreviewsNameTheSender() {
        val messages = listOf(
            msg("1", "!0000000b", false, 100),
            msg("2", "!0000000b", true, 200, text = "on my way", state = DeliveryState.DELIVERED),
            msg("3", "grp:7", false, 300, text = "trail closed", from = "!0000000a"),
        )
        val items = build(messages).associateBy { it.key }
        assertEquals(DeliveryState.DELIVERED, items.getValue("!0000000b").lastOutgoing)
        assertEquals("You: on my way", items.getValue("!0000000b").preview)
        assertNull(items.getValue("grp:7").lastOutgoing)
        assertEquals("Alice: trail closed", items.getValue("grp:7").preview)
        assertEquals("offline", items.getValue("!0000000a").preview) // no messages yet: reachability
    }

    // ── Search + filters ────────────────────────────────────────────────────

    @Test
    fun search_matchesNameMessageOrNodeId() {
        val items = build(listOf(msg("1", "!0000000b", false, 100, text = "meet at the ridge")))
        fun keys(q: String) = ChatListModel.filter(items, q, ChatFilter.ALL).map { it.key }
        assertEquals(listOf("!0000000a"), keys("ali"))
        assertEquals(listOf("!0000000b"), keys("RIDGE"))
        assertEquals(listOf("!0000000b"), keys("0000000b"))
        assertEquals(listOf(ChatListModel.BROADCAST_KEY), keys("broadcast"))
        assertTrue(keys("nobody").isEmpty())
        assertEquals(items.size, keys("  ").size)
    }

    @Test
    fun filters_unreadAndGroups() {
        val items = build(listOf(msg("1", "!0000000b", false, 100), msg("2", "grp:7", true, 200)))
        assertEquals(listOf("!0000000b"), ChatListModel.filter(items, "", ChatFilter.UNREAD).map { it.key })
        assertEquals(listOf("grp:7"), ChatListModel.filter(items, "", ChatFilter.GROUPS).map { it.key })
        assertEquals(items, ChatListModel.filter(items, "", ChatFilter.ALL))
        assertEquals(1, ChatListModel.totalUnread(items))
    }
}
