package com.nuvio.app.core.overlay

import android.app.Application
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The registry's contract is that **a pin covers the server's domain**, not a list of
 * hosts. That is what [isPinnedHost] decides, and it is the gate that selects the
 * validating playback client — so getting it wrong is what made every bsf link buffer.
 *
 * ⚠️ Robolectric is required: `pin()` calls `android.util.Log`, which is unmocked in a
 * plain JVM host test. The SDK is pinned because the module compiles against SDK 37,
 * which Robolectric does not map. Same treatment as `LocalServerHostsTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OverlayPinRegistryTest {

    private val pinned = InetAddress.getByName("192.168.68.65")
    private val overlay = InetAddress.getByName("10.77.0.1")

    @AfterTest
    fun tearDown() {
        OverlayPinRegistry.clearAll()
    }

    @Test
    fun `nothing is pinned before discovery runs`() {
        assertNull(OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://bsc.tracemonkey.org"))
    }

    @Test
    fun `a stream url on the server's domain selects the pinned client`() {
        // `bss-dav` and `bss-tor` are in no configuration and in no addon manifest --
        // they arrive inside bsf's stream URLs. When this gate said false for them, the
        // player used the trust-all client, resolved to the WAN address and sat on
        // `failed to connect to bss-dav.tracemonkey.org/153.68.210.49 after 15000ms`.
        OverlayPinRegistry.pin(
            LocalServerSource.LAN,
            listOf("bsc.tracemonkey.org", "bsf.tracemonkey.org"),
            pinned,
        )

        assertTrue(
            OverlayPinRegistry.isPinnedHost(
                "https://bss-dav.tracemonkey.org/library/movie/tt0137523/stream.mkv",
            ),
        )
        assertTrue(OverlayPinRegistry.isPinnedHost("https://bss-tor.tracemonkey.org/resolve/x"))
        assertTrue(OverlayPinRegistry.isPinnedHost("https://nzbdav.tracemonkey.org/content/x.mkv"))
    }

    @Test
    fun `a third-party url never selects the pinned client`() {
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        assertFalse(OverlayPinRegistry.isPinnedHost("https://image.tmdb.org/t/p/w500/a.jpg"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://catalog.nuvio.tv/manifest.json"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://opensubtitles-v3.strem.io/m.json"))
        // Ends with the string `tracemonkey.org` but is a different domain.
        assertFalse(OverlayPinRegistry.isPinnedHost("https://eviltracemonkey.org/x"))
    }

    @Test
    fun `a blank or unparseable url is not pinned`() {
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        assertFalse(OverlayPinRegistry.isPinnedHost(""))
        assertFalse(OverlayPinRegistry.isPinnedHost("nonsense"))
        assertNull(OverlayPinRegistry.lookup(null))
    }

    @Test
    fun `clear removes the domain match as well as the exact host`() {
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)
        OverlayPinRegistry.clear(LocalServerSource.LAN)

        assertNull(OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        assertNull(OverlayPinRegistry.lookup("bss-dav.tracemonkey.org"))
    }

    @Test
    fun `an empty host set never clears a good pin`() {
        // `OverlayLocalDiscovery.refresh` falls back to the browsed set when the live
        // derivation comes back empty; a pin must not evaporate just because a caller
        // had nothing to say.
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)
        OverlayPinRegistry.pin(LocalServerSource.LAN, emptyList(), pinned)

        assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
    }

    @Test
    fun `the two sources hold their own pins and are ranked`() {
        // The tiers are driven by independent triggers that know nothing about each
        // other. With one slot, a tunnel probe landing a second after a browse would
        // replace a working LAN pin with the overlay address — silently, and for the
        // rest of the session.
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf("bsc.tracemonkey.org"), overlay)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
    }

    @Test
    fun `clearing one source does not disturb the other`() {
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf("bsc.tracemonkey.org"), overlay)
        OverlayPinRegistry.pin(LocalServerSource.LAN, listOf("bsc.tracemonkey.org"), pinned)

        OverlayPinRegistry.clear(LocalServerSource.LAN)

        assertEquals(overlay, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        assertEquals(overlay, OverlayPinRegistry.lookup("bss-dav.tracemonkey.org"))
    }

    @Test
    fun `a tunnel pin alone covers the domain exactly as a LAN pin does`() {
        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, listOf("bsc.tracemonkey.org"), overlay)

        assertTrue(OverlayPinRegistry.isPinnedHost("https://bsf.tracemonkey.org/find/x"))
        assertTrue(OverlayPinRegistry.isPinnedHost("https://bss-tor.tracemonkey.org/x"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://image.tmdb.org/x.jpg"))
    }
}
