package com.nuvio.app.features.cast

/**
 * The smallest binary-plist (bplist00) writer that can carry an AirPlay `/play`
 * body.
 *
 * An Apple TV does not read the media URL out of a `Content-Location` header —
 * that is the shape third-party receivers (AirScreen and friends) accept, and it
 * is what this app used to send. Apple's own senders post a binary plist with
 * `Content-Location`, `Start-Position` and `X-Apple-Session-ID` in it, so a
 * header-shaped request is refused with 403 and reads as "this Apple TV wants
 * pairing" when the truth is "wrong wire format".
 *
 * Only what that body needs is implemented: a flat dictionary of strings and
 * numbers. Nothing here parses a plist, and nothing here is general-purpose — a
 * feature that needs more belongs in a real library, not in this file.
 */
internal object BinaryPlist {

    private val MAGIC = "bplist00".encodeToByteArray()

    /**
     * Encode a flat dictionary. Values may be [String], [Double] or [Int].
     *
     * Object layout, in the order it is written:
     *   0        the dictionary
     *   1..n     its keys
     *   n+1..2n  its values
     *
     * which keeps every reference inside one byte for the few entries this body
     * ever carries.
     */
    fun encode(entries: Map<String, Any>): ByteArray {
        require(entries.size in 1..14) { "binary plist dictionary size must be 1..14" }
        val keys = entries.keys.toList()
        val n = keys.size

        val objects = ArrayList<ByteArray>(1 + 2 * n)
        val dictionary = ByteArray(1 + 2 * n)
        dictionary[0] = (0xD0 or n).toByte()   // 0xD0 = dictionary, low nibble = count
        for (i in 0 until n) {
            dictionary[1 + i] = (1 + i).toByte()          // key refs
            dictionary[1 + n + i] = (1 + n + i).toByte()  // value refs
        }
        objects.add(dictionary)
        for (key in keys) objects.add(ascii(key))
        for (key in keys) objects.add(value(entries.getValue(key)))

        val out = ArrayList<Byte>(256)
        for (byte in MAGIC) out.add(byte)

        val offsets = IntArray(objects.size)
        for (i in objects.indices) {
            offsets[i] = out.size
            for (byte in objects[i]) out.add(byte)
        }

        val tableOffset = out.size
        val offsetSize = when {
            tableOffset < 0x100 -> 1
            tableOffset < 0x10000 -> 2
            else -> 4
        }
        for (offset in offsets) {
            for (shift in offsetSize - 1 downTo 0) {
                out.add(((offset shr (8 * shift)) and 0xFF).toByte())
            }
        }

        // Trailer: 6 unused bytes, then the sizes, the object count, the top
        // object and the offset-table position — each of the last three as
        // unsigned 64-bit big-endian.
        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = 1                              // object ref size
        putLong(trailer, 8, objects.size.toLong())
        putLong(trailer, 16, 0L)
        putLong(trailer, 24, tableOffset.toLong())
        for (byte in trailer) out.add(byte)

        return out.toByteArray()
    }

    private fun value(raw: Any): ByteArray = when (raw) {
        is String -> ascii(raw)
        is Double -> real(raw)
        is Int -> integer(raw.toLong())
        else -> error("unsupported binary plist value: ${raw::class.simpleName}")
    }

    /**
     * ASCII string. Under 15 bytes the length rides in the marker's low nibble;
     * longer strings use `0x5F` followed by an integer object. A stream URL —
     * edge host, path and token — is always the latter.
     */
    private fun ascii(text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        return if (bytes.size < 15) {
            byteArrayOf((0x50 or bytes.size).toByte()) + bytes
        } else {
            byteArrayOf(0x5F) + integer(bytes.size.toLong()) + bytes
        }
    }

    /** `0x23` marks a 64-bit IEEE-754 double. */
    private fun real(v: Double): ByteArray {
        val out = ByteArray(9)
        out[0] = 0x23
        val bits = v.toRawBits()
        for (i in 0 until 8) out[1 + i] = ((bits shr (8 * (7 - i))) and 0xFF).toByte()
        return out
    }

    /** Integer object: `0x10|(2^n - 1)` with n = 0, 1 or 2 bytes of payload. */
    private fun integer(v: Long): ByteArray = when {
        v in 0..0xFF -> byteArrayOf(0x10, v.toByte())
        v in 0..0xFFFF -> byteArrayOf(0x11, (v shr 8).toByte(), v.toByte())
        else -> byteArrayOf(
            0x12,
            (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte(),
        )
    }

    private fun putLong(out: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) out[at + i] = ((v shr (8 * (7 - i))) and 0xFF).toByte()
    }
}
