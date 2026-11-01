package dev.troupe.android.testing

import dev.troupe.android.servers.TokenCipher

/** Deterministic stand-in for the Keystore-backed cipher: XOR with a constant, bound to the associated data. */
class XorTokenCipher : TokenCipher {
    override fun encrypt(plain: String, associated: String): ByteArray =
        (associated + "\u0000" + plain).toByteArray().map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(cipher: ByteArray, associated: String): String {
        val text = String(cipher.map { (it.toInt() xor 0x5A).toByte() }.toByteArray())
        val prefix = associated + "\u0000"
        require(text.startsWith(prefix)) { "associated data mismatch" }
        return text.removePrefix(prefix)
    }
}
