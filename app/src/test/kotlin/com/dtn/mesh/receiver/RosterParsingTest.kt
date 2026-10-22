package com.dtn.mesh.receiver

import com.dtn.mesh.model.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two roster formats: the hub's binary ROSTER frame and the Wi-Fi Direct owner's text roster. */
class RosterParsingTest {

    // ── Hub ROSTER (0x05): [1B n][n × 4B node id] ─────────────────────────

    @Test
    fun hubRoster_parsesEveryNode() {
        val payload = byteArrayOf(2, 0x1e, 0xa5.toByte(), 0x31, 0x58, 0xf6.toByte(), 0xe3.toByte(), 0xb7.toByte(), 0xdf.toByte())
        assertEquals(listOf(NodeId("!1ea53158"), NodeId("!f6e3b7df")), LoRaHubTransportAdapter.parseRoster(payload))
    }

    @Test
    fun hubRoster_emptyHub() {
        assertEquals(emptyList<NodeId>(), LoRaHubTransportAdapter.parseRoster(byteArrayOf(0)))
    }

    @Test
    fun hubRoster_truncatedOrEmpty_isRejected() {
        assertNull(LoRaHubTransportAdapter.parseRoster(ByteArray(0)))
        assertNull(LoRaHubTransportAdapter.parseRoster(byteArrayOf(2, 1, 2, 3, 4, 5)))
    }

    // ── Wi-Fi Direct ROSTER text: "hex@ip,hex@ip" ─────────────────────────

    @Test
    fun wifiRoster_roundTrip() {
        val members = mapOf("!1ea53158" to "192.168.49.1", "!2fab0bf3" to "192.168.49.23")
        val text = WifiDirectTransportAdapter.encodeRoster(members)
        assertEquals("1ea53158@192.168.49.1,2fab0bf3@192.168.49.23", text)
        assertEquals(members, WifiDirectTransportAdapter.parseRoster(text))
    }

    @Test
    fun wifiRoster_skipsMalformedEntries() {
        val parsed = WifiDirectTransportAdapter.parseRoster("1EA53158@10.0.0.1, nope@1.2.3.4,12345678@,zz345678@1.1.1.1,,abc")
        assertEquals(mapOf("!1ea53158" to "10.0.0.1"), parsed)
    }

    @Test
    fun wifiRoster_empty() {
        assertTrue(WifiDirectTransportAdapter.parseRoster("").isEmpty())
    }
}
