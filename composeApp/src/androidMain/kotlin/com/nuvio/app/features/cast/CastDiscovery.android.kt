package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI

/**
 * Finds renderers on the LAN with SSDP.
 *
 * Two search targets are sent, because the two protocols answer differently and
 * only one of them can be played to today:
 *
 *   - `urn:schemas-upnp-org:device:MediaRenderer:1` — a DLNA television. The reply
 *     carries a `LOCATION` pointing at a description XML, which is where the
 *     manufacturer and model live.
 *   - `roku:ecp` — a Roku. The reply carries no description; the device answers
 *     `GET :8060/query/device-info` with a much richer document (`is-tv`,
 *     `ui-resolution`), which is why Roku is the one family the catalog can place
 *     precisely.
 *
 * Discovery is bounded and best-effort: a box that answers with something
 * malformed is dropped, never retried, and never fails the whole scan. An empty
 * list is a normal answer — it means "no renderer here", and the caller hides the
 * cast affordance rather than showing a dead button.
 *
 * No `MulticastLock` is taken and no multicast permission is needed: M-SEARCH
 * replies are unicast back to this socket, so joining a group never happens.
 * (mDNS for Google Cast does need both, which is why it is a separate step.)
 */
object CastDiscovery {

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val ROKU_PORT = 8060
    private const val HTTP_TIMEOUT_MS = 1500

    const val DEFAULT_TIMEOUT_MS = 2500L

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "roku:ecp",
    )

    /**
     * One bounded scan. Never throws — a socket failure is an empty result, so a
     * device that is offline or on a hostile network degrades to "no targets".
     */
    suspend fun discover(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): List<CastDevice> =
        withContext(Dispatchers.IO) {
            val replies = searchSsdp(timeoutMillis)
            val devices = replies.mapNotNull { resolve(it) }
                // A Roku TV can answer both targets; keep the richer record, which
                // is the Roku one — it carries the capability row.
                .groupBy { it.ip }
                .map { (_, found) -> found.firstOrNull { it.protocol == CastProtocol.ROKU } ?: found.first() }
            devices.sortedBy { it.name.lowercase() }
        }

    // ── SSDP ────────────────────────────────────────────────────────────────

    private class Reply(val ip: String, val headers: Map<String, String>)

    private fun searchSsdp(timeoutMillis: Long): List<Reply> {
        val socket = try {
            DatagramSocket().apply { soTimeout = timeoutMillis.toInt() }
        } catch (_: Exception) {
            return emptyList()
        }
        val replies = LinkedHashMap<String, Reply>()
        try {
            val address = InetAddress.getByName(SSDP_HOST)
            for (target in SEARCH_TARGETS) {
                val request = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $target\r\n")
                    append("\r\n")
                }.toByteArray()
                socket.send(DatagramPacket(request, request.size, address, SSDP_PORT))
            }

            val deadline = System.currentTimeMillis() + timeoutMillis
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                socket.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: Exception) {
                    break // read timeout: the scan window is over
                }
                val ip = packet.address?.hostAddress ?: continue
                val text = packet.data.decodeToString(0, packet.length)
                val headers = parseHeaders(text)
                replies.putIfAbsent(ip, Reply(ip, headers))
            }
        } catch (_: Exception) {
            // Keep whatever arrived before the failure.
        } finally {
            socket.close()
        }
        return replies.values.toList()
    }

    /** `KEY: value` lines, lowercased keys. The status line is ignored. */
    private fun parseHeaders(text: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            if (key.contains(' ')) continue // the "HTTP/1.1 200 OK" status line
            headers[key] = line.substring(colon + 1).trim()
        }
        return headers
    }

    // ── Turning a reply into a device ───────────────────────────────────────

    private fun resolve(reply: Reply): CastDevice? {
        val st = reply.headers["st"].orEmpty()
        val location = reply.headers["location"]
        return when {
            st.contains("roku:ecp", ignoreCase = true) -> resolveRoku(reply.ip)
            location != null -> resolveDlna(reply.ip, location)
            else -> null
        }
    }

    private fun resolveRoku(ip: String): CastDevice? {
        val xml = httpGet("http://$ip:$ROKU_PORT/query/device-info") ?: return null
        val facts = parseLeafFields(xml)
        val name = facts["friendly-device-name"]
            ?: facts["model-name"]
            ?: facts["user-device-name"]
            ?: return null
        return CastModelCatalog.device(
            protocol = CastProtocol.ROKU,
            ip = ip,
            name = name,
            port = ROKU_PORT,
            facts = facts,
        )
    }

    private fun resolveDlna(ip: String, location: String): CastDevice? {
        val finalLocation = absoluteLocation(ip, location) ?: return null
        Logger.withTag("CastDiscovery").d { "dlna reply from $ip location=$finalLocation" }
        val xml = httpGet(finalLocation) ?: return null
        val facts = parseLeafFields(xml)
        val name = facts["friendlyname"] ?: facts["modelname"] ?: return null
        return CastModelCatalog.device(
            protocol = CastProtocol.DLNA,
            ip = ip,
            name = name,
            port = portOf(finalLocation),
            // The description URL is not a leaf element, so it rides along here:
            // the AVTransport control URL is relative to it, and re-reading the
            // description at cast time is useless without knowing where it lives.
            facts = facts + ("location" to finalLocation),
        )
    }

    /**
     * A `LOCATION` may be relative on a badly-behaved renderer; resolve it against
     * the address the reply actually came FROM, not the one it claims.
     */
    private fun absoluteLocation(ip: String, location: String): String? = when {
        location.startsWith("http://", ignoreCase = true) -> location
        location.startsWith("/") -> "http://$ip$location"
        else -> null
    }

    private fun portOf(url: String): Int =
        runCatching { URI(url).port }.getOrNull()?.takeIf { it > 0 } ?: 80

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
}
