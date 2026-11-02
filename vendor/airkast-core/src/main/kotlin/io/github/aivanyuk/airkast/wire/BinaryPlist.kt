package io.github.aivanyuk.airkast.wire

import java.io.ByteArrayOutputStream
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * Apple's binary property list, as much of it as receivers send: dictionaries, arrays, strings,
 * integers, reals, booleans, data and dates. Values map to Kotlin as `Map<String, Any?>`,
 * `List<Any?>`, `String`, `Long`, `Double`, `Boolean`, `ByteArray` and [PlistDate]; an absent
 * value (plist `null`) reads as `null`.
 */
internal object BinaryPlist {
    private val MAGIC = "bplist00".toByteArray()

    fun isPlist(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)

    fun decode(bytes: ByteArray): Any? {
        require(isPlist(bytes) && bytes.size >= MAGIC.size + 32) { "Not a binary plist" }
        val trailer = ByteBuffer.wrap(bytes, bytes.size - 32, 32)
        // Through Buffer: the ByteBuffer.position(int) overload is Java 9's, and older Android
        // versions throw NoSuchMethodError on a call compiled against it (Animal Sniffer).
        (trailer as Buffer).position(trailer.position() + 6)
        val offsetSize = trailer.get().toInt() and 0xff
        val refSize = trailer.get().toInt() and 0xff
        val objectCount = trailer.long.toInt()
        val top = trailer.long.toInt()
        val tableOffset = trailer.long.toInt()
        require(objectCount in 1..MAX_OBJECTS && top in 0 until objectCount) { "Bad plist trailer" }
        val offsets = IntArray(objectCount) { readUnsigned(bytes, tableOffset + it * offsetSize, offsetSize).toInt() }
        return Reader(bytes, offsets, refSize).read(top, 0)
    }

    fun encode(value: Any?): ByteArray = Writer().write(value)

    private class Reader(
        val bytes: ByteArray,
        val offsets: IntArray,
        val refSize: Int,
    ) {
        fun read(
            index: Int,
            depth: Int,
        ): Any? {
            require(depth < MAX_DEPTH) { "Plist nests too deep" }
            var at = offsets[index]
            val marker = bytes[at].toInt() and 0xff
            val kind = marker shr 4
            val low = marker and 0x0f
            at++

            fun count(): Int {
                if (low != 0x0f) return low
                val intMarker = bytes[at].toInt() and 0xff
                require(intMarker shr 4 == 0x1) { "Bad plist count" }
                val size = 1 shl (intMarker and 0x0f)
                val value = readUnsigned(bytes, at + 1, size).toInt()
                at += 1 + size
                return value
            }
            return when (kind) {
                0x0 -> {
                    when (marker) {
                        0x08 -> false
                        0x09 -> true
                        else -> null
                    }
                }

                0x1 -> {
                    val size = 1 shl low
                    if (size == 8) ByteBuffer.wrap(bytes, at, 8).long else readUnsigned(bytes, at, size)
                }

                0x2 -> {
                    if (low ==
                        2
                    ) {
                        ByteBuffer.wrap(bytes, at, 4).float.toDouble()
                    } else {
                        ByteBuffer.wrap(bytes, at, 8).double
                    }
                }

                0x3 -> {
                    PlistDate(ByteBuffer.wrap(bytes, at, 8).double)
                }

                0x4 -> {
                    count().let { bytes.copyOfRange(at, at + it) }
                }

                0x5 -> {
                    count().let { String(bytes, at, it, Charsets.US_ASCII) }
                }

                0x6 -> {
                    count().let { String(bytes, at, it * 2, Charsets.UTF_16BE) }
                }

                0x8 -> {
                    readUnsigned(bytes, at, low + 1)
                }

                0xA -> {
                    val n = count()
                    List(n) { read(ref(at + it * refSize), depth + 1) }
                }

                0xD -> {
                    val n = count()
                    val map = LinkedHashMap<String, Any?>(n)
                    for (i in 0 until n) {
                        val key =
                            read(ref(at + i * refSize), depth + 1) as? String
                                ?: throw IllegalArgumentException("Plist dictionary key is not a string")
                        map[key] = read(ref(at + (n + i) * refSize), depth + 1)
                    }
                    map
                }

                else -> {
                    throw IllegalArgumentException("Unsupported plist object 0x${marker.toString(16)}")
                }
            }
        }

        private fun ref(at: Int): Int =
            readUnsigned(bytes, at, refSize).toInt().also {
                require(it in offsets.indices) { "Plist reference out of range" }
            }
    }

    private class Writer {
        private val values = mutableListOf<Any?>()
        private val children = mutableMapOf<Int, List<Int>>()

