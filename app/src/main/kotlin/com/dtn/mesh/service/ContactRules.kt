package com.dtn.mesh.service

/**
 * How a signed announce for a node changes the keys this phone holds for it (decision 14). Node
 * ids now survive a data clear, but the keys don't: the same id can come back with new keys. Pure
 * logic, unit-tested; [ContactKeyManager] applies it.
 */
object KeyChangeRules {
    enum class Decision {
        /** First keys for this node (trust on first use). */
        PIN_FIRST,
        /** The keys we already hold: refresh the name and the issue time. */
        SAME_KEYS,
        /** Different keys, but not newer than what we hold (a replay or an old copy): ignored. */
        IGNORE_STALE,
        /** Newer keys for an unverified contact: switch, keep the contact + chat, note it in the chat. */
        REPLACE,
        /** Newer keys for a QR-verified contact: never switched silently, held until Accept / a QR re-scan. */
        HOLD_FOR_REVIEW,
    }

    /**
     * @param storedIssuedAtSec issue time of the newest accepted announce for the stored keys.
     * @param pendingIssuedAtSec issue time of keys already held for review (verified contacts), if any.
     */
    fun decide(
        hasStoredKeys: Boolean,
        sameAsStored: Boolean,
        storedIssuedAtSec: Long,
        verified: Boolean,
        announceIssuedAtSec: Long,
        pendingIssuedAtSec: Long? = null,
    ): Decision = when {
        !hasStoredKeys -> Decision.PIN_FIRST
        sameAsStored -> Decision.SAME_KEYS
        announceIssuedAtSec <= storedIssuedAtSec -> Decision.IGNORE_STALE
        !verified -> Decision.REPLACE
        pendingIssuedAtSec != null && announceIssuedAtSec <= pendingIssuedAtSec -> Decision.IGNORE_STALE
        else -> Decision.HOLD_FOR_REVIEW
    }
}

/**
 * Delete contact / Block (decision 13) as pure rules, shared by the orchestrator, the outbound
 * service and the UI, and unit-tested.
 *
 * - Block: their DMs are acknowledged (the delivery receipt still goes out) and then dropped, never
 *   shown, stored or auto-added; no direct announces go to them; their announces are ignored and
 *   their group posts hidden. Relaying their bundles for others is unchanged.
 * - Delete: the chat goes and they leave the chat list, but the contact row (keys, routing stats)
 *   stays, so relaying keeps working and a later DM still decrypts and re-adds them. They're hidden
 *   from Discovered until they announce after the deletion.
 */
object ContactRules {
    /** What happens to a DM for us that decrypted. */
    enum class InboundDm {
        /** Stored in the chat, acknowledged, and the sender (re-)added to the chat list. */
        SHOW,
        /** Acknowledged (receipt queued) and dropped: not shown, not stored, not added. */
        RECEIPT_ONLY,
    }

    fun inboundDm(senderBlocked: Boolean): InboundDm = if (senderBlocked) InboundDm.RECEIPT_ONLY else InboundDm.SHOW

    /** Whether a verified announce from [origin] may pin / change keys. */
    fun acceptAnnounce(origin: String, blocked: Set<String>): Boolean = origin !in blocked

    /** The contacts that get our direct announce while Discover is off (never blocked ones). */
    fun directAnnounceTargets(added: List<String>, blocked: Set<String>, me: String): List<String> =
        added.filter { it != me && it.startsWith("!") && it !in blocked }

    /** A received group / broadcast post is shown unless its sender is blocked. */
    fun showPost(senderId: String, blocked: Set<String>): Boolean = senderId !in blocked

    /** An announce issued after the deletion brings a deleted contact back to Discovered. */
    fun announceUndeletes(announceIssuedAtSec: Long, deletedAtMs: Long): Boolean = announceIssuedAtSec * 1000L > deletedAtMs

    /**
     * Discovered (Peers › Nearby devices): phones that announced their keys but aren't contacts,
     * minus blocked ones, hubs, and deleted ones that haven't announced since.
     */
    fun inDiscovered(
        nodeId: String,
        added: Boolean,
        hasKeys: Boolean,
        isHub: Boolean,
        blocked: Set<String>,
        deleted: Set<String>,
    ): Boolean = !added && hasKeys && !isHub && nodeId !in blocked && nodeId !in deleted

    /** Chats / Contacts: added contacts (or ones we've chatted with), never blocked ones or hubs. */
    fun inContacts(nodeId: String, added: Boolean, hasChat: Boolean, isHub: Boolean, blocked: Set<String>): Boolean =
        (added || hasChat) && !isHub && nodeId !in blocked
}
