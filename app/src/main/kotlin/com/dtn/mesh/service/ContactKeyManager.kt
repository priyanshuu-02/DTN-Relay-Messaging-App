package com.dtn.mesh.service

import com.dtn.mesh.crypto.Announce
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.entity.ContactEntity
import com.dtn.mesh.settings.PendingKeys
import com.dtn.mesh.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies [KeyChangeRules] to the contacts table + the key-change state in DataStore: pins keys
 * from signed announces, switches unverified contacts to newer keys (with a "safety number
 * changed" note), and holds a QR-verified contact's new keys until the user accepts them or
 * re-scans the QR. The orchestrator's event loop is its only announce caller, so the
 * read-modify-write never races.
 *
 * Never logs key material: node ids and times only.
 */
@Singleton
class ContactKeyManager @Inject constructor(
    private val identity: DtnIdentity,
    private val contactDao: ContactDao,
    private val settings: SettingsRepository,
    private val diagnostics: DiagnosticsLog,
) {
    /** What a verified announce did; [relay] = a broadcast copy keeps flooding. */
    enum class Outcome(val relay: Boolean) {
        PINNED(true),
        REFRESHED(true),
        REPLACED(true),
        HELD(true),
        /** Older than the keys we hold: not pinned and not passed on. */
        STALE(false),
        /** Carries this phone's own keys (an echo from before our id switched): ignored. */
        OWN_KEYS(false),
        /** From a blocked contact: ignored here, still relayed for others. */
        BLOCKED(true),
    }

    suspend fun onAnnounce(origin: String, a: Announce, nowMs: Long = System.currentTimeMillis()): Outcome {
        if (a.xPub.contentEquals(identity.xPub)) return Outcome.OWN_KEYS
        if (!ContactRules.acceptAnnounce(origin, settings.blockedNodesOnce())) return Outcome.BLOCKED
        val contact = contactDao.getByNodeId(origin)
        val storedX = contact?.xPub
        val storedEd = contact?.edPub
        val hasKeys = storedX != null && storedEd != null
        val same = storedX != null && storedEd != null && storedX.contentEquals(a.xPub) && storedEd.contentEquals(a.edPub)
        // Keys pinned before issue times were kept: when we first pinned them is the lower bound.
        val storedIssued = settings.keyIssuedAtSec(origin) ?: ((contact?.keyFirstSeenMs ?: 0L) / 1000L)
        val pending = if (hasKeys && !same) settings.pendingKeys(origin) else null
        val issued = a.issuedAtSec.toLong()
        val decision = KeyChangeRules.decide(
            hasStoredKeys = hasKeys,
            sameAsStored = same,
            storedIssuedAtSec = storedIssued,
            verified = contact?.keyVerified == true,
            announceIssuedAtSec = issued,
            pendingIssuedAtSec = pending?.issuedAtSec,
        )
        if (decision != KeyChangeRules.Decision.IGNORE_STALE) {
            settings.deletedAtOnce(origin)?.let { deletedAt ->
                if (ContactRules.announceUndeletes(issued, deletedAt)) settings.clearDeleted(origin)
            }
        }
        val who = origin.takeLast(8)
        return when (decision) {
            KeyChangeRules.Decision.PIN_FIRST -> {
                contactDao.insertIfNew(ContactEntity(nodeId = origin))
                contactDao.updateKeys(origin, a.xPub, a.edPub, a.name, nowMs)
                settings.setKeyIssuedAtSec(origin, issued)
                Outcome.PINNED
            }
            KeyChangeRules.Decision.SAME_KEYS -> {
                contactDao.updateKeys(origin, a.xPub, a.edPub, a.name, nowMs)
                if (issued > storedIssued) settings.setKeyIssuedAtSec(origin, issued)
                Outcome.REFRESHED
            }
            KeyChangeRules.Decision.IGNORE_STALE -> {
                diagnostics.i(DiagSource.CRYPTO, "Announce from $who carries other keys but isn't newer than the ones " +
                    "pinned here — stale copy ignored")
                Outcome.STALE
            }
            KeyChangeRules.Decision.REPLACE -> {
                contactDao.updateKeys(origin, a.xPub, a.edPub, a.name, nowMs)
                settings.setKeyIssuedAtSec(origin, issued)
                settings.clearPendingKeys(origin)
                settings.setKeyChangedAt(origin, nowMs)
                diagnostics.w(DiagSource.CRYPTO, "Safety number of $who changed (newer signed announce, contact not " +
                    "verified) — switched to the new keys")
                Outcome.REPLACED
            }
            KeyChangeRules.Decision.HOLD_FOR_REVIEW -> {
                settings.setPendingKeys(origin, PendingKeys(a.xPub, a.edPub, issued, a.name))
                diagnostics.w(DiagSource.CRYPTO, "Keys of verified contact $who changed — held until you accept them " +
                    "or re-scan their QR; DMs to them wait")
                Outcome.HELD
            }
        }
    }

    /** Switch a verified contact to the keys held for review; they're no longer verified. */
    suspend fun acceptPendingKeys(nodeId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val p = settings.pendingKeys(nodeId) ?: return false
        val name = p.name.ifBlank { contactDao.getByNodeId(nodeId)?.announceName.orEmpty() }
        contactDao.updateKeys(nodeId, p.xPub, p.edPub, name.ifBlank { null }, nowMs)
        contactDao.setKeyVerified(nodeId, false)
        settings.setKeyIssuedAtSec(nodeId, p.issuedAtSec)
        settings.clearPendingKeys(nodeId)
        settings.setKeyChangedAt(nodeId, nowMs)
        diagnostics.i(DiagSource.CRYPTO, "New keys of ${nodeId.takeLast(8)} accepted (safety number not verified)")
        return true
    }

    /**
     * Keys from a scanned QR: they replace whatever is held (also keys waiting for review) and
     * are verified. @return true when they differ from the keys held before.
     */
    suspend fun applyScannedKeys(nodeId: String, xPub: ByteArray, edPub: ByteArray, name: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        contactDao.insertIfNew(ContactEntity(nodeId = nodeId))
        val before = contactDao.getByNodeId(nodeId)
        val oldX = before?.xPub
        val oldEd = before?.edPub
        val changed = oldX != null && oldEd != null && !(oldX.contentEquals(xPub) && oldEd.contentEquals(edPub))
        contactDao.updateKeys(nodeId, xPub, edPub, name, nowMs)
        contactDao.setKeyVerified(nodeId, true)
        settings.setKeyIssuedAtSec(nodeId, nowMs / 1000L)
        settings.clearPendingKeys(nodeId)
        if (changed) {
            settings.setKeyChangedAt(nodeId, nowMs)
            diagnostics.i(DiagSource.CRYPTO, "Keys of ${nodeId.takeLast(8)} replaced from a scanned QR (verified)")
        }
        return changed
    }
}
