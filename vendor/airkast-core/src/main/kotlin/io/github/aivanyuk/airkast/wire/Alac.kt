package io.github.aivanyuk.airkast.wire

/**
 * ALAC frames that carry stereo 16-bit PCM uncompressed: the escape every ALAC decoder reads, so a
 * receiver that takes ALAC takes PCM with no encoder on the sender. A frame is a channel-pair
 * element's header, the samples as they are, big-endian, and the end element.
 */
internal object Alac {
    /** Bytes in a frame of [frames] stereo frames: 23 header bits, 32 bits a frame, 3 end bits. */
    fun size(frames: Int): Int = (HEADER_BITS + frames * 32 + END_BITS + 7) / 8

    /**
     * A frame of [frames] stereo frames from [pcm] at [offset]: 16-bit samples, little-endian,
     * left then right. Its length is the frame length the stream set up, so the header gives none.
     */
    fun frame(
        pcm: ByteArray,
        offset: Int,
        frames: Int,
    ): ByteArray {
        val out = BitWriter(size(frames))
        out.write(CHANNEL_PAIR, 3)
        out.write(0, 4) // element instance tag
        out.write(0, 12) // unused
        out.write(0, 1) // no sample count: the frame is the stream's frame length
        out.write(0, 2) // no bytes shifted out of the samples
        out.write(1, 1) // not compressed
        for (i in 0 until frames * 2) {
            val at = offset + i * 2
            out.write(((pcm[at + 1].toInt() and 0xff) shl 8) or (pcm[at].toInt() and 0xff), 16)
        }
        out.write(END, 3)
        return out.finish()
    }

    private const val CHANNEL_PAIR = 1
    private const val END = 7
    private const val HEADER_BITS = 23
    private const val END_BITS = 3

    private class BitWriter(
        size: Int,
    ) {
        private val bytes = ByteArray(size)
        private var index = 0
        private var pending = 0L
        private var pendingBits = 0

        fun write(
            value: Int,
            bits: Int,
        ) {
            pending = (pending shl bits) or (value.toLong() and ((1L shl bits) - 1))
            pendingBits += bits
            while (pendingBits >= 8) {
                pendingBits -= 8
                bytes[index++] = (pending ushr pendingBits).toByte()
            }
        }

        /** The bytes written, the last one padded with zero bits. */
        fun finish(): ByteArray {
            if (pendingBits > 0) bytes[index] = (pending shl (8 - pendingBits)).toByte()
            return bytes
        }
    }
}
