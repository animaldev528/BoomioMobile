package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.boomio.BoomioConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerCapabilities(
    val emailPasswordAuth: Boolean,
    val tvLogin: Boolean,
)

data class ServerConfiguration(
    val backendUrl: String,
    val publishableKey: String,
    val capabilities: ServerCapabilities,
    val isCustom: Boolean,
    val discoveryUrl: String? = null,
    val fallbackBackendUrl: String? = null,
) {
    val isSecure: Boolean
        get() = backendUrl.startsWith("https://", ignoreCase = true)

    val isPublicHost: Boolean
        get() = isPublicServerHost(backendUrl)
}

object ServerConfigurationRepository {
    private val _active = MutableStateFlow(loadActiveConfiguration())
    val active: StateFlow<ServerConfiguration> = _active.asStateFlow()

    fun saveCustom(configuration: ServerConfiguration): Boolean {
        if (!AppFeaturePolicy.customServerConnectionsEnabled) return false
        if (!ServerConfigurationStorage.saveCustom(configuration)) return false
        _active.value = configuration
        return true
    }

    fun useOfficial(): Boolean {
        if (!ServerConfigurationStorage.useOfficial()) return false
        _active.value = officialConfiguration()
        return true
    }

    private fun loadActiveConfiguration(): ServerConfiguration {
        if (!AppFeaturePolicy.customServerConnectionsEnabled) return officialConfiguration()
        return ServerConfigurationStorage.loadCustom() ?: officialConfiguration()
    }

    /**
     * Re-derives the official configuration after discovery published a service name.
     *
     * ⚠️ **[ServerConfiguration] is a snapshot, and `_active` holds the one taken at first
     * access.** The service name is not known then — [BoomioConfig.serviceOrigin] starts at
     * the build's floor and only moves when a publication names the real one — so without
     * this the app would keep the floor forever and "discovered" would mean nothing to the
     * auth plane. The update is skipped when nothing moved, so the ordinary cold start (where
     * the record names the same host the floor already did) costs no client rebuild at all.
     *
     * ⚠️ **A user-chosen server always wins.** A custom configuration is the user saying which
     * server to talk to; a publication from the *other* one is not grounds to overrule them.
     *
     * ⚠️ **The Supabase client caches itself on first use and must be dropped**, or the new
     * origin would only take effect after a process restart. Same reason
     * [com.nuvio.app.features.auth.ServerConnectionController] resets it when the server is
     * switched by hand.
     */
    suspend fun onServiceOriginChanged() {
        if (_active.value.isCustom) return
        val refreshed = officialConfiguration()
        if (refreshed.backendUrl == _active.value.backendUrl) return
        _active.value = refreshed
        SupabaseProvider.reset()
    }
}

/**
 * The auth backend's origin.
 *
 * ⚠️ **A baked build value wins; a build that names none gets the *runtime* origin, never
 * `""`.** [SupabaseConfig.URL] is a compile-time constant, so a build made without
 * `NUVIO_SUPABASE_URL` used to hand [officialConfiguration] the empty string — and an empty
 * base is not inert. supabase-kt builds its client on Ktor, whose `URLBuilder` defaults an
 * absent host to `localhost`, so every sign-in went to `http://localhost/auth/v1/authorize`
 * and no request ever left the device. That is not hypothetical: it is the fault this
 * function exists to remove, and the tell is a sign-in URL with no port on it.
 *
 * ⚠️ **[BoomioConfig.serviceOrigin] is a NAME, never an endpoint.** It floors on the
 * deployment's one name and `OverlayEndpointDiscovery` replaces it from the `svc=` field that
 * both the mDNS advert and the DuckDNS `TXT` record publish. The dial target is decided
 * separately — the record's `lan=`/`wan=` literals are pinned for that name and tried before
 * any resolver is consulted, while SNI and `Host` stay on the name — which is exactly what
 * makes the same URL work on the home LAN and away from it. Publishing the `A` record's
 * private address as the endpoint would work on exactly one of those.
 */
internal fun resolveBackendUrl(bakedUrl: String, serviceOrigin: String): String =
    bakedUrl.trim().trimEnd('/').ifBlank { serviceOrigin.trim().trimEnd('/') }

internal fun officialConfiguration() = ServerConfiguration(
    backendUrl = resolveBackendUrl(SupabaseConfig.URL, BoomioConfig.serviceOrigin),
    publishableKey = SupabaseConfig.ANON_KEY.trim(),
    capabilities = ServerCapabilities(
        emailPasswordAuth = true,
        tvLogin = true,
    ),
    isCustom = false,
    fallbackBackendUrl = SupabaseConfig.FALLBACK_URL.trim().trimEnd('/').takeIf { it.isNotBlank() },
)

internal fun isPublicServerHost(url: String): Boolean {
    val host = runCatching { io.ktor.http.Url(url).host.lowercase() }.getOrNull() ?: return true
    if (host == "localhost" || host.endsWith(".local") || host == "::1") return false
    if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.")) return false
    val parts = host.split('.')
    if (parts.size == 4) {
        val first = parts[0].toIntOrNull()
        val second = parts[1].toIntOrNull()
        if (first == 172 && second != null && second in 16..31) return false
        if (first == 169 && second == 254) return false
    }
    return true
}
