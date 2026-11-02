package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import com.nuvio.app.features.boomio.CompanionMusicAnswer
import com.nuvio.app.features.boomio.CompanionMusicFailure
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Rendition
import io.github.aivanyuk.airkast.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

private val remoteLog = Logger.withTag("CastRemote")
private const val POLL_MILLIS = 1_000L

/**
 * The remote for an AirPlay 2 session, backed by airkast.
 *
 * The receiver owns playback: it buffers, it may seek on its own (the Apple
 * TV's remote is right there), and it decides which rendition is audible. So the
 * `renditions()` list is read once per cast and the position/state are polled —
 * one `playbackInfo` and one `tracks` a second, which is what the protocol costs.
 *
 * A receiver that reports no seekable range is live; the screen turns the
 * scrubber into a progress line rather than pretending it can seek.
 */
internal actual object CastRemote {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<CastRemoteState?>(null)
    actual val state: StateFlow<CastRemoteState?> = _state.asStateFlow()

    private var pollJob: Job? = null

    @Volatile
    private var context: WatchContext? = null

    private data class WatchContext(
        val streamUrl: String,
        val imdbId: String?,
        val season: Int?,
        val episode: Int?,
    )

    actual fun watch(
        device: CastDevice,
        title: String,
        streamUrl: String,
        imdbId: String?,
        season: Int?,
        episode: Int?,
    ): Boolean {
        val session = AirPlay.session()
        if (session == null) {
            remoteLog.w { "watch: no session to follow" }
            _state.value = null
            return false
        }
        context = WatchContext(streamUrl, imdbId, season, episode)
        pollJob?.cancel()
        _state.value = CastRemoteState(deviceName = device.name, title = title)
        pollJob = scope.launch {
            val renditions = runCatching { session.renditions() }.getOrDefault(emptyList())
            val audio = renditions.filter { it.kind == TrackKind.Audio }
                .mapIndexed { index, rendition -> CastRemoteTrack(rendition.id, rendition.label(index, "Audio")) }
            val subtitles = renditions.filter { it.kind == TrackKind.Subtitles }
                .mapIndexed { index, rendition -> CastRemoteTrack(rendition.id, rendition.label(index, "Subtitles")) }
            _state.update {
                it?.copy(
                    audio = audio,
                    subtitles = subtitles,
                    canPickTracks = audio.size > 1 || subtitles.isNotEmpty(),
                )
            }
            while (isActive) {
                val info = runCatching { session.playbackInfo() }.getOrNull()
                val tracks = runCatching { session.tracks() }.getOrNull()
                if (info != null) {
                    _state.update { current ->
                        current?.copy(
                            playing = info.state == PlaybackState.Playing,
                            loading = info.state == PlaybackState.Loading,
                            positionMs = info.position?.toLong(DurationUnit.MILLISECONDS) ?: current.positionMs,
                            durationMs = info.duration?.toLong(DurationUnit.MILLISECONDS) ?: current.durationMs,
                            canSeek = info.seekable.isNotEmpty() || info.duration != null,
                            audioId = tracks?.firstOrNull { it.kind == TrackKind.Audio }?.id ?: current.audioId,
                            subtitleId = if (tracks != null) {
                                tracks.firstOrNull { it.kind == TrackKind.Subtitles }?.id
                            } else {
                                current.subtitleId
                            },
                        )
                    }
                }
                delay(POLL_MILLIS)
            }
        }
        return true
    }

    actual fun stopWatching() {
        pollJob?.cancel()
        pollJob = null
        context = null
        _state.value = null
    }

    actual suspend fun togglePlayPause(): String? {
        val session = AirPlay.session() ?: return NO_SESSION
        val playing = _state.value?.playing == true
        return command("${if (playing) "pause" else "play"}") {
            if (playing) session.pause() else session.play()
        }
    }

    actual suspend fun seekTo(positionMs: Long): String? {
        val session = AirPlay.session() ?: return NO_SESSION
        return command("seek") { session.seek(positionMs.coerceAtLeast(0).milliseconds) }
    }

    actual suspend fun selectAudio(id: Long): String? {
        val session = AirPlay.session() ?: return NO_SESSION
        return command("audio $id") { session.selectTrack(TrackKind.Audio, id) }
    }

    actual suspend fun setSubtitles(id: Long?): String? {
        val session = AirPlay.session() ?: return NO_SESSION
        return command(if (id == null) "subtitles off" else "subtitles $id") {
            session.selectTrack(TrackKind.Subtitles, id)
        }
    }

    actual suspend fun stopPlayback(): String? {
        val session = AirPlay.session() ?: return NO_SESSION
        val error = command("stop") { session.stop() }
        if (error == null) {
            AirPlay.release()
            stopWatching()
        }
        return error
    }

    actual suspend fun identifySong(): CompanionMusicAnswer {
        val watched = context
            ?: return CompanionMusicAnswer.Unavailable(CompanionMusicFailure.NoPlayer)
        val current = _state.value
            ?: return CompanionMusicAnswer.Unavailable(CompanionMusicFailure.NoPlayer)
        val ordinal = current.audio.indexOfFirst { it.id == current.audioId }
            .takeIf { it >= 0 }
        return identifyCastSong(
            streamUrl = watched.streamUrl,
            imdbId = watched.imdbId,
            season = watched.season,
            episode = watched.episode,
            positionMs = current.positionMs,
            audioTrack = ordinal,
        )
    }

    private inline fun command(name: String, block: () -> Unit): String? =
        try {
            block()
            null
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            remoteLog.w { "$name failed: ${error.message}" }
            "The TV did not take \"$name\": ${error.message ?: error::class.simpleName}"
        }

    private const val NO_SESSION = "The cast has ended"
}

/** The name to show for a rendition: its own, its language, or its position. */
private fun Rendition.label(index: Int, kind: String): String =
    name?.takeIf { it.isNotBlank() }
        ?: language?.takeIf { it.isNotBlank() }
        ?: "$kind ${index + 1}"

