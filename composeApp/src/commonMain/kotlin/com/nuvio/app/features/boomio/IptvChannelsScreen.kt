package com.nuvio.app.features.boomio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_cancel
import nuvio.composeapp.generated.resources.iptv_device_offline
import nuvio.composeapp.generated.resources.iptv_empty_group
import nuvio.composeapp.generated.resources.iptv_empty_search
import nuvio.composeapp.generated.resources.iptv_empty_unscoped
import nuvio.composeapp.generated.resources.iptv_error_generic
import nuvio.composeapp.generated.resources.iptv_group_all
import nuvio.composeapp.generated.resources.iptv_loading
import nuvio.composeapp.generated.resources.iptv_party_active_title
import nuvio.composeapp.generated.resources.iptv_party_changed
import nuvio.composeapp.generated.resources.iptv_party_conflict_unknown
import nuvio.composeapp.generated.resources.iptv_party_end
import nuvio.composeapp.generated.resources.iptv_party_ended
import nuvio.composeapp.generated.resources.iptv_party_invite_hint
import nuvio.composeapp.generated.resources.iptv_party_invite_title
import nuvio.composeapp.generated.resources.iptv_party_members
import nuvio.composeapp.generated.resources.iptv_party_needs_channel
import nuvio.composeapp.generated.resources.iptv_party_on
import nuvio.composeapp.generated.resources.iptv_party_start
import nuvio.composeapp.generated.resources.iptv_party_started
import nuvio.composeapp.generated.resources.iptv_reload
import nuvio.composeapp.generated.resources.iptv_search_hint
import nuvio.composeapp.generated.resources.iptv_start
import nuvio.composeapp.generated.resources.iptv_title
import nuvio.composeapp.generated.resources.iptv_tune_offline
import nuvio.composeapp.generated.resources.iptv_tune_sent
import org.jetbrains.compose.resources.stringResource

/**
 * P5 + P6: pick a live IPTV channel on the phone, tune the paired TV to it, and
 * optionally run a live watch party on that channel.
 *
 * Nothing streams from here. Tuning sends `{type: "iptv_tune", streamId}` over
 * the companion websocket ([CompanionBridge.tuneChannel]) and the TV tunes
 * ITSELF through `IptvClient`, resolving its own playlist URL — so no session
 * token is ever minted here or pushed to another device.
 *
 * The party does no streaming work either. The edge's tuner is single-slot and
 * its segment fetch already fans out to every caller, so a second TV on the SAME
 * channel costs one upstream connection. Joining is just another `iptv_tune`.
 * Same-channel-only is enforced by a server-side tuner lock, because a different
 * channel tears down the session everyone is watching.
 *
 * WHY THE CATALOGUE IS LOADED AS ONE LIST RATHER THAN PER GROUP: see
 * [IptvRepository] — `/iptv/channels` is scoped to the household's FOLLOWED
 * groups, so asking for an unfollowed group returns an empty list that looks
 * like a bug.
 */
