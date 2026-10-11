package com.nuvio.app.core.device

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nuvio.app.features.addons.AddonHttpClientProvider
import com.nuvio.app.features.boomio.BoomioConfig
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

private const val TAG = "DeviceNetworkMeter"

/**
 * Headless download-throughput meter for this device's current link — the number behind the fleet
 * view's "Mbps" chip.
 *
 * One bounded download from the boomio bsc edge (`{companion origin}/api/speedtest/probe` — the same
 * host the app already reaches as a companion over wss). The last result is cached in
 * SharedPreferences so the report always has a value on a cold start, before the meter has run again.
 *
 * ⚠️ **A measurement is not a limit.** It is reported as `network.estimated_bandwidth_mbps` and
 * nothing else; `services/device-caps.js` resolves the bandwidth *cap* from the operator override and
 * the legacy column only, so this number can never silently become policy. (That is also why the
 * snake_case key is the only one sent — the dashboard reads that key, and the camelCase lane does not
 * feed limits.)
 *
 * Best-effort and inert: it never throws, and never blocks the caller past the bounded window. A
 * failed probe leaves the previous cached value intact.
 */
internal object DeviceNetworkMeter {

    /** Last measured download throughput in Mbps (1 decimal), or null when never measured. */
    fun lastMeasuredMbps(context: Context): Double? {
        val mbps = prefs(context).getFloat(KEY_LAST_MEASURED_MBPS, -1f)
        return if (mbps > 0f) roundTo1Decimal(mbps.toDouble()) else null
    }

    /**
     * Runs one probe download, caches the result, and returns Mbps (1 decimal). Returns null when the
     * probe is unconfigured, the download fails, or nothing was read within the window.
     */
    suspend fun measure(context: Context): Double? {
        val probe = probeUrl()?.toHttpUrlOrNull() ?: return null
        // The probe streams for up to MEASURE_WINDOW_MS, so a slow-but-alive link must not be cut by
        // the shared client's timeout. `getFor` keeps the certificate scoping intact: the probe goes
        // to a host of ours, so it travels over the certificate-bearing twin and the certificate is
        // still never offered to a stranger's host.
        val client = AddonHttpClientProvider.getFor(probe.toString())
            .newBuilder()
            .readTimeout(MEASURE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(Request.Builder().url(probe).get().build())
        val result = withTimeoutOrNull(MEASURE_TOTAL_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { downloadAndMeasure(call) }
        }
        if (result == null) {
            // Timeout path: release the connection so a stalled blocking read cannot linger.
            runCatching { call.cancel() }
            return null
        }
        prefs(context).edit()
            .putFloat(KEY_LAST_MEASURED_MBPS, result.toFloat())
            .putLong(KEY_LAST_MEASURED_AT_MS, System.currentTimeMillis())
            .apply()
        Log.i(TAG, String.format(Locale.US, "Measured link %.1f Mbps", result))
        return result
    }

    /** Reads bytes from the probe until EOF (fast links, accurate) or the wall-clock window elapses. */
    private fun downloadAndMeasure(call: Call): Double? = runCatching {
        call.execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val start = System.currentTimeMillis()
            val deadline = start + MEASURE_WINDOW_MS
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0L
            body.byteStream().use { input ->
                while (System.currentTimeMillis() < deadline) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
            }
            val elapsed = System.currentTimeMillis() - start
            // Ignore sub-window samples read back instantly — TCP slow-start noise would dominate.
            if (total <= 0L || elapsed < MIN_SAMPLE_ELAPSED_MS) return null
            roundTo1Decimal(total * 8.0 / (elapsed * 1000.0))
        }
    }.getOrNull()

    private fun probeUrl(): String? {
        // Off the seam, not a baked constant: the probe must follow the origin the rest of the app
        // dials, or the measurement describes a host nothing else uses.
        val companion = BoomioConfig.companionBaseUrl.trim()
        if (companion.isBlank()) {
            Log.d(TAG, "companion base url blank — automated bandwidth measurement disabled")
            return null
        }
        // The companion socket is wss; the probe is plain HTTP on the same host and path prefix.
        val origin = companion
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .trimEnd('/')
        return "$origin/api/speedtest/probe"
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun roundTo1Decimal(value: Double): Double = (value * 10).roundToInt() / 10.0

    private const val PREFS_NAME = "nuvio_network_meter"
    private const val KEY_LAST_MEASURED_MBPS = "last_measured_mbps"
    private const val KEY_LAST_MEASURED_AT_MS = "last_measured_at_ms"

    /** Wall-clock window for one probe download — short enough to be a cheap cadence probe. */
    private const val MEASURE_WINDOW_MS = 8_000L
    /** Probe-client read timeout; must exceed the window so a slow link is measured, not cut. */
    private const val MEASURE_READ_TIMEOUT_MS = 15_000L
    /** Outer bound on the whole measurement (covers connect + stall) so callers stay bounded. */
    private const val MEASURE_TOTAL_TIMEOUT_MS = 12_000L
    /** Ignore samples shorter than this — TCP slow-start noise would dominate. */
    private const val MIN_SAMPLE_ELAPSED_MS = 200L
    private const val READ_BUFFER_BYTES = 64 * 1024
}
