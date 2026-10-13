package com.nuvio.app.core.device

import android.content.Context

/**
 * The device's hardware fingerprint, reachable from code that has no `Context`.
 *
 * It exists because the two places that must agree about *which device this is* sit on opposite
 * sides of the platform seam: [DeviceCapabilityProbe] is `androidMain` and sends the fingerprint
 * to bsm in its capability report, while the pairing request that enrols the device is built in
 * `commonMain` ([com.nuvio.app.features.boomio.BoomioSessionRepository]).
 *
 * ⚠️ **The two values must be identical.** bsm resolves a device's row by fingerprint *first* on
 * both doors — enrolment and the capability report. A pairing request that carried a different
 * value, or none, cannot match the row the reports write to, so the enrolment inserts a second
 * row for a device that already has one. Measured on 2026-10-13: one phone, eight rows in
 * `bsm.devices`, six of them created by six separate enrolments.
 *
 * The shape follows [com.nuvio.app.core.sync.SyncClientIdentityStorage]: an `androidMain` object
 * holding the application context, initialised once from the `Application`.
 */
internal object DeviceHardwareFingerprint {
    private var appContext: Context? = null

    /** Called once from [com.nuvio.app.NuvioApplication], before any pairing request exists. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * "" until [initialize] has run, and "" on a device that will not supply an ANDROID_ID.
     * Callers treat blank as "unknown" and omit the field, which is the pre-existing wire shape.
     */
    fun value(): String = appContext?.let { DeviceCapabilityProbe.fingerprint(it) }.orEmpty()
}
