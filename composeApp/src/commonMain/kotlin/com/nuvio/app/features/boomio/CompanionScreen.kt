package com.nuvio.app.features.boomio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.companion_cancel
import nuvio.composeapp.generated.resources.companion_connected_hub
import nuvio.composeapp.generated.resources.companion_connecting_hub
import nuvio.composeapp.generated.resources.companion_connect
import nuvio.composeapp.generated.resources.companion_disconnect_tv
import nuvio.composeapp.generated.resources.companion_music_ask
import nuvio.composeapp.generated.resources.companion_music_ask_again
import nuvio.composeapp.generated.resources.companion_music_already_saved
import nuvio.composeapp.generated.resources.companion_music_fail_network
import nuvio.composeapp.generated.resources.companion_music_fail_network_hint
import nuvio.composeapp.generated.resources.companion_music_fail_no_link
import nuvio.composeapp.generated.resources.companion_music_fail_no_link_hint
import nuvio.composeapp.generated.resources.companion_music_fail_no_player
import nuvio.composeapp.generated.resources.companion_music_fail_no_player_hint
import nuvio.composeapp.generated.resources.companion_music_fail_not_linked
import nuvio.composeapp.generated.resources.companion_music_fail_not_linked_hint
import nuvio.composeapp.generated.resources.companion_music_fail_timeout
import nuvio.composeapp.generated.resources.companion_music_fail_timeout_hint
import nuvio.composeapp.generated.resources.companion_music_found_hint
import nuvio.composeapp.generated.resources.companion_music_from_index
import nuvio.composeapp.generated.resources.companion_music_hint
import nuvio.composeapp.generated.resources.companion_music_listen_hint
import nuvio.composeapp.generated.resources.companion_music_mismatch
import nuvio.composeapp.generated.resources.companion_music_no_context
import nuvio.composeapp.generated.resources.companion_music_no_context_hint
import nuvio.composeapp.generated.resources.companion_music_no_match
import nuvio.composeapp.generated.resources.companion_music_no_match_hint
import nuvio.composeapp.generated.resources.companion_music_no_stream
import nuvio.composeapp.generated.resources.companion_music_no_stream_hint
import nuvio.composeapp.generated.resources.companion_music_rate_limited
import nuvio.composeapp.generated.resources.companion_music_rate_limited_hint
import nuvio.composeapp.generated.resources.companion_music_save
import nuvio.composeapp.generated.resources.companion_music_saved
import nuvio.composeapp.generated.resources.companion_music_saving
import nuvio.composeapp.generated.resources.companion_music_title
import nuvio.composeapp.generated.resources.companion_key_back
import nuvio.composeapp.generated.resources.companion_key_down
import nuvio.composeapp.generated.resources.companion_key_left
import nuvio.composeapp.generated.resources.companion_key_ok
import nuvio.composeapp.generated.resources.companion_key_right
import nuvio.composeapp.generated.resources.companion_key_up
import nuvio.composeapp.generated.resources.companion_link_failed_expired
import nuvio.composeapp.generated.resources.companion_link_failed_start
import nuvio.composeapp.generated.resources.companion_link_failed_unauthenticated
import nuvio.composeapp.generated.resources.companion_linking
import nuvio.composeapp.generated.resources.companion_no_tvs
import nuvio.composeapp.generated.resources.companion_nothing_playing
import nuvio.composeapp.generated.resources.companion_now_playing
import nuvio.composeapp.generated.resources.companion_paused
import nuvio.composeapp.generated.resources.companion_pick_a_tv
import nuvio.composeapp.generated.resources.companion_play_pause
import nuvio.composeapp.generated.resources.companion_playing
import nuvio.composeapp.generated.resources.companion_remote_title
import nuvio.composeapp.generated.resources.companion_remove
import nuvio.composeapp.generated.resources.companion_search_go
import nuvio.composeapp.generated.resources.companion_search_hint
import nuvio.composeapp.generated.resources.companion_search_mic
import nuvio.composeapp.generated.resources.companion_search_no_speech
import nuvio.composeapp.generated.resources.companion_search_title
import nuvio.composeapp.generated.resources.companion_toast_not_paired
import nuvio.composeapp.generated.resources.companion_toast_rate_limited
import nuvio.composeapp.generated.resources.companion_toast_state_restored
import nuvio.composeapp.generated.resources.companion_toast_timeout
import nuvio.composeapp.generated.resources.companion_tv_navigation
import nuvio.composeapp.generated.resources.companion_unlink
import nuvio.composeapp.generated.resources.companion_unlink_confirm
import nuvio.composeapp.generated.resources.companion_unlinked_description
import nuvio.composeapp.generated.resources.companion_unlinked_title
import nuvio.composeapp.generated.resources.companion_pl_error_connection
import nuvio.composeapp.generated.resources.companion_pl_error_different_network
import nuvio.composeapp.generated.resources.companion_pl_error_fork
import nuvio.composeapp.generated.resources.companion_pl_error_no_network
import nuvio.composeapp.generated.resources.companion_pl_error_no_player
import nuvio.composeapp.generated.resources.companion_pl_error_timeout
import nuvio.composeapp.generated.resources.companion_pl_sync
import nuvio.composeapp.generated.resources.companion_pl_sync_hint
import nuvio.composeapp.generated.resources.companion_pl_sync_ms
import nuvio.composeapp.generated.resources.companion_private_listening
import nuvio.composeapp.generated.resources.companion_private_listening_hint
import nuvio.composeapp.generated.resources.companion_private_listening_on
import nuvio.composeapp.generated.resources.companion_private_listening_starting
import nuvio.composeapp.generated.resources.companion_private_listening_stopping
import nuvio.composeapp.generated.resources.companion_volume
import nuvio.composeapp.generated.resources.compose_settings_page_companion
import nuvio.composeapp.generated.resources.iptv_open_description
import nuvio.composeapp.generated.resources.iptv_title
import org.jetbrains.compose.resources.stringResource

