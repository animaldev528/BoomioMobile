package com.nuvio.app.features.cast

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryPlistTest {

    /**
     * The reference bytes were produced by an independent implementation of the
     * same layout and handed back through Python's `plistlib`, which parsed the
     * result into exactly this dictionary. Pinning the Kotlin encoder to them is
     * what makes "the AirPlay body is a valid binary plist" a test rather than a
     * hope — this is a wire format sent to a television, not an internal detail.
     *
     * Dictionary: Content-Location / Start-Position 0.0 / X-Apple-Session-ID "ABC".
     */
    private val reference: ByteArray =
        (
            "62706C6973743030D30102030405065F1010436F6E74656E742D4C6F636174696F6E" +
                "5E53746172742D506F736974696F6E5F1012582D4170706C652D53657373696F6E" +
                "2D49445F104368747470733A2F2F626F6F6D696F2E6578616D706C652F6273732D" +
                "6461762F70726F78792F6162633132332F766964656F2E6D6B763F746F6B656E3D" +
                "746F6B2E73696723000000000000000053414243080F2231468C95" +
                "0000000000000101000000000000000700000000000000000000000000000099"
            )
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    @Test
    fun `a play body is byte-for-byte the reference encoding`() {
        val encoded = BinaryPlist.encode(
            linkedMapOf(
                "Content-Location" to "https://boomio.example/bss-dav/proxy/abc123/video.mkv?token=tok.sig",
                "Start-Position" to 0.0,
                "X-Apple-Session-ID" to "ABC",
            ),
        )
        assertContentEquals(reference, encoded)
    }

    @Test
    fun `the file starts with the bplist magic`() {
        val encoded = BinaryPlist.encode(linkedMapOf("a" to "b"))
        assertEquals("bplist00", encoded.decodeToString(0, 8))
    }

    @Test
    fun `a long value uses the extended string form`() {
        // A stream URL never fits the 15-byte short form, so this is the path
        // every real /play body takes.
        val encoded = BinaryPlist.encode(linkedMapOf("Content-Location" to "https://example.test/abcd"))
        // Layout: 8-byte magic · 3-byte dict header · [3-byte string header + 16
        // bytes "Content-Location"] · then the value object at index 30.
        // 0x5F = long ASCII string, immediately followed by a one-byte-length int.
        assertEquals(0x5F, encoded[11].toInt() and 0xFF) // key
        assertEquals(0x10, encoded[13].toInt() and 0xFF) // 16, one-byte length
        assertEquals(0x5F, encoded[30].toInt() and 0xFF) // value
        assertEquals(0x10, encoded[31].toInt() and 0xFF)
        assertEquals(25, encoded[32].toInt()) // "https://example.test/abcd"
    }

    @Test
    fun `a short value uses the compact form`() {
        // Every object after the dict header is a 0x5n short ASCII string, so a
        // length below 15 never grows a header — the encoder must not pad them.
        val encoded = BinaryPlist.encode(linkedMapOf("a" to "b"))
        assertEquals(0x51, encoded[11].toInt() and 0xFF) // key marker, 1-byte string
        assertEquals('a'.code, encoded[12].toInt() and 0xFF)
        assertEquals(0x51, encoded[13].toInt() and 0xFF) // value marker, 1-byte string
        assertEquals('b'.code, encoded[14].toInt() and 0xFF)
    }

    @Test
    fun `an oversized dictionary is refused rather than silently truncated`() {
        val tooMany = (1..15).associate { "k$it" to "v" }
        assertFailsWith<IllegalArgumentException> { BinaryPlist.encode(tooMany) }
    }
}
