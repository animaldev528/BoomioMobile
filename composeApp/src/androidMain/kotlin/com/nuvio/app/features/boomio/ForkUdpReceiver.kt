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
 * Sanity cap on a single repaired gap. A gap longer than this is not a stall, it is
 * an outage, and it resyncs rather than being concealed for seconds on end.
 */
private const val GAP_FILL_MAX_MS = 2_000

/**
 * How much already-played audio is kept to conceal a lost span.
 *
 * A gap is filled by replaying the audio that preceded it, so this ring has to be long
 * enough that the repeat does not read as a loop. 400 ms covers every gap seen in
 * practice with room to spare (the worst measured burst was 434 ms, and that one is
 * past [GAP_FILL_MAX_MS] of *audio* only because the sender was emitting its small
 * 440 B buffers at the time). Beyond the ring the replay wraps and repeats, which is
 * why an outage is allowed to give up and resync rather than stutter for seconds.
 *
 * Sized in bytes per stream in [ensureTrack]; at 48 kHz stereo it is ~77 KB, at
 * 192 kHz 8-channel ~1.2 MB.
 */
private const val HISTORY_MS = 400

/**
 * The widest sequence step that can still be a *forward* gap. The counter is a
 * wrapping uint16, so `(seq - lastSeq) and 0xFFFF` returns 65535 both for "65535
 * ahead" and for "exactly 1 behind" — and the old code could not tell them apart,
 * so a single late or reordered datagram was read as a ~135 s gap (a full wrap at
 * the observed ~485 datagrams/s). That mistake was self-amplifying: `lastSeq` was
 * then set to the *stale* value, so the next in-order packet looked like another
 * 135 s gap, and the log shows exactly that cascade — four resyncs inside 200 ms.
 * Anything at or above this is therefore treated as a packet that arrived *behind*
 * us and is dropped, never as a gap. Half the range is the only defensible split:
 * forward steps are small and frequent, backwards steps are at most a few packets.
 */
private const val SEQ_FORWARD_MAX = 0x8000

/**
 * Consecutive behind-us packets tolerated before the receiver assumes the sender
 * restarted rather than that every packet is late. A fresh `PrivateListeningAudioSender`
 * (a re-armed fork) begins its sequence at 0, which from a high standing sequence
 * looks like being permanently "behind"; without this the receiver would drop the
 * whole new stream. Any accepted packet clears the count.
 */
private const val MAX_CONSECUTIVE_LATE = 64

/**
 * Length of the ramp into and out of a concealment patch, and into the audio that
 * follows one. Splice silence straight onto a non-zero sample and the step *is* a
 * click; 4 ms is long enough to be inaudible and short enough that it never
 * meaningfully colors the audio.
 */
