package com.nuvio.app.features.tracking

import com.nuvio.app.features.mdblist.MdbListConfig
import com.nuvio.app.features.simkl.SimklConfig
import com.nuvio.app.features.trakt.TraktConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The tracker OAuth *application ids* the server says this deployment owns.
 *
 * boomio is self-hosted, so the Trakt / Simkl / MDBList OAuth applications belong to whoever runs
 * the box, not to us: they register their own and paste the ids into the dashboard's Credentials
 * page. bsm holds them, and bsc relays them in `GET /api/config` — the per-device document the
 * clients already fetch at cold boot. This object is the client half of that: it writes the served
 * ids over the generated constants, so every existing read site (`TraktConfig.CLIENT_ID`,
 * `SimklConfig.CLIENT_ID`, …) sees the deployment's app instead of the one baked at build time.
 *
 * ⚠️ **Why the generated fields are `var`s.** A baked constant can only ever name the app that
 * shipped in the APK, and those apps are registered for the *device* flow while the phone
 * implements the browser flow — which is why "Connect Trakt" on a self-hosted box lands the user on
 * `invalid_redirect`. There is no way to fix that from the client alone; the id has to come from the
 * deployment, which is what makes it mutable rather than a build-time constant.
 *
 * ⚠️ **Absent means "keep the floor".** A deployment that configured nothing serves no `trackers`
 * object (or serves nulls), and then nothing here changes a thing — the baked values stay, and the
 * behaviour is exactly what it was before this existed. That is deliberate: it makes the whole
 * feature inert on a stock build rather than a new failure mode.
 */
object TrackerCredentials {

    // Captured before anything can overwrite them, so "the server gave us an id" can be told apart
    // from "this is still the app that shipped", and so that an id the deployment *stops* serving can
    // be given back. Read when this object is first touched, which is always an apply() — see [apply].
    private val bakedTraktClientId: String = TraktConfig.CLIENT_ID
    private val bakedSimklClientId: String = SimklConfig.CLIENT_ID
    private val bakedMdbListClientId: String = MdbListConfig.CLIENT_ID

    /**
     * True once the Trakt client id has been replaced by one the deployment supplied.
     *
     * ⚠️ **This is load-bearing, not bookkeeping.** Trakt pairs a client id with its secret at the
     * token exchange; a server-supplied id sent with the *baked* secret is a mismatched pair and
     * Trakt refuses it (`invalid_client`). Trakt deprecates `client_secret` in favour of PKCE, so
     * when the id is the deployment's the exchange drops the secret and presents a PKCE
     * `code_verifier` instead. While the id is still ours the secret goes out as before, and that
     * flow is unchanged.
     *
     * ⚠️ **Equality, not provenance.** A deployment that serves the very same id the build baked is
     * *not* in this state: the id and the secret are then still a registered pair, the secret is
     * still the right one to send, and PKCE would be a change to a working flow for no reason.
     */
    var traktClientIdIsServerSupplied: Boolean = false
        private set

    /** The Trakt flow must complete on PKCE, because there is no matching secret to send. */
    val usesPkce: Boolean
        get() = traktClientIdIsServerSupplied

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Make [ids] the credentials in force.
     *
     * ⚠️ **[ids] is the whole state, not a patch.** A null field means "this deployment supplies
     * none", and that restores the build's own value rather than leaving whatever was there — which
     * is what makes an id the admin *removes* actually go away, instead of surviving until the process
     * restarts. Callers hand over a complete [TrackerIds]; [restoreBaked] is the all-null case.
     */
    fun apply(ids: TrackerIds) {
        TraktConfig.CLIENT_ID = ids.traktClientId ?: bakedTraktClientId
        traktClientIdIsServerSupplied =
            ids.traktClientId != null && ids.traktClientId != bakedTraktClientId
        SimklConfig.CLIENT_ID = ids.simklClientId ?: bakedSimklClientId
        MdbListConfig.CLIENT_ID = ids.mdbListClientId ?: bakedMdbListClientId
    }

    /** Put the build's own ids back — the state a deployment that serves no ids leaves us in. */
    fun restoreBaked() = apply(TrackerIds.None)

    /**
     * Pull the `trackers` object out of a `GET /api/config` document.
     *
     * Answers null when [body] is not a config document at all, so the caller can tell "this
     * deployment serves no ids" (a [TrackerIds] with null fields — authoritative, restore the floor)
     * from "we did not get a config document" (null — change nothing). Only the first may replace what
     * is cached.
     *
     * ⚠️ **The `config_version` check is the difference between those two, and it is not optional.**
     * A 200 is not proof of a config document: a prefix that Caddy routes elsewhere answers with an
     * SPA's HTML, and a misrouted call can answer with an error object. Both parse as "no `trackers`
     * key", and without a shape check either would read as *"the admin removed the ids"* and blank out
     * a cache that is still correct. Every real document carries `config_version`, so it is the tell.
     */
    fun parseConfigDocument(body: String): TrackerIds? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if (!root.containsKey("config_version")) return null
        val trackers = root["trackers"] as? JsonObject ?: return TrackerIds.None
        return TrackerIds(
            traktClientId = trackers.stringOrNull("trakt_client_id"),
            simklClientId = trackers.stringOrNull("simkl_client_id"),
            mdbListClientId = trackers.stringOrNull("mdblist_client_id"),
        )
    }
}

/**
 * The three client ids as served, each null when absent or blank.
 *
 * A null is meaningful — see [TrackerCredentials.apply] — so this is a statement about what the
 * deployment configured, not a partial update.
 */
data class TrackerIds(
    val traktClientId: String?,
    val simklClientId: String?,
    val mdbListClientId: String?,
) {
    /** True when this deployment supplied at least one id — i.e. it runs its own apps. */
    val hasAny: Boolean
        get() = traktClientId != null || simklClientId != null || mdbListClientId != null

    companion object {
        /** "This deployment supplied none of them" — the state that restores the baked floor. */
        val None = TrackerIds(null, null, null)
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
