package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import com.nuvio.app.features.boomio.CompanionMusicAnswer
import com.nuvio.app.features.boomio.CompanionMusicFailure
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import io.github.aivanyuk.airkast.AirkastSession
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
 * How often the receiver's position is written to watch progress while casting.
 * Cheaper than the player's own 60 s tick because the receiver is the only
 * record of where the viewer is: if this process is killed, the last write is
 * all the next launch has to resume from.
 */
private const val PROGRESS_PERSIST_MILLIS = 15_000L

/** Polls in a row that must fail before the cast is treated as ended by the receiver. */
private const val SESSION_LOST_POLLS = 20

/**
 * How far from the resume point the receiver may report before another seek is
 * sent. Generous: a receiver rounds and re-buffers around a seek, and a second
 * seek for the last few seconds would only cause a visible stutter.
 */
private const val RESUME_TOLERANCE_MS = 45_000L

/**
 * Seeks spent chasing the resume point before the viewer's own position wins.
 * Bounded so a receiver that never honours a seek cannot fight someone who has
 * already moved somewhere else.
 */
private const val MAX_RESUME_SEEKS = 8

/**
 * How long to leave a resume seek alone before sending another.
 *
 * ⚠️ A seek is not free on the server: bsc has to restart its worker at the new
 * offset and produce the first segments from there, measured at 3-5 s for a
 * cold burst. Re-seeking on every poll while that happens restarts the burst
 * over and over, and a receiver whose buffer is still filling answers by
 * dropping the item (measured 2026-11-09 against a Roku over AirPlay: three
 * seeks in 13 s, audio segments 4.6-5 s each, `error` at 18 s).
 */
