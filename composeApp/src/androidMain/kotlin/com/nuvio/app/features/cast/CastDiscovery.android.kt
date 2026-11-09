package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Finds renderers on the LAN.
 *
 * SSDP covers DLNA and Roku, and (as a fallback) Google Cast through its DIAL
 * advertisement:
 *
 *   - `urn:schemas-upnp-org:device:MediaRenderer:1` — a DLNA television. The reply
 *     carries a `LOCATION` pointing at a description XML, which is where the
 *     manufacturer and model live.
 *   - `roku:ecp` — a Roku. The reply carries no description; the device answers
 *     `GET :8060/query/device-info` with a much richer document (`is-tv`,
 *     `ui-resolution`), which is why Roku is the one family the catalog can place
 *     precisely.
 *   - `urn:dial-multiscreen-org:service:dial:1` — a Google Cast receiver's
 *     secondary announcement. Not every device answers it, and not every device
 *     that answers it is a Cast receiver (smart TVs use DIAL to launch their own
 *     apps), so a DIAL hit only becomes a Cast target when `:8009` is open.
 *
 * mDNS (`_googlecast._tcp`, `_airplay._tcp`) runs alongside it and is the
 * reliable path for Cast and AirPlay — see `CastDiscoveryMdns.android.kt`.
 *
 * Discovery is bounded and best-effort: a box that answers with something
 * malformed is dropped, never retried, and never fails the whole scan. An empty
 * list is a normal answer — it means "no renderer here", and the caller hides the
 * cast affordance rather than showing a dead button.
 *
 * No `MulticastLock` is taken for SSDP and no multicast permission is needed:
 * M-SEARCH replies are unicast back to this socket, so joining a group never
 * happens. (mDNS does need both, which is why it is a separate step.)
 */
object CastDiscovery {

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val ROKU_PORT = 8060
    private const val CAST_PORT = 8009
    /** The ecosystem string `CastModelCatalog.forDlna` gives a Fire TV. */
    private const val AMAZON_ECOSYSTEM = "Amazon"
    private const val HTTP_TIMEOUT_MS = 1500
    private const val PROBE_TIMEOUT_MS = 400
    /** ECP answers on the LAN in milliseconds; anything slower is a stranger. */
    private const val ECP_IDENTITY_TIMEOUT_MS = 900

    const val DEFAULT_TIMEOUT_MS = 2500L

