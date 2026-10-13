package com.nuvio.app.features.trakt

import com.nuvio.app.features.simkl.SimklPkceCrypto
import com.nuvio.app.features.simkl.base64UrlWithoutPadding
import io.ktor.http.encodeURLParameter

/**
 * PKCE material for the Trakt browser flow.
 *
 * ── Why Trakt needs this at all ─────────────────────────────────────────────
 * Trakt pairs a client id with its secret at the token exchange, so a deployment's own Trakt app
 * cannot be driven by a client that only knows the id — the pair has to match. Trakt deprecates
 * `client_secret` in favour of PKCE for exactly this reason ("optional, and should only be sent from
 * your own server, never from a website, mobile app, or desktop app"), and PKCE is what lets the app
 * complete the exchange holding nothing but the id the deployment served it.
 *
 * ⚠️ **It is used only when the id came from the deployment.** The build's own app keeps sending its
 * secret, unchanged — see `TrackerCredentials.useBakedTraktSecret`. Changing the working flow is a
 * risk with no upside, and the flow that broke is the one that needs the fix.
 *
 * ⚠️ **The platform seam is [SimklPkceCrypto], under that name, on purpose.** It is the module's only
 * `expect`/`actual` crypto — `SecureRandom` and SHA-256, with android and iOS actuals — and it is
 * generic despite the name: Simkl was simply the first flow that needed PKCE. A second expect/actual
 * pair for Trakt would mean two platform implementations of the same two primitives, which is a
 * well-trodden route to two subtly different ones.
 */
internal data class TraktPkceMaterial(
    val verifier: String,
    val challenge: String,
)

internal fun generateTraktPkceMaterial(): TraktPkceMaterial =
    createTraktPkceMaterial(SimklPkceCrypto.secureRandomBytes(32))

internal fun createTraktPkceMaterial(verifierEntropy: ByteArray): TraktPkceMaterial {
    require(verifierEntropy.size >= 32) { "PKCE verifier entropy must be at least 32 bytes" }
    val verifier = verifierEntropy.base64UrlWithoutPadding()
    require(verifier.length in 43..128) { "PKCE verifier must be 43-128 characters" }
    return TraktPkceMaterial(verifier = verifier, challenge = traktPkceChallenge(verifier))
}

/**
 * The S256 challenge for a verifier already in hand.
 *
 * The verifier is the only half worth storing: the challenge is a pure function of it, so re-deriving
 * it is what keeps a rebuilt authorize URL consistent with the code the user was actually sent to.
 */
internal fun traktPkceChallenge(verifier: String): String =
    SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding()

/**
 * The Trakt authorize URL, as a free function so its shape can be asserted in a test.
 *
 * ⚠️ **`codeChallenge` is null exactly when the flow is not PKCE.** The challenge is what binds the
 * code that comes back to the verifier this app holds, so a challenge here without a verifier at the
 * exchange — or the reverse — fails the sign-in at the last step with nothing visible to explain it.
 * The two are decided by one condition, `TrackerCredentials.usesPkce`, at the call site.
 */
internal fun buildTraktAuthorizationUrl(
    clientId: String,
    redirectUri: String,
    state: String,
    codeChallenge: String?,
): String {
    val encodedClientId = clientId.encodeURLParameter()
    val encodedRedirectUri = redirectUri.encodeURLParameter()
    val encodedState = state.encodeURLParameter()
    val pkce = codeChallenge
        ?.let { "&code_challenge=${it.encodeURLParameter()}&code_challenge_method=S256" }
        .orEmpty()
    return "$TRAKT_AUTHORIZE_URL?response_type=code&client_id=$encodedClientId" +
        "&redirect_uri=$encodedRedirectUri&state=$encodedState$pkce"
}

/** Trakt's user-facing authorization host — deliberately not the `api.trakt.tv` of the token calls. */
internal const val TRAKT_AUTHORIZE_URL = "https://trakt.tv/oauth/authorize"
