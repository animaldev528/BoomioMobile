package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import java.net.HttpURLConnection
import java.net.URI

private val log = Logger.withTag("AirPlay")

/**
 * Starts playback on an AirPlay 1 receiver by telling it where the video is.
 *
 * AirPlay's video path is deliberately small: `POST :<port>/play` with the media
 * in a `Content-Location` header. The receiver then fetches that URL itself — the
 * same "the device is the fetcher" shape as DLNA and Cast, which is why an AirPlay
 * stream also has to carry its token in the URL.
 *
 * ⚠️ **AirPlay 1, not 2.** AirPlay 2 needs an encrypted session with per-device
 * pairing keys, which is a platform SDK feature on Apple hardware and out of
 * scope for a hand-rolled sender. AirPlay 1 is what third-party receivers on
 * Android TV boxes (AirScreen and friends) implement, and what this speaks; an
 * Apple TV in its default mode will refuse it — the picker still lists it,
 * because a refusal that names the receiver is more useful than hiding it.
 */
internal object AirPlay {

    private const val DEFAULT_PORT = 7000
    private const val TIMEOUT_MS = 2500

    fun play(device: CastDevice, url: String, title: String): CastPlayResult {
        val port = device.port.takeIf { it > 0 } ?: DEFAULT_PORT
        val endpoint = "http://${device.ip}:$port/play"
        log.d { "airplay: ${device.name} post=$endpoint media=${url.substringBefore('?')} query=${url.contains("token=")}" }

        val connection = try {
            URI(endpoint).toURL().openConnection() as HttpURLConnection
        } catch (error: Exception) {
            return CastPlayResult.failed("bad AirPlay address $endpoint (${error.message})")
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = false
            connection.setFixedLengthStreamingMode(0)
            connection.setRequestProperty("Content-Location", url)
            connection.setRequestProperty("Start-Position", "0")
            connection.setRequestProperty("X-Apple-ProtocolVersion", "1")
            connection.setRequestProperty("Content-Type", "text/parameters")
            connection.setRequestProperty("User-Agent", "MediaControl/1.0")
            connection.setRequestProperty("Connection", "close")

            val code = connection.responseCode
            val body = runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
            log.d { "airplay: POST /play -> HTTP $code${body?.let { " body=${it.take(200)}" }.orEmpty()}" }
            if (code in 200..299) {
                log.d { "airplay: ${device.name} accepted the stream" }
                CastPlayResult.Ok
            } else {
                CastPlayResult.failed("AirPlay /play answered HTTP $code")
            }
        } catch (error: Exception) {
            CastPlayResult.failed("could not reach the AirPlay receiver at $endpoint (${error.message})")
        } finally {
            connection.disconnect()
        }
    }
}
