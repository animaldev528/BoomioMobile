package com.nuvio.app.core.overlay

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.server_direct_found
import nuvio.composeapp.generated.resources.server_local_found
import nuvio.composeapp.generated.resources.server_local_searching
import nuvio.composeapp.generated.resources.server_local_unavailable
import nuvio.composeapp.generated.resources.server_overlay_found
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
        // ⚠️ The tunnel is not "local", and saying so would be wrong in the one place it
        // matters: this row is how a user confirms the app is off the public edge, and
        // `10.77.0.1` presented as a local server would send them looking on their own
        // network for a machine that is somewhere else entirely.
        is LocalServerStatus.Found -> when (status.source) {
            LocalServerSource.LAN -> stringResource(Res.string.server_local_found, status.address)
            LocalServerSource.TUNNEL -> stringResource(Res.string.server_overlay_found, status.address)
            // ⚠️ **Not "local", and the distinction is the whole reason this is a separate
            // string.** This address came from the discovery record and is the server's *public*
            // one; calling it local would send a user looking on their own network for a machine
            // that is not on it. Nothing publishes this state today — the WAN pin is placed by
            // `OverlayEndpointDiscovery.applyServicePins`, which does not own a status slot — so
            // this branch exists to keep the `when` exhaustive rather than because it renders.
            LocalServerSource.WAN -> stringResource(Res.string.server_direct_found, status.address)
        }
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
