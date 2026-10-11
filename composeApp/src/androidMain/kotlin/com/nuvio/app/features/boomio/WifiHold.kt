package com.nuvio.app.features.boomio

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import co.touchlab.kermit.Logger

/**
 * Keeps the Wi-Fi radio awake for exactly as long as the private-listening fork is
 * receiving, and no longer.
 *
 * **Why this is required, not a tuning tweak.** With no WifiLock held, the phone's Wi-Fi
 * firmware runs its default power save: the radio dozes between beacons and the AP holds
 * frames for it. Measured on this LAN (SM_S908W @ .54, 2026-10-11) with a 20 pps probe:
 * the radio went **completely deaf for ~860 ms once every ~43 s** — 2% of the time — and
 * the AP then dumped every held frame in one burst (18 ICMP replies arrived inside 12 ms
 * while spanning 800 ms of sends).
 *
 * Small probes survive that because 18 small packets fit the AP's per-station buffer. The
 * fork does not: ~173 datagrams/s of 1192 B is ~200 KB/s, so the buffer overflows during
 * the doze and the surplus is **dropped** — which is precisely the 260 ms and 347 ms
 * logical gaps the receiver logged (`fork gap of ~Nms exceeds the cushion — resyncing`),
 * and why it bites hardest on dense/multichannel music, where the byte rate is highest.
 *
 * The TV end has never had this problem: Google Cast's receiver already holds a high-perf
 * Wi-Fi lock on it, which is why `cmd wifi status` there reports `lostTxPackets: 0`.
 *
 * [WifiManager.WIFI_MODE_FULL_LOW_LATENCY] (API 29+) is the mode Android documents for
 * real-time audio/video; older devices fall back to
 * [WifiManager.WIFI_MODE_FULL_HIGH_PERF]. Either way the lock needs `WAKE_LOCK`, which is
 * declared in androidApp's manifest.
 *
 * The two modes are tried in order at *acquire* time, not just at create time: low-latency
 * is the more restricted of the two and a build can hand back a lock object it then refuses
 * to acquire. Failing over to HIGH_PERF — the mode Google Cast's own receiver holds on the
 * Shield — beats silently holding nothing, which is the bug this class exists to fix.
 *
 * Acquire/release are both idempotent — the framework reference-counts a WifiLock, so a
 * double acquire would need a double release to undo.
 */
internal object WifiHold {

    private val log = Logger.withTag("WifiHold")

    /** Surfaces in `dumpsys wifi`'s WifiLock list, so on-device verification can name it. */
    private const val LOCK_TAG = "nuvio:private-listening"

    private var lock: WifiManager.WifiLock? = null

    fun acquire(context: Context?) {
        if (lock != null) return
        val ctx = context ?: return
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            log.w { "no WifiManager — the radio may doze and drop fork audio" }
            return
        }
        // Each mode is tried end to end — create *and* acquire. A build can hand back a
        // low-latency lock object and then refuse to acquire it, so a fallback that only
        // covers create() would still leave us holding nothing.
        val modes = listOf(preferredMode(), WifiManager.WIFI_MODE_FULL_HIGH_PERF).distinct()
        for (mode in modes) {
            val candidate = runCatching { wifi.createWifiLock(mode, LOCK_TAG) }.getOrNull()
            if (candidate == null) {
                log.w { "unable to create a Wi-Fi lock (mode=$mode)" }
                continue
            }
            if (runCatching { candidate.acquire() }.isSuccess) {
                lock = candidate
                log.i { "Wi-Fi lock held (mode=$mode) — radio stays awake for the fork" }
                return
            }
            log.w { "Wi-Fi lock refused (mode=$mode) — trying the next mode" }
        }
        log.w { "no Wi-Fi lock could be held — the radio may doze and drop fork audio" }
    }

    fun release() {
        val held = lock ?: return
        lock = null
        runCatching { held.release() }
        log.i { "Wi-Fi lock released" }
    }

    private fun preferredMode(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
}
