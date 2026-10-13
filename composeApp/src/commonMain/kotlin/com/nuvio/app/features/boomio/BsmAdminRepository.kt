package com.nuvio.app.features.boomio

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.admin_request_failed
import org.jetbrains.compose.resources.getString

/** One household on the box, as `GET /tvpair/admin/accounts` lists it. */
internal data class AdminAccount(
    val id: String,
    val name: String,
    /** From GoTrue, so null both for a household with no Nuvio account and during a Nuvio outage. */
    val email: String?,
    /** How many TVs this account may enrol. `0` is a REAL limit, `null` is "unset". */
    val maxDevices: Int?,
    val archivedAt: String?,
    val userCount: Int,
    val deviceCount: Int,
)

/** One profile row inside an account. */
internal data class AdminProfile(
    val id: String,
    val username: String,
    val displayName: String?,
    val role: String,
    val isActive: Boolean,
    val profileIndex: Int?,
    val profileId: String?,
    /** Recorded policy only — nothing in bsm reads these. See the per-device cap for what binds. */
    val maxBandwidthMbps: Double?,
    val minBandwidthMbps: Double?,
)

/** The resolved ceilings for one device, plus which of them an operator owns. */
internal data class AdminLimits(
    val maxResolution: String?,
    val videoCodecs: List<String>,
    val hdr: List<String>,
    val audioChannels: List<String>,
    val bandwidthCapMbps: Double?,
    /** The subset of the fields an operator has set. The editor marks these "set by you". */
    val overridden: List<String>,
)

internal data class AdminDevice(
    val id: String,
    val deviceId: String,
    val name: String,
    val type: String?,
    val lastSeen: String?,
    val registeredAt: String?,
    val revoked: Boolean,
    val limits: AdminLimits,
)

/** The allowed values for each ceiling, straight from the module that enforces them. */
internal data class AdminVocabulary(
    val maxResolution: List<String>,
    val videoCodecs: List<String>,
    val hdr: List<String>,
    val audioChannels: List<String>,
)

internal data class AdminAccountDetail(
    val account: AdminAccount,
    val users: List<AdminProfile>,
    val devices: List<AdminDevice>,
)

internal data class AdminDeviceDetail(
    val device: AdminDevice,
    val vocabulary: AdminVocabulary,
)

/**
 * The ceilings to write for one device.
 *
 * A null field CLEARS that ceiling, handing the field back to whatever the device reported — which
 * is why every field is nullable rather than defaulted. An absent field is impossible here on
 * purpose: the caller builds this from a whole editor, so "I did not touch it" and "clear it" must
 * not be the same value.
 */
internal data class AdminPolicyPatch(
    val maxResolution: String?,
    val videoCodecs: List<String>?,
    val hdr: List<String>?,
    val audioChannels: List<String>?,
    val bandwidthCapMbps: Double?,
)

internal sealed interface AdminResult<out T> {
    data class Ok<T>(val value: T) : AdminResult<T>

    /** [message] is user-facing: the server's own sentence where there is one. */
    data class Failed(
        val message: String,
        val needsSignIn: Boolean = false,
    ) : AdminResult<Nothing>
}

/**
 * The phone's administrator plane — bsm's `/tvpair/admin/…` family.
 *
 * SAME PREFIX as [TvEnrollRepository], and deliberately so: the edge's carve-out for the
 * `/bsm/tvpair/` family is secret-less and reachable from the WAN, and it lives in a file tracked
 * by neither repo, so reusing the prefix avoids a second live-only edge edit. What differs is the
 * authority — every call here carries the caller's claimed profile and bsm resolves it to a
 * `bsm.users` row with `role='admin'` — and the reach, which is GLOBAL: every household, user and
 * device on the box, where [TvEnrollRepository] only ever sees the caller's own account.
 *
 * ⚠️ THE PROFILE HEADER IS NOT A SECURITY BOUNDARY. bsm never sees a profile PIN, so any member of
 * the household who can select the administrator's profile in this app is an administrator for the
 * length of that session. That is a known, accepted limit of this POC, not an oversight — but it is
 * why the header is sent on every request rather than only on the read, and why switching profiles
 * must [clear] the cached answer.
 *
 * ⚠️ Hiding the Admin row is convenience only. Every route re-checks server-side; a client that
 * believes it is an admin gains nothing.
 */
