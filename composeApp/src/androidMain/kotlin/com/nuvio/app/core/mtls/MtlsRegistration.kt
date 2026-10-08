package com.nuvio.app.core.mtls

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.app.features.boomio.companionRestBaseUrl
import com.nuvio.app.features.boomio.createBoomioHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The device's registration with the edge — `docs/mtls-plan.md` P2.3, with P2.6's re-registration
 * rule and P2's *"no re-registration on relaunch"* satisfied by [CertPlan.AlreadyRegistered].
 *
 * This is the half that needs a device: it reads the server-assigned name the overlay learned, drives
 * [MtlsIdentity] to mint and bind a certificate, and POSTs it on the session the app already holds.
 * Everything with a decision in it lives in [planCertificate] and [MtlsRegistrar], which is why this
 * file is mostly wiring.
 *
 * ── Which plane the POST takes, and why it is not the enrollment client ───────
 * `OverlayEnrollment`'s client is deliberately plain — enrollment *creates* the tunnel, so a client
 * that routed through the tunnel could never complete the call that brings it up. Registration is
 * the opposite case: it happens **after** an assignment lands, and the plan puts the certificate
 * endpoint behind the tunnel (§10.1, and the operator's own sequence — "need wireguard once at
 * least to be provisioned"). So this uses the app's ordinary `createBoomioHttpClient()`, which
 * carries the overlay proxy and the DNS seam and therefore works whether or not the tunnel is up
 * yet.
 *
 * ⚠️ It carries **no client certificate**, and that is not an oversight: registration is what
 * *creates* the certificate, so requiring one would be circular. The call is authenticated by the
 * `bs_ses_` session bearer alone, and the route is deliberately not one of §8's enforced sites.
 *
 * ── The prefs file, and why it is not the enrollment one ─────────────────────
 * [com.nuvio.app.core.overlay.OverlayEnrollment] owns `boomio_overlay_enrollment` and everything in
 * it is the *server's* answer about where to reach it. This file is the *device's* own identity, and
 * the two have different lifetimes: a device can be re-enrolled (a new address, a new endpoint) while
 * keeping the same certificate, and a certificate can be re-minted without touching the address.
 * Sharing a file would invite exactly the confusion [CertPlan] exists to prevent.
 *
 * Plain `SharedPreferences`, like the enrollment store: everything here is public by construction —
 * a certificate is sent in the clear on every handshake (which is why the server writes it `0644`),
 * and the rest is the server's own fingerprint for it. The private key is in the AndroidKeyStore and
 * is never written here.
 */
internal object MtlsRegistration {

    private const val TAG = "BoomioMtls"
    private const val PREFS = "boomio_mtls"

    private const val KEY_CERT_PEM = "cert_pem"
    private const val KEY_CERT_NAME = "cert_name"
    private const val KEY_REGISTERED = "cert_registered"
    private const val KEY_REGISTERED_FINGERPRINT = "cert_registered_fingerprint"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One registration at a time.
     *
     * ⚠️ Two triggers are expected to race — startup applies a cached assignment and calls for
     * registration while enrollment may refresh and call for it again — and without this both would
     * read the same "not yet registered" state and both POST the same PEM. That is not harmful to
     * the server (the write is idempotent), but it spends two of the device's ten-per-minute budget
     * and writes two ledger rows for one event.
     */
    private val lock = Mutex()

    private val _state = MutableStateFlow<MtlsRegistrationState>(MtlsRegistrationState.Idle)
    val state: StateFlow<MtlsRegistrationState> = _state.asStateFlow()

    @Volatile
    private var started = false

    @Volatile
    private var registrar: MtlsRegistrar? = null

    @Volatile
    private var http: HttpClient? = null

    fun initialize(context: Context) {
        if (started) return
        started = true

        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        registrar = MtlsRegistrar(
            api = ::apiOrNull,
            assignedName = { BoomioConfig.overlayDeviceName },
            held = { read(store) },
            mintAndInstall = ::mintAndInstall,
            remember = { write(store, it) },
        )
    }

    /**
     * Bring the registration up to date, and report what happened.
     *
     * Ordinary callers should use [requestRegistration]: this suspends for as long as the retry
     * window lasts, which is up to ~16s when the tunnel is still coming up.
     *
     * @param force re-send even though these bytes were already acknowledged — P2.6's "the server
     *   does not know this device" path, and the only thing that causes a second POST.
     */
    suspend fun ensureRegistered(force: Boolean = false): MtlsRegistrationState = lock.withLock {
        val runner = registrar ?: return MtlsRegistrationState.Skipped("mTLS is not initialized")
        val result = runner.register(force)
        _state.value = result
        Log.i(TAG, "mTLS registration: $result")
        result
    }

    /**
     * Ask for a registration without waiting for it.
     *
     * Safe to call from anywhere and cheap to call repeatedly: the plan is re-read under the lock
     * each time, so a device that is already registered does no network I/O at all.
     */
    fun requestRegistration(force: Boolean = false) {
        if (!started) return
        scope.launch { ensureRegistered(force) }
    }

    /**
     * The client's certificate, PEM. `null` until one has been minted and bound.
     *
     * Read from the keystore rather than from the stored PEM, so it is the certificate that is
     * actually usable for a handshake — the two can differ for exactly one window, between a
     * regenerated key and the re-registration that follows it, and in that window the stored PEM is
     * the wrong answer.
     */
    fun certificatePem(): String? = runCatching {
        MtlsIdentity.certificate()?.let { MtlsCertificate.toPem(it.encoded) }
    }.getOrNull()

    // ── the wire ─────────────────────────────────────────────────────────────

    private fun apiOrNull(): MtlsRegistrationApi? {
        val token = BoomioSessionRepository.bearerToken()?.takeIf { it.isNotBlank() } ?: return null
        val base = BoomioConfig.companionRestBaseUrl.takeIf { it.isNotBlank() } ?: return null
        return BscMtlsRegistrationApi(baseUrl = base, token = token, http = client())
    }

    /**
     * One client for the process.
     *
     * ⚠️ Caching this is safe *because of how the overlay seam is built*, and it is worth saying so
     * rather than leaving it to be rediscovered: `OverlayProxy.selector` is a `ProxySelector`
     * OkHttp consults **once per route, not once per connection attempt at construction**, so a
     * relay that comes up after this client was built is picked up by the next new connection. The
     * alternative — building a client per registration attempt — would buy nothing and leak an
     * engine each time.
     */
    private fun client(): HttpClient {
        http?.let { return it }
        return synchronized(this) {
            http ?: createBoomioHttpClient().also { http = it }
        }
    }

    // ── the device ───────────────────────────────────────────────────────────

    /**
     * Mint for [name] and bind it to the keystore key.
     *
     * ⚠️ [MtlsIdentity.loadOrCreate] — never `generate`. This is the whole of P2.6's *"(do **not**
     * silently generate a new key)"*: the key is the one thing in this flow that cannot be replaced
     * without leaving a live allow-list entry behind, so the only path to a new key here is a device
     * that has no key at all.
     */
    private fun mintAndInstall(name: String): ClientCertificate {
        val keyPair = MtlsIdentity.loadOrCreate()
        val certificate = MtlsCertificate.selfSign(name, keyPair, System.currentTimeMillis())
        MtlsIdentity.installCertificate(keyPair, certificate)
        Log.i(TAG, "Minted a client certificate for $name (${certificate.fingerprintSha256.take(16)}…)")
        return certificate
    }

    private fun read(store: SharedPreferences): HeldCertificate? {
        val pem = store.getString(KEY_CERT_PEM, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = store.getString(KEY_CERT_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        return HeldCertificate(
            assignedName = name,
            pem = pem,
            matchesCurrentKey = isBoundToCurrentKey(pem),
            registered = store.getBoolean(KEY_REGISTERED, false),
            registeredFingerprintSha256 = store.getString(KEY_REGISTERED_FINGERPRINT, null),
        )
    }

    private fun write(store: SharedPreferences, held: HeldCertificate) {
        store.edit()
            .putString(KEY_CERT_PEM, held.pem)
            .putString(KEY_CERT_NAME, held.assignedName)
            .putBoolean(KEY_REGISTERED, held.registered)
            .putString(KEY_REGISTERED_FINGERPRINT, held.registeredFingerprintSha256)
            .apply()
    }

    /**
     * Whether the stored certificate belongs to the key the keystore holds now.
     *
     * ⚠️ This is the check that catches a **lost key behind a kept certificate**, and the reason it
     * exists is that the server cannot catch it: `parseClientCertificate` validates the certificate
     * alone and never asks the device to prove it holds the private half. So a mismatched pair
     * registers with a `200` and then fails the TLS handshake on the *edge*.
     *
     * AndroidKeyStore returns its own placeholder certificate (`CN=Unverified…`) for an alias whose
     * key was replaced, so the comparison is against whatever is bound now, not against a stub
     * assumption. A stored PEM that will not parse answers `false` — treated as unusable, which is
     * what it is.
     */
    private fun isBoundToCurrentKey(pem: String): Boolean = runCatching {
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII))) as X509Certificate
        MtlsIdentity.certificate()?.publicKey == parsed.publicKey
    }.getOrDefault(false)
}

/**
 * `POST /api/overlay/cert` on the companion session.
 *
 * `expectSuccess = false` on the client, so every status is handed to [decodeCertRegistration]
 * rather than thrown — which matters because the route's refusals are the interesting replies and
 * an exception would flatten all of them into one.
 */
internal class BscMtlsRegistrationApi(
    private val baseUrl: String,
    private val token: String,
    private val http: HttpClient,
) : MtlsRegistrationApi {

    override suspend fun register(pem: String): CertRegistrationAck {
        val response = runCatching {
            http.post("$baseUrl/api/overlay/cert") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(mtlsRegistrationJson.encodeToString(CertRegistrationRequestDto(pem)))
            }
        }.getOrElse {
            // A connect timeout while the tunnel comes up is the expected first failure, so this is
            // `Deferred` and not an error: the registrar's retry window exists for this.
            return CertRegistrationAck.Deferred("Could not reach the server: ${it.message}")
        }
        return decodeCertRegistration(response.status.value, response.bodyAsText())
    }
}
