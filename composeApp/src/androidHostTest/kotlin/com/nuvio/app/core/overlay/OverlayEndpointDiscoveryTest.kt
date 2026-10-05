package com.nuvio.app.core.overlay

import android.app.Application
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The discovery ladder's pure half: the two advert formats, the `host:port` parser, and the DNS
 * wire parser behind rung 2.
 *
 * **What this covers, and what it deliberately does not.** Everything here is deterministic and
 * offline — no socket is opened, no browse is started, no resolver is consulted. What is left
 * out is precisely the part that cannot be tested this way: whether the *gate* picks the right
 * candidate on a real network. That is a device test, and it belongs with the rest of Stage 8.
 *
 * ⚠️ **The DNS parser is the reason this file exists.** It is the only place in the overlay
 * where a wrong answer is *silent*: a mis-read name compression offset or a mis-read `TXT`
 * rdata yields a well-formed endpoint with the wrong key, and the only symptom is a tunnel that
 * never handshakes. Every case below is one that a plausible implementation gets wrong.
 *
 * Robolectric because `validServerKeyOrNull` reaches `android.util.Base64` through
 * `decodeWireGuardKey`. Nothing here needs a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayEndpointDiscoveryTest {

    // -----------------------------------------------------------------------------------------
    // host:port parsing — rung 3's input
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a bare host takes the default port`() {
        assertEquals("192.168.68.65" to 51820, parseEndpointAuthority("192.168.68.65", 51820))
    }

    @Test
    fun `host and port split on the single colon`() {
        assertEquals("192.168.68.65" to 1234, parseEndpointAuthority("192.168.68.65:1234", 51820))
    }

    @Test
    fun `a pasted URL loses its scheme, path and trailing slash`() {
        // The shape someone copies out of a browser or a chat message, which is the whole
        // reason this parser is forgiving.
        assertEquals(
            "beamstream.tracemonkey.org" to 51820,
            parseEndpointAuthority("https://beamstream.tracemonkey.org/", 51820),
        )
        assertEquals(
            "beamstream.tracemonkey.org" to 9000,
            parseEndpointAuthority("wg://beamstream.tracemonkey.org:9000/some/path", 51820),
        )
    }

    @Test
    fun `a bracketed IPv6 literal keeps its colons and takes an optional port`() {
        assertEquals("fd00::1" to 51820, parseEndpointAuthority("[fd00::1]", 51820))
        assertEquals("fd00::1" to 51821, parseEndpointAuthority("[fd00::1]:51821", 51820))
    }

    @Test
    fun `an unbracketed IPv6 literal is read as an address, not as host colon port`() {
        // ⚠️ The bug this pins: splitting `::1:51820` on its last colon would produce a *host*
        // of `::1` and a port of 51820 from a string that is also a perfectly valid address.
        // Guessing right here would still be guessing; the parser takes it whole.
        assertEquals("::1:51820" to 51820, parseEndpointAuthority("::1:51820", 51820))
    }

    @Test
    fun `a nonsense port is rejected rather than truncated`() {
        assertNull(parseEndpointAuthority("host:99999999999", 51820))
        assertNull(parseEndpointAuthority("host:0", 51820))
        assertNull(parseEndpointAuthority("host:70000", 51820))
        assertNull(parseEndpointAuthority("host:", 51820))
        assertNull(parseEndpointAuthority(":51820", 51820))
    }

    @Test
    fun `blank and scheme-only input is rejected`() {
        assertNull(parseEndpointAuthority("", 51820))
        assertNull(parseEndpointAuthority("   ", 51820))
        assertNull(parseEndpointAuthority("https://", 51820))
    }

    // -----------------------------------------------------------------------------------------
    // The advert formats
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the mDNS advert yields the full tuple`() {
        val tuple = parseMdnsAdvertTxt(
            mapOf(
                "addr" to "10.77.0.1".toByteArray(),
                "pubkey" to PUBLISHED_SERVER_KEY_B64.toByteArray(),
                "port" to "51820".toByteArray(),
                "v" to "1".toByteArray(),
            ),
        )
        assertEquals(PUBLISHED_SERVER_KEY_B64, tuple.serverPublicKeyBase64)
        assertEquals(51820, tuple.port)
    }

    @Test
    fun `the mDNS advert accepts pk as well as pubkey`() {
        // The two channels spell the field differently, and a rung that only knew one spelling
        // would fail for a reason that has nothing to do with discovery.
        val tuple = parseMdnsAdvertTxt(mapOf("pk" to PUBLISHED_SERVER_KEY_B64.toByteArray()))
        assertEquals(PUBLISHED_SERVER_KEY_B64, tuple.serverPublicKeyBase64)
    }

    @Test
    fun `an empty or absent advert is a tuple of nulls, not a crash`() {
        assertEquals(OverlayAdvertTuple(null, null), parseMdnsAdvertTxt(null))
        assertEquals(OverlayAdvertTuple(null, null), parseMdnsAdvertTxt(emptyMap()))
    }

    @Test
    fun `a blank value in an advert reads as absent`() {
        // An advert written by a shell script can very easily publish `pubkey=` with nothing
        // after it; that must not become a key that is the empty string.
        val tuple = parseMdnsAdvertTxt(mapOf("pubkey" to "".toByteArray(), "port" to "  ".toByteArray()))
        assertNull(tuple.serverPublicKeyBase64)
        assertNull(tuple.port)
    }

    @Test
    fun `the DuckDNS TXT record parses into the same tuple as the mDNS advert`() {
        val tuple = parseDnsTxtRecord(listOf("v1;pk=$PUBLISHED_SERVER_KEY_B64;port=51820;prov=0"))
        assertEquals(PUBLISHED_SERVER_KEY_B64, tuple.serverPublicKeyBase64)
        assertEquals(51820, tuple.port)
    }

    @Test
    fun `a name carrying several TXT records has them joined before parsing`() {
        // ⚠️ The input here is a list of **records**, not a list of fragments — see
        // `a TXT rdata split across several character-strings is reassembled end to end` below,
        // which is the layer that owns fragments. A publisher is free to put the tuple's fields
        // in two records instead of one; joining on `;` is what makes that readable as one
        // tuple. (This test previously fed a *fragment* split to this function, which is an
        // input shape the production path never produces — the failure was in the test's
        // placement of the boundary, not in the parser.)
        val tuple = parseDnsTxtRecord(
            listOf("v1;pk=$PUBLISHED_SERVER_KEY_B64", "port=51820"),
        )
        assertEquals(PUBLISHED_SERVER_KEY_B64, tuple.serverPublicKeyBase64)
        assertEquals(51820, tuple.port)
    }

    @Test
    fun `a TXT rdata split across several character-strings is reassembled end to end`() {
        // This is the layer that owns the split. RFC 1035 lets a publisher break one logical
        // record across several length-prefixed strings, and — the part that makes this worth a
        // test — the break can land *inside a value*, so the reassembly must be plain
        // concatenation with no separator inserted. A `;`-join here would turn a key split
        // mid-base64 into two fields, the second of which is silently ignored as unknown, and
        // the only symptom downstream is a tunnel that never handshakes.
        //
        // Driven through `parseResponse`, not by calling `parseTxtRdata` directly, because the
        // question being asked is *where* the split is handled — a unit test of the inner
        // function would pass even if the caller never reached it.
        val half = PUBLISHED_SERVER_KEY_B64.length / 2
        val response = dnsResponse(
            id = 1,
            question = "boomio-local.tracemonkey.org",
            questionType = TYPE_TXT,
            answers = listOf(
                Answer(
                    TYPE_TXT,
                    txtRdata("v1;pk=${PUBLISHED_SERVER_KEY_B64.take(half)}", "${PUBLISHED_SERVER_KEY_B64.drop(half)};port=51820"),
                ),
            ),
        )

        val message = OverlayDnsClient.parseResponse(response, 1)
        val tuple = parseDnsTxtRecord(message!!.txt)
        assertEquals(PUBLISHED_SERVER_KEY_B64, tuple.serverPublicKeyBase64)
        assertEquals(51820, tuple.port)
    }

    @Test
    fun `an unknown TXT field is ignored rather than fatal`() {
        // The format is versioned so a server can add fields without a client update.
        val tuple = parseDnsTxtRecord(listOf("v2;pk=$PUBLISHED_SERVER_KEY_B64;port=51820;future=yes"))
        assertEquals(51820, tuple.port)
    }

    @Test
    fun `a TXT record with no key yields an addressable but unusable tuple`() {
        val tuple = parseDnsTxtRecord(listOf("v1;port=51820"))
        assertNull(tuple.serverPublicKeyBase64)
        assertEquals(51820, tuple.port)
    }

    // -----------------------------------------------------------------------------------------
    // Key validation — the silent-failure guard
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a published key validates and junk does not`() {
        assertEquals(PUBLISHED_SERVER_KEY_B64, validServerKeyOrNull(PUBLISHED_SERVER_KEY_B64))
        assertNull(validServerKeyOrNull(null))
        assertNull(validServerKeyOrNull(""))
        assertNull(validServerKeyOrNull("   "))
        // ⚠️ `android.util.Base64` is lenient: it *skips* out-of-alphabet characters rather
        // than throwing, so this decodes to a short byte array instead of failing. The 32-byte
        // check is what catches it, and without it a paste with a stray character would become
        // a garbage peer key whose only symptom is a tunnel that never handshakes.
        assertNull(validServerKeyOrNull("not!valid!base64!"))
        assertNull(validServerKeyOrNull("c2hvcnQ="))
    }

    // -----------------------------------------------------------------------------------------
    // The DNS wire parser — rung 2
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a response carrying a TXT and an A record parses both`() {
        val response = dnsResponse(
            id = 0x1234,
            question = "boomio-local.tracemonkey.org",
            questionType = TYPE_TXT,
            answers = listOf(
                // The name is a compression pointer to offset 12 (the question's name) — which
                // is what every real resolver emits and what a naive parser mis-reads.
                Answer(TYPE_TXT, txtRdata("v1;pk=$PUBLISHED_SERVER_KEY_B64;port=51820")),
                Answer(TYPE_A, byteArrayOf(192.toByte(), 168.toByte(), 68.toByte(), 65.toByte())),
            ),
        )

        val message = OverlayDnsClient.parseResponse(response, 0x1234)
        assertNotNull(message)
        assertEquals(0, message.rcode)
        assertEquals(1, message.addresses.size)
        assertEquals("192.168.68.65", message.addresses.single().hostAddress)
        assertEquals(1, message.txt.size)

        val tuple = parseDnsTxtRecord(message.txt)
        assertEquals(PUBLISHED_SERVER_KEY_B64, tuple.serverPublicKeyBase64)
        assertEquals(51820, tuple.port)
    }

    @Test
    fun `a response for a different query id is rejected`() {
        // ⚠️ The id check is the entire integrity story of a UDP lookup — anything on the path
        // can inject a datagram, and a mismatched id is the one thing that says it is not ours.
        val response = dnsResponse(0x1234, "boomio-local.tracemonkey.org", TYPE_A, listOf(Answer(TYPE_A, IPV4)))
        assertNull(OverlayDnsClient.parseResponse(response, 0x9999))
    }

    @Test
    fun `a query is not accepted as its own answer`() {
        val query = OverlayDnsClient.buildQuery("boomio-local.tracemonkey.org", TYPE_A, 7)
        // The QR bit is clear, so this is a question, not a response.
        assertNull(OverlayDnsClient.parseResponse(query, 7))
    }

    @Test
    fun `a CNAME answer is reported so the caller can follow it`() {
        val response = dnsResponse(
            1,
            "boomio-local.tracemonkey.org",
            TYPE_TXT,
            listOf(Answer(TYPE_CNAME, nameRdata("boomio.duckdns.org"))),
        )
        val message = OverlayDnsClient.parseResponse(response, 1)
        assertEquals("boomio.duckdns.org", message?.cname)
        assertTrue(message!!.txt.isEmpty())
    }

    @Test
    fun `a self-referential compression pointer terminates instead of looping`() {
        // ⚠️ The jump cap is load-bearing, not defensive: this runs on the cold path, and a
        // walk that simply follows pointers hangs forever on a packet like this one.
        val response = dnsResponse(
            1,
            "boomio-local.tracemonkey.org",
            TYPE_A,
            listOf(Answer(TYPE_A, IPV4)),
        )
        // Point the *answer's* name at itself: trailing 12 bytes of the answer are
        // name(2) + type(2) + class(2) + ttl(4) + rdlength(2), so the name starts here.
        val answerNameOffset = response.size - (12 + IPV4.size)
        response[answerNameOffset] = 0xC0.toByte()
        response[answerNameOffset + 1] = answerNameOffset.toByte()

        // The assertion is that this returns at all — a walk that follows pointers without a
        // cap never comes back from this packet.
        assertNull(OverlayDnsClient.parseResponse(response, 1)?.addresses?.firstOrNull())
    }

    @Test
    fun `a truncated response is flagged rather than parsed as whole`() {
        val response = dnsResponse(1, "boomio-local.tracemonkey.org", TYPE_A, listOf(Answer(TYPE_A, IPV4)))
        response[2] = (response[2].toInt() or 0x02).toByte() // set TC
        assertTrue(OverlayDnsClient.parseResponse(response, 1)!!.truncated)
    }

    @Test
    fun `a short or empty datagram is rejected`() {
        assertNull(OverlayDnsClient.parseResponse(ByteArray(0), 1))
        assertNull(OverlayDnsClient.parseResponse(ByteArray(5), 1))
    }

    @Test
    fun `a TXT rdata split into several strings is concatenated`() {
        val rdata = txtRdata("v1;pk=", "ABC", ";port=51820")
        assertEquals("v1;pk=ABC;port=51820", OverlayDnsClient.parseTxtRdata(rdata, 0, rdata.size))
    }

    @Test
    fun `a query encodes the name as length-prefixed labels`() {
        val query = OverlayDnsClient.buildQuery("a.bc", TYPE_TXT, 0x0102)
        assertEquals(0x01, query[0].toInt())
        assertEquals(0x02, query[1].toInt())
        // 12-byte header, then 1+1 'a', 1+2 'bc', 1 root, 4 type/class.
        assertEquals(12 + 2 + 3 + 1 + 4, query.size)
        assertEquals("a", String(query.copyOfRange(13, 14)))
    }

    @Test
    fun `a label longer than 63 bytes produces no query at all`() {
        // A length prefix is one byte, so a 64-byte label would corrupt every field after it.
        assertTrue(OverlayDnsClient.buildQuery("x".repeat(64) + ".org", TYPE_A, 1).isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    /** A plain class, not a data class: a `data class` holding a `ByteArray` warns for no gain. */
    private class Answer(val type: Int, val rdata: ByteArray)

    /** One `TXT` rdata: a sequence of length-prefixed strings, as the wire carries it. */
    private fun txtRdata(vararg strings: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (string in strings) {
            val bytes = string.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /** A name in uncompressed wire form — used for `CNAME` rdata, which carries a real name. */
    private fun nameRdata(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    /**
     * A response whose answer names are compression pointers back to the question.
     *
     * Written out longhand rather than through a library because the point of the test is the
     * *bytes*: a parser tested against a builder it shares has tested nothing.
     */
    private fun dnsResponse(
        id: Int,
        question: String,
        questionType: Int,
        answers: List<Answer>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((id ushr 8) and 0xFF); out.write(id and 0xFF)
        out.write(0x81); out.write(0x80) // response, recursion desired + available, no error
        out.write(0x00); out.write(0x01) // QDCOUNT
        out.write(0x00); out.write(answers.size) // ANCOUNT
        out.write(0x00); out.write(0x00) // NSCOUNT
        out.write(0x00); out.write(0x00) // ARCOUNT

        // The question starts at offset 12, which is what the answer pointers below target.
        for (label in question.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write((questionType ushr 8) and 0xFF); out.write(questionType and 0xFF)
        out.write(0x00); out.write(0x01) // class IN

        for (answer in answers) {
            out.write(0xC0); out.write(0x0C) // pointer to offset 12
            out.write((answer.type ushr 8) and 0xFF); out.write(answer.type and 0xFF)
            out.write(0x00); out.write(0x01) // class IN
            out.write(0x00); out.write(0x00); out.write(0x00); out.write(0x3C) // ttl 60
            out.write((answer.rdata.size ushr 8) and 0xFF); out.write(answer.rdata.size and 0xFF)
            out.write(answer.rdata)
        }
        return out.toByteArray()
    }

    private val IPV4 = byteArrayOf(192.toByte(), 168.toByte(), 68.toByte(), 65.toByte())

    /** The key published by both channels for this deployment — see `OverlayWgTunnelTest`. */
    private companion object {
        const val PUBLISHED_SERVER_KEY_B64 = "kqZZdZcb8cGF9AhUTJw7RWuM/98WZs9NJOGlBKWtiU0="
        const val TYPE_A = 1
        const val TYPE_CNAME = 5
        const val TYPE_TXT = 16
    }
}