internal object BsmAdminRepository {
    private val log = Logger.withTag("BsmAdmin")

    /** The account's device cap has no separate ceiling to clamp to — `0` is a real limit. */
    const val MIN_DEVICE_LIMIT: Int = 0

    private val json = Json { ignoreUnknownKeys = true }

    private val _isAdmin = MutableStateFlow<Boolean?>(null)

    /**
     * Whether the ACTIVE PROFILE is an administrator. `null` means "not asked yet", which the UI must
     * treat as "do not show the row" rather than "no" — the difference is a row that flickers in
     * after sign-in versus one that is wrongly absent for a frame.
     */
    val isAdmin: StateFlow<Boolean?> = _isAdmin.asStateFlow()

    private fun baseUrl(): String? =
        BoomioConfig.bsmBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }

    /** The uuid of the profile the person is currently using, or null before profiles have loaded. */
    private fun profileId(): String? =
        ProfileRepository.state.value.activeProfile?.id?.takeIf { it.isNotBlank() }

    private suspend fun accessToken(): String? =
        runCatching { SupabaseProvider.client.auth.currentAccessTokenOrNull() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    // ── the admin flag ──────────────────────────────────────────────────────────

    /**
     * Ask bsm whether the active profile is an administrator.
     *
     * ⚠️ ONLY A DEFINITIVE ANSWER WRITES THE FLAG. A 200 is one, and a 401 *from the server* is one —
     * a dead session administers nothing. A transport error or a 5xx is NOT, and leaves the previous
     * answer alone: flipping the row off because the network blipped would look exactly like being
     * demoted, and would hide the Admin entry from someone who is standing in front of a box that is
     * briefly unreachable.
     *
     * ⚠️ **A token this process has not got yet is the third case, and it used to be miscounted as
     * the second.** [request] reports a missing token as 401 so the *calls* can say "sign in again",
     * but no question was asked, so there is no answer to believe. Counting it as a demotion was a
     * real bug with a nasty shape: the answer is only ever re-asked when `authState` or the profile
     * index changes (see `MainAppContent`), so one cold start that raced the session — the effect
     * runs before the token is restorable — latched `false` for the rest of the session, and the
     * Admin row stayed gone with nothing in it to retry. That is why [Response.refusedByServer]
     * exists, and why the row is also re-asked on every return to the foreground.
     */
    suspend fun refresh() {
        val response = request("GET", WHOAMI_PATH)
        when {
            response.status == 200 -> _isAdmin.value = response.payload?.isAdmin == true
            response.status == 401 && response.refusedByServer -> _isAdmin.value = false
            else -> Unit // no token yet, a transport error, or an outage — keep what we last knew
        }
    }

    /**
     * Forget the answer, for a profile switch or a sign-out.
     *
     * The grain is per-profile, so this MUST run before [refresh] on a switch: without it, profile B
     * inherits profile A's `true` for as long as the round trip takes, and the row is briefly
     * tappable by someone who is not an administrator.
     */
    fun clear() {
        _isAdmin.value = null
    }

    // ── accounts ────────────────────────────────────────────────────────────────

    suspend fun loadAccounts(): AdminResult<List<AdminAccount>> {
        val response = request("GET", "/tvpair/admin/accounts")
        return respond(response) { it.accounts.map(AccountDto::toAccount) }
    }

    suspend fun loadAccount(id: String): AdminResult<AdminAccountDetail> {
        val response = request("GET", "/tvpair/admin/accounts/${encode(id)}")
        return respond(response) { payload ->
            AdminAccountDetail(
                account = payload.account?.toAccount() ?: AdminAccount(
                    id = id, name = "", email = null, maxDevices = null,
                    archivedAt = null, userCount = 0, deviceCount = 0,
                ),
                users = payload.users.map(UserDto::toProfile),
                devices = payload.devices.map(DeviceDto::toDevice),
            )
        }
    }

    /** Creates a Nuvio account and its household, which is what "add a user" means here. */
    suspend fun createAccount(email: String, password: String): AdminResult<AdminAccount> {
        val body = buildJsonObject {
            put("email", email.trim())
            put("password", password)
        }.toString()
        val response = request("POST", "/tvpair/admin/accounts", body)
        return respond(response) { payload ->
            AdminAccount(
                id = payload.account?.id.orEmpty(),
                name = payload.account?.name.orEmpty(),
                email = payload.account?.email ?: email.trim(),
                maxDevices = null,
                archivedAt = null,
                userCount = 0,
                deviceCount = 0,
            )
        }
    }

    /**
     * Deletes an account and its household. Irreversible from here.
     *
     * [confirmEmail] is re-checked server-side; the field exists because this is the one verb with no
     * undo, not because the client's check is trusted.
     */
    suspend fun deleteAccount(id: String, confirmEmail: String): AdminResult<String> {
        val body = buildJsonObject { put("confirm_email", confirmEmail.trim()) }.toString()
        val response = request("DELETE", "/tvpair/admin/accounts/${encode(id)}", body)
        return respond(response) { payload -> payload.scope ?: "nuvio" }
    }

    suspend fun setDeviceLimit(id: String, maxDevices: Int): AdminResult<Int> {
        val body = buildJsonObject { put("max_devices", maxDevices.coerceAtLeast(MIN_DEVICE_LIMIT)) }.toString()
        val response = request("PUT", "/tvpair/admin/accounts/${encode(id)}/device-limit", body)
        return respond(response) { payload -> payload.maxDevices ?: maxDevices }
    }

    /**
     * Records a per-profile bandwidth range.
     *
     * ⚠️ Nothing enforces these. The only bandwidth that reaches a filter is a DEVICE's
     * `bandwidth_cap_mbps`; the screen must not suggest otherwise.
     */
    suspend fun setBandwidth(
        userId: String,
        minMbps: Double?,
        maxMbps: Double?,
    ): AdminResult<Unit> {
        // `put(key, value: Number?)` writes JSON null for a null, which is what "unset" means here.
        val body = buildJsonObject {
            put("min_bandwidth_mbps", minMbps)
            put("max_bandwidth_mbps", maxMbps)
        }.toString()
        val response = request("PUT", "/tvpair/admin/users/${encode(userId)}/bandwidth", body)
        return respond(response) { }
    }

    // ── devices ─────────────────────────────────────────────────────────────────

    suspend fun loadDevice(ref: String): AdminResult<AdminDeviceDetail> {
        val response = request("GET", "/tvpair/admin/devices/${encode(ref)}")
        return respond(response) { payload ->
            AdminDeviceDetail(
                device = payload.device?.toDevice() ?: error("server sent no device"),
                vocabulary = payload.vocabulary?.toVocabulary() ?: AdminVocabulary(
                    maxResolution = emptyList(), videoCodecs = emptyList(),
                    hdr = emptyList(), audioChannels = emptyList(),
                ),
            )
        }
    }

    /** Merges [patch] into the device's ceilings; a null field clears it back to the device's report. */
    suspend fun savePolicy(ref: String, patch: AdminPolicyPatch): AdminResult<AdminDeviceDetail> {
        // ⚠️ EVERY key is sent, and a null one means CLEAR — the server merges this into the stored
        // override, so an omitted key would mean "leave it as it was" rather than "hand it back to
        // the device". The editor always holds a whole device's worth of state, so it can say which.
        val caps = buildJsonObject {
            put("max_resolution", patch.maxResolution)
            put("video_codecs", patch.videoCodecs?.toJsonArray() ?: JsonNull)
            put("hdr", patch.hdr?.toJsonArray() ?: JsonNull)
            put("audio_channels", patch.audioChannels?.toJsonArray() ?: JsonNull)
            put("bandwidth_cap_mbps", patch.bandwidthCapMbps)
        }
        val body = buildJsonObject { put("caps", caps) }.toString()
        val response = request("PUT", "/tvpair/admin/devices/${encode(ref)}/policy", body)
        return respond(response) { payload ->
            AdminDeviceDetail(
                device = payload.device?.toDevice() ?: error("server sent no device"),
                vocabulary = payload.vocabulary?.toVocabulary() ?: AdminVocabulary(
                    maxResolution = emptyList(), videoCodecs = emptyList(),
                    hdr = emptyList(), audioChannels = emptyList(),
                ),
            )
        }
    }

    /**
     * Stops a device. [confirm] must equal the device's name; the server re-checks it.
     *
     * Unlike the owner's revoke in [TvEnrollRepository] this KEEPS the device row, so it stays listed
     * with a Restore action beside it.
     */
    suspend fun revoke(ref: String, confirm: String): AdminResult<AdminDevice> {
        val body = buildJsonObject { put("confirm", confirm.trim()) }.toString()
        val response = request("POST", "/tvpair/admin/devices/${encode(ref)}/revoke", body)
        return respond(response) { payload -> payload.device?.toDevice() ?: error("server sent no device") }
    }

    /** Undoes [revoke]. Already-restored counts as success: the asked-for end state is what it is. */
    suspend fun unrevoke(ref: String): AdminResult<AdminDevice> {
        val response = request("POST", "/tvpair/admin/devices/${encode(ref)}/unrevoke")
        return respond(response) { payload -> payload.device?.toDevice() ?: error("server sent no device") }
    }

    // ── transport ───────────────────────────────────────────────────────────────

    private class Response(
        val status: Int,
        val payload: AdminResponse?,
        val transportError: String?,
        val signIn: Boolean,
        /**
         * ⚠️ **True only when an HTTP answer actually arrived and was a 401.**
         *
         * A token this process does not have yet is *also* reported as 401, because "sign in again"
         * is the right thing to say to someone who tapped a row — but it is not evidence about
         * anyone's role, and [refresh] must never read it as one. The two states look identical in
         * [status] and mean opposite things: one is a server saying no, the other is this app never
         * having asked.
         */
        val refusedByServer: Boolean = false,
    )

    private suspend fun request(method: String, path: String, body: String? = null): Response {
        val base = baseUrl()
            ?: return Response(0, null, getString(Res.string.admin_request_failed), signIn = false)
        val token = accessToken()
            ?: return Response(401, null, null, signIn = true)

        return try {
            val raw = httpRequestRaw(
                method = method,
                url = base + path,
                headers = buildMap {
                    put("Accept", "application/json")
                    put("Content-Type", "application/json")
                    put("Authorization", "Bearer $token")
                    // ⚠️ The header is ABSENT, not empty, when the profile has not loaded. An empty
                    // value would reach bsm as a claim that matches nothing, so the refusal would be
                    // `not_admin` — "you are not an administrator" — instead of `no_profile`, and
                    // that is a materially different sentence to debug.
                    profileId()?.let { put(PROFILE_ID_HEADER, it) }
                },
                body = body ?: "",
            )
            Response(
                status = raw.status,
                payload = runCatching { json.decodeFromString<AdminResponse>(raw.body) }.getOrNull(),
                transportError = null,
                signIn = raw.status == 401,
                refusedByServer = raw.status == 401,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) { "bsm admin request failed ($method $path)" }
            Response(0, null, getString(Res.string.admin_request_failed), signIn = false)
        }
    }

    /**
     * Map an HTTP answer onto a result.
     *
     * The server's own sentence always wins where there is one: it distinguishes "the snapshot failed
     * so nothing was deleted" from "could not reach Nuvio" from "that password is too short", and
     * re-wording it here would throw away the distinction it went to the trouble of making.
     */
    private suspend fun <T> respond(
        response: Response,
        build: (AdminResponse) -> T,
    ): AdminResult<T> {
        val payload = response.payload
        if (response.status in 200..299) {
            if (payload == null) return AdminResult.Failed(defaultMessage(), response.signIn)
            return runCatching { AdminResult.Ok(build(payload)) }
                .getOrElse {
                    log.e(it) { "bsm admin response did not fit its contract" }
                    AdminResult.Failed(defaultMessage(), response.signIn)
                }
        }
        val message = response.transportError
            ?: payload?.error?.takeIf { it.isNotBlank() }
            ?: if (response.signIn) SIGN_IN_MESSAGE else defaultMessage()
        return AdminResult.Failed(message = message, needsSignIn = response.signIn)
    }

    private suspend fun defaultMessage(): String = getString(Res.string.admin_request_failed)

    /** A ref reaches a path unescaped otherwise, and these refs address real rows. */
    private fun encode(ref: String): String =
        ref.replace("%", "%25").replace("/", "%2F").replace("?", "%3F").replace("#", "%23")

    private const val WHOAMI_PATH = "/tvpair/admin/whoami"
    private const val PROFILE_ID_HEADER = "x-nuvio-profile-id"
    private const val SIGN_IN_MESSAGE = "Your session expired — sign in again."
}

