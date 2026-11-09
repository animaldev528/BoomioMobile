package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * The transport half of a cast, per protocol: DLNA over `AVTransport`, Google
 * Cast over CASTV2 on `:8009`, AirPlay over `POST :7000/play`.
 *
 * Everything here is bounded and single-shot. A renderer that is asleep, on
 * another VLAN or lying about its control URL fails within a few seconds and the
 * caller falls back to the phone, because a cast that hangs looks like a player
 * that hung.
 */
internal actual suspend fun playOnRenderer(
    device: CastDevice,
    url: String,
    title: String,
): CastPlayResult = withContext(Dispatchers.IO) {
    val rendererUrl = castPlaybackUrl(device, url)
    // Which URL went over the wire, in the shape the receiver will fetch it. The
    // query is where the token rides, so it is named, never printed.
    log.d {
        "cast: ${device.protocol}/${device.name} media=${redact(rendererUrl)} " +
            "query=${rendererUrl.contains("token=")} remuxed=${rendererUrl != url}"
    }
    val result = when (device.protocol) {
        CastProtocol.DLNA -> dlnaPlay(device, url, title)
        CastProtocol.CAST -> CastV2.play(device, rendererUrl, title)
        // A Roku has no generic media renderer: playback is a deep link into a
        // URL-capable channel. See RokuEcp for why that channel is Media Assistant.
        CastProtocol.ROKU -> RokuEcp.play(device, rendererUrl, title)
        CastProtocol.AIRPLAY -> AirPlay.play(device, rendererUrl, title)
    }
    if (!result.ok) log.w { "cast to ${device.protocol}/${device.name} (${device.ip}) failed: ${result.reason}" }
    result
}

/**
 * The URL a renderer can actually decode.
 *
 * ⚠️ A Google Cast Default Media Receiver plays MP4/WebM and HLS — it ships no
 * Matroska decoder at all. The household's streams are mostly MKV, so the URL
 * `/find` hands back is a file the receiver accepts the LOAD for, connects, and
 * then cannot play: the television opens its player and the phone reports a
 * failure a second later.
 *
 * bsc remuxes any registered stream into HLS on the same streamId — the media
 * playlist `/proxy/<id>/hls/index.m3u8` (a `-c copy` remux), reached through
 * `/hls/master.m3u8` on a Roku, whose player cannot hear in-band fMP4 audio
 * (see `castPlaybackUrl`) — and the stream is already bound to the renderer's
 * address, so the playlist's relative URIs resolve beside it and are authorised
 * by the same rule the file itself was. AirPlay gets the same playlist: an
 * Apple receiver is HLS-native and no better at MKV than the Cast receiver is.
 *
 * A URL that is already playable is returned byte-identical, query included —
 * which also covers a server that has already turned it into a playlist.
 */
internal fun castPlaybackUrl(device: CastDevice, url: String): String {
    // A Roku's own player decodes HLS and MP4, not Matroska, exactly like the
    // Cast receiver and the Apple TV — so the same remux applies.
    if (device.protocol != CastProtocol.CAST &&
        device.protocol != CastProtocol.AIRPLAY &&
        device.protocol != CastProtocol.ROKU
    ) {
        return url
    }
    val path = url.substringBefore('?')
    val file = path.substringAfterLast('/')
    val ext = file.substringAfterLast('.', "").lowercase()
    if (ext == "mp4" || ext == "m4v" || ext == "webm" || ext == "m3u8") return url
    // Only bsc's own registered-stream shape is rewritten. Anything else (a
    // library file, a direct addon URL) is handed over as it came: a guess about
    // someone else's route is worse than the URL they gave us.
    if (!file.startsWith("video.")) return url
    val query = url.substringAfter('?', "")
    // ⛔ A Roku's player cannot hear audio MUXED into fMP4/CMAF at all: handed
    // the media playlist it plays the picture and reports `audio="none"`. The
    // master is the only address at which bsc can declare the audio as a
    // rendition — and name its codec, or the player refuses the stream at the
    // master without fetching anything else. Both of Roku's cast doors are
    // matched by User-Agent server-side, so this choice is only about which
    // address to hand over. Every other renderer keeps the media playlist byte
    // for byte: a master there would add a hop and change nothing.
    val isRoku = device.ecosystem?.equals("Roku", ignoreCase = true) == true ||
        device.name.contains("Roku", ignoreCase = true)
    val playlist = path.substringBeforeLast('/') +
        (if (isRoku) "/hls/master.m3u8" else "/hls/index.m3u8")
    return if (query.isEmpty()) playlist else "$playlist?$query"
}

