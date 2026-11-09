package com.nuvio.app.features.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.features.boomio.CompanionMusicAnswer
import com.nuvio.app.features.boomio.CompanionMusicFailure
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cast_remote_none
import org.jetbrains.compose.resources.stringResource

/**
 * The phone's controls for a cast that is already playing.
 *
 * It is the companion screen's counterpart for a renderer: same job, different
 * wire. The controls on it are chosen by [CastRemoteState.capabilities], so a
 * Roku gets transport and stop but never a scrubber (ECP has no seek-to-position),
 * and a receiver that reports no renditions gets no track pickers.
 *
 * ⚠️ No private listening: see [CastRemote]. The renderer holds the audio, and
 * the phone has none to fork.
 */
@Composable
internal fun CastRemoteScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by CastRemote.state.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }

    NuvioScreen(modifier = modifier) {
        item {
            NuvioScreenHeader(title = "Casting", onBack = onBack)
        }
        val current = state
        if (current == null) {
            item {
                Text(
                    text = "Nothing is casting. Start a cast, then come back here to control it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@NuvioScreen
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = current.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Playing on ${current.deviceName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            CastRemoteControls(
                state = current,
                error = error,
                onError = { error = it },
                onStopped = onBack,
            )
        }
    }
}

/**
 * The capability-gated remote body.
 *
 * Shared by the full-screen cast route and by the companion screen, which shows
 * it inline when a cast is live — so a cast and a Boomio companion are the same
 * kind of remote on the same surface, and a protocol only draws the controls it
 * can honour.
 */
@Composable
internal fun CastRemoteControls(
    state: CastRemoteState,
    error: String?,
    onError: (String?) -> Unit,
    onStopped: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val caps = state.capabilities
    val hasAnyControl = caps.transport || caps.seek || caps.stop || caps.volume ||
        caps.tracks || caps.musicIdentify

    Column(modifier = Modifier.fillMaxWidth()) {
        // A scrubber is only drawn where seeking is BOTH a protocol capability and
        // true of this item (a live stream has no seekable range). There is
        // deliberately no disabled slider: a thumb that cannot move reads as a
        // broken control, which is exactly what the Roku used to look like.
        if (caps.seek && state.canSeek) {
            Scrubber(
                state = state,
                onSeek = { positionMs -> scope.launch { onError(CastRemote.seekTo(positionMs)) } },
            )
        } else if (state.durationMs > 0L) {
            PositionLine(state = state)
        }

        if (caps.transport || caps.stop) {
            TransportRow(
                state = state,
                canToggle = caps.transport,
                canStop = caps.stop,
                onToggle = { scope.launch { onError(CastRemote.togglePlayPause()) } },
                onStop = {
                    scope.launch {
                        val message = CastRemote.stopPlayback()
                        onError(message)
                        if (message == null) onStopped()
                    }
                },
            )
        }

        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (!hasAnyControl) {
            Text(
                text = stringResource(Res.string.cast_remote_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        if (caps.musicIdentify) {
            MusicCard()
        }

        if (caps.tracks && state.canPickTracks && state.audio.size > 1) {
            TrackSection(
                title = "Audio",
                tracks = state.audio,
                selected = state.audioId,
                // Audio has no "off": a receiver must play something.
                onSelect = { id -> id?.let { scope.launch { onError(CastRemote.selectAudio(it)) } } },
            )
        }
        if (caps.tracks && state.canPickTracks && state.subtitles.isNotEmpty()) {
            TrackSection(
                title = "Subtitles",
                tracks = state.subtitles,
                selected = state.subtitleId,
                includeOff = true,
                onSelect = { id -> scope.launch { onError(CastRemote.setSubtitles(id)) } },
            )
        }
    }
}

@Composable
private fun Scrubber(
    state: CastRemoteState,
    onSeek: (Long) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else state.progress
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * state.durationMs).toLong())
            },
        )
        TimeRow(state = state)
    }
}

/**
 * Where the receiver is, for a target or an item that cannot seek. No slider: the
 * point of this line is that there is nothing to drag.
 */
@Composable
private fun PositionLine(state: CastRemoteState) {
    TimeRow(state = state)
}

@Composable
private fun TimeRow(state: CastRemoteState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(time(state.positionMs), style = MaterialTheme.typography.labelSmall)
        Text(time(state.durationMs), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TransportRow(
    state: CastRemoteState,
    canToggle: Boolean,
    canStop: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canToggle) {
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play",
                )
            }
        }
        if (canStop) {
            OutlinedButton(onClick = onStop) {
                Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Stop", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun TrackSection(
    title: String,
    tracks: List<CastRemoteTrack>,
    selected: Long?,
    includeOff: Boolean = false,
    onSelect: (Long?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (includeOff) {
                FilterChip(
                    selected = selected == null,
                    onClick = { onSelect(null) },
                    label = { Text("Off") },
                )
            }
            tracks.forEach { track ->
                FilterChip(
                    selected = selected == track.id,
                    onClick = { onSelect(track.id) },
                    label = { Text(track.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/**
 * "What's this song?" — the same answer the companion shows, asked by the phone
 * because this renderer is not a companion.
 */
@Composable
private fun MusicCard() {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<CompanionMusicAnswer?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedButton(
            onClick = {
                asking = true
                answer = null
                scope.launch {
                    answer = CastRemote.identifySong()
                    asking = false
                }
            },
            enabled = !asking,
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = if (asking) "Listening…" else "What's this song?",
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        val result = answer ?: return@Column
        NuvioSurfaceCard(modifier = Modifier.padding(top = 10.dp)) {
            when (result) {
                is CompanionMusicAnswer.Found -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    result.match.artworkUrl?.let { artwork ->
                        AsyncImage(
                            model = artwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Column {
                        Text(result.match.title, style = MaterialTheme.typography.titleSmall)
                        listOfNotNull(result.match.artist, result.match.album)
                            .joinToString(" — ")
                            .takeIf { it.isNotBlank() }
                            ?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                }

                is CompanionMusicAnswer.NoMatch ->
                    Text("No match for this moment.", style = MaterialTheme.typography.bodySmall)

                CompanionMusicAnswer.RateLimited ->
                    Text(
                        "That is today's limit for song identification. Try again tomorrow.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                is CompanionMusicAnswer.Unavailable -> Text(
                    text = when (result.reason) {
                        CompanionMusicFailure.NoPlayer -> "Nothing is playing to listen to."
                        CompanionMusicFailure.NoLink -> "Link this phone to the server first."
                        CompanionMusicFailure.Timeout -> "The server did not answer in time."
                        CompanionMusicFailure.NotLinked -> "This phone is not linked to the server."
                        CompanionMusicFailure.Network -> "Could not reach the server."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun time(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mm = if (hours > 0) minutes.toString().padStart(2, '0') else minutes.toString()
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}
