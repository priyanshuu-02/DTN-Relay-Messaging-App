package com.dtn.mesh.receiver

import com.dtn.mesh.receiver.WifiDirectGroupPolicy.Action
import com.dtn.mesh.receiver.WifiDirectGroupPolicy.Group
import com.dtn.mesh.receiver.WifiDirectGroupPolicy.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectGroupPolicyTest {

    private val now = 1_000_000L

    private fun group(owner: String, members: Int = 0) =
        Group(owner, WifiDirectHints.networkName(owner), "pass-$owner", members, now)

    private fun decide(
        me: String,
        role: Role = Role.SEARCHING,
        members: Int = 0,
        groups: List<Group> = emptyList(),
        phones: List<String> = emptyList(),
        waitingSince: Long? = null,
        idleSince: Long? = null,
    ) = WifiDirectGroupPolicy.decide(me, role, now, members, groups, phones, waitingSince, idleSince)

    @Test
    fun searching_nobodyAround_doesNothing() {
        assertEquals(Action.None, decide("50000000"))
    }

    @Test
    fun searching_lowestIdCreatesTheGroup() {
        assertEquals(Action.Create, decide("10000000", phones = listOf("20000000", "30000000")))
    }

    @Test
    fun searching_higherIdWaitsForTheLowestToCreate() {
        // Still waiting just before CREATE_WAIT_MS (5 s since fix 2, was 8 s).
        assertEquals(Action.None, decide("30000000", phones = listOf("10000000"),
            waitingSince = now - WifiDirectGroupPolicy.CREATE_WAIT_MS + 1_000L))
    }

    @Test
    fun searching_createsItselfWhenTheLowestNeverDoes() {
        val waited = now - WifiDirectGroupPolicy.CREATE_WAIT_MS
        assertEquals(Action.Create, decide("30000000", phones = listOf("10000000"), waitingSince = waited))
    }

    @Test
    fun searching_joinsAnExistingGroupInsteadOfCreating() {
        val g = group("90000000", members = 1)
        assertEquals(Action.Join(g), decide("10000000", groups = listOf(g), phones = listOf("20000000")))
    }

    @Test
    fun best_prefersMoreMembersThenLowerOwner() {
        val small = group("10000000", members = 1)
        val big = group("f0000000", members = 3)
        val tieLow = group("20000000", members = 3)
        assertEquals(tieLow, WifiDirectGroupPolicy.best(listOf(small, big, tieLow)))
        assertNull(WifiDirectGroupPolicy.best(emptyList()))
    }

    @Test
    fun owner_withMembers_staysPut() {
        assertEquals(Action.None, decide("50000000", Role.OWNER, members = 2, groups = listOf(group("10000000", 4))))
    }

    @Test
    fun owner_emptyGroup_mergesIntoALowerOwnersGroup() {
        val lower = group("10000000")
        assertEquals(Action.LeaveAndJoin(lower), decide("50000000", Role.OWNER, groups = listOf(lower)))
    }

    @Test
    fun owner_emptyGroup_mergesIntoAGroupWithMembers() {
        val higherButUsed = group("90000000", members = 1)
        assertEquals(Action.LeaveAndJoin(higherButUsed), decide("50000000", Role.OWNER, groups = listOf(higherButUsed)))
    }

    @Test
    fun owner_emptyGroup_keepsItAgainstAHigherEmptyGroup() {
        // The other (higher-id, empty) owner is the one that merges — never both.
        assertEquals(Action.None, decide("50000000", Role.OWNER, groups = listOf(group("90000000"))))
    }

    @Test
    fun owner_disbandsAfterIdlingAloneLongEnough() {
        val idle = now - WifiDirectGroupPolicy.OWNER_IDLE_MS
        assertEquals(Action.Disband, decide("50000000", Role.OWNER, idleSince = idle))
        assertEquals(Action.None, decide("50000000", Role.OWNER, idleSince = now - 1_000L))
        // Someone is still around: keep the group up for them.
        assertEquals(Action.None, decide("50000000", Role.OWNER, phones = listOf("90000000"), idleSince = idle))
    }

    @Test
    fun transientRoles_neverAct() {
        val g = group("10000000", 2)
        for (r in listOf(Role.JOINING, Role.CREATING, Role.MEMBER)) {
            assertEquals(r.name, Action.None, decide("50000000", r, groups = listOf(g), phones = listOf("20000000")))
        }
    }
}
