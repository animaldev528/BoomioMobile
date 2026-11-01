package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private val log = Logger.withTag("CastV2")

/**
 * Plays a URL on a Google Cast receiver by speaking CASTV2 to it directly.
 *
 * Cast is protobuf-over-TLS on `:8009`, framed with a 4-byte big-endian length.
 * The exchange is fixed:
 *
 *   CONNECT receiver-0 → GET_STATUS → (LAUNCH `CC1AD845` if it is not running)
 *   → CONNECT <transportId> → LOAD in `urn:x-cast:com.google.cast.media`
 *
 * and then the **receiver** fetches the URL — which is exactly the same shape as
 * DLNA, and the reason a cast stream has to carry its token in the URL.
 *
 * We drive the *Default Media Receiver* rather than registering a receiver app:
 * it already knows how to play a progressive or HLS URL, so a cast works on any
 * Cast device without us hosting anything.
 *
 * The receiver presents a self-signed certificate, so TLS here is encrypted
 * transport, not authentication; the identity that matters is the LAN address
 * discovery just handed us. Verification is therefore off — the same posture as
 * every other Cast sender.
 */
internal object CastV2 {

    fun play(device: CastDevice, url: String, title: String): CastPlayResult {
        // A previous session's socket is dead weight and would keep heartbeating.
        CastSenderHolder.close()
        val sender = try {
            CastSender(device.ip)
        } catch (error: Exception) {
            return CastPlayResult.failed("could not reach the Cast receiver at ${device.ip}:8009 (${error.message})")
        }
        return try {
            sender.load(url, title)
            sender.startKeepAlive()
            CastSenderHolder.replace(sender)
            log.d { "cast: ${device.name} accepted the stream" }
            CastPlayResult.Ok
        } catch (error: Exception) {
            runCatching { sender.close() }
            CastPlayResult.failed(error.message ?: "Cast failed")
        }
    }
}

/**
 * The live sender for the last successful cast.
 *
 * The Default Media Receiver keeps playing if the sender vanishes, but the
 * session is cleaner — and a second LOAD behaves predictably — while a socket is
 * held. The heartbeat thread keeps it open; this holder makes sure only one
 * exists, so casting twice does not leak a thread per attempt.
 */
private object CastSenderHolder {
    private var current: CastSender? = null

    @Synchronized
    fun replace(sender: CastSender) {
        runCatching { current?.close() }
        current = sender
    }

    @Synchronized
    fun close() {
        runCatching { current?.close() }
        current = null
    }
}

private class CastFailure(message: String) : Exception(message)

private const val PORT = 8009
private const val SENDER = "sender-0"
private const val RECEIVER = "receiver-0"

/** "Default Media Receiver": plays a URL, needs no registration from us. */
private const val DEFAULT_RECEIVER = "CC1AD845"

private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

private const val CONNECT_TIMEOUT_MS = 3000
private const val HANDSHAKE_TIMEOUT_MS = 4000
private const val READ_TIMEOUT_MS = 1000
private const val STATUS_TIMEOUT_MS = 3000L
private const val LAUNCH_TIMEOUT_MS = 10000L
private const val LOAD_TIMEOUT_MS = 12000L
private const val PING_INTERVAL_MS = 5000L
private const val MAX_FRAME = 1 shl 20

private class CastSender(private val host: String) : Closeable {

    private val socket: SSLSocket
    private val input: DataInputStream
    private val writeLock = Any()
    private var requestId = 0
    @Volatile
    private var keeper: Thread? = null

