package com.nuvio.app.features.cast

/**
 * A renderer on the LAN that runs none of our code — a DLNA TV, a Chromecast, a
 * Roku. Discovery finds these; casting means handing one of them a stream URL.
 *
 * The device is the *decoder* for a cast session, so the stream list has to be
 * built for it rather than for the phone. That is why identity here is
 * `(ecosystem, model)` in the vocabulary of bsc's `device_capabilities` table —
 * bsf looks the row up by exactly those two strings and gates on its columns.
 */
enum class CastProtocol {
    /** SSDP `MediaRenderer:1`; play via `AVTransport.SetAVTransportURI`. */
    DLNA,

    /** Google Cast (`_googlecast._tcp`); the Default Media Receiver plays a URL. */
    CAST,

    /**
     * Roku ECP. Discovery only for now: ECP has no generic "play this URL"
     * command, so a stock Roku needs a companion channel (phase 2).
     */
    ROKU,

    /** `_airplay._tcp`. Deferred — H.264/MP4 or HLS only. */
    AIRPLAY,
}

/**
 * One discovered renderer.
 *
 * `ecosystem`/`model` are the capability-table family strings, or `null` when the
 * discovery facts do not identify one. `null` is not "no caps" — the server
 * substitutes a conservative floor (1080p / H.264 / AAC / no HDR) for a target it
 * cannot place, which is the only safe answer for a screen we cannot size.
 */
data class CastDevice(
    val protocol: CastProtocol,
    val ip: String,
    val name: String,
    val port: Int = 0,
    val ecosystem: String? = null,
    val model: String? = null,
    /** What the protocol actually told us, kept for the log and for diagnosis. */
    val raw: Map<String, String> = emptyMap(),
) {
    /**
     * Stable across rediscovery so the picker does not grow duplicate rows:
     * the address is what makes a renderer reachable, and it is the same value
     * the stream will be bound to.
     */
    val id: String get() = "${protocol.name.lowercase()}:$ip"

    /** True when we can name the row; false means the server's floor applies. */
    val hasCapabilityRow: Boolean
        get() = !ecosystem.isNullOrBlank() && !model.isNullOrBlank()
}

/**
 * The three values `/find` needs to treat a request as a cast.
 *
 * ⚠️ bsf only recognises a cast request when `targetEcosystem` or `targetModel`
 * is present — an IP alone falls through to the ordinary path and binds the
 * stream to the PHONE. So [of] always fills both, with the protocol name and the
 * discovered name when the device could not be placed in the table. Those are
 * deliberately *not* capability rows: the lookup misses, and bsf answers with the
 * floor rather than with an invented profile.
 */
data class CastTarget(
    val ip: String,
    val ecosystem: String,
    val model: String,
) {
    /**
     * `?targetEcosystem=…&targetModel=…&targetIp=…` — ready to append to a /find
     * URL. Encoded by hand because this is commonMain and `URLEncoder` is JVM-only.
     */
    fun queryString(): String =
        "targetEcosystem=${encode(ecosystem)}" +
            "&targetModel=${encode(model)}" +
            "&targetIp=${encode(ip)}"

    companion object {
        fun of(device: CastDevice): CastTarget = CastTarget(
            ip = device.ip,
            // A non-empty label keeps the request a cast request; the server's
            // lookup misses on it and applies the floor, which is the intent.
            ecosystem = device.ecosystem ?: device.protocol.name,
            model = device.model ?: device.name.ifBlank { "unknown" },
        )
    }
}

/**
 * Percent-encodes for a query component: everything outside the RFC 3986
 * unreserved set, UTF-8, uppercase hex. Device names carry spaces, commas and
 * parentheses ("1080p Models (TCL, Hisense, etc)"), so this is load-bearing.
 */
internal fun encode(value: String): String {
    val out = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        if (c < 0x80 && UNRESERVED[c]) {
            out.append(c.toChar())
        } else {
            out.append('%')
            out.append(HEX[c shr 4])
            out.append(HEX[c and 0x0F])
        }
    }
    return out.toString()
}

private val UNRESERVED = BooleanArray(128).also { table ->
    for (ch in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~") {
        table[ch.code] = true
    }
}

private const val HEX = "0123456789ABCDEF"
