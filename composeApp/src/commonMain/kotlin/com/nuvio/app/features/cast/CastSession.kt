package com.nuvio.app.features.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The renderer this phone is currently pointed at, or `null` for "play here".
 *
 * One target at a time, app-wide: a cast is a property of the phone's intent, not
 * of one screen, and the details screen is the only place that sets it today. A
 * second screen reading it must see the same value, which is why this is a plain
 * singleton and not state inside a composable.
 *
 * Not persisted: a renderer is reachable at an address, and an address survives a
 * process death only if the TV keeps the same one. Re-discovering on the next
 * launch is cheap and always true, while a remembered IP is sometimes a lie.
 */
object CastSession {

    private val mutableTarget = MutableStateFlow<CastDevice?>(null)

    val target: StateFlow<CastDevice?> = mutableTarget.asStateFlow()

    fun select(device: CastDevice) {
        mutableTarget.value = device
    }

    fun clear() {
        mutableTarget.value = null
    }

    fun current(): CastDevice? = mutableTarget.value
}
