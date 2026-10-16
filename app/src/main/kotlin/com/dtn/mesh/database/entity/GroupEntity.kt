package com.dtn.mesh.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group/channel the user has joined. Messages on `channel` are encrypted with a pre-shared
 * 32-byte channel key (suite 2). The key is stored Keystore-wrapped ([com.dtn.mesh.crypto.KeyVault]).
 * Channel 0 is reserved for DMs / the public broadcast, so group channels are 1..255.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    @ColumnInfo(name = "channel")
    val channel: Int,

    @ColumnInfo(name = "name")
    val name: String,

    /** Keystore-wrapped 32-byte channel key. */
    @ColumnInfo(name = "key_wrapped", typeAffinity = ColumnInfo.BLOB)
    val keyWrapped: ByteArray,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupEntity) return false
        return channel == other.channel
    }

    override fun hashCode(): Int = channel
}
