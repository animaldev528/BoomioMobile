package com.nuvio.android

import android.util.Log
import com.nuvio.app.MainActivity
import com.nuvio.app.core.overlay.OverlayProbe
import java.io.File

/**
 * Debug-only launcher for the userland-tunnel probe.
 *
 * Extends [MainActivity] rather than a bare `Activity` for the same reason
 * `BackgroundDownloadTestActivity` does: the probe needs the app's real initialization to have
 * run, and `MainActivity.onCreate` is where `OverlayRelay.initialize()` and the storage
 * initializers happen. A bare Activity would test a process the app never actually has.
 *
 * ⚠️ **The probe runs off the main thread and the Activity finishes immediately.** The
 * in-tunnel query is bounded by a watchdog that can legitimately wait 15 s, and blocking the
 * UI thread for that is an ANR — which would look like a tunnel failure and send someone
 * debugging the wrong thing entirely.
 *
 * **Reading the result.** The report goes to logcat under the `OverlayProbe` tag, and is
 * written to `cacheDir/overlay-probe-report.txt` so it can be pulled with `run-as` when
 * logcat is inconvenient. Neither is the oracle — the oracle is the server's `wg show`.
 */
class OverlayProbeActivity : MainActivity() {

    private var started = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || started) return
        started = true

        // Holds the process up while the probe runs. Without it the app could be torn down
        // mid-query on a device that reaps backgrounded processes aggressively, and a
        // truncated report is indistinguishable from a hung tunnel.
        val worker = Thread {
            val report = try {
                OverlayProbe.run(applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Probe threw", t)
                "OVERLAY PROBE: threw ${t::class.java.simpleName}: ${t.message}"
            }
            try {
                File(cacheDir, REPORT_FILE).writeText(report)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not write the probe report", t)
            }
        }
        worker.isDaemon = false
        worker.start()
    }

    private companion object {
        const val TAG = "OverlayProbeActivity"
        const val REPORT_FILE = "overlay-probe-report.txt"
    }
}
