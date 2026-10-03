package com.nuvio.app.features.boomio

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A channel category (group) on the IPTV edge. */
data class IptvGroup(
    val id: String,
    val name: String,
    /** Channels in this group on the panel — NOT how many are tunable here. */
    val channelCount: Int,
)

/** One tunable live channel. */
data class IptvChannel(
    val streamId: String,
    val name: String,
    val categoryId: String,
    val icon: String?,
    val hasEpg: Boolean,
)

/**
 * The household's tunable channel set.
 *
 * [unscoped] is a real state, not a failure: the edge serves only the groups the
 * household follows, and following nothing returns an empty list BY DESIGN (the
 * alternative is parsing ~57k channels). The picker must say so rather than
 * render a bare empty list, which reads as a broken panel.
 */
data class IptvChannelsPage(
    val channels: List<IptvChannel>,
    val unscoped: Boolean,
    val followedGroups: Int,
)

/** Thrown when an IPTV catalogue call fails; message is user-presentable. */
class IptvException(message: String) : Exception(message)

/**
 * The phone's read client for the bss-iptv live edge.
 *
 * Contract mirrored from `bsc/routes/iptv.js`:
 *   GET /iptv/groups    → {groups:[{id, name, rawName, parentId, channelCount, epgChannelCount}]}
 *   GET /iptv/channels  → {count, limited, followedGroups, selectionStale, unscoped, channels:[{streamId, name, rawName, categoryId, icon, epgChannelId, hasEpg, tvArchive, tvArchiveDays}]}
 *
 * AUTH: the phone reads the edge DIRECTLY with the token it already holds for the
 * companion. Both services share `lib/session.js` and the same Redis, so
 * `bs_ses_*` validates on the edge unchanged — there is no proxy route. (The one
 * credential that travels in a URL elsewhere, the session token on playback
 * URIs, is not used here: these are ordinary header-authenticated REST calls.)
 *
 * WHY CHANNELS ARE GROUPED CLIENT-SIDE: `/iptv/channels` is scoped to the
 * FOLLOWED groups, so `?category=<id>` for an unfollowed group returns nothing
 * even though it exists in `/iptv/groups`. Browsing group-by-group would
 * therefore show empty lists that look like a bug. Instead this fetches the
 * whole tunable set once (~1 request) and joins it against `/groups` for names,
 * so the picker can only ever offer channels that will actually tune.
 *
 * No DI — the app's object-singleton pattern, mirroring [WatchPartyRepository].
 */
object IptvRepository {
    private val log = Logger.withTag("IptvRepository")
    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    private fun requireSession(): BoomioSession {
        val session = BoomioSessionRepository.session.value
            ?: throw IptvException("Link the companion hub first.")
        return session
    }

    private fun requireConfigured() {
        if (!BoomioConfig.iptvEnabled()) {
            throw IptvException("Live TV is not configured on this phone.")
        }
    }

    /** Every group on the panel, for naming the ones the household can tune. */
    suspend fun loadGroups(): List<IptvGroup> {
        requireConfigured()
        val session = requireSession()
        val response = http.get("${BoomioConfig.iptvRestBaseUrl}/iptv/groups") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            val parsed = runCatching { json.decodeFromString<IptvGroupsDto>(body) }.getOrNull()
            if (parsed != null) {
                return parsed.groups.map {
                    IptvGroup(id = it.id, name = it.name, channelCount = it.channelCount)
                }
            }
        }
        throw IptvException(describeError("load channel groups", body, response.status.value))
    }

    /** The household's tunable channels. */
    suspend fun loadChannels(limit: Int = 1000): IptvChannelsPage {
        requireConfigured()
        val session = requireSession()
        val response = http.get("${BoomioConfig.iptvRestBaseUrl}/iptv/channels?limit=$limit") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            val parsed = runCatching { json.decodeFromString<IptvChannelsPageDto>(body) }.getOrNull()
            if (parsed != null) {
                return IptvChannelsPage(
                    channels = parsed.channels.map {
                        IptvChannel(
                            streamId = it.streamId,
                            name = it.name,
                            categoryId = it.categoryId,
                            icon = it.icon,
                            hasEpg = it.hasEpg,
                        )
                    },
                    unscoped = parsed.unscoped,
                    followedGroups = parsed.followedGroups,
                )
            }
        }
        throw IptvException(describeError("load channels", body, response.status.value))
    }

    /**
     * Groups that actually contain a tunable channel, in the order the panel
     * lists them. Groups the household does not follow are dropped rather than
     * shown empty — an empty group in the picker is indistinguishable from a
     * broken one.
     */
    suspend fun loadTunableGroups(): List<IptvGroup> {
        val groups = loadGroups()
        val page = loadChannels()
        val present = page.channels.map { it.categoryId }.toSet()
        return groups.filter { it.id in present }
    }

    private fun describeError(action: String, body: String, status: Int): String {
        val error = runCatching {
            json.parseToJsonElement(body).let { el ->
                (el as? kotlinx.serialization.json.JsonObject)?.get("error")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }
        }.getOrNull()
        val message = runCatching {
            json.parseToJsonElement(body).let { el ->
                (el as? kotlinx.serialization.json.JsonObject)?.get("message")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            }
        }.getOrNull()
        log.w { "IPTV $action failed (HTTP $status): ${error ?: message ?: body.take(200)}" }
        val detail = message ?: error
        return if (detail.isNullOrBlank()) {
            "Could not $action (HTTP $status)."
        } else {
            "Could not $action: $detail"
        }
    }
}

// ── Wire DTOs ────────────────────────────────────────────────────────────────

@Serializable
private data class IptvGroupDto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("channelCount") val channelCount: Int = 0,
)

@Serializable
private data class IptvGroupsDto(
    @SerialName("groups") val groups: List<IptvGroupDto> = emptyList(),
)

@Serializable
private data class IptvChannelDto(
    @SerialName("streamId") val streamId: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("categoryId") val categoryId: String = "",
    @SerialName("icon") val icon: String? = null,
    @SerialName("hasEpg") val hasEpg: Boolean = false,
)

@Serializable
private data class IptvChannelsPageDto(
    @SerialName("unscoped") val unscoped: Boolean = false,
    @SerialName("followedGroups") val followedGroups: Int = 0,
    @SerialName("channels") val channels: List<IptvChannelDto> = emptyList(),
)
