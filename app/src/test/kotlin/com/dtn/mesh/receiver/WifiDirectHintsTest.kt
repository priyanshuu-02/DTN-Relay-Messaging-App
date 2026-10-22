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
        assertEquals(9, data.size)
        assertEquals(listOf(0x1e, 0xa5, 0x31, 0x58), data.take(4).map { it.toInt() and 0xFF })
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
