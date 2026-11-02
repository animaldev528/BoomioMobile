package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.internal.Poko
import kotlin.time.Duration

/** What to play: a URL the receiver fetches itself, from [startAt]. */
@Poko
public class Media(
    public val url: String,
    public val startAt: Duration = Duration.ZERO,
    /**
     * How the receiver loads the item, read from the URL's extension by default. A URL with no
     * extension airkast knows reads as [MediaFormat.Hls], so a file served from such a URL is
     * given [MediaFormat.File] here, or its MIME type through [MediaFormat.of].
     */
    public val format: MediaFormat = MediaFormat.of(url),
)

/**
 * How a receiver loads a [Media]: as an HLS stream, or as a file it downloads while it plays. A
 * format such as [Dash] names media no receiver loads, so that `load` can say so. New formats may join in a minor release. docs/compatibility.md has what each receiver plays.
 */
public enum class MediaFormat(
    /** The `mediaType` a receiver loads the item as, or null for a format no receiver loads. */
    internal val wire: String?,
) {
    /**
     * An HLS playlist, loaded as `streaming`. The LG reports what it has buffered, and reports and
     * switches tracks ([AirkastSession.tracks]), only for an item loaded this way.
     */
    Hls("streaming"),

    /**
     * A file the receiver downloads while it plays, loaded as `file`: MP4 or MOV video, or audio
     * such as MP3, AAC or M4A. The receiver reports no tracks for it, and the LG no buffered or
     * seekable ranges.
     */
    File("file"),

    /**
     * An MPEG-DASH presentation (`.mpd`, `application/dash+xml`). No receiver loads one, so
     * [AirkastSession.load] throws [AirkastException.UnsupportedMedia] at once, rather than wait
     * out the load timeout for an item the receiver never takes.
     */
    Dash(null),
    ;

    public companion object {
        private val HLS_TYPES =
            setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
        private const val DASH_TYPE = "application/dash+xml"
        private val HLS_EXTENSIONS = setOf("m3u8", "m3u")
        private val FILE_EXTENSIONS = setOf("mp4", "m4v", "mov", "m4a", "m4b", "mp3", "aac", "wav", "flac")

        /**
         * The format of [url]: from [mimeType] when it is HLS's, DASH's or any `video/` or
         * `audio/` type, as a media3 `MediaItem` or an HTTP `Content-Type` gives it, and from the
         * extension of the URL's path otherwise. [Hls] when neither says.
         */
        public fun of(
            url: String,
            mimeType: String? = null,
        ): MediaFormat = mimeType?.let(::ofMimeType) ?: ofPath(url) ?: Hls

        private fun ofMimeType(mimeType: String): MediaFormat? {
            val type = mimeType.substringBefore(';').trim().lowercase()
            return when {
                type in HLS_TYPES -> Hls
                type == DASH_TYPE -> Dash
                type.startsWith("video/") || type.startsWith("audio/") -> File
                else -> null
            }
        }

        private fun ofPath(url: String): MediaFormat? {
            val name = url.substringBefore('#').substringBefore('?').substringAfterLast('/')
            val extension = name.substringAfterLast('.', "").lowercase()
            return when (extension) {
                in HLS_EXTENSIONS -> Hls
                "mpd" -> Dash
                in FILE_EXTENSIONS -> File
                else -> null
            }
        }
    }
}

/** What the receiver says it is doing. New states may join in a minor release. */
public enum class PlaybackState { Loading, Playing, Paused, Stopped, Unknown }

/** The answer to a position poll. Times the receiver marks invalid read as null. */
@Poko
public class PlaybackInfo(
    public val state: PlaybackState,
    public val rate: Double,
    public val position: Duration?,
    public val duration: Duration?,
    /** What the receiver has buffered. Empty unless the item is [MediaFormat.Hls]. */
    public val buffered: List<ClosedRange<Duration>>,
    public val seekable: List<ClosedRange<Duration>>,
    public val itemId: String?,
)

public enum class TrackKind(
    internal val wire: String,
) {
    Audio("soun"),
    Subtitles("sbtl"),
}

/** One rendition the receiver reports as selected. [id] follows the HLS master's order. */
@Poko
public class Track(
    public val kind: TrackKind,
    public val id: Long,
    public val name: String?,
    public val language: String?,
    public val forced: Boolean,
)

