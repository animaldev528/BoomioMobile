package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.Origin
import io.github.aivanyuk.airkast.Resource
import io.github.aivanyuk.airkast.wire.HttpMessage
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * The [Origin] as an HTTP/1.1 server on [bind], the address the session's control connection
 * uses, so only the receiver's network reaches it. It answers GET, HEAD and OPTIONS with CORS
 * headers, which the LG's hls.js needs, and byte ranges, which a file and a byte-range segment
 * need.
 */
internal class HttpOrigin(
    private val bind: InetAddress,
    private val log: Log = Log.NONE,
) : Origin,
    AutoCloseable {
    private val prefix = randomPrefix()
    private val resources = ConcurrentHashMap<String, Resource>()
    private val connections: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    private val lock = Any()
    private var server: ServerSocket? = null
    private var closed = false

    /** The port it listens on, or null while nothing is published. */
    val port: Int? get() = synchronized(lock) { server?.localPort }

    override fun publish(
        name: String,
        resource: Resource,
    ): String {
        require(
            NAME.matches(name) && name.split('/').none { it == "." || it == ".." },
        ) { "Not a path to publish: $name" }
        val port =
            synchronized(lock) {
                check(!closed) { "The origin is closed" }
                resources["/$prefix/$name"] = resource
                (server ?: start()).localPort
            }
        log.debug { "published $name" }
        return "http://${host(bind)}:$port/$prefix/$name"
    }

    override fun withdraw(url: String) {
        val path = "/" + url.substringAfter("://").substringAfter('/')
        synchronized(lock) {
            if (resources.remove(path) == null) return
            if (resources.isEmpty()) stop()
        }
        log.debug { "withdrew ${path.removePrefix("/$prefix/")}" }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            resources.clear()
            stop()
        }
    }

    private fun start(): ServerSocket {
        val socket = ServerSocket()
        socket.bind(InetSocketAddress(bind, 0))
        server = socket
        Thread({ accept(socket) }, "airkast-origin").apply {
            isDaemon = true
            start()
        }
        log.debug { "listening on port ${socket.localPort}" }
        return socket
    }

    /** Closes the listening socket and every open connection: a withdrawn origin serves nothing. */
    private fun stop() {
        server?.let { runCatching { it.close() } }
        server = null
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private fun accept(server: ServerSocket) {
        while (true) {
            val socket =
                try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
            connections += socket
            if (server.isClosed) {
                runCatching { socket.close() }
                return
            }
            Thread({ serve(socket) }, "airkast-origin-connection").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream().buffered()
            while (true) {
                val request =
                    try {
                        HttpMessage.read(input) ?: return
                    } catch (_: SocketTimeoutException) {
                        return
                    } catch (_: IllegalArgumentException) {
                        respond(output, Response(400, "Bad Request"), keepOpen = false)
                        return
                    }
                if (!answer(request, output)) return
            }
        } catch (_: IOException) {
            // The receiver hung up, or the origin stopped.
        } catch (e: Exception) {
            // Thrown on this thread, it would end the app on Android.
            log.error(e) { "a connection failed" }
        } finally {
            connections -= socket
            runCatching { socket.close() }
        }
    }

    /** Answers one request, and returns whether the connection stays open for the next one. */
    private fun answer(
        request: HttpMessage,
        output: OutputStream,
    ): Boolean {
        val parts = request.startLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) {
            respond(output, Response(400, "Bad Request"), keepOpen = false)
            return false
        }
        val (method, target, version) = parts
        if (method != "GET" && method != "HEAD" && method != "OPTIONS") {
            // A body this server does not read would be taken for the next request.
            respond(output, Response(405, "Method Not Allowed", listOf("Allow" to ALLOWED)), keepOpen = false)
            return false
        }
        val keepOpen =
            version == "HTTP/1.1" && !request.header("Connection").equals("close", ignoreCase = true)
        val path = target.substringBefore('?')
        val resource = resources[path]
        val name = path.removePrefix("/$prefix/")
        val response =
            when {
                resource == null -> {
                    Response(404, "Not Found")
                }

                method == "OPTIONS" -> {
                    Response(204, "No Content", PREFLIGHT)
                }

                else -> {
                    try {
                        content(resource, request.header("Range"))
                    } catch (e: Exception) {
                        log.warn(e) { "$name could not be opened" }
                        Response(500, "Internal Server Error")
                    }
                }
            }
        log.verbose { "$method ${if (resource == null) "an unpublished path" else name}: ${response.status}" }
        val body = response.body.takeIf { method == "GET" }
        try {
            // An unknown length ends at the close of the connection.
            val open = keepOpen && (response.length != null || response.body == null)
            respond(output, response, open, body)
            return open
        } finally {
            response.body?.close()
        }
    }

    /** What [resource] answers with, the [range] the receiver asked for applied when it can be. */
    private fun content(
        resource: Resource,
        range: String?,
    ): Response {
        val content = resource.open()
        val length = content.length
        val headers = mutableListOf("Content-Type" to resource.contentType, "Cache-Control" to "no-cache")
        if (length == null) return Response(200, "OK", headers, content.from(0), null)
        headers += "Accept-Ranges" to "bytes"
        val asked = range?.let { Ranges.parse(it, length) }
        return when {
            asked == null -> {
                Response(200, "OK", headers, content.from(0), length)
            }

            asked.isEmpty() -> {
                Response(416, "Range Not Satisfiable", headers + ("Content-Range" to "bytes */$length"))
            }

            else -> {
                headers += "Content-Range" to "bytes ${asked.first}-${asked.last}/$length"
                Response(206, "Partial Content", headers, content.from(asked.first), asked.last - asked.first + 1)
            }
        }
    }

    private fun respond(
        output: OutputStream,
        response: Response,
        keepOpen: Boolean,
        body: InputStream? = null,
    ) {
        val head = StringBuilder("HTTP/1.1 ${response.status} ${response.reason}\r\n")
        for ((name, value) in CORS + response.headers) {
            head
                .append(name)
                .append(": ")
                .append(value)
                .append("\r\n")
        }
        when {
            response.length != null -> head.append("Content-Length: ").append(response.length).append("\r\n")
            response.body == null -> head.append("Content-Length: 0\r\n")
        }
        if (!keepOpen) head.append("Connection: close\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) copy(body, output, response.length)
        output.flush()
    }

    private class Response(
        val status: Int,
        val reason: String,
        val headers: List<Pair<String, String>> = emptyList(),
        val body: InputStream? = null,
        /** The body's length, null when it is not known ahead. */
        val length: Long? = if (body == null) 0 else null,
    )

    companion object {
        private const val IDLE_TIMEOUT_MS = 30_000
        private const val ALLOWED = "GET, HEAD, OPTIONS"
        private val NAME = Regex("[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*")
        private val CORS =
            listOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Expose-Headers" to "Accept-Ranges, Content-Length, Content-Range",
            )
        private val PREFLIGHT =
            listOf(
                "Access-Control-Allow-Methods" to ALLOWED,
                "Access-Control-Allow-Headers" to "Range",
                "Access-Control-Max-Age" to "86400",
            )

        private fun randomPrefix(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** [address] as a URL's host: an IPv6 address in brackets, with its zone's `%` escaped. */
        fun host(address: InetAddress): String =
            if (address is Inet6Address) "[${address.hostAddress.replace("%", "%25")}]" else address.hostAddress

        private fun copy(
            from: InputStream,
            to: OutputStream,
            length: Long?,
        ) {
            val buffer = ByteArray(64 * 1024)
            var left = length ?: Long.MAX_VALUE
            while (left > 0) {
                val n = from.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) break
                to.write(buffer, 0, n)
                left -= n
            }
        }
    }
}

/** The `Range` header of a request, as RFC 9110 reads it, for one range of bytes. */
internal object Ranges {
    /**
     * The bytes of a [length]-byte resource that [header] asks for; empty when none of them is
     * there, which is a 416; null when the whole resource is the answer, as for several ranges, a
     * unit other than bytes or a header that does not parse, which the RFC lets a server ignore.
     */
    fun parse(
        header: String,
        length: Long,
    ): LongRange? {
        val spec = header.trim()
        if (!spec.startsWith("bytes=", ignoreCase = true)) return null
        val ranges = spec.substring("bytes=".length).trim()
        if (',' in ranges) return null
        val dash = ranges.indexOf('-')
        if (dash < 0) return null
        val first = ranges.substring(0, dash).trim()
        val last = ranges.substring(dash + 1).trim()
        if (first.isEmpty()) {
            val suffix = last.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            if (suffix == 0L || length == 0L) return LongRange.EMPTY
            return maxOf(0, length - suffix) until length
        }
        val start = first.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val end = if (last.isEmpty()) Long.MAX_VALUE else last.toLongOrNull() ?: return null
        if (end < start) return null
        if (start >= length) return LongRange.EMPTY
        return start..minOf(end, length - 1)
    }
}
