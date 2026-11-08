package com.nuvio.app.core.overlay

/**
 * iOS has no mDNS advertising in this build.
 *
 * The phone's overlay discovery stack is Android-only today (`OverlayLocalDiscovery` and the
 * ladder live in `androidMain`), so there is no tuple to publish here and no Bonjour registration
 * wired. Reporting [TvAdvertAvailability.Unsupported] keeps the share affordance hidden rather
 * than offering something that would silently do nothing.
 */
internal actual object TvServerAdvert {
    actual fun availability(): TvAdvertAvailability = TvAdvertAvailability.Unsupported

    actual suspend fun start(): TvAdvertResult = TvAdvertResult.Unsupported

    actual fun stop() = Unit
}
