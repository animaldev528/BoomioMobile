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
 * Android implements DLNA; iOS answers "unsupported" because AirPlay is reached
 * through the system picker instead, and the platform seam keeps the common
 * caller free of that distinction.
 */
internal expect suspend fun playOnRenderer(
    device: CastDevice,
    url: String,
    title: String,
): CastPlayResult
