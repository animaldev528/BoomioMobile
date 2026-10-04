package com.nuvio.app.features.boomio

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The phone's music button in companion mode: ask the paired TV what is playing
 * without putting anything on the TV's picture.
 *
 * The TV's own note button opens an overlay over the video. That is the right
 * shape when the viewer is holding the TV remote and looking at the TV — and the
 * wrong one when they are holding the phone. This is the same question with the
 * answer rendered where the press happened.
 *
 * It is not a phone-side reimplementation: the press still goes to the TV, and
 * the TV still asks bsc. The audio-track ordinal that decides which stream the
 * server listens to lives only on the TV, and a phone-side call would quietly
 * identify the file's default track instead. See
 * [CompanionBridge.requestMusicIdentify].
 */

/** One identified song, as the TV forwarded it from bsc. */
data class CompanionMusicMatch(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val isrc: String? = null,
    val provider: String? = null,
    val providerTrackId: String? = null,
    /** Where in the title the cue sits, per the server — not where playback is now. */
    val positionMs: Long? = null,
)

/** Why there is no song to show. */
enum class CompanionMusicFailure {
    /** The TV is not playing anything, so there is nothing to listen to. */
    NoPlayer,

    /** This phone has no session, or the companion socket is not open. */
    NoLink,

    /** The press reached the TV and the TV never answered. */
    Timeout,

    /** The session carries no user, so there is no library to write into. */
    NotLinked,

    /** The call itself failed: offline, timeout, or a 5xx. */
    Network,
}

/**
 * What the TV said. Mirrors `bsc`'s identify contract, which the TV forwards
 * verbatim — including `track`, whose `reason` decides the mismatch warning.
 */
sealed interface CompanionMusicAnswer {
    data class Found(
        val match: CompanionMusicMatch,
        /** True when this came from the shared cue index — no provider was called. */
        val fromIndex: Boolean,
        /** How many songs this title has in the index, this one included. */
        val cuesInEpisode: Int,
        /**
         * `unresolved` when the TV told the server which audio track was playing
         * and the server could not honour it, so it listened to something else.
         * The identification may then describe audio the viewer is not hearing —
         * worth saying, and invisible without this field.
         */
        val trackMismatch: Boolean,
    ) : CompanionMusicAnswer

    /** The server listened and recognised nothing. A real answer, not a fault. */
    data class NoMatch(val status: String) : CompanionMusicAnswer

    /** The per-device daily cap. Try again tomorrow; nothing is broken. */
    data object RateLimited : CompanionMusicAnswer

    /** The question never reached the server, or never got back. */
    data class Unavailable(val reason: CompanionMusicFailure) : CompanionMusicAnswer
}

/** How keeping a track ended. */
sealed interface CompanionMusicSaveResult {
    /** [duplicate] is true when it was already in the library. */
    data class Stored(val duplicate: Boolean) : CompanionMusicSaveResult

    data class Failed(val reason: CompanionMusicFailure) : CompanionMusicSaveResult
}

/** What the phone's music card is showing. */
sealed interface CompanionMusicState {
    /** Nothing asked yet. */
    data object Idle : CompanionMusicState

    /** The press is in flight. Genuinely slow — the server fetches and decodes audio. */
    data object Listening : CompanionMusicState

    data class Found(
        val match: CompanionMusicMatch,
        val fromIndex: Boolean,
        val cuesInEpisode: Int,
        val trackMismatch: Boolean,
    ) : CompanionMusicState

    data class NoMatch(val status: String) : CompanionMusicState

    data object RateLimited : CompanionMusicState

    data class Unavailable(val reason: CompanionMusicFailure) : CompanionMusicState
}

/** Keeping the found song, tracked separately so a failed save can't wipe the answer. */
sealed interface CompanionMusicSaveState {
    data object Idle : CompanionMusicSaveState

    data object Saving : CompanionMusicSaveState

    data object Saved : CompanionMusicSaveState

    data object AlreadySaved : CompanionMusicSaveState

    data class Failed(val reason: CompanionMusicFailure) : CompanionMusicSaveState
}

