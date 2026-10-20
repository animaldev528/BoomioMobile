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
    fun aBakedBackendStillWins() {
        // A build aimed at a different deployment must not be moved by this device's floor.
        assertEquals(
            "https://other.example",
            resolveBackendUrl(bakedUrl = "https://other.example", serviceOrigin = "https://boomio.duckdns.org"),
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
            "https://other.example",
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
        // downstream as `localhost`, so it must be impossible for the official config to hold one.
        val backendUrl = officialConfiguration().backendUrl
        assertTrue(backendUrl.contains("://"), "official backend url must be absolute, was '$backendUrl'")
        assertTrue(backendUrl.isNotBlank())
    }
}
