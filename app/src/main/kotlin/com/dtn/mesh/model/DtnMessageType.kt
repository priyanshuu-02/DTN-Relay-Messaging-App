package com.dtn.mesh.model

/**
 * Discriminator for DTN wire message types.
 *
 * Encoded as a single byte at offset 27 of the 32-byte self-contained DTN header
 * (see [com.dtn.mesh.receiver.DtnWireCodec]). Usable payload after the 32-byte header is
 * 223 bytes per single LoRa frame.
 */
enum class DtnMessageType(val wireValue: Byte) {
    /** User-generated store-carry-forward message content. */
    DATA(0x00),

    /** Serialised routing state summary exchanged on peer encounter. */
    ROUTING_SUMMARY(0x01),

    /** Lightweight acknowledgement that a routing summary was received and processed. */
    ROUTING_ACK(0x02),

    /**
     * Bundle offer: list of message IDs we hold and are willing to forward.
     * Sent after routing summary exchange to enable selective pull.
     */
    BUNDLE_OFFER(0x03),

    /**
     * Bundle request: list of message IDs we want the peer to transmit to us.
     * Sent in response to a BUNDLE_OFFER.
     */
    BUNDLE_REQUEST(0x04),

    /**
     * Signed identity/key announcement. Broadcast so peers can learn this node's X25519
     * (encryption) and Ed25519 (verification) public keys and bind them to its node id
     * (trust-on-first-use). Payload = [com.dtn.mesh.crypto.Announce].
     */
    ANNOUNCE(0x05);

    companion object {
        /** Decode a wire byte to the enum, defaulting to DATA for unknown values. */
        fun fromWire(value: Byte): DtnMessageType =
            entries.firstOrNull { it.wireValue == value } ?: DATA

        /** Whether this message type carries user data (vs control plane). */
        fun isControlPlane(type: DtnMessageType): Boolean = type != DATA

        /**
         * Control-plane types that should NOT be persisted in the message buffer.
         * They are processed in-memory and discarded.
         */
        val EPHEMERAL_TYPES: Set<DtnMessageType> = setOf(
            ROUTING_SUMMARY,
            ROUTING_ACK,
            BUNDLE_OFFER,
            BUNDLE_REQUEST,
        )
    }

    /** Whether this type is ephemeral (not stored in the forwarding buffer). */
    val isEphemeral: Boolean get() = this in EPHEMERAL_TYPES

    /** Whether this type carries user payload that participates in store-carry-forward. */
    val isUserData: Boolean get() = this == DATA

    /** Whether this is a signed identity/key announcement. */
    val isAnnounce: Boolean get() = this == ANNOUNCE

    /** Whether this type is part of the encounter handshake protocol. */
    val isHandshake: Boolean get() = this == ROUTING_SUMMARY || this == ROUTING_ACK

    /** Whether this type is part of the bundle exchange protocol. */
    val isBundleExchange: Boolean get() = this == BUNDLE_OFFER || this == BUNDLE_REQUEST
}
