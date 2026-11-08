package com.nuvio.app.core.overlay

/**
 * What this phone can hand a nearby TV: the server it is already set up on.
 *
 * Deliberately **WAN-only**. A TV standing on a different network cannot use the server's home
 * LAN address, and the ladder's last-resort tier takes the *first* plane it is given when nothing
 * answers its probe — so publishing `lan=` would send the TV to a guaranteed-dead address.
 */
internal data class TvAdvertPayload(
    /** `svc` — the FQDN every URL, SNI and certificate check must keep. */
    val serviceName: String,
    /** `wan` — the server's public address, as a literal, never a name. */
    val wanAddress: String,
    /** The WireGuard UDP port at [wanAddress]. */
    val wanPort: Int,
    /** `pk` — the server's tunnel public key, base64. */
    val serverPublicKey: String,
)

/** Whether this phone can share its server right now, and why not when it cannot. */
internal enum class TvAdvertAvailability {
    /** The platform cannot advertise an mDNS service (iOS today). */
    Unsupported,

    /** This phone is not set up on a server, so it has nothing to hand over. */
    NotConfigured,

    /** The server is already on this network — the TV will find it without our help. */
    AlreadyOnServerLan,

    /** Nothing is advertising for this server here; we can. */
    Ready,
}

/** The outcome of asking the phone to advertise. */
internal sealed interface TvAdvertResult {
    data object Started : TvAdvertResult
    data object Unsupported : TvAdvertResult
    data object NotConfigured : TvAdvertResult
    data object AlreadyOnServerLan : TvAdvertResult
    data class Failed(val reason: String? = null) : TvAdvertResult
}

/**
 * Publishes `_boomio-overlay._udp` for the server this phone is set up on, so a TV on a network
 * with no Boomio server can still find it.
 *
 * ⚠️ **This is a bounded, admin-initiated advert, not a background service.** It exists only
 * between [start] and [stop], which the Add-a-TV screens tie to their own lifetime. A phone that
 * advertised continuously would broadcast the home server's public address and keys on every
 * Wi-Fi it joined — a privacy leak and a spoofable beacon, for no benefit when nobody is adding
 * a TV.
 *
 * ⚠️ **The advert carries `wan=` and must not carry `lan=`.** With Android's NSD the SRV target
 * is always *this phone*, so it can never be the server. When the record names a plane the TV's
 * ladder falls through to that plane; a home `lan=` literal would be the first plane and is
 * unreachable from the network the TV is standing on.
 *
 * ⚠️ **Nothing here authorizes anything.** The advert is unauthenticated, exactly like the
 * server's own mDNS advert and the public DNS TXT. A TV that has already paired refuses a
 * different `pk` at the handshake. A fresh TV still needs the admin's approval code, which is
 * unchanged — this only gets it to the screen that shows one.
 */
internal expect object TvServerAdvert {
    /** Cheap, non-blocking check for whether the share affordance can be offered. */
    fun availability(): TvAdvertAvailability

    /** Starts advertising and suspends only until the platform confirms registration. */
    suspend fun start(): TvAdvertResult

    /** Stops advertising. Safe to call when not advertising. */
    fun stop()
}
