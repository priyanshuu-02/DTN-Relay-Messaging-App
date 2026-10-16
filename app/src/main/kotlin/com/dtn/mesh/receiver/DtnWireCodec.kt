package com.dtn.mesh.receiver

import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.DtnMessageType
import com.dtn.mesh.model.NodeId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Encodes/decodes DTN messages to/from the **self-contained** 32-byte wire header + payload
 * format. Self-contained means the bundle carries its own origin and destination, so it needs
 * no outer envelope — required for the hub-to-hub LoRa backbone where there is no transport
 * header to carry addressing.
 *
 * ## Wire Header (32 bytes, big-endian)
 * ```
 * Offset | Size | Field
 * ───────┼──────┼──────────────────────────
 *  0     | 16   | messageId (UUID: msb 8 bytes + lsb 8 bytes)
 * 16     |  4   | originNodeId       (uint32, NodeId.toNodeNum32)
 * 20     |  4   | destinationNodeId  (uint32, NodeId.toNodeNum32)
 * 24     |  2   | ttlMinutes (UShort, big-endian; max 65535 min ≈ 45.5 days)
 * 26     |  1   | hopCount (0–255)
 * 27     |  1   | messageType (DtnMessageType.wireValue)
 * 28     |  1   | channel (0–255)
 * 29     |  1   | fragmentIndex (0 = single-frame complete; 1..N for multi-frame)
 * 30     |  1   | fragmentTotal (1 = no fragmentation; 2..255 for multi-frame)
 * 31     |  1   | reserved (must be 0x00, ignored on read)
 * ```
 *
 * After the header: raw payload bytes (up to 223 bytes per frame over LoRa).
 *
 * This codec is stateless and has zero Android framework dependencies.
 */
object DtnWireCodec {

    /** Total wire header size in bytes. */
    const val HEADER_SIZE = 32

    /** Maximum payload per single LoRa frame after header. */
    const val MAX_PAYLOAD_PER_FRAME = DtnMessage.MAX_SINGLE_PACKET_PAYLOAD // 223

    /**
     * Encode a [DtnMessage] into self-contained wire bytes.
     *
     * @param message The DTN message to encode. Its [DtnMessage.originNodeId] and
     *   [DtnMessage.destinationNodeId] are written into the header as 32-bit values.
     * @return Raw bytes: 32-byte header + payload. Total ≤ 255 bytes for a single frame.
     * @throws IllegalArgumentException if payload exceeds single-frame limit and fragmentation
     *   is not indicated.
     */
    fun encode(message: DtnMessage): ByteArray {
        val payloadSize = message.payloadBytes.size

        // Validate: if not fragmented, payload must fit in a single frame
        if (message.fragmentTotal == 1 && payloadSize > MAX_PAYLOAD_PER_FRAME) {
            throw IllegalArgumentException(
                "Payload ($payloadSize bytes) exceeds single-frame limit ($MAX_PAYLOAD_PER_FRAME bytes). " +
                    "Use fragmentation or reduce payload size."
            )
        }

        val totalSize = HEADER_SIZE + payloadSize
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // UUID (16 bytes): most significant bits first, then least significant.
        // Defensive: if the id isn't a valid UUID (legacy data / control ids), fall back
        // to a deterministic hash so the encode doesn't crash the whole send path.
        val uuid = try {
            UUID.fromString(message.id)
        } catch (e: IllegalArgumentException) {
            UUID.nameUUIDFromBytes(message.id.toByteArray(Charsets.UTF_8))
        }
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)

        // Origin + destination node IDs (4 bytes each) — makes the bundle self-contained
        buffer.putInt(message.originNodeId.toNodeNum32())
        buffer.putInt(message.destinationNodeId.toNodeNum32())

        // TTL in minutes (2 bytes UShort) — encode the REMAINING ttl so it counts DOWN across
        // hops and bundles actually expire. (Was message.ttlMs, which reset the clock at every
        // hop and made bundles effectively immortal.)
        val ttlMinutes = (message.remainingTtlMs() / 60_000L).coerceIn(0, 65535).toInt()
        buffer.putShort(ttlMinutes.toShort())

        // Hop count (1 byte)
        buffer.put(message.hopCount.coerceIn(0, 255).toByte())

        // Message type (1 byte)
        buffer.put(message.messageType.wireValue)

        // Channel (1 byte)
        buffer.put(message.channel.coerceIn(0, 255).toByte())

