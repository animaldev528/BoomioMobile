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

    @AfterTest
    fun tearDown() {
        OverlayPinRegistry.clear()
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
        OverlayPinRegistry.pin(listOf("bsc.tracemonkey.org", "bsf.tracemonkey.org"), pinned)

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
        OverlayPinRegistry.pin(listOf("bsc.tracemonkey.org"), pinned)

        assertFalse(OverlayPinRegistry.isPinnedHost("https://image.tmdb.org/t/p/w500/a.jpg"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://catalog.nuvio.tv/manifest.json"))
        assertFalse(OverlayPinRegistry.isPinnedHost("https://opensubtitles-v3.strem.io/m.json"))
        // Ends with the string `tracemonkey.org` but is a different domain.
        assertFalse(OverlayPinRegistry.isPinnedHost("https://eviltracemonkey.org/x"))
    }

    @Test
    fun `a blank or unparseable url is not pinned`() {
        OverlayPinRegistry.pin(listOf("bsc.tracemonkey.org"), pinned)

        assertFalse(OverlayPinRegistry.isPinnedHost(""))
        assertFalse(OverlayPinRegistry.isPinnedHost("nonsense"))
        assertNull(OverlayPinRegistry.lookup(null))
    }

    @Test
    fun `clear removes the domain match as well as the exact host`() {
        OverlayPinRegistry.pin(listOf("bsc.tracemonkey.org"), pinned)
        OverlayPinRegistry.clear()

        assertNull(OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
        assertNull(OverlayPinRegistry.lookup("bss-dav.tracemonkey.org"))
    }

    @Test
    fun `an empty host set never clears a good pin`() {
        // `OverlayLocalDiscovery.refresh` falls back to the browsed set when the live
        // derivation comes back empty; a pin must not evaporate just because a caller
        // had nothing to say.
        OverlayPinRegistry.pin(listOf("bsc.tracemonkey.org"), pinned)
        OverlayPinRegistry.pin(emptyList(), pinned)

        assertEquals(pinned, OverlayPinRegistry.lookup("bsc.tracemonkey.org"))
    }
}