private const val HTTP_TIMEOUT_MS = 2500

/**
 * Every step of a failed cast is a line here. A cast fails on a device the
 * developer cannot see — sometimes across the room, sometimes across a country —
 * so "it said it could not reach it" is not a diagnosis. The log names the
 * description URL, the control URL, the URL handed over (token elided) and the
 * renderer's own HTTP status.
 */
private val log = Logger.withTag("CastPlayback")

/** The URL without its query: a stream token is a credential, not a log line. */
private fun redact(url: String): String = url.substringBefore('?')

private fun dlnaPlay(device: CastDevice, url: String, title: String): CastPlayResult {
    val descriptionUrl = device.raw["location"]?.takeIf { it.isNotBlank() }
        ?: "http://${device.ip}:${device.port.takeIf { it > 0 } ?: 80}/"
    log.d { "dlna: ${device.name} desc=$descriptionUrl media=${redact(url)} query=${url.contains("token=")}" }
    val description = httpGet(descriptionUrl)
        ?: return CastPlayResult.failed("Could not read the renderer's description at $descriptionUrl")
    val controlUrl = DlnaSoap.controlUrl(description)
        ?: return CastPlayResult.failed("Renderer offers no AVTransport service")
    val endpoint = DlnaSoap.resolve(descriptionUrl, controlUrl)
    log.d { "dlna: avTransport=$controlUrl -> $endpoint" }

    val setUri = soap(
        endpoint = endpoint,
        action = "SetAVTransportURI",
        body = DlnaSoap.setAvTransportUriEnvelope(url, DlnaSoap.didlLite(title, url)),
    )
    if (!setUri.ok) return CastPlayResult.failed("SetAVTransportURI failed: ${setUri.reason}")

    val play = soap(endpoint = endpoint, action = "Play", body = DlnaSoap.playEnvelope())
    if (!play.ok) return CastPlayResult.failed("Play failed: ${play.reason}")

    log.d { "dlna: ${device.name} accepted the stream" }
    return CastPlayResult.Ok
}

private class SoapReply(val ok: Boolean, val reason: String?, val body: String? = null)

private fun soap(endpoint: String, action: String, body: String): SoapReply {
    val connection = try {
        URI(endpoint).toURL().openConnection() as HttpURLConnection
    } catch (error: Exception) {
        return SoapReply(false, error.message ?: "bad control URL")
    }
    return try {
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPACTION", "\"${DlnaSoap.AV_TRANSPORT}#$action\"")
        connection.setRequestProperty("Connection", "close")
        val bytes = body.toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        val code = connection.responseCode
        // Drain either stream so the socket is reused/closed rather than reset.
        val text = runCatching {
            (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
        }.getOrNull()
        // A renderer's 500 carries the reason in a UPnPError block; without it the
        // log says only "500", which is where diagnosis stops.
        log.d { "soap: $action -> HTTP $code${text?.takeIf { it.isNotBlank() }?.let { " body=${it.take(300)}" }.orEmpty()}" }
        if (code in 200..299) SoapReply(true, null, text) else SoapReply(false, "HTTP $code", text)
    } catch (error: Exception) {
        SoapReply(false, error.message ?: "network error")
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
