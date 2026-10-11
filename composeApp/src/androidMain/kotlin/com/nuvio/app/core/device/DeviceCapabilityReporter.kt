package com.nuvio.app.core.device

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.ConnectivityManager
// ⚠️ Aliased, and not for taste: this package declares its own `NetworkCapabilities` (the report's
// wire DTO), and an explicit import *beats* a same-package declaration — so a plain import here
// silently rebinds every unqualified `NetworkCapabilities` in this file to the platform class.
import android.net.NetworkCapabilities as AndroidNetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.boomio.BoomioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "DevCapReporter"

/** The one bsm path the edge serves without a token — see the class KDoc. */
private const val REPORT_PATH = "/api/prov/devices/capability-report"

private val reportJson = Json {
    // The envelope is a contract with bsm's ingest, which reads named keys — so every field is
    // written, including the defaults. A key that is merely absent and a key that is explicitly
    // empty read differently there, and only one of them is what this device actually probed.
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Reports this install's decode + display capabilities to bsm, so the phone shows up in the fleet
 * view with real numbers instead of not at all.
 *
 * Before this existed a phone could not appear on the Devices page by any path: rows are created
 * only by this report or by pairing, and no mobile tree implemented the reporter. The payoff is not
 * cosmetic — bsf identifies a device from its *certificate*, the edge's cert-index maps that
 * certificate back to [SyncClientIdentity]'s client id, and bsm's per-device policy is looked up by
 * that same id. Reporting the install id is therefore what makes an operator's Devices-page limits
 * actually bind to this phone's playback; a row keyed on anything else would appear and then never
 * be consulted.
 *
 * ⚠️ **Why no auth header.** The edge carves out exactly this path
 * (`/bsm/api/prov/devices/capability-report`) ahead of the token gate and forwards it un-prefixed to
 * bsm, precisely so a secret-less client can report what it is. Every other path under `/bsm` is
 * token-gated or 404s. The request still travels over the certificate-bearing client, because the
 * host is ours — that is incidental here, not a requirement of the route.
 *
 * Best-effort and inert without a bsm url:
 *  - nothing is sent when [BoomioConfig.bsmBaseUrl] is blank (never on a build with a compiled floor);
 *  - no crash, no queue, no user-visible state on failure — the next tick simply retries.
 *
 * ⚠️ **No display listener, unlike the TV reporter.** A TV re-probes when the HDMI sink
 * re-negotiates, because that genuinely changes the answer. A phone's display changes when the user
 * rotates it, which changes nothing bsm acts on — an 8 s debounce would just spend a socket per
 * rotation to re-post the same panel.
 *
 * [start] is idempotent and must run after `SyncClientIdentityStorage.initialize`, or
 * [SyncClientIdentity.currentClientId] would mint an id it cannot persist and every launch would
 * report under a new one — which is the duplicate-row failure this field exists to avoid.
 */
internal object DeviceCapabilityReporter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        // Hold the application context: this outlives whatever Activity called start().
        val app = context.applicationContext
        if (BoomioConfig.bsmBaseUrl.isBlank()) {
            Log.i(TAG, "bsm base url blank — device capability reporting disabled")
            return
        }
        scope.launch {
            delay(INITIAL_REPORT_DELAY_MS)
            // Boot report first, off whatever the meter cached last run, so the fleet row appears
            // promptly after a launch; then measure and re-report only if the number changed.
            reportOnce(app)
            measureThenReReportIfChanged(app)
            while (isActive) {
                delay(HEARTBEAT_MS)
                // Heartbeat: fresh measurement first, then the always-send report — keeps bsm's
                // last_seen honest AND carries the new number.
                DeviceNetworkMeter.measure(app)
                reportOnce(app)
            }
        }
    }

    /** One download measurement, then re-report only if it changed what the last report carried. */
    private suspend fun measureThenReReportIfChanged(context: Context) {
        val before = DeviceNetworkMeter.lastMeasuredMbps(context)
        val measured = DeviceNetworkMeter.measure(context)
        if (measured != null && measured != before) {
            reportOnce(context)
        }
    }

    private suspend fun reportOnce(context: Context) {
        val report = withContext(Dispatchers.Default) {
            DeviceCapabilityProbe.buildReport(
                context = context,
                installId = SyncClientIdentity.currentClientId(),
                app = appInfo(context),
                network = buildNetworkSection(context, DeviceNetworkMeter.lastMeasuredMbps(context)),
            )
        }
        val url = BoomioConfig.bsmBaseUrl.trimEnd('/') + REPORT_PATH
        try {
            httpPostJsonWithHeaders(url, reportJson.encodeToString(report), emptyMap())
            Log.i(
                TAG,
                "Reported device capabilities to bsm " +
                    "${report.display.sinkWidth}x${report.display.sinkHeight} " +
                    "decode=${report.effective.decodeMaxResolution} " +
                    "hdr=${report.effective.hdrUsable}",
            )
        } catch (t: Throwable) {
            // Best-effort: bsm offline / DNS / socket. The next heartbeat or launch retries.
            Log.w(TAG, "Capability report failed — will retry next tick", t)
        }
    }

    /**
     * Which build produced this report. Read from the platform rather than a `BuildConfig`: the
     * generated `BuildConfig` classes live in `:androidApp`, which this module cannot see.
     */
    @Suppress("DEPRECATION")
    private fun appInfo(context: Context): AppInfo = runCatching {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        AppInfo(
            applicationId = context.packageName,
            versionName = info.versionName ?: "",
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                info.versionCode
            },
            flavor = flavorOf(context.packageName),
            debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
    }.getOrElse {
        Log.w(TAG, "PackageManager could not describe this build", it)
        AppInfo(applicationId = context.packageName)
    }

    /**
     * The distribution, as far as it is visible at runtime.
     *
     * ⚠️ Not a Gradle read — the flavor a build was assembled with is not recorded anywhere the
     * platform exposes. `boomio` is the only flavor that overrides its `applicationId`, so it is the
     * only one that can be named from the installed package at all; anything else reports empty
     * rather than a guess. bsm stores this for display and keys nothing off it.
     */
    private fun flavorOf(applicationId: String): String = when {
        applicationId.contains("boomio") -> "boomio"
        else -> ""
    }

    /**
     * The report's `network` section: the measured value from the meter plus whatever the OS exposes
     * about the active link cheaply. Returns null only when there is neither a measured value nor an
     * active network — so an all-null object is never sent.
     */
    @Suppress("DEPRECATION")
    private fun buildNetworkSection(context: Context, measuredMbps: Double?): NetworkCapabilities? {
        var type: String? = null
        var isMetered: Boolean? = null
        var frequencyGhz: Double? = null
        var signalStrengthDbm: Int? = null
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching
            val onWifi = caps.hasTransport(AndroidNetworkCapabilities.TRANSPORT_WIFI)
            type = when {
                onWifi -> "wifi"
                caps.hasTransport(AndroidNetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(AndroidNetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(AndroidNetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            isMetered = !caps.hasCapability(AndroidNetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (onWifi) {
                // `connectionInfo` is deprecated but is still the only cheap source of band and RSSI;
                // both fields are cosmetic here, so a null on a build that hides it costs nothing.
                val wifiInfo = context.getSystemService(WifiManager::class.java)?.connectionInfo
                wifiInfo?.frequency?.takeIf { it > 0 }?.let { frequencyGhz = it / 1000.0 }
                wifiInfo?.rssi?.takeIf { it != Int.MAX_VALUE }?.let { signalStrengthDbm = it }
            }
        }
        if (measuredMbps == null && type == null) return null
        return NetworkCapabilities(
            type = type,
            estimatedBandwidthMbps = measuredMbps,
            frequencyGhz = frequencyGhz,
            signalStrengthDbm = signalStrengthDbm,
            isMetered = isMetered,
        )
    }

    /**
     * First report shortly after a launch, so it does not fight the startup work (identity storage,
     * the addon client, discovery) that has to have settled for the report to be worth anything.
     */
    private const val INITIAL_REPORT_DELAY_MS = 15_000L

    /** Heartbeat per the approved plan (12h); also the re-report cadence while the process lives. */
    private const val HEARTBEAT_MS = 12L * 60L * 60L * 1000L
}
