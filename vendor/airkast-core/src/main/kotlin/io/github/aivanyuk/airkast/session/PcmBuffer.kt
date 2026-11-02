package io.github.aivanyuk.airkast.session

import java.nio.ByteBuffer

/**
 * The PCM an audio stream holds: written and not yet played, as stereo 16-bit little-endian frames
 * in a ring of [capacity] frames. Frames are numbered from 0 at the last [reset]; [start] is the
 * oldest the stream may still need, [end] one past the newest written.
 */
internal class PcmBuffer(
    val capacity: Int,
) {
    private val bytes = ByteArray(capacity * FRAME)

    var start = 0L
        private set

    var end = 0L
        private set

    /** Frames that fit before the buffer is full. */
    val space: Int get() = capacity - (end - start).toInt()

    /** Appends whole frames of [channels]-channel PCM from [pcm], as many as fit, and returns how many. */
    fun append(
        pcm: ByteArray,
        offset: Int,
        length: Int,
        channels: Int,
    ): Int {
        val frames = minOf(space, length / (channels * 2))
        for (i in 0 until frames) put(pcm, offset + i * channels * 2, channels)
        return frames
    }

    /** As the other [append], from [pcm]'s position, which it moves past the frames taken. */
    fun append(
        pcm: ByteBuffer,
        channels: Int,
    ): Int {
        val size = channels * 2
        val frames = minOf(space, pcm.remaining() / size)
        if (frames == 0) return 0
        val chunk = ByteArray(frames * size)
        pcm.get(chunk)
        return append(chunk, 0, chunk.size, channels)
    }

    private fun put(
        pcm: ByteArray,
        at: Int,
        channels: Int,
    ) {
        val to = (end % capacity).toInt() * FRAME
        bytes[to] = pcm[at]
        bytes[to + 1] = pcm[at + 1]
        val right = if (channels == 2) at + 2 else at
        bytes[to + 2] = pcm[right]
        bytes[to + 3] = pcm[right + 1]
        end++
    }

    /** Copies [frames] frames from frame [from] into [out] at 0. They must lie between [start] and [end]. */
    fun read(
        from: Long,
        frames: Int,
        out: ByteArray,
    ) {
        require(from >= start && from + frames <= end) { "Frames $from+$frames outside $start..$end" }
        var copied = 0
        while (copied < frames) {
            val at = ((from + copied) % capacity).toInt()
            val n = minOf(frames - copied, capacity - at)
            bytes.copyInto(out, copied * FRAME, at * FRAME, (at + n) * FRAME)
            copied += n
        }
    }

    /** Lets go of the frames before [frame], which the stream no longer needs. */
    fun release(frame: Long) {
        start = start.coerceAtLeast(minOf(frame, end))
    }

    /** Drops every frame, and numbers the next one written 0. */
    fun reset() {
        start = 0
        end = 0
    }

    companion object {
        /** Bytes in a stereo 16-bit frame. */
        const val FRAME = 4
    }
}
