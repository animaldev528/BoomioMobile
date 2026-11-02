package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.Secret
import io.github.aivanyuk.airkast.crypto.ChaCha20Poly1305
import io.github.aivanyuk.airkast.crypto.Ed25519
import io.github.aivanyuk.airkast.crypto.Hkdf
import io.github.aivanyuk.airkast.crypto.SrpClient
import io.github.aivanyuk.airkast.crypto.X25519
import io.github.aivanyuk.airkast.wire.Tlv8
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID

/**
 * HomeKit pair-setup's first two exchanges with the transient flag and the fixed PIN 3939: a
 * pairing that lasts one connection and needs no code on the screen. Returns the SRP session key
 * the channel keys derive from.
 */
internal object TransientPairing {
    private const val PIN = "3939"
    private val HEADERS = listOf("X-Apple-HKP" to "4")

    fun pair(connection: ControlConnection): ByteArray {
        connection.exchange("POST", "/pair-pin-start", ControlConnection.HTTP, HEADERS, contentType = TLV)
        val m2 =
            post(
                connection,
                "/pair-setup",
                HEADERS,
                Tlv8.METHOD to byteArrayOf(0),
                Tlv8.SEQUENCE to byteArrayOf(1),
                Tlv8.FLAGS to byteArrayOf(Tlv8.FLAG_TRANSIENT.toByte()),
            )
        val srp = SrpClient("Pair-Setup", PIN)
        val proof = srp.respond(m2.require(Tlv8.SALT, "salt"), m2.require(Tlv8.PUBLIC_KEY, "public key"))
        val m4 =
            post(
                connection,
                "/pair-setup",
                HEADERS,
                Tlv8.SEQUENCE to byteArrayOf(3),
                Tlv8.PUBLIC_KEY to srp.publicKey,
                Tlv8.PROOF to proof,
            )
        if (!srp.verifyServer(m4.require(Tlv8.PROOF, "proof"))) {
            throw AirkastException.PairingFailed("The receiver's proof does not match")
        }
        return srp.sessionKey
    }
}

/**
 * HomeKit pair-setup with a [Secret]: SRP as [TransientPairing] does it, with the PIN the receiver
 * shows or the password set on it, then an exchange of long-term Ed25519 keys, each signed and sent
 * under a key from the SRP secret. [start] puts a PIN on the screen, and [finish] takes the secret
 * and returns the [Credentials] that [PairVerify] proves on every later connection.
 */
