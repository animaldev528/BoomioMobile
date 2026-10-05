package com.nuvio.app.core.overlay

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.server_local_found
import nuvio.composeapp.generated.resources.server_local_searching
import nuvio.composeapp.generated.resources.server_local_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * One line describing what local discovery found, or nothing at all.
 *
 * Silence is the common case and is deliberate: a device with no server on its network
 * should not be told about a feature it is not using. That is also why the failure
 * states are behind [showUnavailable] — they matter to a user who has gone looking, and
 * are noise to everyone else.
 */
@Composable
internal fun LocalServerStatusRow(
    modifier: Modifier = Modifier,
    showUnavailable: Boolean = false,
) {
    val current by LocalServerState.status.collectAsState()
    val tokens = MaterialTheme.nuvio

    // Bound through `when (val …)` rather than read from the delegated property: smart
    // casts do not apply to `by` delegates, so `current.address` would not compile.
    val text: String? = when (val status = current) {
        LocalServerStatus.Idle -> null
        is LocalServerStatus.Found ->
            stringResource(Res.string.server_local_found, status.address)
        LocalServerStatus.Searching ->
            if (showUnavailable) stringResource(Res.string.server_local_searching) else null
        is LocalServerStatus.Unavailable ->
            if (showUnavailable) {
                stringResource(Res.string.server_local_unavailable, status.reason)
            } else {
                null
            }
    }

    if (text == null) return

    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = if (current is LocalServerStatus.Found) tokens.colors.accent else tokens.colors.textMuted,
    )
}