private const val RESUME_SEEK_INTERVAL_MS = 12_000L

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

    /** The watch-progress row this cast writes its position to, if the caller had one. */
    @Volatile
    private var progressSession: WatchProgressPlaybackSession? = null

    @Volatile
    private var lastProgressPersistMs = 0L

    /** Where the viewer was when this cast started; zero once the receiver is there. */
    @Volatile
    private var resumeTargetMs = 0L

    private var resumeSeeksLeft = 0

    /** When the last resume seek was sent, so a recovering burst is not restarted. */
    private var lastResumeSeekAtMs = 0L

    actual fun watch(
        device: CastDevice,
        title: String,
        streamUrl: String,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        startPositionMs: Long,
        playbackSession: WatchProgressPlaybackSession?,
    ): Boolean {
        progressSession = playbackSession
        lastProgressPersistMs = 0L
        if (device.protocol == CastProtocol.ROKU) {
            startForegroundHolder(device, title)
            return watchRoku(device, title, streamUrl, imdbId, season, episode)
        }
        val session = AirPlay.session()
        if (session == null) {
            remoteLog.w { "watch: no session to follow" }
            _state.value = null
            return false
        }
        startForegroundHolder(device, title)
        // A previous cast may have been to a Roku; this one is not, and the
        // keypress branch must not answer for a device it no longer follows.
        roku = null
        context = WatchContext(streamUrl, imdbId, season, episode)
        pollJob?.cancel()
        _state.value = CastRemoteState(
            deviceName = device.name,
            title = title,
            capabilities = RemoteCapabilities.AirPlay,
        )
        // A cast of something already part-watched starts where the viewer
        // left off. The receiver has the item but may still be loading it, so
        // this retries for a few seconds instead of racing the load — and the
        // poll loop below keeps re-seeking until the reported position is
        // actually there, because a receiver can accept a seek and drop it.
        resumeTargetMs = startPositionMs
        resumeSeeksLeft = MAX_RESUME_SEEKS
        lastResumeSeekAtMs = 0L
        if (startPositionMs > 0L) {
            scope.launch { seekToStart(startPositionMs) }
        }
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
            var misses = 0
            while (isActive) {
                val info = runCatching { session.playbackInfo() }.getOrNull()
                val tracks = runCatching { session.tracks() }.getOrNull()
                if (info != null) {
                    misses = 0
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
                    persistProgress(syncRemote = false)
                    reconcileResume(session)
                } else if (++misses >= SESSION_LOST_POLLS) {
                    // The receiver ended the item: stopped on its own remote, the
                    // film finished, or the connection dropped. Whatever the
                    // reason, there is nothing left to control — record where it
                    // stopped and drop the cast card, or the phone would offer
                    // controls for a screen that went black.
                    remoteLog.w { "the receiver stopped reporting; ending the cast" }
                    persistProgress(syncRemote = true)
                    // ⚠️ Do NOT send `stop`/`close` here: if this is a Wi-Fi
                    // hiccup rather than a finished item, closing the session is
                    // exactly what would end a film that is still playing. Drop
                    // our reference and let the receiver keep its own state.
                    AirPlay.release()
                    CastAndroidContext.context?.let { CastPlaybackForeground.stop(it) }
                    stopWatching()
                    return@launch
                }
                delay(POLL_MILLIS)
            }
        }
        return true
    }

    /**
     * Holds the process at foreground priority for the length of the cast.
     *
     * ⛔ The sender is a paired socket held by this process. Without a
     * foreground service the OS freezes the app when the viewer switches away,
     * the socket stops being serviced and the receiver ends playback (measured
     * 2026-11-03). Started only once the cast is known to be followable, so a
     * cast that never starts cannot leave an ongoing notification behind.
     */
    private fun startForegroundHolder(device: CastDevice, title: String) {
        CastAndroidContext.context?.let { ctx ->
            CastPlaybackForeground.start(ctx, device.name, title)
        }
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
        // The Roku branch shares the cast state and the foreground service set
        // up by `watch`; `startPosition` and progress are AirPlay-only today
        // (ECP has no seek-to-position and reports its player inconsistently).
        context = WatchContext(streamUrl, imdbId, season, episode)
        roku = device
        pollJob?.cancel()
        _state.value = CastRemoteState(
            deviceName = device.name,
            title = title,
            playing = true,
            canSeek = false,
            capabilities = RemoteCapabilities.RokuEcp,
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
        resumeTargetMs = 0L
        lastResumeSeekAtMs = 0L
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
        // Record where playback ended before anything clears the state: this is
        // the write that makes "play it again" resume instead of restart.
        persistProgress(syncRemote = true)
        roku?.let { device ->
            val accepted = runCatching { RokuEcp.keypress(device, "Home") }.getOrDefault(false)
            if (accepted) {
                stopWatching()
                CastAndroidContext.context?.let { CastPlaybackForeground.stop(it) }
            }
            return if (accepted) null else "The Roku did not take \"stop\""
        }
        val session = AirPlay.session() ?: return NO_SESSION
        val error = command("stop") { session.stop() }
        if (error == null) {
            AirPlay.release()
            stopWatching()
            CastAndroidContext.context?.let { CastPlaybackForeground.stop(it) }
        }
        return error
    }

    /**
     * Puts the receiver where the viewer left off.
     *
     * The item was accepted by [AirPlay.play] before this runs, but the receiver
     * may still be opening it, so a refused seek is retried a few times. A seek
     * that cannot land is not fatal: the cast plays from the start, which is
     * exactly what happened before this existed.
     */
    private suspend fun seekToStart(positionMs: Long) {
        repeat(3) { attempt ->
            val error = seekTo(positionMs)
            if (error == null) {
                remoteLog.d { "resume: the receiver is at ${positionMs}ms" }
                return
            }
            remoteLog.w { "resume seek ${attempt + 1} failed: $error" }
            delay(1_500)
        }
    }

    /**
     * Keeps pushing the receiver towards the resume point until it is there.
     *
     * A receiver still opening the item accepts a seek and drops it, and the
     * result is indistinguishable from no seek at all: the film starts at zero.
     * So the target is held, and re-sent from the poll loop while the reported
     * position disagrees with it, bounded by [MAX_RESUME_SEEKS] so a viewer who
     * has since seeked somewhere else is never fought.
     */
    private suspend fun reconcileResume(session: AirkastSession) {
        val target = resumeTargetMs
        if (target <= 0L) return
        val state = _state.value ?: return
        // Still opening the item: it takes the seek and drops it, and every extra
        // seek restarts the server's burst underneath the first one.
        if (state.loading) return
        val at = state.positionMs
        if (at <= 0L) return
        // At or past the mark is where the viewer asked to be. Playing on from
        // there is not drift, and seeking back would restart the burst for
        // nothing — the old test compared the distance in both directions.
        if (at >= target - RESUME_TOLERANCE_MS) {
            resumeTargetMs = 0L
            remoteLog.d { "resume: the receiver is at ${at}ms" }
            return
        }
        if (resumeSeeksLeft <= 0) {
            resumeTargetMs = 0L
            remoteLog.w { "resume: giving up at ${at}ms, wanted ${target}ms" }
            return
        }
        val now = System.currentTimeMillis()
        if (lastResumeSeekAtMs != 0L && now - lastResumeSeekAtMs < RESUME_SEEK_INTERVAL_MS) return
        lastResumeSeekAtMs = now
        resumeSeeksLeft--
        runCatching { session.seek(target.milliseconds) }.onFailure { error ->
            remoteLog.w { "resume seek failed: ${error.message}" }
        }
    }

    /**
     * Writes the receiver's current position to watch progress — the same row
     * the local player writes, so Continue Watching and a later cast both see it.
     *
     * A receiver that has not reported a duration or has not started yet is
     * skipped: "0 of 0" would overwrite a real resume point with nothing.
     */
    private fun persistProgress(syncRemote: Boolean) {
        val session = progressSession ?: return
        val current = _state.value ?: return
        if (current.durationMs <= 0L || current.positionMs <= 0L) return
        val now = System.currentTimeMillis()
        if (!syncRemote && now - lastProgressPersistMs < PROGRESS_PERSIST_MILLIS) return
        lastProgressPersistMs = now
        runCatching {
            WatchProgressRepository.upsertPlaybackProgress(
                session = session,
                snapshot = PlayerPlaybackSnapshot(
                    isLoading = current.loading,
                    isPlaying = current.playing,
                    isEnded = false,
                    durationMs = current.durationMs,
                    positionMs = current.positionMs,
                ),
                syncRemote = syncRemote,
            )
        }.onFailure { error ->
            remoteLog.w { "progress write failed: ${error.message}" }
        }
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