internal class SecretPairing private constructor(
    private val connection: ControlConnection,
    private val kind: Secret,
    private val salt: ByteArray,
    private val serverPublic: ByteArray,
) : AutoCloseable {
    fun finish(code: String): Credentials {
        val srp = SrpClient("Pair-Setup", code)
        val proof = srp.respond(salt, serverPublic)
        val m4 =
            post(
                connection,
                "/pair-setup",
                HEADERS,
                Tlv8.SEQUENCE to byteArrayOf(3),
                Tlv8.PUBLIC_KEY to srp.publicKey,
                Tlv8.PROOF to proof,
            ) { error ->
                if (error == Tlv8.ERROR_AUTHENTICATION) AirkastException.SecretRejected(kind) else null
            }
        if (!srp.verifyServer(m4.require(Tlv8.PROOF, "proof"))) {
            throw AirkastException.PairingFailed("The receiver's proof does not match")
        }

        val secret = srp.sessionKey
        val key = Hkdf.sha512(secret, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val seed = ByteArray(Ed25519.KEY_LENGTH).also(RANDOM::nextBytes)
        val senderId = UUID.randomUUID().toString().toByteArray()
        val senderKey = Ed25519.publicKey(seed)
        val senderX = Hkdf.sha512(secret, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val m5 =
            Tlv8.encode(
                Tlv8.IDENTIFIER to senderId,
                Tlv8.PUBLIC_KEY to senderKey,
                Tlv8.SIGNATURE to Ed25519.sign(seed, senderX + senderId + senderKey),
            )
        val m6 =
            post(
                connection,
                "/pair-setup",
                HEADERS,
                Tlv8.SEQUENCE to byteArrayOf(5),
                Tlv8.ENCRYPTED_DATA to ChaCha20Poly1305.seal(key, nonce("PS-Msg05"), m5, ByteArray(0)),
            )
        val receiver = Tlv8.decode(open(key, "PS-Msg06", m6.require(Tlv8.ENCRYPTED_DATA, "encrypted data")))
        val receiverId = receiver.require(Tlv8.IDENTIFIER, "identifier")
        val receiverKey = receiver.require(Tlv8.PUBLIC_KEY, "public key")
        val receiverX = Hkdf.sha512(secret, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        if (!Ed25519.verify(
                receiverKey,
                receiverX + receiverId + receiverKey,
                receiver.require(Tlv8.SIGNATURE, "signature"),
            )
        ) {
            throw AirkastException.PairingFailed("The receiver's signature does not match")
        }
        return Credentials(receiverKey, seed, receiverId, senderId, code.takeIf { kind == Secret.Password })
    }

    override fun close() = connection.close()

    companion object {
        private val HEADERS = listOf("X-Apple-HKP" to "3")

        /**
         * Starts pairing with the receiver on [connection], and takes it over: [close] closes it.
         * For a [Secret.Pin], the receiver shows one. A password needs nothing shown, and owntone
         * sends no `/pair-pin-start` for one.
         */
        fun start(
            connection: ControlConnection,
            secret: Secret,
        ): SecretPairing {
            if (secret == Secret.Pin) {
                connection.exchange("POST", "/pair-pin-start", ControlConnection.HTTP, HEADERS, contentType = TLV)
            }
            val m2 =
                post(
                    connection,
                    "/pair-setup",
                    HEADERS,
                    Tlv8.METHOD to byteArrayOf(0),
                    Tlv8.SEQUENCE to byteArrayOf(1),
                )
            return SecretPairing(
                connection,
                secret,
                m2.require(Tlv8.SALT, "salt"),
                m2.require(Tlv8.PUBLIC_KEY, "public key"),
            )
        }
    }
}

/**
 * HomeKit pair-verify with [Credentials] from a [SecretPairing]: an X25519 exchange that each side
 * signs with its long-term key. Returns the shared secret the channel keys derive from.
 */
internal object PairVerify {
    private val HEADERS = listOf("X-Apple-HKP" to "3")

    fun verify(
        connection: ControlConnection,
        credentials: Credentials,
    ): ByteArray {
        val ephemeral = ByteArray(X25519.KEY_LENGTH).also(RANDOM::nextBytes)
        val public = X25519.publicKey(ephemeral)
        val m2 =
            post(
                connection,
                "/pair-verify",
                HEADERS,
                Tlv8.SEQUENCE to byteArrayOf(1),
                Tlv8.PUBLIC_KEY to public,
                rejected = ::refused,
            )
        val receiverPublic = m2.require(Tlv8.PUBLIC_KEY, "public key")
        val secret = X25519.sharedSecret(ephemeral, receiverPublic)
        val key = Hkdf.sha512(secret, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val receiver = Tlv8.decode(open(key, "PV-Msg02", m2.require(Tlv8.ENCRYPTED_DATA, "encrypted data")))
        val receiverId = receiver.require(Tlv8.IDENTIFIER, "identifier")
        if (!receiverId.contentEquals(credentials.receiverId)) {
            throw AirkastException.PairingFailed(
                "The receiver is not the one the credentials are for",
                credentialsRefused = true,
            )
        }
        val signature = receiver.require(Tlv8.SIGNATURE, "signature")
        if (!Ed25519.verify(credentials.receiverKey, receiverPublic + receiverId + public, signature)) {
            throw AirkastException.PairingFailed("The receiver's signature does not match", credentialsRefused = true)
        }
        val m3 =
            Tlv8.encode(
                Tlv8.IDENTIFIER to credentials.senderId,
                Tlv8.SIGNATURE to Ed25519.sign(credentials.senderSeed, public + credentials.senderId + receiverPublic),
            )
        post(
            connection,
            "/pair-verify",
            HEADERS,
            Tlv8.SEQUENCE to byteArrayOf(3),
            Tlv8.ENCRYPTED_DATA to ChaCha20Poly1305.seal(key, nonce("PV-Msg03"), m3, ByteArray(0)),
            rejected = ::refused,
        )
        return secret
    }

    /**
     * HomeKit's Authentication error, which a receiver answers when it does not know the sender or
     * its proof fails. Any other error, such as a busy receiver's, says nothing of the credentials.
     */
    private fun refused(code: Int): AirkastException? =
        if (code == Tlv8.ERROR_AUTHENTICATION) {
            AirkastException.PairingFailed("The receiver no longer knows this sender", credentialsRefused = true)
        } else {
            null
        }
}

private const val TLV = "application/octet-stream"
private val RANDOM = SecureRandom()

/**
 * Posts one pairing message and returns the receiver's TLV. An error in it throws what [rejected]
 * makes of its code, or [AirkastException.PairingFailed].
 */
private fun post(
    connection: ControlConnection,
    path: String,
    headers: List<Pair<String, String>>,
    vararg items: Pair<Int, ByteArray>,
    rejected: (Int) -> AirkastException? = { null },
): Map<Int, ByteArray> {
    val response = connection.exchange("POST", path, ControlConnection.HTTP, headers, Tlv8.encode(*items), TLV)
    if (response.status !in 200..299) {
        throw AirkastException.PairingFailed("${path.removePrefix("/")} answered ${response.status}")
    }
    val tlv = Tlv8.decode(response.body)
    tlv[Tlv8.ERROR]?.let {
        val code = it.firstOrNull()?.toInt() ?: 0
        throw rejected(code) ?: AirkastException.PairingFailed("${path.removePrefix("/")} error $code")
    }
    return tlv
}

private fun Map<Int, ByteArray>.require(
    type: Int,
    name: String,
): ByteArray = this[type] ?: throw AirkastException.PairingFailed("The receiver sent no $name")

/** HomeKit's 8-byte message nonces, padded on the left to ChaCha20's 12. */
private fun nonce(label: String): ByteArray = ByteArray(4) + label.toByteArray()

private fun open(
    key: ByteArray,
    label: String,
    sealed: ByteArray,
): ByteArray =
    try {
        ChaCha20Poly1305.open(key, nonce(label), sealed, ByteArray(0))
    } catch (e: GeneralSecurityException) {
        throw AirkastException.PairingFailed("The receiver's $label does not decrypt")
    }
