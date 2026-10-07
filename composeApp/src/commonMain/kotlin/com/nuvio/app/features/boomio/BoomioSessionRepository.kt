package com.nuvio.app.features.boomio

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.currentDeviceClientMetadata
import com.nuvio.app.core.sync.SyncClientIdentity
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A linked phone↔TV companion session against the bsc companion hub.
 *
 * The token is the `bs_ses_*` session returned by bsc's device-code flow; it is
 * presented as `Authorization: Bearer <token>` for the companion REST API and as
 * `?session_token=<token>` on the `/ws/phone` connection.
 */
data class BoomioSession(
    val token: String,
    val deviceId: String,
    val userId: String?,
    val displayName: String?,
)

/** Transient state for the device-code link flow. */
sealed interface BoomioLinkState {
    data object Idle : BoomioLinkState
    data object Starting : BoomioLinkState

    /** Self-approve took; the poll is expected to complete on its own. */
    data object Linking : BoomioLinkState

    /**
     * A device code is live and waiting for a **human** to approve it.
     *
     * Set when the best-effort self-approve is declined. The edge gates
     * `POST /api/v1/auth/pair` behind `requireInternalKeyStrict` (approval mints
     * a 90-day session, so it is deliberately not reachable unauthenticated);
     * the intended approver is the bsm `/tv` page, server-to-server. The code is
     * already in Redis, so the poll keeps running and completes as soon as
     * someone approves it at [verificationUri].
     */
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String?,
    ) : BoomioLinkState

    /** [BoomioLinkFailure.Start] is generic network/server failure. */
    data class Failed(val reason: BoomioLinkFailure) : BoomioLinkState
}

/**
 * Why a link attempt failed.
 *
 * ⚠️ There is deliberately no "not signed in" member. There used to be, and the
 * flow refused to start without an identity — which made the device-code exchange
 * unusable for the device it exists for. Being signed out now means taking the
 * human-approval path, not failing, so there is nothing to report.
 */
enum class BoomioLinkFailure {
    /** Device-code request or self-approve failed. */
    Start,
    /** Poll timed out or bsc reported the code expired. */
    Expired,
}

/**
 * Owns the bsc companion session for the phone: device-code self-approve
 * (the phone both requests and approves its own code, using the signed-in
 * Nuvio identity), the poll loop that converts the approved code into a
 * session token, and session teardown.
 *
 * Contract mirrored from `bsc/routes/auth-device.js`:
 *   POST /api/v1/auth/device/request {device_id, platform, name}
 *       → {device_code, user_code, expires_in, interval}
 *   POST /api/v1/auth/pair {code, user_id, username, display_name}
 *       → {status:'ok', session_token}
 *   GET  /api/v1/auth/device/poll?dc=<device_code>
 *       → {status:'pending'} | {status:'ok', session_token, id, display_name}
 *
 * Inert when [BoomioConfig.companionEnabled] is false. No DI — callers read the
 * [session] flow and drive the link flow directly, matching the app's
 * object-singleton pattern.
 */
object BoomioSessionRepository {
    private const val POLL_MAX_ATTEMPTS = 60
    private const val POLL_MAX_CONSECUTIVE_FAILURES = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("BoomioSessionRepository")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * ⚠️ **Deliberately NOT [createBoomioHttpClient].** This object owns the *pairing* calls —
     * the device-code request that mints the `bs_ses_*` token — and pairing has to happen
     * **before** anything is enrolled, so it cannot ride a seam that only exists once
     * enrollment has happened. `createBoomioHttpClient()` carries `withOverlayProxy()` and the
     * overlay DNS hook: the relay dials through a tunnel that is not up yet, and the hook
     * resolves boomio names to an overlay address that has not been assigned. Either one turns
     * this call into a connect timeout, which is exactly the "Couldn't start connecting" the
     * link button reports.
     *
     * Enrollment depends on this token, so the dependency has to run one way only. This dials
     * the public edge directly on the platform resolver — the same plane, and for the same
     * reason, as `OverlayEnrollment.enrollmentHttpClient()`.
     */
    private val http = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    private val _session = MutableStateFlow<BoomioSession?>(null)
    /** Non-null once linked. Cleared by [unlink] or storage reset. */
    val session: StateFlow<BoomioSession?> = _session.asStateFlow()