@Composable
fun IptvChannelsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var channels by remember { mutableStateOf<List<IptvChannel>>(emptyList()) }
    var groupNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var unscoped by remember { mutableStateOf(false) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // Monotonic counter rather than a nullable one-shot, so a reload can never be
    // lost to a consume/null race. Same pattern as the companion search tick.
    var reloadTick by remember { mutableIntStateOf(0) }

    var party by remember { mutableStateOf<IptvParty?>(null) }
    // The channel this phone last sent to the TV. The party starts on it, so the
    // party is always "on what we just put on the Shield" rather than a guess.
    var lastTuned by remember { mutableStateOf<IptvChannel?>(null) }
    var showingInvite by remember { mutableStateOf(false) }
    var selectedTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val session by BoomioSessionRepository.session.collectAsStateWithLifecycle()
    val devices by CompanionBridge.devices.collectAsStateWithLifecycle()
    val pairedDeviceId by CompanionBridge.pairedDeviceId.collectAsStateWithLifecycle()

    val genericError = stringResource(Res.string.iptv_error_generic)
    val offlineMessage = stringResource(Res.string.iptv_tune_offline)
    val allLabel = stringResource(Res.string.iptv_group_all)
    val needsChannelMessage = stringResource(Res.string.iptv_party_needs_channel)
    val conflictUnknown = stringResource(Res.string.iptv_party_conflict_unknown)
    val partyEndedMessage = stringResource(Res.string.iptv_party_ended)
    val cancelLabel = stringResource(Res.string.iptv_cancel)
    val startLabel = stringResource(Res.string.iptv_start)
    val offlineLabel = stringResource(Res.string.iptv_device_offline)

    LaunchedEffect(Unit) {
        CompanionBridge.refreshDevices()
        party = runCatching { IptvPartyRepository.current() }.getOrNull()
    }

    LaunchedEffect(reloadTick) {
        loading = true
        error = null
        try {
            val page = IptvRepository.loadChannels()
            // Skip the groups call entirely when there is nothing to name — an
            // unscoped household should not pay for a second round trip.
            val names = if (page.channels.isEmpty()) {
                emptyMap()
            } else {
                IptvRepository.loadGroups().associate { it.id to it.name }
            }
            channels = page.channels
            groupNames = names
            unscoped = page.unscoped
            // A group can vanish between reloads if the household's selection
            // changed underneath us; fall back to "All" rather than showing nothing.
            if (selectedGroup != null && page.channels.none { it.categoryId == selectedGroup }) {
                selectedGroup = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: genericError
        } finally {
            loading = false
        }
    }

    // Groups that actually contain a tunable channel, named and sorted. Anything
    // the household does not follow is absent by construction.
    val tunableGroups = remember(channels, groupNames) {
        channels.map { it.categoryId }
            .distinct()
            .sortedBy { (groupNames[it] ?: it).lowercase() }
    }

    val visible = remember(channels, selectedGroup, query) {
        val needle = query.trim()
        channels.filter { channel ->
            (selectedGroup == null || channel.categoryId == selectedGroup) &&
                (needle.isEmpty() || channel.name.contains(needle, ignoreCase = true))
        }
    }

    val myDeviceId = session?.deviceId
    val iAmHost = party != null && party?.hostPhoneId != null && party?.hostPhoneId == myDeviceId

    /**
     * Tuning is the same primitive in every case; only the DESTINATION differs.
     * With a party live, the host retargets everyone (and the server writes the
     * new channel into the party record BEFORE broadcasting, so members are not
     * refused the very change they were handed), while a non-host only moves
     * their own TV.
     */
    fun pickChannel(channel: IptvChannel, sentMessage: String) {
        val active = party
        lastTuned = channel
        if (active != null && iAmHost) {
            scope.launch {
                busy = true
                try {
                    IptvPartyRepository.changeChannel(active.id, channel.streamId, channel.name)
                    party = IptvPartyRepository.current() ?: active.copy(streamId = channel.streamId, channelName = channel.name)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NuvioToastController.show(e.message ?: genericError)
                } finally {
                    busy = false
                }
            }
            return
        }
        if (CompanionBridge.tuneChannel(channel.streamId, channel.name)) {
            NuvioToastController.show(sentMessage)
        } else {
            NuvioToastController.show(offlineMessage)
        }
    }

    NuvioScreen(modifier = modifier) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.iptv_title),
                onBack = onBack,
            )
        }

        if (loading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            item {
                Text(
                    text = stringResource(Res.string.iptv_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            return@NuvioScreen
        }

        error?.let { message ->
            item {
                NuvioSurfaceCard {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { reloadTick++ }) {
                        Text(stringResource(Res.string.iptv_reload))
                    }
                }
            }
            return@NuvioScreen
        }

        // The empty state that matters: the household follows no groups, so the
        // edge returns nothing BY DESIGN. Saying so is the difference between
        // "your panel isn't set up" and "this screen is broken".
        if (channels.isEmpty()) {
            item {
                NuvioSurfaceCard {
                    Text(
                        text = if (unscoped) {
                            stringResource(Res.string.iptv_empty_unscoped)
                        } else {
                            stringResource(Res.string.iptv_empty_group)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { reloadTick++ }) {
                        Text(stringResource(Res.string.iptv_reload))
                    }
                }
            }
            return@NuvioScreen
        }

        party?.let { active ->
            item {
                val onLabel = active.channelName ?: lastTuned?.name ?: active.streamId
                NuvioSurfaceCard {
                    Text(
                        text = stringResource(Res.string.iptv_party_active_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.iptv_party_on, onLabel),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(Res.string.iptv_party_members, active.members.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    IptvPartyRepository.end(active.id)
                                    party = null
                                    NuvioToastController.show(partyEndedMessage)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    NuvioToastController.show(e.message ?: genericError)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.iptv_party_end))
                    }
                }
            }
        }

        if (party == null) {
            item {
                Button(
                    enabled = !busy,
                    onClick = {
                        if (lastTuned == null) {
                            NuvioToastController.show(needsChannelMessage)
                        } else {
                            // The TV under control is always in the party; anything
                            // else is an explicit opt-in, so no TV in another room
                            // is ever tuned by surprise.
                            selectedTargets = setOfNotNull(pairedDeviceId)
                            showingInvite = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.iptv_party_start))
                }
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.iptv_search_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (tunableGroups.size > 1) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilledTonalButton(onClick = { selectedGroup = null }) {
                            Text(
                                text = allLabel,
                                fontWeight = if (selectedGroup == null) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                    items(tunableGroups) { groupId ->
                        FilledTonalButton(onClick = { selectedGroup = groupId }) {
                            Text(
                                text = groupNames[groupId] ?: groupId,
                                fontWeight = if (selectedGroup == groupId) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (visible.isEmpty()) {
            item {
                Text(
                    text = stringResource(Res.string.iptv_empty_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@NuvioScreen
        }

        items(visible, key = { it.streamId }) { channel ->
            // Resolved per item so the channel name can be substituted into the
            // confirmation at tap time.
            val sentMessage = stringResource(Res.string.iptv_tune_sent, channel.name)
            val changedMessage = stringResource(Res.string.iptv_party_changed, channel.name)
            NuvioSurfaceCard(
                modifier = Modifier.clickable(enabled = !busy) {
                    pickChannel(channel, if (party != null && iAmHost) changedMessage else sentMessage)
                }
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Only worth showing when the list spans groups — otherwise every
                // row repeats the group the user just filtered to.
                if (selectedGroup == null && tunableGroups.size > 1) {
                    Text(
                        text = groupNames[channel.categoryId] ?: channel.categoryId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (showingInvite) {
        val inviteTitle = stringResource(Res.string.iptv_party_invite_title)
        val inviteHint = stringResource(Res.string.iptv_party_invite_hint)
        // Resolved here rather than in the click handler: stringResource is
        // @Composable, and String.format does not exist in commonMain, so a
        // formatted message can only be built at composition scope. The party
        // always starts on the channel this phone last sent, so the name is
        // known now.
        val startedMessage = lastTuned?.let { stringResource(Res.string.iptv_party_started, it.name) }
        AlertDialog(
            onDismissRequest = { if (!busy) showingInvite = false },
            title = { Text(inviteTitle) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = inviteHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    devices.forEach { device ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedTargets = if (device.deviceId in selectedTargets) {
                                        selectedTargets - device.deviceId
                                    } else {
                                        selectedTargets + device.deviceId
                                    }
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = device.deviceId in selectedTargets,
                                onCheckedChange = { checked ->
                                    selectedTargets = if (checked) {
                                        selectedTargets + device.deviceId
                                    } else {
                                        selectedTargets - device.deviceId
                                    }
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(text = device.name, style = MaterialTheme.typography.bodyMedium)
                                if (!device.online) {
                                    // Not fatal: the server delivers to a TV that is
                                    // registered right now, so an offline TV simply
                                    // never tunes. Worth saying before they tap.
                                    Text(
                                        text = offlineLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && selectedTargets.isNotEmpty(),
                    onClick = {
                        val channel = lastTuned ?: return@TextButton
                        scope.launch {
                            busy = true
                            try {
                                val started = IptvPartyRepository.start(
                                    streamId = channel.streamId,
                                    channelName = channel.name,
                                    targetDeviceIds = selectedTargets.toList(),
                                )
                                party = started
                                showingInvite = false
                                if (startedMessage != null) {
                                    NuvioToastController.show(startedMessage)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: IptvPartyConflictException) {
                                // One tuner means one party. Adopt the running one
                                // instead of failing: reloading it puts the party
                                // card up, which names the channel the toast omits.
                                showingInvite = false
                                party = runCatching { IptvPartyRepository.current() }.getOrNull()
                                NuvioToastController.show(conflictUnknown)
                            } catch (e: Exception) {
                                NuvioToastController.show(e.message ?: genericError)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(startLabel)
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showingInvite = false }) {
                    Text(cancelLabel)
                }
            },
        )
    }
}