/** Interval between device-list refreshes while a companion session is active. */
private const val DEVICE_REFRESH_INTERVAL_MILLIS = 5_000L

/**
 * The N2 companion screen: link the phone to the boomio companion hub, pick a TV,
 * and control its playback. State lives in [BoomioSessionRepository] and
 * [CompanionBridge]; this screen only drives them.
 */
@Composable
fun CompanionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLiveTv: () -> Unit = {},
) {
    LaunchedEffect(Unit) {
        BoomioSessionRepository.initialize()
        CompanionBridge.ensureStarted()
        CompanionBridge.refreshDevices()
    }

    val session by BoomioSessionRepository.session.collectAsStateWithLifecycle()
    val linkState by BoomioSessionRepository.linkState.collectAsStateWithLifecycle()
    val connected by CompanionBridge.connected.collectAsStateWithLifecycle()
    val devices by CompanionBridge.devices.collectAsStateWithLifecycle()
    val pairedDeviceId by CompanionBridge.pairedDeviceId.collectAsStateWithLifecycle()
    val deviceListError by CompanionBridge.deviceListError.collectAsStateWithLifecycle()

    // Keep the now-playing line current while linked.
    LaunchedEffect(session) {
        while (session != null) {
            CompanionBridge.refreshDevices()
            delay(DEVICE_REFRESH_INTERVAL_MILLIS)
        }
    }

    // Surface inbound hub events as toasts.
    val timeoutToast = stringResource(Res.string.companion_toast_timeout)
    val notPairedToast = stringResource(Res.string.companion_toast_not_paired)
    val rateLimitedToast = stringResource(Res.string.companion_toast_rate_limited)
    val stateRestoredToast = stringResource(Res.string.companion_toast_state_restored)
    LaunchedEffect(Unit) {
        CompanionBridge.events.collect { event ->
            when (event) {
                is CompanionEvent.Timeout -> NuvioToastController.show(timeoutToast)
                is CompanionEvent.NotPaired -> NuvioToastController.show(notPairedToast)
                is CompanionEvent.RateLimited -> NuvioToastController.show(rateLimitedToast)
                is CompanionEvent.StateRestored -> NuvioToastController.show(stateRestoredToast)
                is CompanionEvent.AudioFork -> Unit // consumed by PrivateListeningSession's ack waiter
                is CompanionEvent.TvPush -> Unit
                is CompanionEvent.Error -> event.message?.let { NuvioToastController.show(it) }
            }
        }
    }

    NuvioScreen(modifier = modifier) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_companion),
                onBack = onBack,
            )
        }
        if (session == null) {
            item { UnlinkedCard(linkState = linkState) }
        } else {
            val pairedId = pairedDeviceId
            if (pairedId == null) {
                item {
                    DevicePicker(
                        devices = devices,
                        deviceListError = deviceListError,
                        connected = connected,
                    )
                }
            } else {
                item {
                    RemoteControls(
                        pairedDeviceId = pairedId,
                        devices = devices,
                        connected = connected,
                    )
                }
            }
            item { LiveTvRow(onOpen = onOpenLiveTv) }
            item { UnlinkRow() }
        }
    }
}

