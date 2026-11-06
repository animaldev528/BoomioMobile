package io.github.aivanyuk.airkast.wire

import java.io.ByteArrayOutputStream

/** HomeKit's TLV8: a value longer than 255 bytes goes as consecutive fragments of one type. */
internal object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQUENCE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val FLAGS = 0x13

    /** The [ERROR] a receiver answers a wrong PIN with. */
    const val ERROR_AUTHENTICATION = 2

    const val FLAG_TRANSIENT = 0x10

    fun encode(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            var offset = 0
            do {
                val length = minOf(255, value.size - offset)
                out.write(type)
                out.write(length)
                out.write(value, offset, length)
                offset += length
            } while (offset < value.size)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Map<Int, ByteArray> {
        val values = LinkedHashMap<Int, ByteArray>()
        var offset = 0
        var previous = -1
        while (offset < bytes.size) {
            require(offset + 2 <= bytes.size) { "Truncated TLV header" }
            val type = bytes[offset].toInt() and 0xff
            val length = bytes[offset + 1].toInt() and 0xff
            require(offset + 2 + length <= bytes.size) { "Truncated TLV value" }
            val value = bytes.copyOfRange(offset + 2, offset + 2 + length)
            values[type] = if (type == previous) values.getValue(type) + value else value
            previous = type
            offset += 2 + length
        }
        return values
    }
}
