package com.nuvio.app.features.boomio

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.normalizeDeviceLinkCode
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.httpRequestRaw
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.enroll_tv_request_failed
import org.jetbrains.compose.resources.getString

/** One TV on the caller's account, as bsm reports it. */
internal data class EnrolledTv(
    val id: String,
    val deviceId: String,
    val name: String,
    /** ISO-8601 from bsm, or null for a TV that has not reported in yet. */
    val lastSeen: String?,
)

internal sealed interface TvEnrollOutcome {
    /**
     * Everything that succeeded, carrying the account's whole TV list so the screen re-renders
     * from the server's answer instead of guessing what changed.
     *
     * @param approvedName the TV just enrolled, for the confirmation line; null for a list or a
     *   removal, where there is nothing new to announce.
     */
    data class Ok(
        val devices: List<EnrolledTv>,
        val limit: Int,
        val approvedName: String? = null,
    ) : TvEnrollOutcome

    /**
     * The account is at its device cap. Its own outcome rather than a [Failed] because the screen
     * does something a failure cannot: it shows [devices] with a Remove button for each, so a slot
     * can be freed and the code retried without leaving the screen.
     */
    data class AtLimit(
        val message: String,
        val devices: List<EnrolledTv>,
        val limit: Int,
    ) : TvEnrollOutcome

    /** [message] is user-facing: the server's own text where there is one, a local line otherwise. */
    data class Failed(
        val message: String,
        val needsSignIn: Boolean = false,
    ) : TvEnrollOutcome
}

/**
 * Enrols a TV — its device identity, the WireGuard peer and the mTLS certificate — from the phone.
 *
 * This is NOT [TvLoginApprovalRepository] and the difference is the whole point of the two rows.
 * Signing a TV in goes through Supabase (`approve_tv_login_session`, `auth.uid()`) and gives the TV
 * a user session. This goes to bsm's `/tvpair`, which hands the code to the edge so it can mint the
 * TV a `bs_ses_` session, and the TV then self-enrols its peer and certificate. Same 6-character
 * shape, two different planes.
 *
 * WHY bsm AND NOT SUPABASE. The pairing code lives in the edge's Redis, not in Postgres, so only
 * the service that can read it can approve it. The phone cannot hold a bsm operator session — its
 * one existing bsm call is a deliberate unauthenticated carve-out — so bsm accepts the caller's
 * Nuvio access token and introspects it against GoTrue instead. That is why every call here
 * carries a bearer token and why a 401 means "sign in again" rather than "not allowed".
 */
internal object TvEnrollRepository {
    private val log = Logger.withTag("TvEnroll")

    /** Length of the code the TV displays, before the client inserts its dash. */
    const val CODE_LENGTH: Int = 6

    private val json = Json { ignoreUnknownKeys = true }

