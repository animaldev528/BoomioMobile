package com.nuvio.app.features.cast

import com.nuvio.app.features.boomio.CompanionMusicAnswer
import com.nuvio.app.features.boomio.CompanionMusicFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * No remote on iOS: AirPlay there is the system route picker's job, not a
 * protocol this app speaks, so there is no session to poll or control. Discovery
 * already answers empty on iOS, so no cast reaches this.
 */
internal actual object CastRemote {

    private val empty = MutableStateFlow<CastRemoteState?>(null)

    actual val state: StateFlow<CastRemoteState?> = empty.asStateFlow()

    actual fun watch(
        device: CastDevice,
        title: String,
        streamUrl: String,
        imdbId: String?,
        season: Int?,
        episode: Int?,
    ): Boolean = false

    actual fun stopWatching() = Unit

    actual suspend fun togglePlayPause(): String? = NOT_AVAILABLE

    actual suspend fun seekTo(positionMs: Long): String? = NOT_AVAILABLE

    actual suspend fun selectAudio(id: Long): String? = NOT_AVAILABLE

    actual suspend fun setSubtitles(id: Long?): String? = NOT_AVAILABLE

    actual suspend fun stopPlayback(): String? = NOT_AVAILABLE

    actual suspend fun identifySong(): CompanionMusicAnswer =
        CompanionMusicAnswer.Unavailable(CompanionMusicFailure.NoLink)

    private const val NOT_AVAILABLE = "Remote control is not available on iOS"
}
