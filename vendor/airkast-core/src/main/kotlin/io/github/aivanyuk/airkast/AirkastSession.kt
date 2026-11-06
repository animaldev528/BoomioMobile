package io.github.aivanyuk.airkast

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * A connection to one receiver that plays [Media] on it, opened by [Airkast.connect] in the protocol
 * the receiver speaks. Every call suspends on I/O and is safe to make from the main thread. A
 * second sender connecting to the same receiver ends this session.
 */
public interface AirkastSession : AutoCloseable {
    public val receiver: Receiver

    /**
     * What the receiver reports from the moment of collection, ending with
     * [ReceiverEvent.Disconnected], after which the flow completes. Collected once the session has
     * ended, it emits that alone. Every collector hears every event; none replay.
     */
    public val events: Flow<ReceiverEvent>

    /** The latest playback state the receiver reported, kept for late collectors. */
    public val state: StateFlow<PlaybackState>

    /**
     * Plays [item], replacing whatever plays. Returns once the receiver has taken the item; throws
     * [AirkastException.Timeout] when it stays silent, which a reconnect usually fixes.
     *
     * An item the receiver cannot fetch as given, such as a `file:` URL, goes through
     * [Airkast.resolvers] first, and the receiver loads what the first one that handles it serves
     * from the phone. One none of them serves, in a [MediaFormat] no receiver loads or at a URL
     * that is not `http` or `https`, throws [AirkastException.UnsupportedMedia] at once, and the
     * receiver gets nothing.
     */
    public suspend fun load(item: Media)

    public suspend fun play()

    public suspend fun pause()

    /** Seeks the current item and returns the position the receiver reports. */
    public suspend fun seek(position: Duration): Duration?

    public suspend fun playbackInfo(): PlaybackInfo

    /**
     * The renditions the receiver has selected, at most one per [TrackKind]. The LG reports them
     * only for [MediaFormat.Hls] items: for a [MediaFormat.File] item the list is empty, and
     * [selectTrack] is ignored. [renditions] lists every rendition there is to select.
     */
    public suspend fun tracks(): List<Track>

    /**
     * Every audio and subtitle rendition of the loaded [Media], with the ids [selectTrack] takes,
     * read from its HLS master as [HlsMaster.renditions] reads it. Empty before a [load], and for a
     * [MediaFormat.File] item.
     *
     * The session fetches the master once per [load], over HTTP with [Airkast.requestTimeout], and
     * keeps it until the next one. The fetch goes over the phone's default network, not through
     * [Airkast.socketFactory]: the master is on the internet, not on the receiver's network. The
     * receiver fetches the same URL from its own address, so a URL that only it may read throws
     * [AirkastException.ManifestUnreadable] here, as does any answer but 200.
     */
    public suspend fun renditions(): List<Rendition>

    /**
     * Selects the rendition [id] of [kind]; ids follow the master's `EXT-X-MEDIA` order, as
     * [renditions] lists them. A null [id] turns subtitles off, but forced ones. The switch takes a
     * moment, with a rebuffer for audio, and [tracks] then reports it.
     */
    public suspend fun selectTrack(
        kind: TrackKind,
        id: Long?,
    )

    /** The TV's volume from 0 to 1, or null if it does not say. Receivers may not let it be set. */
    public suspend fun volume(): Double?

    /** Ends playback on the receiver, which leaves its player. The session stays open for another [load]. */
    public suspend fun stop()

    /** Closes the connections. A receiver left playing usually stops and goes back to where it was. */
    override fun close()
}
