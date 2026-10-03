package com.nuvio.app.features.boomio

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Reads a string field without the throwing `jsonPrimitive` accessor — this runs
 * inside error paths, where an exception would mask the failure being reported.
 */
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

/** A TV in a live IPTV party. */
data class IptvPartyMember(
    val deviceId: String,
    val status: String?,
)

/** A live watch party: a shared channel the members are all tuned to. */
data class IptvParty(
    val id: String,
    val streamId: String,
    val channelName: String?,
    val hostPhoneId: String?,
    val members: List<IptvPartyMember>,
    /** Member devices registered with the hub right now. */
    val reachable: List<String>,
)

/**
 * The tuner is held by another party on a different channel.
 *
 * Carries the blocking party's channel so the UI can say which one, rather than
 * a bare "busy" — the household has one tuner, so this is a normal outcome, not
 * an error state.
 */
class IptvPartyConflictException(
    val partyId: String?,
    val channelName: String?,
) : IllegalStateException("a live watch party already owns the tuner")

/** Thrown when an IPTV party call fails; message is user-presentable. */
class IptvPartyException(message: String) : Exception(message)

/**
 * The phone's client for the LIVE watch party, served by bsc (`/api/iptv-party`).
 *
 * NOT the same service as [IptvRepository]: the channel catalogue is on the
 * bss-iptv edge, but the party record, its membership, and the device fan-out
 * live on the companion hub. Both are reached with the same `bs_ses_*` token.
 *
 * The party does no streaming work. The edge's tuner is single-slot and
 * `getSegment` already fetches once and fans the bytes to every caller, so a
 * second TV on the SAME channel costs one upstream connection. Joining a party
 * is therefore just another `iptv_tune` — which is why this client never mints a
 * playlist URL.
 *
 * Same-channel-only is enforced server-side by a tuner lock, not by convention:
 * a different channel tears down the session everyone is watching.
 */
object IptvPartyRepository {
    private val log = Logger.withTag("IptvPartyRepository")
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    private fun requireSession(): BoomioSession =
        BoomioSessionRepository.session.value
            ?: throw IptvPartyException("Link the companion hub first.")

    private fun HttpRequestBuilder.authorize(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun base(): String = "${BoomioConfig.companionRestBaseUrl}/api/iptv-party"

    /**
     * Start a party on [streamId], inviting [targetDeviceIds].
     *
     * The initiator is the phone itself, which becomes the host — only the host
     * may change channel or end the party.
     */
    suspend fun start(
        streamId: String,
        channelName: String?,
        targetDeviceIds: List<String>,
    ): IptvParty {
        val session = requireSession()
        if (targetDeviceIds.isEmpty()) {
            throw IptvPartyException("Pick at least one TV to join.")
        }
        val response = http.post(base()) {
            authorize(session.token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    StartRequest(
                        streamId = streamId,
                        channelName = channelName,
                        targetDeviceIds = targetDeviceIds,
                        initiatorPhoneId = session.deviceId,
                    ),
                ),
            )
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            val parsed = runCatching { json.decodeFromString<PartyDto>(body) }.getOrNull()
            if (parsed != null && parsed.id.isNotBlank()) return parsed.toModel()
        }
        // 409 with party_exists is a normal, explainable outcome: one tuner means
        // one party, so a second party cannot coexist with the first.
        if (response.status.value == 409) {
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (obj?.str("error") == "party_exists") {
                throw IptvPartyConflictException(
                    partyId = obj.str("partyId"),
                    channelName = obj.str("channelName"),
                )
            }
        }
        throw IptvPartyException(describeError("start the watch party", body, response.status.value))
    }

    /** The live party, or null if none is running. */
    suspend fun current(): IptvParty? {
        val session = requireSession()
        val response = http.get("${base()}/mine") {
            authorize(session.token)
            parameter("device_id", session.deviceId)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IptvPartyException(describeError("read the watch party", body, response.status.value))
        }
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val party = obj["party"] ?: return null
        if (party is kotlinx.serialization.json.JsonNull) return null
        return runCatching {
            json.decodeFromJsonElement(PartyDto.serializer(), party).toModel()
        }.getOrNull()
    }

    /** Change the channel for everyone. Host only. */
    suspend fun changeChannel(partyId: String, streamId: String, channelName: String?) {
        val session = requireSession()
        val response = http.post("${base()}/$partyId/channel") {
            authorize(session.token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    ChannelRequest(
                        streamId = streamId,
                        channelName = channelName,
                        actorDeviceId = session.deviceId,
                    ),
                ),
            )
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IptvPartyException(describeError("change channel", body, response.status.value))
        }
    }

    /** End the party and release the tuner. Host only. */
    suspend fun end(partyId: String) {
        val session = requireSession()
        val response = http.post("${base()}/$partyId/end") {
            authorize(session.token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ActorRequest(actorDeviceId = session.deviceId)))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IptvPartyException(describeError("end the watch party", body, response.status.value))
        }
    }

    private fun describeError(action: String, body: String, status: Int): String {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = obj?.str("error")
        val message = obj?.str("message")
        log.w { "IPTV party $action failed (HTTP $status): ${error ?: message ?: body.take(200)}" }
        val detail = message ?: error
        return if (detail.isNullOrBlank()) "Could not $action (HTTP $status)." else "Could not $action: $detail"
    }
}

// ── Wire DTOs ────────────────────────────────────────────────────────────────

@Serializable
private data class StartRequest(
    @SerialName("streamId") val streamId: String,
    @SerialName("channelName") val channelName: String? = null,
    @SerialName("targetDeviceIds") val targetDeviceIds: List<String>,
    @SerialName("initiatorPhoneId") val initiatorPhoneId: String,
)

@Serializable
private data class ChannelRequest(
    @SerialName("streamId") val streamId: String,
    @SerialName("channelName") val channelName: String? = null,
    @SerialName("actorDeviceId") val actorDeviceId: String,
)

@Serializable
private data class ActorRequest(
    @SerialName("actorDeviceId") val actorDeviceId: String,
)

@Serializable
private data class MemberDto(
    @SerialName("deviceId") val deviceId: String = "",
    @SerialName("status") val status: String? = null,
)

@Serializable
private data class PartyDto(
    @SerialName("id") val id: String = "",
    @SerialName("streamId") val streamId: String = "",
    @SerialName("channelName") val channelName: String? = null,
    @SerialName("hostPhoneId") val hostPhoneId: String? = null,
    @SerialName("members") val members: List<MemberDto> = emptyList(),
    @SerialName("reachable") val reachable: List<String> = emptyList(),
)

private fun PartyDto.toModel() = IptvParty(
    id = id,
    streamId = streamId,
    channelName = channelName,
    hostPhoneId = hostPhoneId,
    members = members.map { IptvPartyMember(deviceId = it.deviceId, status = it.status) },
    reachable = reachable,
)
