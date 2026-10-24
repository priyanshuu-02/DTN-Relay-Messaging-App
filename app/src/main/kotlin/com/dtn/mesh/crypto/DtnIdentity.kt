package com.dtn.mesh.crypto

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.dtn.mesh.model.NodeId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's long-term cryptographic identity: an X25519 key (for message encryption) and an
 * Ed25519 key (for signing announces), plus its [NodeId].
 *
 * One identity per phone (decision 14): the node id is derived from Android's app-scoped device id
 * ([NodeIdDerivation]), so clearing the app's data or reinstalling keeps the same id; only a
 * factory reset changes it. The KEYS are still new after a data clear, so receivers switch keys
 * only for a newer signed announce ([com.dtn.mesh.service.KeyChangeRules]). If ANDROID_ID is
 * unavailable the id this install already uses is kept (stored next to the keys), or — on an
 * install from before ids were stored — the old key-derived id ([DtnCrypto.deriveNodeNum]).
 *
 * Existing installs switch to the derived id once, on upgrade, and keep their keys; the switch is
 * remembered ([pendingIdSwitch]) until the app has re-announced itself to every contact.
 *
 * Keys are generated once, wrapped by the Android Keystore ([KeyVault]) and stored in app-private
 * storage. They survive app restarts but not a data clear / reinstall.
 */
@Singleton
class DtnIdentity @Inject constructor(
    @ApplicationContext context: Context,
    private val vault: KeyVault,
) {
    val xPriv: ByteArray
    val xPub: ByteArray
    val edSeed: ByteArray
    val edPub: ByteArray
    val nodeId: NodeId
    val nodeNum32: Int
    /** True when [nodeId] comes from ANDROID_ID, so it stays the same across data clears. */
    val idIsStable: Boolean
    /**
     * When the keys were generated (epoch ms, 0 = unknown). Clearing the app's data or
     * reinstalling makes new keys, so anything signed before this belongs to older keys.
     */
    val createdAtMs: Long

    private val switchMarker = File(context.filesDir, ID_SWITCH_FILE)

    init {
        val file = File(context.filesDir, IDENTITY_FILE)
        val (material, generated) = loadOrCreate(file)
        xPriv = material.copyOfRange(0, 32)
        xPub = material.copyOfRange(32, 64)
        edSeed = material.copyOfRange(64, 96)
        edPub = material.copyOfRange(96, 128)

        val idFile = File(context.filesDir, NODE_ID_FILE)
        val stored = runCatching { idFile.takeIf { it.exists() }?.readText()?.trim()?.let { NodeId(it).toNodeNum() } }.getOrNull()
        val androidId = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
        val derived = if (NodeIdDerivation.isUsable(androidId)) NodeIdDerivation.derive(androidId!!, context.packageName) else null
        val keyDerived = DtnCrypto.deriveNodeNum(xPub, edPub)
        nodeNum32 = derived ?: stored ?: keyDerived
        idIsStable = derived != null
        nodeId = NodeId.fromNodeNum(nodeNum32)

        // The id this install used until now: the stored one, or the key-derived id of a build from
        // before ids were stored. A fresh install / data clear has none (no contacts to tell either).
        val previous = stored ?: if (!generated) keyDerived else null
        if (stored != nodeNum32) runCatching { idFile.writeText(nodeId.value) }.onFailure { Log.w(TAG, "could not store node id", it) }
        if (previous != null && previous != nodeNum32) {
            runCatching { switchMarker.writeText(NodeId.fromNodeNum(previous).value) }
            Log.i(TAG, "Node id switched from ${NodeId.fromNodeNum(previous)} to $nodeId")
        }
        // The key file is only ever written when the keys are generated, so its mtime is their birth.
        createdAtMs = runCatching { file.lastModified() }.getOrDefault(0L)
        Log.i(TAG, "Identity ready: $nodeId (${if (idIsStable) "derived from the Android id" else "stored"})")
    }

    /** 32-byte public fingerprint used to render the safety number and pin contacts. */
    fun fingerprint(): ByteArray = DtnCrypto.fingerprint(xPub, edPub)

    /**
     * The node id this phone used before it switched to the derived one, while the one-time
     * re-announce to every contact is still owed (null otherwise). Small file read: call it off
     * the main thread.
     */
    fun pendingIdSwitch(): String? = runCatching {
        if (switchMarker.exists()) switchMarker.readText().trim().ifBlank { null } else null
    }.getOrNull()

    /** The re-announce after the id switch has been queued. */
    fun clearIdSwitch() {
        runCatching { switchMarker.delete() }
    }

    /** The key material and whether it was generated just now (true = new keys). */
    private fun loadOrCreate(file: File): Pair<ByteArray, Boolean> {
        if (file.exists()) {
            runCatching { vault.unwrap(file.readBytes()) }
                .onSuccess { if (it.size == MATERIAL_LEN) return it to false }
                .onFailure { Log.w(TAG, "identity unwrap failed, regenerating", it) }
        }
        val (xp, xpub) = DtnCrypto.generateX25519()
        val (eseed, epub) = DtnCrypto.generateEd25519()
        val material = xp + xpub + eseed + epub
        runCatching { file.writeBytes(vault.wrap(material)) }
            .onFailure { Log.e(TAG, "could not persist identity", it) }
        return material to true
    }

    companion object {
        private const val TAG = "DtnIdentity"
        private const val IDENTITY_FILE = "dtn_identity_v1.bin"
        /** The node id in use ("!hex8"), so a later build can tell that it changed. */
        private const val NODE_ID_FILE = "dtn_node_id_v1.txt"
        /** Present (holding the old id) until the re-announce after an id switch was queued. */
        private const val ID_SWITCH_FILE = "dtn_node_id_switch_pending.txt"
        private const val MATERIAL_LEN = 128 // xPriv32 + xPub32 + edSeed32 + edPub32
    }
}