    private fun baseUrl(): String? =
        BoomioConfig.bsmBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }

    /**
     * The caller's Nuvio access token.
     *
     * Read per request rather than cached: the client refreshes it in the background, so a value
     * captured at screen-open can be stale by the time the person has read a code off the TV and
     * typed it in. A stale token is not a benign failure here — GoTrue rejects it and the person is
     * told to sign in again while they are in fact signed in.
     *
     * ⚠️ `client.auth` is an EXTENSION property (`io.github.jan.supabase.auth.auth`), not a member,
     * so the import above is load-bearing. Without it the compiler says only
     * "Unresolved reference 'auth'" and stops there — which reads like the whole call is wrong
     * rather than one missing import. Verified against the 3.4.1 artifact: `Auth` carries
     * `currentAccessTokenOrNull` (it appears in the class as `access$currentAccessTokenOrNull$jd`).
     */
    private suspend fun accessToken(): String? =
        runCatching { SupabaseProvider.client.auth.currentAccessTokenOrNull() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
    ): Response {
        val base = baseUrl()
            ?: return Response(0, null, getString(Res.string.enroll_tv_request_failed), signIn = false)
        val token = accessToken()
            ?: return Response(401, null, null, signIn = true)

        return try {
            val raw = httpRequestRaw(
                method = method,
                url = base + path,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $token",
                ),
                body = body ?: "",
            )
            Response(
                status = raw.status,
                payload = runCatching { json.decodeFromString<ActionResponse>(raw.body) }.getOrNull(),
                transportError = null,
                signIn = raw.status == 401,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) { "TV enrolment request failed ($method $path)" }
            Response(0, null, getString(Res.string.enroll_tv_request_failed), signIn = false)
        }
    }

    /** The account's enrolled TVs and its cap. */
    suspend fun load(): TvEnrollOutcome {
        val response = request("GET", DEVICES_PATH)
        return response.toOutcome()
    }

    /**
     * Enrol the TV displaying [code].
     *
     * The code is normalized exactly as the server normalizes it, so a person who types the dash
     * the app itself shows them does not get a "not 6 characters" refusal for a code that is fine.
     */
    suspend fun approve(code: String): TvEnrollOutcome {
        val normalized = normalizeDeviceLinkCode(code).take(CODE_LENGTH)
        if (normalized.length != CODE_LENGTH) {
            return TvEnrollOutcome.Failed(getString(Res.string.enroll_tv_request_failed))
        }
        val body = buildJsonObject { put("code", normalized) }.toString()
        val response = request("POST", APPROVE_PATH, body)
        val approved = response.payload?.device?.name
        return response.toOutcome(approvedName = approved)
    }

    /** Remove one of the account's TVs, freeing a cap slot. [ref] is a device id or install id. */
    suspend fun revoke(ref: String): TvEnrollOutcome {
        val response = request("POST", "$DEVICES_PATH/${encodePathSegment(ref)}/revoke")
        return response.toOutcome()
    }

    private class Response(
        val status: Int,
        val payload: ActionResponse?,
        val transportError: String?,
        val signIn: Boolean,
    )

    /**
     * Map one HTTP answer onto the outcome the screen renders.
     *
     * The three failure shapes are kept apart deliberately. `device_limit` is the cap (409 + the
     * list, so it gets its own outcome); a 401 is "your session expired" and the honest word is
     * sign in again; everything else is the server's own sentence, which is written for the person
     * reading it — the expiries, the unreachable edge and the "that is a phone" refusal all read
     * differently and re-wording them here would throw away the distinction the server took care to
     * make.
     */
    private suspend fun Response.toOutcome(approvedName: String? = null): TvEnrollOutcome {
        val devices = payload?.devices?.map { it.toEnrolledTv() }.orEmpty()
        val limit = payload?.limit ?: 0

        if (status in 200..299) {
            return TvEnrollOutcome.Ok(devices = devices, limit = limit, approvedName = approvedName)
        }
        if (status == 409 && payload?.code == "device_limit") {
            return TvEnrollOutcome.AtLimit(
                message = payload.error ?: defaultMessage(),
                devices = devices,
                limit = limit,
            )
        }
        val message = transportError
            ?: payload?.error?.takeIf { it.isNotBlank() }
            ?: if (signIn) SIGN_IN_MESSAGE else defaultMessage()
        return TvEnrollOutcome.Failed(message = message, needsSignIn = signIn)
    }

    private suspend fun defaultMessage(): String = getString(Res.string.enroll_tv_request_failed)

    /**
     * A device id is a server-issued uuid, so there is nothing in practice to escape — but a ref
     * that reached a URL unescaped would be a path traversal, and the endpoint deletes a device.
     * Encoding only the characters that change a path keeps a legitimate ref byte-identical.
     */
    private fun encodePathSegment(ref: String): String =
        ref.replace("%", "%25").replace("/", "%2F").replace("?", "%3F").replace("#", "%23")

    private const val DEVICES_PATH = "/tvpair/devices"
    private const val APPROVE_PATH = "/tvpair/approve"

    private const val SIGN_IN_MESSAGE = "Your session expired — sign in again."
}

@Serializable
private data class ActionResponse(
    val ok: Boolean = false,
    val limit: Int = 0,
    val devices: List<TvDeviceDto> = emptyList(),
    val device: TvDeviceDto? = null,
    /** `bad_code`, `expired`, `not_a_tv`, `device_limit` — the server's machine-readable reason. */
    val code: String? = null,
    val error: String? = null,
)

@Serializable
private data class TvDeviceDto(
    val id: String = "",
    val deviceId: String = "",
    val name: String = "",
    val lastSeen: String? = null,
)

private fun TvDeviceDto.toEnrolledTv(): EnrolledTv =
    EnrolledTv(
        id = id.ifBlank { deviceId },
        deviceId = deviceId,
        name = name.ifBlank { deviceId },
        lastSeen = lastSeen,
    )
