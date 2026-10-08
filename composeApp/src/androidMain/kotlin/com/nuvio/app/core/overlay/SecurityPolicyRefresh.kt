package com.nuvio.app.core.overlay

import android.content.Context
import android.util.Log
import com.nuvio.app.features.boomio.BoomioSessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "SecurityPolicy"

/**
 * Where a policy comes from, as a seam. **One method, and null means "no answer"** — never
 * "the empty policy", because the caller must be able to tell a server that said nothing from a
 * server that said "these are the defaults" (the first leaves the cache in force, the second
 * replaces it).
 *
 * A `fun interface` so a test can supply a lambda, and so the transport choice stays out of the
 * consumer — exactly the shape `MtlsRegistrationApi` and `OverlayEnrollmentApi` already use for the
 * two other server-pushed facts this app holds.
 */
internal fun interface SecurityPolicyApi {
    suspend fun fetch(): SecurityPolicy?
}

/**
 * The pull transport: **the provisioning channel** — the same pre-tunnel connection that already
 * carries enrollment and certificate registration.
 *
 * ── Why the channel, and not the HTTP internal endpoint ─────────────────────
 * bsm's `GET /api/internal/security-policy` is authenticated by `X-Internal-Key`, which is a
 * **server-to-server** secret (it is what bsc presents; the `companion-pair-blocked-by-internal-key`
 * note records `POST /auth/pair` failing without it). The mobile client holds no such key — verified
 * in this tree, there is no `X-Internal-Key`, no `BuildConfig` field for it, and
 * `BoomioConfig.companionRestBaseUrl` addresses bsc, never bsm. The channel is therefore the only
 * authenticated, repeatable path this client actually has, and it is the one the other server-pushed
 * facts already ride.
 *
 * ⚠️ **`{t:"policy.get"}` is a guess.** The committed server half exposes the policy over HTTP only;
 * the channel handler is a server-side follow-up. This client is written to be *correct when it is
 * absent*: an unknown message type, a refusal, or a dead socket all surface as `null` (or a thrown
 * exception the caller swallows), and the cached policy stays in force. It never falls back to the
 * defaults on a failed pull.
 */
internal class ChannelSecurityPolicyApi(
    private val token: String,
    private val target: suspend () -> ProvisionTarget? = OverlayProvisioning::target,
    private val keypair: () -> OverlayWgKeypair? = OverlayProvisioning::deviceKeypair,
    private val crypto: OverlayProvisionCrypto = GomobileProvisionCrypto,
) : SecurityPolicyApi {

    override suspend fun fetch(): SecurityPolicy? {
        val target = target() ?: return null
        val keypair = keypair() ?: return null

        var opened: OverlayProvisionConnection? = null
        return try {
            val connection = OverlayProvisionConnection.open(
                host = target.host,
                port = target.port,
                devicePrivateKeyBase64 = keypair.privateKeyBase64,
                devicePublicKeyBase64 = keypair.publicKeyBase64,
                serverPublicKeyBase64 = target.provisioningKeyBase64,
                crypto = crypto,
            )
            opened = connection
            when (val reply = connection.securityPolicy(token)) {
                is ProvisionMessage.Policy -> reply.policy
                else -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Ordinary while the tunnel comes up, and a channel that does not answer `policy.get`
            // yet is the expected case. Neither is a reason to move off the cached policy.
            Log.w(TAG, "Security policy pull did not complete: ${error.message}")
            null
        } finally {
            runCatching { opened?.close() }
        }
    }
}

/**
 * Fetches the policy, caches it, and applies it — on enrollment and on app-foreground.
 *
 * ── The design question this answers ────────────────────────────────────────
 * There was no periodic server-config refresh: re-enrollment fires only on a session-token change, so
 * a policy pushed by bsm would never reach a running client. The owner's asymmetry rule ("always be
 * able to go DOWN") makes that a correctness bug, not a nicety: a *loosened* policy must reach an
 * already-deployed client without a wipe.
 *
 * **Transport chosen: (b) — a lightweight pull, refreshed on app-foreground and at enrollment.**
 * (a) alone (the policy riding the enrollment reply) is insufficient by construction: it covers only
 * fresh installs and re-enrollments, which is exactly the population that does not need loosening.
 * A pull is required, and the channel is the client's only authenticated pull path (see
 * [ChannelSecurityPolicyApi]). The enrollment trigger is kept because it is free — the same
 * session-token arrival that re-enrolls also refreshes the policy, so a freshly enrolled device has a
 * policy before it has a tunnel.
 *
 * ⚠️ **Every failure leaves the cached policy in force.** No branch here applies the defaults on an
 * error; the defaults are applied only at [initialize] when there is nothing cached, and by
 * [SecurityPolicyState.apply] when the server actually answered.
 */
internal object SecurityPolicyRefresh {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    /** The api factory. Swapped by tests; production reads the session and the provisioning flag. */
    @Volatile
    internal var apiFactory: () -> SecurityPolicyApi? = ::defaultApi

    /** The foreground throttle. A person switching apps must not cost a handshake each time. */
    @Volatile
    internal var minIntervalMs: Long = 5 * 60 * 1000L

    private val busy = AtomicBoolean(false)
    private val lastAttemptAt = AtomicLong(0L)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // The cache is applied before any network call, so a cold start off-network already routes
        // by the last policy it was told — the same order OverlayEnrollment applies its assignment.
        SecurityPolicyStore.load(context)?.let { SecurityPolicyState.apply(it) }
    }

    /**
     * Pulls the policy once. Returns true when a policy was fetched and applied (whether or not it
     * changed), false when nothing could be applied — in which case the cache is untouched.
     */
    suspend fun refresh(): Boolean {
        val api = apiFactory() ?: return false
        if (!busy.compareAndSet(false, true)) return false
        try {
            val policy = try {
                api.fetch()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Security policy refresh failed: ${error.message}")
                return false
            } ?: return false

            SecurityPolicyState.apply(policy)
            appContext?.let { SecurityPolicyStore.save(it, policy) }
            Log.i(TAG, "Security policy applied: $policy")
            return true
        } finally {
            busy.set(false)
            lastAttemptAt.set(nowMs())
        }
    }

    /**
     * The app-foreground trigger: refresh, unless we tried recently.
     *
     * ⚠️ **Fire-and-forget and never on a caller's critical path.** This runs from `onStart`, where a
     * blocking handshake would show up as a frozen first frame; the result is published by
     * `SecurityPolicyState`, which the routing seams read live, so nothing has to wait for it.
     */
    fun onAppForegrounded() {
        if (nowMs() - lastAttemptAt.get() < minIntervalMs) return
        scope.launch { refresh() }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    /**
     * The production api: the channel when a session exists and the channel is this network's
     * transport, and **null otherwise** — no token, or the ingress is not the transport in use.
     *
     * Null is not an error; it is why a device that reaches the server over HTTPS does not pay for a
     * channel handshake it cannot complete, and it is the same "null is the fallback" rule
     * `OverlayProvisioning.enrollmentApi` follows.
     */
    private fun defaultApi(): SecurityPolicyApi? {
        val token = BoomioSessionRepository.bearerToken()?.takeIf { it.isNotBlank() } ?: return null
        if (!OverlayProvisioning.isLinkedOverChannel()) return null
        return ChannelSecurityPolicyApi(token)
    }
}
