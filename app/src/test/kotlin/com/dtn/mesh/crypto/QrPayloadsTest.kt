package com.dtn.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class QrPayloadsTest {

    private val key = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test
    fun groupInvite_roundTrips() {
        val text = GroupInvite.build(42, "Hikers", key)
        val p = GroupInvite.parse(text)
        assertNotNull(p)
        assertEquals(42, p!!.channel)
        assertEquals("Hikers", p.name)
        assertArrayEquals(key, p.key)
    }

    @Test
    fun groupInvite_nameMayContainColons() {
        val p = GroupInvite.parse(GroupInvite.build(7, "Team: alpha:2", key))
        assertNotNull(p)
        assertEquals("Team: alpha:2", p!!.name)
        assertArrayEquals(key, p.key)
    }

    @Test
    fun groupInvite_parsesInvitesFromEarlierBuilds() {
        // Earlier builds wrote android.util.Base64 NO_WRAP|NO_PADDING — identical to unpadded basic.
        val legacy = "dtngrp:1:test:" + Base64.getEncoder().withoutPadding().encodeToString(key)
        val p = GroupInvite.parse(legacy)
        assertNotNull(p)
        assertEquals(1, p!!.channel)
        assertArrayEquals(key, p.key)
    }

    @Test
    fun groupInvite_rejectsDamagedPayloads() {
        assertNull(GroupInvite.parse("dtngrp:0:x:" + Base64.getEncoder().encodeToString(key))) // channel 0 reserved
        assertNull(GroupInvite.parse("dtngrp:256:x:" + Base64.getEncoder().encodeToString(key)))
        assertNull(GroupInvite.parse("dtngrp:5:x:" + Base64.getEncoder().encodeToString(ByteArray(16)))) // short key
        assertNull(GroupInvite.parse("dtngrp:5:no-key-part"))
        assertNull(GroupInvite.parse("hello"))
    }

    @Test
    fun channelFor_isDeterministic_inRange_andSpreadsKeys() {
        assertEquals(GroupInvite.channelFor(key), GroupInvite.channelFor(key.copyOf()))
        val channels = (0 until 500).map { i -> GroupInvite.channelFor(ByteArray(32) { (it xor i).toByte() }) }
        assertTrue(channels.all { it in 1..255 })
        // Sequential allocation put every first group on channel 1; key-derived slots spread out.
        assertTrue("expected a wide spread, got ${channels.toSet().size}", channels.toSet().size > 150)
    }

    @Test
    fun identityQr_roundTrips_andRejectsBadKeys() {
        val x = ByteArray(32) { it.toByte() }
        val ed = ByteArray(32) { (31 - it).toByte() }
        val p = IdentityQr.parse(IdentityQr.build(x, ed, "Priya: phone 2"))
        assertNotNull(p)
        assertArrayEquals(x, p!!.xPub)
        assertArrayEquals(ed, p.edPub)
        assertEquals("Priya: phone 2", p.name)

        assertNull(IdentityQr.parse("dtnid:1:AAAA:BBBB:name"))
        assertNull(IdentityQr.parse("dtngrp:1:x:y"))
    }
}
