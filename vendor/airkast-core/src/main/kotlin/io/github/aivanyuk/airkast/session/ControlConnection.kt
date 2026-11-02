package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.wire.HttpMessage
import io.github.aivanyuk.airkast.wire.Link
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.time.Duration

/**
 * The connection a sender pairs on and sends its RTSP and HTTP requests over, one at a time. A
 * reply that has not started within the socket's timeout throws [LateReply] and is still owed: the
 * next exchange reads and drops it first, and fails for good if it has not come by then.
 */
internal class ControlConnection(
    private val socket: Socket,
    private val identity: SenderIdentity,
    private val log: Log = Log.NONE,
) : AutoCloseable {
    private val link = Link(socket)
    private var sequence = 0
    private var owed = 0
    private val dacpId = HEX.let { digits -> String(CharArray(16) { digits[RANDOM.nextInt(16)] }) }
    private val activeRemote = (RANDOM.nextInt().toLong() and 0xffffffffL).toString()

    // A Mac (960.13.25) names the sender in its "Allow … to AirPlay" prompt from this header, not
    // SETUP's `name`, and shows "" without it. Its UTF-8 bytes go out as they are, and a line
    // break, which would end the header, as a space.
    private val clientName =
        String(identity.name.replace(Regex("[\r\n]"), " ").toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    val localAddress get() = link.localAddress
    val remoteAddress get() = link.remoteAddress

    /** Signs every request from here on, once the receiver asked for its password. */
    @Volatile
    var digest: Digest? = null

    fun encrypt(
        writeKey: ByteArray,
        readKey: ByteArray,
    ) = link.encrypt(writeKey, readKey)

    @Synchronized
    fun exchange(
        method: String,
        target: String,
        protocol: String = RTSP,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
        /** How long to wait for the answer to start, in place of the socket's own timeout. */
        timeout: Duration? = null,
    ): HttpMessage {
        val all =
            buildList {
                add("CSeq" to (++sequence).toString())
                add("DACP-ID" to dacpId)
                add("Active-Remote" to activeRemote)
                add("Client-Instance" to dacpId)
                if (headers.none { it.first.equals("User-Agent", ignoreCase = true) }) {
                    add("User-Agent" to identity.userAgent)
                }
                add("X-Apple-Client-Name" to clientName)
                if (contentType != null) add("Content-Type" to contentType)
                digest?.let { add("Authorization" to it.authorization(method, target)) }
                addAll(headers)
            }
        while (owed > 0) {
            try {
                read()
            } catch (e: SocketTimeoutException) {
                throw IOException("Still no answer to an earlier request", e)
            }
            owed--
            log.warn { "dropped a late answer" }
        }
        link.write(HttpMessage("$method $target $protocol", all, body).encode())
        val usual = socket.soTimeout
        if (timeout != null) socket.soTimeout = timeout.toSocketTimeout()
        val response =
            try {
                read()
            } catch (e: SocketTimeoutException) {
                owed++
                log.warn { "$method $target has no answer yet" }
                throw LateReply("$method $target", e)
            } catch (e: IOException) {
                log.warn(e) { "$method $target got no answer" }
                throw IOException("$method $target: ${e.message}", e)
            } finally {
                if (timeout != null) runCatching { socket.soTimeout = usual }
            }
        log.verbose { "$method $target -> ${response.startLine}, ${response.body.size} bytes" }
        return response
    }

    private fun read(): HttpMessage {
        if (!link.await()) throw EOFException("The receiver closed the connection")
        return HttpMessage.read(link.input) ?: throw EOFException("The receiver closed the connection")
    }

    override fun close() = link.close()

    companion object {
        const val RTSP = "RTSP/1.0"
        const val HTTP = "HTTP/1.1"
        const val BPLIST = "application/x-apple-binary-plist"
        private const val HEX = "0123456789ABCDEF"
        private val RANDOM = SecureRandom()
    }
}

/** A request whose answer has not started arriving in time. The connection is still usable. */
internal class LateReply(
    request: String,
    cause: SocketTimeoutException,
) : IOException("$request: no answer in time", cause)

/** This as a socket timeout: whole milliseconds, at most `Int.MAX_VALUE`, a little under 25 days, for a longer or infinite one. */
internal fun Duration.toSocketTimeout(): Int = inWholeMilliseconds.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
