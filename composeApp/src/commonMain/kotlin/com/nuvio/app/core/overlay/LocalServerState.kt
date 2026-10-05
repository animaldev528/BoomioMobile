package com.nuvio.app.core.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a boomio server has been found on the local network.
 *
 * This is the Tier 1 half of the overlay: on a LAN where system DNS does not point
 * at the server — it moved, the router changed — the app finds it by mDNS and pins
 * the address, while every URL keeps naming the same public FQDN. The tier is
 * deliberately tunnel-free, because Android allows a single active `VpnService` and
 * the slot belongs to whatever VPN the user chose.
 *
 * [Unavailable] is a first-class state rather than an absence of [Found]: "discovery
 * never ran" and "discovery ran and found nothing" are different failures, and a UI
 * that renders both as blank cannot tell the user which one to fix.
 */
sealed interface LocalServerStatus {
    /** No browse has completed yet. The initial state, and the state after a clear. */
    data object Idle : LocalServerStatus

    /** A browse is in flight. */
    data object Searching : LocalServerStatus

    /**
     * A server was found and pinned. The public FQDN now resolves to [address] for
     * the hosts this app talks to.
     */
    data class Found(
        val address: String,
        val serviceName: String?,
        val hostName: String?,
        val version: String?,
    ) : LocalServerStatus

    /**
     * Discovery cannot be used. [reason] is shown to the user, so it must read as an
     * explanation rather than a code — e.g. "not on Wi-Fi".
     */
    data class Unavailable(val reason: String) : LocalServerStatus
}

/**
 * The single source of truth for [LocalServerStatus].
 *
 * A concrete `object` in commonMain rather than `expect`/`actual`: `AppFeaturePolicy`
 * carries five actuals (androidFull, androidPlaystore, iosFull, iosAppStore, desktop),
 * so an `expect object` for an Android-only feature would demand a stub in every one
 * for no benefit. This is the same shape [com.nuvio.app.core.network.ServerConfigurationRepository]
 * already uses — commonMain owns the flow and reads it; androidMain writes it.
 */
object LocalServerState {
    private val _status = MutableStateFlow<LocalServerStatus>(LocalServerStatus.Idle)
    val status: StateFlow<LocalServerStatus> = _status.asStateFlow()

    /** Called by platform code. Not for UI. */
    fun update(value: LocalServerStatus) {
        _status.value = value
    }
}
