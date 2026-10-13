package com.nuvio.app.core.auth

import io.github.jan.supabase.auth.CodeVerifierCache

/**
 * Where supabase-kt keeps the PKCE verifier across a browser sign-in.
 *
 * The SDK's own default is an in-memory map, which is the right shape for a flow that never
 * leaves the app and the wrong one for this: "Continue with Google" hands the person to the
 * account chooser, and Android is free to reclaim the process while they are there. The
 * callback then arrives at a fresh process, `exchangeCodeForSession` finds no verifier, and
 * the sign-in fails with the person already authenticated at Google — an error they can
 * neither see nor fix. On disk it survives that, and [AuthStorage] is already the app's
 * small-string store.
 */
internal object AuthCodeVerifierCache : CodeVerifierCache {
    override suspend fun saveCodeVerifier(codeVerifier: String) {
        AuthStorage.saveAuthCodeVerifier(codeVerifier)
    }

    override suspend fun loadCodeVerifier(): String? = AuthStorage.loadAuthCodeVerifier()

    override suspend fun deleteCodeVerifier() {
        AuthStorage.clearAuthCodeVerifier()
    }
}
