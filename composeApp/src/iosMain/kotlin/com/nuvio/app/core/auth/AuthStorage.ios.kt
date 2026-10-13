package com.nuvio.app.core.auth

import platform.Foundation.NSUserDefaults

actual object AuthStorage {
    private const val KEY_ANONYMOUS_USER_ID = "anonymous_user_id"
    private const val KEY_AUTH_CODE_VERIFIER = "pkce_code_verifier"

    actual fun loadAnonymousUserId(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(KEY_ANONYMOUS_USER_ID)

    actual fun saveAnonymousUserId(userId: String) {
        NSUserDefaults.standardUserDefaults.setObject(userId, forKey = KEY_ANONYMOUS_USER_ID)
    }

    actual fun clearAnonymousUserId() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY_ANONYMOUS_USER_ID)
    }

    actual fun loadAuthCodeVerifier(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(KEY_AUTH_CODE_VERIFIER)

    actual fun saveAuthCodeVerifier(value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = KEY_AUTH_CODE_VERIFIER)
    }

    actual fun clearAuthCodeVerifier() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY_AUTH_CODE_VERIFIER)
    }
}
