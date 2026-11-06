package io.github.aivanyuk.airkast.crypto

import java.math.BigInteger
import java.security.MessageDigest

/**
 * X25519 (RFC 7748), for pair-verify's key exchange. Written out, as Ed25519 is, because the JCA
 * has neither below Android 13. The field arithmetic follows TweetNaCl (public domain): sixteen
 * 16-bit limbs, branch-free on secrets.
 */
internal object X25519 {
    const val KEY_LENGTH = 32

    private val BASE = ByteArray(KEY_LENGTH).also { it[0] = 9 }
    private val A24 = limbs(BigInteger.valueOf(121665))

    fun publicKey(privateKey: ByteArray): ByteArray = sharedSecret(privateKey, BASE)

    fun sharedSecret(
        privateKey: ByteArray,
        publicKey: ByteArray,
    ): ByteArray {
        require(privateKey.size == KEY_LENGTH && publicKey.size == KEY_LENGTH) { "Keys must be $KEY_LENGTH bytes" }
        val z = privateKey.copyOf()
        z[31] = ((z[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = unpack(publicKey)
        val a = gf(1)
        val b = x.copyOf()
        val c = gf()
        val d = gf(1)
        val e = gf()
        val f = gf()
        for (i in 254 downTo 0) {
            val r = (z[i ushr 3].toInt() ushr (i and 7)) and 1
            select(a, b, r)
            select(c, d, r)
            add(e, a, c)
            sub(a, a, c)
            add(c, b, d)
            sub(b, b, d)
            mul(d, e, e)
            mul(f, a, a)
            mul(a, c, a)
            mul(c, b, e)
            add(e, a, c)
            sub(a, a, c)
            mul(b, a, a)
            sub(c, d, f)
            mul(a, c, A24)
            add(a, a, d)
            mul(c, c, a)
            mul(a, d, f)
            mul(d, b, x)
            mul(b, e, e)
            select(a, b, r)
            select(c, d, r)
        }
        invert(c, c)
        mul(a, a, c)
        return pack(a)
    }
}

/**
 * Ed25519 (RFC 8032), for the long-term keys a PIN pairing exchanges. A private key is the
 * 32-byte seed. Verification needs no secrecy, and is not branch-free.
 */
internal object Ed25519 {
    const val KEY_LENGTH = 32
    const val SIGNATURE_LENGTH = 64

    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val D_VALUE = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val D = limbs(D_VALUE)
    private val D2 = limbs(D_VALUE.shiftLeft(1).mod(P))
    private val SQRT_M1 = limbs(BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P))
    private val BASE_X =
        limbs(BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"))
    private val BASE_Y =
        limbs(BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"))

    /** The group order, little-endian. */
    private val L =
        longArrayOf(
            0xed,
            0xd3,
            0xf5,
            0x5c,
            0x1a,
            0x63,
            0x12,
            0x58,
            0xd6,
            0x9c,
            0xf7,
            0xa2,
            0xde,
            0xf9,
            0xde,
            0x14,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0x10,
        )

    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == KEY_LENGTH) { "Seed must be $KEY_LENGTH bytes" }
        return packPoint(scalarBase(clamped(sha512(seed))))
    }

    fun sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray {
        require(seed.size == KEY_LENGTH) { "Seed must be $KEY_LENGTH bytes" }
        val h = sha512(seed)
        val scalar = clamped(h)
        val publicKey = packPoint(scalarBase(scalar))
        val r = reduce(sha512(h.copyOfRange(32, 64), message))
        val bigR = packPoint(scalarBase(r))
        val k = reduce(sha512(bigR, publicKey, message))
        val x = LongArray(64)
        for (i in 0 until 32) x[i] = r[i].toLong() and 0xff
        for (i in 0 until 32) {
            for (j in 0 until 32) x[i + j] += (k[i].toLong() and 0xff) * (scalar[j].toLong() and 0xff)
        }
        return bigR + modL(x)
    }

    fun verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (publicKey.size != KEY_LENGTH || signature.size != SIGNATURE_LENGTH) return false
        val negA = unpackNegative(publicKey) ?: return false
        val bigR = signature.copyOfRange(0, 32)
        val k = reduce(sha512(bigR, publicKey, message))
        val p = scalarMult(negA, k)
        addPoint(p, scalarBase(signature.copyOfRange(32, 64)))
        return MessageDigest.isEqual(packPoint(p), bigR)
    }

    private fun clamped(hash: ByteArray): ByteArray =
        hash.copyOf(32).also {
            it[0] = (it[0].toInt() and 248).toByte()
            it[31] = ((it[31].toInt() and 127) or 64).toByte()
        }

    /** A point in extended coordinates: X, Y, Z, T. */
    private fun point(): Array<LongArray> = arrayOf(gf(), gf(1), gf(1), gf())

    private fun addPoint(
        p: Array<LongArray>,
        q: Array<LongArray>,
    ) {
        val a = gf()
        val b = gf()
        val c = gf()
        val d = gf()
        val t = gf()
        val e = gf()
        val f = gf()
        val g = gf()
        val h = gf()
        sub(a, p[1], p[0])
        sub(t, q[1], q[0])
        mul(a, a, t)
        add(b, p[0], p[1])
        add(t, q[0], q[1])
        mul(b, b, t)
        mul(c, p[3], q[3])
        mul(c, c, D2)
        mul(d, p[2], q[2])
        add(d, d, d)
        sub(e, b, a)
        sub(f, d, c)
        add(g, d, c)
        add(h, b, a)
        mul(p[0], e, f)
        mul(p[1], h, g)
        mul(p[2], g, f)
        mul(p[3], e, h)
    }

    private fun scalarMult(
        q: Array<LongArray>,
        scalar: ByteArray,
    ): Array<LongArray> {
        val p = point()
        for (i in 255 downTo 0) {
            val bit = (scalar[i / 8].toInt() ushr (i and 7)) and 1
            for (n in 0 until 4) select(p[n], q[n], bit)
            addPoint(q, p)
            addPoint(p, p)
            for (n in 0 until 4) select(p[n], q[n], bit)
        }
        return p
    }

    private fun scalarBase(scalar: ByteArray): Array<LongArray> {
        val t = gf()
        mul(t, BASE_X, BASE_Y)
        return scalarMult(arrayOf(BASE_X.copyOf(), BASE_Y.copyOf(), gf(1), t), scalar)
    }

    private fun packPoint(p: Array<LongArray>): ByteArray {
        val zi = gf()
        val tx = gf()
        val ty = gf()
        invert(zi, p[2])
        mul(tx, p[0], zi)
        mul(ty, p[1], zi)
        val out = pack(ty)
        out[31] = (out[31].toInt() xor (parity(tx) shl 7)).toByte()
        return out
    }

    /** -A for the public key A, or null when it is not on the curve. */
    private fun unpackNegative(key: ByteArray): Array<LongArray>? {
        val r = point()
        val t = gf()
        val check = gf()
        val num = gf()
        val den = gf()
        val den2 = gf()
        val den4 = gf()
        val den6 = gf()
        r[1] = unpack(key)
        mul(num, r[1], r[1])
        mul(den, num, D)
        sub(num, num, r[2])
        add(den, r[2], den)
        mul(den2, den, den)
        mul(den4, den2, den2)
        mul(den6, den4, den2)
        mul(t, den6, num)
        mul(t, t, den)
        pow2523(t, t)
        mul(t, t, num)
        mul(t, t, den)
        mul(t, t, den)
        mul(r[0], t, den)
        mul(check, r[0], r[0])
        mul(check, check, den)
        if (!equal(check, num)) mul(r[0], r[0], SQRT_M1)
        mul(check, r[0], r[0])
        mul(check, check, den)
        if (!equal(check, num)) return null
        if (parity(r[0]) == (key[31].toInt() and 0xff) ushr 7) sub(r[0], gf(), r[0])
        mul(r[3], r[0], r[1])
        return r
    }

    private fun reduce(hash: ByteArray): ByteArray = modL(LongArray(64) { hash[it].toLong() and 0xff })

    private fun modL(x: LongArray): ByteArray {
        for (i in 63 downTo 32) {
            var carry = 0L
            var j = i - 32
            while (j < i - 12) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        var carry = 0L
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * L[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255
        }
        for (j in 0 until 32) x[j] -= carry * L[j]
        val r = ByteArray(32)
        for (i in 0 until 32) {
            x[i + 1] += x[i] shr 8
            r[i] = (x[i] and 255).toByte()
        }
        return r
    }

    private fun sha512(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").run {
            parts.forEach(::update)
            digest()
        }
}

// GF(2^255 - 19) as sixteen signed 64-bit limbs of 16 bits each.

private fun gf(first: Long = 0): LongArray = LongArray(16).also { it[0] = first }

private fun limbs(value: BigInteger): LongArray = LongArray(16) { value.shiftRight(16 * it).toLong() and 0xffff }

private fun unpack(bytes: ByteArray): LongArray =
    LongArray(16) { (bytes[2 * it].toLong() and 0xff) + ((bytes[2 * it + 1].toLong() and 0xff) shl 8) }
        .also { it[15] = it[15] and 0x7fff }

private fun carry(o: LongArray) {
    for (i in 0 until 16) {
        o[i] += 1L shl 16
        val c = o[i] shr 16
        if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
        o[i] -= c shl 16
    }
}

/** Swaps [p] and [q] when [bit] is 1, without a branch. */
private fun select(
    p: LongArray,
    q: LongArray,
    bit: Int,
) {
    val mask = (bit - 1).toLong().inv()
    for (i in 0 until 16) {
        val t = mask and (p[i] xor q[i])
        p[i] = p[i] xor t
        q[i] = q[i] xor t
    }
}

private fun pack(n: LongArray): ByteArray {
    val t = n.copyOf()
    val m = gf()
    carry(t)
    carry(t)
    carry(t)
    repeat(2) {
        m[0] = t[0] - 0xffed
        for (i in 1 until 15) {
            m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
            m[i - 1] = m[i - 1] and 0xffff
        }
        m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
        val b = ((m[15] shr 16) and 1).toInt()
        m[14] = m[14] and 0xffff
        select(t, m, 1 - b)
    }
    val o = ByteArray(32)
    for (i in 0 until 16) {
        o[2 * i] = t[i].toByte()
        o[2 * i + 1] = (t[i] shr 8).toByte()
    }
    return o
}

private fun equal(
    a: LongArray,
    b: LongArray,
): Boolean = MessageDigest.isEqual(pack(a), pack(b))

private fun parity(a: LongArray): Int = pack(a)[0].toInt() and 1

private fun add(
    o: LongArray,
    a: LongArray,
    b: LongArray,
) {
    for (i in 0 until 16) o[i] = a[i] + b[i]
}

private fun sub(
    o: LongArray,
    a: LongArray,
    b: LongArray,
) {
    for (i in 0 until 16) o[i] = a[i] - b[i]
}

private fun mul(
    o: LongArray,
    a: LongArray,
    b: LongArray,
) {
    val t = LongArray(31)
    for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
    for (i in 0 until 15) t[i] += 38 * t[i + 16]
    for (i in 0 until 16) o[i] = t[i]
    carry(o)
    carry(o)
}

private fun invert(
    o: LongArray,
    i: LongArray,
) {
    val c = i.copyOf()
    for (a in 253 downTo 0) {
        mul(c, c, c)
        if (a != 2 && a != 4) mul(c, c, i)
    }
    c.copyInto(o)
}

private fun pow2523(
    o: LongArray,
    i: LongArray,
) {
    val c = i.copyOf()
    for (a in 250 downTo 0) {
        mul(c, c, c)
        if (a != 1) mul(c, c, i)
    }
    c.copyInto(o)
}
