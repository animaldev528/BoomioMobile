package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.DisconnectReason
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Secret
import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.crypto.Hkdf
import io.github.aivanyuk.airkast.wire.BinaryPlist
import io.github.aivanyuk.airkast.wire.HttpMessage
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The steps every session opens with, whatever it streams: the control connection, pairing and its
 * keys, and the base SETUP with its Digest challenge.
 */
internal object Opening {
    private const val UNAUTHORIZED = 401
    private val INFO_QUALIFIER = BinaryPlist.encode(mapOf("qualifier" to listOf("txtAirPlay")))

    /**
     * Opens the control connection to [receiver] and pairs on it: pair-verify with the options'
     * credentials, transiently without. Returns the encrypted connection and the pairing's shared
     * secret, which the event channel's keys derive from too.
     */
    fun pair(
        receiver: Receiver,
        identity: SenderIdentity,
        options: SessionOptions,
    ): Paired {
        val control = control(receiver, identity, options)
        try {
            val credentials = options.credentials
            val sessionKey =
                if (credentials != null) PairVerify.verify(control, credentials) else TransientPairing.pair(control)
            control.encrypt(
                Hkdf.sha512(sessionKey, "Control-Salt", "Control-Write-Encryption-Key"),
                Hkdf.sha512(sessionKey, "Control-Salt", "Control-Read-Encryption-Key"),
            )
            Log(options.logger, "pairing").debug { if (credentials != null) "verified" else "paired transiently" }
            return Paired(control, sessionKey)
        } catch (e: Exception) {
            runCatching { control.close() }
            throw e
        }
    }

    /**
     * Asks [receiver] what it is with `GET /info`, which the LG CX, the Samsung HW-Q990B and a Mac
     * answer before pairing, and returns the answer as TXT record properties
     * ([Receiver.propertiesFromInfo]). The body asks for the record itself, as Apple's senders do
     * by Emanuele Cozzi's notes: without it, those three leave `txtAirPlay` out.
     */
    fun info(
        receiver: Receiver,
        identity: SenderIdentity,
        options: SessionOptions,
    ): Map<String, String> =
        try {
            control(receiver, identity, options).use { control ->
                val reply =
                    control
                        .exchange("GET", "/info", body = INFO_QUALIFIER, contentType = ControlConnection.BPLIST)
                        .requireSuccess("GET /info")
                if (!BinaryPlist.isPlist(reply.body)) {
                    throw AirkastException.UnexpectedReply(IOException("GET /info answered no plist"))
                }
                Receiver.propertiesFromInfo(plist(reply))
            }
        } catch (e: Exception) {
            throw failure(e)
        }

    /**
     * Starts pairing with [receiver] for [secret], showing a PIN if it is one, over the connection
     * the pairing then finishes on.
     */
    fun startPairing(
        receiver: Receiver,
        identity: SenderIdentity,
        options: SessionOptions,
        secret: Secret,
    ): SecretPairing {
        val control = control(receiver, identity, options)
        try {
            return SecretPairing.start(control, secret)
        } catch (e: Exception) {
            runCatching { control.close() }
            throw failure(e)
        }
    }

    /** [SecretPairing.finish], failing as the rest of the API does. */
    fun finishPairing(
        pairing: SecretPairing,
        code: String,
    ): Credentials =
        try {
            pairing.finish(code)
        } catch (e: Exception) {
            throw failure(e)
        }

    /**
     * The base SETUP's body: who the sender is, and how the receiver times it, over NTP to
     * [timing] or not at all.
     */
    fun sessionBody(
        identity: SenderIdentity,
        sessionId: String,
        timing: TimingResponder?,
    ): Map<String, Any?> =
        mapOf(
            "deviceID" to identity.deviceId,
            "sessionUUID" to sessionId,
            // tvOS (Apple TV 6,2) answers "None" with 500 and stalls ~32 s before answering
            // "NTP" with 400; "PTP" with no timingPort is accepted. Measured 2026-11-02.
            // LOCAL PATCH to upstream airkast 0.6.0 — see vendor/airkast-core/README.md.
            "timingProtocol" to if (timing != null) "NTP" else "PTP",
            "isMultiSelectAirPlay" to true,
            "groupContainsGroupLeader" to false,
            "macAddress" to identity.deviceId,
            "model" to identity.model,
            "name" to identity.name,
            "osBuildVersion" to identity.osBuildVersion,
            "osName" to identity.osName,
            "osVersion" to identity.osVersion,
            "senderSupportsRelay" to false,
            "sourceVersion" to identity.sourceVersion,
            "statsCollectionEnabled" to false,
        ) + (timing?.let { mapOf("timingPort" to it.port.toLong()) } ?: emptyMap())

