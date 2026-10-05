package com.nuvio.app.core.overlay

import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.isPublicServerHost
import com.nuvio.app.features.boomio.BoomioConfig

/** The registry host of [url], lowercased, or null when it is blank or unparseable. */
internal fun hostOf(url: String): String? = runCatching { io.ktor.http.Url(url).host.lowercase() }
    .getOrNull()
    ?.takeIf { it.isNotBlank() }

/**
 * The subset of [urls] that may be pinned to a discovered LAN address.
 *
 * A host qualifies only when [isPublicServerHost] accepts it, which already rejects
 * `.local` and RFC1918 — so a discovered `.local` name can never itself become a pin
 * target, and the pin can never point the app at an address it already uses.
 */
internal fun derivePinnableHosts(urls: Iterable<String>): Set<String> = urls.asSequence()
    .filter { it.isNotBlank() }
    .filter { isPublicServerHost(it) }
    .mapNotNull { hostOf(it) }
    .toSet()

/**
 * The configured URLs that could name the user's own server.
 *
 * ⚠️ **[ServerConfiguration.fallbackBackendUrl] is deliberately absent.** It is the
 * Supabase *cloud* fallback, not a boomio Caddy host; pinning it to a LAN address
 * would break the very failover it exists for. A unit test asserts its absence, so
 * adding it here is caught rather than shipped.
 *
 * [BoomioConfig.boomioBaseUrl] and [BoomioConfig.bsmBaseUrl] are blank in this
 * repository — a host application assigns them — so they are read, never assumed.
 */
internal fun localServerHostCandidates(): List<String> = listOf(
    ServerConfigurationRepository.active.value.backendUrl,
    BoomioConfig.companionBaseUrl,
    BoomioConfig.iptvBaseUrl,
    BoomioConfig.boomioBaseUrl,
    BoomioConfig.bsmBaseUrl,
)

/** The hosts the overlay may repoint at a LAN address. Derived at runtime, never hardcoded. */
internal fun localServerHosts(): Set<String> = derivePinnableHosts(localServerHostCandidates())
