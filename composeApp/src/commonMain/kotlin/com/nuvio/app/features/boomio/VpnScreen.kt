package com.nuvio.app.features.boomio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.overlay.OverlayEndpointState
import com.nuvio.app.core.overlay.OverlayEndpointStatus
import com.nuvio.app.core.overlay.OverlayEndpointStatusRow
import com.nuvio.app.core.overlay.OverlayTunnelControl
import com.nuvio.app.core.overlay.OverlayTunnelUiState
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_vpn
import nuvio.composeapp.generated.resources.vpn_intro
import nuvio.composeapp.generated.resources.vpn_label_address
import nuvio.composeapp.generated.resources.vpn_label_device_name
import nuvio.composeapp.generated.resources.vpn_label_endpoint
import nuvio.composeapp.generated.resources.vpn_label_overlay_address
import nuvio.composeapp.generated.resources.vpn_label_server_key
import nuvio.composeapp.generated.resources.vpn_not_enrolled_body
import nuvio.composeapp.generated.resources.vpn_not_enrolled_title
import nuvio.composeapp.generated.resources.vpn_private_key_note
import nuvio.composeapp.generated.resources.vpn_section_endpoint
import nuvio.composeapp.generated.resources.vpn_section_interface
import nuvio.composeapp.generated.resources.vpn_section_peer
import nuvio.composeapp.generated.resources.vpn_tunnel_connected
import nuvio.composeapp.generated.resources.vpn_tunnel_disconnect
import nuvio.composeapp.generated.resources.vpn_tunnel_disconnected
import nuvio.composeapp.generated.resources.vpn_tunnel_down
import nuvio.composeapp.generated.resources.vpn_tunnel_failed
import nuvio.composeapp.generated.resources.vpn_tunnel_reconnect
import nuvio.composeapp.generated.resources.vpn_tunnel_title
import nuvio.composeapp.generated.resources.vpn_tunnel_unsupported
import nuvio.composeapp.generated.resources.vpn_value_unset
import org.jetbrains.compose.resources.stringResource

/**
 * The WireGuard details this device is using to reach the box.
 *
 * Read-only on purpose. What it reports is *learned*, not configured: the device name, the tunnel
 * address and the server's public key are written by enrollment from what the server assigned, and
 * the overlay's own address is derived rather than sent. A screen that let someone type over them
 * would be offering an edit that the next enrollment silently reverts — and, worse, an address the
 * server never agreed to, which comes up as a handshake that succeeds and then drops every return
 * packet. So this screen reports; the things that change these values are pairing a device and the
 * endpoint ladder, and both live elsewhere.
 *
 * ⚠️ **The endpoint is shown twice, and the two are not the same fact.** [vpn_section_endpoint]
 * renders [OverlayEndpointStatusRow] — the *ladder's* answer to "where is the box", which is
 * discovered (mDNS, then the DuckDNS record, then an address a person types) and is the value that
 * can actually bring a tunnel up. [vpn_label_endpoint] is [BoomioConfig.overlayEndpoint], what this
 * *build* was compiled with, which on most builds is blank and inert. Reading one for the other is
 * the mistake this layout exists to prevent, so neither is labelled "Endpoint" alone.
 *
 * ⚠️ **Enrolment is [BoomioConfig.overlayDeviceName] being non-blank, not the endpoint.** The name
 * is the one field the server only ever writes through an enrollment, so it is the honest signal; a
 * build with a baked endpoint and no enrollment would otherwise claim to be enrolled, and the
 * address and key below would be build defaults rather than this device's.
 *
 * ⚠️ **The private key is deliberately absent, and its absence is the one thing here worth stating
 * out loud.** It is generated on the device and never leaves it — the server is handed the public
 * half only (`POST /api/overlay/enroll`) — so there is nothing to read back and no reason to put a
 * secret on a screen that screenshots easily. [vpn_private_key_note] says so, because a VPN screen
 * that lists everything *except* the key otherwise reads as one that is hiding something.
 *
 * ⚠️ **Blank is a real state, not an error.** A build with no deployment baked in and no enrollment
 * yet has every field empty (`overlayEndpoint` blank is exactly what "the tunnel is inert" means
 * downstream), so the screen says "not enrolled" and still renders the tables — an empty card would
 * be indistinguishable from a broken one.
 */
