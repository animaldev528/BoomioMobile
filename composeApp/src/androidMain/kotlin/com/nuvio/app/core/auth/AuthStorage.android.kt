package com.nuvio.app.core.auth

import android.content.Context
import android.content.SharedPreferences

actual object AuthStorage {
    private const val PREFS_NAME = "nuvio_auth"
    private const val KEY_ANONYMOUS_USER_ID = "anonymous_user_id"
    private const val KEY_AUTH_CODE_VERIFIER = "pkce_code_verifier"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual fun loadAnonymousUserId(): String? =
        preferences?.getString(KEY_ANONYMOUS_USER_ID, null)

    actual fun saveAnonymousUserId(userId: String) {
        preferences?.edit()?.putString(KEY_ANONYMOUS_USER_ID, userId)?.apply()
    }

    actual fun clearAnonymousUserId() {
        preferences?.edit()?.remove(KEY_ANONYMOUS_USER_ID)?.apply()
    }

    actual fun loadAuthCodeVerifier(): String? =
        preferences?.getString(KEY_AUTH_CODE_VERIFIER, null)

    actual fun saveAuthCodeVerifier(value: String) {
        preferences?.edit()?.putString(KEY_AUTH_CODE_VERIFIER, value)?.apply()
    }

    actual fun clearAuthCodeVerifier() {
        preferences?.edit()?.remove(KEY_AUTH_CODE_VERIFIER)?.apply()
    }
}
