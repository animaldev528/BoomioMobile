package com.nuvio.app.core.network

import android.app.Application
import com.nuvio.app.core.overlay.OverlayPinRegistry
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okhttp3.Dns
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// ⚠️ Robolectric is required here, not stylistic. `OverlayPinRegistry.pin()` calls
// `android.util.Log`, which a plain JVM host test leaves **unmocked** — every test that
// pins dies with `RuntimeException: Method d in android.util.Log not mocked` before it
// reaches an assertion, so the pin behaviour would go untested while looking present.
// The SDK is pinned because the module compiles against SDK 37, which Robolectric does
// not map. Same treatment as `LocalServerHostsTest` in the overlay package.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IPv4FirstDnsTest {

    private val publicHost = "bsc.tracemonkey.org"
    private val pinned = InetAddress.getByName("192.168.68.65")
    private val publicV4 = InetAddress.getByName("203.0.113.10") as Inet4Address
    private val publicV6 = InetAddress.getByName("2001:db8::1") as Inet6Address

    @AfterTest
    fun tearDown() {
        OverlayPinRegistry.clear()
    }

    private fun dnsOf(vararg answers: InetAddress) = IPv4FirstDns(
        delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = answers.toList()
        },
    )

    private fun failingDns() = IPv4FirstDns(
        delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname)
        },
    )

    @Test
    fun `sorts ipv4 ahead of ipv6 when nothing is pinned`() {
        assertEquals(listOf(publicV4, publicV6), dnsOf(publicV6, publicV4).lookup(publicHost))
    }

    @Test
    fun `puts the pin first and keeps the delegate results behind it`() {
        OverlayPinRegistry.pin(listOf(publicHost), pinned)

        val result = dnsOf(publicV6, publicV4).lookup(publicHost)

        assertEquals(pinned, result.first())
        // The fallback is the anti-wedge property: a stale pin costs one failed
        // connect, it does not remove the ability to reach the public edge.
        assertEquals(listOf(pinned, publicV4, publicV6), result)
    }

    @Test
    fun `never re-sorts the pin behind an ipv6 from the delegate`() {
        OverlayPinRegistry.pin(listOf(publicHost), pinned)

        assertEquals(pinned, dnsOf(publicV6).lookup(publicHost).first())
    }

    @Test
    fun `drops a duplicate when the delegate already returns the pinned address`() {
        OverlayPinRegistry.pin(listOf(publicHost), pinned)

        assertEquals(listOf(pinned, publicV4), dnsOf(pinned, publicV4).lookup(publicHost))
    }

    @Test
    fun `falls back to the pin when system dns fails`() {
        // The case the feature exists for: a LAN where system DNS does not know the name.
        OverlayPinRegistry.pin(listOf(publicHost), pinned)

        assertEquals(listOf(pinned), failingDns().lookup(publicHost))
    }

    @Test
    fun `propagates system dns failure when there is no pin`() {
        assertFailsWith<UnknownHostException> { failingDns().lookup(publicHost) }
    }

    @Test
    fun `usePins false ignores the registry entirely`() {
        // The escape hatch for the trust-all playback client, where TLS would not
        // catch a hostile pin.
        OverlayPinRegistry.pin(listOf(publicHost), pinned)
        val dns = IPv4FirstDns(
            delegate = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(publicV4)
            },
            usePins = false,
        )

        assertEquals(listOf(publicV4), dns.lookup(publicHost))
    }

    @Test
    fun `a pin covers every host on the server's domain, including ones learned at runtime`() {
        // The media plane. `bss-dav` and `bss-tor` arrive inside the stream URLs bsf
        // returns, so they are in no configuration and in no addon manifest — a pin set
        // built by enumerating hosts missed them, and the player sat on
        // `failed to connect to bss-dav.tracemonkey.org/153.68.210.49 after 15000ms`
        // while the catalogue worked. Matching the domain is what makes the pin complete.
        OverlayPinRegistry.pin(listOf(publicHost), pinned)
        val dns = dnsOf(publicV4)

        val stream = dns.lookup("bss-dav.tracemonkey.org")

        assertEquals(pinned, stream.first())
        assertTrue(stream.contains(publicV4))
    }

    @Test
    fun `a pin never covers a third-party host`() {
        // Posters are `image.tmdb.org`, two addons are third-party, and Supabase lives
        // in the cloud. Repointing any of them at a LAN address would simply be wrong.
        OverlayPinRegistry.pin(listOf(publicHost), pinned)
        val dns = dnsOf(publicV4)

        for (thirdParty in listOf(
            "image.tmdb.org",
            "catalog.nuvio.tv",
            "opensubtitles-v3.strem.io",
        )) {
            val result = dns.lookup(thirdParty)
            assertEquals(listOf(publicV4), result, "$thirdParty must keep resolving publicly")
            assertTrue(result.none { it == pinned }, "$thirdParty must not be pinned")
        }
    }

    @Test
    fun `a pin does not cover a domain that merely ends with the server's domain`() {
        // `eviltracemonkey.org` ends with the string `tracemonkey.org` but is a different
        // domain. The leading `.` in the suffix match is what keeps the two apart.
        OverlayPinRegistry.pin(listOf(publicHost), pinned)

        val result = dnsOf(publicV4).lookup("eviltracemonkey.org")

        assertEquals(listOf(publicV4), result)
        assertTrue(result.none { it == pinned })
    }
}
