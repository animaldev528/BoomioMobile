package com.nuvio.app.core.auth

import android.os.Build
import com.nuvio.app.core.device.DeviceHardwareFingerprint

internal actual fun currentDeviceClientMetadata(): DeviceClientMetadata {
    val deviceName = formatDeviceName(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        fallback = "Android device",
    )
    val osVersion = Build.VERSION.RELEASE.orEmpty()
        .trim()
        .ifBlank { Build.VERSION.SDK_INT.toString() }

    return DeviceClientMetadata(
        deviceName = deviceName,
        platform = "Android $osVersion",
        // The same value the capability report sends, so an enrolment lands on the row the
        // reports already use instead of inserting a second one. Blank until the Application
        // has initialised the holder, which is before any pairing request can be built.
        hwFingerprint = DeviceHardwareFingerprint.value(),
    )
}
