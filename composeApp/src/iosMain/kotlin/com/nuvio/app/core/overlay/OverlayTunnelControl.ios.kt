package com.nuvio.app.core.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS actual: there is no overlay tunnel on this platform — the userspace WireGuard device is
 * an Android AAR — so the screen reports [OverlayTunnelUiState.Unsupported] and draws no button.
 *
 * ⚠️ **`Unsupported`, not `Down` and not `Failed`.** `Down` says "not connected yet", which
 * promises a connection is coming; `Failed` offers a reason and a Reconnect worth pressing.
 * Neither is true here, and both would send someone hunting for a fault that does not exist.
 */
actual object OverlayTunnelControl {
    private val _state = MutableStateFlow<OverlayTunnelUiState>(OverlayTunnelUiState.Unsupported)

    actual val state: StateFlow<OverlayTunnelUiState> = _state.asStateFlow()

    actual fun disconnect() = Unit

    actual fun reconnect() = Unit
}
