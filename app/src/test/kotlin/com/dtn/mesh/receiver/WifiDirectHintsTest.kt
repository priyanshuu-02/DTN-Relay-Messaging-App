package com.dtn.mesh.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectHintsTest {

    @Test
    fun encode_startsWithTheNodeIdSoOlderBuildsStillReadIt() {
        val data = WifiDirectHints.encode(0x1ea53158, WifiDirectHints.Local())
        // 10 bytes since fix 2 (the group channel): 2 + 16 + 10 = 28 B scan response, under the 31 B limit.
        assertEquals(10, data.size)
        assertEquals(listOf(0x1e, 0xa5, 0x31, 0x58), data.take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun theOwnersChannelTravels_andOlderNineByteAdvertsMeanUnknown() {
        val local = WifiDirectHints.Local(active = true, ownerNonce = 9, members = 1, channel = WifiDirectHints.channelOf(5745))
        val d = WifiDirectHints.decode(WifiDirectHints.encode(42, local))!!
        assertEquals(149, d.channel)
        assertEquals(5745, WifiDirectHints.frequencyOf(d.channel))
        // A build before fix 2: 9 bytes, no channel → scan every channel.
        assertEquals(0, WifiDirectHints.decode(WifiDirectHints.encode(42, local).copyOf(9))!!.channel)
        // A phone that owns no group never advertises one.
        assertEquals(0, WifiDirectHints.decode(WifiDirectHints.encode(42, WifiDirectHints.Local(active = true, channel = 6)))!!.channel)
    }

    @Test
    fun channelMapping_coversBothBands_andRejectsTheRest() {
        assertEquals(1, WifiDirectHints.channelOf(2412))
        assertEquals(6, WifiDirectHints.channelOf(2437))
        assertEquals(14, WifiDirectHints.channelOf(2484))
        assertEquals(36, WifiDirectHints.channelOf(5180))
        assertEquals(165, WifiDirectHints.channelOf(5825))
        assertEquals(0, WifiDirectHints.channelOf(5955)) // 6 GHz: not encoded
        assertEquals(0, WifiDirectHints.channelOf(0))
        for (f in listOf(2412, 2437, 2462, 2484, 5180, 5240, 5745, 5825)) {
            assertEquals(f, WifiDirectHints.frequencyOf(WifiDirectHints.channelOf(f)))
        }
        assertEquals(0, WifiDirectHints.frequencyOf(0))
        assertEquals(0, WifiDirectHints.frequencyOf(200))
    }

    @Test
    fun roundTrip_owner() {
        val local = WifiDirectHints.Local(active = true, ownerNonce = 0x7f00ab12, members = 3)
        val d = WifiDirectHints.decode(WifiDirectHints.encode(42, local))!!
        assertTrue(d.active)
        assertTrue(d.owner)
        assertEquals(0x7f00ab12, d.nonce)
        assertEquals(3, d.members)
    }

    @Test
    fun roundTrip_searchingPhone_isNotAnOwner() {
        val d = WifiDirectHints.decode(WifiDirectHints.encode(42, WifiDirectHints.Local(active = true)))!!
        assertTrue(d.active)
        assertFalse(d.owner)
        assertEquals(0, d.members)
    }

    @Test
    fun inactive_neverClaimsOwnership() {
        val d = WifiDirectHints.decode(WifiDirectHints.encode(42, WifiDirectHints.Local(active = false, ownerNonce = 5)))!!
        assertFalse(d.active)
        assertFalse(d.owner)
    }

    @Test
    fun members_areCappedAt15() {
        val d = WifiDirectHints.decode(WifiDirectHints.encode(42, WifiDirectHints.Local(active = true, ownerNonce = 1, members = 40)))!!
        assertEquals(15, d.members)
        assertTrue(d.owner)
    }

    @Test
    fun negativeNonce_survivesTheRoundTrip() {
        val d = WifiDirectHints.decode(WifiDirectHints.encode(42, WifiDirectHints.Local(active = true, ownerNonce = -123456)))!!
        assertEquals(-123456, d.nonce)
    }

    @Test
    fun decode_legacyNodeIdOnlyAdvert_isNull() {
        assertNull(WifiDirectHints.decode(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun networkName_hasTheDirectPrefixAndALowercaseId() {
        assertEquals("DIRECT-DT-1ea53158", WifiDirectHints.networkName("1EA53158"))
        assertTrue(WifiDirectHints.networkName("1ea53158").startsWith("DIRECT-"))
    }

    @Test
    fun passphrase_isDeterministicAndDependsOnOwnerAndNonce() {
        val p = WifiDirectHints.passphrase("1ea53158", 7)
        assertEquals(20, p.length)
        assertTrue(p.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(p, WifiDirectHints.passphrase("1EA53158", 7))
        assertNotEquals(p, WifiDirectHints.passphrase("1ea53158", 8))
        assertNotEquals(p, WifiDirectHints.passphrase("2fab0bf3", 7))
    }

    @Test
    fun recent_dropsStaleSightings() {
        val hints = WifiDirectHints()
        hints.onBleSighting("aaaaaaaa", WifiDirectHints.Decoded(active = true, owner = false, nonce = 0, members = 0), nowMs = 1_000L)
        hints.onBleSighting("bbbbbbbb", null, nowMs = 50_000L)
        val fresh = hints.recent(nowMs = 60_000L, maxAgeMs = 30_000L)
        assertEquals(listOf("bbbbbbbb"), fresh.map { it.nodeHex })
        assertFalse(fresh.single().active) // a node-id-only advert: not running Wi-Fi Direct
    }
}