    private val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "roku:ecp",
        "urn:dial-multiscreen-org:service:dial:1",
    )

    /**
     * One bounded scan. Never throws — a socket failure is an empty result, so a
     * device that is offline or on a hostile network degrades to "no targets".
     *
     * SSDP and mDNS run together, each with its own window; the merge is by
     * `(protocol, address)` so a screen that speaks two protocols is offered once
     * per protocol rather than twice for the same one.
     */
    suspend fun discover(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): List<CastDevice> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val ssdp = async { ssdpDevices(timeoutMillis) }
                val mdns = async { CastMdns.discover(timeoutMillis) }
                // ⛔ The second door onto a Roku is no longer offered — see
                // `hideDeadDoors`. A Roku does answer `roku:ecp` AND advertise
                // AirPlay (Roku OS 12.5+), and the AirPlay row was kept on
                // purpose until 2026-11-09 because it needs no Media Assistant
                // channel. It does not play, though: see the measurement there.
                val viaSsdp = ssdp.await()
                (viaSsdp + mdns.await())
                    .hideDeadDoors(viaSsdp)
                    .distinctByIdentified()
            }
        }

    /**
     * Removes the rows this build will not offer, and gives a surviving AirPlay
     * row the ECP identity of the box it sits on.
     *
     * ⛔ **Two doors are withheld because neither plays.**
     *
     *  - **A Roku's AirPlay row.** `_airplay._tcp` and `roku:ecp` describe the
     *    same box, and both were offered on purpose until 2026-11-09. Measured
     *    that night on the Pi: over AirPlay the Roku fetched `master.m3u8`,
     *    `index.m3u8`, three media segments and one audio window — every one
     *    `200`, the last inside 570 ms — then stopped asking altogether and
     *    errored ten seconds later, while the identical master, variants and
     *    rendition played first time on an Apple TV. The receiver's own client
     *    is the fault, nothing server-side is left to tune, so the row is hidden
     *    and the Roku's ECP row (Media Assistant) is the one offered.
     *  - **A Fire TV's Cast row.** It answers DIAL and opens `:8009`, which is
     *    enough to be offered as a Cast target, but nothing plays through it.
     *
     * ⚠️ A Roku is only dropped when SSDP already found it as `roku:ecp`. If the
     * M-SEARCH missed the box, the AirPlay row is the only way to reach it and
     * it stays — that is the case the enrichment below exists for.
     */
    private suspend fun List<CastDevice>.hideDeadDoors(
        alreadyAsked: List<CastDevice>,
    ): List<CastDevice> = coroutineScope {
        val rokuByIp = alreadyAsked
            .filter { it.protocol == CastProtocol.ROKU }
            .associateBy { it.ip }
        map { device ->
            async {
                if (device.isDeadDoor()) null else identify(device, rokuByIp[device.ip])
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * A row that would be offered but cannot play. An Amazon row is a Fire TV:
     * the catalog places one from its DIAL/DLNA facts, and the Cast target built
     * from those facts is dead. The raw facts are checked as well as the placed
     * row because a mDNS find carries no model the table can key on.
     */
    private fun CastDevice.isDeadDoor(): Boolean {
        if (ecosystem.equals(AMAZON_ECOSYSTEM, ignoreCase = true)) return true
        val facts = raw.mapKeys { it.key.lowercase() }
        return facts["manufacturer"]?.contains("amazon", ignoreCase = true) == true ||
            facts["modelname"]?.startsWith("AFT", ignoreCase = true) == true
    }

    /**
     * An AirPlay row that names no capability row is given the ECP identity of
     * the box it sits on, and one that is a Roku is dropped.
     *
     * Roku OS 12.5+ advertises `_airplay._tcp` with `model=MFiModelGroup/…` — a
     * certification grouping, not a model the table knows. A Roku SSDP already
     * found is [seenAsRoku] and is dropped: its `ROKU` row is offered instead.
     *
     * Best-effort throughout: a target whose `:8060` does not answer keeps the
     * record it already had.
     */
    private suspend fun identify(device: CastDevice, seenAsRoku: CastDevice?): CastDevice? {
        if (device.protocol != CastProtocol.AIRPLAY) return device
        if (seenAsRoku != null) return null
        if (device.hasCapabilityRow) return device
        val ecp = ecpFacts(device.ip) ?: return device
        // Protocol stays AIRPLAY: the facts only name the hardware, and this is
        // the M-SEARCH-missed case, where AirPlay is the only door to the box.
        val placed = CastModelCatalog.device(
            protocol = CastProtocol.AIRPLAY,
            ip = device.ip,
            name = device.name,
            port = device.port,
            facts = device.raw + ecp,
            md = device.raw["model"],
        )
        return if (placed.hasCapabilityRow) placed else device
    }

    /**
     * `GET :8060/query/device-info`, or null when nothing on [ip] answers ECP.
     * `model-name` is the field the capability table keys on — a document
     * without it is not an identity, and guessing one is what this file exists
     * to avoid.
     */
    internal suspend fun ecpFacts(ip: String): Map<String, String>? =
        withContext(Dispatchers.IO) {
            val xml = httpGet("http://$ip:$ROKU_PORT/query/device-info", ECP_IDENTITY_TIMEOUT_MS)
                ?: return@withContext null
            val facts = parseLeafFields(xml)
            facts.takeIf { !it["model-name"].isNullOrBlank() }
        }

    private fun ssdpDevices(timeoutMillis: Long): List<CastDevice> {
        val devices = searchSsdp(timeoutMillis).mapNotNull { resolve(it) }
        return devices
            // A Roku TV can answer the generic renderer target too; keep the richer
            // record, which is the Roku one — it carries the capability row.
            .groupBy { it.ip }
            .flatMap { (_, found) ->
                val roku = found.firstOrNull { it.protocol == CastProtocol.ROKU }
                if (roku != null) listOf(roku) else found
            }
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
                // Keyed by target as well as address: one screen may answer both
                // the renderer search and the DIAL one, and those are two
                // different protocols to offer, not a duplicate.
                replies.putIfAbsent("$ip|${headers["st"].orEmpty()}", Reply(ip, headers))
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
            st.contains("dial-multiscreen", ignoreCase = true) -> resolveDial(reply.ip, location)
            location != null -> resolveDlna(reply.ip, location)
            else -> null
        }
    }

    /**
     * A DIAL advertisement is only *evidence* of a Cast receiver, not proof:
     * smart TVs speak DIAL to launch Netflix over their own protocol. The proof
     * is `:8009` accepting a connection, which every Cast receiver must do and a
     * plain DIAL server does not.
     */
    private fun resolveDial(ip: String, location: String?): CastDevice? {
        if (!portOpen(ip, CAST_PORT)) return null
        val finalLocation = location?.let { absoluteLocation(ip, it) }
        val facts = finalLocation?.let { httpGet(it) }?.let { parseLeafFields(it) }.orEmpty()
        return CastModelCatalog.device(
            protocol = CastProtocol.CAST,
            ip = ip,
            name = facts["friendlyname"] ?: facts["modelname"] ?: ip,
            port = CAST_PORT,
            facts = facts,
            md = facts["modelname"],
        )
    }

    private fun portOpen(ip: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

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

    private fun httpGet(url: String, timeoutMs: Int = HTTP_TIMEOUT_MS): String? {
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        return try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
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
