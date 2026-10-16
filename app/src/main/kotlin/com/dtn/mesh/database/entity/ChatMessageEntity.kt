package com.dtn.mesh.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent, user-visible chat history — DECOUPLED from the store-carry-forward buffer
 * (`messages`). The buffer holds opaque, possibly-encrypted bundles that get purged once
 * delivered/expired; this table holds the plaintext the user actually sees, keyed by
 * conversation, and is never purged by buffer housekeeping. This fixes the old bug where
 * purging the forwarding buffer also erased the chat log.
 */
@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["conv_key"]), Index(value = ["created_at_ms"])],
)
data class ChatMessageEntity(
    /** Bundle message id (parent id for a fragmented message). Dedup/correlation key. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Conversation key: peer node id (DM), "^all" (broadcast), or "grp:<channel>" (group). */
    @ColumnInfo(name = "conv_key")
    val convKey: String,

    /** The other party's node id; null for broadcast. */
    @ColumnInfo(name = "peer_node_id")
    val peerNodeId: String?,

    /** Channel/group number (0 = DM/broadcast primary). */
    @ColumnInfo(name = "channel")
    val channel: Int = 0,

    /** "OUT" (we sent) or "IN" (we received). */
    @ColumnInfo(name = "direction")
    val direction: String,

    /** Decrypted plaintext body. */
    @ColumnInfo(name = "text")
    val text: String,

    /** Display status: SENDING / BUFFERED / DELIVERED / RECEIVED / EXPIRED / DROPPED / FAILED. */
    @ColumnInfo(name = "status")
    val status: String,

    /** When the message was created/received, for ordering (epoch ms). */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
) {
    object Direction { const val OUT = "OUT"; const val IN = "IN" }
    object Status {
        const val SENDING = "SENDING"
        /** Queued/flooded with no end-to-end ack expected (broadcast / group). */
        const val SENT = "SENT"
        const val BUFFERED = "BUFFERED"
        const val DELIVERED = "DELIVERED"
        const val RECEIVED = "RECEIVED"
        const val EXPIRED = "EXPIRED"
        const val DROPPED = "DROPPED"
        const val FAILED = "FAILED"
    }
}
