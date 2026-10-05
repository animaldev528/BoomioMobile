package com.nuvio.app.features.boomio

/**
 * Runtime configuration for the boomio media-plane integration seam.
 *
 * Both URLs default to blank, which keeps the seam **inert**: [BoomioStreamResolver]
 * and [BsmRatingGate] no-op when their respective base URL is blank, leaving the
 * existing addon/debrid resolvers as the primary stream sources.
 *
 * These are deliberately mutable vars rather than build-time constants so the
 * host application can inject them at startup from its own config source
 * (BuildConfig / gradle properties / env), matching the legacy
 * `BuildConfig.BOOMIO_BASE_URL` / `BuildConfig.BSM_BASE_URL` wiring. Nothing here
 * is read from a secret store or inlined — assign the values at app start.
 *
 * NOTE: this is a static, uncompiled port; the seam has not been exercised against
 * the boomio plane yet.
 */
object BoomioConfig {
    /** Base URL of the boomio media plane (bsf), e.g. `https://bsf.example.com`. */
    var boomioBaseUrl: String = ""

    /** Base URL of the BSM rating service, e.g. `https://bsm.example.com`. */
    var bsmBaseUrl: String = ""

    /**
     * Base URL of the bsc companion hub, e.g. `wss://bsc.example.com`. The phone
     * companion bridge connects to `{companionBaseUrl}/ws/phone?session_token=…&device_id=…`
     * and the TV to `{companionBaseUrl}/ws`. Sourced from `BOOMIO_COMPANION_URL` in
     * `local.properties` (via the generated [BoomioCompanionConfig]); override at
     * startup if needed. Inert when blank — mirrors the blank-inert pattern of the
     * other seams above.
     */
    var companionBaseUrl: String = BoomioCompanionConfig.BASE_URL

    /**
     * Base URL of the bss-iptv live edge, e.g. `https://bss-iptv.example.com`.
     * Sourced from `BOOMIO_IPTV_URL` in `local.properties` (via the generated
     * [BoomioIptvConfig]). This is a DIFFERENT host from [companionBaseUrl]: the
     * channel catalogue is served by the IPTV role edge, while the companion
     * socket and the party REST live on bsc.
     *
     * The phone reads the catalogue directly from the edge — its existing
     * `bs_ses_*` token is valid there because both services share the same
     * session validator and the same Redis. No proxy route is involved.
     */
    var iptvBaseUrl: String = BoomioIptvConfig.BASE_URL

    /**
     * The server's address on the WireGuard overlay, e.g. `10.77.0.1`. Blank disables
     * the overlay resolver entirely.
     *
     * ⚠️ **This is an address, not a URL and not a hostname.** The app never *dials* it:
     * every request keeps naming `bsc.tracemonkey.org` and Caddy picks the site block
     * from that name, so a bare address has nothing to match and fails TLS. All this
     * value does is tell the DNS seam what those names should resolve to while the
     * tunnel is up — see `OverlayTunnel`. A hostname here is refused rather than
     * resolved, because resolving it is the exact behaviour the seam replaces.
     *
     * Sourced from `BOOMIO_OVERLAY_ADDR` in `local.properties` (via the generated
     * [BoomioOverlayConfig]); override at startup if needed. Blank-inert, like the
     * seams above: [iptvBaseUrl] and [companionBaseUrl] are the hosts, this is the
     * address they take on the overlay.
     *
     * In the end state this is *learned*, not configured — pairing (phase 3) mints a
     * code on the server and the client receives the overlay address with it, which is
     * what makes a fresh client work with no manual configuration. Until that exists,
     * it is one field, and it is the only one.
     */
    var overlayServerAddress: String = BoomioOverlayConfig.ADDR

    /** True when the companion seam is configured ([companionBaseUrl] is set). */
    fun companionEnabled(): Boolean = companionBaseUrl.isNotBlank()

    /** True when the live IPTV edge is configured ([iptvBaseUrl] is set). */
    fun iptvEnabled(): Boolean = iptvBaseUrl.isNotBlank()
}

/** REST (`https://`) variant of [BoomioConfig.companionBaseUrl] for the bsc companion API. */
val BoomioConfig.companionRestBaseUrl: String
    get() = companionBaseUrl.trimEnd('/')
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")

/** Phone companion websocket endpoint (`{base}/ws/phone`). */
val BoomioConfig.companionPhoneWsUrl: String
    get() = companionBaseUrl.trimEnd('/') + "/ws/phone"

/** REST base of the bss-iptv edge, normalised to `http(s)://…` with no trailing slash. */
val BoomioConfig.iptvRestBaseUrl: String
    get() = iptvBaseUrl.trimEnd('/')
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")
