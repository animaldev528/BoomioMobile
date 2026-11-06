package com.nuvio.app.features.cast

/**
 * iOS deliberately finds nothing here.
 *
 * SSDP needs a multicast entitlement this app does not hold, and the platforms
 * iOS supports for casting (AirPlay) arrive through `AVRoutePickerView`, not
 * through a scan. Reporting an empty network keeps the picker truthful: it says
 * "no TV found" instead of listing a renderer the app cannot reach.
 */
internal actual suspend fun discoverCastDevices(timeoutMillis: Long): List<CastDevice> = emptyList()
