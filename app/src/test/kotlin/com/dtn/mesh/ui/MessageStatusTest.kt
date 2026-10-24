package com.dtn.mesh.ui

import com.dtn.mesh.service.MessageLifecycleLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageStatusTest {

    private fun route(
        delivered: Boolean = false,
        next: String? = null,
        path: String = "",
        outcome: String = "",
        dest: String = "ffbd889b",
    ) = MessageLifecycleLog.RouteRecord(
        msgId = "1a2b3c4d", source = "58fe1be9", destination = dest, me = "58fe1be9",
        nextHop = next, delivered = delivered, lastPath = path, outcome = outcome,
    )

    @Test
    fun delivered_fromEitherSource_beatsAStaleInTransit() {
        // The bug report: details said IN TRANSIT although the message had been delivered.
        assertEquals(DeliveryState.DELIVERED, deliveryState("DELIVERED", true, false, route(next = "ffbd889b", path = "BLE")))
        // The journal saw the receipt while the chat row still said "sending".
        assertEquals(DeliveryState.DELIVERED, deliveryState("SENDING", true, false, route(delivered = true)))
    }

    @Test
    fun outgoingDm_queuedThenInTransit() {
        assertEquals(DeliveryState.QUEUED, deliveryState("SENDING", true, false, null))
        assertEquals(DeliveryState.QUEUED, deliveryState("SENDING", true, false, route()))
        // Handed to a carrier phone (custody) → on its way.
        assertEquals(DeliveryState.IN_TRANSIT, deliveryState("SENDING", true, false, route(next = "aabbccdd", path = "BLE")))
    }

    @Test
    fun dmPublishedToTheRelay_staysQueuedUntilTheReceipt() {
        // The bug report: the recipient was offline, yet the sender showed "Sent". The relay keeps
        // no copy, so a publish proves nothing; journals from older builds recorded it like this.
        val published = route(next = "ffbd889b", path = "Internet relay").copy(strategy = "INTERNET RELAY")
        assertEquals(DeliveryState.QUEUED, deliveryState("SENDING", true, false, published))
        assertEquals(DeliveryState.DELIVERED, deliveryState("SENDING", true, false, published.copy(delivered = true)))
        // A broadcast published to the relay did reach everyone present.
        val bcast = route(dest = "BROADCAST", path = "Internet relay").copy(strategy = "INTERNET RELAY")
        assertEquals(DeliveryState.SPREADING, deliveryState("SENT", true, true, bcast))
    }

    @Test
    fun terminalStates_neverOverrideDelivery() {
        assertEquals(DeliveryState.EXPIRED, deliveryState("SENDING", true, false, route(outcome = "EXPIRED")))
        assertEquals(DeliveryState.EXPIRED, deliveryState("EXPIRED", true, false, null))
        assertEquals(DeliveryState.DROPPED, deliveryState("DROPPED", true, false, null))
        assertEquals(DeliveryState.DROPPED, deliveryState("FAILED", true, false, null))
        assertEquals(DeliveryState.DELIVERED, deliveryState("DELIVERED", true, false, route(outcome = "EXPIRED")))
    }

    @Test
    fun incoming_isAlwaysReceived() {
        assertEquals(DeliveryState.RECEIVED, deliveryState("RECEIVED", false, false, route(delivered = true)))
        // A carried copy of a received broadcast expiring doesn't change what we received.
        assertEquals(DeliveryState.RECEIVED, deliveryState("EXPIRED", false, true, null))
    }

    @Test
    fun ownBroadcast_isQueuedUntilItsFirstHandOff() {
        assertEquals(DeliveryState.QUEUED, deliveryState("SENT", true, true, route(dest = "BROADCAST")))
        assertEquals(DeliveryState.SPREADING, deliveryState("SENT", true, true, route(dest = "BROADCAST", path = "Internet relay")))
        // Sent by an older version (no journal record): keep showing it as sent.
        assertEquals(DeliveryState.SPREADING, deliveryState("SENT", true, true, null))
    }

    // ── Group messages: the members' receipts ───────────────────────────────

    private fun groupRoute(vararg receipts: String, next: String? = "aabbccdd", outcome: String = "") =
        route(dest = "BROADCAST", next = next, path = if (next != null) "BLE" else "", outcome = outcome).copy(
            channel = 7, groupReceipts = receipts.map { MessageLifecycleLog.GroupReceipt(it, 1_000L, "BLE") },
        )

    @Test
    fun ownGroupMessage_showsWhoOfItsMembersReceivedIt() {
        val known = setOf("aaaa0001", "bbbb0002")
        fun state(r: MessageLifecycleLog.RouteRecord, members: Set<String> = known): DeliveryState {
            val progress = GroupReceiptStatus.progress(GroupReceiptStatus.members(r, members))
            return deliveryState("SENT", true, true, r, progress)
        }
        assertEquals(DeliveryState.SPREADING, state(groupRoute()))               // none yet
        assertEquals(DeliveryState.RECEIVED_BY_SOME, state(groupRoute("aaaa0001")))
        assertEquals(DeliveryState.RECEIVED_BY_ALL, state(groupRoute("aaaa0001", "bbbb0002")))
        // Not handed to anyone yet: still queued, whoever the members are.
        assertEquals(DeliveryState.QUEUED, state(groupRoute(next = null)))
        // No member known yet: spreading, as before.
        assertEquals(DeliveryState.SPREADING, state(groupRoute(), members = emptySet()))
        // Receipts beat expiry (it did reach them), like delivery does for a DM.
        assertEquals(DeliveryState.RECEIVED_BY_SOME, state(groupRoute("aaaa0001", outcome = "EXPIRED")))
        assertEquals(DeliveryState.EXPIRED, state(groupRoute(outcome = "EXPIRED")))
        // A group never becomes "Delivered", and DMs ignore a group count.
        assertEquals(DeliveryState.RECEIVED_BY_ALL, deliveryState("SENT", true, true, groupRoute("aaaa0001").copy(delivered = true),
            GroupProgress(1, 1)))
        assertEquals(DeliveryState.QUEUED, deliveryState("SENDING", true, false, route(), GroupProgress(1, 1)))
        // Without receipts the public broadcast and group messages read as before.
        assertEquals(DeliveryState.SPREADING, deliveryState("SENT", true, true, groupRoute(), null))
    }

    @Test
    fun knownMembers_areInvitedPlusPostersPlusReceiptSenders_minusMeAndBlocked() {
        val me = "0cdc860b"
        val known = GroupReceiptStatus.knownMembers(
            invited = listOf("!aaaa0001", "!$me"),
            posters = listOf("!bbbb0002", "!dddd0004", ""),
            receiptSenders = listOf("cccc0003", "aaaa0001"),
            me8 = me,
            blocked = setOf("!dddd0004"),
        )
        assertEquals(setOf("aaaa0001", "bbbb0002", "cccc0003"), known)

        val byChannel = GroupReceiptStatus.knownByChannel(
            invited = mapOf(7 to setOf("!aaaa0001")),
            posts = listOf(7 to "!bbbb0002", 9 to "!eeee0005", 0 to "!ffff0006"),
            routes = listOf(groupRoute("cccc0003"), route(dest = "ffbd889b")),
            me8 = me,
            blocked = emptySet(),
        )
        assertEquals(setOf("aaaa0001", "bbbb0002", "cccc0003"), byChannel[7])
        assertEquals(setOf("eeee0005"), byChannel[9])
        assertNull(byChannel[0])
    }

    @Test
    fun members_receivedFirst_withTheirReceipt() {
        val r = groupRoute().copy(groupReceipts = listOf(
            MessageLifecycleLog.GroupReceipt("cccc0003", 9_000L, "LoRa hub", "da65bdb8"),
            MessageLifecycleLog.GroupReceipt("bbbb0002", 2_000L, "BLE"),
        ))
        val members = GroupReceiptStatus.members(r, setOf("aaaa0001", "bbbb0002", "cccc0003"))
        assertEquals(listOf("bbbb0002", "cccc0003", "aaaa0001"), members.map { it.member8 })
        assertEquals(listOf(true, true, false), members.map { it.received })
        assertEquals(GroupProgress(2, 3), GroupReceiptStatus.progress(members))
        assertEquals(GroupProgress(0, 0), GroupReceiptStatus.progress(GroupReceiptStatus.members(null, emptySet())))
    }

    @Test
    fun broadcastsSpread_carriedDmsAreCarrying() {
        assertEquals(DeliveryState.SPREADING, deliveryState("SENT", true, true, route(dest = "BROADCAST", next = "aabbccdd")))
        // Broadcasts have no single destination, so they never become "delivered".
        assertEquals(DeliveryState.SPREADING, deliveryState(null, null, true, route(dest = "BROADCAST", delivered = true)))
        assertEquals(DeliveryState.CARRYING, deliveryState(null, null, false, route(next = "bbccddee")))
        assertEquals(DeliveryState.DELIVERED, deliveryState(null, null, false, route(delivered = true)))
    }
}
