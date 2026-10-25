package com.nuvio.app.features.addons

import com.nuvio.app.features.boomio.BoomioConfig

internal fun addonTransportBaseUrl(manifestUrl: String): String =
    manifestUrl.substringBefore("?").removeSuffix("/manifest.json")

internal fun buildAddonResourceUrl(
    manifestUrl: String,
    resource: String,
    type: String,
    id: String,
    extraPathSegment: String? = null,
): String {
    val encodedId = id.encodeAddonPathSegment()
    val baseUrl = addonTransportBaseUrl(manifestUrl)
    val resourceUrl = if (extraPathSegment.isNullOrEmpty()) {
        "$baseUrl/$resource/$type/$encodedId.json"
    } else {
        "$baseUrl/$resource/$type/$encodedId/$extraPathSegment.json"
    }
    return resourceUrl + resourceQuery(manifestUrl, baseUrl)
}

/**
 * The query tail for a resource call: whatever the manifest URL already carried, plus this
 * device's decode ceilings when the addon is the boomio media plane.
 *
 * ⚠️ **The caps belong on the REQUEST, not in the manifest URL.** `bsf` gates `/stream` on the
 * capabilities that arrive with the call, and that call is made without a client certificate —
 * so a device that declares nothing is offered everything the index holds. The TV client declares
 * its ceilings here; this one did not, which is how a 1080p, non-HDR tablet was handed a 4K HDR10
 * HEVC release that it opened, stalled on, and abandoned.
 *
 * ⚠️ **Only the media plane.** Every other addon (TMDB rows, real-debrid sources) has no use for
 * these keys, and adding them would change cache keys for no gain. The match is on the addon's
 * base URL, which for `bsf` is exactly [BoomioConfig.boomioBaseUrl] (`<origin>/bsf`) — the
 * manifest is served at its root.
 *
 * A key the manifest URL already carries wins: this appends only what is missing, so a hand-made
 * addon URL that pins a ceiling is not silently overridden by the probe.
 */
private fun resourceQuery(manifestUrl: String, baseUrl: String): String {
    val existing = manifestUrl.substringAfter("?", "")
    val params = mutableListOf<String>()
    if (existing.isNotBlank()) params += existing

    if (!isBoomioMediaPlane(baseUrl)) {
        return if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    val present = existing.split('&')
        .map { it.substringBefore('=').trim() }
        .filter { it.isNotEmpty() }
        .toSet()
    BoomioConfig.bsfCapsQuery()
        .split('&')
        .filter { it.isNotBlank() }
        .filter { it.substringBefore('=') !in present }
        .forEach { params += it }

    return if (params.isEmpty()) "" else "?" + params.joinToString("&")
}

/**
 * True when [baseUrl] is this deployment's `bsf` addon (the media plane).
 *
 * ⚠️ **Matched on the PATH as well as on the configured origin.** An addon URL is stored once,
 * at install time, so the host inside it is whichever name was current then — the DuckDNS
 * origin, the LAN literal, or an overlay address. [BoomioConfig.boomioBaseUrl] follows
 * discovery and may not equal it. A host-only test would therefore drop the caps on exactly the
 * devices whose stored addon predates a rename, which is the class of client this exists to fix.
 */
private fun isBoomioMediaPlane(baseUrl: String): Boolean {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return false
    val bsf = BoomioConfig.boomioBaseUrl.trim().trimEnd('/')
    if (bsf.isNotEmpty() && base.equals(bsf, ignoreCase = true)) return true
    val path = base.substringAfter("://", base).substringAfter('/', "").trimEnd('/')
    return path.equals("bsf", ignoreCase = true) || path.startsWith("bsf/", ignoreCase = true)
}

internal fun String.encodeAddonPathSegment(): String =
    buildString {
        encodeToByteArray().forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (
                char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.' ||
                char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(ADDON_URL_HEX[value shr 4])
                append(ADDON_URL_HEX[value and 0x0F])
            }
        }
    }

private const val ADDON_URL_HEX = "0123456789ABCDEF"