    /**
     * Sends the base SETUP and returns its reply. A Digest challenge in a 401 is answered with the
     * options' password, which then signs every request on [control]; without one, it fails with
     * what makes `Airkast.connect` ask for it.
     *
     * Each SETUP may take the options' approval timeout: a Mac (960.13.25) asks its user whether
     * to allow a sender it has not seen, and holds SETUP until they answer.
     */
    fun setUp(
        control: ControlConnection,
        uri: String,
        body: Map<String, Any?>,
        options: SessionOptions,
        log: Log,
    ): Map<*, *> {
        val encoded = BinaryPlist.encode(body)

        fun setUp() =
            try {
                control.exchange(
                    "SETUP",
                    uri,
                    body = encoded,
                    contentType = ControlConnection.BPLIST,
                    timeout = options.approvalTimeout,
                )
            } catch (e: LateReply) {
                throw AirkastException.Timeout(
                    "No answer to SETUP in ${options.approvalTimeout}: the receiver may be asking its user to allow this sender",
                )
            }
        var base = setUp()
        if (base.status == UNAUTHORIZED) {
            control.digest = answer(base, options, log)
            base = setUp()
            if (base.status == UNAUTHORIZED) throw AirkastException.SecretRejected(Secret.Password)
        }
        base.requireSuccess("SETUP")
        val reply = plist(base)
        log.debug { "SETUP ${base.status} ${reply.keys}" }
        return reply
    }

    private fun answer(
        reply: HttpMessage,
        options: SessionOptions,
        log: Log,
    ): Digest {
        val challenge =
            Digest.challenge(reply.header("WWW-Authenticate"))
                ?: throw AirkastException.Rejected("SETUP", reply.status)
        log.debug { "SETUP asks for a password" }
        val password =
            options.password
                ?: throw AirkastException.PairingFailed(
                    "The receiver asks for a password",
                    credentialsRefused = false,
                    passwordAsked = true,
                )
        return challenge.answer(password)
    }

    /** Connects to the event port SETUP named, which can refuse for a moment right after. */
    fun connectEvents(
        receiver: Receiver,
        port: Int,
        options: SessionOptions,
        log: Log,
    ): Socket {
        var attempt = 0
        while (true) {
            try {
                return connect(InetSocketAddress(receiver.host, port), options)
            } catch (e: AirkastException.Unreachable) {
                if (e.cause !is ConnectException || ++attempt >= 5) throw e
                log.debug { "the event port refused, attempt $attempt" }
                Thread.sleep(200)
            }
        }
    }

    private fun control(
        receiver: Receiver,
        identity: SenderIdentity,
        options: SessionOptions,
    ): ControlConnection {
        Log(options.logger, "connect").debug {
            "receiver ${receiver.model} srcvers ${receiver.sourceVersion} " +
                "features 0x${receiver.features.toString(16)} ${receiver.compatibility}"
        }
        val socket =
            connect(InetSocketAddress(receiver.host, receiver.port), options).apply {
                soTimeout = options.requestTimeout.toSocketTimeout()
                tcpNoDelay = true
            }
        return ControlConnection(socket, identity, Log(options.logger, "control"))
    }

    fun failure(e: Exception): AirkastException =
        when (e) {
            is AirkastException -> e
            is LateReply -> AirkastException.Timeout(e.message.orEmpty())
            is IOException -> AirkastException.Disconnected(e, disconnectReason(e))
            else -> AirkastException.UnexpectedReply(e)
        }

    /**
     * Why a connection ended by itself, from what it failed with. An end of stream, which the
     * event channel reads as no cause, is the receiver hanging up; any other I/O failure, a reset
     * or a receiver silent past a timeout, is the connection failing.
     */
    fun disconnectReason(cause: Throwable?): DisconnectReason {
        val chain = generateSequence(cause) { it.cause }
        return when {
            cause == null || chain.any { it is EOFException } -> DisconnectReason.ClosedByReceiver
            chain.any { it is IOException } -> DisconnectReason.ConnectionFailed
            else -> DisconnectReason.Unknown
        }
    }

    /** A connected socket, or [AirkastException.Unreachable]. */
    private fun connect(
        address: InetSocketAddress,
        options: SessionOptions,
    ): Socket {
        val socket =
            try {
                options.socketFactory?.createSocket() ?: Socket()
            } catch (e: IOException) {
                throw AirkastException.Unreachable(e)
            }
        try {
            socket.connect(address, options.connectTimeout.inWholeMilliseconds.toInt())
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw AirkastException.Unreachable(e)
        }
        return socket
    }

    fun plist(message: HttpMessage): Map<*, *> =
        if (BinaryPlist.isPlist(message.body)) {
            BinaryPlist.decode(message.body) as? Map<*, *>
                ?: emptyMap<Any, Any>()
        } else {
            emptyMap<Any, Any>()
        }
}

/** A control connection that paired and encrypts, and the shared secret its keys came from. */
internal class Paired(
    val control: ControlConnection,
    val sessionKey: ByteArray,
)

internal fun HttpMessage.requireSuccess(request: String): HttpMessage {
    if (status !in 200..299) throw AirkastException.Rejected(request, status)
    return this
}
