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

    /**
     * The **service origin** — one name, and every boomio URL in this object is built from it.
     *
     * ⚠️ **One seam, not four setters with four call sites.** The four base URLs below are the same
     * host with four path prefixes (`/bsc`, `/bsf`, `/bss-iptv`, `/bsm`); assigning them
     * individually would let a discovery result land on three of them and miss the fourth, and the
     * miss would be silent — the seam's consumers no-op on a blank base rather than failing. The
     * setter re-derives all four from one value, so they cannot disagree.
     *
     * ⚠️ **The baked value here is a floor, not the source.** Discovery overwrites it from the
     * record's `svc=` field the moment a publication is read, which is what makes the DuckDNS name
     * drive the client's URLs rather than a compile-time constant — the point of the whole v2
     * record.
     *
     * ⚠️ **Do not read this as "the companion seam can never be blank at cold start" — it can.**
     * Assigning the initializer does not run the setter (see below), so construction leaves the four
     * bases exactly as the generated config left them; on a build with no `BOOMIO_*` entry in
     * `local.properties` they are empty until discovery lands. The floor that actually prevents a
     * brick is the *overlay* pair — the baked endpoint and the baked server pubkey, which are what
     * get a tunnel up in the first place. The order is tunnel → record → this setter → the four
     * bases, so a blank base is a state the app passes through, not one it is stuck in, provided
     * the overlay pair is baked. A build that bakes neither is broken by construction, not by a
     * missing default here.
     *
     * ⚠️ **Assigning the initializer does not run the setter**, so the four bases are *not* rebuilt
     * at construction: a build whose `local.properties` names a different host keeps it until
     * discovery learns otherwise. That ordering is deliberate — it means this field can be
     * introduced without changing the behaviour of any existing build.
     */
    var serviceOrigin: String = DEFAULT_SERVICE_ORIGIN
        set(value) {
            val normalised = value.trim().trimEnd('/')
            // A blank assignment would strip every base URL to a bare path and disable the seams
            // in a way that looks like "no server configured" rather than like a bug. Refuse it.
            if (normalised.isBlank()) return
            field = normalised
            applyServiceOrigin(normalised)
        }

    /** Base URL of the boomio media plane (bsf), e.g. `https://bsf.example.com`. */
    var boomioBaseUrl: String = ""

    /** Base URL of the BSM rating service, e.g. `https://bsm.example.com`. */
    var bsmBaseUrl: String = ""

    /**
     * Base URL of the bsc companion hub, e.g. `wss://bsc.example.com` — or, on the
     * collapsed edge, `wss://boomio.example.com/bsc`. The phone companion bridge
     * connects to `{companionBaseUrl}/ws/phone?session_token=…&device_id=…` and the
     * TV to `{companionBaseUrl}/ws`. Sourced from `BOOMIO_COMPANION_URL` in
     * `local.properties` (via the generated [BoomioCompanionConfig]); override at
     * startup if needed. Inert when blank — mirrors the blank-inert pattern of the
     * other seams above.
     *
     * ⚠️ **A trailing path prefix here is load-bearing and must survive every
     * derivation.** `wss://` is not cosmetic: [companionPhoneWsUrl] appends `/ws/phone`
     * to this value and Ktor refuses an `https://` scheme there, while
     * [companionRestBaseUrl] rewrites the scheme to `https://` and leaves the rest
     * alone, so the prefix carries into REST calls too. Stripping the `/bsc` segment
     * would 404 every companion request and the WS would fail silently, with no retry.
     */
    var companionBaseUrl: String = BoomioCompanionConfig.BASE_URL

    /**
     * Base URL of the bss-iptv live edge, e.g. `https://bss-iptv.example.com` — or,
     * on the collapsed edge, `https://boomio.example.com/bss-iptv`.
     * Sourced from `BOOMIO_IPTV_URL` in `local.properties` (via the generated
     * [BoomioIptvConfig]).
     *
     * Under the collapse this is **the same host as [companionBaseUrl]** and only the
     * path segment differs; before it, the two were distinct hosts. Nothing may be
     * keyed off host inequality — the services are told apart by prefix.
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
     * every request keeps naming the collapsed edge host (e.g. `boomio.tracemonkey.org`)
     * and Caddy picks the site block from that name, so a bare address has nothing to
     * match and fails TLS. All this
     * value does is tell the DNS seam what those names should resolve to while the
     * tunnel is up — see `OverlayTunnel`. A hostname here is refused rather than
     * resolved, because resolving it is the exact behaviour the seam replaces.
     *
     * **Learned, not configured.** Enrollment writes it: the assignment carries the overlay
     * CIDR, and the server holds that network's first host, so a build aimed at no deployment
     * in particular still gets this right — and a device that enrolled once keeps it across a
     * cold start from the cached assignment. `BOOMIO_OVERLAY_ADDR` from `local.properties`
     * (via the generated [BoomioOverlayConfig]) stands in only until a device has enrolled;
     * it is a fallback now, not the source.
     *
     * Blank-inert, like the seams above: [iptvBaseUrl] and [companionBaseUrl] are the
     * hosts, this is the address they take on the overlay.
     */
    var overlayServerAddress: String = BoomioOverlayConfig.ADDR

    /**
     * **This device's own** address inside the overlay, CIDR form — `10.77.0.2/32`.
     *
     * ⚠️ **The third overlay address, and the one most likely to be confused with the other
     * two.** [overlayServerAddress] is where the app's traffic is *aimed* (`10.77.0.1`);
     * [overlayEndpoint] is where the tunnel's *UDP* goes, in the clear, over the local
     * network; this is the address the device *holds* once the tunnel is up. It must equal
     * the `allowed-ips` the operator enrolled this client's public key with, or the tunnel
     * comes up, completes a handshake, and then silently drops every return packet — the
     * server has no route to an address it never agreed to.
     *
     * Sourced from `BOOMIO_OVERLAY_LOCAL_CIDR`, defaulting to `10.77.0.2/32`. That default is
     * right for the first client on an overlay and **wrong for the second**, which is exactly
     * what per-client enrollment (U4) has to replace.
     */
    var overlayLocalCidr: String = BoomioOverlayConfig.LOCAL_CIDR

    /**
     * Absolute URL of the loopback HTTP CONNECT relay this app runs for its own userspace
     * WireGuard tunnel, e.g. `http://127.0.0.1:8100`. Blank (the default) means no relay:
     * every engine dials directly, exactly as it does today.
     *
     * Only libmpv actually needs this. The OkHttp and Ktor clients can be pointed at the
     * tunnel through the DNS seam, but libmpv resolves inside libcurl's `getaddrinfo`, which
     * is not hookable from the application — a proxy is the only way in.
     *
     * Sourced from `BOOMIO_OVERLAY_PROXY` in `local.properties` (via the generated
     * [BoomioOverlayConfig]).
     */
    var overlayProxyUrl: String = BoomioOverlayConfig.PROXY

    /**
     * Playback engine a fresh install should resolve to, or `null` for "leave it alone".
     *
     * `AndroidPlaybackEngine.Auto` resolves to ExoPlayer, so an ordinary play never touches
     * libmpv — which matters because the S-U2 spike's oracle is a proxy log that only
     * libmpv can write to. Without this, a *correct* build looks like a failed spike.
     *
     * It supplies the **default only**: the caller consults it where the stored setting is
     * absent, so an engine chosen in Settings still wins. Blank (the default) returns null
     * and every normal build behaves exactly as before.
     *
     * Sourced from `BOOMIO_OVERLAY_ENGINE` (via the generated [BoomioOverlayConfig]).
     */
    fun overlayEngineDefault(): String? =
        BoomioOverlayConfig.ENGINE.trim().takeIf { it.isNotEmpty() }

    /**
     * The WireGuard endpoint the userspace tunnel dials, `host:port` — e.g.
     * `192.168.68.65:51820` on the LAN, `153.68.210.49:51820` off it. Blank disables the
     * tunnel, which is the default.
     *
     * ⚠️ **Not the same thing as [overlayServerAddress], and the two are easy to confuse.**
     * That one is `10.77.0.1` — the address the app's *traffic* takes on the overlay, which
     * is what names resolve to. This one is where the *tunnel's UDP* goes, in the clear, over
     * whatever network the device is on. Different planes, different addresses.
     *
     * In the end state this is **discovered**, not configured — the ladder in architecture
     * §4.4 tries mDNS, then `boomio-local`, then asks the user. This field is what a debug
     * build bakes in so the transport can be exercised before that ladder exists.
     *
     * Sourced from `BOOMIO_OVERLAY_ENDPOINT` in `local.properties`.
     */
    var overlayEndpoint: String = BoomioOverlayConfig.ENDPOINT

    /**
     * The **server's** WireGuard public key, base64. Public, not secret: the mDNS advert and
     * the DuckDNS TXT record publish the identical value as `pk=…`.
     *
     * Base64 rather than hex because that is what both publication channels use; the
     * controller converts to the hex `IpcSet` takes, so nothing here has to know.
     *
     * Sourced from `BOOMIO_OVERLAY_PUBKEY` in `local.properties`.
     */
    var overlayServerPubKey: String = BoomioOverlayConfig.PUBKEY

    /**
     * The name the **server** gave this device, e.g. `device-pixel-7-pro-430f9ca3`. Blank until
     * a device has enrolled, and blank means "we have not learned it yet" — never a placeholder.
     *
     * ⚠️ **This is not a label, and it is not [overlayLocalCidr].** It is the *identity* half of an
     * enrollment: the server derives it from the device id (`peerNameFor` in `bsc/lib/overlay-store.js`)
     * and files everything it later learns about this device — its WireGuard peer record, its entry
     * in the edge's mTLS allow-list — under exactly this string. A certificate minted for any other
     * CN is refused `cn_mismatch` by `POST /api/overlay/cert` and by the channel alike, so a wrong
     * value here does not degrade: it fails closed, and the device never gets a certificate.
     *
     * ⚠️ **The server is the only source, and it must stay that way.** A name this app invented for
     * itself would be a name the allow-list has never heard of. It arrives on both enrollment
     * transports — `GET /api/overlay/enroll/status`'s `name`, and the provisioning channel's
     * `enroll.ready.name` — and [com.nuvio.app.core.overlay.OverlayEnrollment] writes it from
     * whichever one answered, alongside the address, so the two can never disagree.
     *
     * It is emphatically **not** [overlayServerAddress] (the server's overlay address) and not the
     * user's device name from Settings — the three coincide in neither shape nor origin.
     *
     * Blank-inert: `MtlsRegistrar.planCertificate` treats an unknown name as `Unavailable`, so a
     * device that has not enrolled registers nothing rather than minting for a name it guessed.
     */
    var overlayDeviceName: String = ""

    /** True when the companion seam is configured ([companionBaseUrl] is set). */
    fun companionEnabled(): Boolean = companionBaseUrl.isNotBlank()

    /** True when the live IPTV edge is configured ([iptvBaseUrl] is set). */
    fun iptvEnabled(): Boolean = iptvBaseUrl.isNotBlank()

    /**
     * Rebuilds the four base URLs from [origin], each with the path prefix the collapsed edge
     * serves it under.
     *
     * ⚠️ **The prefixes are not decoration.** Every boomio service is a site block behind one
     * Caddy, told apart by path rather than by host, so the prefix is part of the address. The
     * companion one is *also* the scheme conversion: the bridge is a WebSocket and Ktor refuses an
     * `https://` scheme for it, while [companionRestBaseUrl] converts back for REST — so storing
     * `wss://…/bsc` here is what keeps both halves working from one value.
     */
    private fun applyServiceOrigin(origin: String) {
        companionBaseUrl = origin.toWebSocketScheme() + "/bsc"
        iptvBaseUrl = "$origin/bss-iptv"
        boomioBaseUrl = "$origin/bsf"
        bsmBaseUrl = "$origin/bsm"
    }
}

/**
 * The host every boomio URL falls back to when no publication has been read.
 *
 * Public DNS, and deliberately so: a client that has never reached this server has never read a
 * record, so there is nothing to discover a name *from*. The discovery record (`boomio-prov`) is
 * what supplies the real one, including on a deployment that renames its service origin.
 */
private const val DEFAULT_SERVICE_ORIGIN = "https://boomio.duckdns.org"

/** `https://…` → `wss://…`, and `http://…` → `ws://…`; anything else is passed through unchanged. */
private fun String.toWebSocketScheme(): String = when {
    startsWith("https://") -> "wss://" + removePrefix("https://")
    startsWith("http://") -> "ws://" + removePrefix("http://")
    else -> this
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
