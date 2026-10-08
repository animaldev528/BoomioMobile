package com.nuvio.app.core.mtls

import android.util.Log
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * Handing the client certificate to an HTTP client. This is `docs/mtls-plan.md` P2.4 — the step
 * between "the device holds a certificate the edge has listed" and "the device actually presents
 * it".
 *
 * The whole surface is [withClientCertificate], one line at a construction site:
 *
 * ```
 * OkHttpClient.Builder().withOverlayProxy().withClientCertificate()
 * ```
 *
 * ── Why the attach is safe to apply anywhere ─────────────────────────────────
 * A TLS client sends its certificate **only if the server asks for one** — RFC 8446 §4.4.2 (and
 * §7.4.6 for 1.2). Nothing here inspects the URL, so pointing a client at a host that does not run
 * client auth costs one unused key manager and changes no byte on the wire. That is what makes
 * "attach at every Boomio-facing client" a defensible default rather than a list that has to be
 * kept in sync with the edge's per-path enforcement (§8), which is still being decided in §13.
 *
 * ── ⚠️ It is a no-op until the device has a certificate, and that is load-bearing ───
 * [TlsContext] is read from the keystore at the moment a client is *built*, so at start-up — before
 * enrollment has landed and registration has run — every client is built exactly as it is today.
 * Getting the certificate in afterwards therefore requires the client to be **rebuilt**, which is
 * P2.5 and is why P2.5 is not optional: attaching here and rebuilding there are two halves of one
 * change, and a device that registers but never rebuilds looks precisely like a device whose mTLS
 * does not work.
 *
 * ⚠️ The same property is why the certificate is *not* fetched per request. There is no
 * `SSLSocketFactory` that consults a callback mid-handshake, and OkHttp resolves a client's TLS
 * configuration when the client is constructed.
 *
 * ── Where it is deliberately *not* applied ───────────────────────────────────
 * `PlayerPlaybackNetworking` (its `checkServerTrusted = Unit` is scoped to playback and §8 says
 * leave it), `OverlayEnrollment.enrollmentHttpClient` (enrollment is what *creates* the tunnel, so
 * a client that could not complete without the tunnel would deadlock), and the pairing client
 * (`BoomioSessionRepository`, which runs before a session exists). Attaching there would not
 * merely be useless — on the playback client it would be a lie, because trust-all is the point.
 */
internal object MtlsSsl {

    private const val TAG = "BoomioMtls"

    /**
     * A TLS configuration that presents the client certificate: what an HTTP client needs, plus the
     * fingerprint it was built for so the cache below can tell when it has gone stale.
     */
    internal class TlsContext(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
        val fingerprint: String,
    )

    /**
     * Where the certificate comes from.
     *
     * An interface because the host suite cannot reach an `AndroidKeyStore` — there is no provider
     * for one off a device — and the cache below is the only part of this file with a decision in
     * it, so it is the part that has to be testable. See [MtlsSslTest].
     */
    internal interface CertificateSource {
        /** SHA-256 of the certificate a handshake would present, or `null` if there is none. */
        fun fingerprint(): String?

        /** Build a context for the certificate hashing to [fingerprint]. */
        fun build(fingerprint: String): TlsContext
    }

    /** The real source: the `AndroidKeyStore` alias [MtlsIdentity] owns. */
    internal val androidKeyStoreSource: CertificateSource = object : CertificateSource {

        override fun fingerprint(): String? =
            MtlsRegistration.presentableCertificate()?.let { sha256Hex(it.encoded) }

        override fun build(fingerprint: String): TlsContext {
            // ⚠️ The logging lives here rather than in [context] on purpose. `android.util.Log` is
            // unavailable to a JVM host test unless it runs under Robolectric, and the cache above
            // is tested without one — so the Android-only half of this file is the half that talks
            // to Android. [context] catches what this throws and says nothing.
            try {
                val trustManagers = platformTrustManagers()
                val trustManager = trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                    ?: throw IllegalStateException("the platform offers no X509 trust manager")
                val context = SSLContext.getInstance("TLS")
                context.init(keyManagers(), trustManagers, null)
                return TlsContext(context.socketFactory, trustManager, fingerprint)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not build a TLS context for the client certificate", t)
                throw t
            }
        }
    }

    @Volatile
    internal var source: CertificateSource = androidKeyStoreSource

    @Volatile
    private var cached: TlsContext? = null

    /** The context to attach, or `null` when this device has nothing to present. */
    internal fun context(): TlsContext? {
        // Re-read every call: this is a keystore lookup plus a hash, and it is the *only* thing that
        // notices the certificate changed.
        val fingerprint = source.fingerprint() ?: return null
        cached?.let { if (it.fingerprint == fingerprint) return it }
        synchronized(this) {
            cached?.let { if (it.fingerprint == fingerprint) return it }
            // A failure here must not escape into a client's construction — a client that cannot get
            // a client certificate is the pre-registration state, which is ordinary, and the
            // alternative is a crash on start-up for a device that merely has not enrolled yet.
            // Nothing is cached on failure, so the next call retries.
            val built = try {
                source.build(fingerprint)
            } catch (t: Throwable) {
                return null
            }
            cached = built
            return built
        }
    }

