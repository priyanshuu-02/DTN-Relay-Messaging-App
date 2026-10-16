package com.dtn.mesh.crypto

import android.util.Base64
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
 * A group is shared out-of-band via an invite string (rendered as a QR): `dtngrp:<ch>:<name>:<b64key>`.
 */
@Singleton
class GroupKeyStore @Inject constructor(
    private val groupDao: GroupDao,
    private val vault: KeyVault,
) {
    private val cache = ConcurrentHashMap<Int, ByteArray>()

    /** Raw 32-byte key for a channel, or null if we're not a member / can't unwrap. */
    suspend fun key(channel: Int): ByteArray? {
        if (channel <= 0) return null
        cache[channel]?.let { return it }
        val group = groupDao.getByChannel(channel) ?: return null
        return runCatching { vault.unwrap(group.keyWrapped) }.getOrNull()?.also { cache[channel] = it }
    }

    suspend fun groups(): List<GroupEntity> = groupDao.getAll()

    fun observeGroups(): kotlinx.coroutines.flow.Flow<List<GroupEntity>> = groupDao.observeAll()

    /** Create a brand-new group with a fresh key on the first free channel. */
    suspend fun createGroup(name: String): GroupEntity {
        val used = groupDao.getAll().map { it.channel }.toSet()
        val channel = (1..255).firstOrNull { it !in used }
            ?: throw IllegalStateException("No free group channel (max 255)")
        val raw = DtnCrypto.randomBytes(32)
        val entity = GroupEntity(channel, name, vault.wrap(raw), System.currentTimeMillis())
        groupDao.upsert(entity)
        cache[channel] = raw
        return entity
    }

    /** Join an existing group from an invite's (channel, name, rawKey). */
    suspend fun joinGroup(channel: Int, name: String, rawKey: ByteArray) {
        require(channel in 1..255) { "invalid channel" }
        require(rawKey.size == 32) { "channel key must be 32 bytes" }
        groupDao.upsert(GroupEntity(channel, name, vault.wrap(rawKey), System.currentTimeMillis()))
        cache[channel] = rawKey
    }

    suspend fun leaveGroup(channel: Int) {
        groupDao.delete(channel)
        cache.remove(channel)
    }

    fun inviteString(channel: Int, name: String, rawKey: ByteArray): String =
        "dtngrp:$channel:$name:" + Base64.encodeToString(rawKey, Base64.NO_WRAP or Base64.NO_PADDING)

    fun parseInvite(text: String): Triple<Int, String, ByteArray>? {
        if (!text.startsWith("dtngrp:")) return null
        val parts = text.removePrefix("dtngrp:").split(":", limit = 3)
        if (parts.size != 3) return null
        val channel = parts[0].toIntOrNull() ?: return null
        val key = runCatching { Base64.decode(parts[2], Base64.NO_WRAP or Base64.NO_PADDING) }.getOrNull() ?: return null
        if (key.size != 32 || channel !in 1..255) return null
        return Triple(channel, parts[1], key)
    }
}
