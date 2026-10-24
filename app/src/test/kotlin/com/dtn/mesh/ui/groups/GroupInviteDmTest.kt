package com.dtn.mesh.ui.groups

import com.dtn.mesh.crypto.DtnCrypto
import com.dtn.mesh.crypto.GroupInvite
import com.dtn.mesh.model.DtnMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GroupInviteDmTest {

    private val key = ByteArray(32) { (it * 11 + 5).toByte() }

    @Test
    fun detect_findsAnInviteDm_andReturnsItsGroup() {
        val text = GroupInvite.build(42, "Hikers", key)
        val p = GroupInviteDm.detect(text)
        assertNotNull(p)
        assertEquals(42, p!!.channel)
        assertEquals("Hikers", p.name)
        assertArrayEquals(key, p.key)
        // Surrounding whitespace (a pasted invite) is fine.
        assertNotNull(GroupInviteDm.detect("  $text\n"))
        assertEquals("Group invite · Hikers", GroupInviteDm.preview(text))
    }

    @Test
    fun detect_ignoresOrdinaryTextAndDamagedInvites() {
        assertNull(GroupInviteDm.detect("hello there"))
        assertNull(GroupInviteDm.detect("join my group dtngrp:4:Team:" + b64(key)))
        assertNull(GroupInviteDm.detect(GroupInvite.build(4, "Team", key) + " see you"))
        assertNull(GroupInviteDm.detect("dtngrp:4:Team:AAAA")) // key too short
        assertNull(GroupInviteDm.detect("dtngrp:0:Team:" + b64(key))) // channel 0 is reserved
        assertNull(GroupInviteDm.detect("dtnid:2:9a0b1c2d:AAAA:BBBB:name"))
        assertNull(GroupInviteDm.preview("hello there"))
    }

    @Test
    fun nameCap_isComputedFromTheDmOverhead() {
        // 255-byte frame − 32-byte header − 16-byte GCM tag − 1 algorithm byte = 206 text bytes.
        assertEquals(255 - 32 - 16 - 1, GroupInvite.DM_TEXT_BYTES)
        assertEquals(DtnMessage.MAX_SINGLE_PACKET_PAYLOAD - DtnCrypto.GCM_TAG_LEN - 1, GroupInvite.DM_TEXT_BYTES)
        // "dtngrp:" + 3 channel digits + 2 colons + 43 Base64 key characters.
        assertEquals("dtngrp:".length + 3 + 2 + b64(key).length, GroupInvite.FIXED_BYTES)
        assertEquals(151, GroupInvite.MAX_NAME_BYTES)
    }

    @Test
    fun inviteWithTheLongestName_fitsOneDmFrame_onTheWidestChannel() {
        val name = "x".repeat(GroupInvite.MAX_NAME_BYTES)
        val invite = GroupInvite.build(255, name, key)
        assertEquals(GroupInvite.DM_TEXT_BYTES, invite.toByteArray(Charsets.UTF_8).size)
        assertTrue(GroupInviteDm.fitsOneFrame(invite))
        assertEquals(name, GroupInvite.parse(invite)!!.name)
    }

    @Test
    fun longerNames_areCutToTheCap_withoutSplittingCharacters() {
        val ascii = GroupInvite.build(255, "y".repeat(400), key)
        assertTrue(GroupInviteDm.fitsOneFrame(ascii))
        assertEquals(GroupInvite.MAX_NAME_BYTES, GroupInvite.parse(ascii)!!.name.length)

        // 2-byte characters: 75 fit (150 bytes); the 76th would pass 151.
        val accents = GroupInvite.cleanName("é".repeat(200))
        assertEquals(75, accents.length)
        assertTrue(GroupInvite.utf8Bytes(accents) <= GroupInvite.MAX_NAME_BYTES)

        // 4-byte emoji are never cut in half: 37 whole ones (148 bytes), ending on a complete pair.
        val emoji = GroupInvite.cleanName("😀".repeat(60))
        assertEquals(37, emoji.codePointCount(0, emoji.length))
        assertEquals(74, emoji.length)
        assertEquals(148, GroupInvite.utf8Bytes(emoji))
        assertTrue(Character.isLowSurrogate(emoji.last()))
        assertTrue(GroupInviteDm.fitsOneFrame(GroupInvite.build(255, emoji, key)))
    }

    @Test
    fun nameCounter_showsBytesAgainstTheCap() {
        assertEquals("6/151 bytes", GroupInviteDm.nameCounter("Hikers"))
        assertEquals("4/151 bytes", GroupInviteDm.nameCounter("éé"))
        assertEquals("ab", GroupInvite.truncateUtf8("abc", 2))
        assertEquals("", GroupInvite.truncateUtf8("é", 1))
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(bytes)
}
