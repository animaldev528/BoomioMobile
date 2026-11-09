package com.nuvio.app.features.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.features.boomio.CompanionBridge
import com.nuvio.app.features.boomio.toCastDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Picks the screen to cast to.
 *
 * The sheet lists two kinds of target in one place:
 *
 *  - **Enrolled Boomio TVs**, from the companion hub. They are not discovered on
 *    the LAN — they are already enrolled — and playback starts with a `play`
 *    frame, so a Boomio TV is as easy to send to as a Roku.
 *  - **Discovered renderers** (DLNA / Google Cast / Roku ECP / AirPlay), scanned
 *    when the sheet opens.
 *
 * The scan runs when the sheet opens, not when the app starts: SSDP is a network
 * call and a details screen is not a reason to make one. [rescanToken] re-runs it
 * on demand, which matters because a TV that was in standby when the sheet opened
 * answers on the second try.
 *
 * An AirPlay receiver is prepared before the dialog hands it back: the first cast
 * to an Apple TV pairs with the code on its screen, and this sheet shows the field
 * for it. The pairing is kept by the app, so the same TV is a single tap later.
 * Every other protocol needs no preparation and is handed back as it is.
 *
 * A device row states what the server will build for it. Devices whose model the
 * capability table cannot place are shown, not hidden — they still play, at the
 * server's floor (1080p / H.264 / AAC / no HDR), and saying so is better than an
 * unexplained soft picture.
 */
@Composable
internal fun CastPickerDialog(
    onDismissRequest: () -> Unit,
    onSelect: (CastDevice) -> Unit,
) {
    var rescanToken by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(true) }
    var discovered by remember { mutableStateOf<List<CastDevice>>(emptyList()) }
    // The receiver being prepared, the secret it is waiting for (with the field that
    // answers it), and the last failure — one cast attempt at a time.
    var preparing by remember { mutableStateOf<String?>(null) }
    var awaiting by remember { mutableStateOf<AirPlaySecret?>(null) }
    var answer by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Enrolled Boomio TVs are already known to the hub. Starting the companion
    // bridge here means the details screen works even when the viewer has never
    // opened Settings → Companion on this launch.
    val companionDevices by CompanionBridge.devices.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        CompanionBridge.ensureStarted()
        CompanionBridge.refreshDevices()
    }

    LaunchedEffect(rescanToken) {
        scanning = true
        discovered = discoverCastDevices()
        scanning = false
    }

    // One list, two sources. A Boomio TV with no address is not castable (the
    // stream has nowhere to be bound), so it is left out rather than offered as a
    // row that cannot work.
    val devices = remember(discovered, companionDevices) {
        (discovered + companionDevices.mapNotNull { it.toCastDevice() }).distinctBy { it.id }
    }

    // Which row is the current target. `null` means "this device", so the picker
    // can show the way back and mark it as the active choice.
    val current by CastSession.target.collectAsStateWithLifecycle()

    fun select(device: CastDevice) {
        error = null
        if (device.protocol != CastProtocol.AIRPLAY) {
            onSelect(device)
            return
        }
        if (preparing != null) return
        scope.launch {
            preparing = device.name
            val result =
                try {
                    prepareAirPlay(device) { secret ->
                        val typed = CompletableDeferred<String?>()
                        awaiting = secret
                        answer = typed
                        code = ""
                        typed.await()
                    }
                } finally {
                    preparing = null
                    awaiting = null
                    answer = null
                }
            if (result.ok) onSelect(device) else error = result.reason
        }
    }

    DialogSurface(
        onDismissRequest = onDismissRequest,
        title = stringResource(Res.string.cast_picker_title),
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // "This device" is a target like any other — and the way back. Once
            // a renderer is chosen this sheet is the only place that can say
            // "play here again", so leaving it out made the choice one-way: the
            // phone kept sending every later Play to the TV with no way out but
            // clearing the app's state.
            item(key = "__this_device__") {
                CastDeviceRow(
                    name = stringResource(Res.string.cast_this_device),
                    subtitle = stringResource(Res.string.cast_this_device_subtitle),
                    icon = Icons.Rounded.Smartphone,
                    selected = current == null,
                    onClick = {
                        CastSession.clear()
                        onDismissRequest()
                    },
                )
            }
            items(devices, key = { it.id }) { device ->
                CastDeviceRow(
                    name = device.name,
                    subtitle = device.subtitle(),
                    icon = Icons.Rounded.Cast,
                    selected = current?.id == device.id,
                    onClick = { select(device) },
                )
            }
            if (scanning) {
                item(key = "__scanning__") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        NuvioLoadingIndicator(size = 20.dp)
                        Text(
                            text = stringResource(Res.string.cast_picker_scanning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (devices.isEmpty()) {
                item(key = "__empty__") {
                    Text(
                        text = stringResource(Res.string.cast_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }

        // One line of progress, one optional code field, one optional error. The
        // code is what the Apple TV shows while it waits for this phone to answer.
        preparing?.let { name ->
            if (awaiting == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    NuvioLoadingIndicator(size = 20.dp)
                    Text(
                        text = stringResource(Res.string.cast_pairing_waiting, name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        awaiting?.let { secret ->
            Column(modifier = Modifier.padding(top = 12.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(8) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(stringResource(Res.string.cast_pairing_code, preparing.orEmpty()))
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secret == AirPlaySecret.PIN) KeyboardType.Number else KeyboardType.Text,
                    ),
                )
            }
        }

        error?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        DialogButtons {
            if (awaiting != null) {
                DialogButton(
                    text = stringResource(Res.string.cast_pairing_cancel),
                    onClick = {
                        answer?.complete(null)
                        error = "Pairing was cancelled"
                    },
                    style = DialogButtonStyle.Secondary,
                )
                DialogButton(
                    text = stringResource(Res.string.cast_pairing_pair),
                    onClick = { answer?.complete(code.trim()) },
                    style = DialogButtonStyle.Primary,
                    enabled = code.isNotBlank(),
                )
            } else {
                DialogButton(
                    text = stringResource(Res.string.cast_picker_rescan),
                    onClick = { rescanToken++ },
                    style = DialogButtonStyle.Secondary,
                    enabled = !scanning && preparing == null,
                )
                DialogButton(
                    text = stringResource(Res.string.action_close),
                    onClick = onDismissRequest,
                    style = DialogButtonStyle.Primary,
                )
            }
        }
    }
}

@Composable
private fun CastDeviceRow(
    name: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.width(18.dp),
            )
        }
    }
}

/**
 * "Roku · Roku TVs" when the table knows the model, and an explicit limit note when
 * it does not — the device still plays, just at the floor. An enrolled Boomio TV
 * is named as such: its limits come from its own stored row, not the model table.
 */
@Composable
private fun CastDevice.subtitle(): String {
    if (protocol == CastProtocol.BOOMIO) {
        return stringResource(Res.string.cast_target_boomio)
    }
    val protocol = protocol.name
    val model = model
    return if (model.isNullOrBlank()) {
        "$protocol · " + stringResource(Res.string.cast_device_floor)
    } else {
        // The ceiling is the useful half: "Chromecast (1st/2nd/3rd Gen)" is a
        // name, "1080p · H.264 · AAC" is what the viewer is about to get.
        listOfNotNull("$protocol · $model", ceiling?.takeIf { it.isNotBlank() })
            .joinToString(" — ")
    }
}
