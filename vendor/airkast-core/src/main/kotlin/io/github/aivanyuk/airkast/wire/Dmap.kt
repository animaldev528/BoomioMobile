package io.github.aivanyuk.airkast.wire

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * DMAP, the tagged format receivers take a track's name in: each item a four-letter tag, a 32-bit
 * big-endian length, and the value.
 */
internal object Dmap {
    /** A listing item (`mlit`) with the track's name (`minm`), artist (`asar`) and album (`asal`), each when given. */
    fun track(
        title: String?,
        artist: String?,
        album: String?,
    ): ByteArray {
        val items = ByteArrayOutputStream()
        listOf("minm" to title, "asar" to artist, "asal" to album).forEach { (tag, value) ->
            if (value != null) items.write(item(tag, value.toByteArray(Charsets.UTF_8)))
        }
        return item("mlit", items.toByteArray())
    }

    private fun item(
        tag: String,
        value: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(8 + value.size)
            .put(tag.toByteArray(Charsets.US_ASCII))
            .putInt(value.size)
            .put(value)
            .array()
}
