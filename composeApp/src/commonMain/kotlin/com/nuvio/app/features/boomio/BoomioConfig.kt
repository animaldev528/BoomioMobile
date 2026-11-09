package com.nuvio.app.features.boomio

/**
 * Runtime configuration for the boomio media-plane integration seam.
 *
 * ⚠️ **No deployment is compiled in.** Every base URL is blank until the client has been given a
 * server, and one value — [serviceOrigin] — derives all four at once. That origin is the FQDN the
 * person enters at bootstrap; nothing here knows anybody's hostname, address or key. A blank base
 * leaves its seam inert, which is the honest state for an unconfigured client: [BoomioStreamResolver]
 * and [BsmRatingGate] no-op until a server is chosen, leaving the existing addon/debrid resolvers as
 * the primary stream sources.
 *
 * These are deliberately mutable vars rather than build-time constants so the
 * host application can inject them at startup from its own config source
 * (BuildConfig / gradle properties / env). A build value overrides the base it names;
 * [serviceOrigin] is the one knob that moves all four together. Nothing here
 * is read from a secret store or inlined — assign the values at app start.
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
     * ⚠️ **This is the one value the client is configured with, and it starts blank.** It is the
     * server FQDN a person enters at bootstrap; the auth backend, the discovery record and the
     * four base URLs all derive from it. Nothing is compiled in, so an unconfigured client has no
     * server rather than somebody else's.
     *
     * ⚠️ **Assigning a server configures all four bases at once.** A blank assignment is the
     * unconfigured state and clears them, which is what "Use official server" needs after a person
     * leaves a custom server. Discovery may overwrite the origin from a record's `svc=` field, but
     * only because that is the same name the client was given.
     */
    private var serviceOriginValue: String = ""

    var serviceOrigin: String
        get() = serviceOriginValue
        set(value) {
            val normalised = value.trim().trimEnd('/')
            // Blank is a legitimate state — an unconfigured client — and clears the bases rather
            // than leaving them on a server the person just left.
            serviceOriginValue = normalised
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
     * every request keeps naming the collapsed edge host ([serviceOrigin])
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
     * In the end state this is **discovered**, not configured — the ladder tries mDNS, then the
     * discovery record's `lan=`/`wan=` literals, then asks the user. This field is what a debug
     * build bakes in so the transport can be exercised before that ladder exists.
     *
     * ⚠️ **Never floor this to [serviceOrigin].** After the collapse that name's `A` record is
     * the server's *private* address, so a baked `boomio.duckdns.org:51820` is a dial target that
     * works on the LAN and is unroutable off it — the trap the ladder exists to avoid. The published
     * literals are the only correct endpoints, and a blank here leaves the tunnel inert rather than
     * pointed somewhere wrong.
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

    /**
     * **What this device says it can decode**, in the media plane's own query vocabulary.
     *
     * ⚠️ **Why the client declares this at all.** `bsf` gates `/find` and `/stream` on the
     * capabilities that arrive WITH the request. A device is identified either by its client
     * certificate (resolved against the edge's allow-list) or by what it *declares*; a request
     * carrying neither falls through to the permissive branch and is offered everything the
     * index holds. The TV client has declared its ceilings since the gates shipped; this client
     * did not — which is how a 1080p, non-HDR tablet was handed a 4K HDR10 HEVC release that it
     * opened, stalled on, and abandoned.
     *
     * ⚠️ **Populated from this device's own probe, never from a build value.** A hand-typed
     * ceiling is a claim about hardware, and a wrong claim here either withholds playable
     * streams or hands over unplayable ones. See `DeviceCapabilityReporter`.
     *
     * ⚠️ **Blank is a real state and must stay blank.** An absent cap reaches `bsf` as
     * "unknown" and gates nothing; defaulting to e.g. "1080p" would silently cap every device
     * whose probe has not run yet, including 4K televisions.
     */
    var deviceMaxResolution: String = ""
    var deviceMaxResByCodec: String = ""

    /**
     * The HDR stance to declare: a comma list of the types this device may be handed
     * (`dv,hdr10,hdr10plus,hlg`), `0` for an explicit denial, or blank while unknown.
     *
     * ⚠️ **The list, not `1`.** `hdr=1` is an unbounded affirmation — it tells the plane this
     * device takes HDR but not which, so an operator who allowed HDR10 and withheld Dolby
     * Vision still received every DV release. The list is the probe intersected with the
     * operator's row, and the intersection is what `bfs`'s Gate 3b drops against.
     */
    var deviceHdr: String = ""

    /**
     * The caps query for a media-plane request — `max_resolution=…&codecCapsMaxRes=…&hdr=…` —
     * or blank when this device has not probed itself yet. Callers append nothing on blank, so
     * an unprobed device asks for the same thing it did before this existed.
     */
    fun bsfCapsQuery(): String {
        val parts = mutableListOf<String>()
        deviceMaxResolution.trim().takeIf { it.isNotEmpty() }
            ?.let { parts += "max_resolution=" + it.encodeBsfQueryValue() }
        deviceMaxResByCodec.trim().takeIf { it.isNotEmpty() }
            ?.let { parts += "codecCapsMaxRes=" + it.encodeBsfQueryValue() }
        deviceHdr.trim().takeIf { it.isNotEmpty() }
            ?.let { parts += "hdr=" + it.encodeBsfQueryValue() }
        return parts.joinToString("&")
    }

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
        if (origin.isBlank()) {
            // Unconfigured: no seam may carry a stale server. Build-time companion/IPTV values are
            // separate floors and stay if a build names them.
            companionBaseUrl = BoomioCompanionConfig.BASE_URL.trim()
            iptvBaseUrl = BoomioIptvConfig.BASE_URL.trim()
            boomioBaseUrl = ""
            bsmBaseUrl = ""
            return
        }
        companionBaseUrl = origin.toWebSocketScheme() + "/bsc"
        iptvBaseUrl = "$origin/bss-iptv"
        boomioBaseUrl = "$origin/bsf"
        bsmBaseUrl = "$origin/bsm"
    }

    /**
     * Puts every boomio base back to "no server configured", undoing a manual server.
     *
     * Needed because "Use official server" clears the *stored* configuration; a process that
     * survives the switch would otherwise keep deriving `/bsm`, `/bsc` and `/bsf` from the server
     * the person just left.
     */
    fun resetServiceOrigin() {
        serviceOrigin = ""
    }

    /**
     * Lets a build name the separate companion/IPTV hosts, if it names any.
     *
     * ⚠️ **There is no compile-time service name to establish any more.** The service origin is the
     * server FQDN a person enters, so the bases start blank and stay blank until then. The two
     * per-service build values below remain only because a build may point at hosts of its own; the
     * one-name derivation still overwrites them whenever a server is configured.
     */
    init {
        BoomioCompanionConfig.BASE_URL.trim().takeIf { it.isNotEmpty() }?.let { companionBaseUrl = it }
        BoomioIptvConfig.BASE_URL.trim().takeIf { it.isNotEmpty() }?.let { iptvBaseUrl = it }
    }
}

/**
 * Encodes one cap value for a query string.
 *
 * `:` and `,` stay literal on purpose: `codecCapsMaxRes=avc:1080p,hevc:1080p` is the exact wire
 * form the TV client sends and the one `bsf`'s reader expects. Everything outside the unreserved
 * set — a `&`, a `#`, a space — is percent-encoded so a value can never break the query apart.
 */
private fun String.encodeBsfQueryValue(): String = buildString {
    for (byte in encodeToByteArray()) {
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        val literal = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char == '-' || char == '_' || char == '.' || char == '~' || char == ':' || char == ','
        if (literal) {
            append(char)
        } else {
            append('%').append(BSF_QUERY_HEX[value shr 4]).append(BSF_QUERY_HEX[value and 0x0F])
        }
    }
}

private const val BSF_QUERY_HEX = "0123456789ABCDEF"

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