    init {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
            raw.soTimeout = HANDSHAKE_TIMEOUT_MS
            val ssl = trustAllFactory().createSocket(raw, host, PORT, true) as SSLSocket
            ssl.startHandshake()
            ssl.soTimeout = READ_TIMEOUT_MS
            socket = ssl
            input = DataInputStream(ssl.inputStream)
        } catch (error: Exception) {
            runCatching { raw.close() }
            throw error
        }
    }

    /**
     * Runs the whole handshake and throws [CastFailure] with the step that failed,
     * because "Cast failed" on the couch is not a diagnosis.
     */
    fun load(url: String, title: String) {
        send(SENDER, RECEIVER, NS_CONNECTION, """{"type":"CONNECT","origin":{}}""")
        send(
            SENDER, RECEIVER, NS_RECEIVER,
            """{"type":"GET_STATUS","requestId":${nextRequestId()}}""",
        )

        val transport = awaitTransport(
            appId = DEFAULT_RECEIVER,
            deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MS,
        ) ?: run {
            log.d { "cast: no media receiver running, launching $DEFAULT_RECEIVER" }
            send(
                SENDER, RECEIVER, NS_RECEIVER,
                """{"type":"LAUNCH","appId":"$DEFAULT_RECEIVER","requestId":${nextRequestId()}}""",
            )
            awaitTransport(
                appId = DEFAULT_RECEIVER,
                deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MS,
            ) ?: throw CastFailure("the TV did not start its media receiver")
        }

        send(
            SENDER, transport, NS_CONNECTION,
            """{"type":"CONNECT","origin":{}}""",
        )

        val loadRequestId = nextRequestId()
        val load = JSONObject()
            .put("type", "LOAD")
            .put("requestId", loadRequestId)
            .put("sessionId", JSONObject.NULL)
            .put("autoplay", true)
            .put("currentTime", 0)
            .put(
                "media",
                JSONObject()
                    .put("contentId", url)
                    .put("streamType", "BUFFERED")
                    .put("contentType", contentTypeFor(url))
                    .put(
                        "metadata",
                        JSONObject()
                            .put("metadataType", 0)
                            .put("title", title),
                    ),
            )
        send(SENDER, transport, NS_MEDIA, load.toString())
        awaitLoadResult(loadRequestId, System.currentTimeMillis() + LOAD_TIMEOUT_MS)
    }

    /** Sends PING twice a minute and drains what comes back, until closed. */
    fun startKeepAlive() {
        keeper = Thread({ keepAliveLoop() }, "cast-keepalive").apply {
            isDaemon = true
            start()
        }
    }

    private fun keepAliveLoop() {
        try {
            while (!socket.isClosed) {
                Thread.sleep(PING_INTERVAL_MS)
                send(SENDER, RECEIVER, NS_HEARTBEAT, """{"type":"PING"}""")
                // Read the PONG (and any status push) so the receive buffer cannot
                // fill over a long film. One frame per ping is the whole traffic.
                readFrame(System.currentTimeMillis() + READ_TIMEOUT_MS)
            }
        } catch (_: Exception) {
            // The receiver closed the socket, or we did. Nothing to report.
        }
    }

    override fun close() {
        runCatching { socket.close() }
        keeper?.interrupt()
    }

    // ── Receiver state ──────────────────────────────────────────────────────

    /** Waits for a RECEIVER_STATUS naming [appId] and returns its transport id. */
    private fun awaitTransport(appId: String, deadline: Long): String? {
        while (true) {
            val message = awaitMessage(deadline) ?: return null
            val payload = message.json ?: continue
            when (payload.optString("type")) {
                "RECEIVER_STATUS" -> {
                    val apps = payload.optJSONObject("status")?.optJSONArray("applications") ?: continue
                    for (index in 0 until apps.length()) {
                        val app = apps.optJSONObject(index) ?: continue
                        if (app.optString("appId") != appId) continue
                        val transport = app.optString("transportId")
                        if (transport.isNotEmpty()) return transport
                    }
                }

                "LAUNCH_ERROR" ->
                    throw CastFailure("the TV refused to start its media receiver (${payload.optString("reason")})")
            }
        }
    }

    /**
     * Waits for the receiver's answer to the LOAD that was just sent.
     *
     * ⚠️ Connecting to a transport makes the receiver PUSH the state it already
     * had — with nothing loaded that is `MEDIA_STATUS` / `IDLE` and an empty
     * `idleReason`. That is a snapshot from before our LOAD, not its answer, and
     * taking it as one fails the cast a few milliseconds after a launch that is
     * still starting the receiver: the television opens its player and the phone
     * says it could not play the stream. Only an IDLE that answers *this* load
     * (matching `requestId`) or that names a reason is a verdict.
     */
    private fun awaitLoadResult(loadRequestId: Int, deadline: Long) {
        while (true) {
            val message = awaitMessage(deadline)
                ?: throw CastFailure("the TV did not answer the load")
            if (message.namespace != NS_MEDIA) continue
            val payload = message.json ?: continue
            when (payload.optString("type")) {
                "MEDIA_STATUS" -> {
                    val status = payload.optJSONArray("status")?.optJSONObject(0) ?: continue
                    val answered = payload.optInt("requestId", -1) == loadRequestId
                    when (status.optString("playerState")) {
                        "PLAYING", "BUFFERING", "PAUSED" -> return
                        "IDLE" -> {
                            val reason = status.optString("idleReason")
                            if (reason.isBlank() && !answered) {
                                log.d { "cast: ignoring the pre-load session snapshot (IDLE, no reason)" }
                                continue
                            }
                            throw CastFailure("the TV could not play it (${reason.ifBlank { "idle" }})")
                        }
                    }
                }

                "LOAD_FAILED" ->
                    throw CastFailure("the TV rejected the stream (${payload.optString("reason")})")

                "INVALID_REQUEST", "LOAD_CANCELLED" ->
                    throw CastFailure("the TV refused the load (${payload.optString("reason")})")
            }
        }
    }

    // ── Framing ─────────────────────────────────────────────────────────────

    private class Message(val namespace: String, val json: JSONObject?)

    /** The next frame, or null when [deadline] passes before a single byte arrives. */
    private fun awaitMessage(deadline: Long): Message? = readFrame(deadline)

    private fun readFrame(deadline: Long): Message? {
        val header = readExactly(4, deadline) ?: return null
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length <= 0 || length > MAX_FRAME) throw CastFailure("malformed frame from the TV")
        val body = readExactly(length, deadline)
            ?: throw CastFailure("the TV stopped mid-frame")
        val fields = parseFields(body)
        val namespace = fields[4] as? String ?: ""
        val payload = (fields[6] as? String)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()
        }
        return Message(namespace, payload)
    }

    /**
     * ⚠️ Partial reads are preserved across timeouts. `read` may return fewer
     * bytes than asked (or throw a read timeout) and the bytes already consumed
     * are gone from the stream — if the caller restarted at the length prefix it
     * would read the payload as a header and mis-frame everything after. So a
     * deadline that expires *mid-frame* is a failure, never a retry.
     */
    private fun readExactly(count: Int, deadline: Long): ByteArray? {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                if (read == 0) return null
                throw CastFailure("timed out reading from the TV")
            }
            socket.soTimeout = remaining.coerceAtMost(READ_TIMEOUT_MS.toLong()).toInt().coerceAtLeast(1)
            try {
                val received = input.read(buffer, read, count - read)
                if (received < 0) throw CastFailure("the TV closed the connection")
                read += received
            } catch (_: SocketTimeoutException) {
                // Nothing new yet; the bytes already read stay in `buffer`.
            }
        }
        return buffer
    }

    private fun send(source: String, destination: String, namespace: String, payload: String) {
        val frame = buildFrame(source, destination, namespace, payload)
        synchronized(writeLock) {
            val out = socket.outputStream
            out.write(frame)
            out.flush()
        }
    }

    private fun nextRequestId(): Int = ++requestId
}

