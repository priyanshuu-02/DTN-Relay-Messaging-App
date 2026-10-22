package com.dtn.mesh.model

import com.dtn.mesh.crypto.DtnIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable [NodeId] for this install, shared across every transport (LoRa hub, BLE, WiFi Direct).
 *
 * The id is now **cryptographically derived** from this device's X25519 + Ed25519 public keys
 * (see [DtnIdentity]) instead of a spoofable ANDROID_ID hash, so a node id is bound to the keys
 * that can actually decrypt traffic for it. This is a thin delegate kept for the transports and
 * orchestrator that already depend on `identity.nodeId` / `identity.nodeNum32`.
 */
@Singleton
class LocalNodeIdentity @Inject constructor(
    private val identity: DtnIdentity,
) {
    /** This device's stable DTN node id, e.g. "!3fa27c10". */
    val nodeId: NodeId get() = identity.nodeId

    /** The 32-bit numeric form written on the wire. */
    val nodeNum32: Int get() = identity.nodeNum32

    /** When this identity was generated (epoch ms, 0 = unknown); see [DtnIdentity.createdAtMs]. */
    val createdAtMs: Long get() = identity.createdAtMs
}
