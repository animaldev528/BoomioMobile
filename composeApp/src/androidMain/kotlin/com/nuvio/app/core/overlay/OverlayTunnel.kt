package com.nuvio.app.core.overlay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.boomio.BoomioConfig
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "OverlayTunnel"

/**
 * Pins the app's public FQDNs to the server's **overlay** address while the tunnel is up.
 *
 * This is the Tier 2 half of the overlay, and the second source feeding
 * [OverlayPinRegistry]. Tier 1 ([OverlayLocalDiscovery]) needs the phone to be on the
 * server's own network; this one is for everywhere else — the phone on cellular, the TV
 * at a friend's house — where the server is reached through WireGuard at `10.77.0.1`.
 *
 * **What it changes, and what it deliberately does not.** The server keeps emitting
 * `https://bss-tor.tracemonkey.org/…` forever (see §6.2 option D). Nothing here rewrites
 * a URL; it changes what that name *resolves to*. That is not a shortcut but a
 * requirement: Caddy holds certificates **per named site block**, so an overlay address
 * used as a URL has no site block to match it. Reaching `10.77.0.1:443` with the name
 * `bss-tor.tracemonkey.org` serves correctly — verified live against the server with
 * `curl --resolve` and `openssl -servername`, both returning a valid Let's Encrypt chain
 * — and reaching it with the bare address does not, at any point in the stack.
 *
 * ⚠️ **There is no `VpnService` here, and that is the point.** This object does not
 * create or own a tunnel; it observes one. Android grants exactly one active
 * `VpnService`, and coexistence with a VPN the user already runs (NordVPN) is a hard
 * requirement, so the app cannot take that slot. The tunnel is whatever the platform —
 * or the user's WireGuard app — has already established, and all this does is notice it
 * and point DNS at it. When no such tunnel exists, this reports [LocalServerStatus.Idle]
 * and the app falls back to the public edge, which still works.
 *
 * **The gate is the probe, not the interface.** Detecting "a VPN is up" is not enough:
 * NordVPN also satisfies that, and it routes nothing the app wants. So the identifying
 * signal is the VPN link's own address lying on the overlay subnet, and the confirming
 * signal is a real TCP connect to the server's overlay address. A pin is placed only
 * when both hold.
 */
internal object OverlayTunnel {

    /**
     * The edge port, not the WireGuard port.
     *
     * The tunnel listens on udp/51820, but nothing here speaks to it. What is being
     * checked is whether the *server's Caddy* is reachable through the tunnel, which is
     * where every request the app makes actually goes.
     */
    private const val EDGE_PORT = 443
    private const val PROBE_TIMEOUT_MS = 900

    /**
     * A WireGuard handshake completes about a second after the interface comes up, and
     * the network callback fires at the interface, not at the handshake. Without the
     * retry the first, natural probe lands in that gap, reports the tunnel unreachable,
     * and — because the next trigger is the foreground TTL — leaves the app on the
     * public edge for up to [RESULT_TTL_MS] afterwards.
     */
    private const val PROBE_ATTEMPTS = 3
    private const val PROBE_RETRY_DELAY_MS = 1_000L

    /** See [OverlayLocalDiscovery.RESULT_TTL_MS]: the same foreground cadence. */
    private const val RESULT_TTL_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeMutex = Mutex()

    private var appContext: Context? = null

    @Volatile
    private var lastProbedAtMs = 0L

    private var lifecycleStarted = false

