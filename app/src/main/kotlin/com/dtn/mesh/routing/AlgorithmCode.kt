package com.dtn.mesh.routing

/**
 * One byte at the start of a DM's plaintext naming the routing algorithm the sender's phone had
 * selected when the message was sent, so the receiver's Message info can show it. It is inside
 * the end-to-end encryption: only the receiver reads it, and hubs / carriers still route on the
 * header alone.
 *
 * Codes are the control characters 0x01–0x06, which never start typed text (UTF-8 text starts with
 * a byte ≥ 0x20), so messages from older builds (no code) are told apart safely.
 */
object AlgorithmCode {

    fun of(type: StrategySelector.StrategyType): Byte = when (type) {
        StrategySelector.StrategyType.PROPHET -> 0x01
        StrategySelector.StrategyType.STABLE -> 0x02
        StrategySelector.StrategyType.MAXPROP -> 0x03
        StrategySelector.StrategyType.DIMAXPROP -> 0x04
        StrategySelector.StrategyType.EPIDEMIC -> 0x05
        StrategySelector.StrategyType.QLEARNING -> 0x06
    }

    /** Display name for a code, or null when [code] isn't one. */
    fun label(code: Int): String? = when (code) {
        0x01 -> "PRoPHET"
        0x02 -> "Stable PRoPHET"
        0x03 -> "MaxProp"
        0x04 -> "DiMaxProp"
        0x05 -> "Epidemic"
        0x06 -> "Q-learning"
        else -> null
    }

    /** [text] prefixed with the code for [type]. */
    fun prefix(type: StrategySelector.StrategyType, text: ByteArray): ByteArray = byteArrayOf(of(type)) + text

    /** Split a decrypted DM plaintext into (sender's algorithm, or null for older builds; the text). */
    fun split(plain: ByteArray): Pair<String?, ByteArray> {
        val label = plain.firstOrNull()?.let { label(it.toInt() and 0xFF) } ?: return null to plain
        return label to plain.copyOfRange(1, plain.size)
    }
}