/**
 * Drives the music card. A singleton for the same reason
 * [PrivateListeningSession] is one: the answer has to survive the card
 * recomposing, and the press outlives a scroll.
 */
object CompanionMusicController {
    /**
     * How long to wait for the TV's answer.
     *
     * The work itself is genuinely slow — 6.5–9.5 s measured on an 88 GB remux,
     * because the server fetches and decodes a window of the real audio — so
     * this is a backstop for a companion link that died mid-press, not a budget
     * for the identification. Generous on purpose: reporting "no answer" over a
     * slow-but-working call would be a lie the viewer cannot check.
     */
    private const val IDENTIFY_TIMEOUT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("CompanionMusic")

    private val _state = MutableStateFlow<CompanionMusicState>(CompanionMusicState.Idle)
    val state: StateFlow<CompanionMusicState> = _state.asStateFlow()

    private val _saveState = MutableStateFlow<CompanionMusicSaveState>(CompanionMusicSaveState.Idle)
    val saveState: StateFlow<CompanionMusicSaveState> = _saveState.asStateFlow()

    /** Ask the paired TV what it is playing. A second press while one is in flight is ignored. */
    fun identify() {
        if (_state.value is CompanionMusicState.Listening) return

        val waiter = CompanionBridge.requestMusicIdentify()
        if (waiter == null) {
            // No socket. Said plainly rather than left to a 30 s wait: the two
            // are indistinguishable on screen and only one of them is fixable.
            _state.value = CompanionMusicState.Unavailable(CompanionMusicFailure.NoLink)
            return
        }

        // A fresh question means a fresh answer, so a save state left over from
        // the previous one is cleared — otherwise a new song would open already
        // claiming to be in the library.
        _saveState.value = CompanionMusicSaveState.Idle
        _state.value = CompanionMusicState.Listening

        scope.launch {
            val answer = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) { waiter.await() }
            _state.value = when (answer) {
                null -> {
                    log.w { "music_identify: no answer in ${IDENTIFY_TIMEOUT_MS}ms" }
                    CompanionMusicState.Unavailable(CompanionMusicFailure.Timeout)
                }
                is CompanionMusicAnswer.Found -> CompanionMusicState.Found(
                    match = answer.match,
                    fromIndex = answer.fromIndex,
                    cuesInEpisode = answer.cuesInEpisode,
                    trackMismatch = answer.trackMismatch,
                )
                is CompanionMusicAnswer.NoMatch -> CompanionMusicState.NoMatch(answer.status)
                CompanionMusicAnswer.RateLimited -> CompanionMusicState.RateLimited
                is CompanionMusicAnswer.Unavailable ->
                    CompanionMusicState.Unavailable(answer.reason)
            }
        }
    }

    /**
     * Keep the song that was just identified.
     *
     * Sends back what the identify answer already returned rather than asking
     * again: the track is in hand, and a second identification would spend the
     * daily cap to arrive at the same row.
     */
    fun save() {
        val found = _state.value as? CompanionMusicState.Found ?: return
        if (_saveState.value is CompanionMusicSaveState.Saving ||
            _saveState.value is CompanionMusicSaveState.Saved ||
            _saveState.value is CompanionMusicSaveState.AlreadySaved
        ) {
            return
        }

        _saveState.value = CompanionMusicSaveState.Saving
        scope.launch {
            _saveState.value = when (val result = CompanionBridge.saveMusicToLibrary(found.match)) {
                is CompanionMusicSaveResult.Stored ->
                    if (result.duplicate) CompanionMusicSaveState.AlreadySaved
                    else CompanionMusicSaveState.Saved

                is CompanionMusicSaveResult.Failed -> CompanionMusicSaveState.Failed(result.reason)
            }
        }
    }

    /** Close the card. */
    fun clear() {
        _state.value = CompanionMusicState.Idle
        _saveState.value = CompanionMusicSaveState.Idle
    }
}

private val musicJson = Json { ignoreUnknownKeys = true }

