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

    /**
     * The Roku being followed, when the cast is ECP rather than AirPlay.
     *
     * A Roku has no session object to hold: every command is a separate HTTP POST
     * to `:8060`, so "the session" is just the address.
     */
    @Volatile
    private var roku: CastDevice? = null

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
        if (device.protocol == CastProtocol.ROKU) {
            return watchRoku(device, title, streamUrl, imdbId, season, episode)
        }
        val session = AirPlay.session()
        if (session == null) {
            remoteLog.w { "watch: no session to follow" }
            _state.value = null
            return false
        }
        // A previous cast may have been to a Roku; this one is not, and the
        // keypress branch must not answer for a device it no longer follows.
        roku = null
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

    /**
     * Follows a Roku.
     *
     * Roku's ECP reports a system media player only for channels that use it, and
     * Media Assistant plays through its own Video node — so most of the time
     * there is no position to read. The state then says what is true: playing
     * (the launch was confirmed by [RokuEcp.play]), no seekable range, and no
     * track pickers. The transport buttons still work, because they are keypresses.
     */
    private fun watchRoku(
        device: CastDevice,
        title: String,
        streamUrl: String,
        imdbId: String?,
        season: Int?,
        episode: Int?,
    ): Boolean {
        context = WatchContext(streamUrl, imdbId, season, episode)
        roku = device
        pollJob?.cancel()
        _state.value = CastRemoteState(
            deviceName = device.name,
            title = title,
            playing = true,
            canSeek = false,
        )
        pollJob = scope.launch {
            while (isActive) {
                val player = runCatching { RokuEcp.mediaPlayer(device) }.getOrNull()
                if (player != null) {
                    _state.update { current ->
                        current?.copy(
                            playing = player.state.equals("play", ignoreCase = true),
                            positionMs = player.positionMs,
                            durationMs = player.durationMs,
                            canSeek = false,
                        )
                    }
                }
                delay(ROKU_POLL_MILLIS)
            }
        }
        return true
    }

    actual fun stopWatching() {
        pollJob?.cancel()
        pollJob = null
        context = null
        roku = null
        _state.value = null
    }

    actual suspend fun togglePlayPause(): String? {
        roku?.let { device ->
            val accepted = runCatching { RokuEcp.keypress(device, "Play") }.getOrDefault(false)
            if (accepted) {
                // `Play` is a toggle on a Roku. Flip what we show optimistically;
                // the /query/media-player poll corrects it when the channel reports.
                _state.update { current -> current?.let { it.copy(playing = !it.playing) } }
                return null
            }
            return "The Roku did not take \"play\""
        }
        val session = AirPlay.session() ?: return NO_SESSION
        val playing = _state.value?.playing == true
        return command("${if (playing) "pause" else "play"}") {
            if (playing) session.pause() else session.play()
        }
    }

    actual suspend fun seekTo(positionMs: Long): String? {
        // ECP can send Rev/Fwd keypresses but has no "seek to position": the
        // channel decides how far one press jumps. Saying so beats a control that
        // silently does nothing. The screen hides the scrubber (canSeek=false).
        if (roku != null) return "A Roku seeks from its own remote, not from here"
        val session = AirPlay.session() ?: return NO_SESSION
        return command("seek") { session.seek(positionMs.coerceAtLeast(0).milliseconds) }
    }

    actual suspend fun selectAudio(id: Long): String? {
        if (roku != null) return NO_TRACKS
        val session = AirPlay.session() ?: return NO_SESSION
        return command("audio $id") { session.selectTrack(TrackKind.Audio, id) }
    }

    actual suspend fun setSubtitles(id: Long?): String? {
        if (roku != null) return NO_TRACKS
        val session = AirPlay.session() ?: return NO_SESSION
        return command(if (id == null) "subtitles off" else "subtitles $id") {
            session.selectTrack(TrackKind.Subtitles, id)
        }
    }

    actual suspend fun stopPlayback(): String? {
        roku?.let { device ->
            val accepted = runCatching { RokuEcp.keypress(device, "Home") }.getOrDefault(false)
            if (accepted) stopWatching()
            return if (accepted) null else "The Roku did not take \"stop\""
        }
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

    /** Roku's own player reports slowly and unreliably; one poll every two seconds. */
    private const val ROKU_POLL_MILLIS = 2_000L

    private const val NO_TRACKS = "A Roku does not switch tracks from this phone"
}

/** The name to show for a rendition: its own, its language, or its position. */
private fun Rendition.label(index: Int, kind: String): String =
    name?.takeIf { it.isNotBlank() }
        ?: language?.takeIf { it.isNotBlank() }
        ?: "$kind ${index + 1}"

