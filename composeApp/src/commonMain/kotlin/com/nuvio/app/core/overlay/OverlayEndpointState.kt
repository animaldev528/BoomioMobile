package com.nuvio.app.core.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which rung of the discovery ladder produced an endpoint.
 *
 * ⚠️ **The declaration order is the rung order**, and it is the order the ladder tries them
 * in. Unlike [LocalServerSource], nothing here ranks concurrent answers — the ladder stops at
 * the first rung that works, so at most one of these is ever in flight — but the order is
 * still the specification, and [OverlayEndpointStatus.Found] reports which rung won so a
 * support log can say *how* the app found the server rather than only that it did.
 */
enum class OverlayEndpointSource {
    /** Rung 1 — the mDNS advert `_boomio-overlay._udp`, on the server's own network. */
    MDNS,

    /** Rung 2 — the `boomio-local` DNS record, when mDNS is blocked or unavailable. */
    LOCAL_DNS,

    /**
     * Rung 3 — a person typed it: the in-session prompt, or a value already persisted in
     * settings. The two are the same rung because they are the same fact — a human supplied
     * this endpoint — and splitting them would only add a state with no distinct behaviour.
     */
    MANUAL,

    /**
     * The two names the server publishes, climbed **only after every rung has missed the gate**.
     *
     * ⚠️ **This is not a fourth rung, and the order above is what says so.** The three rungs
     * answer "where is the server?"; this answers "the answer I had has gone stale", which is a
     * state only the gate can detect. It fires when rung 1 has nothing to say *and* the address
     * rung 3 is still holding does not answer on the current network — which is exactly the phone
     * having left the house. It is declared last because it is tried last, and because an endpoint
     * that came from a name must be distinguishable in a support log from one a human typed.
     *
     * See `OverlayDiscoveryNames` for what the names are and why they are a pair.
     */
    DISCOVERY_NAME,
}

/**
 * A WireGuard endpoint the userspace tunnel can be brought up against.
 *
 * This is the **whole tuple**, not just an address: [host] and [port] say where the UDP goes,
 * and [serverPublicKeyBase64] is what makes a handshake possible at all. An `A` record alone
 * solves half the problem — see architecture §4.4.
 *
 * [serverPublicKeyBase64] is nullable because rung 3 has to be able to represent "the user
 * typed an address and did not know the key". A null key is not usable for a handshake, and
 * [OverlayEndpoint.isUsable] says so; the ladder treats such an endpoint as a *candidate*
 * that must be completed, never as a working answer.
 *
 * ⚠️ **[host] is a literal address, never a name.** The endpoint string is handed to
 * `OverlayWgTunnel.up` and from there to `IpcSet`'s `endpoint=` **verbatim**, so a name here
 * would be resolved by wireguard-go's own resolver inside a gomobile AAR — where Go's resolver
 * looks for an `/etc/resolv.conf` that Android does not have. `OverlayEndpointDiscovery`
 * resolves before it publishes, for exactly that reason, and it means the host the tunnel
 * dials is the same one whose reachability was just tested.
 */
data class OverlayEndpoint(
    val host: String,
    val port: Int,
    val serverPublicKeyBase64: String?,
    val source: OverlayEndpointSource,
) {
    /** `host:port`, the form `BoomioConfig.overlayEndpoint` and the WG binding both take. */
    val authority: String get() = "$host:$port"

    /**
     * True when this endpoint could actually bring a tunnel up.
     *
     * A reachable host with no server public key is a dead end that looks like progress, which
     * is exactly the shape of bug this property exists to make impossible to write by accident.
     */
    val isUsable: Boolean
        get() = host.isNotBlank() && port in 1..65535 && !serverPublicKeyBase64.isNullOrBlank()
}

/**
 * What the discovery ladder has to say.
 *
 * [NeedsManual] is a first-class state rather than an [Unavailable] with a particular message,
 * because it is the only one with an **action** attached: the ladder has exhausted its
 * automatic rungs and is waiting for a person. Architecture §10.7 makes that a requirement, not
 * a nicety — with no LAN fallback, a failure to establish the tunnel fails visibly, and this is
 * the state that says what the user can do about it.
 */
sealed interface OverlayEndpointStatus {
    /** Nothing has run yet, or the seam is switched off. Silent in the UI. */
    data object Idle : OverlayEndpointStatus

    /** A rung is being tried. */
    data object Searching : OverlayEndpointStatus

    /** An endpoint was found **and passed the reachability gate**. */
    data class Found(val endpoint: OverlayEndpoint) : OverlayEndpointStatus

    /**
     * Every automatic rung missed. [reason] is shown to the user, so it must read as an
     * explanation and should name the rungs that were tried.
     */
    data class NeedsManual(val reason: String) : OverlayEndpointStatus

    /** The seam cannot run here at all — e.g. discovery was never initialised. */
    data class Unavailable(val reason: String) : OverlayEndpointStatus
}

/**
 * The single source of truth for [OverlayEndpointStatus].
 *
 * Shaped like [LocalServerState] on purpose, and for the same reason: the writer is Android
 * platform code and the reader is common UI, so an `expect`/`actual` pair would demand a stub
 * in every one of `AppFeaturePolicy`'s five actuals to serve one platform.
 *
 * ⚠️ **One value, not one per source** — the opposite of [LocalServerState], and the difference
 * is the point. Those two sources run *concurrently* and both can hold a pin, so they need
 * separate slots. These are *rungs of one ladder*, tried in sequence, stopping at the first
 * success: a later rung never has an opinion while an earlier one is holding, so a second slot
 * could only ever be empty.
 */
object OverlayEndpointState {
    private val mutable = MutableStateFlow<OverlayEndpointStatus>(OverlayEndpointStatus.Idle)

    val status: StateFlow<OverlayEndpointStatus> = mutable.asStateFlow()

    /** Called by platform code. Not for UI. */
    fun update(value: OverlayEndpointStatus) {
        mutable.value = value
    }

    /** Tests only — production code always moves to another state. */
    fun reset() {
        mutable.value = OverlayEndpointStatus.Idle
    }
}
