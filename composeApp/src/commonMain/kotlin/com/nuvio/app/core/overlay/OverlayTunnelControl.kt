package com.nuvio.app.core.overlay

import kotlinx.coroutines.flow.StateFlow

/**
 * What the VPN screen is allowed to say about the private tunnel.
 *
 * ⚠️ **A projection, not a second state machine.** The device behind this has exactly one state
 * (`TunnelState`, Android-only); this adds exactly one fact the device cannot know — whether a
 * *person* asked for it to stay down — and the Android actual's `overlayTunnelUiState` is the
 * whole of the relation. Anything that needed to remember more would be two sources of truth,
 * and two sources is how "Disconnect" ends up showing "Connected" while the tunnel is down.
 *
 * Declared here rather than beside the device because the screen that draws it lives in
 * `commonMain` and cannot see the Android overlay package.
 */
sealed interface OverlayTunnelUiState {

    /** Carrying traffic: `up()` succeeded and a handshake completed. */
    data object Connected : OverlayTunnelUiState

    /** The user asked for it down. **Wins over a device that still reports `Up`.** */
    data object Disconnected : OverlayTunnelUiState

    /** Not up, and nobody asked for that: no endpoint yet, or it is still coming up. */
    data object Down : OverlayTunnelUiState

    /**
     * This platform has no tunnel at all. Distinct from [Down], which promises that connecting
     * is merely a matter of time, and from [Failed], which offers a reason worth retrying.
     */
    data object Unsupported : OverlayTunnelUiState

    /** `up()` refused. [reason] is the AAR's own words, shown verbatim so it can be searched. */
    data class Failed(val reason: String) : OverlayTunnelUiState
}

/**
 * The manual Disconnect / Reconnect control, for the VPN screen.
 *
 * Separate from `OverlaySession` (which is Android-only and `internal`) for the same reason
 * [PrivateListeningSession][com.nuvio.app.features.boomio.PrivateListeningSession] exists: the
 * screen is common code and the mechanism is not.
 *
 * Other platforms report [OverlayTunnelUiState.Failed] — not `Down`, which would promise that
 * connecting is merely a matter of time. The tunnel is Android-only, and saying "not connected
 * yet" on a platform that can never connect is the one answer worse than "not available here".
 */
expect object OverlayTunnelControl {
    val state: StateFlow<OverlayTunnelUiState>

    /** Tear the tunnel down and keep it down until [reconnect]. */
    fun disconnect()

    /** Undo [disconnect] and rebuild the tunnel. */
    fun reconnect()
}
