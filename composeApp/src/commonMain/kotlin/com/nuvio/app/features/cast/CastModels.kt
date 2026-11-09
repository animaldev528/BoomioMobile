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
     * Roku ECP. Discovery is SSDP `roku:ecp` + `GET :8060/query/device-info`;
     * playback is a deep link into a URL-capable channel (`Media Assistant`),
     * because Roku has no generic "play this URL" command of its own.
     */
    ROKU,

    /**
     * `_airplay._tcp`, AirPlay 1: `POST :7000/play` with the URL in
     * `Content-Location`. The receiver fetches it itself, so like DLNA and Cast it
     * plays H.264/MP4 or HLS only — and the stream must carry its token in the URL.
     */
    AIRPLAY,

    /**
     * A Boomio TV, reached over the companion socket rather than a renderer
     * protocol. It is not discovered on the LAN: it is already enrolled and
     * listed by `GET /api/companion/devices`, and playback starts with a `play`
     * frame instead of `SetAVTransportURI` / CASTV2 / ECP.
     *
     * It lives in this enum so the details cast button can offer ONE target list
     * — a Boomio TV, a Roku, an Apple TV, a DLNA panel — and the rest of the cast
     * plumbing (session, stream resolution, the remote surface) does not need a
     * second code path.
     */
    BOOMIO,
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
    /** The row's ceiling in words, for the picker only — never for a decision. */
    val ceiling: String? = null,
    /** What the protocol actually told us, kept for the log and for diagnosis. */
    val raw: Map<String, String> = emptyMap(),
    /**
     * The enrolled device id of a Boomio TV (`CompanionDevice.deviceId`), or null
     * for a discovered renderer. It is the stable identity of a TV across DHCP
     * lease changes, and it is what the server resolves the TV's own caps from —
     * a renderer has neither, which is why the two are kept distinct here.
     */
    val deviceId: String? = null,
) {
    /**
     * Stable across rediscovery so the picker does not grow duplicate rows.
     *
     * An enrolled Boomio TV is identified by its device id: its address can change
     * with a lease, and the id is what the server and the companion socket key on.
     * A renderer has no id, so its address is the identity — the same value the
     * stream is bound to.
     */
    val id: String
        get() = deviceId?.let { "boomio:$it" } ?: "${protocol.name.lowercase()}:$ip"

    /** True when we can name the row; false means the server's floor applies. */
    val hasCapabilityRow: Boolean
        get() = !ecosystem.isNullOrBlank() && !model.isNullOrBlank()
}

/**
 * Collapses the same screen discovered twice onto the record that can name it.
 *
 * A Cast receiver answers BOTH SSDP/DIAL and mDNS, and the two do not say the
 * same thing. A television with Cast built in answers DIAL with
 * `modelName=Eureka Dongle` — Google's generic name for the built-in product,
 * which names no row at all — while the same screen's `_googlecast._tcp` record
 * carries `md=Chromecast`. Deduplicating on the id alone therefore threw away
 * the only record that could place the device, and the picker showed
 * "not identified" for a television it had correctly identified a second earlier.
 *
 * The rule is identification first, then order: a record that names a row beats
 * one that does not, and when neither (or both) can, the first one seen wins.
 */
internal fun List<CastDevice>.distinctByIdentified(): List<CastDevice> =
    groupBy { it.id }
        .map { (_, found) -> found.reduce(::betterRecord) }
        .sortedBy { it.name.lowercase() }

private fun betterRecord(current: CastDevice, candidate: CastDevice): CastDevice = when {
    candidate.hasCapabilityRow && !current.hasCapabilityRow -> candidate
    else -> current
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
    /**
     * An enrolled Boomio TV's device id. When present the server resolves the
     * target's stored limits from this id (bsm's `/api/internal/device-caps`)
     * instead of from a model row — a Boomio TV's row is keyed per device, not
     * per model, and it is the same row its own policy is enforced from.
     */
    val deviceId: String? = null,
    /**
     * The ceiling the RENDERER declared for itself, when the protocol tells us.
     *
     * ⛔ The model row describes the BOX, not the receiver we are handing a URL
     * to. A Roku Streaming Stick 4K decodes 4K HEVC/DV in its own player, but its
     * AirPlay 2 receiver advertises 1080p / SDR / `avc1` only — and the pick is
     * made from the row. Measured 2026-11-09: a 4K DV x265 remux (25.7 Mbps) was
     * cast to that receiver, which played a few seconds, stuttered, and died.
     *
     * bsf already parses these (`targetMaxResolution`, `targetHdr`,
     * `targetVideoCodecs`) and can only ever NARROW the row with them, so
     * declaring nothing was leaving the receiver's own limits unsaid.
     */
    val maxResolution: String? = null,
    val hdr: String? = null,
    val videoCodecs: String? = null,
) {
    /**
     * `?targetEcosystem=…&targetModel=…&targetIp=…[&targetDeviceId=…][&caps]` —
     * ready to append to a /find URL. Encoded by hand because this is commonMain
     * and `URLEncoder` is JVM-only.
     */
    fun queryString(): String = buildString {
        append("targetEcosystem=").append(encode(ecosystem))
        append("&targetModel=").append(encode(model))
        append("&targetIp=").append(encode(ip))
        deviceId?.takeIf { it.isNotBlank() }?.let {
            append("&targetDeviceId=").append(encode(it))
        }
        maxResolution?.takeIf { it.isNotBlank() }?.let {
            append("&targetMaxResolution=").append(encode(it))
        }
        hdr?.takeIf { it.isNotBlank() }?.let {
            append("&targetHdr=").append(encode(it))
        }
        videoCodecs?.takeIf { it.isNotBlank() }?.let {
            append("&targetVideoCodecs=").append(encode(it))
        }
    }

    companion object {
        /**
         * ⛔ An AirPlay 2 receiver is a NARROWER renderer than the box under it,
         * and it is the one that fetches and decodes these bytes. The AirPlay
         * discovery path reads only the `_airplay._tcp` TXT record — it never
         * asks the receiver `GET :7000/info`, which is where the real
         * `widthPixelsMax` / `HDRSupportedModes` live — so the receiver's own
         * ceiling is not available here to forward.
         *
         * What is available is what we know about the protocol's receivers: an
         * Apple TV over AirPlay does 4K HDR, everything else on this LAN answers
         * `_airplay._tcp` as a compatibility receiver and is H.264 / 1080p / SDR
         * (measured on the Roku: `avc1.64002a`, `HDRMode: SDR`, 1920x1080).
         * Declaring that ceiling costs nothing when it is right and is the
         * difference between playing and dying in seconds when it is.
         */
        private fun airPlayCeiling(device: CastDevice): Boolean =
            device.protocol == CastProtocol.AIRPLAY &&
                !device.ecosystem.equals("Apple", ignoreCase = true)

        fun of(device: CastDevice): CastTarget {
            val narrowToAirPlay = airPlayCeiling(device)
            return CastTarget(
                ip = device.ip,
                // A non-empty label keeps the request a cast request; the server's
                // lookup misses on it and applies the floor, which is the intent.
                ecosystem = device.ecosystem
                    ?: if (device.protocol == CastProtocol.BOOMIO) "boomio" else device.protocol.name,
                model = device.model ?: device.name.ifBlank { "unknown" },
                deviceId = device.deviceId,
                maxResolution = if (narrowToAirPlay) "1080p" else null,
                hdr = if (narrowToAirPlay) "0" else null,
                videoCodecs = if (narrowToAirPlay) "h264" else null,
            )
        }
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
