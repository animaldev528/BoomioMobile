package com.nuvio.app.features.trakt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktPkceTest {

    @Test
    fun `pkce material uses a compliant verifier and its S256 challenge`() {
        val material = createTraktPkceMaterial(verifierEntropy = ByteArray(32) { it.toByte() })

        assertEquals(43, material.verifier.length)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", material.verifier)
        assertTrue(material.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        assertFalse('=' in material.verifier)
        // The challenge is a pure function of the verifier, which is what lets the authorize URL be
        // re-derived after a cold start rather than remembered.
        assertEquals(material.challenge, traktPkceChallenge(material.verifier))
        assertEquals(43, material.challenge.length)
    }

    @Test
    fun `the baked flow's authorization URL carries no PKCE parameters`() {
        val url = buildTraktAuthorizationUrl(
            clientId = "baked-id",
            redirectUri = "nuvio://auth/trakt",
            state = "state-1",
            codeChallenge = null,
        )

        assertTrue(url.startsWith("https://trakt.tv/oauth/authorize?"))
        assertTrue("response_type=code" in url)
        assertTrue("state=state-1" in url)
        assertTrue("redirect_uri=nuvio%3A%2F%2Fauth%2Ftrakt" in url)
        // ⚠️ The regression this test exists for: the build's own app is a registered id+secret pair
        // and its flow must stay exactly what it was.
        assertFalse("code_challenge" in url)
        assertFalse("code_challenge_method" in url)
    }

    @Test
    fun `the deployment's authorization URL declares S256 and the challenge`() {
        val url = buildTraktAuthorizationUrl(
            clientId = "deployment-id",
            redirectUri = "nuvio://auth/trakt",
            state = "state-1",
            codeChallenge = "a-challenge",
        )

        assertTrue("client_id=deployment-id" in url)
        assertTrue("code_challenge=a-challenge" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("state=state-1" in url)
    }
}