/**
 * Entry point to the P5 channel picker.
 *
 * It lives inside the companion screen, not the settings root, on purpose: sending
 * a tune needs a live companion session, so a root-level row could be opened
 * unpaired and would appear broken. Here it is only reachable once a TV is paired.
 */
@Composable
private fun LiveTvRow(onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onOpen) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(Res.string.iptv_title))
        }
        Text(
            text = stringResource(Res.string.iptv_open_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnlinkedCard(linkState: BoomioLinkState) {
    val title = stringResource(Res.string.companion_unlinked_title)
    val description = stringResource(Res.string.companion_unlinked_description)
    val connectLabel = stringResource(Res.string.companion_connect)
    val linkingLabel = stringResource(Res.string.companion_linking)
    val failStart = stringResource(Res.string.companion_link_failed_start)
    val failUnauthenticated = stringResource(Res.string.companion_link_failed_unauthenticated)
    val failExpired = stringResource(Res.string.companion_link_failed_expired)

    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (linkState) {
                BoomioLinkState.Idle -> {
                    Button(onClick = { BoomioSessionRepository.startLink() }) {
                        Text(connectLabel)
                    }
                }
                BoomioLinkState.Starting, BoomioLinkState.Linking -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(linkingLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is BoomioLinkState.Failed -> {
                    val message = when (linkState.reason) {
                        BoomioLinkFailure.Unauthenticated -> failUnauthenticated
                        BoomioLinkFailure.Expired -> failExpired
                        BoomioLinkFailure.Start -> failStart
                    }
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { BoomioSessionRepository.startLink() }) {
                        Text(connectLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicePicker(
    devices: List<CompanionDevice>,
    deviceListError: String?,
    connected: Boolean,
) {
    val pickLabel = stringResource(Res.string.companion_pick_a_tv)
    val noTvs = stringResource(Res.string.companion_no_tvs)
    val connectLabel = stringResource(Res.string.companion_connect)
    val connectedHub = stringResource(Res.string.companion_connected_hub)
    val connectingHub = stringResource(Res.string.companion_connecting_hub)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionStatusLine(connected = connected, connectedHub = connectedHub, connectingHub = connectingHub)
        Text(
            pickLabel,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        deviceListError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (devices.isEmpty()) {
            NuvioSurfaceCard {
                Text(
                    noTvs,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            devices.forEach { device ->
                NuvioSurfaceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                device.nowPlaying ?: noTvs,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(onClick = { CompanionBridge.pairTo(device.deviceId) }) {
                            Text(connectLabel)
                        }
                    }
                }
            }
        }
    }
}

// Audio-sync slider domain in ms, relative to the receiver's 100 ms baseline
// prefill. Kept inside ForkUdpReceiver's clamp window (20..500 ms prefill) so the
// shown value is always the applied value. 10 ms per discrete step.
private const val SYNC_MIN_MS = -80
private const val SYNC_MAX_MS = 200
private const val SYNC_STEP_MS = 10

@Composable
private fun RemoteControls(
    pairedDeviceId: String,
    devices: List<CompanionDevice>,
    connected: Boolean,
) {
    val remoteTitle = stringResource(Res.string.companion_remote_title)
    val connectedHub = stringResource(Res.string.companion_connected_hub)
    val connectingHub = stringResource(Res.string.companion_connecting_hub)
    val nowPlayingLabel = stringResource(Res.string.companion_now_playing)
    val nothingPlaying = stringResource(Res.string.companion_nothing_playing)
    val playing = stringResource(Res.string.companion_playing)
    val paused = stringResource(Res.string.companion_paused)
    val playPauseLabel = stringResource(Res.string.companion_play_pause)
    val volumeLabel = stringResource(Res.string.companion_volume)
    val disconnectLabel = stringResource(Res.string.companion_disconnect_tv)
    val tvNavigationLabel = stringResource(Res.string.companion_tv_navigation)
    val okLabel = stringResource(Res.string.companion_key_ok)
    val backLabel = stringResource(Res.string.companion_key_back)
    val upLabel = stringResource(Res.string.companion_key_up)
    val downLabel = stringResource(Res.string.companion_key_down)
    val leftLabel = stringResource(Res.string.companion_key_left)
    val rightLabel = stringResource(Res.string.companion_key_right)

    val device = devices.firstOrNull { it.deviceId == pairedDeviceId }

    // Leaving the remote does NOT end an active fork: a foreground service
    // ([PrivateListeningForeground]) holds the process while the fork plays, so
    // audio keeps coming when the user does something else on the phone or the
    // screen goes dark. Only the toggle, the notification's Stop, or swiping the
    // app away ends it.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionStatusLine(connected = connected, connectedHub = connectedHub, connectingHub = connectingHub)
        Text(
            remoteTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        NuvioSurfaceCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    nowPlayingLabel.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val title = device?.nowPlaying ?: nothingPlaying
                val stateLabel = when {
                    device == null -> null
                    device.isPlaying -> playing
                    else -> paused
                }
                Text(
                    listOfNotNull(title, stateLabel).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Button(
            onClick = { CompanionBridge.togglePlayPause() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(
                imageVector = if (device?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = playPauseLabel,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(playPauseLabel)
        }

        SearchRemoteControl()

        MusicIdentifyCard()

        TvControlPad(
            label = tvNavigationLabel,
            upLabel = upLabel,
            downLabel = downLabel,
            leftLabel = leftLabel,
            rightLabel = rightLabel,
            okLabel = okLabel,
            backLabel = backLabel,
        )

        if (device != null && device.durationMs > 0L) {
            SeekBar(device = device)
        }

        VolumeSlider(volumeLabel = volumeLabel)

        PrivateListeningToggle()

        OutlinedButton(
            onClick = { CompanionBridge.unpair() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.LinkOff,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(disconnectLabel)
        }
    }
}

/**
 * The music note, for companion mode.
 *
 * The TV has its own note button, and it opens a card over the picture. That is
 * the right shape when the viewer is holding the TV remote; it is the wrong one
 * when they are holding the phone and looking at it. This is the same question
 * with the answer rendered where the press happened, and nothing drawn on the TV.
 *
 * It is not a phone-side reimplementation. The press still goes to the TV and
 * the TV still asks bsc — the audio-track ordinal that decides which stream the
 * server listens to exists only there. See [CompanionBridge.requestMusicIdentify].
 */
@Composable
private fun MusicIdentifyCard() {
    val state by CompanionMusicController.state.collectAsStateWithLifecycle()
    val saveState by CompanionMusicController.saveState.collectAsStateWithLifecycle()

    val title = stringResource(Res.string.companion_music_title)
    val hint = stringResource(Res.string.companion_music_hint)
    val askLabel = stringResource(Res.string.companion_music_ask)
    val askAgainLabel = stringResource(Res.string.companion_music_ask_again)
    val listeningHint = stringResource(Res.string.companion_music_listen_hint)
    val foundHint = stringResource(Res.string.companion_music_found_hint)
    val noMatch = stringResource(Res.string.companion_music_no_match)
    val noMatchHint = stringResource(Res.string.companion_music_no_match_hint)
    val noStream = stringResource(Res.string.companion_music_no_stream)
    val noStreamHint = stringResource(Res.string.companion_music_no_stream_hint)
    val noContext = stringResource(Res.string.companion_music_no_context)
    val noContextHint = stringResource(Res.string.companion_music_no_context_hint)
    val rateLimited = stringResource(Res.string.companion_music_rate_limited)
    val rateLimitedHint = stringResource(Res.string.companion_music_rate_limited_hint)
    val failNoPlayer = stringResource(Res.string.companion_music_fail_no_player)
    val failNoPlayerHint = stringResource(Res.string.companion_music_fail_no_player_hint)
    val failNoLink = stringResource(Res.string.companion_music_fail_no_link)
    val failNoLinkHint = stringResource(Res.string.companion_music_fail_no_link_hint)
    val failTimeout = stringResource(Res.string.companion_music_fail_timeout)
    val failTimeoutHint = stringResource(Res.string.companion_music_fail_timeout_hint)
    val failNotLinked = stringResource(Res.string.companion_music_fail_not_linked)
    val failNotLinkedHint = stringResource(Res.string.companion_music_fail_not_linked_hint)
    val failNetwork = stringResource(Res.string.companion_music_fail_network)
    val failNetworkHint = stringResource(Res.string.companion_music_fail_network_hint)
    val mismatchLabel = stringResource(Res.string.companion_music_mismatch)
    val fromIndexLabel = stringResource(Res.string.companion_music_from_index)
    val saveLabel = stringResource(Res.string.companion_music_save)
    val savingLabel = stringResource(Res.string.companion_music_saving)
    val savedLabel = stringResource(Res.string.companion_music_saved)
    val alreadySavedLabel = stringResource(Res.string.companion_music_already_saved)

    val listening = state is CompanionMusicState.Listening

    // One line under the title that always says what is going on. Every string
    // is resolved above rather than inside a `when` — `stringResource` is a
    // composable call and cannot be reached from a non-composable lambda such as
    // `ifBlank {}`.
    val status = when (val s = state) {
        CompanionMusicState.Idle -> hint
        CompanionMusicState.Listening -> listeningHint
        is CompanionMusicState.Found ->
            listOfNotNull(s.match.artist, s.match.album).joinToString(" · ").ifBlank { foundHint }
        is CompanionMusicState.NoMatch -> when (s.status) {
            "no_stream" -> noStreamHint
            "no_context" -> noContextHint
            else -> noMatchHint
        }
        CompanionMusicState.RateLimited -> rateLimitedHint
        is CompanionMusicState.Unavailable -> when (s.reason) {
            CompanionMusicFailure.NoPlayer -> failNoPlayerHint
            CompanionMusicFailure.NoLink -> failNoLinkHint
            CompanionMusicFailure.Timeout -> failTimeoutHint
            CompanionMusicFailure.NotLinked -> failNotLinkedHint
            CompanionMusicFailure.Network -> failNetworkHint
        }
    }

    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (listening) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (listening) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    TextButton(onClick = { CompanionMusicController.identify() }) {
                        Text(if (state is CompanionMusicState.Idle) askLabel else askAgainLabel)
                    }
                }
            }

            when (val s = state) {
                is CompanionMusicState.Found -> FoundTrackBody(
                    found = s,
                    saveState = saveState,
                    mismatchLabel = mismatchLabel,
                    fromIndexLabel = fromIndexLabel,
                    saveLabel = saveLabel,
                    savingLabel = savingLabel,
                    savedLabel = savedLabel,
                    alreadySavedLabel = alreadySavedLabel,
                    saveFailedLabel = when (val f = saveState) {
                        is CompanionMusicSaveState.Failed -> when (f.reason) {
                            CompanionMusicFailure.NotLinked -> failNotLinked
                            else -> failNetwork
                        }
                        else -> failNetwork
                    },
                )

                is CompanionMusicState.NoMatch -> Text(
                    when (s.status) {
                        "no_stream" -> noStream
                        "no_context" -> noContext
                        else -> noMatch
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                CompanionMusicState.RateLimited -> Text(
                    rateLimited,
                    style = MaterialTheme.typography.bodyMedium,
                )

                is CompanionMusicState.Unavailable -> Text(
                    when (s.reason) {
                        CompanionMusicFailure.NoPlayer -> failNoPlayer
                        CompanionMusicFailure.NoLink -> failNoLink
                        CompanionMusicFailure.Timeout -> failTimeout
                        CompanionMusicFailure.NotLinked -> failNotLinked
                        CompanionMusicFailure.Network -> failNetwork
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                CompanionMusicState.Idle, CompanionMusicState.Listening -> Unit
            }
        }
    }
}

/**
 * The identified song: artwork, credits, and the two things about the answer
 * that are not visible in a title and artist.
 */
@Composable
private fun FoundTrackBody(
    found: CompanionMusicState.Found,
    saveState: CompanionMusicSaveState,
    mismatchLabel: String,
    fromIndexLabel: String,
    saveLabel: String,
    savingLabel: String,
    savedLabel: String,
    alreadySavedLabel: String,
    saveFailedLabel: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val artwork = found.match.artworkUrl
            if (!artwork.isNullOrBlank()) {
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    found.match.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                found.match.artist?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                found.match.album?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // The one caveat worth the space: the server listened to a different
        // audio track than the one playing, so this may name music the viewer is
        // not hearing. Silent without it, and the TV's own overlay flags it too.
        if (found.trackMismatch) {
            Text(
                mismatchLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (found.fromIndex) {
            Text(
                fromIndexLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (saveState) {
            CompanionMusicSaveState.Idle -> FilledTonalButton(
                onClick = { CompanionMusicController.save() },
            ) {
                Icon(
                    imageVector = Icons.Rounded.LibraryAdd,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(saveLabel)
            }

            CompanionMusicSaveState.Saving -> Text(
                savingLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CompanionMusicSaveState.Saved -> Text(
                savedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            CompanionMusicSaveState.AlreadySaved -> Text(
                alreadySavedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is CompanionMusicSaveState.Failed -> Text(
                saveFailedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * TV-search input for the phone remote — for TVs whose own search bar has no
 * keyboard or speech input (e.g. mic-less demo units). The phone's soft
 * keyboard does the typing; a mic button runs the system voice dialog. Text is
 * forwarded as whole-query `keyboard_input` replacements so the TV's live
 * search updates per keystroke; Enter or the Search button submits. Focusing
 * the field opens the TV's Search screen via `stealth_search`.
 */
@Composable
private fun SearchRemoteControl() {
    val titleLabel = stringResource(Res.string.companion_search_title)
    val hint = stringResource(Res.string.companion_search_hint)
    val goLabel = stringResource(Res.string.companion_search_go)
    val micLabel = stringResource(Res.string.companion_search_mic)
    val noSpeech = stringResource(Res.string.companion_search_no_speech)
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val launchSpeech = rememberSpeechLauncher(prompt = hint) { transcript ->
        if (transcript != null) {
            query = transcript
            CompanionBridge.sendSearchText(transcript)
            CompanionBridge.submitSearch()
        }
    }

    NuvioSurfaceCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                titleLabel.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { next ->
                    query = next
                    CompanionBridge.sendSearchText(next)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) CompanionBridge.openSearch() },
                singleLine = true,
                placeholder = { Text(hint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    CompanionBridge.submitSearch()
                    keyboard?.hide()
                }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val launched = launchSpeech()
                        if (!launched) NuvioToastController.show(noSpeech)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = micLabel,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(micLabel)
                }
                Button(
                    onClick = {
                        CompanionBridge.submitSearch()
                        keyboard?.hide()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(goLabel)
                }
            }
        }
    }
}

@Composable
private fun SeekBar(device: CompanionDevice) {
    var scrubbing by remember(device.deviceId) { mutableStateOf(false) }
    var dragMs by remember(device.deviceId) { mutableFloatStateOf(device.positionMs.toFloat()) }
    LaunchedEffect(device.deviceId, device.positionMs) {
        if (!scrubbing) dragMs = device.positionMs.toFloat()
    }
    val durationMs = device.durationMs.coerceAtLeast(1L)
    Slider(
        value = dragMs.coerceIn(0f, durationMs.toFloat()),
        onValueChange = { newValue ->
            dragMs = newValue
            scrubbing = true
            CompanionBridge.sendScrubUpdate(newValue.toLong())
        },
        onValueChangeFinished = {
            scrubbing = false
            CompanionBridge.seekTo(dragMs.toLong())
        },
        valueRange = 0f..durationMs.toFloat(),
    )
}

@Composable
private fun VolumeSlider(volumeLabel: String) {
    var volume by remember { mutableIntStateOf(50) }
    // bsc caps stealth_volume at 5/s — coalesce a drag to ~4/s and always commit
    // the final value on release so the TV lands exactly where the user lets go.
    var lastSentVolume by remember { mutableIntStateOf(-1) }
    var lastVolumeSendAt by remember { mutableStateOf(TimeSource.Monotonic.markNow()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            volumeLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = volume.toFloat(),
            onValueChange = { newValue ->
                volume = newValue.toInt()
                val now = TimeSource.Monotonic.markNow()
                if (volume != lastSentVolume &&
                    (lastSentVolume < 0 || now - lastVolumeSendAt >= 250.milliseconds)
                ) {
                    lastVolumeSendAt = now
                    lastSentVolume = volume
                    CompanionBridge.setVolume(volume)
                }
            },
            onValueChangeFinished = {
                if (volume != lastSentVolume) {
                    lastSentVolume = volume
                    CompanionBridge.setVolume(volume)
                }
            },
            valueRange = 0f..100f,
        )
    }
}

/**
 * A TV-style navigation pad: directional arrows around an OK key plus a Back
 * button. Presses are forwarded to the TV as `stealth_keyevent` frames and drive
 * whatever screen is focused there (home rows, player, settings).
 */
@Composable
private fun TvControlPad(
    label: String,
    upLabel: String,
    downLabel: String,
    leftLabel: String,
    rightLabel: String,
    okLabel: String,
    backLabel: String,
) {
    NuvioSurfaceCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                NavPadKey(
                    keyCode = CompanionKeyCodes.DPAD_UP,
                    icon = Icons.Rounded.KeyboardArrowUp,
                    label = upLabel,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NavPadKey(
                    keyCode = CompanionKeyCodes.DPAD_LEFT,
                    icon = Icons.Rounded.KeyboardArrowLeft,
                    label = leftLabel,
                )
                FilledTonalButton(
                    onClick = { CompanionBridge.pressKey(CompanionKeyCodes.DPAD_CENTER) },
                    modifier = Modifier.size(width = 56.dp, height = 56.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(okLabel, style = MaterialTheme.typography.labelLarge)
                }
                NavPadKey(
                    keyCode = CompanionKeyCodes.DPAD_RIGHT,
                    icon = Icons.Rounded.KeyboardArrowRight,
                    label = rightLabel,
                )
            }
            Row {
                NavPadKey(
                    keyCode = CompanionKeyCodes.DPAD_DOWN,
                    icon = Icons.Rounded.KeyboardArrowDown,
                    label = downLabel,
                )
            }
            OutlinedButton(
                onClick = { CompanionBridge.pressKey(CompanionKeyCodes.BACK) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(backLabel)
            }
        }
    }
}

@Composable
private fun NavPadKey(
    keyCode: Int,
    icon: ImageVector,
    label: String,
) {
    FilledTonalButton(
        onClick = { CompanionBridge.pressKey(keyCode) },
        modifier = Modifier.size(width = 56.dp, height = 56.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun ConnectionStatusLine(connected: Boolean, connectedHub: String, connectingHub: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = if (connected) Color(0xFF4CAF50) else Color(0xFFFFB300),
                    shape = CircleShape,
                ),
        )
        Text(
            text = if (connected) connectedHub else connectingHub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnlinkRow() {
    val unlinkLabel = stringResource(Res.string.companion_unlink)
    val confirmLabel = stringResource(Res.string.companion_unlink_confirm)
    val removeLabel = stringResource(Res.string.companion_remove)
    val cancelLabel = stringResource(Res.string.companion_cancel)
    var showConfirm by remember { mutableStateOf(false) }

    TextButton(onClick = { showConfirm = true }) {
        Icon(
            imageVector = Icons.Rounded.LinkOff,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(unlinkLabel)
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(confirmLabel) },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    BoomioSessionRepository.unlink()
                }) {
                    Text(removeLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }
}

/**
 * Roku-style private listening toggle: arm/stop the TV's audio fork from the
 * remote. Rendered only when the platform supports it ([PrivateListeningStatus.Unsupported]
 * hides it entirely — e.g. iOS).
 */
@Composable
private fun PrivateListeningToggle() {
    val state by PrivateListeningSession.state.collectAsStateWithLifecycle()
    if (state.status == PrivateListeningStatus.Unsupported) return

    val title = stringResource(Res.string.companion_private_listening)
    val hint = stringResource(Res.string.companion_private_listening_hint)
    val onLabel = stringResource(Res.string.companion_private_listening_on)
    val startingLabel = stringResource(Res.string.companion_private_listening_starting)
    val stoppingLabel = stringResource(Res.string.companion_private_listening_stopping)
    val errorNoPlayer = stringResource(Res.string.companion_pl_error_no_player)
    val errorDifferentNetwork = stringResource(Res.string.companion_pl_error_different_network)
    val errorFork = stringResource(Res.string.companion_pl_error_fork)
    val errorNoNetwork = stringResource(Res.string.companion_pl_error_no_network)
    val errorTimeout = stringResource(Res.string.companion_pl_error_timeout)
    val errorConnection = stringResource(Res.string.companion_pl_error_connection)

    val active = state.status == PrivateListeningStatus.Active
    val busy = state.status == PrivateListeningStatus.Arming ||
        state.status == PrivateListeningStatus.Stopping
    // Hoist before the null check: `state` is a delegated property, so Kotlin
    // won't smart-cast past a null check on `state.endpoint`.
    val endpoint = state.endpoint

    val subtitle = when (state.status) {
        PrivateListeningStatus.Active -> onLabel
        PrivateListeningStatus.Arming -> startingLabel
        PrivateListeningStatus.Stopping -> stoppingLabel
        PrivateListeningStatus.Idle -> when (state.failure) {
            PrivateListeningFailure.NoActivePlayer -> errorNoPlayer
            PrivateListeningFailure.DifferentNetwork -> errorDifferentNetwork
            PrivateListeningFailure.BadAddress,
            PrivateListeningFailure.ForkUnavailable -> errorFork
            PrivateListeningFailure.NoNetwork -> errorNoNetwork
            PrivateListeningFailure.Timeout -> errorTimeout
            PrivateListeningFailure.ConnectionLost -> errorConnection
            null -> hint
        }
        PrivateListeningStatus.Unsupported -> hint
    }
    val showError = state.status == PrivateListeningStatus.Idle && state.failure != null

    NuvioSurfaceCard {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (showError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (active && endpoint != null) {
                        Text(
                            endpoint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = active,
                    enabled = !busy,
                    onCheckedChange = { PrivateListeningSession.toggle() },
                )
            }
            // Only reachable while audio is actually forking — tune against the
            // live TV picture; releasing the thumb re-cushions at the new delay
            // (a brief gap, then audio at the new latency).
            if (active) {
                AudioSyncSlider()
            }
        }
    }
}

/**
 * Phone-vs-TV audio-sync slider, shown while a fork is active. Shifts the playout
 * prefill around the receiver's 100 ms baseline: negative = phone audio sooner
 * (it currently trails the TV picture), positive = later. The slider domain
 * (−80..+200 ms) sits inside the receiver's clamp window (20..500 ms prefill ⇒
 * offset −80..+400) so the shown value is always the applied value. Committed on
 * thumb release — the receiver re-cushions live; the session persists the value.
 */
@Composable
private fun AudioSyncSlider() {
    val syncLabel = stringResource(Res.string.companion_pl_sync)
    val syncHint = stringResource(Res.string.companion_pl_sync_hint)
    val committed by PrivateListeningSession.syncOffsetMs.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(committed) }
    val steps = (SYNC_MAX_MS - SYNC_MIN_MS) / SYNC_STEP_MS - 1
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                syncLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.companion_pl_sync_ms, draft),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = draft.toFloat(),
            onValueChange = { draft = it.roundToInt() },
            onValueChangeFinished = { PrivateListeningSession.setSyncOffsetMs(draft) },
            valueRange = SYNC_MIN_MS.toFloat()..SYNC_MAX_MS.toFloat(),
            steps = steps,
        )
        Text(
            syncHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
