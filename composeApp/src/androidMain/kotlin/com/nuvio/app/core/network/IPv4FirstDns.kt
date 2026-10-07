package com.nuvio.app.core.network

import com.nuvio.app.core.overlay.OverlayPinRegistry
import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Reorders DNS results to prefer IPv4 first. This helps avoid broken IPv6 routes
 * on some emulator and network setups.
 *
 * It is also the overlay's pin seam: when the local-network discovery has found a
 * boomio server, [hostname] may be answered with that LAN address instead of the
 * public one. Two properties matter:
 *
 * - **The pin is first, not the only answer.** The delegate's results still follow,
 *   so a stale pin costs one failed connect rather than wedging a working app — the
 *   tier's whole philosophy is that a missed path falls back to the public edge
 *   rather than breaking.
 * - **[usePins] defaults true**, so the existing no-arg call sites pick the behaviour
 *   up unchanged. The playback client passes false: it is built trust-all (see
 *   `PlayerPlaybackNetworking`), so TLS would not catch a hostile pin there.
 */
class IPv4FirstDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val usePins: Boolean = true,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        // `lookup`, not a map read: a pin covers the server's whole domain, so a host
        // that appears in no configuration — a `bss-dav`/`bss-tor` stream URL, learned
        // only from bsf's response — is still answered with the LAN address.
        val pin = if (usePins) OverlayPinRegistry.lookup(hostname) else null

        val resolved = try {
            delegate.lookup(hostname)
        } catch (error: Exception) {
            // A pin is worth more than the system resolver's failure: the whole point
            // is a LAN where system DNS does not know this name.
            if (pin != null) return listOf(pin)
            throw error
        }

        if (pin == null) return resolved.sortedBy { if (it is Inet4Address) 0 else 1 }

        // The pin is never re-sorted behind an IPv6 the delegate happened to return.
        return listOf(pin) + resolved
            .filter { it != pin }
            .sortedBy { if (it is Inet4Address) 0 else 1 }
    }
}
