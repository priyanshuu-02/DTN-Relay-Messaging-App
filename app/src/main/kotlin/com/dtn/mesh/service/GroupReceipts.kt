package com.dtn.mesh.service

import com.dtn.mesh.crypto.DtnCrypto
import com.dtn.mesh.model.DtnMessage
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Group receipts (Oct 2026): a member whose phone receives and decrypts a group message for the
 * first time tells the message's origin, as an ordinary end-to-end encrypted DM, so phones, hubs
 * and the internet relay carry it like any DM and carriers drop it on its own DM receipt. A group
 * message itself NEVER gets a ROUTING_ACK: that would make hubs and carriers delete it while it
 * still has members to reach.
 *
 * Plaintext of the DM (inside the encryption, instead of the [com.dtn.mesh.routing.AlgorithmCode]
 * byte): `[MARKER 0xA7][version 1][channel][n][n × 16-byte message id]`. A first byte in
 * 0x80–0xBF can't start UTF-8 text (a continuation byte) and is outside the 0x01–0x06 algorithm
 * codes, so it never reads as a chat message. Only unfragmented DMs are checked: a later fragment
 * of a long text may start with a continuation byte.
 */
object GroupReceiptCodec {
    const val MARKER = 0xA7
    const val VERSION = 1
    /** marker + version + channel + n. */
    const val HEADER_BYTES = 4
    const val ID_BYTES = 16

    /** Plaintext one DM frame holds: 223 payload bytes − 16 (GCM tag) = 207. */
    const val DM_PLAINTEXT_BYTES = DtnMessage.MAX_SINGLE_PACKET_PAYLOAD - DtnCrypto.GCM_TAG_LEN

    /** Message ids per receipt, so it always fits one LoRa frame: (207 − 4) / 16 = 12. */
    const val MAX_IDS = (DM_PLAINTEXT_BYTES - HEADER_BYTES) / ID_BYTES

    /** A receipt lives at least this long, or as long as the group message had left. */
    const val MIN_TTL_MS = 30 * 60_000L

    data class Receipt(val channel: Int, val ids: List<String>)

    /** First plaintext bytes reserved for control payloads (never text, never an algorithm code). */
    fun isControlByte(first: Int): Boolean = first in 0x80..0xBF

    /** True when an unfragmented DM's plaintext is a group receipt (even a damaged one: never shown as text). */
    fun isMarked(plain: ByteArray): Boolean = plain.isNotEmpty() && (plain[0].toInt() and 0xFF) == MARKER

    fun encode(channel: Int, ids: List<String>): ByteArray {
        require(channel in 1..255) { "group channel must be 1..255" }
        require(ids.size in 1..MAX_IDS) { "1..$MAX_IDS message ids per receipt" }
        val buf = ByteBuffer.allocate(HEADER_BYTES + ids.size * ID_BYTES)
        buf.put(MARKER.toByte())
        buf.put(VERSION.toByte())
        buf.put(channel.toByte())
        buf.put(ids.size.toByte())
        for (id in ids) {
            val u = UUID.fromString(id)
            buf.putLong(u.mostSignificantBits)
            buf.putLong(u.leastSignificantBits)
        }
        return buf.array()
    }

    /** The receipt, or null for another version, a bad channel / count, or a short body. Extra trailing bytes are ignored. */
    fun decode(plain: ByteArray): Receipt? {
        if (plain.size < HEADER_BYTES || !isMarked(plain)) return null
        if ((plain[1].toInt() and 0xFF) != VERSION) return null
        val channel = plain[2].toInt() and 0xFF
        val n = plain[3].toInt() and 0xFF
        if (channel == 0 || n == 0 || n > MAX_IDS || plain.size < HEADER_BYTES + n * ID_BYTES) return null
        val buf = ByteBuffer.wrap(plain, HEADER_BYTES, n * ID_BYTES)
        val ids = List(n) { UUID(buf.long, buf.long).toString() }
        return Receipt(channel, ids)
    }

    /** The receipt DM's lifetime: what the group messages had left, at least [MIN_TTL_MS]. */
    fun ttlFor(groupTtlLeftMs: Long): Long = maxOf(MIN_TTL_MS, groupTtlLeftMs)
}

/**
 * Collects the group messages a member received, per (origin, channel), for [windowMs], so one
 * receipt DM covers several of them (at most [GroupReceiptCodec.MAX_IDS] each). Thread-safe.
 */
class GroupReceiptBatcher(private val windowMs: Long) {

    /** One receipt DM to send: [ttlLeftMs] = the most any of its group messages had left. */
    data class Batch(val origin: String, val channel: Int, val ids: List<String>, val ttlLeftMs: Long)

    private class Pending(val dueAtMs: Long) {
        val ids = LinkedHashSet<String>()
        var ttlLeftMs = 0L
    }

    private val pending = LinkedHashMap<Pair<String, Int>, Pending>()

    /**
     * Queue a receipt for [msgId]. @return the due time when this starts a new batch, or joins one
     * whose time has already passed (its send never ran, e.g. the service stopped): schedule a send
     * then. Null when it joined a batch that is still waiting.
     */
    @Synchronized
    fun add(origin: String, channel: Int, msgId: String, ttlLeftMs: Long, nowMs: Long): Long? {
        val key = origin to channel
        val existing = pending[key]
        val p = existing ?: Pending(nowMs + windowMs).also { pending[key] = it }
        p.ids += msgId
        p.ttlLeftMs = maxOf(p.ttlLeftMs, ttlLeftMs)
        return if (existing == null || existing.dueAtMs <= nowMs) p.dueAtMs else null
    }

    /** Removes and returns every batch due by [nowMs], cut into receipts that each fit one frame. */
    @Synchronized
    fun takeDue(nowMs: Long): List<Batch> {
        val due = pending.filterValues { it.dueAtMs <= nowMs }
        due.keys.forEach { pending.remove(it) }
        return due.flatMap { (key, p) ->
            p.ids.toList().chunked(GroupReceiptCodec.MAX_IDS).map { Batch(key.first, key.second, it, p.ttlLeftMs) }
        }
    }

    /** Batches waiting. */
    @get:Synchronized
    val waiting: Int get() = pending.size
}

/** Which group receipts the origin records (pure, unit-tested). */
object GroupReceiptRules {
    /**
     * A receipt from [member] for [route] counts when the route is this phone's own group message on
     * [channel] and that member hasn't been recorded yet (the first receipt wins).
     */
    fun accepts(route: MessageLifecycleLog.RouteRecord, channel: Int, member: String): Boolean {
        val m8 = member.takeLast(8)
        return channel > 0 && route.isBroadcast && route.channel == channel &&
            route.me.isNotBlank() && route.source == route.me && m8.isNotBlank() && m8 != route.me &&
            route.groupReceipts.none { it.member == m8 }
    }
}