// ── Protobuf, the two wire types CASTV2 uses ────────────────────────────────

private fun buildFrame(
    source: String,
    destination: String,
    namespace: String,
    payload: String,
): ByteArray {
    val body = ByteArrayOutputStream()
    body.write(varint((1 shl 3) or 0))
    body.write(varint(0)) // protocol_version = CASTV2_1_0
    body.write(bytesField(2, source))
    body.write(bytesField(3, destination))
    body.write(bytesField(4, namespace))
    body.write(varint((5 shl 3) or 0))
    body.write(varint(0)) // payload_type = STRING
    body.write(bytesField(6, payload))
    val bytes = body.toByteArray()
    return byteArrayOf(
        (bytes.size ushr 24).toByte(),
        (bytes.size ushr 16).toByte(),
        (bytes.size ushr 8).toByte(),
        bytes.size.toByte(),
    ) + bytes
}

private fun bytesField(number: Int, value: String): ByteArray {
    val raw = value.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream()
    out.write(varint((number shl 3) or 2))
    out.write(varint(raw.size))
    out.write(raw)
    return out.toByteArray()
}

private fun varint(value: Int): ByteArray {
    var remaining = value
    val out = ByteArrayOutputStream()
    while (true) {
        val low = remaining and 0x7F
        remaining = remaining ushr 7
        if (remaining == 0) {
            out.write(low)
            return out.toByteArray()
        }
        out.write(low or 0x80)
    }
}

