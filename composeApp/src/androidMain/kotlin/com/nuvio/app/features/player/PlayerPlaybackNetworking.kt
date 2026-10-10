package com.nuvio.app.features.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.core.mtls.urlIsOurs
import com.nuvio.app.core.mtls.withClientCertificate
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.overlay.withOverlayProxy
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    internal const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val DEFAULT_STREAM_HEADERS = mapOf(
        "User-Agent" to DEFAULT_USER_AGENT,
    )

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    /**
     * The trust-all client, for third-party sources whose certificates are not ours to
     * demand (IPTV panels, addon CDNs).
     *
     * ⚠️ `usePins = false` is a **security control, not a preference.** This client
     * accepts any certificate and any hostname, so on this path TLS authenticates
     * nothing. Following a pin here would let an unauthenticated mDNS advert redirect
     * the media stream — and the `Authorization` header riding on it — to whatever LAN
     * host the advert named. Our own hosts use [ownServerPlaybackHttpClient] instead.
     */
    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns(usePins = false))
            .withOverlayProxy()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(SentryNetworkBreadcrumbInterceptor())
            .build()
    }

    /**
     * The validating twin, selected when the source host is one **our own server answers for**.
     *
     * Identical timeouts, redirects and interceptor to [playbackHttpClient]; the single
     * difference is that TLS is left at the platform default, so following the pin is
     * safe. This costs nothing on the path it serves: the user's own hosts present real
     * certificates (verified live in A1), and third-party hosts never reach it.
     *
     * ⚠️ **This is the one client in this file that carries the client certificate, and the
     * pairing is not a coincidence.** It is the only playback client that follows a pin, and a pin
     * is only followed under real certificate validation — `withClientCertificate()` installs the
     * *platform* trust manager alongside the certificate, which is exactly the property a pinned
     * dial needs. [playbackHttpClient] above must never take it: there it would replace the
     * trust-all factory and lose the SSLException fallback that exists for third-party sources.
     * Before 2026-10-09 playback carried no certificate at all, which was half of why "every rung
     * presents the cert" was false for the media plane.
     *
     * ⚠️ **The selector is host *identity*, not pin presence — corrected 2026-10-09.** It used to
     * be `OverlayPinRegistry.isPinnedHost(sourceUrl)`, which asked two different questions at once:
     * *may this caller follow the pin (a DNS override)?* and *must this caller present our
     * certificate?* Only the first needs a pin. Tying the certificate to one meant that the moment
     * the pin went away — a browse that found nothing, an arm the policy suppressed — the app
     * reclassified **its own media host** as a third-party host and silently stopped offering the
     * certificate. Measured: four `403 client_certificate_required` on `/bss-dav/proxy/…` while
     * keystore2 logged no `create_operation` at all, i.e. the key was never asked to sign. The
     * predicate is now the one the handshake watch already uses, and it covers the media plane's
     * hosts (`bss-dav`, `bss-tor`, `nzbdav`) by registrable domain rather than by enumeration.
     * A URL with no parseable host, or an IP literal, is **not** ours and stays on [playbackHttpClient].
     */
    private val ownServerPlaybackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .withOverlayProxy()
            .withClientCertificate()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(SentryNetworkBreadcrumbInterceptor())
            .build()
    }

    private val loopbackPlaybackHttpClient: OkHttpClient by lazy {
        playbackHttpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val requestChain = if (isLoopbackHost(request.url.host)) {
                    chain.withReadTimeout(65, TimeUnit.SECONDS)
                } else {
                    chain
                }
                requestChain.proceed(request)
            }
            .build()
    }

    /**
     * The client for [sourceUrl] — the player's client-selection rule, in one place.
     *
     * Extracted so that anything which talks to **the same host the player is about to stream
     * from** cannot answer with a third spelling of the rule. The MIME probe is the caller that
     * matters: it asks a media URL the player will fetch a moment later, so a probe that dialled on
     * some other client would be diagnosing a different connection than the one that failed — and
     * with the media surface refused on any plane but the overlay, an unproxied dial does not
     * merely differ, it fails.
     *
     * The rule itself is [urlIsOurs]; see [ownServerPlaybackHttpClient] for why it is identity and
     * never pin presence, and why our own hosts get the certificate-bearing client while everyone
     * else gets the trust-all one.
     */
    internal fun clientForSource(sourceUrl: String?): OkHttpClient =
        if (urlIsOurs(sourceUrl)) ownServerPlaybackHttpClient else playbackHttpClient

    fun createHttpDataSourceFactory(
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        sourceUrl: String? = null,
    ): DataSource.Factory {
        val requestHeaders = sanitizeHeaders(defaultHeaders)
        // A loopback source is 127.0.0.1 and can never be one of our hosts, so the
        // long-read-timeout client needs no validating twin.
        //
        // ⚠️ **Identity, not pin presence.** See [ownServerPlaybackHttpClient] for why this is not
        // `OverlayPinRegistry.isPinnedHost`: the certificate must not depend on whether a pin
        // happens to exist this second. [urlIsOurs] answers false for a blank or unparseable URL and
        // for a bare address, so both fall to [playbackHttpClient], the trust-all client — the same
        // place they landed before.
        val baseClient = if (useLongReadTimeout) loopbackPlaybackHttpClient else clientForSource(sourceUrl)
        val client = requestHeaders.headerValue("Authorization")?.let { authorization ->
            baseClient.newBuilder()
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    if (request.header("Authorization") == null) {
                        chain.proceed(
                            request.newBuilder()
                                .header("Authorization", authorization)
                                .build()
                        )
                    } else {
                        chain.proceed(request)
                    }
                }
                .build()
        } ?: baseClient

        return OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(requestHeaders)
            if (requestHeaders.headerValue("User-Agent") == null) {
                setUserAgent(DEFAULT_USER_AGENT)
            }
        }
    }

    // `createDataSourceFactory(context, …)` used to sit here: a `DefaultDataSource.Factory` wrapper
    // that forwarded only `defaultHeaders` and `useLongReadTimeout` to
    // [createHttpDataSourceFactory], **dropping the `sourceUrl`** its sibling takes. Both real call
    // sites go through the per-flavour `PlatformPlaybackDataSourceFactory`, which passes
    // `sourceUrl = sourceUrl`; this one had no callers at all, so it was a trap rather than an API —
    // a future caller reaching for it would have silently received the trust-all, certificate-less
    // client for **our own media hosts**, which is the exact defect this file exists to prevent.
    // Deleted rather than given a `sourceUrl` parameter, because an unused function has no caller to
    // keep honest and the compiler now enforces that nobody re-adds one without thinking about it.

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null,
    ): HttpURLConnection {
        val mergedHeaders = withDefaultUserAgent(headers)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                sslSocketFactory = sslContext.socketFactory
                hostnameVerifier = playbackHostnameVerifier
            }
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", mergedHeaders.headerValue("User-Agent") ?: DEFAULT_USER_AGENT)
            mergedHeaders.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isBlank() || value.isBlank() || key.equals("Range", ignoreCase = true)) {
                null
            } else {
                key to value
            }
        }.toMap()

    private fun isLoopbackHost(host: String): Boolean = when (host.lowercase()) {
        "127.0.0.1", "localhost", "::1" -> true
        else -> false
    }

    private fun withDefaultUserAgent(headers: Map<String, String>): Map<String, String> {
        val sanitized = sanitizeHeaders(headers)
        if (sanitized.headerValue("User-Agent") != null) return sanitized
        return DEFAULT_STREAM_HEADERS + sanitized
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}
