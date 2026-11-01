package com.nuvio.app.features.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * The transport half of a DLNA cast: fetch the description, then POST the two
 * `AVTransport` actions the renderer expects.
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
    when (device.protocol) {
        CastProtocol.DLNA -> dlnaPlay(device, url, title)
        CastProtocol.CAST -> CastPlayResult.failed("Chromecast playback is not implemented yet")
        CastProtocol.ROKU -> CastPlayResult.failed("A Roku needs a channel; playback is not implemented yet")
        CastProtocol.AIRPLAY -> CastPlayResult.failed("AirPlay is not implemented yet")
    }
}

private const val HTTP_TIMEOUT_MS = 2500

private fun dlnaPlay(device: CastDevice, url: String, title: String): CastPlayResult {
    val descriptionUrl = device.raw["location"]?.takeIf { it.isNotBlank() }
        ?: "http://${device.ip}:${device.port.takeIf { it > 0 } ?: 80}/"
    val description = httpGet(descriptionUrl)
        ?: return CastPlayResult.failed("Could not read the renderer's description at $descriptionUrl")
    val controlUrl = DlnaSoap.controlUrl(description)
        ?: return CastPlayResult.failed("Renderer offers no AVTransport service")
    val endpoint = DlnaSoap.resolve(descriptionUrl, controlUrl)

    val setUri = soap(
        endpoint = endpoint,
        action = "SetAVTransportURI",
        body = DlnaSoap.setAvTransportUriEnvelope(url, DlnaSoap.didlLite(title, url)),
    )
    if (!setUri.ok) return CastPlayResult.failed("SetAVTransportURI failed: ${setUri.reason}")

    val play = soap(endpoint = endpoint, action = "Play", body = DlnaSoap.playEnvelope())
    if (!play.ok) return CastPlayResult.failed("Play failed: ${play.reason}")

    return CastPlayResult.Ok
}

private class SoapReply(val ok: Boolean, val reason: String?)

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
        runCatching {
            (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
        }
        if (code in 200..299) SoapReply(true, null) else SoapReply(false, "HTTP $code")
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
