package com.nuvio.app.core.network

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
    fun `a pin for one host does not affect another`() {
        OverlayPinRegistry.pin(listOf(publicHost), pinned)
        val dns = dnsOf(publicV4)

        val other = dns.lookup("other.tracemonkey.org")

        assertEquals(listOf(publicV4), other)
        assertTrue(other.none { it == pinned })
    }
}
