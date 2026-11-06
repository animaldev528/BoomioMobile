package io.github.aivanyuk.airkast.crypto

import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * ChaCha20-Poly1305 AEAD (RFC 8439). Written out because the JCA has no provider for it below
 * Android 9, and every message on a receiver connection goes through it.
 */
internal object ChaCha20Poly1305 {
    const val KEY_LENGTH = 32
    const val NONCE_LENGTH = 12
    const val TAG_LENGTH = 16

    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        checkSizes(key, nonce)
        val out = ByteArray(plaintext.size + TAG_LENGTH)
        xor(key, nonce, plaintext, 0, plaintext.size, out)
        tag(key, nonce, aad, out, plaintext.size).copyInto(out, plaintext.size)
        return out
    }

    fun open(
        key: ByteArray,
        nonce: ByteArray,
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        checkSizes(key, nonce)
        if (sealed.size < TAG_LENGTH) throw GeneralSecurityException("Sealed message shorter than its tag")
        val length = sealed.size - TAG_LENGTH
        val expected = tag(key, nonce, aad, sealed, length)
        if (!MessageDigest.isEqual(expected, sealed.copyOfRange(length, sealed.size))) {
            throw GeneralSecurityException("Authentication tag mismatch")
        }
        val out = ByteArray(length)
        xor(key, nonce, sealed, 0, length, out)
        return out
    }

    private fun checkSizes(
        key: ByteArray,
        nonce: ByteArray,
    ) {
        require(key.size == KEY_LENGTH) { "Key must be $KEY_LENGTH bytes" }
        require(nonce.size == NONCE_LENGTH) { "Nonce must be $NONCE_LENGTH bytes" }
    }

    /** XORs [length] bytes of [input] with the keystream from block counter 1. */
    private fun xor(
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        offset: Int,
        length: Int,
        out: ByteArray,
    ) {
        var counter = 1
        var position = 0
        while (position < length) {
            val stream = block(key, nonce, counter++)
            val n = minOf(64, length - position)
            for (i in 0 until n) {
                out[position + i] =
                    (input[offset + position + i].toInt() xor stream[i].toInt()).toByte()
            }
            position += n
        }
    }

    private fun tag(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
        length: Int,
    ): ByteArray {
        val oneTimeKey = block(key, nonce, 0).copyOf(32)
        val mac = Poly1305(oneTimeKey)
        mac.update(aad, 0, aad.size)
        mac.pad(aad.size)
        mac.update(ciphertext, 0, length)
        mac.pad(length)
        val lengths = ByteArray(16)
        putLongLe(lengths, 0, aad.size.toLong())
        putLongLe(lengths, 8, length.toLong())
        mac.update(lengths, 0, 16)
        return mac.finish()
    }

    internal fun block(
        key: ByteArray,
        nonce: ByteArray,
        counter: Int,
    ): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = intLe(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = intLe(nonce, i * 4)
        val x = state.copyOf()
        repeat(10) {
            quarter(x, 0, 4, 8, 12)
            quarter(x, 1, 5, 9, 13)
            quarter(x, 2, 6, 10, 14)
            quarter(x, 3, 7, 11, 15)
            quarter(x, 0, 5, 10, 15)
            quarter(x, 1, 6, 11, 12)
            quarter(x, 2, 7, 8, 13)
            quarter(x, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = x[i] + state[i]
            out[i * 4] = v.toByte()
            out[i * 4 + 1] = (v ushr 8).toByte()
            out[i * 4 + 2] = (v ushr 16).toByte()
            out[i * 4 + 3] = (v ushr 24).toByte()
        }
        return out
    }

    private fun quarter(
        x: IntArray,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ) {
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun intLe(
        b: ByteArray,
        o: Int,
    ): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun putLongLe(
        b: ByteArray,
        o: Int,
        v: Long,
    ) {
        for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte()
    }

    /**
     * Poly1305 (RFC 8439, section 2.5) in five 26-bit limbs, so every product fits a Long: an audio
     * stream tags over a hundred packets a second.
     */
    private class Poly1305(
        key: ByteArray,
    ) {
        private val r0 = intLe(key, 0).toLong() and 0x3ffffff
        private val r1 = (intLe(key, 3).toLong() ushr 2) and 0x3ffff03
        private val r2 = (intLe(key, 6).toLong() ushr 4) and 0x3ffc0ff
        private val r3 = (intLe(key, 9).toLong() ushr 6) and 0x3f03fff
        private val r4 = (intLe(key, 12).toLong() ushr 8) and 0x00fffff
        private val s1 = r1 * 5
        private val s2 = r2 * 5
        private val s3 = r3 * 5
        private val s4 = r4 * 5
        private val pad = LongArray(4) { intLe(key, 16 + it * 4).toLong() and MASK_32 }
        private var h0 = 0L
        private var h1 = 0L
        private var h2 = 0L
        private var h3 = 0L
        private var h4 = 0L
        private val pending = ByteArray(16)
        private var pendingSize = 0

        fun update(
            data: ByteArray,
            offset: Int,
            length: Int,
        ) {
            var at = offset
            val end = offset + length
            if (pendingSize > 0) {
                val n = minOf(16 - pendingSize, end - at)
                data.copyInto(pending, pendingSize, at, at + n)
                pendingSize += n
                at += n
                if (pendingSize < 16) return
                block(pending, 0, HIBIT)
                pendingSize = 0
            }
            while (end - at >= 16) {
                block(data, at, HIBIT)
                at += 16
            }
            data.copyInto(pending, 0, at, end)
            pendingSize = end - at
        }

        /** Zero-pads the current input to a 16-byte boundary, as the AEAD construction asks. */
        fun pad(length: Int) {
            if (length % 16 != 0) update(ByteArray(16 - length % 16), 0, 16 - length % 16)
        }

        fun finish(): ByteArray {
            if (pendingSize > 0) {
                pending[pendingSize] = 1
                pending.fill(0, pendingSize + 1)
                block(pending, 0, 0)
            }
            var c = h1 ushr 26
            h1 = h1 and MASK_26
            h2 += c
            c = h2 ushr 26
            h2 = h2 and MASK_26
            h3 += c
            c = h3 ushr 26
            h3 = h3 and MASK_26
            h4 += c
            c = h4 ushr 26
            h4 = h4 and MASK_26
            h0 += c * 5
            c = h0 ushr 26
            h0 = h0 and MASK_26
            h1 += c

            // h - p, kept when it does not go below zero, which is when h >= p.
            var g0 = h0 + 5
            c = g0 ushr 26
            g0 = g0 and MASK_26
            var g1 = h1 + c
            c = g1 ushr 26
            g1 = g1 and MASK_26
            var g2 = h2 + c
            c = g2 ushr 26
            g2 = g2 and MASK_26
            var g3 = h3 + c
            c = g3 ushr 26
            g3 = g3 and MASK_26
            val g4 = h4 + c - (1L shl 26)
            if (g4 >= 0) {
                h0 = g0
                h1 = g1
                h2 = g2
                h3 = g3
                h4 = g4
            }

            val words =
                longArrayOf(
                    (h0 or (h1 shl 26)) and MASK_32,
                    ((h1 ushr 6) or (h2 shl 20)) and MASK_32,
                    ((h2 ushr 12) or (h3 shl 14)) and MASK_32,
                    ((h3 ushr 18) or (h4 shl 8)) and MASK_32,
                )
            val out = ByteArray(16)
            var carry = 0L
            for (i in 0 until 4) {
                val f = words[i] + pad[i] + carry
                carry = f ushr 32
                for (b in 0 until 4) out[i * 4 + b] = (f ushr (8 * b)).toByte()
            }
            return out
        }

        private fun block(
            m: ByteArray,
            at: Int,
            hibit: Long,
        ) {
            h0 += intLe(m, at).toLong() and MASK_26
            h1 += (intLe(m, at + 3).toLong() ushr 2) and MASK_26
            h2 += (intLe(m, at + 6).toLong() ushr 4) and MASK_26
            h3 += (intLe(m, at + 9).toLong() ushr 6) and MASK_26
            h4 += (intLe(m, at + 12).toLong() and MASK_32 ushr 8) or hibit

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            var c = d0 ushr 26
            h0 = d0 and MASK_26
            d1 += c
            c = d1 ushr 26
            h1 = d1 and MASK_26
            d2 += c
            c = d2 ushr 26
            h2 = d2 and MASK_26
            d3 += c
            c = d3 ushr 26
            h3 = d3 and MASK_26
            d4 += c
            c = d4 ushr 26
            h4 = d4 and MASK_26
            h0 += c * 5
            c = h0 ushr 26
            h0 = h0 and MASK_26
            h1 += c
        }

        private companion object {
            const val MASK_26 = 0x3ffffffL
            const val MASK_32 = 0xffffffffL
            const val HIBIT = 1L shl 24
        }
    }
}
