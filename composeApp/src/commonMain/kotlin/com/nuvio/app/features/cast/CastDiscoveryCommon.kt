package com.nuvio.app.features.cast

/** How long one scan listens. Long enough for a slow TV, short enough to hide a spinner. */
internal const val CAST_SCAN_TIMEOUT_MILLIS = 2500L

/**
 * One bounded scan of the local network, in the vocabulary of the capability
 * table.
 *
 * Android answers with SSDP (DLNA, Roku, DIAL) and mDNS (Google Cast, AirPlay) —
 * see `CastDiscovery.android.kt` and `CastDiscoveryMdns.android.kt`. iOS answers
 * with an empty list on purpose: it has no SSDP or mDNS without entitlements, and
 * the picker there will bind to the platform's own cast UI instead. Returning
 * nothing is the honest answer — it makes the caller show "no TV found" rather
 * than a row that could never connect.
 */
internal expect suspend fun discoverCastDevices(
    timeoutMillis: Long = CAST_SCAN_TIMEOUT_MILLIS,
): List<CastDevice>
