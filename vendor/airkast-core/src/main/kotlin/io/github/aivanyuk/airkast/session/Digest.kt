package io.github.aivanyuk.airkast.session

import java.security.MessageDigest

/**
 * HTTP Digest authentication with a receiver's password, as RFC 2617 has it without `qop`: the
 * receiver answers a request with 401 and a [Challenge], and every request after carries
 * [authorization]. owntone answers an AirPlay 2 SETUP this way, and pyatv an AirPlay 1 ANNOUNCE.
 */
internal class Digest private constructor(
    private val realm: String,
    private val nonce: String,
    private val password: String,
) {
    /** The `Authorization` header for [method] on [uri]. */
    fun authorization(
        method: String,
        uri: String,
    ): String {
        val ha1 = md5("$USERNAME:$realm:$password")
        val ha2 = md5("$method:$uri")
        val response = md5("$ha1:$nonce:$ha2")
        return "Digest username=\"$USERNAME\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
    }

    /** `WWW-Authenticate: Digest realm="…", nonce="…"`, which a password [answer]s. */
    class Challenge(
        private val realm: String,
        private val nonce: String,
    ) {
        fun answer(password: String): Digest = Digest(realm, nonce, password)
    }

    companion object {
        /** Apple TV's password login takes this user name, as Rapid7's scanner for it does. */
        private const val USERNAME = "AirPlay"
        private val PARAMETER = Regex("""(\w+)\s*=\s*"([^"]*)"""")

        /** The Digest challenge in a `WWW-Authenticate` header, or null for none or another scheme. */
        fun challenge(header: String?): Challenge? {
            if (header == null || !header.trimStart().startsWith("Digest ", ignoreCase = true)) return null
            val parameters = PARAMETER.findAll(header).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            return Challenge(parameters["realm"] ?: return null, parameters["nonce"] ?: return null)
        }

        private fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