private const val FADE_MS = 4

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
 * **There is a second format on the same socket, and the first four bytes choose between
 * them.** The TV may fork the same audio as Opus instead, with a nine-byte header whose
 * bytes 0..3 hold [ForkOpusWire.MAGIC] where the PCM header holds a sample rate — see that
 * constant for why the collision is deliberate and what makes it safe against a receiver
 * that predates it. Everything downstream of the header is identical: both formats are
 * PCM16 playout, both use the same sequence counter, and both are repaired by the same gap
 * accounting. The TV picks per track (a 5.1 track cannot be carried as Opus and falls back),
 * so a mid-session change between the two is normal and expected.
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
 * **Packet loss.** This is UDP: the sender drops frames under back-pressure, and Wi-Fi
 * drops them in flight. (Kernel receive-queue overflow is *not* a real source here:
 * measured on the phone 2026-10-11, the socket's own drop counter sat unchanged at 154
 * across ten minutes while a session lost ~1,900 datagrams, so those never arrived at
 * all.) The sequence number makes the loss visible, and a visible gap is *concealed*
 * rather than left to slip the timeline. Concealment replays the audio that immediately
 * preceded the gap — [HISTORY_MS] of it is kept in a ring — so the patch is real sound
 * of exactly the gap's length. Both halves of that matter. Real sound, because a lost
 * span is anything from one datagram (~6 ms) to tens of them and there are several a
 * second at any real loss rate, and silent holes at that rate are precisely what "the
 * audio crackles" sounds like. Exactly the gap's length, because that is what keeps the
 * playout offset at zero, and an offset that cannot grow is an offset that can never
 * trip anything. A gap too long to be a stall resyncs instead ([GAP_FILL_MAX_MS]).
 *
 * **Two things made the old repair audible, and both are fixed here.** A wrap-around
 * sequence needs the step *classified* before it is called a gap — [SEQ_FORWARD_MAX]
 * explains how one late datagram used to become a ~135 s "gap" and then a cascade of
 * resyncs, which is what a crackle is. And a repair that writes anything must not itself
 * be a step: every patch now ramps out of the audio before it and back in over [FADE_MS]
 * after it. Packets that arrive *behind* us are dropped outright — replaying them would
 * reorder the audio, and advancing [lastSeq] to a stale value is what let the old code
 * turn one late packet into a storm.
 *
 * **What the old model measured, on the Shield, 2026-10-11.** Concealment used to be
 * silence, and it used to be *capped*: past 500 ms of accumulated silence the receiver
 * flushed and re-prefilled. Because silence is written in real time
 * and the sender keeps sending, the playout sat pinned against that ceiling — a median
 * 479 ms of the 500 ms budget, with 8 of 24 sampled gaps at 490 ms or more. A gap of only
 * four datagrams (~24 ms) was then enough to trip it, so the receiver answered 24 ms of
 * loss with a flush plus a ~500 ms forward jump, roughly every 40 s in one live session,
 * on top of the silent holes it wrote several times a second. Matching the patch to the
 * gap deletes the ceiling outright: there is no longer any such thing as accumulated
 * concealment lag.
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

    /**
     * Scratch for the concealment ramps, sized for the worst case rather than allocated
     * per gap: [FADE_MS] at the highest accepted rate and channel count.
     * 192 kHz × 8 ch × 2 B × 4 ms = 12,288 B.
     */
    private val fade = ByteArray(16 * 1024)

    /** Channel samples of the last frame written, for the ramp *out* of live audio. */
    private val lastFrame = IntArray(8)

    /** Reused scratch for building a concealment patch a chunk at a time. */
    private val plc = ByteArray(16 * 1024)

    /**
     * The payload about to be played, and where in it the audio starts.
     *
     * PCM reads the datagram's own bytes ([buffer] at [PREAMBLE_BYTES]), so nothing is copied;
     * Opus reads the decoded frame ([ForkOpusDecoder.bytes] at 0). Every consumer below —
     * [writePcm], [recordHistory], [fadeIn], [rememberLastFrame] — goes through these two
     * rather than naming a buffer, and that is the whole of what lets one playout path, one
     * history ring and one set of fades serve both formats.
     */
    private var payload: ByteArray = buffer
    private var payloadOff: Int = PREAMBLE_BYTES

    /** True while what is being played is a decoded Opus frame rather than datagram PCM. */
    private var opusMode = false

    /** Rebuilt when the stream's channel count changes; null until the first Opus datagram. */
    private var opusDecoder: ForkOpusDecoder? = null

    /** Opus datagrams played — the proof, in the close summary, that this path actually ran. */
    private var opusDatagrams = 0L

    /** One-shot: a device missing libboomioopus would otherwise log once per datagram. */
    private var opusUnavailableReported = false

    /**
     * The audio already handed to the track, most recent last — the source a lost span is
     * filled from. A ring rather than a growing buffer, and sized for the stream in
     * [ensureTrack] so it is a whole number of frames. [historyFilled] saturates at the
     * ring size and tells us whether there is enough in it to conceal anything yet.
     */
    private var history = ByteArray(0)
    private var historyPos = 0
    private var historyFilled = 0

    /** Set when a concealment patch ends, so the next real packet ramps *in* from zero. */
    private var fadeArmed = false

    // ── Loss accounting (reported once, on close) ─────────────────────────────
    private var lastSeq = -1
    private var gapEvents = 0
    private var gapPackets = 0L
    private var maxGapPackets = 0
    private var resyncs = 0
    private var latePackets = 0L
    private var consecutiveLate = 0
    private var announcedLoss = false

    /** Gaps concealed from the history, and the audio they stood in for. */
    private var concealedGaps = 0
    private var concealedMs = 0L

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
        // Past the join, so the read thread can no longer be inside the decoder. The error
        // count is read HERE, before the release: [opusDecoder] is cleared on the next line
        // and the summary below is written last, so reading it there would always say 0.
        val opusErrors = opusDecoder?.decodeErrors ?: 0L
        val opusConcealed = opusDecoder?.concealedFrames ?: 0L
        opusDecoder?.close()
        opusDecoder = null
        val current = track
        // Read the underrun counter BEFORE release() — it dies with the track. This is the
        // one failure the sequence accounting cannot see. `PrivateListeningAudioSender` drops
        // a frame before it is ever handed a sequence number (both of its `continue` paths run
        // ahead of `seq++`), so the phone receives less-than-realtime audio with no gap to
        // detect: a fork can look perfectly clean here while the playout is starving.
        val underruns = current?.let { runCatching { it.underrunCount }.getOrDefault(-1) } ?: -1
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
            track = null
        }
        // One line per fork — this is the measurement that was missing: before it, a fork
        // that dropped a third of its packets looked identical to a clean one. The two
        // halves answer different questions: a gap means data was lost in flight, an
        // underrun means the buffer ran dry whatever the reason.
        // Which codec ACTUALLY ran belongs in the same line as the loss totals, because a
        // phone that asked for opus and a TV that carried pcm look identical from every other
        // number here -- and from anything the TV side can see.
        val codec = if (opusDatagrams > 0) {
            // The plc count is the counterpart of `concealedGaps`: equal means every gap got
            // codec-synthesised audio, short means some fell back to silence for want of
            // decoder state. Without it those two look identical from here.
            "opus ${ForkOpusWire.FRAME_MS}ms x$opusDatagrams datagrams, " +
                "$opusConcealed plc frame(s) ($opusErrors decode error(s))"
        } else {
            "pcm"
        }
        val summary =
            "$gapEvents gaps, $gapPackets datagrams missing, worst $maxGapPackets, " +
                "$concealedGaps concealed (${concealedMs}ms), $resyncs resync(s), " +
                "$latePackets late/duplicate dropped, $underruns underrun(s), codec=$codec"
        if (gapEvents > 0 || latePackets > 0 || underruns > 0) {
            LOG.w { "fork ended with loss: $summary" }
        } else {
            LOG.i { "fork ended clean: $summary" }
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
            // The format discriminator, and the whole of the negotiation: the Opus sender puts
            // a magic here that is not a legal sample rate, so this test and the range check
            // below are one decision. See [ForkOpusWire.MAGIC].
            if (sampleRate == ForkOpusWire.MAGIC) {
                readOpus(len)
                continue
            }
            if (sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) continue
            val channels = buffer[6].toInt() and 0xFF
            if (channels !in SUPPORTED_CHANNEL_COUNTS) continue
            val frameBytes = 2 * channels
            val payloadLen = len - PREAMBLE_BYTES
            // Whole frames only — AudioTrack.write must never be handed a partial frame.
            if (payloadLen < frameBytes || payloadLen % frameBytes != 0) continue
            val seq = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
            // The TV picks the transport per track, so a mid-session switch to PCM is normal
            // and this is where the playout path is told which of the two it is holding.
            opusMode = false
            payload = buffer
            payloadOff = PREAMBLE_BYTES
            writePcm(sampleRate, channels, payloadLen, seq)
        }
    }

    /**
     * Decode and play one Opus datagram.
     *
     * Every early return here leaves [lastSeq] alone on purpose: the next good packet then
     * registers as a one-datagram gap and is repaired with a concealment frame, which is
     * exactly what a datagram lost in the air gets. Advancing the sequence for a frame we
     * could not decode would hide it.
     */
    private fun readOpus(len: Int) {
        if (len <= ForkOpusWire.HEADER_BYTES) return
        // Refuse what this build cannot read rather than adapt to it. A frame duration other
        // than the one we were built for would mistime every concealment patch and every gap
        // estimate, silently and in opposite directions.
        if (buffer[7].toInt() and 0xFF != ForkOpusWire.FRAME_MS) return
        if ((buffer[8].toInt() ushr 4) != ForkOpusWire.VERSION) return
        val channels = buffer[6].toInt() and 0xFF
        if (channels !in 1..ForkOpusWire.MAX_CHANNELS) return

        var decoder = opusDecoder
        if (decoder == null || decoder.channels != channels) {
            decoder?.close()
            decoder = ForkOpusDecoder(channels)
            opusDecoder = decoder
            if (!decoder.isUsable && !opusUnavailableReported) {
                opusUnavailableReported = true
                LOG.w {
                    "fork: opus datagrams are arriving but libboomioopus is unusable — " +
                        "nothing can be played (the fork will be silent until it is rebuilt)"
                }
            }
        }
        if (!decoder.decode(buffer, ForkOpusWire.HEADER_BYTES, len - ForkOpusWire.HEADER_BYTES)) return

        val seq = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
        opusMode = true
        payload = decoder.bytes
        payloadOff = 0
        opusDatagrams++
        writePcm(ForkOpusWire.SAMPLE_RATE, channels, decoder.payloadBytes, seq)
    }

    private fun writePcm(sampleRate: Int, channels: Int, payloadLen: Int, seq: Int) {
        ensureTrack(sampleRate, channels)
        val current = track ?: return

        if (lastSeq >= 0) {
            val delta = (seq - lastSeq) and 0xFFFF
            if (delta == 0 || delta >= SEQ_FORWARD_MAX) {
                // A duplicate, or a datagram that arrived *behind* the play head. Writing
                // it would reorder the audio, and moving lastSeq back to a stale value is
                // precisely what turned a single late packet into a resync storm under the
                // old `delta > 1` test. Drop it and leave lastSeq alone.
                //
                // A re-armed sender restarts its counter at zero, which from a high
                // standing sequence looks like being permanently behind — so after
                // MAX_CONSECUTIVE_LATE of them, re-lock rather than drop the whole stream.
                latePackets++
                if (++consecutiveLate >= MAX_CONSECUTIVE_LATE) {
                    LOG.w {
                        "fork: $consecutiveLate datagrams arrived behind us — sender restarted, re-locking"
                    }
                    resync(current)
                    lastSeq = -1
                    consecutiveLate = 0
                }
                return
            }
            consecutiveLate = 0
            if (delta > 1) {
                val missing = delta - 1
                val missingMs = missing.toLong() * packetMs(payloadLen, sampleRate, channels)
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
                // Conceal it from the history. The patch is exactly as long as the gap,
                // so this cannot accumulate into drift and there is no ceiling left to
                // trip — which is what used to answer 24 ms of loss with a 500 ms jump.
                if (missingMs <= GAP_FILL_MAX_MS) {
                    concealedGaps++
                    concealedMs += missingMs
                    conceal(sampleRate, channels, missingMs.toInt())
                } else {
                    // Not a stall: an outage. Drop what is buffered and refill.
                    resyncs++
                    LOG.i {
                        "fork gap of ~${missingMs}ms — resyncing (seq $lastSeq→$seq, delta " +
                            "$delta, ${missing} lost, ${payloadLen}B payload, " +
                            "${sampleRate}Hz/${channels}ch)"
                    }
                    resync(current)
                }
            }
        }
        lastSeq = seq

        // Coming out of a concealment patch: ease back up rather than jumping to full
        // amplitude, which would click exactly where the ramp down was avoiding one.
        if (fadeArmed) fadeIn(sampleRate, channels, payloadLen)

        // What we are about to play is what a later gap gets filled from, so record it
        // *after* the ramp-in: the history should be the audio as heard, not as received.
        recordHistory(channels, payloadLen)

        val wantPrefill = targetPrefillMs()
        var off = payloadOff
        var remaining = payloadLen
        // A short write is not an error: AudioTrack may accept less than it was handed,
        // and the old single-shot call silently dropped the tail of the packet mid-audio.
        while (remaining > 0) {
            val written = runCatching { current.write(payload, off, remaining) }.getOrDefault(0)
            if (written <= 0) return
            off += written
            remaining -= written
            acceptWritten(current, sampleRate, channels, written, wantPrefill)
        }
        rememberLastFrame(channels, payloadLen)
    }

    /**
     * Conceal a lost span of [ms] by replaying the audio that preceded it.
     *
     * The patch is built to be *exactly* [ms] long, split into a short ramp out of the
     * live audio and a body taken from [history]. That exactness is the whole point: the
     * old repair wrote silence, which is also real time, so every patch pushed the playout
     * permanently further behind the sender until a later, smaller gap tripped the ceiling
     * and forced a flush. Matching the gap's length keeps the offset at zero for the life
     * of the fork, so accumulated loss has nothing to accumulate into.
     *
     * Filling with audio rather than zeros is the other half. A lost span is between one
     * datagram (~6 ms) and tens of them, several times a second at real-world loss rates;
     * silence makes each one an audible hole, and the body of preceding audio does not.
     */
    private fun conceal(sampleRate: Int, channels: Int, ms: Int) {
        val current = track ?: return
        // Opus conceals its own losses, from its own decoder state, and does it continuously --
        // so replaying PCM history here would throw that away for a worse patch. See
        // [concealWithPlc].
        if (opusMode) {
            concealWithPlc(current, sampleRate, channels, ms)
            return
        }
        val frameBytes = frameBytes(channels)
        val wantPrefill = targetPrefillMs()
        var remaining = msBytes(sampleRate, channels, ms.coerceAtMost(GAP_FILL_MAX_MS))
        remaining = remaining / frameBytes * frameBytes
        if (remaining <= 0) return

        // Ramp out of the live audio first: the body below opens at zero gain, and stepping
        // onto zero from a non-zero sample is a step discontinuity, which is a click.
        val leadBytes = (fadeFrames(sampleRate) * frameBytes).coerceAtMost(remaining / 2)
        if (leadBytes > 0) {
            buildFadeOut(channels, leadBytes / frameBytes)
            if (!writeRun(current, fade, 0, leadBytes, sampleRate, channels, wantPrefill)) return
            remaining -= leadBytes
        }

        val frames = remaining / frameBytes
        if (frames <= 0) {
            fadeArmed = true
            return
        }

        // Nothing played yet (the opening packets of a fork, or just after a rate change):
        // there is no history to conceal from, so silence is the only honest filler.
        // `silence` is one datagram long, so a patch has to go out in chunks of it.
        val ringSize = history.size
        if (ringSize < frameBytes * 2 || historyFilled < frameBytes * 2) {
            val chunkMax = silence.size / frameBytes * frameBytes
            var left = remaining
            while (left > 0 && chunkMax > 0) {
                val chunk = minOf(left, chunkMax)
                if (!writeRun(current, silence, 0, chunk, sampleRate, channels, wantPrefill)) return
                left -= chunk
            }
            fadeArmed = true
            return
        }

        // Fade the body in and out over the same ramp, capped so a very short patch is not
        // *all* ramp. The body ends exactly where the next real packet begins, so the join
        // at that end is already continuous — the ramp there is belt-and-braces.
        val ramp = minOf(fadeFrames(sampleRate), frames / 3).coerceAtLeast(1)
        var src = historyPos - remaining
        src %= ringSize
        if (src < 0) src += ringSize

        var done = 0
        while (done < frames) {
            val chunk = minOf(frames - done, plc.size / frameBytes)
            for (i in 0 until chunk) {
                val n = done + i
                val gain = when {
                    n < ramp -> n.toFloat() / ramp
                    n >= frames - ramp -> (frames - n).toFloat() / ramp
                    else -> 1f
                }
                val dst = i * frameBytes
                for (c in 0 until channels) {
                    val o = src + c * 2
                    val v = ((history[o + 1].toInt() shl 8) or
                        (history[o].toInt() and 0xFF)).toShort().toInt()
                    val scaled = (v * gain).toInt()
                    plc[dst + c * 2] = (scaled and 0xFF).toByte()
                    plc[dst + c * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
                }
                src += frameBytes
                if (src >= ringSize) src -= ringSize
            }
            if (!writeRun(current, plc, 0, chunk * frameBytes, sampleRate, channels, wantPrefill)) return
            done += chunk
        }
        // The audio after the patch must ramp up from the zero the ramp above ended on.
        fadeArmed = true
    }

    /**
     * Fill an Opus gap with codec-synthesised frames — one per lost datagram.
     *
     * Every Opus datagram is exactly one [ForkOpusWire.FRAME_MS] frame, so the patch comes out
     * the same length as the gap and the playout offset stays at zero; there is no accumulated
     * concealment lag left to trip anything. Rounding up to a whole frame at most overshoots by
     * one frame, which the sequence accounting of the *next* gap then sees as one packet less.
     *
     * The output is deliberately **not** ramped in afterwards, so [fadeArmed] is left alone
     * here — unlike the history-replay patch, which ends on a hard zero and must be eased back
     * up. libopus's concealment runs continuously into the next real frame, and a ramp there
     * would itself be a step.
     *
     * Before the first real packet there is no decoder state to extrapolate from, so that
     * window gets silence: real time, of the right length, which is the only property that
     * matters. A device without the native library lands here too, for every gap.
     */
    private fun concealWithPlc(current: AudioTrack, sampleRate: Int, channels: Int, ms: Int) {
        val decoder = opusDecoder ?: return
        val wantPrefill = targetPrefillMs()
        var frames = (ms + ForkOpusWire.FRAME_MS - 1) / ForkOpusWire.FRAME_MS
        while (frames > 0) {
            frames--
            val synthesised = decoder.conceal()
            val src = if (synthesised) decoder.bytes else silence
            val len = if (synthesised) {
                decoder.payloadBytes
            } else {
                ForkOpusWire.FRAME_SAMPLES * frameBytes(channels)
            }
            if (len <= 0 || len > src.size) return
            if (!writeRun(current, src, 0, len, sampleRate, channels, wantPrefill)) return
        }
    }

    /**
     * How many milliseconds of audio one datagram carries, for the gap estimate.
     *
     * PCM's answer is derived from the datagram's own length, which is what makes it correct at
     * every rate and channel count. Opus's is *not*: its packets are variable-bitrate, so their
     * length says nothing about duration. Every Opus datagram is one [ForkOpusWire.FRAME_MS]
     * frame, and reading the packet size instead would misreport a gap by a factor of four and
     * could push a real one past [GAP_FILL_MAX_MS] into a resync.
     */
    private fun packetMs(payloadLen: Int, sampleRate: Int, channels: Int): Long =
        if (opusMode) {
            ForkOpusWire.FRAME_MS.toLong()
        } else {
            payloadLen.toLong() * 1_000L / (sampleRate.toLong() * frameBytes(channels))
        }

    /**
     * Remember the payload we are about to play, so a later gap can be filled from it.
     * Writes through the ring in at most two runs rather than byte by byte.
     */
    private fun recordHistory(channels: Int, payloadLen: Int) {
        val ringSize = history.size
        if (ringSize <= 0 || payloadLen <= 0) return
        var from = payloadOff
        var left = payloadLen
        while (left > 0) {
            val run = minOf(left, ringSize - historyPos)
            System.arraycopy(payload, from, history, historyPos, run)
            historyPos += run
            if (historyPos >= ringSize) historyPos = 0
            from += run
            left -= run
        }
        historyFilled = minOf(ringSize, historyFilled + payloadLen)
    }

    /**
     * Write [len] bytes of [from] starting at [off], following short writes. `false` means
     * the track stopped accepting, which every caller treats as "give up on this patch".
     */
    private fun writeRun(
        current: AudioTrack,
        from: ByteArray,
        off: Int,
        len: Int,
        sampleRate: Int,
        channels: Int,
        wantPrefill: Int,
    ): Boolean {
        var at = off
        var left = len
        while (left > 0) {
            val written = runCatching { current.write(from, at, left) }.getOrDefault(0)
            if (written <= 0) return false
            at += written
            left -= written
            acceptWritten(current, sampleRate, channels, written, wantPrefill)
        }
        return true
    }

    /** Fill [fade] with a linear ramp from [lastFrame] down to zero, [frames] long. */
    private fun buildFadeOut(channels: Int, frames: Int) {
        val frameBytes = frameBytes(channels)
        for (f in 0 until frames) {
            val gain = 1f - f.toFloat() / frames
            val base = f * frameBytes
            for (c in 0 until channels) {
                val scaled = (lastFrame[c] * gain).toInt()
                fade[base + c * 2] = (scaled and 0xFF).toByte()
                fade[base + c * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
        }
    }

    /**
     * Ramp the first [FADE_MS] of the received payload up from zero, in place. Both formats
     * arrive as little-endian PCM16 — that is the point of decoding Opus into the same layout —
     * so this edits [payload] before it goes to the track and needs no copy. For Opus that
     * buffer is the decoder's own output, which is safe: it is overwritten on the next decode,
     * and the decoder's *state* is what carries between frames, not these bytes.
     */
    private fun fadeIn(sampleRate: Int, channels: Int, payloadLen: Int) {
        val frameBytes = frameBytes(channels)
        val frames = minOf(fadeFrames(sampleRate), payloadLen / frameBytes)
        if (frames <= 0) return // nothing to ramp yet; stay armed for the next packet
        fadeArmed = false
        for (f in 0 until frames) {
            val gain = f.toFloat() / frames
            val base = payloadOff + f * frameBytes
            for (c in 0 until channels) {
                val o = base + c * 2
                val v = readS16Le(payload, o)
                val scaled = (v * gain).toInt()
                payload[o] = (scaled and 0xFF).toByte()
                payload[o + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
        }
    }

    /** Remember the last frame of a real payload, so a later ramp-out starts from it. */
    private fun rememberLastFrame(channels: Int, payloadLen: Int) {
        val frameBytes = frameBytes(channels)
        val frameCount = payloadLen / frameBytes
        if (frameCount <= 0) return
        val base = payloadOff + (frameCount - 1) * frameBytes
        for (c in 0 until channels) lastFrame[c] = readS16Le(payload, base + c * 2)
    }

    /** Count a write against the prefill budget, starting playback once it is met. */
    private fun acceptWritten(
        current: AudioTrack,
        sampleRate: Int,
        channels: Int,
        written: Int,
        wantPrefill: Int,
    ) {
        if (trackPlaying) return
        prefilledBytes += written
        if (prefilledBytes >= msBytes(sampleRate, channels, wantPrefill)) {
            runCatching { current.play() }
            trackPlaying = true
        }
    }

    private fun readS16Le(src: ByteArray, offset: Int): Int =
        ((src[offset + 1].toInt() shl 8) or (src[offset].toInt() and 0xFF)).toShort().toInt()

    private fun fadeFrames(sampleRate: Int): Int =
        (sampleRate * FADE_MS / 1_000).coerceAtLeast(1)

    /** Discard buffered audio and re-prefill, so a long stall restarts cleanly. */
    private fun resync(current: AudioTrack) {
        runCatching { current.pause() }
        runCatching { current.flush() }
        trackPlaying = false
        prefilledBytes = 0
        // The track restarts from an empty buffer, so the first packet after this begins
        // from silence and must be ramped up — arm the fade rather than clearing it.
        fadeArmed = true
        lastFrame.fill(0)
        // Back at the live edge. The history is left alone on purpose: it is still the audio
        // that preceded this point, which is exactly what a patch after the resync wants.
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

    /** A whole number of frames of [HISTORY_MS], so the ring never splits a frame. */
    private fun historyCapacity(sampleRate: Int, channels: Int): Int {
        val fb = frameBytes(channels)
        return (msBytes(sampleRate, channels, HISTORY_MS) / fb).coerceAtLeast(2) * fb
    }

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
        consecutiveLate = 0
        // Empty buffer again: whatever the stream opens on, it opens on silence.
        fadeArmed = true
        lastFrame.fill(0)
        // A new track is a new rate/layout, so the history is sized again and starts empty
        // -- it is indexed in this stream's frames and cannot be carried across a change.
        history = ByteArray(historyCapacity(sampleRate, channels))
        historyPos = 0
        historyFilled = 0

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

    // Long intermediate: sampleRate × frameBytes × ms reaches 3.07e9 for 192 kHz 8-channel
    // at a 1000 ms patch, which overflows Int *before* the divide and would size the
    // concealment patch from a wrapped value.
    private fun msBytes(sampleRate: Int, channels: Int, ms: Int): Int =
        (sampleRate.toLong() * frameBytes(channels) * ms / 1_000L).toInt()

    private companion object {
        private val LOG = Logger.withTag("ForkUdpReceiver")
    }
}
