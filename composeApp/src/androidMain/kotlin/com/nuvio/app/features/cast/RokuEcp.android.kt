package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * Roku playback over ECP, the External Control Protocol on `:8060`.
 *
 * ⚠️ Roku has **no generic "play this URL" command** — unlike DLNA, Cast and
 * AirPlay, the OS does not ship a media renderer that accepts an address. What
 * it does have is deep linking: `POST /launch/<channelId>?u=<url>&t=v` hands a
 * URL to a channel, and whether that URL is played is entirely the channel's
 * decision. So casting to a Roku means casting *through a channel*.
 *
 * The channel this uses is **Media Assistant** (id `782875`), the open-source
 * Roku channel that exists for exactly this job and is the one Home Assistant and
 * Music Assistant drive. It reads `u` (or `contentId`) and `t` (`v` video /
 * `a` audio), and `POST /input` to change media while it is already open.
 *
 * Roku's own "Play on Roku" (`15985`) used to do this without an extra channel,
 * but Roku closed it to third parties in Roku OS 11.5 (2022): the launch is
 * accepted and the app exits immediately. A Roku that has Media Assistant is
 * therefore the only reliable target, and one that does not gets a message
 * naming the channel to install rather than a cast that quietly does nothing.
 *
 * One Roku setting is load-bearing: Settings → System → Advanced system settings
 * → Control by mobile apps → Network access must be **Enabled**. With it off,
 * the `/query` endpoints still answer but `/launch` is refused, which is why a failed
 * launch checks whether the channel actually started before calling it done.
 */
internal object RokuEcp {

    private const val PORT = 8060
    private const val HTTP_TIMEOUT_MS = 2500

    /** Channel-store id of Media Assistant; `dev` is a sideloaded copy. */
    private const val MEDIA_ASSISTANT = "782875"
    private const val SIDELOADED = "dev"

    /**
     * How long to wait for the channel to appear as the active app after a launch.
     * A cold launch of Media Assistant on a stick is a few seconds, not a second;
     * timing out early would report a failure for a cast that actually worked.
     */
    private const val LAUNCH_CONFIRM_MS = 6000L
    private const val LAUNCH_POLL_MS = 250L

    /**
     * One video, handed to the URL-capable channel.
     *
     * The URL goes over percent-encoded (`&` inside it would otherwise end the
     * query), and `videoFormat` tells Media Assistant how to route it: an HLS
     * playlist and an MP4 are opened by different players inside that channel.
     */
    suspend fun play(device: CastDevice, url: String, title: String): CastPlayResult =
        withContext(Dispatchers.IO) {
            val base = baseUrl(device)
            val apps = installedApps(base)
                ?: return@withContext CastPlayResult.failed(
                    "Could not read the Roku's channel list at $base/query/apps. " +
                        "Check Settings → System → Advanced system settings → " +
                        "Control by mobile apps → Network access is Enabled.",
                )
            val channel = listOf(MEDIA_ASSISTANT, SIDELOADED).firstOrNull { apps.containsKey(it) }
                ?: return@withContext CastPlayResult.failed(
                    "The Roku has no channel that can play a link. Install \"Media Assistant\" " +
                        "($MEDIA_ASSISTANT) from the Roku Channel Store, then cast again.",
                )
            log.d {
                "roku: ${device.name} channel=$channel(${apps[channel]}) " +
                    "media=${redact(url)} format=${videoFormat(url)} length=${url.length}"
            }

            val query = buildString {
                append("u=").append(encode(url))
                append("&t=v")
                append("&videoFormat=").append(videoFormat(url))
                if (title.isNotBlank()) append("&videoName=").append(encode(title))
            }
            // Media Assistant is already open: `/input` changes what it is
            // playing. A cold target gets `/launch`, which starts it and plays.
            val running = activeApp(base)?.id == channel
            val endpoint = if (running) {
                "$base/input?$query"
            } else {
                "$base/launch/$channel?$query"
            }
            val status = post(endpoint)
            if (status !in 200..299) {
                return@withContext CastPlayResult.failed(
                    "The Roku refused the launch (HTTP $status). Enable " +
                        "Settings → System → Advanced system settings → Control by mobile apps → " +
                        "Network access, then try again.",
                )
            }

            // 200 means the request was accepted, not that the channel opened.
            // The setting above is the commonest reason it did not.
            val started = awaitActiveApp(base, channel)
            if (!started) {
                return@withContext CastPlayResult.failed(
                    "The Roku accepted the request but did not open Media Assistant. " +
                        "Enable Settings → System → Advanced system settings → " +
                        "Control by mobile apps → Network access, then try again.",
                )
            }
            log.d { "roku: ${device.name} playing via $channel" }
            CastPlayResult.Ok
        }

