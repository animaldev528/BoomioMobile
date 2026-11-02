package io.github.aivanyuk.airkast.wire

import io.github.aivanyuk.airkast.crypto.ChaCha20Poly1305
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One TCP connection to a receiver. It is plain until [encrypt], then every byte travels in HAP
 * frames: a little-endian length of at most 1024 bytes, which is also the frame's associated
 * data, then the sealed bytes. Each direction counts its own frames into the nonce.
 *
 * A socket read timeout only surfaces from [await], before a message has started, where it has
 * consumed nothing. Inside a message, [input] waits out a few timeouts, then fails for good, since
 * the stream would be out of step.
 */
internal class Link(
    private val socket: Socket,
) : AutoCloseable {
    private val raw = BufferedInputStream(socket.getInputStream())
    private val out = socket.getOutputStream()
    private var writeKey: ByteArray? = null
    private var readKey: ByteArray? = null
    private var writeCounter = 0L
    private var readCounter = 0L
    private var plain = ByteArray(0)
    private var plainOffset = 0

    val input: InputStream =
        object : InputStream() {
            override fun read(): Int {
                if (!fill(boundary = false)) return -1
                return plain[plainOffset++].toInt() and 0xff
            }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (len == 0) return 0
                if (!fill(boundary = false)) return -1
                val n = minOf(len, plain.size - plainOffset)
                plain.copyInto(b, off, plainOffset, plainOffset + n)
                plainOffset += n
                return n
            }
        }

    val localAddress get() = socket.localAddress
    val remoteAddress get() = socket.inetAddress

    fun encrypt(
        writeKey: ByteArray,
        readKey: ByteArray,
    ) {
        this.writeKey = writeKey
        this.readKey = readKey
    }

    @Synchronized
    fun write(bytes: ByteArray) {
        val key = writeKey
        if (key == null) {
            out.write(bytes)
        } else {
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(FRAME, bytes.size - offset)
                val aad = byteArrayOf(length.toByte(), (length shr 8).toByte())
                val sealed =
                    ChaCha20Poly1305.seal(
                        key,
                        nonce(writeCounter++),
                        bytes.copyOfRange(offset, offset + length),
                        aad,
                    )
                out.write(aad)
                out.write(sealed)
                offset += length
            }
        }
        out.flush()
    }

    /**
     * Waits for the next message to start arriving, for up to the socket's timeout. Its
     * [SocketTimeoutException] leaves the stream as it was, so the wait can be tried again. False
     * at the end of the stream.
     */
    fun await(): Boolean = fill(boundary = true)

    /** Makes decrypted bytes available; false at the end of the stream. */
    private fun fill(boundary: Boolean): Boolean {
        if (plainOffset < plain.size) return true
        val key = readKey
        if (key == null) {
            val chunk = ByteArray(FRAME)
            val n = patiently(boundary) { raw.read(chunk) }
            if (n < 0) return false
            plain = chunk.copyOf(n)
        } else {
            val lo = patiently(boundary) { raw.read() }
            if (lo < 0) return false
            val hi = patiently { raw.read() }
            if (hi < 0) throw EOFException("Frame header cut short")
            val length = lo or (hi shl 8)
            require(length in 1..FRAME) { "Frame of $length bytes" }
            val sealed = ByteArray(length + ChaCha20Poly1305.TAG_LENGTH)
            readFully(sealed)
            plain = ChaCha20Poly1305.open(key, nonce(readCounter++), sealed, byteArrayOf(lo.toByte(), hi.toByte()))
        }
        plainOffset = 0
        return true
    }

    private fun readFully(buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = patiently { raw.read(buffer, read, buffer.size - read) }
            if (n < 0) throw EOFException("Frame cut short")
            read += n
        }
    }

    /** Runs [read], waiting out [STALLS] timeouts unless [boundary] lets the first one through. */
    private inline fun patiently(
        boundary: Boolean = false,
        read: () -> Int,
    ): Int {
        var stalls = 0
        while (true) {
            try {
                return read()
            } catch (e: SocketTimeoutException) {
                if (boundary) throw e
                if (++stalls > STALLS) throw IOException("The receiver stalled inside a message", e)
            }
        }
    }

    override fun close() = socket.close()

    private companion object {
        const val FRAME = 1024
        const val STALLS = 3

        fun nonce(counter: Long): ByteArray =
            ByteArray(12).also {
                for (i in 0 until 8) it[4 + i] = (counter ushr (8 * i)).toByte()
            }
    }
}
