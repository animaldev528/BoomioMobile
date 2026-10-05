package com.nuvio.app.core.overlay

import android.util.Log
import java.net.InetAddress

/**
 * The LAN address a discovered boomio server is pinned to.
 *
 * The pin is **address-only**. It never rewrites a URL: Caddy serves certificates per
 * named site block, so `https://192.168.68.65/` and `https://beamstream.local/` both
 * fail TLS. Only the *resolution* of a name changes, which is why this is a DNS seam
 * and not a base-URL swap.
 *
 * ⚠️ **A pin covers a domain, not a list of hosts.** The server's edge is ~18 site
 * blocks — `bsc`, `bsf`, `tmdb`, `usn`, `bss-iptv`, `bss-dav`, `bss-tor`, `bss-local`,
 * `bss-en`, `nzbdav`, `easynews`, `aiot`, `hydra`, `lists`, `dmm`, `bsm`, `grafana` —
 * and the app learns most of them **only at runtime**, from the stream URLs bsf hands
 * back. A pin set built by enumerating configuration and addon manifests is therefore
 * always one plane short, and it was short twice before this: once missing Supabase,
 * once missing the whole addon plane.
 *
 * That is not a hypothetical. Measured on device 2026-10-05, with the app otherwise
 * fully working, the player sat on
 *
 *     SocketTimeoutException: failed to connect to
 *         bss-dav.tracemonkey.org/153.68.210.49 (port 443) after 15000ms
 *
 * `153.68.210.49` is the public WAN address, which hairpin NAT cannot reach from the
 * LAN, so every attempt burned the full 15 s and the stream buffered forever. The
 * catalogue worked throughout, because the hosts *it* uses are config- and
 * manifest-derived and were pinned. To the user that reads as "posters and search
 * work, nothing plays".
 *
 * So [lookup] matches a host **on the pinned domain** as well as an exact host. The
 * suffix is derived from the pinned hosts, which are themselves already filtered to
 * the server's own registrable domain (see `derivePinnableAddonHosts`), so a
 * third-party host — `image.tmdb.org`, `catalog.nuvio.tv`, `opensubtitles-v3.strem.io`,
 * the Supabase cloud — can never match and keeps resolving publicly.
 *
 * Held as one immutable object behind `@Volatile` rather than three separate fields,
 * so a `lookup` on a network thread reads one consistent snapshot and never blocks.
 * The address is [InetAddress] rather than a string so a link-local scope id survives.
 */
internal object OverlayPinRegistry {

    private const val TAG = "OverlayPinRegistry"

    private class Pin(
        val hosts: Set<String>,
        val suffixes: Set<String>,
        val address: InetAddress,
    )

    @Volatile
    private var pin: Pin? = null

    /** The pinned address for [host], or null when [host] is not covered by a pin. */
    fun lookup(host: String?): InetAddress? {
        val current = pin ?: return null
        if (host.isNullOrBlank()) return null
        if (host in current.hosts) return current.address
        // The domain match is what covers the hosts the app only ever learns at runtime.
        return current.address.takeIf {
            current.suffixes.any { suffix -> host.endsWith(".$suffix") }
        }
    }

    /**
     * True when [url]'s host is currently pinned.
     *
     * This is the gate that decides whether a connection may use a pin at all. A pin is
     * only ever safe to follow under real certificate validation, so callers that cannot
     * validate must not consult it — see the two clients in `PlayerPlaybackNetworking`.
     */
    fun isPinnedHost(url: String): Boolean = lookup(hostOf(url)) != null

    /** Pins [hosts], and every other host on their domain, to [address]. */
    fun pin(hosts: Collection<String>, address: InetAddress) {
        if (hosts.isEmpty()) return
        val hostSet = hosts.toSet()
        val suffixSet = serverDomainSuffixes(hostSet)
        pin = Pin(hostSet, suffixSet, address)
        // The host set is the single most useful line when discovery "works" but the app
        // still fails: a pin landing on a host nothing requests looks identical, from the
        // server side, to discovery never having run. Observed 2026-10-05.
        Log.d(TAG, "Pinned ${hostSet.sorted()} + *.$suffixSet -> ${address.hostAddress}")
    }

    fun clear() {
        pin = null
    }
}
