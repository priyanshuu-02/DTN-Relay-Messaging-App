package com.dtn.mesh.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delete contact / Block (decision 13). */
class ContactRulesTest {

    private val blocked = setOf("!0000000b")

    @Test
    fun blockedSender_dmIsAcknowledgedButNotShownOrAdded() {
        assertEquals(ContactRules.InboundDm.RECEIPT_ONLY, ContactRules.inboundDm(senderBlocked = true))
        assertEquals(ContactRules.InboundDm.SHOW, ContactRules.inboundDm(senderBlocked = false))
    }

    @Test
    fun blockedSender_announcesAreIgnored() {
        assertFalse(ContactRules.acceptAnnounce("!0000000b", blocked))
        assertTrue(ContactRules.acceptAnnounce("!0000000a", blocked))
    }

    @Test
    fun directAnnounces_neverGoToBlockedContacts_orToUs() {
        val targets = ContactRules.directAnnounceTargets(
            added = listOf("!0000000a", "!0000000b", "!0000000f", "^all", "!legacy"),
            blocked = blocked, me = "!0000000f",
        )
        assertEquals(listOf("!0000000a", "!legacy"), targets)
    }

    @Test
    fun blockedSender_groupPostsAreHidden() {
        assertFalse(ContactRules.showPost("!0000000b", blocked))
        assertTrue(ContactRules.showPost("!0000000a", blocked))
    }

    @Test
    fun deletedContact_staysOutOfDiscovered_untilANewerAnnounce() {
        val deletedAtMs = 1_700_000_000_000L
        assertFalse(ContactRules.inDiscovered("!0000000a", added = false, hasKeys = true, isHub = false,
            blocked = emptySet(), deleted = setOf("!0000000a")))
        // An announce issued before (or at) the deletion doesn't bring them back; a later one does.
        assertFalse(ContactRules.announceUndeletes(deletedAtMs / 1000 - 60, deletedAtMs))
        assertFalse(ContactRules.announceUndeletes(deletedAtMs / 1000, deletedAtMs))
        assertTrue(ContactRules.announceUndeletes(deletedAtMs / 1000 + 1, deletedAtMs))
        // Once un-deleted they're an ordinary discovered phone again.
        assertTrue(ContactRules.inDiscovered("!0000000a", added = false, hasKeys = true, isHub = false,
            blocked = emptySet(), deleted = emptySet()))
    }

    @Test
    fun discovered_isAnnouncedNonContacts_withoutBlockedPhonesOrHubs() {
        fun d(id: String, added: Boolean = false, keys: Boolean = true, hub: Boolean = false) =
            ContactRules.inDiscovered(id, added, keys, hub, blocked, deleted = emptySet())
        assertTrue(d("!0000000a"))
        assertFalse(d("!0000000a", added = true))
        assertFalse(d("!0000000a", keys = false))
        assertFalse(d("!0000000a", hub = true))
        assertFalse(d("!0000000b"))
    }

    @Test
    fun contacts_areAddedOrChattedWith_neverBlocked() {
        assertTrue(ContactRules.inContacts("!0000000a", added = true, hasChat = false, isHub = false, blocked = blocked))
        assertTrue(ContactRules.inContacts("!0000000a", added = false, hasChat = true, isHub = false, blocked = blocked))
        assertFalse(ContactRules.inContacts("!0000000a", added = false, hasChat = false, isHub = false, blocked = blocked))
        assertFalse(ContactRules.inContacts("!0000000b", added = true, hasChat = true, isHub = false, blocked = blocked))
        assertFalse(ContactRules.inContacts("!0000000c", added = true, hasChat = false, isHub = true, blocked = blocked))
    }
}
