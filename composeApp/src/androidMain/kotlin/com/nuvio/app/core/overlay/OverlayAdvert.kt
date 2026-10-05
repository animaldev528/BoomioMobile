package com.nuvio.app.core.overlay

import java.net.InetAddress

/**
 * One resolved mDNS advert — the address *and* the tunnel tuple together.
 *
 * ⚠️ **This exists because `OverlayLocalDiscovery` originally kept only the address**, which was
 * correct while Tier 1 was tunnel-free and is wrong now that the tunnel carries the LAN too
 * (architecture §4.1). The advert always carried the tuple; nothing read it. Rather than browse
 * twice — once for the pin, once for the endpoint, on the same six-second window — the browse
 * publishes this and both consumers take what they need.
 *
 * [address] is the **SRV target's** address, i.e. where the server is on the network the client
 * is on. That is the endpoint's host: the advert's `addr=10.77.0.1` is the address the client
 * *takes on the overlay*, which is a different plane entirely and is never dialled.
 */
internal data class OverlayMdnsAdvert(
    val address: InetAddress,
    val serverPublicKeyBase64: String?,
    val port: Int?,
    val serviceName: String?,
)

/**
 * The part of a discovery advert that is a **tunnel**, as opposed to an address.
 *
 * Both publication channels carry this and they spell it differently — mDNS uses one
 * `key=value` TXT attribute per field, DuckDNS packs them into one semicolon-separated string —
 * so both are parsed into this one shape and nothing downstream has to know which rung it came
 * from.
 *
 * Both fields are nullable, and that is a statement about the *wire*, not about what is
 * usable: a channel can publish an address without a key (the pre-2026-10-05 mDNS advert did
 * exactly that), and the honest representation of "there was no key in the advert" is null
 * rather than a guess.
 */
internal data class OverlayAdvertTuple(
    val serverPublicKeyBase64: String?,
    val port: Int?,
) {
    companion object {
        /** The WG listen port when the advert does not name one. The POC's port, and the default. */
        const val DEFAULT_PORT = 51820
    }
}

/**
 * Parses the mDNS advert's TXT attribute map.
 *
 * The publisher is `avahi-publish-service` on the server (`overlay/`), which emits
 * `addr=10.77.0.1`, `pubkey=<base64>`, `port=51820`, `v=1` as **separate** TXT strings; Android
 * hands them back as a `Map<String, ByteArray>`, so the split has already happened by the time
 * this is called.
 *
 * ⚠️ **`pk` and `pubkey` are both accepted on purpose.** The DuckDNS record uses `pk` and the
 * mDNS advert uses `pubkey`, and a ladder whose rungs disagree about a field name would fail
 * rung 2 for a reason that has nothing to do with DNS. Accepting both here costs one `?:` and
 * removes a whole class of "it works on the LAN and not off it" bug.
 *
 * Every value is trimmed and every blank is treated as absent: an advert is written by a shell
 * script, and `pubkey=` with nothing after it must read as "no key" rather than as a key that
 * happens to be the empty string.
 */
internal fun parseMdnsAdvertTxt(attributes: Map<String, ByteArray>?): OverlayAdvertTuple {
    if (attributes.isNullOrEmpty()) return OverlayAdvertTuple(null, null)
    val fields = attributes.mapValues { (_, raw) ->
        runCatching { raw.toString(Charsets.UTF_8) }.getOrDefault("").trim()
    }
    val key = fields["pubkey"].orEmpty()
        .ifEmpty { fields["pk"].orEmpty() }
        .takeIf { it.isNotEmpty() }
    val port = fields["port"].orEmpty().toPortOrNull()
    return OverlayAdvertTuple(key, port)
}

/**
 * Parses the `TXT` rdata a DNS channel returns, in either of the two shapes it arrives in.
 *
 * The record the POC publishes is one string:
 *
 * ```
 * v1;pk=<base64>;port=51820;prov=0
 * ```
 *
 * A DNS `TXT` record is a *sequence* of length-prefixed strings, though, and a resolver is free
 * to return the same logical record split across several of them — or to return several
 * records. So [strings] is joined before splitting: one `;`-delimited field list, however the
 * transport chose to frame it. Joining with `;` rather than `""` is deliberate — a split that
 * lands mid-field would otherwise fuse two fields into one nonsense token, while a separator
 * that is already the field delimiter turns that case into two ordinary fields.
 *
 * Unknown fields (`v`, `prov`) are ignored rather than rejected: the format is versioned
 * precisely so a server can add fields without a client update, and a client that failed on an
 * unrecognised token would make that impossible.
 */
internal fun parseDnsTxtRecord(strings: List<String>): OverlayAdvertTuple {
    if (strings.isEmpty()) return OverlayAdvertTuple(null, null)
    val fields = strings.joinToString(";")
        .split(';')
        .mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            token.substring(0, separator).trim().lowercase() to token.substring(separator + 1).trim()
        }
        .toMap()
    val key = (fields["pk"] ?: fields["pubkey"])?.takeIf { it.isNotEmpty() }
    val port = fields["port"].orEmpty().toPortOrNull()
    return OverlayAdvertTuple(key, port)
}

/**
 * A usable TCP/UDP port number, or null.
 *
 * ⚠️ **A `Long` range check, not an `Int` one.** `toIntOrNull` rejects overflow, but
 * `"99999999999"` is well inside a `Long` and would silently wrap if it were narrowed first.
 * The check happens before any conversion, so a nonsense port is rejected rather than truncated
 * into a plausible-looking one.
 */
internal fun String.toPortOrNull(): Int? =
    toLongOrNull()?.takeIf { it in 1..65535 }?.toInt()

/**
 * A WireGuard public key the tunnel could actually use, or null.
 *
 * ⚠️ **Validated here, at the edge, rather than at `IpcSet`.** A key that is the wrong length
 * is the classic silent failure of this whole subsystem: `android.util.Base64` is lenient about
 * stray characters, so a truncated paste decodes cleanly to the wrong bytes, the handshake
 * never completes, and the only symptom is a tunnel that does not come up. Rejecting it here
 * means the ladder keeps walking rungs instead of stopping on a candidate that cannot work —
 * see [decodeWireGuardKey], which owns the 32-byte rule for the whole package.
 */
internal fun validServerKeyOrNull(value: String?): String? {
    val candidate = value?.trim().orEmpty()
    if (candidate.isEmpty()) return null
    return candidate.takeIf { decodeWireGuardKey(it) != null }
}
