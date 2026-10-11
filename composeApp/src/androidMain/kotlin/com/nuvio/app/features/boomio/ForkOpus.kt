package com.nuvio.app.features.boomio

import com.boomio.opus.OpusCodec

/**
 * The Opus fork's wire format — the phone half.
 *
 * ⚠️ **These constants are duplicated verbatim in the TV app** (its own `ForkOpusWire` in
 * `com.nuvio.tv.ui.screens.player`). The two clients ship as separate apps in separate
 * repositories and cannot share a module, so the two copies are kept identical by hand —
 * changing one without the other silently breaks the fork.
 *
 * The header is nine bytes, TV → phone:
 * ```
 *   bytes 0..3  magic 0x4F505553 ("OPUS", big-endian)
 *   bytes 4..5  sequence (big-endian uint16, wraps) — the same counter the PCM path uses
 *   byte   6    channel count (1..2)
 *   byte   7    frame duration in ms (20)
 *   byte   8    flags: bits 4..7 format version, bit 0 = in-band FEC present
 *   bytes 9..   one Opus packet
 * ```
 *
 * ⚠️ The magic sits in the four bytes the PCM header uses for the sample rate, and that
 * placement *is* the discrimination mechanism. [ForkUdpReceiver] reads those four bytes as a
 * rate first and drops anything outside 8000..192000, so an Opus datagram is discarded by a
 * receiver that predates this format rather than being played as noise — and this receiver
 * branches on the same four bytes. No handshake is needed in either direction, which is also
 * why the TV can fork PCM for one track and Opus for the next without telling anyone.
 */
internal object ForkOpusWire {

    /** `0x4F505553` — "OPUS" as a big-endian int32. Not a legal sample rate, by construction. */
    const val MAGIC = 0x4F505553

    const val HEADER_BYTES = 9

    /** Bumped only for an incompatible header change; a receiver rejects what it cannot read. */
    const val VERSION = 0
    const val FLAG_FEC = 0x01

    /**
     * The only rate the Opus path carries: libopus's own native rate, so nothing is resampled
     * on either end. A track at any other rate takes the PCM path.
     */
    const val SAMPLE_RATE = 48_000

    const val FRAME_MS = 20
    const val FRAME_SAMPLES = SAMPLE_RATE / 1000 * FRAME_MS

    /**
     * libopus mapping family 0 — the simple path — is mono/stereo only. A wider track needs
     * `opus_multistream_*`, which the bridge does not expose, so 5.1/7.1 takes the PCM path
     * rather than being silently folded or truncated.
     */
    const val MAX_CHANNELS = 2
}

/**
 * Decodes the TV's Opus fork packets, and synthesises a replacement frame for a lost one.
 *
 * The decoder's output is PCM16 **little-endian interleaved** — byte-for-byte the same layout
 * the PCM fork path puts on the wire — so [bytes] goes straight into the same playout code,
 * the same history ring and the same fades, with nothing between them but a copy out of [pcm].
 * That is the point: the two formats differ in transport only, and one playout path serves both.
 *
 * **Why concealment asks the codec rather than replaying history.** [conceal] requests a
 * *packet-loss concealment* frame, which libopus extrapolates from its own decoder state (LPC
 * continuation, fading toward comfort noise). Measured at -3.6 dB on a lost frame — and unlike
 * a replay of preceding audio it cannot read as a loop, and it splices continuously into the
 * next real frame instead of needing a ramp back in. Replaying history remains the *PCM* path's
 * repair, because there is no codec there to ask.
 *
 * Owned and touched only by the receiver's read thread.
 *
 * @param channelCount 1 or 2. The receiver rebuilds this object when the stream's count changes.
 */
internal class ForkOpusDecoder(private val channelCount: Int) {

    private var handle: Long =
        if (OpusCodec.available) {
            OpusCodec.decoderCreate(ForkOpusWire.SAMPLE_RATE, channelCount)
        } else {
            0L
        }

    /**
     * False when the native library or the decoder could not be had.
     *
     * ⚠️ Not a fallback trigger *mid-session*: once the TV is forking Opus there is no
     * in-band way to ask it to stop, because the wire carries no such message. The failover is
     * therefore decided before the arm, not during it — [PrivateListeningSession] asks for PCM
     * when this is false, so an unusable library costs this build the Opus transport and never
     * the feature. This flag is what tells it to.
     */
    val isUsable: Boolean get() = handle != 0L

    val channels: Int get() = channelCount

    private val pcm = ShortArray(ForkOpusWire.FRAME_SAMPLES * channelCount)

    /**
     * The most recent frame as PCM16 LE — the payload the receiver plays. Reused, and valid
     * only until the next [decode] or [conceal].
     */
    val bytes = ByteArray(ForkOpusWire.FRAME_SAMPLES * channelCount * 2)

    /** Bytes of [bytes] holding decoded audio, i.e. how much to write. */
    var payloadBytes = 0
        private set

    /**
     * Whether a real packet has ever been decoded. Concealment before the first decode has no
     * decoder state to extrapolate from, so the receiver writes silence for that window.
     */
    var hasDecoded = false
        private set

    var decodeErrors: Long = 0
        private set

    /** Frames synthesised for lost packets, for the close summary. */
    var concealedFrames: Long = 0
        private set

    /**
     * Decode one packet: the [len] bytes of [data] starting at [offset].
     *
     * @return false when the packet was unusable. The caller deliberately does *not* advance its
     *   sequence on a failure, so the next good packet registers as a one-packet gap and is
     *   repaired with a concealment frame — the same treatment a packet lost in the air gets.
     */
    fun decode(data: ByteArray, offset: Int, len: Int): Boolean {
        if (handle == 0L || len <= 0) return false
        val samples = OpusCodec.decoderDecode(
            handle, data, offset, len, pcm, ForkOpusWire.FRAME_SAMPLES, false,
        )
        if (samples <= 0) {
            decodeErrors++
            return false
        }
        payloadBytes = pack(samples)
        hasDecoded = true
        return true
    }

    /**
     * Synthesise one frame for a lost packet.
     *
     * Always exactly [ForkOpusWire.FRAME_MS] of audio, which is what keeps a patch the same
     * length as the gap it stands in for — the property that makes concealment latency-neutral.
     */
    fun conceal(): Boolean {
        if (handle == 0L || !hasDecoded) return false
        val samples = OpusCodec.decoderDecode(
            handle, null, 0, 0, pcm, ForkOpusWire.FRAME_SAMPLES, false,
        )
        if (samples <= 0) {
            decodeErrors++
            return false
        }
        payloadBytes = pack(samples)
        concealedFrames++
        return true
    }

    fun close() {
        if (handle != 0L) {
            OpusCodec.decoderDestroy(handle)
            handle = 0L
        }
    }

    /** Flatten [samplesPerChannel] interleaved shorts per channel into little-endian bytes. */
    private fun pack(samplesPerChannel: Int): Int {
        val total = samplesPerChannel.coerceAtMost(ForkOpusWire.FRAME_SAMPLES) * channelCount
        var i = 0
        while (i < total) {
            val v = pcm[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            i++
        }
        return total * 2
    }
}
