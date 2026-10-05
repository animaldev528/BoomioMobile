package com.nuvio.app.core.overlay

import com.nuvio.app.core.network.ServerConfigurationRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalServerHostsTest {

    @Test
    fun `keeps public https hosts`() {
        assertEquals(
            setOf("bsc.tracemonkey.org"),
            derivePinnableHosts(listOf("https://bsc.tracemonkey.org")),
        )
    }

    @Test
    fun `keeps a wss companion url, which is not an http scheme`() {
        assertEquals(
            setOf("bsc.tracemonkey.org"),
            derivePinnableHosts(listOf("wss://bsc.tracemonkey.org")),
        )
    }

    @Test
    fun `drops local and rfc1918 addresses`() {
        // isPublicServerHost already rejects these, so a discovered .local name can
        // never itself become a pin target.
        assertTrue(
            derivePinnableHosts(
                listOf(
                    "https://beamstream.local",
                    "https://192.168.68.65",
                    "https://10.77.0.1",
                    "https://172.16.0.1",
                    "https://127.0.0.1",
                    "https://localhost",
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `keeps 172_32, which is public`() {
        // The RFC1918 block is 172.16/12; 172.32 is outside it and must survive.
        assertEquals(
            setOf("172.32.0.1"),
            derivePinnableHosts(listOf("https://172.32.0.1")),
        )
    }

    @Test
    fun `ignores blanks and unparseable values`() {
        assertTrue(derivePinnableHosts(listOf("", "   ", "not a url")).isEmpty())
    }

    @Test
    fun `strips the port and lowercases`() {
        assertEquals(
            setOf("bsc.tracemonkey.org"),
            derivePinnableHosts(listOf("https://BSC.Tracemonkey.org:8443/path")),
        )
    }

    @Test
    fun `dedupes the same host reached by two schemes`() {
        assertEquals(
            setOf("bsc.tracemonkey.org"),
            derivePinnableHosts(
                listOf("https://bsc.tracemonkey.org", "wss://bsc.tracemonkey.org"),
            ),
        )
    }

    @Test
    fun `hostOf is null for blank input`() {
        assertNull(hostOf(""))
        assertNull(hostOf("nonsense"))
    }

    @Test
    fun `never pins the configured fallback backend`() {
        // The fallback is the Supabase *cloud* host: it exists so the app has somewhere
        // to go when the primary is gone, so repointing it at a LAN address would
        // destroy the failover. This asserts it never enters the candidate list.
        val fallbackHost = hostOf(ServerConfigurationRepository.active.value.fallbackBackendUrl.orEmpty())
        if (fallbackHost == null) return

        assertFalse(
            localServerHostCandidates().any { hostOf(it) == fallbackHost },
            "the Supabase fallback host must not be pinnable",
        )
    }
}
