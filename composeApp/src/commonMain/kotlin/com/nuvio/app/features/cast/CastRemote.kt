package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.features.boomio.CompanionMusicAnswer
import com.nuvio.app.features.boomio.CompanionMusicFailure
import com.nuvio.app.features.boomio.companionRestBaseUrl
import com.nuvio.app.features.boomio.createBoomioHttpClient
import com.nuvio.app.features.boomio.parseMusicIdentifyAnswer
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** One audio or subtitle rendition the receiver offers, as the phone lists it. */
internal data class CastRemoteTrack(val id: Long, val label: String)

/**
 * What the phone shows while a renderer is playing.
 *
 * Everything here is reported by the receiver — none of it is inferred from what
 * the phone handed it. A receiver that buffers, seeks on its own, or switches
 * tracks changes these values without the phone doing anything, which is why the
 * screen polls instead of trusting the last command.
 */
internal data class CastRemoteState(
    val deviceName: String,
    val title: String,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val audio: List<CastRemoteTrack> = emptyList(),
    val audioId: Long? = null,
    val subtitles: List<CastRemoteTrack> = emptyList(),
    /** Null turns subtitles off. */
    val subtitleId: Long? = null,
    /** False when the receiver does not report a seekable range (a live stream). */
    val canSeek: Boolean = true,
    /** False for a protocol or item that cannot switch tracks at all. */
    val canPickTracks: Boolean = false,
) {
    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * The phone-side remote for a cast that is already playing.
 *
 * This is the same job the TV companion screen does, for a renderer that speaks
 * a cast protocol rather than the Boomio companion socket: the renderer fetches
 * the stream itself, so the phone cannot read its position or change its tracks
 * locally — it has to ask, and this holds the answers.
 *
 * ⚠️ **Private listening is not here, deliberately.** The companion gets it by
 * forking the TV's audio to the phone (`audio_fork`). A renderer playing a URL
 * has no audio to fork: the sound never passes through this phone, and there is
 * nothing to intercept short of proxying the whole stream.
 *
 * Everything is best-effort per protocol. A renderer that cannot report
 * renditions leaves [CastRemoteState.canPickTracks] false and the screen hides
 * the pickers rather than showing controls that do nothing.
 */
internal expect object CastRemote {
    /** Null when nothing is casting; the screen is only meaningful while this is set. */
    val state: StateFlow<CastRemoteState?>

    /**
     * Starts following the session [playOnRenderer] just opened — call it after
     * a successful cast, with what that cast was handed, so song identification
     * can name the title and the exact stream.
     *
     * @return false when this protocol has no remote (DLNA, Chromecast today):
     *   the cast is fine, there is just nothing to control from here, and the
     *   caller must not open a screen that cannot do anything.
     */
    /**
     * @param startPositionMs where the viewer was when they last watched this
     *   title — the receiver is seeked there once its item is loaded, so a cast
     *   resumes instead of restarting. Zero keeps the receiver at its own start.
     * @param playbackSession the latest watch-progress row: the receiver's
     *   position is written to it as playback advances, which is what makes the
     *   title come back at the right place after the app or the cast is stopped.
     */
    fun watch(
        device: CastDevice,
        title: String,
        streamUrl: String,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        startPositionMs: Long = 0L,
        playbackSession: WatchProgressPlaybackSession? = null,
    ): Boolean

    /** Stops following. Does not stop the renderer. */
    fun stopWatching()

    /** @return a message to show, or null when the command was taken. */
    suspend fun togglePlayPause(): String?

    suspend fun seekTo(positionMs: Long): String?

    suspend fun selectAudio(id: Long): String?

    /** Null turns subtitles off. */
    suspend fun setSubtitles(id: Long?): String?

    /** Ends playback on the renderer and clears [state]. */
    suspend fun stopPlayback(): String?

    /**
     * Asks the server what song is playing at the receiver's current position.
     *
     * The audio track is reported with it: the server listens to the track the
     * viewer is hearing, and a phone that sent no ordinal would get the file's
     * default — the commentary mismatch the companion's own button exists to
     * avoid.
     */
    suspend fun identifySong(): CompanionMusicAnswer
}

private val identifyLog = Logger.withTag("CastRemote")
private val identifyJson = Json { ignoreUnknownKeys = true }

/** One client for every press: building a Ktor client per song would leak its pool. */
private val identifyHttp by lazy { createBoomioHttpClient() }

/**
 * `POST /api/music/identify` from the phone, for a stream a renderer is playing.
 *
 * The companion asks the TV instead, because only the TV knows which audio track
 * is audible. Here the phone does know: it chose the stream and it polls the
 * receiver for the selected rendition, so both the URL and the ordinal come from
 * the same place that handed the item over.
 *
 * The server answers in the shape the TV forwards, so [parseMusicIdentifyAnswer]
 * reads it unchanged — wrapped in the one field that parser requires.
 */
internal suspend fun identifyCastSong(
    streamUrl: String,
    imdbId: String?,
    season: Int?,
    episode: Int?,
    positionMs: Long,
    audioTrack: Int?,
): CompanionMusicAnswer {
    val token = BoomioSessionRepository.bearerToken()
        ?: return CompanionMusicAnswer.Unavailable(CompanionMusicFailure.NotLinked)
    val body = buildJsonObject {
        put("streamUrl", streamUrl)
        imdbId?.let { put("imdbId", it) }
        season?.let { put("season", it) }
        episode?.let { put("episode", it) }
        put("positionMs", positionMs)
        audioTrack?.let { put("audioTrack", it) }
    }
    return try {
        val response = identifyHttp.post("${BoomioConfig.companionRestBaseUrl}/api/music/identify") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (!response.status.isSuccess()) {
            return when (response.status.value) {
                429 -> CompanionMusicAnswer.RateLimited
                else -> {
                    identifyLog.w { "identify: HTTP ${response.status.value}" }
                    CompanionMusicAnswer.Unavailable(CompanionMusicFailure.Network)
                }
            }
        }
        val result = identifyJson.parseToJsonElement(response.bodyAsText()).jsonObject
        parseMusicIdentifyAnswer(
            buildJsonObject {
                put("answered", "true")
                put("result", result)
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        identifyLog.w { "identify failed: ${error.message}" }
        CompanionMusicAnswer.Unavailable(CompanionMusicFailure.Network)
    }
}
