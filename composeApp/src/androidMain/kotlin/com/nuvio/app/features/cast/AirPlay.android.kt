package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

private val log = Logger.withTag("AirPlay")

/**
 * Starts playback on an AirPlay 1 receiver by telling it where the video is.
 *
 * AirPlay's video path is deliberately small: `POST :<port>/play`, and the
 * receiver fetches the media itself — the same "the device is the fetcher" shape
 * as DLNA and Cast, which is why an AirPlay stream also has to carry its token in
 * the URL.
 *
 * ⚠️ **Two wire shapes, and only one of them works on an Apple TV.**
 *
 * Apple's senders put the media in a **binary plist body**:
 *
 *     Content-Location: <url>, Start-Position: 0, X-Apple-Session-ID: <uuid>
 *
 * with `Content-Type: application/x-apple-binary-plist`, `User-Agent:
 * AirPlay/550.10`, `X-Apple-ProtocolVersion: 1` and `X-Apple-Stream-ID: 1`.
 * Third-party receivers on Android boxes (AirScreen and friends) instead read
 * the URL out of a `Content-Location` **header** with `Content-Type:
 * text/parameters` and an empty body.
 *
 * This app used to send only the second shape. Against an Apple TV that is
 * refused with 403, which is indistinguishable from "you must pair first" — so
 * the failure read as a pairing problem while the real problem was the format.
 * Both shapes are sent now, Apple's first, and only a 403 is reported as a
 * pairing refusal.
 *
 * ⚠️ **AirPlay 1, not 2.** AirPlay 2 needs an encrypted session with per-device
 * pairing keys, which is a platform SDK feature on Apple hardware and out of
 * scope for a hand-rolled sender.
 */
internal object AirPlay {

    private const val DEFAULT_PORT = 7000
    private const val TIMEOUT_MS = 2500

    fun play(device: CastDevice, url: String, title: String): CastPlayResult {
        val port = device.port.takeIf { it > 0 } ?: DEFAULT_PORT
        val endpoint = "http://${device.ip}:$port/play"
        log.d { "airplay: ${device.name} post=$endpoint media=${url.substringBefore('?')} query=${url.contains("token=")}" }

        // ── Apple's shape: the media goes in the body ───────────────────────
        val body = BinaryPlist.encode(
            linkedMapOf(
                "Content-Location" to url,
                "Start-Position" to 0.0,
                "X-Apple-Session-ID" to UUID.randomUUID().toString().uppercase(),
            ),
        )
        val apple = post(endpoint, body, "application/x-apple-binary-plist", mediaHeader = null)
            ?: return CastPlayResult.failed("could not reach the AirPlay receiver at $endpoint")
        log.d { "airplay: plist body -> HTTP ${apple.code}" }
        verdict(device, apple)?.let { return it }

        // ── Third-party shape: the media goes in a header ───────────────────
        val legacy = post(endpoint, ByteArray(0), "text/parameters", mediaHeader = url)
            ?: return CastPlayResult.failed("could not reach the AirPlay receiver at $endpoint")
        log.d { "airplay: header body -> HTTP ${legacy.code}" }
        return verdict(device, legacy) ?: CastPlayResult.failed("AirPlay /play answered HTTP ${legacy.code}")
    }

    /** The result an answer already decides, or null when the shape should be retried. */
    private fun verdict(device: CastDevice, reply: Reply): CastPlayResult? = when {
        reply.code in 200..299 -> {
            log.d { "airplay: ${device.name} accepted the stream" }
            CastPlayResult.Ok
        }

        // The one refusal worth naming: an Apple TV that requires pairing answers
        // 403 to an unauthenticated /play and never takes it. Saying "could not
        // reach" would send someone checking the network for a pairing rule.
        reply.code == 403 -> CastPlayResult.failed(
            "${device.name} refused AirPlay (HTTP 403) — this receiver wants pairing",
        )

        // Anything else is a shape the receiver did not understand; try the other.
        else -> null
    }

    private class Reply(val code: Int, val body: String?)

    private fun post(
        endpoint: String,
        body: ByteArray,
        contentType: String,
        mediaHeader: String?,
    ): Reply? {
        val connection = try {
            URI(endpoint).toURL().openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-Apple-ProtocolVersion", "1")
            connection.setRequestProperty("X-Apple-Stream-ID", "1")
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("User-Agent", "AirPlay/550.10")
            connection.setRequestProperty("Connection", "close")
            if (mediaHeader != null) {
                connection.setRequestProperty("Content-Location", mediaHeader)
                connection.setRequestProperty("Start-Position", "0")
            }
            // An explicit Content-Length is load-bearing: without it at least one
            // receiver (AirScreen) closes the connection without answering at
            // all, which surfaces as "unexpected end of stream" and looks like a
            // network fault. Fixed-length streaming mode emits the header even
            // when the body is empty.
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            val text = runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
            Reply(code, text)
        } catch (error: Exception) {
            log.d { "airplay: $endpoint failed (${error.message})" }
            null
        } finally {
            connection.disconnect()
        }
    }
}
