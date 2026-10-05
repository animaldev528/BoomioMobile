package com.nuvio.app.core.overlay

import android.util.Log
import java.net.InetAddress

/**
 * The address a public FQDN is pinned to, and which source put it there.
 *
 * The pin is **address-only**. It never rewrites a URL: Caddy serves certificates per
 * named site block, so `https://192.168.68.65/` and `https://beamstream.local/` both
 * fail TLS. Only the *resolution* of a name changes, which is why this is a DNS seam
 * and not a base-URL swap. The same holds for the overlay address: `https://10.77.0.1/`
 * carries no name, so Caddy has no site block to answer it — the name has to survive
 * and only its address may change.
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
 * ⚠️ **One pin per source, and the sources are ranked.** The two tiers pin the same
 * host set to different addresses — the LAN address on the server's own network, the
 * overlay address everywhere else — and they are driven by independent triggers that
 * know nothing about each other. A single slot would let the last writer win, so a
 * tunnel probe landing a second after a browse would silently replace a working LAN
 * pin. Sources are held separately and consulted in [LocalServerSource] order instead.
 *
 * Held as one immutable map behind `@Volatile` and replaced copy-on-write rather than
 * guarded by a lock: a `lookup` runs on a network thread for *every* connect and must
 * never block, and a reader needs one consistent snapshot rather than several fields
 * read one at a time. The address is [InetAddress] rather than a string so a link-local
 * scope id survives.
 */
internal object OverlayPinRegistry {

    private const val TAG = "OverlayPinRegistry"

    private class Pin(
        val hosts: Set<String>,
        val suffixes: Set<String>,
        val address: InetAddress,
    )

    @Volatile
    private var pins: Map<LocalServerSource, Pin> = emptyMap()

    /** The pinned address for [host], or null when no source covers [host]. */
    fun lookup(host: String?): InetAddress? {
        if (host.isNullOrBlank()) return null
        val current = pins
        if (current.isEmpty()) return null
        // Walks the sources in rank order, so the most local pin answers first.
        for (source in LocalServerSource.entries) {
            val pin = current[source] ?: continue
            if (host in pin.hosts) return pin.address
            // The domain match is what covers the hosts the app only ever learns at runtime.
            if (pin.suffixes.any { suffix -> host.endsWith(".$suffix") }) return pin.address
        }
        return null
    }

    /**
     * True when [url]'s host is currently pinned.
     *
     * This is the gate that decides whether a connection may use a pin at all. A pin is
     * only ever safe to follow under real certificate validation, so callers that cannot
     * validate must not consult it — see the two clients in `PlayerPlaybackNetworking`.
     * It is the gate for *every* source, which keeps that protection in one place rather
     * than one per tier.
     */
    fun isPinnedHost(url: String): Boolean = lookup(hostOf(url)) != null

    /** Pins [hosts], and every other host on their domain, to [address] for [source]. */
    fun pin(source: LocalServerSource, hosts: Collection<String>, address: InetAddress) {
        if (hosts.isEmpty()) return
        val hostSet = hosts.toSet()
        val suffixSet = serverDomainSuffixes(hostSet)
        pins = pins.toMutableMap().apply { this[source] = Pin(hostSet, suffixSet, address) }
        // The host set is the single most useful line when discovery "works" but the app
        // still fails: a pin landing on a host nothing requests looks identical, from the
        // server side, to discovery never having run. Observed 2026-10-05.
        Log.d(TAG, "[$source] pinned ${hostSet.sorted()} + *.$suffixSet -> ${address.hostAddress}")
    }

    /** Drops [source]'s pin, leaving every other source's in place. */
    fun clear(source: LocalServerSource) {
        if (source !in pins) return
        pins = pins.toMutableMap().apply { remove(source) }
        Log.d(TAG, "[$source] pin cleared")
    }

    /** Drops every pin. Tests only — production code clears one source at a time. */
    fun clearAll() {
        pins = emptyMap()
    }
}
