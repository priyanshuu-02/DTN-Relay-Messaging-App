package com.dtn.mesh.model

import com.dtn.mesh.crypto.DtnIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable [NodeId] for this phone, shared across every transport (LoRa hub, BLE, WiFi Direct).
 *
 * The id is derived from Android's app-scoped device id (decision 14, see [DtnIdentity]), so it
 * stays the same across data clears and reinstalls; the keys that decrypt traffic for it are
 * announced (signed) and pinned by the receivers. This is a thin delegate kept for the transports
 * and orchestrator that already depend on `identity.nodeId` / `identity.nodeNum32`.
 */
@Singleton
class LocalNodeIdentity @Inject constructor(
    private val identity: DtnIdentity,
) {
    /** This device's stable DTN node id, e.g. "!3fa27c10". */
    val nodeId: NodeId get() = identity.nodeId

    /** The 32-bit numeric form written on the wire. */
    val nodeNum32: Int get() = identity.nodeNum32

    /** When this phone's keys were generated (epoch ms, 0 = unknown); see [DtnIdentity.createdAtMs]. */
    val createdAtMs: Long get() = identity.createdAtMs

    /** True when the id is derived from ANDROID_ID (it survives a data clear). */
    val idIsStable: Boolean get() = identity.idIsStable
}
