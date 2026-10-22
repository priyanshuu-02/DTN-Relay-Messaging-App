package com.dtn.mesh.crypto

import com.dtn.mesh.database.dao.GroupDao
import com.dtn.mesh.database.entity.GroupEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages group/channel keys. Each group owns a random 32-byte key (suite 2 AEAD). Keys are
 * stored Keystore-wrapped in the `groups` table and cached in memory after first unwrap.
 * Channel 0 is reserved (DM / public broadcast), so group channels are 1..255.
 *
 * New groups take a key-derived channel slot ([GroupInvite.channelFor]); groups joined from an
 * invite use the invite's channel (so invites from earlier builds keep working). Joining never
 * overwrites a different group that already occupies the slot — that returns [JoinResult.Conflict].
 * A group is shared out-of-band via an invite string ([GroupInvite]) rendered as a QR.
 */
@Singleton
class GroupKeyStore @Inject constructor(
    private val groupDao: GroupDao,
    private val vault: KeyVault,
) {
    private val cache = ConcurrentHashMap<Int, ByteArray>()

    sealed interface JoinResult {
        data class Joined(val channel: Int, val name: String) : JoinResult
        data class AlreadyMember(val channel: Int, val name: String) : JoinResult
        /** Another group (with a different key) already uses this channel slot on this phone. */
        data class Conflict(val channel: Int, val existingName: String) : JoinResult
    }

    /** Raw 32-byte key for a channel, or null if we're not a member / can't unwrap. */
    suspend fun key(channel: Int): ByteArray? {
        if (channel <= 0) return null
        cache[channel]?.let { return it }
        val group = groupDao.getByChannel(channel) ?: return null
        return runCatching { vault.unwrap(group.keyWrapped) }.getOrNull()?.also { cache[channel] = it }
    }

    suspend fun groups(): List<GroupEntity> = groupDao.getAll()

    fun observeGroups(): kotlinx.coroutines.flow.Flow<List<GroupEntity>> = groupDao.observeAll()

    /** Create a brand-new group with a fresh key on a key-derived, locally free channel. */
    suspend fun createGroup(name: String): GroupEntity {
        val used = groupDao.getAll().map { it.channel }.toSet()
        if (used.size >= 255) throw IllegalStateException("No free group channel (max 255)")
        // Draw keys until the key's own channel slot is free here (expected ~1 draw).
        repeat(256) {
            val raw = DtnCrypto.randomBytes(32)
            val channel = GroupInvite.channelFor(raw)
            if (channel !in used) return insert(channel, name, raw)
        }
        // Practically unreachable (needs a nearly full table): fall back to any free slot.
        val raw = DtnCrypto.randomBytes(32)
        val channel = (1..255).first { it !in used }
        return insert(channel, name, raw)
    }

    private suspend fun insert(channel: Int, name: String, raw: ByteArray): GroupEntity {
        val entity = GroupEntity(channel, name, vault.wrap(raw), System.currentTimeMillis())
        groupDao.upsert(entity)
        cache[channel] = raw
        return entity
    }

    /** Join a group from an invite. Never replaces a different group on the same channel. */
    suspend fun joinGroup(channel: Int, name: String, rawKey: ByteArray): JoinResult {
        require(channel in 1..255) { "invalid channel" }
        require(rawKey.size == 32) { "channel key must be 32 bytes" }
        val existing = groupDao.getByChannel(channel)
        if (existing != null) {
            val existingKey = runCatching { vault.unwrap(existing.keyWrapped) }.getOrNull()
            if (existingKey != null && existingKey.contentEquals(rawKey)) {
                return JoinResult.AlreadyMember(channel, existing.name)
            }
            // A row we can no longer unwrap (Keystore reset) is dead weight — replace it.
            if (existingKey != null) return JoinResult.Conflict(channel, existing.name)
        }
        groupDao.upsert(GroupEntity(channel, name, vault.wrap(rawKey), System.currentTimeMillis()))
        cache[channel] = rawKey
        return JoinResult.Joined(channel, name)
    }

    suspend fun leaveGroup(channel: Int) {
        groupDao.delete(channel)
        cache.remove(channel)
    }

    fun inviteString(channel: Int, name: String, rawKey: ByteArray): String =
        GroupInvite.build(channel, name, rawKey)

    fun parseInvite(text: String): GroupInvite.Parsed? = GroupInvite.parse(text)
}
