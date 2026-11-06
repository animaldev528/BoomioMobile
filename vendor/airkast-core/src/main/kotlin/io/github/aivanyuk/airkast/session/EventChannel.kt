package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.crypto.Hkdf
import io.github.aivanyuk.airkast.wire.BinaryPlist
import io.github.aivanyuk.airkast.wire.HttpMessage
import io.github.aivanyuk.airkast.wire.Link
import java.net.Socket

/**
 * The connection the receiver sends its events and replies over. Every request it sends gets an
 * empty 200, and its body, unwrapped from `{params: {data: <plist>}}`, goes to [onMessage].
 */
internal class EventChannel(
    socket: Socket,
    sessionKey: ByteArray,
    private val onMessage: (Map<String, Any?>) -> Unit,
    private val onClosed: (Throwable?) -> Unit,
    private val log: Log = Log.NONE,
) : AutoCloseable {
    private val link = Link(socket)

    @Volatile
    private var closing = false

    private val reader = Thread(::readLoop, "airkast-events").apply { isDaemon = true }

    init {
        link.encrypt(
            writeKey = Hkdf.sha512(sessionKey, "Events-Salt", "Events-Read-Encryption-Key"),
            readKey = Hkdf.sha512(sessionKey, "Events-Salt", "Events-Write-Encryption-Key"),
        )
        reader.start()
    }

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (true) {
                val request = HttpMessage.read(link.input) ?: break
                log.verbose { "${request.startLine}, ${request.body.size} bytes" }
                val headers =
                    buildList {
                        add("Audio-Latency" to "0")
                        request.header("CSeq")?.let { add("CSeq" to it) }
                        request.header("Server")?.let { add("Server" to it) }
                    }
                link.write(HttpMessage("${request.requestProtocol} 200 OK", headers).encode())
                unwrap(request.body)?.let(onMessage)
            }
        } catch (e: Exception) {
            // The session logs why it ended.
            if (!closing) log.debug { "failed: $e" }
            failure = e
        }
        log.debug { "closed" }
        if (!closing) onClosed(failure)
    }

    override fun close() {
        closing = true
        link.close()
    }

    companion object {
        fun unwrap(body: ByteArray): Map<String, Any?>? {
            if (!BinaryPlist.isPlist(body)) return null
            val outer = BinaryPlist.decode(body) as? Map<*, *> ?: return null
            val data = (outer["params"] as? Map<*, *>)?.get("data") as? ByteArray
            val inner = if (data != null && BinaryPlist.isPlist(data)) BinaryPlist.decode(data) else outer
            @Suppress("UNCHECKED_CAST")
            return inner as? Map<String, Any?>
        }
    }
}
