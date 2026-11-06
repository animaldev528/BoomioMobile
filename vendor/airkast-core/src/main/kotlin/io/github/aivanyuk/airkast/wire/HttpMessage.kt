package io.github.aivanyuk.airkast.wire

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** An HTTP or RTSP request or response, which receivers mix on one connection. */
internal class HttpMessage(
    val startLine: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray = ByteArray(0),
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** The status of a response (`RTSP/1.0 200 OK`). */
    val status: Int get() = startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1

    /** The protocol of a request (`POST /command RTSP/1.0`). */
    val requestProtocol: String get() = startLine.substringAfterLast(' ')

    fun encode(): ByteArray {
        val head = StringBuilder(startLine).append("\r\n")
        headers
            .filterNot { it.first.equals("Content-Length", ignoreCase = true) }
            .forEach { (k, v) ->
                head
                    .append(k)
                    .append(": ")
                    .append(v)
                    .append("\r\n")
            }
        head.append("Content-Length: ").append(body.size).append("\r\n\r\n")
        return head.toString().toByteArray(Charsets.ISO_8859_1) + body
    }

    companion object {
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 1024 * 1024

        /** Reads one message, or null if the stream ends before one starts. */
        fun read(input: InputStream): HttpMessage? {
            val head = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b < 0) {
                    if (head.size() == 0) return null
                    throw EOFException("Message head cut short")
                }
                head.write(b)
                require(head.size() <= MAX_HEAD) { "Message head too long" }
                matched =
                    when {
                        b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                        b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                        b == '\r'.code -> 1
                        else -> 0
                    }
            }
            val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
            require(lines.isNotEmpty()) { "Message head of blank lines" }
            val headers =
                lines.drop(1).mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon < 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
                }
            val length =
                headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }?.second?.toIntOrNull() ?: 0
            require(length in 0..MAX_BODY) { "Body of $length bytes" }
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw EOFException("Body cut short")
                read += n
            }
            return HttpMessage(lines.first(), headers, body)
        }
    }
}