// Explicit `JsonPrimitive` rather than the builder's `add(String?)` overload: that overload is not
// resolvable through the MutableList<JsonElement> delegate, so the short form fails to compile.
private fun List<String>.toJsonArray() = buildJsonArray { forEach { add(JsonPrimitive(it)) } }

// ── wire shapes ────────────────────────────────────────────────────────────────

@Serializable
private data class AdminResponse(
    val status: String? = null,
    val ok: Boolean = false,
    val error: String? = null,
    val code: String? = null,
    val scope: String? = null,
    val isAdmin: Boolean = false,
    val role: String? = null,
    val username: String? = null,
    val accounts: List<AccountDto> = emptyList(),
    val account: AccountDto? = null,
    val users: List<UserDto> = emptyList(),
    val devices: List<DeviceDto> = emptyList(),
    val device: DeviceDto? = null,
    val vocabulary: VocabularyDto? = null,
    val maxDevices: Int? = null,
)

@Serializable
private data class AccountDto(
    val id: String = "",
    val name: String = "",
    val email: String? = null,
    val maxDevices: Int? = null,
    val archivedAt: String? = null,
    val createdAt: String? = null,
    val userCount: Int = 0,
    val deviceCount: Int = 0,
)

@Serializable
private data class UserDto(
    val id: String = "",
    val username: String = "",
    val displayName: String? = null,
    val role: String = "user",
    val isActive: Boolean = true,
    val profileIndex: Int? = null,
    val profileId: String? = null,
    val maxBandwidthMbps: Double? = null,
    val minBandwidthMbps: Double? = null,
)

