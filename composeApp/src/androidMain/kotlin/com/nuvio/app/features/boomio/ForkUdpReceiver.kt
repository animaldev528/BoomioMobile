package com.nuvio.app.features.boomio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import co.touchlab.kermit.Logger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

private const val PREAMBLE_BYTES = 7        // 4B sampleRate + 2B seq (BE) + 1B channel count
private const val MAX_DATAGRAM_BYTES = 4096 // TV sender caps datagrams at 1200 B; headroom to be safe
private const val MIN_SAMPLE_RATE = 8_000
private const val MAX_SAMPLE_RATE = 192_000
// Channel counts the wire may advertise (matches the TV's sender). 6/8 = raw multichannel
// passthrough — rendered via a 5.1/7.1 AudioTrack so Android's own mixer does the stereo fold.
private val SUPPORTED_CHANNEL_COUNTS = setOf(1, 2, 6, 8)
private const val BASE_PREFILL_MS = 100     // baseline playout prefill (play only after ~100 ms)
private const val MIN_PREFILL_MS = 20       // sync slider floor — below this the phone underruns
private const val MAX_PREFILL_MS = 500      // sync slider ceiling — beyond this it's unusable lag
private const val JITTER_CUSHION_MS = 120   // extra AudioTrack buffer above prefill for network jitter

/**
 * Socket receive buffer. The kernel drops datagrams when a socket's receive queue
 * is full (`Udp.RcvbufErrors`), and this socket is drained by the SAME thread that
 * calls the blocking `AudioTrack.write` — so every time the track buffer is full
 * the reader stops draining and the queue behind it grows. Measured on the phone
 * 2026-10-11: 33,214 `RcvbufErrors` accumulated, i.e. real audio was discarded by
 * the kernel, not lost in the air. The default buffer is ~64 KB, which at 5.1/48 kHz
 * (576 KB/s) is only ~113 ms of slack; 1 MiB is ~1.7 s of it and costs nothing.
 * [setReceiveBufferSize] is advisory — the kernel may clamp to net.core.rmem_max,
 * which is why [getReceiveBufferSize] is logged rather than assumed.
 */
private const val RECEIVE_BUFFER_BYTES = 1 shl 20

/**
 * A seq gap up to this long is repaired with silence, keeping the playout timeline
 * continuous. A longer gap means the track already drained, so patching it would
 * add seconds of lag; that case resyncs instead (see [ForkUdpReceiver]).
 */
private const val GAP_FILL_MAX_MS = 250

/**
 * Receives the TV's private-listening PCM stream on a bound [DatagramSocket]
 * and plays it through a low-latency [AudioTrack].
 *
 * Wire format (from the nuvio-tv `PrivateListeningAudioSender`; each datagram is
 * self-describing so a late-joining phone syncs on its first packet and a
 * mid-stream rate change flows through):
 * ```
 *   bytes 0..3  sampleRate (big-endian int32)
 *   bytes 4..5  sequence   (big-endian uint16, wraps)
 *   byte   6    channel count (1..8): 2 = stereo; 6/8 = raw multichannel passthrough
 *   bytes 7..   PCM16 signed little-endian interleaved, channel-count channels
 * ```
 *
 * The TV sends the decoded PCM in its native layout (2.0 stays stereo; 5.1/7.1 pass through
 * untouched — the TV never downmixes). For 6/8ch this receiver renders through a multichannel
 * AudioTrack and Android's own mixer folds it to whatever the earbuds accept; a hand-rolled TV-side
 * downmix was the source of an audible artifact on surround content, passthrough is clean. Each
 * datagram is a whole number of frames, so writes are always frame-aligned.
 *
 * The AudioTrack's own buffer IS the jitter buffer: it is sized to the playout
 * prefill plus a [JITTER_CUSHION_MS] cushion, and ~[BASE_PREFILL_MS] (+ the
 * user's audio-sync offset, see [setSyncOffsetMs]) is prefilled (written while
 * the track is still stopped) before [AudioTrack.play] is called, so network
 * jitter lands inside the cushion instead of popping the speaker. After play,
 * streaming `write()` blocks when the buffer is full, so the phone is paced by
 * the TV's decode clock and cannot drift (R4 in the spec).
 *
 * **Packet loss.** This is UDP: the sender deliberately drops frames under
 * back-pressure, the kernel drops them when the socket queue overflows, and Wi-Fi
 * drops them in flight. The sequence number makes the loss visible, and a visible
 * gap is *repaired* rather than left to slip the timeline: a gap up to
 * [GAP_FILL_MAX_MS] is written through as silence (playback stays continuous and
 * in sync; the audio simply has a hole where the audio had one), and a longer one
 * — meaning the track already ran dry — resyncs by flushing and re-prefilling at
 * the current prefill target. Both are counted and reported on [close]; before
 * this, loss was silently played as a time-compressed jump, which is what "the
 * audio cuts out" sounds like.
 *
 * Audio sync: the phone is structurally a fixed offset behind the TV's decoded
 * moment, dominated by the prefill + OS output latency. The companion remote's
 * Audio sync slider shifts that offset in ~± steps around the baseline by
 * changing the prefill target; [setSyncOffsetMs] while playing tears the track
 * down and re-cushions to the new target (a brief gap, then audio at the new
 * delay). This corrects the setup-dependent TV-vs-phone lip-sync offset.
 *
 * All socket + AudioTrack work happens on a single daemon thread; [close]
 * unblocks the blocking receive by closing the socket, joins the thread, and
 * only then stops/releases the track — the track is never touched concurrently.
 */
