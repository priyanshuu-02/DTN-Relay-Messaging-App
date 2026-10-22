package com.dtn.mesh.crypto

import android.content.Context
import android.util.Log
import com.dtn.mesh.model.NodeId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's long-term cryptographic identity: an X25519 key (for message encryption) and an
 * Ed25519 key (for signing announces). The [NodeId] is DERIVED from the public keys
 * ([DtnCrypto.deriveNodeNum]) so a node id is cryptographically bound to its keys — it can no
 * longer be freely spoofed the way the old ANDROID_ID hash could.
 *
 * Keys are generated once, wrapped by the Android Keystore ([KeyVault]) and stored in app-private
 * storage. They survive app restarts but not a factory reset / reinstall (export/import is a
 * future feature).
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
    /**
     * When this identity was generated (epoch ms, 0 = unknown). Clearing the app's data or
     * reinstalling makes a new identity, so anything issued before this belongs to an older one.
     */
    val createdAtMs: Long

    init {
        val file = File(context.filesDir, IDENTITY_FILE)
        val material = loadOrCreate(file)
        xPriv = material.copyOfRange(0, 32)
        xPub = material.copyOfRange(32, 64)
        edSeed = material.copyOfRange(64, 96)
        edPub = material.copyOfRange(96, 128)
        nodeNum32 = DtnCrypto.deriveNodeNum(xPub, edPub)
        nodeId = NodeId.fromNodeNum(nodeNum32)
        // The file is only ever written when the keys are generated, so its mtime is their birth.
        createdAtMs = runCatching { file.lastModified() }.getOrDefault(0L)
        Log.i(TAG, "Identity ready: $nodeId")
    }

    /** 32-byte public fingerprint used to render the safety number and pin contacts. */
    fun fingerprint(): ByteArray = DtnCrypto.fingerprint(xPub, edPub)

    private fun loadOrCreate(file: File): ByteArray {
        if (file.exists()) {
            runCatching { vault.unwrap(file.readBytes()) }
                .onSuccess { if (it.size == MATERIAL_LEN) return it }
                .onFailure { Log.w(TAG, "identity unwrap failed, regenerating", it) }
        }
        val (xp, xpub) = DtnCrypto.generateX25519()
        val (eseed, epub) = DtnCrypto.generateEd25519()
        val material = xp + xpub + eseed + epub
        runCatching { file.writeBytes(vault.wrap(material)) }
            .onFailure { Log.e(TAG, "could not persist identity", it) }
        return material
    }

    companion object {
        private const val TAG = "DtnIdentity"
        private const val IDENTITY_FILE = "dtn_identity_v1.bin"
        private const val MATERIAL_LEN = 128 // xPriv32 + xPub32 + edSeed32 + edPub32
    }
}
