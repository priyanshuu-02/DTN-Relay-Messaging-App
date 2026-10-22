package com.dtn.mesh.ui

import com.dtn.mesh.service.MessageLifecycleLog
import org.junit.Assert.assertEquals
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

    @Test
    fun broadcastsSpread_carriedDmsAreCarrying() {
        assertEquals(DeliveryState.SPREADING, deliveryState("SENT", true, true, route(dest = "BROADCAST", next = "aabbccdd")))
        // Broadcasts have no single destination, so they never become "delivered".
        assertEquals(DeliveryState.SPREADING, deliveryState(null, null, true, route(dest = "BROADCAST", delivered = true)))
        assertEquals(DeliveryState.CARRYING, deliveryState(null, null, false, route(next = "bbccddee")))
        assertEquals(DeliveryState.DELIVERED, deliveryState(null, null, false, route(delivered = true)))
    }
}