internal class ForkUdpReceiver(private val socket: DatagramSocket) {

    private val closed = AtomicBoolean(false)
    @Volatile private var running = true

    @Volatile private var syncOffsetMs = 0

    private val buffer = ByteArray(MAX_DATAGRAM_BYTES)

    /** Widest silence patch we will ever need, reused so a repair allocates nothing. */
    private val silence = ByteArray(MAX_DATAGRAM_BYTES)

    // ── Loss accounting (reported once, on close) ─────────────────────────────
    private var lastSeq = -1
    private var gapEvents = 0
    private var gapPackets = 0L
    private var maxGapPackets = 0
    private var resyncs = 0
    private var announcedLoss = false

    // AudioTrack state — touched only from the receiver thread (post-join in close()).
    private var track: AudioTrack? = null
    private var trackSampleRate = 0
    private var trackChannels = 2
    private var trackPlaying = false
    private var appliedPrefillMs = 0
    private var prefilledBytes = 0

    private val thread = Thread({ runLoop() }, "nuvio-pl-udp-receiver").apply { isDaemon = true }

    fun start() = thread.start()

    /** User audio-sync offset in ms (+ = later). Applied live; persists in the session. */
    fun setSyncOffsetMs(offsetMs: Int) {
        syncOffsetMs = offsetMs
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        running = false
        runCatching { socket.close() } // unblocks receive()
        runCatching { thread.join(1_500) }
        val current = track
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
            track = null
        }
        // One line, only when something was actually lost — this is the measurement
        // that was missing: before it, a fork that dropped a third of its packets
        // looked identical to a clean one.
        if (gapEvents > 0) {
            LOG.w {
                "fork ended with loss: $gapEvents gaps, $gapPackets datagrams missing, " +
                    "worst $maxGapPackets, $resyncs resync(s)"
            }
        } else {
            LOG.i { "fork ended clean (no sequence gaps)" }
        }
    }

    private fun runLoop() {
        // Must be set before the first receive; the effective size is logged because
        // the kernel clamps to net.core.rmem_max and a silently-tiny buffer is the
        // whole failure mode this guards against.
        val effective = runCatching {
            socket.receiveBufferSize = RECEIVE_BUFFER_BYTES
            socket.receiveBufferSize
        }.getOrDefault(-1)
        LOG.i { "fork socket receive buffer: $effective bytes (asked for $RECEIVE_BUFFER_BYTES)" }

        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (!running) break
                continue
            }
            val len = packet.length
            if (len <= PREAMBLE_BYTES) continue
            val sampleRate = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            if (sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) continue
            val channels = buffer[6].toInt() and 0xFF
            if (channels !in SUPPORTED_CHANNEL_COUNTS) continue
            val frameBytes = 2 * channels
            val payloadLen = len - PREAMBLE_BYTES
            // Whole frames only — AudioTrack.write must never be handed a partial frame.
            if (payloadLen < frameBytes || payloadLen % frameBytes != 0) continue
            val seq = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
            writePcm(sampleRate, channels, payloadLen, seq)
        }
    }

    private fun writePcm(sampleRate: Int, channels: Int, payloadLen: Int, seq: Int) {
        ensureTrack(sampleRate, channels)
        val current = track ?: return

        if (lastSeq >= 0) {
            val delta = (seq - lastSeq) and 0xFFFF
            if (delta > 1) {
                val missing = delta - 1
                val missingMs = missing.toLong() * payloadLen * 1_000L /
                    (sampleRate.toLong() * frameBytes(channels))
                gapEvents++
                gapPackets += missing
                if (missing > maxGapPackets) maxGapPackets = missing
                if (!announcedLoss) {
                    announcedLoss = true
                    LOG.w {
                        "fork packet loss: $missing datagram(s) missing (~${missingMs}ms) — " +
                            "repairing; further loss is counted, not logged"
                    }
                }
                if (missingMs <= GAP_FILL_MAX_MS) {
                    writeSilence(sampleRate, channels, missingMs.toInt())
                } else {
                    // Longer than the track can hold — it has already drained, so
                    // filling would only add lag. Drop what is buffered and refill.
                    resyncs++
                    LOG.i { "fork gap of ~${missingMs}ms exceeds the cushion — resyncing" }
                    resync(current)
                }
            }
        }
        lastSeq = seq

        val wantPrefill = targetPrefillMs()
        val written = runCatching { current.write(buffer, PREAMBLE_BYTES, payloadLen) }.getOrDefault(0)
        if (written <= 0) return
        if (!trackPlaying) {
            prefilledBytes += written
            if (prefilledBytes >= msBytes(sampleRate, channels, wantPrefill)) {
                runCatching { current.play() }
                trackPlaying = true
            }
        }
    }

    /** Write [ms] of silence so a lost span does not slip the playout timeline. */
    private fun writeSilence(sampleRate: Int, channels: Int, ms: Int) {
        val current = track ?: return
        val frameBytes = frameBytes(channels)
        var remaining = msBytes(sampleRate, channels, ms.coerceAtMost(GAP_FILL_MAX_MS))
        remaining = remaining / frameBytes * frameBytes
        while (remaining > 0) {
            val chunk = minOf(remaining, silence.size / frameBytes * frameBytes)
            if (chunk <= 0) return
            val written = runCatching { current.write(silence, 0, chunk) }.getOrDefault(0)
            if (written <= 0) return
            remaining -= written
            if (!trackPlaying) {
                prefilledBytes += written
                if (prefilledBytes >= msBytes(sampleRate, channels, targetPrefillMs())) {
                    runCatching { current.play() }
                    trackPlaying = true
                }
            }
        }
    }

    /** Discard buffered audio and re-prefill, so a long stall restarts cleanly. */
    private fun resync(current: AudioTrack) {
        runCatching { current.pause() }
        runCatching { current.flush() }
        trackPlaying = false
        prefilledBytes = 0
    }

    /** The current prefill target: the 100 ms baseline plus the user's sync offset. */
    private fun targetPrefillMs(): Int =
        (BASE_PREFILL_MS + syncOffsetMs).coerceIn(MIN_PREFILL_MS, MAX_PREFILL_MS)

    private fun channelMaskFor(channels: Int): Int? = when (channels) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        6 -> AudioFormat.CHANNEL_OUT_5POINT1
        8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
        else -> null
    }

    private fun frameBytes(channels: Int): Int = 2 * channels

    /**
     * Create (or rebuild) the [AudioTrack] for [sampleRate] and [channels]. Rebuilds when the
     * sample rate changes (mid-stream track change), the channel count changes (2.0 vs 5.1 track,
     * or passthrough toggled), OR the playout prefill target moves (audio-sync slider) — all reset
     * the cushion so playback re-syncs at the new latency.
     *
     * For multichannel (5.1/7.1) input the track is opened with that channel mask and the platform
     * mixer folds to the actual output (earbuds) when the sink is narrower than the track. Low-latency
     * mode is only requested for stereo/mono; multichannel tracks take the default mode.
     */
    private fun ensureTrack(sampleRate: Int, channels: Int) {
        val wantPrefill = targetPrefillMs()
        if (track != null && trackSampleRate == sampleRate && trackChannels == channels &&
            appliedPrefillMs == wantPrefill
        ) return
        val previous = track
        if (previous != null) {
            runCatching { previous.stop() }
            runCatching { previous.release() }
        }
        track = null
        trackSampleRate = 0
        trackChannels = channels
        trackPlaying = false
        appliedPrefillMs = wantPrefill
        prefilledBytes = 0
        // A new track starts a new timeline; the old sequence is meaningless against it.
        lastSeq = -1

        val mask = channelMaskFor(channels) ?: return
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            mask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val minBufferSafe = if (minBuffer > 0) minBuffer else 4_000
        val bufferBytes = maxOf(
            minBufferSafe * 2,
            msBytes(sampleRate, channels, wantPrefill + JITTER_CUSHION_MS),
        )
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(mask)
            .build()
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && channels <= 2) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val built = runCatching { builder.build() }.getOrNull() ?: return
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { built.release() }
            return
        }
        track = built
        trackSampleRate = sampleRate
        trackChannels = channels
    }

    private fun msBytes(sampleRate: Int, channels: Int, ms: Int) =
        sampleRate * frameBytes(channels) * ms / 1_000

    private companion object {
        private val LOG = Logger.withTag("ForkUdpReceiver")
    }
}
