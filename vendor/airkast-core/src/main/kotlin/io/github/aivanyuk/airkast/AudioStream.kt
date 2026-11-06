package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.internal.Poko
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import kotlin.time.Duration

/**
 * Plays audio the app decodes on a receiver, opened by [Airkast.connectAudio]: a speaker or a
 * soundbar, or a TV or Mac that plays AirPlay audio. The app's own player decodes, so it plays what
 * that player plays, local files and protected streams included, and keeps its queue.
 *
 * It works as Android's `AudioTrack` does. The app writes PCM in [format]; [play] and [pause] start
 * and stop the receiver, [flush] drops what was written and not yet heard, and [position] says how
 * much has been heard. What is written is heard [latency] after [play], or after it is written if
 * the stream ran dry. While the app writes nothing, the receiver plays silence, which [position]
 * leaves out.
 *
 * Every call is safe from any thread. [play], [pause] and [flush] return at once, and the stream
 * tells the receiver in the background; a failure there ends the stream, as [events] report.
 */
public interface AudioStream : AutoCloseable {
    public val receiver: Receiver

    /** The PCM [write] and [offer] take. */
    public val format: PcmFormat

    /** How long after [play] the receiver plays the first frame: what it buffers ahead of its output. */
    public val latency: Duration

    /**
     * What the receiver reports from the moment of collection, ending with
     * [ReceiverEvent.Disconnected], after which the flow completes. Collected once the stream has
     * ended, it emits that alone.
     */
    public val events: Flow<ReceiverEvent>

    /** Whether the stream is playing: true from [play] to [pause], whether or not it has PCM to send. */
    public val isPlaying: Boolean

    /**
     * How much of the PCM written since the stream opened or last flushed the receiver has played,
     * by the receiver's clock. It stands still while paused, and while the stream plays silence.
     */
    public val position: Duration

    /** How much written PCM the receiver has yet to play: zero once it played the last frame written. */
    public val buffered: Duration

    /**
     * Queues [length] bytes of [pcm] from [offset], whole frames in [format], and suspends while the
     * stream's buffer is full. The buffer holds [latency] and a second more, so a paused stream fills
     * it and then waits for [play]. Throws [AirkastException.Disconnected] once the stream ended.
     */
    public suspend fun write(
        pcm: ByteArray,
        offset: Int = 0,
        length: Int = pcm.size - offset,
    )

    /**
     * Takes as many whole frames from [pcm] as the buffer has room for, without waiting, and advances
     * its position past them. Returns the bytes taken, 0 when the buffer is full, for a player whose
     * output must not block, as an ExoPlayer `AudioSink` must not. Throws
     * [AirkastException.Disconnected] once the stream ended.
     */
    public fun offer(pcm: ByteBuffer): Int

    /** Starts playing what was written and what will be, [latency] from now. */
    public fun play()

    /**
     * Stops at once: the receiver drops what it buffered, and the stream keeps it, so [play] goes on
     * from the frame that was playing.
     */
    public fun pause()

    /**
     * Drops what was written and not yet heard, and sets [position] back to zero, as a seek or a
     * skip to another track does in the app's player. A playing stream plays what is written next,
     * [latency] later.
     */
    public fun flush()

    /** Sets the receiver's volume, from 0 for silent to 1. Speakers take it; the LG CX ignores it. */
    public suspend fun setVolume(volume: Double)

    /** The receiver's volume from 0 to 1, or null if it does not say. */
    public suspend fun volume(): Double?

    /**
     * Tells the receiver what plays, for a TV or a speaker with a screen to show. With a
     * [NowPlaying.duration], it shows the progress too: [elapsed] is how far into the track the
     * frame the receiver plays now is, so the app calls this again after a seek. Only what the
     * receiver's feature bits say it shows goes out: a receiver without the artwork bit gets no
     * [NowPlaying.artwork]. One with no feature bits to read, such as one typed in by hand, gets
     * everything.
     */
    public suspend fun setNowPlaying(
        nowPlaying: NowPlaying,
        elapsed: Duration = Duration.ZERO,
    )

    /** Ends playback and closes the connections. The receiver goes back to what it was doing. */
    override fun close()
}

/**
 * PCM as an [AudioStream] takes it: 16-bit signed samples, little-endian, with the channels
 * interleaved, as Android's `AudioFormat.ENCODING_PCM_16BIT` and ExoPlayer's decoders give it. The
 * receiver plays stereo, so mono plays on both channels.
 */
@Poko
public class PcmFormat(
    /** Frames a second: 44100 or 48000, the rates receivers take. A player resamples any other. */
    public val sampleRate: Int = 44_100,
    /** 1 or 2. */
    public val channels: Int = 2,
) {
    init {
        require(sampleRate in SAMPLE_RATES) { "Receivers take $SAMPLE_RATES Hz, not $sampleRate" }
        require(channels in 1..2) { "Receivers take 1 or 2 channels, not $channels" }
    }

    /** Bytes per frame: 2 per channel. */
    public val frameSize: Int get() = channels * 2

    public companion object {
        /** The sample rates receivers take. */
        public val SAMPLE_RATES: Set<Int> = setOf(44_100, 48_000)
    }
}

/**
 * What plays, as [AudioStream.setNowPlaying] tells the receiver. Each field is optional, and a TV
 * shows what it gets.
 */
@Poko
public class NowPlaying(
    public val title: String? = null,
    public val artist: String? = null,
    public val album: String? = null,
    public val duration: Duration? = null,
    /** A JPEG or PNG image, such as the album's cover. */
    public val artwork: ByteArray? = null,
)

/**
 * A player's audio output that can move to an [AudioStream], so a cast to a speaker keeps the app's
 * own player playing. `AirkastPlayer` in airkast-media3 [attach]es the stream it opened for a speaker,
 * and null when the cast ends; `AirkastAudioSink` in airkast-exoplayer is one for ExoPlayer, and an
 * app with its own player implements its own.
 */
public fun interface AudioRoute {
    /**
     * Sends the player's audio to [stream] from now on, or back to the device's own output for
     * null. Called on the thread that runs the cast; it must not block.
     */
    public fun attach(stream: AudioStream?)
}