        // Fragment index (1 byte)
        buffer.put(message.fragmentIndex.coerceIn(0, 255).toByte())

        // Fragment total (1 byte)
        buffer.put(message.fragmentTotal.coerceIn(1, 255).toByte())

        // Flags (1 byte) — crypto suite / version / routing flags (was reserved 0x00)
        buffer.put((message.flags and 0xFF).toByte())

        // Payload
        buffer.put(message.payloadBytes)

        return buffer.array()
    }

    /**
     * Decode self-contained wire bytes into a [DtnMessage].
     *
     * Origin and destination are read from the header (not passed in), so the message identity
     * is preserved end-to-end across every hop and transport.
     *
     * @param wireBytes The raw bytes received from any transport (LoRa hub, BLE, or WiFi Direct).
     * @param rssi RSSI observed on this receive hop (transport metadata, not part of the header).
     * @param snr SNR observed on this receive hop (transport metadata, not part of the header).
     * @return The decoded [DtnMessage], or null if the wire data is malformed.
     */
    fun decode(
        wireBytes: ByteArray,
        rssi: Int = 0,
        snr: Float = 0f,
    ): DtnMessage? {
        if (wireBytes.size < HEADER_SIZE) return null // Too short to contain a valid header

        val buffer = ByteBuffer.wrap(wireBytes).order(ByteOrder.BIG_ENDIAN)

        // UUID (16 bytes)
        val msb = buffer.getLong()
        val lsb = buffer.getLong()
        val uuid = UUID(msb, lsb)

        // Origin + destination node IDs
        val originNodeId = NodeId.fromNodeNum(buffer.getInt())
        val destinationNodeId = NodeId.fromNodeNum(buffer.getInt())

        // TTL minutes → ms
        val ttlMinutes = buffer.getShort().toInt() and 0xFFFF // unsigned
        val ttlMs = ttlMinutes.toLong() * 60_000L

        // Hop count
        val hopCount = buffer.get().toInt() and 0xFF

        // Message type
        val messageType = DtnMessageType.fromWire(buffer.get())

        // Channel
        val channel = buffer.get().toInt() and 0xFF

        // Fragment index
        val fragmentIndex = buffer.get().toInt() and 0xFF

        // Fragment total
        val fragmentTotal = buffer.get().toInt() and 0xFF

        // Flags
        val flags = buffer.get().toInt() and 0xFF

        // Remaining bytes = payload
        val payloadSize = wireBytes.size - HEADER_SIZE
        val payloadBytes = if (payloadSize > 0) {
            ByteArray(payloadSize).also { buffer.get(it) }
        } else {
            ByteArray(0)
        }

        return DtnMessage(
            id = uuid.toString(),
            originNodeId = originNodeId,
            destinationNodeId = destinationNodeId,
            payloadBytes = payloadBytes,
            portNum = DtnMessage.PORT_NUM_PRIVATE_APP,
            createdAtMs = System.currentTimeMillis(), // Approximate; origin time is ttl-relative
            ttlMs = ttlMs,
            hopCount = hopCount,
            channel = channel,
            lastRssi = rssi,
            lastSnr = snr,
            messageType = messageType,
            fragmentIndex = fragmentIndex,
            fragmentTotal = fragmentTotal,
            flags = flags,
        )
    }

    /**
     * The 32-byte header serialised for use as AEAD additional-authenticated-data, with the
     * mutable fields (ttlMinutes, hopCount) zeroed. Both the sender and every recipient derive
     * the exact same AAD regardless of how many hops changed the TTL/hop en route, so the
     * ciphertext stays verifiable end-to-end while relays are still free to decrement TTL/hop.
     */
    fun aadHeader(message: DtnMessage): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        val uuid = uuidOf(message.id)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        buffer.putInt(message.originNodeId.toNodeNum32())
        buffer.putInt(message.destinationNodeId.toNodeNum32())
        buffer.putShort(0)                                   // ttlMinutes masked
        buffer.put(0)                                        // hopCount masked
        buffer.put(message.messageType.wireValue)
        buffer.put(message.channel.coerceIn(0, 255).toByte())
        buffer.put(message.fragmentIndex.coerceIn(0, 255).toByte())
        buffer.put(message.fragmentTotal.coerceIn(1, 255).toByte())
        buffer.put((message.flags and 0xFF).toByte())
        return buffer.array()
    }

    private fun uuidOf(id: String): UUID = try {
        UUID.fromString(id)
    } catch (e: IllegalArgumentException) {
        UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8))
    }
}
