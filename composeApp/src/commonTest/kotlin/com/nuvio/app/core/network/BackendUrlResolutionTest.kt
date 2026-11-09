package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The auth backend must never be the empty string.
 *
 * ⚠️ That is not a style rule. supabase-kt builds its client on Ktor, and a blank base url is
 * parsed as `http://localhost` rather than rejected — so a build made without
 * `NUVIO_SUPABASE_URL` sent every sign-in to a server on the phone itself, and the request
 * never left the device. These tests pin the fallback that replaced it.
 */
class BackendUrlResolutionTest {

    @Test
    fun aBuildThatNamesNoBackendFallsBackToTheServiceOrigin() {
        assertEquals(
            "https://boomio.duckdns.org",
            resolveBackendUrl(bakedUrl = "", serviceOrigin = "https://boomio.duckdns.org"),
        )
    }

    @Test
    fun theConfiguredServerBeatsABakedOne() {
        // The client is configured by the one FQDN a person enters. A build carrying another
        // deployment's name must not keep signing in there after the person pointed it elsewhere —
        // that is the "entered my server, talked to theirs" defect.
        assertEquals(
            "https://boomio.duckdns.org",
            resolveBackendUrl(bakedUrl = "https://other.example", serviceOrigin = "https://boomio.duckdns.org"),
        )
    }

    @Test
    fun aBuildWithNoRuntimeOriginFallsBackToItsBakedValue() {
        // Before a server is named, a build that names one still has somewhere to point. This is a
        // floor, not an identity: the first configured FQDN replaces it.
        assertEquals(
            "https://other.example",
            resolveBackendUrl(bakedUrl = "https://other.example", serviceOrigin = ""),
        )
    }

    @Test
    fun whitespaceIsNotABakedValue() {
        // `runtimeConfigValue` trims, but a hand-edited local.properties need not have.
        assertEquals(
            "https://boomio.duckdns.org",
            resolveBackendUrl(bakedUrl = "   ", serviceOrigin = "https://boomio.duckdns.org"),
        )
    }

    @Test
    fun trailingSlashesAreTrimmedOnWhicheverArmWins() {
        assertEquals(
            "https://boomio.duckdns.org",
            resolveBackendUrl(bakedUrl = "https://other.example/", serviceOrigin = "https://boomio.duckdns.org"),
        )
        assertEquals(
            "https://boomio.duckdns.org",
            resolveBackendUrl(bakedUrl = "", serviceOrigin = "https://boomio.duckdns.org/"),
        )
    }

    @Test
    fun theResolvedBackendIsAlwaysAnOriginTheClientCanDial() {
        // The invariant the whole defect was a violation of: an empty or host-less value reads
        // downstream as `localhost`. Whenever a server is named the result must be absolute...
        val backendUrl = resolveBackendUrl(bakedUrl = "", serviceOrigin = "https://server.example")
        assertTrue(backendUrl.contains("://"), "backend url must be absolute, was '$backendUrl'")
        assertTrue(backendUrl.isNotBlank())

        // ...and when nothing is named at all it stays blank rather than inventing a host. That is
        // the unconfigured state, and the server-entry screen is the answer to it.
        assertEquals("", resolveBackendUrl(bakedUrl = "", serviceOrigin = ""))
    }
}
