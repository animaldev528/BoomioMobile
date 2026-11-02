package com.nuvio.app.features.cast

/**
 * What a cast attempt did.
 *
 * [reason] is for the log and for the toast: it names the step that failed
 * ("no AVTransport service", "HTTP 500 from Play") rather than saying "cast
 * failed", because the only person who can act on it is the one holding the
 * remote at the television.
 */
data class CastPlayResult(val ok: Boolean, val reason: String? = null) {
    companion object {
        val Ok = CastPlayResult(ok = true)
        fun failed(reason: String) = CastPlayResult(ok = false, reason = reason)
    }
}

/**
 * Hands [url] to [device] and starts it.
 *
 * Android implements DLNA, Google Cast (CASTV2) and AirPlay 1; iOS answers
 * "unsupported" because AirPlay is reached through the system picker instead, and
 * the platform seam keeps the common caller free of that distinction.
 */
internal expect suspend fun playOnRenderer(
    device: CastDevice,
    url: String,
    title: String,
): CastPlayResult

/**
 * Whether a renderer can actually fetch this stream.
 *
 * A television sends no headers of its own, so a stream that only plays with our
 * `Authorization` / `X-Boomio-Stream-Token` headers is a guaranteed 403 on the
 * couch. bsf knows this and, on a cast request, moves the stream token into the
 * URL query — which is the same credential on the one channel the device has.
 * So the question is not "are there headers" but "does the link carry its own
 * credential".
 */
internal fun canHandToRenderer(url: String, requestHeaders: Map<String, String>): Boolean =
    requestHeaders.isEmpty() || url.contains("token=", ignoreCase = true)

/** Which secret an AirPlay receiver is asking the user to read off its screen. */
internal enum class AirPlaySecret { PIN, PASSWORD }

/**
 * Makes [device] ready to take a cast before the picker hands it back.
 *
 * An Apple TV refuses AirPlay 2's transient pairing outright (HTTP 470) and does
 * not say so in its announcement, so the first cast to one has to pair with the
 * PIN it shows. Pairing is per receiver and survives: it is kept in the app's
 * credential store, and every later cast proves it without asking.
 *
 * [askSecret] runs while the connection waits, so the picker can show a code
 * field and suspend until the viewer types what the TV shows; returning null
 * cancels. The session opened here is closed again — this proves the pairing,
 * it does not start playback.
 */
internal expect suspend fun prepareAirPlay(
    device: CastDevice,
    askSecret: suspend (AirPlaySecret) -> String?,
): CastPlayResult