    private val _linkState = MutableStateFlow<BoomioLinkState>(BoomioLinkState.Idle)
    val linkState: StateFlow<BoomioLinkState> = _linkState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var linkJob: Job? = null

    val companionEnabled: Boolean
        get() = BoomioConfig.companionEnabled()

    /** Loads a persisted token into [session]; call once at app startup. */
    fun initialize() {
        if (_session.value != null) return
        val token = BoomioSessionStorage.loadSessionToken()?.takeIf { it.isNotBlank() }
        _session.value = token?.let {
            BoomioSession(
                token = it,
                deviceId = SyncClientIdentity.currentClientId(),
                userId = null,
                displayName = null,
            )
        }
    }

    fun bearerToken(): String? = _session.value?.token

    /**
     * Starts the device-code flow. Safe to call repeatedly — no-op while the
     * flow is already running.
     *
     * ⚠️ **Signing in to Nuvio is not a precondition.** It used to be, and that
     * gate was the load-bearing defect of the old flow: the device-code exchange
     * exists precisely so a device with *no* identity can be admitted, and
     * refusing to start it unless the device was already signed in made that
     * impossible. The code is approved by a human on the bsm `/tv` page, which is
     * the party that holds an account; the poll below mints the session either
     * way, so a signed-out device completes this flow exactly as a signed-in one.
     *
     * A signed-in device additionally gets the one-tap path, because the edge may
     * still permit it to approve its own code. If auth has not settled by the time
     * this runs, [AuthRepository.state] reads as null and the flow takes the human
     * path instead — slower, never wrong.
     */
    fun startLink() {
        if (!companionEnabled) {
            _error.value = "Boomio companion is not configured on this build."
            return
        }
        if (_linkState.value is BoomioLinkState.Starting ||
            _linkState.value is BoomioLinkState.Linking ||
            _linkState.value is BoomioLinkState.AwaitingApproval
        ) {
            return
        }

        linkJob?.cancel()
        linkJob = scope.launch {
            _error.value = null
            _linkState.value = BoomioLinkState.Starting
            try {
                val authUser = AuthRepository.state.value as? AuthState.Authenticated
                val request = requestDeviceCode()

                // Best-effort self-approve, and only when there is an identity to do
                // it with. On an edge that gates /v1/auth/pair
                // (requireInternalKeyStrict — approval mints a 90-day session),
                // this answers 401. That is NOT a failure of the link flow: the
                // code is already live in Redis, so fall through to the human
                // approval path and let whoever opens `verification_uri` (bsm
                // /tv) complete the pair. The poll below picks up the token
                // either way, so this stays a one-tap flow wherever the edge
                // still permits self-approve.
                //
                // ⚠️ A null [authUser] short-circuits to the human path rather than
                // failing the flow. That is the signed-out device, which is the case
                // this flow exists for.
                val selfApproved = if (authUser == null) {
                    false
                } else {
                    try {
                        selfApprove(request.user_code, authUser)
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        log.i { "self-approve declined (${error.message}); awaiting approval" }
                        false
                    }
                }
                _linkState.value = if (selfApproved) {
                    BoomioLinkState.Linking
                } else {
                    BoomioLinkState.AwaitingApproval(
                        userCode = request.user_code,
                        verificationUri = request.verification_uri,
                    )
                }
                pollAndComplete(request, authUser)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w(error) { "Boomio link failed" }
                _linkState.value = BoomioLinkState.Failed(BoomioLinkFailure.Start)
            }
        }
    }

    /** Cancels an in-flight link flow. */
    fun cancelLink() {
        linkJob?.cancel()
        linkJob = null
        _linkState.value = BoomioLinkState.Idle
    }