@Composable
fun VpnScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enrolled = BoomioConfig.overlayDeviceName.trim().isNotEmpty()
    val status by OverlayEndpointState.status.collectAsState()
    val tunnel by OverlayTunnelControl.state.collectAsState()

    NuvioScreen(modifier = modifier) {
        stickyHeader {
            NuvioScreenHeader(
                title = stringResource(Res.string.compose_settings_page_vpn),
                onBack = onBack,
            )
        }
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(Res.string.vpn_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!enrolled) {
                    NuvioSurfaceCard {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(Res.string.vpn_not_enrolled_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(Res.string.vpn_not_enrolled_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Above the endpoint card on purpose: this is the control, and the cards
                // below are the facts it acts on. Leading with the diagnosis would leave the
                // one thing the screen can *do* below the fold on a phone.
                //
                // Hidden entirely on a platform with no tunnel. A card saying "unsupported"
                // above a working endpoint is noise; there is nothing here for that user.
                if (tunnel != OverlayTunnelUiState.Unsupported) {
                    VpnCard(title = stringResource(Res.string.vpn_tunnel_title)) {
                        Text(
                            text = overlayTunnelText(tunnel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                // Real teardown, remembered across restarts -- see
                                // OverlaySession. The projection above is what comes back.
                                if (tunnel is OverlayTunnelUiState.Connected) {
                                    OverlayTunnelControl.disconnect()
                                } else {
                                    OverlayTunnelControl.reconnect()
                                }
                            },
                            // Nothing to reconnect to before the ladder has any answer at all.
                            // Once the user disconnected by hand it stays live even from
                            // `Idle`: Reconnect re-walks the ladders, so it is their retry.
                            enabled = tunnel !is OverlayTunnelUiState.Down ||
                                status != OverlayEndpointStatus.Idle,
                        ) {
                            Text(
                                // The label is the action, not the state: a button reading
                                // "Connected" is one you press to find out what happens.
                                text = if (tunnel is OverlayTunnelUiState.Connected) {
                                    stringResource(Res.string.vpn_tunnel_disconnect)
                                } else {
                                    stringResource(Res.string.vpn_tunnel_reconnect)
                                },
                            )
                        }
                    }
                }

                // The ladder's own row, not a copy of it: `NeedsManual` is the one state that
                // carries an action, and that row is the only thing that renders the field which
                // performs it. Hidden while the ladder is `Idle` — a platform that never started it
                // has nothing to say, and an empty card would read as a broken one.
                if (status != OverlayEndpointStatus.Idle) {
                    VpnCard(title = stringResource(Res.string.vpn_section_endpoint)) {
                        OverlayEndpointStatusRow(showUnavailable = true)
                    }
                }

                VpnDetailCard(
                    title = stringResource(Res.string.vpn_section_interface),
                    rows = listOf(
                        stringResource(Res.string.vpn_label_device_name) to BoomioConfig.overlayDeviceName,
                        stringResource(Res.string.vpn_label_address) to BoomioConfig.overlayLocalCidr,
                        stringResource(Res.string.vpn_label_overlay_address) to BoomioConfig.overlayServerAddress,
                    ),
                )

                VpnDetailCard(
                    title = stringResource(Res.string.vpn_section_peer),
                    rows = listOf(
                        stringResource(Res.string.vpn_label_endpoint) to BoomioConfig.overlayEndpoint,
                        stringResource(Res.string.vpn_label_server_key) to BoomioConfig.overlayServerPubKey,
                    ),
                )

                Text(
                    text = stringResource(Res.string.vpn_private_key_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What the Private tunnel card says about the tunnel.
 *
 * ⚠️ **`Disconnected` and `Down` are different sentences on purpose.** `Down` is the app's
 * answer before anything has happened — a cold start, or enrollment that has not supplied an
 * address — and telling someone "you disconnected" when they did not would send them looking
 * for a switch they never touched. Only [OverlayTunnelUiState.Disconnected] means a person
 * asked for this, and only that one promises the state will hold.
 *
 * ⚠️ **The failure reason is shown verbatim.** It is the AAR's own words for why `up()`
 * refused; paraphrasing it would make it unsearchable in a log or an issue.
 */
@Composable
private fun overlayTunnelText(state: OverlayTunnelUiState): String = when (state) {
    OverlayTunnelUiState.Connected -> stringResource(Res.string.vpn_tunnel_connected)
    OverlayTunnelUiState.Disconnected -> stringResource(Res.string.vpn_tunnel_disconnected)
    OverlayTunnelUiState.Down -> stringResource(Res.string.vpn_tunnel_down)
    OverlayTunnelUiState.Unsupported -> stringResource(Res.string.vpn_tunnel_unsupported)
    is OverlayTunnelUiState.Failed ->
        stringResource(Res.string.vpn_tunnel_failed, state.reason)
}

/** A titled card whose body is arbitrary — the endpoint row brings its own layout. */
@Composable
private fun VpnCard(
    title: String,
    content: @Composable () -> Unit,
) {
    NuvioSurfaceCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            content()
        }
    }
}

/**
 * One titled group of label/value pairs.
 *
 * Values are monospaced because every one of them is a machine token someone may need to compare
 * character by character — a base64 key, an address, a `host:port`. Proportional digits make two
 * keys that differ in one character look identical at a glance, which is the one mistake this
 * screen exists to make impossible.
 */
@Composable
private fun VpnDetailCard(
    title: String,
    rows: List<Pair<String, String>>,
) {
    // Resolved once, outside the loop: `stringResource` is @Composable and cannot be called from
    // inside a lambda that is not.
    val unset = stringResource(Res.string.vpn_value_unset)

    VpnCard(title = title) {
        rows.forEach { (label, value) ->
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value.trim().ifEmpty { unset },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