/**
 * Parse the TV's `music_identify_result` frame.
 *
 * Shape (see `BoomioCompanionManager.sendMusicIdentifyResult` on the TV side):
 * `{ type, answered, result? }` when the server answered, or
 * `{ type, answered: false, reason, detail? }` when the TV could not ask.
 *
 * An unrecognised `status` degrades to [CompanionMusicAnswer.NoMatch] carrying
 * the server's own word, matching the TV's overlay: a status added server-side
 * later reads as "nothing here" rather than as a made-up network fault.
 */
internal fun parseMusicIdentifyAnswer(msg: JsonObject): CompanionMusicAnswer {
    val answered = msg["answered"]?.jsonPrimitive?.contentOrNull == "true"
    if (!answered) {
        val reason = msg["reason"]?.jsonPrimitive?.contentOrNull
        return CompanionMusicAnswer.Unavailable(
            when (reason) {
                "not_playing" -> CompanionMusicFailure.NoPlayer
                "not_linked" -> CompanionMusicFailure.NotLinked
                "network" -> CompanionMusicFailure.Network
                // `not_paired` / `not_configured` both mean this phone has no
                // usable link to a TV that can answer.
                else -> CompanionMusicFailure.NoLink
            },
        )
    }

    val result = msg["result"] as? JsonObject ?: return CompanionMusicAnswer.Unavailable(
        CompanionMusicFailure.Network,
    )
    val dto = runCatching { musicJson.decodeFromJsonElement(MusicIdentifyWire.serializer(), result) }
        .getOrElse { return CompanionMusicAnswer.Unavailable(CompanionMusicFailure.Network) }

    return when (dto.status) {
        "ok" -> {
            val match = dto.match?.toModel()
            if (match == null || match.title.isBlank()) {
                // The contract says `match` is set when `status` is `ok`. A
                // violation of it is still something the viewer can be told
                // honestly, rather than an empty card.
                CompanionMusicAnswer.NoMatch("no_match")
            } else {
                CompanionMusicAnswer.Found(
                    match = match,
                    fromIndex = dto.source == "cache",
                    cuesInEpisode = dto.cuesInEpisode ?: 0,
                    trackMismatch = dto.track?.reason == "unresolved",
                )
            }
        }
        "rate_limited" -> CompanionMusicAnswer.RateLimited
        else -> CompanionMusicAnswer.NoMatch(dto.status ?: "unknown")
    }
}

// ── Wire shapes ──────────────────────────────────────────────────────────────
// The server's own payload, re-emitted by the TV with Moshi. `cues_in_episode`
// is the one snake_case key in it; every other field is camelCase.

@Serializable
internal data class MusicIdentifyWire(
    val status: String? = null,
    val match: MusicMatchWire? = null,
    val source: String? = null,
    @SerialName("cues_in_episode") val cuesInEpisode: Int? = null,
    val error: String? = null,
    val track: MusicTrackSelectionWire? = null,
)

@Serializable
internal data class MusicMatchWire(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val isrc: String? = null,
    val artworkUrl: String? = null,
    val provider: String? = null,
    val providerTrackId: String? = null,
    val positionMs: Long? = null,
)

@Serializable
internal data class MusicTrackSelectionWire(
    val reported: Int? = null,
    val chosen: Int? = null,
    val reason: String? = null,
)

/** The library save body, as `bsc/routes/music.js` reads it. */
@Serializable
internal data class MusicLibrarySaveRequest(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val isrc: String? = null,
    val artworkUrl: String? = null,
    val provider: String? = null,
    val providerTrackId: String? = null,
)

private fun MusicMatchWire.toModel() = CompanionMusicMatch(
    title = title.orEmpty(),
    artist = artist,
    album = album,
    artworkUrl = artworkUrl,
    isrc = isrc,
    provider = provider,
    providerTrackId = providerTrackId,
    positionMs = positionMs,
)

internal fun CompanionMusicMatch.toSaveRequest() = MusicLibrarySaveRequest(
    title = title,
    artist = artist,
    album = album,
    isrc = isrc,
    artworkUrl = artworkUrl,
    provider = provider,
    providerTrackId = providerTrackId,
)
