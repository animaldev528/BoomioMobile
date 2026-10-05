package com.nuvio.app.core.overlay

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.overlay_endpoint_found
import nuvio.composeapp.generated.resources.overlay_endpoint_needs_manual
import nuvio.composeapp.generated.resources.overlay_endpoint_searching
import nuvio.composeapp.generated.resources.overlay_endpoint_unavailable
import org.jetbrains.compose.resources.stringResource

/**
 * One line describing what the endpoint ladder found, or nothing at all.
 *
 * **Why this exists next to [LocalServerStatusRow] rather than inside it.** The two answer
 * different questions and can disagree. That row says *where boomio traffic is going*; this
 * one says *how the app went looking for the tunnel*. A device whose ladder has exhausted
 * every rung has no server to report at all — `LocalServerState` is `Idle`, the other row
 * renders nothing — so folding this into it would make the one state that needs an action
 * the one state that is invisible.
 *
 * ⚠️ **[OverlayEndpointStatus.NeedsManual] renders unconditionally, and the rest do not.**
 * That asymmetry is architecture §10.7, not a style choice: the design has **no LAN
 * fallback**, so a discovery failure must fail *visibly* and carry the action the user can
 * take. Hiding it behind a "show diagnostics" flag would leave a user who is off the LAN,
 * with a dead tunnel and working Wi-Fi, looking at an app that simply does not connect and
 * says nothing about why. Every other state is either progress or noise, so those stay
 * behind [showUnavailable].
 */
@Composable
internal fun OverlayEndpointStatusRow(
    modifier: Modifier = Modifier,
    showUnavailable: Boolean = false,
) {
    val current by OverlayEndpointState.status.collectAsState()
    val tokens = MaterialTheme.nuvio

    val text: String? = when (val status = current) {
        // Not started, or the seam is switched off — the same silence the LAN row keeps.
        OverlayEndpointStatus.Idle -> null

        OverlayEndpointStatus.Searching ->
            if (showUnavailable) stringResource(Res.string.overlay_endpoint_searching) else null

        // ⚠️ Silent here on purpose. A rung having found an endpoint is not the same as the
        // app using it — the tunnel still has to come up and answer — and when it does,
        // [LocalServerStatusRow] already reports the address the traffic is actually taking.
        // Saying it twice would give the user two lines that can contradict each other.
        is OverlayEndpointStatus.Found ->
            if (showUnavailable) stringResource(Res.string.overlay_endpoint_found, status.endpoint.authority) else null

        // See the doc: never gated.
        is OverlayEndpointStatus.NeedsManual ->
            stringResource(Res.string.overlay_endpoint_needs_manual, status.reason)

        is OverlayEndpointStatus.Unavailable ->
            if (showUnavailable) stringResource(Res.string.overlay_endpoint_unavailable, status.reason) else null
    }

    if (text == null) return

    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        // Amber for the actionable failure, muted for everything else — so the one line the
        // user can *do* something about does not look like the three they cannot.
        color = if (current is OverlayEndpointStatus.NeedsManual) tokens.colors.warning else tokens.colors.textMuted,
    )
}