        fun write(root: Any?): ByteArray {
            flatten(root)
            val refSize = sizeFor(values.size.toLong())
            val encoded = values.indices.map { encodeObject(it, refSize) }
            val out = ByteArrayOutputStream()
            out.write(MAGIC)
            val offsets = encoded.map { bytes -> out.size().toLong().also { out.write(bytes) } }
            val tableOffset = out.size().toLong()
            val offsetSize = sizeFor(tableOffset)
            offsets.forEach { out.write(unsigned(it, offsetSize)) }
            val trailer = ByteBuffer.allocate(32)
            (trailer as Buffer).position(6)
            trailer.put(offsetSize.toByte()).put(refSize.toByte())
            trailer.putLong(values.size.toLong()).putLong(0).putLong(tableOffset)
            out.write(trailer.array())
            return out.toByteArray()
        }

        /** Gives every value an index, parents before children, so the root is object 0. */
        private fun flatten(value: Any?): Int {
            val index = values.size
            values += value
            when (value) {
                is Map<*, *> -> {
                    val keys =
                        value.keys.map {
                            flatten(it as? String ?: throw IllegalArgumentException("Plist keys must be strings"))
                        }
                    val vals = value.values.map { flatten(it) }
                    children[index] = keys + vals
                }

                is List<*> -> {
                    children[index] = value.map { flatten(it) }
                }

                is Array<*> -> {
                    children[index] = value.map { flatten(it) }
                }
            }
            return index
        }

        private fun encodeObject(
            index: Int,
            refSize: Int,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            when (val value = values[index]) {
                null -> {
                    out.write(0x00)
                }

                is Boolean -> {
                    out.write(if (value) 0x09 else 0x08)
                }

                is Int, is Long, is Short, is Byte -> {
                    writeInt(out, (value as Number).toLong())
                }

                is Float, is Double -> {
                    out.write(0x23)
                    out.write(ByteBuffer.allocate(8).putDouble((value as Number).toDouble()).array())
                }

                is PlistDate -> {
                    out.write(0x33)
                    out.write(ByteBuffer.allocate(8).putDouble(value.secondsSince2001).array())
                }

                is ByteArray -> {
                    header(out, 0x4, value.size)
                    out.write(value)
                }

                is String -> {
                    if (value.all { it.code < 0x80 }) {
                        header(out, 0x5, value.length)
                        out.write(value.toByteArray(Charsets.US_ASCII))
                    } else {
                        header(out, 0x6, value.length)
                        out.write(value.toByteArray(Charsets.UTF_16BE))
                    }
                }

                is Map<*, *> -> {
                    header(out, 0xD, value.size)
                    children.getValue(index).forEach { out.write(unsigned(it.toLong(), refSize)) }
                }

                is List<*>, is Array<*> -> {
                    val refs = children.getValue(index)
                    header(out, 0xA, refs.size)
                    refs.forEach { out.write(unsigned(it.toLong(), refSize)) }
                }

                else -> {
                    throw IllegalArgumentException("Cannot encode ${value::class.java.simpleName} in a plist")
                }
            }
            return out.toByteArray()
        }

        private fun header(
            out: ByteArrayOutputStream,
            kind: Int,
            count: Int,
        ) {
            if (count < 15) {
                out.write((kind shl 4) or count)
            } else {
                out.write((kind shl 4) or 0x0f)
                writeInt(out, count.toLong())
            }
        }

        private fun writeInt(
            out: ByteArrayOutputStream,
            value: Long,
        ) {
            val size = if (value < 0) 8 else sizeFor(value)
            out.write(0x10 or Integer.numberOfTrailingZeros(size))
            out.write(unsigned(value, size))
        }
    }

    private fun sizeFor(value: Long): Int =
        when {
            value < 0x100 -> 1
            value < 0x10000 -> 2
            value < 0x100000000 -> 4
            else -> 8
        }

    private fun unsigned(
        value: Long,
        size: Int,
    ): ByteArray =
        ByteArray(size) {
            (value ushr (8 * (size - 1 - it))).toByte()
        }

    private fun readUnsigned(
        bytes: ByteArray,
        at: Int,
        size: Int,
    ): Long {
        var value = 0L
        for (i in 0 until size) value = (value shl 8) or (bytes[at + i].toLong() and 0xff)
        return value
    }

    private const val MAX_OBJECTS = 1 shl 16
    private const val MAX_DEPTH = 32
}

/** A plist date, in seconds since 2001-01-01 UTC as Apple counts them. */
internal data class PlistDate(
    val secondsSince2001: Double,
)
