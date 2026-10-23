package com.dtn.mesh.learning

import com.dtn.mesh.model.ForwardingAction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Binary format for saving the Q-tables across app restarts. Pure JVM code (unit-tested).
 *
 * `[magic "DQT1"][schema UTF][epsilon][episodes][updates][capturedAt]` then table A and table B,
 * each `[n]` × `[state key UTF][k]` × `[action ordinal][value]`.
 *
 * [decode] returns null for anything else, including a table saved with a different
 * [QLearningConfig.stateSchema] (its keys would mean different states).
 */
object QTableCodec {

    private const val MAGIC = 0x44515431 // "DQT1"

    fun encode(snapshot: QTableSnapshot, schema: String): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(schema)
            out.writeDouble(snapshot.epsilon)
            out.writeLong(snapshot.episodeCount)
            out.writeLong(snapshot.totalUpdates)
            out.writeLong(snapshot.capturedAtMs)
            writeTable(out, snapshot.tableA)
            writeTable(out, snapshot.tableB)
        }
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray, expectedSchema: String): QTableSnapshot? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
            if (inp.readInt() != MAGIC) return null
            if (inp.readUTF() != expectedSchema) return null
            val epsilon = inp.readDouble()
            val episodes = inp.readLong()
            val updates = inp.readLong()
            val captured = inp.readLong()
            QTableSnapshot(
                tableA = readTable(inp),
                tableB = readTable(inp),
                episodeCount = episodes,
                totalUpdates = updates,
                capturedAtMs = captured,
                epsilon = epsilon,
            )
        }
    }.getOrNull()

    private fun writeTable(out: DataOutputStream, table: Map<String, Map<ForwardingAction, Double>>) {
        out.writeInt(table.size)
        for ((key, actions) in table) {
            out.writeUTF(key)
            out.writeByte(actions.size)
            for ((action, value) in actions) {
                out.writeByte(action.ordinal)
                out.writeDouble(value)
            }
        }
    }

    private fun readTable(inp: DataInputStream): Map<String, Map<ForwardingAction, Double>> {
        val actions = ForwardingAction.entries
        val n = inp.readInt()
        require(n in 0..1_000_000)
        val table = HashMap<String, Map<ForwardingAction, Double>>(n)
        repeat(n) {
            val key = inp.readUTF()
            val k = inp.readUnsignedByte()
            val values = HashMap<ForwardingAction, Double>(k)
            repeat(k) {
                val ordinal = inp.readUnsignedByte()
                val value = inp.readDouble()
                actions.getOrNull(ordinal)?.let { values[it] = value }
            }
            table[key] = values
        }
        return table
    }
}
