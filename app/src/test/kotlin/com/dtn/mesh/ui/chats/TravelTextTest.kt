package com.dtn.mesh.ui.chats

import com.dtn.mesh.service.MessageLifecycleLog
import com.dtn.mesh.service.MessageLifecycleLog.RouteRecord
import com.dtn.mesh.ui.DeliveryState
import com.dtn.mesh.ui.GroupProgress
import com.dtn.mesh.ui.MemberReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TravelTextTest {

    private fun route(path: String, prev: String? = null, next: String? = null) =
        RouteRecord(msgId = "abcd1234", source = "0000000a", destination = "0000000b", prevHop = prev, me = "0000000b",
            nextHop = next, lastPath = path)

    @Test
    fun received_namesTheLink_andTheHubThatHandedItOver() {
        assertEquals("Received via BLE", TravelText.travelLine(false, DeliveryState.RECEIVED, route("BLE", prev = "0000000a")))
        assertEquals("Received via Wi-Fi Direct", TravelText.travelLine(false, DeliveryState.RECEIVED, route("Wi-Fi Direct")))
        assertEquals("Received via LoRa hub (hub A9B0)",
            TravelText.travelLine(false, DeliveryState.RECEIVED, route("LoRa hub", prev = "da64a9b0")))
        assertEquals("Received via Internet relay", TravelText.travelLine(false, DeliveryState.RECEIVED, route("Internet relay")))
        assertNull(TravelText.travelLine(false, DeliveryState.RECEIVED, null))
    }

    @Test
    fun sent_saysDeliveredOrSent_viaTheLastLink() {
        assertEquals("Delivered via BLE", TravelText.travelLine(true, DeliveryState.DELIVERED, route("BLE", next = "0000000b")))
        // Handed to our hub for a phone on the same hub: just "LoRa hub".
        assertEquals("Sent via LoRa hub", TravelText.travelLine(true, DeliveryState.IN_TRANSIT, route("LoRa hub", next = "0000000b")))
        // Handed to a hub that floods it on.
        assertEquals("Sent via LoRa hub (hub BDB8)",
            TravelText.travelLine(true, DeliveryState.IN_TRANSIT, route("LoRa hub flood", next = "da65bdb8")))
        assertEquals("Delivered", TravelText.travelLine(true, DeliveryState.DELIVERED, null))
        assertEquals("Expired before it could be delivered", TravelText.travelLine(true, DeliveryState.EXPIRED, null))
    }

    @Test
    fun groupBubble_countsTheMembersWhoReceivedIt() {
        assertEquals("✓ Received by 1 of 2", TravelText.bubbleStatus(DeliveryState.RECEIVED_BY_SOME, "BLE", GroupProgress(1, 2)))
        assertEquals("✓✓ Received by all (2)", TravelText.bubbleStatus(DeliveryState.RECEIVED_BY_ALL, "BLE", GroupProgress(2, 2)))
        assertEquals("✓ Spreading", TravelText.bubbleStatus(DeliveryState.SPREADING, "BLE", GroupProgress(0, 0)))
        assertEquals("✓ Received by 0 of 2", TravelText.bubbleStatus(DeliveryState.SPREADING, "BLE", GroupProgress(0, 2)))
        assertEquals("🕓 Queued", TravelText.bubbleStatus(DeliveryState.QUEUED, "", GroupProgress(0, 2)))
        assertEquals("! Expired", TravelText.bubbleStatus(DeliveryState.EXPIRED, "", GroupProgress(0, 2)))
        // The public broadcast has no receipts: unchanged.
        assertEquals("✓ Sent via BLE", TravelText.bubbleStatus(DeliveryState.SPREADING, "BLE"))
    }

    @Test
    fun memberLines_andTheCount() {
        val time: (Long) -> String = { "T$it" }
        assertEquals("✓ T5 · via LoRa hub (hub BDB8)",
            TravelText.memberLine(MemberReceipt("aaaa0001", MessageLifecycleLog.GroupReceipt("aaaa0001", 5L, "LoRa hub", "da65bdb8")), time))
        assertEquals("✓ T5 · via BLE", TravelText.memberLine(MemberReceipt("aaaa0001", MessageLifecycleLog.GroupReceipt("aaaa0001", 5L, "BLE", "aaaa0001")), time))
        assertEquals("✓ T5 · via Internet relay",
            TravelText.memberLine(MemberReceipt("aaaa0001", MessageLifecycleLog.GroupReceipt("aaaa0001", 5L, "Internet relay")), time))
        assertEquals("Not yet", TravelText.memberLine(MemberReceipt("bbbb0002"), time))
        assertEquals("1 of 2 members", TravelText.progressLine(GroupProgress(1, 2)))
        assertEquals("All 2 members", TravelText.progressLine(GroupProgress(2, 2)))
        assertEquals("No members known yet", TravelText.progressLine(GroupProgress(0, 0)))
    }

    @Test
    fun theFallback_isNamedOnTheLinkItUsed() {
        val relayed = route("").copy(fallbackVia = "Internet relay", fallbackAfter = "BLE")
        // Published to the relay: still queued (the relay stores nothing) until the receipt.
        assertEquals("Sent via Internet relay (fallback after BLE) — no delivery receipt yet",
            TravelText.travelLine(true, DeliveryState.QUEUED, relayed))
        assertEquals("Delivered via Internet relay (fallback after BLE)",
            TravelText.travelLine(true, DeliveryState.DELIVERED, relayed.copy(lastPath = "Internet relay")))
        // Handed to a hub that floods it.
        val hubbed = route("LoRa hub", next = "da65bdb8").copy(fallbackVia = "LoRa hub", fallbackAfter = "BLE")
        assertEquals("Sent via LoRa hub (hub BDB8, fallback after BLE)", TravelText.travelLine(true, DeliveryState.IN_TRANSIT, hubbed))
        // It reached the phone over BLE after all: no fallback note on that link.
        assertEquals("Delivered via BLE", TravelText.travelLine(true, DeliveryState.DELIVERED, relayed.copy(lastPath = "BLE")))
        assertEquals("Not sent yet — waiting in this phone's buffer for a link", TravelText.travelLine(true, DeliveryState.QUEUED, route("")))
    }

    @Test
    fun bubbleStatusLine() {
        assertEquals("✓✓ Delivered via BLE", TravelText.bubbleStatus(DeliveryState.DELIVERED, "BLE"))
        assertEquals("✓ Sent via LoRa hub", TravelText.bubbleStatus(DeliveryState.IN_TRANSIT, "LoRa hub flood"))
        assertEquals("🕓 Queued", TravelText.bubbleStatus(DeliveryState.QUEUED, ""))
        assertEquals("! Expired", TravelText.bubbleStatus(DeliveryState.EXPIRED, "BLE"))
        assertEquals("", TravelText.bubbleStatus(DeliveryState.RECEIVED, "BLE"))
    }
}
