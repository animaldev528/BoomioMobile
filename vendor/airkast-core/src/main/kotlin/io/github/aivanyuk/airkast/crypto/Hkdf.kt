package io.github.aivanyuk.airkast.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA512 (RFC 5869) for the 32-byte channel keys a receiver derives from a pairing secret. */
internal object Hkdf {
    fun sha512(
        secret: ByteArray,
        salt: String,
        info: String,
        length: Int = 32,
    ): ByteArray {
        require(length <= 64) { "One HMAC block is all the receiver protocol needs" }
        val prk = hmac(salt.toByteArray(), secret)
        return hmac(prk, info.toByteArray() + byteArrayOf(1)).copyOf(length)
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA512").run {
            init(SecretKeySpec(key, "HmacSHA512"))
            doFinal(data)
        }
}
