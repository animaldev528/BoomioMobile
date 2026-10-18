package com.nuvio.app.core.overlay

import kotlinx.coroutines.flow.StateFlow

/**
 * Android actual: the tunnel is the in-process userspace WireGuard device, so the screen's
 * control is [OverlaySession]'s.
 */
actual object OverlayTunnelControl {
    actual val state: StateFlow<OverlayTunnelUiState>
        get() = OverlaySession.connectionState

    actual fun disconnect() {
        OverlaySession.disconnect()
    }

    actual fun reconnect() {
        OverlaySession.reconnect()
    }
}

/**
 * Projects the device plus the user's choice onto one displayable state.
 *
 * ⚠️ **The manual flag is checked first, before `Up`.** The teardown is asynchronous — the flag
 * is set on the caller's thread and `down()` runs on a background scope — so between those two
 * moments the device genuinely reports `Up`. Reading that as "Connected" for a frame would flip
 * the button back to "Disconnect" under the user's finger, which reads as the press having
 * failed. The user's intent is the earlier and stronger fact, so it wins.
 */
internal fun overlayTunnelUiState(
    state: TunnelState,
    manuallyDisconnected: Boolean,
): OverlayTunnelUiState = when {
    manuallyDisconnected -> OverlayTunnelUiState.Disconnected
    state is TunnelState.Up -> OverlayTunnelUiState.Connected
    state is TunnelState.Failed -> OverlayTunnelUiState.Failed(state.reason)
    else -> OverlayTunnelUiState.Down
}