    /**
     * Tears down the pairing: best-effort POST /api/companion/unpair (so the TV
     * learns the phone disconnected) then clears the local session.
     */
    fun unlink() {
        scope.launch {
            try {
                bearerToken()?.let { token ->
                    http.post("${BoomioConfig.companionRestBaseUrl}/api/companion/unpair") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w(error) { "Boomio unpair failed (non-fatal)" }
            }
            BoomioSessionStorage.clearSessionToken()
            BoomioSessionStorage.clearPairedDeviceId()
            _session.value = null
        }
    }

    private suspend fun requestDeviceCode(): DeviceRequestResponse {
        val metadata = currentDeviceClientMetadata()
        val response = http.post("${BoomioConfig.companionRestBaseUrl}/api/v1/auth/device/request") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    DeviceRequestPayload(
                        device_id = SyncClientIdentity.currentClientId(),
                        platform = metadata.platform,
                        name = metadata.deviceName,
                    ),
                ),
            )
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            val parsed = runCatching { json.decodeFromString<DeviceRequestResponse>(body) }.getOrNull()
            if (parsed != null && parsed.device_code.isNotBlank() && parsed.user_code.isNotBlank()) {
                return parsed
            }
        }
        throw BoomioSessionException("device request failed: HTTP ${response.status.value}")
    }

    private suspend fun selfApprove(userCode: String, authUser: AuthState.Authenticated) {
        val metadata = currentDeviceClientMetadata()
        val response = http.post("${BoomioConfig.companionRestBaseUrl}/api/v1/auth/pair") {
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    PairPayload(
                        code = userCode,
                        user_id = authUser.userId,
                        username = authUser.email?.substringBefore("@"),
                        display_name = metadata.deviceName,
                    ),
                ),
            )
        }
        if (!response.status.isSuccess()) {
            throw BoomioSessionException("self-approve failed: HTTP ${response.status.value}")
        }
    }

    /**
     * [authUser] is null for a device that is not signed in to Nuvio — the ordinary
     * case for a fresh install being onboarded. It is only ever a *fallback* for the
     * session's `userId`: the poll's own `id` wins, and a device admitted by the
     * device-code flow has no local identity to prefer over it.
     */
    private suspend fun pollAndComplete(
        request: DeviceRequestResponse,
        authUser: AuthState.Authenticated?,
    ) {
        var attempts = 0
        var consecutiveFailures = 0
        val intervalMillis = request.interval.coerceIn(2, 10) * 1_000L

        while (currentCoroutineContext().isActive && attempts < POLL_MAX_ATTEMPTS) {
            delay(intervalMillis)
            attempts += 1

            val parsed = try {
                val response = http.get("${BoomioConfig.companionRestBaseUrl}/api/v1/auth/device/poll") {
                    parameter("dc", request.device_code)
                }
                if (!response.status.isSuccess()) continue
                json.decodeFromString<PollResponse>(response.bodyAsText())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                consecutiveFailures += 1
                if (consecutiveFailures >= POLL_MAX_CONSECUTIVE_FAILURES) throw error
                continue
            }
            consecutiveFailures = 0

            when (parsed.status.lowercase()) {
                "ok" -> {
                    val token = parsed.session_token ?: parsed.token
                    if (token.isNullOrBlank()) {
                        throw BoomioSessionException("poll approved but no token")
                    }
                    BoomioSessionStorage.saveSessionToken(token)
                    _session.value = BoomioSession(
                        token = token,
                        deviceId = SyncClientIdentity.currentClientId(),
                        userId = parsed.id ?: authUser?.userId,
                        displayName = parsed.display_name ?: parsed.username,
                    )
                    _linkState.value = BoomioLinkState.Idle
                    return
                }
                "pending" -> Unit
                else -> {
                    // 'expired' (or anything unexpected) — the code is no longer usable.
                    _linkState.value = BoomioLinkState.Failed(BoomioLinkFailure.Expired)
                    return
                }
            }
        }

        _linkState.value = BoomioLinkState.Failed(BoomioLinkFailure.Expired)
    }
}

@Serializable
private data class DeviceRequestPayload(
    @SerialName("device_id") val device_id: String,
    @SerialName("platform") val platform: String,
    @SerialName("name") val name: String,
)

@Serializable
private data class PairPayload(
    @SerialName("code") val code: String,
    @SerialName("user_id") val user_id: String,
    @SerialName("username") val username: String? = null,
    @SerialName("display_name") val display_name: String? = null,
)

@Serializable
private data class DeviceRequestResponse(
    @SerialName("device_code") val device_code: String = "",
    @SerialName("user_code") val user_code: String = "",
    @SerialName("verification_uri") val verification_uri: String? = null,
    @SerialName("expires_in") val expires_in: Int = 300,
    @SerialName("interval") val interval: Int = 5,
)

@Serializable
private data class PollResponse(
    @SerialName("status") val status: String = "",
    @SerialName("token") val token: String? = null,
    @SerialName("session_token") val session_token: String? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("display_name") val display_name: String? = null,
)

private class BoomioSessionException(message: String) : Exception(message)
