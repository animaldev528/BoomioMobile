package io.github.aivanyuk.airkast.wire

import io.github.aivanyuk.airkast.crypto.ChaCha20Poly1305
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The UDP packets of a realtime audio stream (RTP payload type 96): audio sealed with the stream's
 * key, the sync that maps the stream's RTP clock to NTP time, and the receiver's request to send
 * packets again.
 */
internal object Rtp {
    private const val VERSION = 0x80
    private const val MARKER = 0x80
    private const val AUDIO = 0x60
    private const val SYNC = 0xD4
    private const val RETRANSMIT_REQUEST = 0xD5

    /** The first sync after the stream starts or flushes sets bit 4 of its first byte. */
    private const val EXTENSION = 0x10
    private const val HEADER = 12

    /**
     * An audio packet: the RTP header in the clear, then [payload] sealed with [key] under the
     * header's timestamp and SSRC, its tag, and the last 8 bytes of the nonce, which is [sequence].
     * [first] marks the first packet after the stream starts or flushes.
     */
    fun audio(
        sequence: Int,
        rtpTime: Long,
        ssrc: Int,
        first: Boolean,
        payload: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val header =
            ByteBuffer
                .allocate(HEADER)
                .put(VERSION.toByte())
                .put((AUDIO or if (first) MARKER else 0).toByte())
                .putShort(sequence.toShort())
                .putInt(rtpTime.toInt())
                .putInt(ssrc)
                .array()
        val nonce = ByteArray(ChaCha20Poly1305.NONCE_LENGTH)
        ByteBuffer.wrap(nonce, 4, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence.toLong() and 0xffff)
        val sealed = ChaCha20Poly1305.seal(key, nonce, payload, header.copyOfRange(4, HEADER))
        return header + sealed + nonce.copyOfRange(4, ChaCha20Poly1305.NONCE_LENGTH)
    }

    /**
     * A sync: the frame at [playing] plays at [ntpTime], and the next packet sent starts at [next].
     * [first] after the stream starts or flushes, when [next] is the RTP time the receiver was given.
     */
    fun sync(
        first: Boolean,
        playing: Long,
        ntpTime: Long,
        next: Long,
    ): ByteArray =
        ByteBuffer
            .allocate(20)
            .put((VERSION or if (first) EXTENSION else 0).toByte())
            .put(SYNC.toByte())
            .putShort(7)
            .putInt(playing.toInt())
            .putLong(ntpTime)
            .putInt(next.toInt())
            .array()

    /** The packets a retransmit request asks for again, as (first sequence number, count), or null for another packet. */
    fun retransmitRequest(
        packet: ByteArray,
        length: Int,
    ): Pair<Int, Int>? {
        if (length < 8 || packet[1].toInt() and 0xff != RETRANSMIT_REQUEST) return null
        val buffer = ByteBuffer.wrap(packet, 4, 4)
        return (buffer.short.toInt() and 0xffff) to (buffer.short.toInt() and 0xffff)
    }
}
