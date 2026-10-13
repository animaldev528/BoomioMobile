package com.nuvio.app.core.auth

internal expect object AuthStorage {
    fun loadAnonymousUserId(): String?
    fun saveAnonymousUserId(userId: String)
    fun clearAnonymousUserId()

    /**
     * The PKCE verifier for an in-flight browser sign-in (Google).
     *
     * Kept on disk rather than in memory because the trip out to the account chooser leaves
     * the app: if the platform reclaims the process while the person is choosing, the callback
     * arrives at a fresh process and the code cannot be exchanged without it. The verifier is
     * not a credential on its own — it is only meaningful to whoever holds the matching
     * authorization code, and that never reaches this device until the person comes back.
     */
    fun loadAuthCodeVerifier(): String?
    fun saveAuthCodeVerifier(value: String)
    fun clearAuthCodeVerifier()
}