    /**
     * There is deliberately **no `invalidate()`**.
     *
     * The cache is keyed on the certificate's own hash, so re-minting for a new name, replacing the
     * key, or deleting the identity all produce a different fingerprint — or no fingerprint, which
     * returns before the cache is consulted — and the stale entry is simply never matched again. An
     * invalidation call would be a second source of truth for "the certificate changed" that a
     * caller has to remember to make, and this file exists because that kind of obligation is how
     * mTLS ends up silently off.
     */
    private fun keyManagers(): Array<KeyManager> =
        clientKeyManagers(
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) },
            MtlsIdentity.KEY_ALIAS,
        )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Point an `OkHttpClient.Builder` at the client certificate, or leave it alone.
 *
 * Leaving it alone is the common case, and it is not an optimisation: the alternative — attaching a
 * context with no identity behind it — would replace OkHttp's trust manager for nothing.
 */
internal fun OkHttpClient.Builder.withClientCertificate(): OkHttpClient.Builder {
    val context = MtlsSsl.context() ?: return this
    return sslSocketFactory(context.socketFactory, context.trustManager)
}

/**
 * The trust managers to keep alongside the certificate.
 *
 * ⚠️ These are the **platform defaults, built the same way OkHttp builds them itself**
 * (`Platform.platformTrustManager()` is exactly these three lines), so attaching a client
 * certificate changes *who we are* and nothing about *who we trust*. That matters twice over: this
 * app's trust decisions belong to `network_security_config.xml`, which the default `X509TrustManager`
 * honours, and a custom trust manager sneaked in beside the key manager is how "we added mTLS" turns
 * into "we stopped verifying the server".
 */
private fun platformTrustManagers(): Array<TrustManager> =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers

/**
 * The key manager for [alias], wrapped so it cannot decline to present it.
 *
 * ⚠️ **This is the one thing in P2.4 that turns a silent failure into a loud one, and it is worth
 * the ten lines.** A client picks its certificate through `chooseClientAlias`, and the platform's
 * implementation filters candidates by the certificate authorities the *server* listed in its
 * `CertificateRequest`. Our certificate is self-signed and no CA ever signed it, so if the edge
 * ever sends a non-empty CA list — a `trust_pool` appearing beside the `leafdir` verifier, a
 * reordered `client_auth` block, a future Caddy that derives the list differently — the platform
 * finds no match and returns `null`. The client then sends **no certificate at all**, and the edge
 * answers with a handshake failure that reads exactly like a broken certificate. §8 calls this out
 * by name as the failure mode to avoid.
 *
 * So the delegate is asked first and its answer is honoured; ours is the fallback, and it is only
 * ever reached when the platform was going to send nothing. Presenting a certificate the server
 * may reject is strictly more informative than presenting none, and against the edge as configured
 * today (a verifier with no trust pool ⇒ an empty CA list ⇒ the delegate matches) this is a no-op.
 *
 * Only `chooseClientAlias` is overridden, because it is the method every TLS stack calls to answer
 * a `CertificateRequest`; the rest of the interface is delegated untouched, which is what keeps
 * `getCertificateChain`/`getPrivateKey` reading from the keystore rather than from here.
 */
internal class AliasPreferredKeyManager(
    private val delegate: X509KeyManager,
    private val alias: String,
) : X509KeyManager by delegate {

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = delegate.chooseClientAlias(keyType, issuers, socket) ?: alias
}

/**
 * The key managers for [keyStore]'s [alias], or none.
 *
 * Takes the `KeyStore` as an argument rather than reaching for `AndroidKeyStore` so that the host
 * suite can run it against a real provider — see [MtlsSslTest], which builds a genuine RSA-2048
 * keypair and a genuine minted certificate into a `PKCS12` store and drives the real
 * `KeyManagerFactory` through it. Returning an empty array is the honest answer for an alias that
 * is not there: an `SSLContext` with no key managers sends nothing, which is what "this device has
 * no certificate" should mean.
 *
 * ⚠️ `init(keyStore, null)` — the null password — is not sloppiness. An `AndroidKeyStore` entry's
 * private key has no password to give: the key never leaves the keystore, and the provider
 * authorises its use through the key's own `KeyGenParameterSpec` ([MtlsIdentity.spec]), not through
 * a passphrase.
 */
internal fun clientKeyManagers(keyStore: KeyStore, alias: String): Array<KeyManager> {
    if (!keyStore.containsAlias(alias)) return emptyArray()
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    factory.init(keyStore, null)
    val delegate = factory.keyManagers.filterIsInstance<X509KeyManager>().firstOrNull()
        ?: return emptyArray()
    return arrayOf(AliasPreferredKeyManager(delegate, alias))
}