    /**
     * The address the last successful probe pinned, or null when nothing is pinned.
     *
     * A flow rather than a field for the same reason [OverlayLocalDiscovery] uses one:
     * the host set is not fixed at probe time. The addon catalogue, where most of the
     * app's server hosts live, loads asynchronously and can land before or after the pin,
     * so the pin has to be re-applied when the set widens — in either order.
     */
    private val pinnedAddress = MutableStateFlow<InetAddress?>(null)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        // Idempotent: onCreate can run again after a configuration-forced restart, and a
        // second set of observers would double every probe.
        if (lifecycleStarted) return
        lifecycleStarted = true
        observeVpnNetworks()
        observeForeground()
        observeServerChanges()
        observeAddonChanges()
    }

    /**
     * Re-probes whenever a VPN network appears, disappears, or changes.
     *
     * A `NetworkRequest` on `TRANSPORT_VPN` rather than the *default* network callback:
     * a split-tunnel VPN that carries only the overlay subnet never becomes the default
     * network, so the default callback would never fire for the case this exists to
     * catch. This one fires on the interface itself.
     *
     * `onLinkPropertiesChanged` is included because that is when the tunnel's address
     * and routes are actually installed — `onAvailable` can precede them, and probing
     * before the route exists is a probe that fails for a reason that is about to stop
     * being true.
     */
    private fun observeVpnNetworks() {
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onTunnelEvent()
            override fun onLost(network: Network) = onTunnelEvent()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                onTunnelEvent()
        }
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "Could not observe VPN networks", it) }
    }

    /**
     * Re-decides rather than clearing immediately.
     *
     * The LAN tier clears its pin the moment the network changes, because a LAN address
     * is meaningless on a different network. An overlay address is not: it is the same
     * address on every network, and it stays valid across a Wi-Fi-to-cellular handover —
     * which is exactly the case the tunnel exists for. Clearing on every link-properties
     * event would drop a working pin during ordinary roaming, so the probe decides
     * instead. A tunnel that is genuinely gone fails the probe, and the pin clears then.
     */
    private fun onTunnelEvent() {
        lastProbedAtMs = 0L
        refreshAsync()
    }

    /** Re-probes on foreground, at most once per [RESULT_TTL_MS]. */
    private fun observeForeground() {
        scope.launch {
            AppForegroundMonitor.events()
                // `events()` is a `callbackFlow` whose block calls
                // `ProcessLifecycleOwner.lifecycle.addObserver`, and Android requires
                // that on the main thread — collecting it straight from this object's IO
                // scope throws on the *first* collection, which would kill the app on
                // launch. `flowOn` moves only the upstream; the body below stays on IO.
                .flowOn(Dispatchers.Main.immediate)
                .collect { visibility ->
                    if (visibility != AppVisibility.Foreground) return@collect
                    if (SystemClock.elapsedRealtime() - lastProbedAtMs < RESULT_TTL_MS) return@collect
                    refresh()
                }
        }
    }

    /**
     * Re-derives the host set when the configured server changes.
     *
     * The pin is host-scoped, so switching servers changes what may be pinned at all —
     * and the overlay address belongs to the server that was configured, not to whoever
     * is configured now.
     */
    private fun observeServerChanges() {
        scope.launch {
            ServerConfigurationRepository.active
                .map { it.backendUrl }
                .distinctUntilChanged()
                // The value already in the flow at assembly is not a change.
                .drop(1)
                .collect {
                    lastProbedAtMs = 0L
                    refresh()
                }
        }
    }

    /**
     * Re-applies the pin when the *host set* widens under a pin already in place.
     *
     * The same race [OverlayLocalDiscovery] documents, and it is worth restating because
     * it is invisible from this side: the addon catalogue is where most of the app's
     * server hosts live, it loads seconds after launch, and it can beat the pin. A
     * collector that read `pinnedAddress` *inside* itself would drop that emission
     * permanently — the mapped host set is `distinctUntilChanged`, so a dropped value is
     * never revisited. [combine] makes the order irrelevant; whichever changes last
     * produces the pair.
     *
     * Deliberately does **not** re-probe: the tunnel address has not changed, only the
     * list of hosts that should use it.
     */
    private fun observeAddonChanges() {
        scope.launch {
            combine(
                AddonRepository.uiState.map { localServerHosts() }.distinctUntilChanged(),
                pinnedAddress,
            ) { hosts, address -> hosts to address }
                .collect { (hosts, address) ->
                    if (hosts.isNotEmpty() && address != null) {
                        OverlayPinRegistry.pin(LocalServerSource.TUNNEL, hosts, address)
                    }
                }
        }
    }

    /** Fire-and-forget [refresh] for callers that are not themselves coroutines. */
    fun refreshAsync() {
        scope.launch { refresh() }
    }

    /** Drops the tunnel pin without probing. */
    private fun clear() {
        pinnedAddress.value = null
        OverlayPinRegistry.clear(LocalServerSource.TUNNEL)
    }

    /**
     * Decides what the tunnel can offer right now, and publishes it.
     *
     * Safe to call repeatedly; concurrent calls serialise rather than racing the probe.
     */
    suspend fun refresh() = probeMutex.withLock {
        // Stamped on entry, not on success: a probe that finds nothing is still a probe.
        lastProbedAtMs = SystemClock.elapsedRealtime()

        val context = appContext
        if (context == null) {
            publish(LocalServerStatus.Idle)
            return@withLock
        }

        val server = parseOverlayAddress(BoomioConfig.overlayServerAddress)
        if (server == null) {
            // Not configured: the tier is simply not in use, which is not a failure and
            // must stay silent. This is the blank-inert pattern the rest of BoomioConfig
            // follows, and it is what keeps the feature off for anyone not running an
            // overlay.
            publish(LocalServerStatus.Idle)
            return@withLock
        }

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null || !hasOverlayTunnel(manager, server)) {
            // No VPN carrying the overlay subnet. Either the user runs no tunnel at all,
            // or they run a different VPN — NordVPN on the same phone, which satisfies
            // "a VPN is up" and routes nothing this app wants. Both are ordinary states,
            // not errors, so neither is reported as one.
            publish(LocalServerStatus.Idle)
            return@withLock
        }

        val hosts = localServerHosts()
        if (hosts.isEmpty()) {
            publish(LocalServerStatus.Unavailable("No boomio server is configured"))
            return@withLock
        }

        if (probeWithRetry(server)) {
            pinnedAddress.value = server
            OverlayPinRegistry.pin(LocalServerSource.TUNNEL, hosts, server)
            LocalServerState.update(
                LocalServerSource.TUNNEL,
                LocalServerStatus.Found(address = server.hostAddress.orEmpty(), source = LocalServerSource.TUNNEL),
            )
            Log.d(TAG, "Overlay tunnel up; pinned $hosts -> ${server.hostAddress}")
        } else {
            // The tunnel is up and the server did not answer. That is a real fault —
            // the handshake succeeded but nothing is being served — so it is reported
            // rather than swallowed.
            publish(LocalServerStatus.Unavailable("The overlay tunnel is up but the server did not answer"))
        }
    }

    private fun publish(status: LocalServerStatus) {
        // Any status that is not Found means there is nothing to pin. Without this a
        // failed probe would leave the previous pin in place, and the app would keep
        // resolving to an address the probe has just said is dead.
        if (status !is LocalServerStatus.Found) clear()
        LocalServerState.update(LocalServerSource.TUNNEL, status)
    }

    private suspend fun probeWithRetry(address: InetAddress): Boolean {
        repeat(PROBE_ATTEMPTS) { attempt ->
            if (isReachable(address)) return true
            if (attempt < PROBE_ATTEMPTS - 1) delay(PROBE_RETRY_DELAY_MS)
        }
        return false
    }

    /** Blocking; the caller runs it on an IO dispatcher. */
    private fun isReachable(address: InetAddress): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, EDGE_PORT), PROBE_TIMEOUT_MS)
                true
            }
        }.onFailure {
            Log.w(
                TAG,
                "Overlay probe to ${address.hostAddress}:$EDGE_PORT failed after " +
                    "${SystemClock.elapsedRealtime() - startedAt}ms",
                it,
            )
        }.getOrDefault(false)
    }

    /**
     * True when some up VPN network is carrying the overlay subnet.
     *
     * The subnet is taken from [server]'s first three octets — the POC's `10.77.0.0/24`,
     * in which the server is `10.77.0.1` and the phone `10.77.0.2`. That is a property of
     * this deployment, not a general truth, and the failure it can produce is bounded and
     * safe: a deployment on a different overlay subnet is not recognised, so the tier
     * reports [LocalServerStatus.Idle] and the app uses the public edge, which works. A
     * wrong answer here is never a wrong pin.
     */
    private fun hasOverlayTunnel(manager: ConnectivityManager, server: InetAddress): Boolean =
        runCatching {
            manager.allNetworks.any { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@any false
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
                val link = manager.getLinkProperties(network) ?: return@any false
                link.linkAddresses.map { it.address }.sharesOverlaySubnetWith(server)
            }
        }.onFailure { Log.w(TAG, "Could not enumerate networks", it) }.getOrDefault(false)
}

