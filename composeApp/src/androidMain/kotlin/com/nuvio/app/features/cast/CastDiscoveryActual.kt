package com.nuvio.app.features.cast

internal actual suspend fun discoverCastDevices(timeoutMillis: Long): List<CastDevice> =
    CastDiscovery.discover(timeoutMillis)
