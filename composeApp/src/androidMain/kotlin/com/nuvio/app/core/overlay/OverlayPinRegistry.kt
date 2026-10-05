package com.nuvio.app.core.overlay

import android.util.Log
import java.net.InetAddress

/**
 * The LAN address a discovered boomio server is pinned to, keyed by public FQDN.
 *
 * The pin is **address-only and host-scoped**. It never rewrites a URL: Caddy serves
 * certificates per named site block, so `https://192.168.68.65/` and
 * `https://beamstream.local/` both fail TLS. Only the *resolution* of the FQDN
 * changes, which is why this is a DNS seam and not a base-URL swap.
 *
 * Kept as a single immutable map behind `@Volatile` rather than a computed value, so
 * a `lookup` on a network thread reads one consistent snapshot and never blocks. The
 * values are [InetAddress] rather than strings so a link-local scope id survives the
 * round trip.
 */
internal object OverlayPinRegistry {

    private const val TAG = "OverlayPinRegistry"

    @Volatile
    private var pins: Map<String, InetAddress> = emptyMap()

    fun snapshot(): Map<String, InetAddress> = pins

    /**
     * True when [url]'s host is currently pinned.
     *
     * This is the gate that decides whether a connection may use a pin at all. A pin
     * is only ever safe to follow under real certificate validation, so callers that
     * cannot validate must not consult it — see the two clients in
     * `PlayerPlaybackNetworking`.
     */
    fun isPinnedHost(url: String): Boolean = hostOf(url)?.let { pins.containsKey(it) } == true

    /** Pins every host in [hosts] to [address]. Hostnames must already be normalized. */
    fun pin(hosts: Collection<String>, address: InetAddress) {
        if (hosts.isEmpty()) return
        pins = hosts.associateWith { address }
        // The host set is the single most useful line when discovery "works" but the app
        // still fails: a pin landing on a host nothing requests looks identical, from the
        // server side, to discovery never having run. Observed 2026-10-05.
        Log.d(TAG, "Pinned ${hosts.sorted()} -> ${address.hostAddress}")
    }

    fun clear() {
        pins = emptyMap()
    }
}
