package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The provider name the auth server knows, and the path segment our callback answers for. */
private const val GOOGLE_PROVIDER = "google"

/** Shown when the exchange fails without an `error_description` of its own. */
private const val FAILURE_MESSAGE = "Couldn't sign in with Google."

/**
 * Signing in to nuvio with a Google account, from the app's own sign-in screen.
 *
 * This is the other half of the pair the screen offers: the same account, reached without a
 * password the person would have to have been given. Nothing about it is a page of ours —
 * the app asks, Google asks, and the app takes the answer. The account chooser itself is a
 * browser page because it has to be: Google refuses to be framed, and an embedded WebView is
 * refused outright rather than merely discouraged. What matters is that the *sign-in screen*
 * never becomes a web page: the person taps a button here and comes back here.
 *
 * The round trip is GoTrue's PKCE flow, so the app needs both ends of it:
 *
 *  1. [beginAuthorization] asks the SDK for the authorize URL. The SDK is what mints the PKCE
 *     challenge *and* keeps the verifier (`SupabaseProvider` configures [AuthCodeVerifierCache]),
 *     which is why this does not hand-roll the URL — a challenge minted here would be one the
 *     exchange below could never match.
 *  2. The caller opens it. The browser lands on Google, comes back to the auth server, and the
 *     server redirects to [redirectUrl] — a URL this app is registered to receive.
 *  3. [handleCallback] takes that URL, and `exchangeCodeForSession` turns its `code` into a
 *     session. `AuthRepository` watches the client's session status, so the gate leaves the
 *     sign-in screen on its own once that lands; nothing here has to navigate.
 *
 * ⚠️ [redirectUrl] must be listed in the auth server's `GOTRUE_URI_ALLOW_LIST`. The server
 * validates `redirect_to` against it and silently substitutes `SITE_URL` when it does not
 * match, so a missing entry looks like "Google worked, then the app never came back" rather
 * than like an error.
 */
object GoogleSignInRepository {
    /** Where the browser is sent back to; received by the app's bare `nuvio://` intent filter. */
    const val redirectUrl: String = "nuvio://auth/google"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("GoogleSignInRepository")

    private val _isAwaitingBrowser = MutableStateFlow(false)

    /**
     * True from the moment the chooser is opened until the answer comes back, so the screen can
     * show that something is in flight across the app switch — the person is looking at a
     * browser, not at this.
     */
    val isAwaitingBrowser: StateFlow<Boolean> = _isAwaitingBrowser.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var exchangeJob: Job? = null

    /**
     * The URL to open for the account chooser, or null when it cannot be built.
     *
     * Returns the URL rather than opening it because opening one is the platform's job (the
     * screen holds a `UriHandler`); this stays a pure "what should be opened" answer.
     */
    fun beginAuthorization(): String? {
        _error.value = null
        return runCatching {
            SupabaseProvider.client.auth.getOAuthUrl(
                // The library's own provider object. It is declared as an ID token provider, which
                // is about *its* native flow, not this one: the URL builder reads nothing but
                // `name`, so what comes out is `authorize?provider=google` either way. Building a
                // custom provider instead would mean importing the package's `Companion.invoke`
                // factory, which exists for providers the library does not ship.
                provider = Google,
                redirectUrl = redirectUrl,
            )
        }.onSuccess {
            _isAwaitingBrowser.value = true
        }.onFailure { failure ->
            log.e(failure) { "Could not build the Google sign-in URL" }
            _error.value = FAILURE_MESSAGE
        }.getOrNull()
    }

    /**
     * Takes the app's callback URL, if that is what [url] is.
     *
     * Returns true only for our own callback, so the deep-link router knows the URL has been
     * claimed and need not offer it to the trackers. The exchange itself runs in the background:
     * this is called from the intent dispatch, which must not block on a network round trip.
     */
    fun handleCallback(url: String): Boolean {
        val callback = parseCallback(url) ?: return false
        exchangeJob?.cancel()
        exchangeJob = scope.launch {
            val denied = callback.error
            if (denied != null) {
                log.w { "Google sign-in was not completed: $denied" }
                _isAwaitingBrowser.value = false
                _error.value = callback.errorDescription?.takeIf { it.isNotBlank() }
                    ?: FAILURE_MESSAGE
                return@launch
            }
            val code = callback.code
            if (code.isNullOrBlank()) {
                // A callback that is ours but carries neither an answer nor a refusal: the only
                // thing left is to stop pretending something is in flight.
                _isAwaitingBrowser.value = false
                return@launch
            }
            runCatching {
                SupabaseProvider.client.auth.exchangeCodeForSession(code, saveSession = true)
            }.onSuccess {
                // The session landing is the sign-in: `AuthRepository` sees it through the
                // client's session status and the gate moves on by itself.
                log.i { "Google sign-in completed" }
            }.onFailure { failure ->
                log.e(failure) { "Google sign-in code exchange failed" }
                _error.value = failure.message?.takeIf { it.isNotBlank() } ?: FAILURE_MESSAGE
            }
            _isAwaitingBrowser.value = false
        }
        return true
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Gives up on an answer that is not coming — the person backed out of the browser.
     *
     * Only the waiting state is dropped: a callback already on its way must still be allowed to
     * land, because the app cannot tell "the person closed the tab" from "the person is still
     * choosing", and the second one is the common case.
     */
    fun stopWaiting() {
        _isAwaitingBrowser.value = false
    }
}

private data class GoogleCallback(
    val code: String?,
    val error: String?,
    val errorDescription: String?,
)

/**
 * `nuvio://auth/google?code=…`, or null when [url] is not that.
 *
 * ⚠️ Matched exactly — scheme, host and the `google` path segment. `nuvio://auth/…` is shared
 * with the tracker callbacks (`nuvio://auth/simkl`, `nuvio://auth/trakt`), and a looser match
 * would swallow one of theirs: an unresolved link is theirs to report, a mis-claimed one is
 * silently dropped.
 */
private fun parseCallback(url: String): GoogleCallback? {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return null
    if (!parsed.protocol.name.equals("nuvio", ignoreCase = true)) return null
    if (!parsed.host.equals("auth", ignoreCase = true)) return null
    val path = parsed.pathSegments.filter(String::isNotBlank).firstOrNull()
    if (!path.equals(GOOGLE_PROVIDER, ignoreCase = true)) return null

    val parameters = parsed.parameters
    return GoogleCallback(
        code = parameters["code"]?.takeIf { it.isNotBlank() },
        error = parameters["error"]?.takeIf { it.isNotBlank() },
        errorDescription = parameters["error_description"]?.takeIf { it.isNotBlank() },
    )
}