/**
 * The configured overlay address, or null when it is blank or not a literal IPv4 address.
 *
 * ⚠️ **Literal-only on purpose.** `InetAddress.getByName` resolves a *name* through the
 * system resolver, which is a blocking DNS call — and this value is read on every probe,
 * including from the foreground observer. It is also the wrong failure: a hostname here
 * would put the resolution back in the system's hands, which is the one thing this whole
 * seam exists to take away. A non-literal is treated as "not configured".
 */
internal fun parseOverlayAddress(raw: String): InetAddress? {
    val text = raw.trim()
    val parts = text.split('.')
    if (parts.size != 4) return null
    val octets = ByteArray(4)
    for (index in 0 until 4) {
        val value = parts[index].toIntOrNull() ?: return null
        if (value !in 0..255) return null
        // A leading zero is not a valid dotted-quad octet, and `toIntOrNull` would
        // accept `010` as ten — which is a different address than it looks like.
        if (parts[index].length > 1 && parts[index].startsWith("0")) return null
        octets[index] = value.toByte()
    }
    return runCatching { InetAddress.getByAddress(octets) }.getOrNull()
        ?.takeIf { it is Inet4Address }
}

/**
 * True when one of [this] lies on the same /24 as [server].
 *
 * A VPN link address is the discriminator between the overlay tunnel and any other VPN
 * the user might be running: the overlay puts the client at `10.77.0.2`, so a tunnel
 * carrying it is ours, while NordVPN's interface is somewhere else entirely.
 */
internal fun List<InetAddress>.sharesOverlaySubnetWith(server: InetAddress): Boolean {
    val target = server.address
    if (target.size != 4) return false
    return any { address ->
        val candidate = address.address
        candidate.size == 4 &&
            candidate[0] == target[0] &&
            candidate[1] == target[1] &&
            candidate[2] == target[2]
    }
}