/** Flat message: field number → varint value or UTF-8 string. */
private fun parseFields(bytes: ByteArray): Map<Int, Any> {
    val fields = HashMap<Int, Any>()
    var offset = 0
    while (offset < bytes.size) {
        val key = readVarint(bytes, offset) ?: throw CastFailure("malformed frame from the TV")
        offset = key.second
        val number = (key.first ushr 3).toInt()
        when ((key.first and 7L).toInt()) {
            0 -> {
                val value = readVarint(bytes, offset) ?: throw CastFailure("malformed frame from the TV")
                offset = value.second
                fields[number] = value.first
            }

            2 -> {
                val length = readVarint(bytes, offset) ?: throw CastFailure("malformed frame from the TV")
                offset = length.second
                val end = offset + length.first.toInt()
                if (length.first < 0 || end > bytes.size) throw CastFailure("malformed frame from the TV")
                fields[number] = String(bytes, offset, length.first.toInt(), Charsets.UTF_8)
                offset = end
            }

            else -> throw CastFailure("unsupported frame field from the TV")
        }
    }
    return fields
}

private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
    var value = 0L
    var shift = 0
    var offset = start
    while (offset < bytes.size) {
        val byte = bytes[offset].toInt() and 0xFF
        offset++
        value = value or ((byte and 0x7F).toLong() shl shift)
        if (byte and 0x80 == 0) return value to offset
        shift += 7
        if (shift > 63) return null
    }
    return null
}

// ── TLS ─────────────────────────────────────────────────────────────────────

private var trustAll: SSLSocketFactory? = null

/**
 * A factory that accepts the receiver's self-signed certificate. Cast devices do
 * not present a chain anyone can validate, and the sender cannot pin one before
 * it has seen the device — the LAN address is the trust boundary here.
 */
@Synchronized
private fun trustAllFactory(): SSLSocketFactory {
    trustAll?.let { return it }
    val context = SSLContext.getInstance("TLS")
    val manager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    context.init(null, arrayOf<TrustManager>(manager), SecureRandom())
    return context.socketFactory.also { trustAll = it }
}

// ── Content type ────────────────────────────────────────────────────────────

/**
 * The receiver has to know what it is fetching. Our stream URLs carry no file
 * extension, so this is the progressive default; a playlist URL says so itself.
 */
private fun contentTypeFor(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".m3u8") -> "application/x-mpegurl"
        path.endsWith(".mpd") -> "application/dash+xml"
        path.endsWith(".webm") -> "video/webm"
        else -> "video/mp4"
    }
}
