package com.nuvio.app.features.cast

/**
 * iOS has no implementation here on purpose. AirPlay is delivered through the
 * system route picker (`AVRoutePickerView`), not by hand-building a protocol, and
 * discovery on iOS already answers with an empty list — so no device can reach
 * this call. Say so plainly rather than pretending a cast started.
 */
internal actual suspend fun playOnRenderer(
    device: CastDevice,
    url: String,
    title: String,
): CastPlayResult = CastPlayResult.failed("Casting is not available on iOS")

/** iOS has no AirPlay sender here either; the system route picker does this work. */
internal actual suspend fun prepareAirPlay(
    device: CastDevice,
    askSecret: suspend (AirPlaySecret) -> String?,
): CastPlayResult = CastPlayResult.failed("Casting is not available on iOS")
