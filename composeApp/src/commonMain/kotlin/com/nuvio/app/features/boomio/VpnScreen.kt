package com.nuvio.app.features.boomio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_vpn
import nuvio.composeapp.generated.resources.vpn_intro
import nuvio.composeapp.generated.resources.vpn_label_address
import nuvio.composeapp.generated.resources.vpn_label_device_name
import nuvio.composeapp.generated.resources.vpn_label_dns
import nuvio.composeapp.generated.resources.vpn_label_endpoint
import nuvio.composeapp.generated.resources.vpn_label_server_key
import nuvio.composeapp.generated.resources.vpn_not_enrolled_body
import nuvio.composeapp.generated.resources.vpn_not_enrolled_title
import nuvio.composeapp.generated.resources.vpn_private_key_note
import nuvio.composeapp.generated.resources.vpn_section_interface
import nuvio.composeapp.generated.resources.vpn_section_peer
import nuvio.composeapp.generated.resources.vpn_value_unset
import org.jetbrains.compose.resources.stringResource

/**
 * The WireGuard details this device is using to reach the box.
 *
 * Read-only on purpose. Everything here is *learned*, not configured: [BoomioConfig.overlayEndpoint],
 * [BoomioConfig.overlayLocalCidr] and [BoomioConfig.overlayServerPubKey] are written by enrollment
 * from what the server assigned, and the overlay CIDR's first host is derived rather than sent. A
 * screen that let someone type over them would be offering an edit that the next enrollment silently
 * reverts — and, worse, an address the server never agreed to, which comes up as a handshake that
 * succeeds and then drops every return packet. So this screen reports; the things that change these
 * values are pairing a device and the endpoint ladder, and both live elsewhere.
 *
 * ⚠️ **The private key is deliberately absent, and its absence is the one thing here worth stating
 * out loud.** It is generated on the device and never leaves it — the server is handed the public
 * half only (`POST /api/overlay/enroll`) — so there is nothing to read back and no reason to put a
 * secret on a screen that screenshots easily. [vpn_private_key_note] says so, because a VPN screen
 * that lists everything *except* the key otherwise reads as one that is hiding something.
 *
 * ⚠️ **Blank is a real state, not an error.** A build with no deployment baked in and no enrollment
 * yet has every field empty ([overlayEndpoint] blank is exactly what "the tunnel is inert" means
 * downstream), so the screen says "not enrolled" and still renders the table — an empty card would
 * be indistinguishable from a broken one.
 */
@Composable
fun VpnScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val endpoint = BoomioConfig.overlayEndpoint.trim()
    val enrolled = endpoint.isNotEmpty()

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

                VpnDetailCard(
                    title = stringResource(Res.string.vpn_section_interface),
                    rows = listOf(
                        stringResource(Res.string.vpn_label_device_name) to BoomioConfig.overlayDeviceName,
                        stringResource(Res.string.vpn_label_address) to BoomioConfig.overlayLocalCidr,
                        stringResource(Res.string.vpn_label_dns) to BoomioConfig.overlayServerAddress,
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

    NuvioSurfaceCard {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
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
}
