package com.nuvio.app.features.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.core.mtls.urlIsOurs
import com.nuvio.app.core.mtls.withClientCertificate
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.overlay.withOverlayProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI

/**
 * Downloads subtitle files from remote URLs to local cache and provides
 * content:// URIs via FileProvider so external players can read them.
 */
object SubtitleFileCache {
    private const val TAG = "SubtitleFileCache"
    private const val SUBTITLE_CACHE_DIR = "subtitles"

    private var appContext: Context? = null
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .withOverlayProxy()
            .addInterceptor(SentryNetworkBreadcrumbInterceptor())
            .build()
    }

    /**
     * The certificate-bearing twin, for subtitle URLs that are **ours**.
     *
     * Subtitles for our own streams are served from the same hosts the media surface is: on the
     * enforced plane the media surface is certificate-exempt (libmpv can never present one) but
     * **not** tunnel-exempt, and a subtitle URL on one of our hosts is reached through the same
     * overlay relay. [okHttpClient] above already rides the relay, so a subtitle on a *refused*
     * control path is the only thing missing a certificate.
     *
     * ⚠️ Selected by [urlIsOurs], never by a pin: the pin evaporates on the LAN and a certificate
     * tied to it disappears exactly at home. See `PlayerPlaybackNetworking.clientForSource` for the
     * defect that spelling caused, and `MtlsHandshakeWatch.urlIsOurs` for why the predicate is
     * shared rather than re-derived.
     *
     * Third-party subtitle hosts keep [okHttpClient] — they were never ours to present a
     * certificate to, and TLS there is validated by the platform default either way.
     */
    private val ownServerOkHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().withClientCertificate().build()
    }

    private fun clientFor(url: String): OkHttpClient =
        if (urlIsOurs(url)) ownServerOkHttpClient else okHttpClient

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val cacheDir: File?
        get() = appContext?.let { File(it.cacheDir, SUBTITLE_CACHE_DIR).also { dir -> dir.mkdirs() } }

    /**
     * Downloads subtitle files and returns updated [SubtitleInput] list with
     * content:// URIs instead of HTTP URLs.
     *
     * Subtitles that fail to download are silently skipped (not included in result).
     * Returns null if no subtitles could be downloaded.
     *
     * [headers] are the same playback headers the stream is being launched with (see
     * `ExternalPlayerPlaybackRequest.sourceHeaders`). They are sent **only** to URLs that are ours:
     * a subtitle on our server needs the same device stream token the media surface requires, while
     * forwarding the stream's `Authorization` to a public subtitle host would hand it a credential
     * it has no business seeing. Sanitized here rather than at the call site so every caller is
     * covered, and so this file owns the "what may leave the device" decision.
     */
    suspend fun cacheSubtitles(
        subtitles: List<SubtitleInput>,
        headers: Map<String, String> = emptyMap(),
    ): List<SubtitleInput>? {
        if (subtitles.isEmpty()) return null
        val context = appContext ?: return null
        val sanitizedHeaders = sanitizePlaybackHeaders(headers)

        // Clean old cached subtitles before downloading new ones
        clearCache()

        val cached = subtitles.mapNotNull { input ->
            try {
                val localUri = downloadToCache(context, input, sanitizedHeaders)
                if (localUri != null) {
                    input.copy(url = localUri.toString())
                } else {
                    Log.w(TAG, "Failed to download subtitle: ${input.name}")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error caching subtitle: ${input.name}", e)
                null
            }
        }

        return cached.ifEmpty { null }
    }

    /**
     * Downloads a single subtitle file and returns a content:// URI.
     */
    private suspend fun downloadToCache(
        context: Context,
        input: SubtitleInput,
        headers: Map<String, String>,
    ): Uri? =
        withContext(Dispatchers.IO) {
            val dir = cacheDir ?: return@withContext null
            val extension = guessExtension(input.url)
            val filename = sanitizeFilename("${input.lang}_${input.name}.$extension")
            val file = File(dir, filename)

            try {
                if (input.url.startsWith("file:")) {
                    File(URI(input.url)).copyTo(file, overwrite = true)
                    return@withContext FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
                // Headers are ours-only by construction: they are applied here, on the branch that
                // sends them, and the third-party path below builds the same request without them.
                val request = Request.Builder().url(input.url).apply {
                    if (urlIsOurs(input.url)) {
                        headers.forEach { (key, value) -> header(key, value) }
                    }
                }.build()
                clientFor(input.url).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP ${response.code} downloading subtitle: ${input.url}")
                        return@withContext null
                    }

                    val body = response.body ?: return@withContext null
                    file.outputStream().use { output ->
                        body.byteStream().copyTo(output)
                    }
                }

                // Return content:// URI via FileProvider
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download subtitle file: ${input.url}", e)
                file.delete()
                null
            }
        }

    /**
     * Removes all cached subtitle files.
     */
    fun clearCache() {
        try {
            cacheDir?.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear subtitle cache", e)
        }
    }

    private fun guessExtension(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').trimEnd('/')
        return when {
            path.endsWith(".vtt", ignoreCase = true) -> "vtt"
            path.endsWith(".ass", ignoreCase = true) -> "ass"
            path.endsWith(".ssa", ignoreCase = true) -> "ssa"
            path.endsWith(".ttml", ignoreCase = true) -> "ttml"
            path.endsWith(".dfxp", ignoreCase = true) -> "dfxp"
            else -> "srt"
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .take(100) // Limit filename length
    }
}