    /** `POST /keypress/<key>` — the remote's transport, and "Home" for stop. */
    suspend fun keypress(device: CastDevice, key: String): Boolean = withContext(Dispatchers.IO) {
        post("${baseUrl(device)}/keypress/$key") in 200..299
    }

    /**
     * The Roku's system media player, when the channel uses it.
     *
     * Media Assistant plays through its own Video node, so on many Rokus this
     * answers with no player at all — hence nullable rather than an error. The
     * remote screen then shows transport controls and no position, which is the
     * honest description of what the phone can know.
     */
    suspend fun mediaPlayer(device: CastDevice): RokuPlayer? = withContext(Dispatchers.IO) {
        val xml = httpGet("${baseUrl(device)}/query/media-player") ?: return@withContext null
        val fields = parseLeafFields(xml)
        if (fields["state"].isNullOrBlank() && fields["position"].isNullOrBlank()) {
            return@withContext null
        }
        RokuPlayer(
            state = fields["state"].orEmpty(),
            positionMs = fields["position"]?.toLongOrNull() ?: 0L,
            durationMs = fields["duration"]?.toLongOrNull() ?: 0L,
        )
    }

    internal data class RokuPlayer(val state: String, val positionMs: Long, val durationMs: Long)

    private data class ActiveApp(val id: String, val name: String)

    private fun baseUrl(device: CastDevice): String = "http://${device.ip}:$PORT"

    /** `GET /query/apps` → id → display name, or null when the list is unreadable. */
    private fun installedApps(base: String): Map<String, String>? {
        val xml = httpGet("$base/query/apps") ?: return null
        val apps = LinkedHashMap<String, String>()
        for (match in APP_ELEMENT.findAll(xml)) {
            apps[match.groupValues[1]] = decodeEntities(match.groupValues[2]).trim()
        }
        return apps
    }

    private fun activeApp(base: String): ActiveApp? {
        val xml = httpGet("$base/query/active-app") ?: return null
        val match = APP_ELEMENT.find(xml) ?: return null
        return ActiveApp(match.groupValues[1], decodeEntities(match.groupValues[2]).trim())
    }

    private suspend fun awaitActiveApp(base: String, channel: String): Boolean {
        val deadline = System.currentTimeMillis() + LAUNCH_CONFIRM_MS
        while (System.currentTimeMillis() < deadline) {
            if (activeApp(base)?.id == channel) return true
            kotlinx.coroutines.delay(LAUNCH_POLL_MS)
        }
        return false
    }

    /** Empty-body POST: ECP commands carry their input in the URL. */
    private fun post(url: String): Int {
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (error: Exception) {
            log.w { "roku: bad URL ${redact(url)}: ${error.message}" }
            return -1
        }
        return try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.setRequestProperty("Connection", "close")
            connection.outputStream.use { } // sends Content-Length: 0
            val code = connection.responseCode
            // Drain so the socket closes cleanly rather than resetting.
            runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }
            code
        } catch (error: Exception) {
            log.w { "roku: POST ${redact(url)} failed: ${error.message }" }
            -1
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGet(url: String): String? {
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * What Media Assistant calls the stream. Roku's native players can decode HLS
     * and MP4 but not Matroska, which is why [castPlaybackUrl] remuxes a
     * registered stream to the playlist before it gets here.
     */
    private fun videoFormat(url: String): String =
        when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "m3u8" -> "hls"
            "mp4", "m4v" -> "mp4"
            "webm" -> "webm"
            "mkv" -> "mkv"
            else -> "hls"
        }

    private fun redact(url: String): String = url.substringBefore('?')

    private val log = Logger.withTag("RokuEcp")

    // `<app id="12" type="appl" version="6.1.0">Netflix</app>`
    private val APP_ELEMENT = Regex("<app\\s[^>]*\\bid=\"([^\"]+)\"[^>]*>([^<]*)</app>")

    // The five predefined entities only — this is untrusted text from the LAN,
    // and an unknown named entity must never expand.
    private fun decodeEntities(value: String): String {
        if (value.indexOf('&') < 0) return value
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
