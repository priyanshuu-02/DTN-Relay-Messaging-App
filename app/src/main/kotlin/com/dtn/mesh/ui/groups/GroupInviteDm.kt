package com.dtn.mesh.ui.groups

import com.dtn.mesh.crypto.GroupInvite

/**
 * Group invites sent as ordinary encrypted DMs (Create group › Add members, decision 10). The DM's
 * whole text is the invite string, so the receiver's chat shows a Join group card instead of the
 * raw text, and lists show "Group invite · <name>" (never the key). Joining is always a tap on the
 * card ([com.dtn.mesh.ui.DtnViewModel.joinGroupFromInvite], with today's conflict rules). Pure.
 */
object GroupInviteDm {

    /** The invite a DM carries, or null when its text isn't exactly one valid invite. */
    fun detect(text: String): GroupInvite.Parsed? {
        val t = text.trim()
        if (!t.startsWith(GroupInvite.PREFIX)) return null
        return GroupInvite.parse(t)
    }

    /** "Group invite · Family" for chat and queue previews, or null when [text] isn't an invite. */
    fun preview(text: String): String? = detect(text)?.let { "Group invite · ${it.name}" }

    /** Whether [invite] travels as one DM in one LoRa frame (no fragments). */
    fun fitsOneFrame(invite: String): Boolean = GroupInvite.utf8Bytes(invite) <= GroupInvite.DM_TEXT_BYTES

    /** The Create group name counter: "12/151 bytes". */
    fun nameCounter(name: String): String = "${GroupInvite.utf8Bytes(name)}/${GroupInvite.MAX_NAME_BYTES} bytes"
}

/** What a Join group card offers. */
enum class InviteStatus {
    /** Not in the group yet: Join. */
    JOINABLE,
    /** This phone already holds the group's key: Open group. */
    MEMBER,
    /** Another local group uses the same channel slot: joining is refused (leave that one first). */
    CONFLICT,
    /** Not a valid invite (damaged). */
    INVALID,
}

/** [status] of an invite, plus the local group in the way for [InviteStatus.CONFLICT]. */
data class InviteCheck(val status: InviteStatus, val otherGroup: String? = null)
