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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Picks the screen to cast to.
 *
 * The scan runs when the sheet opens, not when the app starts: SSDP is a network
 * call and a details screen is not a reason to make one. [rescanToken] re-runs it
 * on demand, which matters because a TV that was in standby when the sheet opened
 * answers on the second try.
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
    var devices by remember { mutableStateOf<List<CastDevice>>(emptyList()) }

    LaunchedEffect(rescanToken) {
        scanning = true
        devices = discoverCastDevices()
        scanning = false
    }

    DialogSurface(
        onDismissRequest = onDismissRequest,
        title = stringResource(Res.string.cast_picker_title),
    ) {
        when {
            scanning -> Row(
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

            devices.isEmpty() -> Text(
                text = stringResource(Res.string.cast_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(devices, key = { it.id }) { device ->
                    CastDeviceRow(device = device, onClick = { onSelect(device) })
                }
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.cast_picker_rescan),
                onClick = { rescanToken++ },
                style = DialogButtonStyle.Secondary,
                enabled = !scanning,
            )
            DialogButton(
                text = stringResource(Res.string.action_close),
                onClick = onDismissRequest,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun CastDeviceRow(
    device: CastDevice,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Cast,
            contentDescription = null,
            modifier = Modifier.width(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = device.subtitle(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "Roku · Roku TVs" when the table knows the model, and an explicit limit note when
 * it does not — the device still plays, just at the floor.
 */
@Composable
private fun CastDevice.subtitle(): String {
    val protocol = protocol.name
    val model = model
    return if (model.isNullOrBlank()) {
        "$protocol · " + stringResource(Res.string.cast_device_floor)
    } else {
        "$protocol · $model"
    }
}