/**
 * One audio or subtitle rendition of an HLS master, with the [id] a receiver knows it by, which
 * [AirkastSession.selectTrack] takes. [HlsMaster.renditions] says how ids are counted.
 */
@Poko
public class Rendition(
    public val kind: TrackKind,
    public val id: Long,
    /** The `NAME` attribute, such as "English". */
    public val name: String?,
    /** The `LANGUAGE` attribute, a language tag such as `en` or `pt-BR`. */
    public val language: String?,
    /** Whether `FORCED=YES`: subtitles shown even with subtitles off. */
    public val forced: Boolean,
    /** The `CHARACTERISTICS` attribute's values, such as `public.accessibility.describes-video`. */
    public val characteristics: List<String>,
    /** The `GROUP-ID` attribute: the first group the rendition appears in. */
    public val group: String?,
)

/**
 * Why the receiver changed its state or item, as the receiver names it. A receiver may send a
 * name not listed here, so compare against the constants rather than switch exhaustively.
 */
@JvmInline
public value class Reason(
    public val name: String,
) {
    public companion object {
        /** The item played to its end. */
        public val Ended: Reason = Reason("ended")

        /** The TV switched away from the item: another input, another app, or a second sender. */
        public val Interrupted: Reason = Reason("interrupted")
    }
}

/**
 * What the receiver reports on its own. New events may join in a minor release, so a `when` over
 * them keeps an `else` branch.
 */
public sealed interface ReceiverEvent {
    @Poko
    public class StateChanged(
        public val state: PlaybackState,
        public val reason: Reason?,
    ) : ReceiverEvent

    @Poko
    public class ItemChanged(
        public val itemId: String?,
        public val reason: Reason?,
    ) : ReceiverEvent

    @Poko
    public class ItemEnded(
        public val itemId: String?,
    ) : ReceiverEvent

    /** The TV's own remote changed the rate: pause, play or fast forward. */
    @Poko
    public class RateChanged(
        public val rate: Double,
        public val position: Duration?,
    ) : ReceiverEvent

    @Poko
    public class TimeJumped(
        public val position: Duration?,
    ) : ReceiverEvent

    /**
     * BACK on the TV's remote, which the receiver leaves to the sender: the TV leaves its player
     * only once the sender calls [AirkastSession.stop].
     */
    public data object Back : ReceiverEvent

    /**
     * A playback button on the receiver's remote, or on the receiver itself, that it leaves to the
     * sender: play/pause, next or previous on a speaker's remote. `AirkastPlayer` acts on it
     * unless its `followsRemote` is off; an app that drives a session or stream itself decides.
     */
    @Poko
    public class RemoteCommand(
        public val command: io.github.aivanyuk.airkast.RemoteCommand,
    ) : ReceiverEvent

    /** The TV's remote changed its volume. [volume] runs from 0 to 1. */
    @Poko
    public class VolumeChanged(
        public val volume: Double,
    ) : ReceiverEvent

    /** Any other message, as the receiver sent it. */
    @Poko
    public class Other(
        public val payload: Map<String, Any?>,
    ) : ReceiverEvent

    /**
     * The session or stream ended, for [reason]: the last event, after which the flow completes.
     * [cause] is what the connection failed with, for logs, or null.
     */
    @Poko
    public class Disconnected(
        public val cause: Throwable?,
        public val reason: DisconnectReason,
    ) : ReceiverEvent
}

/**
 * Why a session or stream ended, as the connection showed it. New reasons may join in a minor
 * release, so a `when` over them keeps an `else` branch.
 */
public enum class DisconnectReason {
    /**
     * The receiver closed the connection: another sender took it over, or it left its player and
     * hung up. The cast ended; nothing failed.
     */
    ClosedByReceiver,

    /**
     * The connection failed: it was reset, or the receiver went silent, as it does when it loses
     * power or the Wi-Fi drops. Connecting again may work.
     */
    ConnectionFailed,

    /** The app closed it. */
    ClosedByApp,

    /** It ended in a way none of the others describes. */
    Unknown,
}

/**
 * The playback button a [ReceiverEvent.RemoteCommand] names. New buttons may join in a minor
 * release, so a `when` over them keeps an `else` branch.
 */
public enum class RemoteCommand {
    Play,
    Pause,

    /** One button that plays when paused and pauses when playing. */
    PlayPause,
    Next,
    Previous,
}
