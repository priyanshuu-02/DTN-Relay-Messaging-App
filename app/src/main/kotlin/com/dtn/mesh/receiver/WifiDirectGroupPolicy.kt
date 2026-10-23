package com.dtn.mesh.receiver

/**
 * Who forms the Wi-Fi Direct group, and who joins whose. Pure logic (JVM unit-tested); the
 * adapter feeds it what it heard over BLE / Wi-Fi P2P service discovery and carries out the
 * returned [Action].
 *
 * One DTN phone owns a group (a mini hotspot) and every other DTN phone in range joins it with
 * the group's name + password, which nobody has to accept. So no invitation pop-ups, and any
 * number of phones share one group (the owner's chip says how many are linked).
 *
 * Election: with no group around, the DTN phone with the lowest node id creates one; the others
 * wait for it ([CREATE_WAIT_MS]) and then join. If that phone never creates (it can't hear us),
 * we create our own. Two groups that formed at the same time merge: an owner with no members
 * gives up its group for a better one (more members, then lower owner id).
 */
object WifiDirectGroupPolicy {

    /** How long a higher-id phone waits for the lowest-id phone to create the group. */
    const val CREATE_WAIT_MS = 8_000L
    /** An owner with no members and no DTN phone in sight gives the group up after this. */
    const val OWNER_IDLE_MS = 120_000L

    enum class Role { SEARCHING, JOINING, CREATING, OWNER, MEMBER }

    /** A DTN group seen nearby (owner's node hex, how to join it, size, when last seen). */
    data class Group(
        val ownerHex: String,
        val networkName: String,
        val passphrase: String,
        val members: Int,
        val seenAtMs: Long,
    )

    sealed interface Action {
        data object None : Action
        data class Join(val group: Group) : Action
        data object Create : Action
        /** Give up our own (empty) group and join [group]. */
        data class LeaveAndJoin(val group: Group) : Action
        /** Give up our own group (nobody joined, nobody around). */
        data object Disband : Action
    }

    /** The group a phone should prefer: more members, then the lower owner id. */
    fun best(groups: Collection<Group>): Group? =
        groups.sortedWith(compareByDescending<Group> { it.members }.thenBy { it.ownerHex }).firstOrNull()

    /**
     * @param groups fresh groups nearby (not ours, not temporarily blocked after a failed join).
     * @param phones node hex of fresh DTN phones running Wi-Fi Direct that own no group.
     * @param waitingSinceMs when we started waiting for a lower-id phone to create (null = not waiting).
     * @param idleSinceMs as an owner: since when we've had no members.
     */
    fun decide(
        myHex: String,
        role: Role,
        nowMs: Long,
        members: Int,
        groups: Collection<Group>,
        phones: Collection<String>,
        waitingSinceMs: Long?,
        idleSinceMs: Long?,
    ): Action = when (role) {
        Role.SEARCHING -> {
            val target = best(groups)
            when {
                target != null -> Action.Join(target)
                phones.isEmpty() -> Action.None // nobody to link with: don't run a group for nothing
                phones.all { myHex < it } -> Action.Create
                waitingSinceMs != null && nowMs - waitingSinceMs >= CREATE_WAIT_MS -> Action.Create
                else -> Action.None
            }
        }
        Role.OWNER -> {
            if (members > 0) {
                Action.None
            } else {
                val better = best(groups.filter { it.members > 0 || it.ownerHex < myHex })
                when {
                    better != null -> Action.LeaveAndJoin(better)
                    groups.isEmpty() && phones.isEmpty() && idleSinceMs != null && nowMs - idleSinceMs >= OWNER_IDLE_MS ->
                        Action.Disband
                    else -> Action.None
                }
            }
        }
        Role.JOINING, Role.CREATING, Role.MEMBER -> Action.None
    }
}
