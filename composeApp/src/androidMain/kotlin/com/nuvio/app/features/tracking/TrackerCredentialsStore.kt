package com.nuvio.app.features.tracking

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.companionRestBaseUrl
import com.nuvio.app.features.boomio.createBoomioHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "TrackerCredentials"

/**
 * Carries this deployment's tracker app ids from bsc to [TrackerCredentials].
 *
 * ── Why a cache and a background pull, rather than a pull ───────────────────
 * ⚠️ **The ids have to be in force before the first read, and a network call cannot promise that.**
 * `MdbListTracker` builds its configuration from `MdbListConfig.CLIENT_ID` at *object initialization*,
 * and the Trakt authorize URL is built the moment the user taps Connect — neither can wait on a
 * fetch, and a cold start with no network would never get one. So the order is the same one
 * `SecurityPolicyRefresh` and `OverlayEnrollment` use: **apply the cache synchronously in
 * [initialize], then refresh on foreground.** A device that has launched once carries the ids across
 * every later cold start, off-network included.
 *
 * ⚠️ **The address is bsc, and it is the same one the companion link uses.** `GET /api/config` is
 * mounted on bsc's own `/api` router beside `/api/overlay/policy` and the enroll routes, so this
 * reuses `BoomioConfig.companionRestBaseUrl` and `createBoomioHttpClient()` — the client that already
 * carries the overlay DNS pin, the relay, and the mTLS certificate. A second, subtly different TLS
 * seam is exactly what [com.nuvio.app.core.overlay.BscSecurityPolicyApi] refuses to introduce, and the
 * reasoning is identical here.
 *
 * ⚠️ **No credential, and none needed.** `/api/config` is unauthenticated on bsc and the ids it
 * carries are public by construction — they travel in the browser URL the user is sent to. This is
 * also why it is reachable before a device has enrolled, which matters: the ids are needed to
 * *complete* the tracker sign-in that a not-yet-linked device may be doing.
 */
object TrackerCredentialsStore {

    private const val preferencesName = "tracker_credentials"
    private const val keyTrakt = "trakt_client_id"
    private const val keySimkl = "simkl_client_id"
    private const val keyMdbList = "mdblist_client_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var preferences: SharedPreferences? = null

    /** How long a *successful* pull stands. Swapped by tests. */
    internal var minIntervalMs: Long = 5 * 60 * 1000L

    /**
     * Set only on success, deliberately.
     *
     * ⚠️ **A failed pull must not buy silence.** The common failures here are transient and early —
     * the service origin is still the baked floor until discovery lands, the network is not up yet —
     * and each is fixed by the *next* foreground rather than by time passing. Throttling on the
     * attempt instead of the success would spend the whole window on the state that needs retrying.
     */
    private val lastSuccessAt = AtomicLong(0L)

    private val inFlight = AtomicBoolean(false)

    private val http: HttpClient by lazy { createBoomioHttpClient() }

    /**
     * Store the context and apply what the last run cached — **synchronously**, before returning.
     *
     * ⚠️ **This call is what makes the ids correct on the very first read of the process.** It must
     * run before any tracker object's configuration can be captured; in practice that means in
     * `MainActivity.onCreate`, ahead of composition. A first install has nothing cached, so this is a
     * no-op and the baked constants stand until a pull lands.
     */
    fun initialize(context: Context) {
        val store = context.applicationContext
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences = store
        TrackerCredentials.apply(load(store))
    }

    /**
     * Refresh unless we already have a recent answer.
     *
     * Fire-and-forget and throttled, for the reason `SecurityPolicyRefresh.onAppForegrounded` gives:
     * a pull may never delay a resumed frame, and until it lands the cached ids are the ones in force.
     */
    fun onAppForegrounded() {
        if (inFlight.get()) return
        if (System.currentTimeMillis() - lastSuccessAt.get() < minIntervalMs) return
        scope.launch { refresh() }
    }

    /**
     * One pull: `GET {bsc}/api/config`.
     *
     * Returns true when the deployment's answer was applied. False covers every way of *not* getting
     * one — no base URL, a transport error, and a response that is not a config document — and in all
     * of them nothing changes, so the ids already in force stay in force.
     */
    internal suspend fun refresh(): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        try {
            val base = BoomioConfig.companionRestBaseUrl.trimEnd('/')
            if (base.isBlank()) return false

            val body = try {
                http.get("$base/api/config") {
                    // The device id only selects `deviceOverrides`; the `trackers` block is the same
                    // for every device. Read lazily, here rather than at [initialize], because
                    // `SyncClientIdentity.currentClientId()` mints and caches an id when storage is
                    // uninitialized — and an id minted before `SyncClientIdentityStorage.initialize`
                    // cannot be persisted, which is one new device row per launch on the bsm Devices
                    // page. `onAppForegrounded` is only ever called well after that initialize.
                    parameter("device", SyncClientIdentity.currentClientId())
                }.bodyAsText()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Tracker config pull did not complete: ${error.message}")
                return false
            }

            // null is "not a config document" and must not touch the cache — see the shape check in
            // TrackerCredentials.parseConfigDocument for why a 200 alone does not qualify.
            val ids = TrackerCredentials.parseConfigDocument(body) ?: return false

            persist(ids)
            TrackerCredentials.apply(ids)
            lastSuccessAt.set(System.currentTimeMillis())
            Log.i(
                TAG,
                if (ids.hasAny) "Applied this deployment's tracker client ids"
                else "Deployment serves no tracker ids; using the build's own",
            )
            return true
        } finally {
            inFlight.set(false)
        }
    }

    private fun load(store: SharedPreferences): TrackerIds = TrackerIds(
        traktClientId = store.getString(keyTrakt, null),
        simklClientId = store.getString(keySimkl, null),
        mdbListClientId = store.getString(keyMdbList, null),
    )

    /**
     * Cache the answer verbatim, nulls included.
     *
     * ⚠️ **Writing the nulls is the point.** If an admin removes an app id, the next launch has to
     * come up on the baked floor rather than on the id that is no longer registered — a stale client
     * id is a *worse* failure than a missing one, because it sends the user to a vendor page that
     * rejects the redirect instead of to one that was never configured.
     */
    private fun persist(ids: TrackerIds) {
        preferences
            ?.edit()
            ?.putString(keyTrakt, ids.traktClientId)
            ?.putString(keySimkl, ids.simklClientId)
            ?.putString(keyMdbList, ids.mdbListClientId)
            ?.apply()
    }
}
