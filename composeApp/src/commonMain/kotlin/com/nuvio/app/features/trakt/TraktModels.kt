package com.nuvio.app.features.trakt

import kotlinx.serialization.Serializable

@Serializable
data class TraktAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val createdAt: Long? = null,
    val expiresIn: Int? = null,
    val username: String? = null,
    val userSlug: String? = null,
    val pendingAuthorizationState: String? = null,
    val pendingAuthorizationStartedAtMillis: Long? = null,
    /**
     * The PKCE verifier for the pending authorization, when this deployment's Trakt app is the one
     * the flow is running against.
     *
     * ⚠️ **Stored with the state, not held in memory, because the URL is re-derived.** A cold start,
     * or any later `pendingAuthorizationUrl`, rebuilds the authorize URL from this record — and a
     * `code_challenge` that no longer matches the verifier is a sign-in that fails at the token
     * exchange with no visible cause. Null means "no PKCE", which is the baked-app flow and must stay
     * byte-for-byte what it was.
     */
    val pendingPkceVerifier: String? = null,
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
}

enum class TraktConnectionMode {
    DISCONNECTED,
    AWAITING_APPROVAL,
    CONNECTED,
}

data class TraktAuthUiState(
    val mode: TraktConnectionMode = TraktConnectionMode.DISCONNECTED,
    val credentialsConfigured: Boolean = true,
    val isLoading: Boolean = false,
    val username: String? = null,
    val tokenExpiresAtMillis: Long? = null,
    val pendingAuthorizationStartedAtMillis: Long? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

enum class TraktBrandAsset {
    Glyph,
    Wordmark,
}

@Serializable
enum class TraktListType {
    WATCHLIST,
    PERSONAL,
}

@Serializable
enum class TraktListPrivacy(val apiValue: String) {
    PRIVATE("private"),
    LINK("link"),
    FRIENDS("friends"),
    PUBLIC("public");

    companion object {
        fun fromApi(value: String?): TraktListPrivacy =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: PRIVATE
    }
}

@Serializable
data class TraktListTab(
    val key: String,
    val title: String,
    val type: TraktListType,
    val traktListId: Long? = null,
    val slug: String? = null,
    val description: String? = null,
    val privacy: TraktListPrivacy? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
)

data class TraktMembershipSnapshot(
    val listMembership: Map<String, Boolean> = emptyMap(),
)

data class TraktMembershipChanges(
    val desiredMembership: Map<String, Boolean>,
)
