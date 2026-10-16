package com.dtn.mesh.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent dedup / tombstone record. Remembers every bundle id we've accepted or terminated
 * (delivered / expired / dropped) until its TTL fully elapses, so a late or re-flooded copy
 * can't resurrect a message after the forwarding buffer row has been purged.
 */
@Entity(
    tableName = "seen_bundles",
    indices = [Index(value = ["expires_at_ms"])],
)
data class SeenBundleEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "origin_node_id")
    val originNodeId: String,

    @ColumnInfo(name = "destination_node_id")
    val destinationNodeId: String,

    /** SEEN / DELIVERED / EXPIRED / DROPPED. */
    @ColumnInfo(name = "status")
    val status: String,

    /** Keep the tombstone until this time, then it may be purged (epoch ms). */
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,
) {
    object Status {
        const val SEEN = "SEEN"
        const val DELIVERED = "DELIVERED"
        const val EXPIRED = "EXPIRED"
        const val DROPPED = "DROPPED"
    }
}
