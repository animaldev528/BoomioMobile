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
 * - **The pins come first, in rank order, and are not the only answers.** The delegate's
 *   results still follow, so a stale pin costs one failed connect rather than wedging a
 *   working app — the tier's whole philosophy is that a missed path falls back to the
 *   public edge rather than breaking.
 * - **There is a *chain* of them, not one, and the second is what makes off-LAN work.**
 *   The service name's public `A` record deliberately points at the server's *private*
 *   address, so away from home the name resolves to something undialable; the discovery
 *   record's published `wan=` literal is the only way to reach the HTTP plane there. Both
 *   pins are handed over and the connect decides — a private address on a foreign network
 *   is refused immediately rather than after a timeout, so trying the LAN one first costs
 *   nothing where it cannot work.
 * - **[usePins] defaults true**, so the existing no-arg call sites pick the behaviour
 *   up unchanged. The playback client passes false: it is built trust-all (see
 *   `PlayerPlaybackNetworking`), so TLS would not catch a hostile pin there.
 */
class IPv4FirstDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val usePins: Boolean = true,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        // `lookupChain`, not a map read: a pin covers the server's whole domain, so a host
        // that appears in no configuration — a `bss-dav`/`bss-tor` stream URL, learned
        // only from bsf's response — is still answered with the LAN address.
        val pins = if (usePins) OverlayPinRegistry.lookupChain(hostname) else emptyList()

        val resolved = try {
            delegate.lookup(hostname)
        } catch (error: Exception) {
            // A pin is worth more than the system resolver's failure: the whole point
            // is a LAN where system DNS does not know this name.
            if (pins.isNotEmpty()) return pins
            throw error
        }

        val sorted = resolved.sortedBy { if (it is Inet4Address) 0 else 1 }
        if (pins.isEmpty()) return sorted

        // The pins keep their rank order and are never re-sorted behind an IPv6 the delegate
        // happened to return — nor behind each other, which is what carries LAN-then-WAN.
        return pins + sorted.filter { it !in pins }
    }
}