@Serializable
private data class DeviceDto(
    val id: String = "",
    val deviceId: String = "",
    val name: String? = null,
    val type: String? = null,
    val lastSeen: String? = null,
    val registeredAt: String? = null,
    val revoked: Boolean = false,
    val limits: LimitsDto? = null,
)

@Serializable
private data class LimitsDto(
    val max_resolution: String? = null,
    val video_codecs: List<String> = emptyList(),
    val hdr: List<String> = emptyList(),
    val audio_channels: List<String> = emptyList(),
    val bandwidth_cap_mbps: Double? = null,
    val overridden: List<String> = emptyList(),
)

@Serializable
private data class VocabularyDto(
    val max_resolution: List<String> = emptyList(),
    val video_codecs: List<String> = emptyList(),
    val hdr: List<String> = emptyList(),
    val audio_channels: List<String> = emptyList(),
)

private fun AccountDto.toAccount() = AdminAccount(
    id = id,
    name = name,
    email = email,
    maxDevices = maxDevices,
    archivedAt = archivedAt,
    userCount = userCount,
    deviceCount = deviceCount,
)

private fun UserDto.toProfile() = AdminProfile(
    id = id,
    username = username,
    displayName = displayName,
    role = role,
    isActive = isActive,
    profileIndex = profileIndex,
    profileId = profileId,
    maxBandwidthMbps = maxBandwidthMbps,
    minBandwidthMbps = minBandwidthMbps,
)

private fun DeviceDto.toDevice() = AdminDevice(
    id = id,
    deviceId = deviceId,
    name = name?.takeIf { it.isNotBlank() } ?: deviceId,
    type = type,
    lastSeen = lastSeen,
    registeredAt = registeredAt,
    revoked = revoked,
    limits = limits?.toLimits() ?: AdminLimits(
        maxResolution = null, videoCodecs = emptyList(), hdr = emptyList(),
        audioChannels = emptyList(), bandwidthCapMbps = null, overridden = emptyList(),
    ),
)

private fun LimitsDto.toLimits() = AdminLimits(
    maxResolution = max_resolution,
    videoCodecs = video_codecs,
    hdr = hdr,
    audioChannels = audio_channels,
    bandwidthCapMbps = bandwidth_cap_mbps,
    overridden = overridden,
)

private fun VocabularyDto.toVocabulary() = AdminVocabulary(
    maxResolution = max_resolution,
    videoCodecs = video_codecs,
    hdr = hdr,
    audioChannels = audio_channels,
)
