package io.github.aivanyuk.airkast.session

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Answers the receiver's NTP-style timing requests. Without it a receiver still plays, but it
 * sends no events, so the sender cannot see the state or read the position.
 */
internal class TimingResponder(
    bind: InetAddress,
    private val receiver: InetAddress,
    private val log: Log = Log.NONE,
) : AutoCloseable {
    private val socket = DatagramSocket(InetSocketAddress(bind, 0))
    val port: Int get() = socket.localPort

    private val thread =
        Thread(::serve, "airkast-timing").apply {
            isDaemon = true
            start()
        }

    private fun serve() {
        val buffer = ByteArray(128)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                return
            }
            log.verbose { "request from ${packet.address.hostAddress}:${packet.port}, ${packet.length} bytes" }
            if (packet.address != receiver || packet.length < PACKET) continue
            val reply = reply(buffer, ntpNow())
            runCatching { socket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
        }
    }

    /**
     * Sends the receiver's timing [port] a request of the sender's own, whose answer it drops. A
     * stateful firewall in front of the sender, as WSL's or a desktop's, then lets the receiver's
     * requests from that port in, where it drops them otherwise.
     */
    fun reach(port: Int) {
        val request =
            ByteBuffer
                .allocate(PACKET)
                .put(0x80.toByte())
                .put(0xD2.toByte())
                .putShort(7)
                .putInt(0)
                .putLong(0)
                .putLong(0)
                .putLong(ntpNow())
                .array()
        runCatching { socket.send(DatagramPacket(request, request.size, InetSocketAddress(receiver, port))) }
    }

    override fun close() = socket.close()

    companion object {
        private const val PACKET = 32
        private const val NTP_EPOCH_OFFSET = 0x83AA7E80L

        /** The reply echoes the request's send time as its reference and stamps now twice. */
        fun reply(
            request: ByteArray,
            now: Long,
        ): ByteArray =
            ByteBuffer
                .allocate(PACKET)
                .apply {
                    put(request[0])
                    put(0xD3.toByte())
                    putShort(7)
                    putInt(0)
                    put(request, 24, 8)
                    putLong(now)
                    putLong(now)
                }.array()

        /**
         * Now in NTP's format, seconds since 1900 above a 32-bit fraction: the wall clock when the
         * class loaded, moved on by the monotonic clock, so it never jumps and resolves below a
         * millisecond. Timing replies and an audio stream's sync packets read the same clock.
         */
        fun ntpNow(): Long = ntp(EPOCH_NANOS + (System.nanoTime() - NANOS))

        /** [ntpNow] at [nanoTime], a reading of `System.nanoTime()`. */
        fun ntpAt(nanoTime: Long): Long = ntp(EPOCH_NANOS + (nanoTime - NANOS))

        private val NANOS = System.nanoTime()
        private val EPOCH_NANOS = System.currentTimeMillis() * 1_000_000

        private fun ntp(epochNanos: Long): Long {
            val seconds = epochNanos / 1_000_000_000 + NTP_EPOCH_OFFSET
            val fraction = ((epochNanos % 1_000_000_000) shl 32) / 1_000_000_000
            return (seconds shl 32) or fraction
        }
    }
}
