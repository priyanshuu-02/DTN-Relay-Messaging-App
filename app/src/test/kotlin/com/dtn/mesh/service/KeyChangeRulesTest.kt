package com.dtn.mesh.service

import com.dtn.mesh.service.KeyChangeRules.Decision
import com.dtn.mesh.settings.PendingKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Decision 14: the same node id can come back with new keys (a data clear). */
class KeyChangeRulesTest {

    private fun decide(
        hasKeys: Boolean = true,
        same: Boolean = false,
        stored: Long = 1_000,
        verified: Boolean = false,
        issued: Long = 2_000,
        pending: Long? = null,
    ) = KeyChangeRules.decide(hasKeys, same, stored, verified, issued, pending)

    @Test
    fun firstKeys_arePinned() {
        assertEquals(Decision.PIN_FIRST, decide(hasKeys = false))
        assertEquals(Decision.PIN_FIRST, decide(hasKeys = false, verified = true, issued = 1))
    }

    @Test
    fun theSameKeys_justRefresh_evenWhenOlder() {
        assertEquals(Decision.SAME_KEYS, decide(same = true))
        assertEquals(Decision.SAME_KEYS, decide(same = true, issued = 10))
    }

    @Test
    fun otherKeys_notNewerThanTheStoredOnes_areStaleReplays() {
        assertEquals(Decision.IGNORE_STALE, decide(stored = 2_000, issued = 1_999))
        assertEquals(Decision.IGNORE_STALE, decide(stored = 2_000, issued = 2_000))
        assertEquals(Decision.IGNORE_STALE, decide(stored = 2_000, issued = 1_000, verified = true))
    }

    @Test
    fun newerKeys_forAnUnverifiedContact_replaceTheOldOnes() {
        assertEquals(Decision.REPLACE, decide(stored = 1_000, issued = 1_001))
    }

    @Test
    fun newerKeys_forAVerifiedContact_areNeverSwitchedSilently() {
        assertEquals(Decision.HOLD_FOR_REVIEW, decide(verified = true))
        // A newer change replaces the one already waiting…
        assertEquals(Decision.HOLD_FOR_REVIEW, decide(verified = true, issued = 3_000, pending = 2_500))
        // …an older copy of it doesn't.
        assertEquals(Decision.IGNORE_STALE, decide(verified = true, issued = 2_400, pending = 2_500))
        assertEquals(Decision.IGNORE_STALE, decide(verified = true, issued = 2_500, pending = 2_500))
    }

    @Test
    fun pendingKeys_roundTrip_andKeepColonsInTheName() {
        val x = ByteArray(32) { it.toByte() }
        val ed = ByteArray(32) { (it * 2).toByte() }
        val back = PendingKeys.decode(PendingKeys(x, ed, 1_700_000_123L, "Priya: phone 2").encode())
        assertNotNull(back)
        assertArrayEquals(x, back!!.xPub)
        assertArrayEquals(ed, back.edPub)
        assertEquals(1_700_000_123L, back.issuedAtSec)
        assertEquals("Priya: phone 2", back.name)
        assertNull(PendingKeys.decode("garbage"))
        assertNull(PendingKeys.decode("12:AAAA:BBBB:x"))
    }
}
