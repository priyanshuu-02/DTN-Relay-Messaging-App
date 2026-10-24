package com.dtn.mesh.ui.peers

import com.dtn.mesh.ui.PeerInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Block / delete as the Peers and Network lists apply them. */
class PeerListsTest {

    private val keys = "ab".repeat(32)
    private val friend = PeerInfo("!0000000a", isOnline = true, lastSeenMs = 1, customName = "Friend", added = true, onBle = true,
        lastRssi = -60, fingerprintHex = keys)
    private val blocked = PeerInfo("!0000000b", isOnline = true, lastSeenMs = 1, customName = "Blocked", added = true, onBle = true,
        blocked = true, fingerprintHex = keys)
    private val announced = PeerInfo("!0000000c", isOnline = false, lastSeenMs = 1, announceName = "New", fingerprintHex = keys,
        internetOnline = true)
    private val deleted = PeerInfo("!0000000d", isOnline = true, lastSeenMs = 1, announceName = "Gone", fingerprintHex = keys, onBle = true)
    private val hub = PeerInfo("!da64a9b0", isOnline = true, lastSeenMs = 1, longName = "LoRa Hub", isHub = true)
    private val all = listOf(friend, blocked, announced, deleted, hub)

    @Test
    fun blockedPhones_neverShowInContactsNearbyOrDiscovered() {
        assertEquals(listOf("!0000000a"), PeerLists.contacts(all, "").map { it.nodeId })
        assertEquals(listOf("!0000000c", "!0000000d"), PeerLists.discovered(all, deleted = emptySet()).map { it.nodeId }.sorted())
        assertEquals(listOf("!0000000a", "!0000000d", "!0000000c"), PeerLists.nearby(all, deleted = emptySet()).map { it.nodeId })
    }

    @Test
    fun deletedPhones_areHiddenUntilTheyComeBack() {
        val gone = setOf("!0000000d")
        assertEquals(listOf("!0000000c"), PeerLists.discovered(all, gone).map { it.nodeId })
        assertEquals(listOf("!0000000a", "!0000000c"), PeerLists.nearby(all, gone).map { it.nodeId })
    }

    @Test
    fun reachChips() {
        assertEquals("Nearby", friend.reach().chip)
        assertEquals("Internet", announced.reach().chip)
        assertEquals("via LoRa (hub BDB8)", PeerInfo("!0000000e", isOnline = false, lastSeenMs = 1, meshHub = "!da65bdb8").reach().chip)
        assertEquals("Offline", PeerInfo("!0000000f", isOnline = false, lastSeenMs = 0).reach().chip)
        assertEquals("Online · via BLE", PeerLists.headerLine(friend) { "now" })
        assertEquals("Seen now · BLE · -60 dBm", PeerLists.subtitle(friend) { "now" })
    }
}
