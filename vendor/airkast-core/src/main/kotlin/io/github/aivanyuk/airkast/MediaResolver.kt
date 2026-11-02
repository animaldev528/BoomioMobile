package io.github.aivanyuk.airkast

import java.io.File
import java.net.URI
import java.util.UUID

/**
 * Turns a [Media] the receiver cannot fetch as given into one it can, publishing what it needs on
 * the session's [Origin]: a file on the phone, and later a DASH manifest translated to HLS.
 * [AirkastSession.load] asks each of [Airkast.resolvers] in order and loads what the first one
 * returns.
 */
public fun interface MediaResolver {
    /**
     * What the receiver loads in place of [media], or null when this resolver does not handle it.
     * It runs on an I/O thread, and may fetch; the time counts in [Airkast.Event.Loaded.took]. It
     * throws an [AirkastException] for media it handles but cannot serve, which fails the load;
     * `load` throws any other exception as [AirkastException.UnsupportedMedia] with it as the
     * cause, or as [AirkastException.Disconnected] once the session has ended.
     */
    public suspend fun resolve(
        media: Media,
        receiver: Receiver,
        origin: Origin,
    ): ResolvedMedia?
}

/**
 * What the receiver loads in place of what the app gave. The session closes it once the receiver
 * has moved on: after the receiver takes the next [AirkastSession.load], on
 * [AirkastSession.stop], and when the session ends. A load the receiver does not take leaves it
 * open, since the receiver may still play it. [close] withdraws what the resolver published.
 */
public interface ResolvedMedia : AutoCloseable {
    /** The item the receiver gets, at a URL it can fetch. */
    public val media: Media

    /**
     * The renditions of an HLS master the resolver wrote, with the ids the receiver will use,
     * which [AirkastSession.renditions] returns. Null, the default, reads them from [media]'s
     * master as for any item.
     */
    public val renditions: List<Rendition>? get() = null
}

/**
 * Serves a `file:` URL from the phone: the receiver loads it as a [MediaFormat.File] item from the
 * session's [Origin], with the byte ranges it asks for. In [Airkast.resolvers] by default. Media in
 * another format, such as a playlist on the phone, is left alone, and `load` fails with
 * [AirkastException.UnsupportedMedia] since the receiver cannot fetch a `file:` URL.
 */
public class LocalFileResolver : MediaResolver {
    override suspend fun resolve(
        media: Media,
        receiver: Receiver,
        origin: Origin,
    ): ResolvedMedia? {
        if (!media.url.startsWith("file:", ignoreCase = true) || media.format != MediaFormat.File) return null
        val file =
            try {
                File(URI(media.url))
            } catch (_: Exception) {
                throw AirkastException.UnsupportedMedia(media.format, "The file: URL names no file on this device")
            }
        if (!file.isFile || !file.canRead()) {
            throw AirkastException.UnsupportedMedia(media.format, "The file the item names cannot be read")
        }
        val extension = file.extension.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        val name = "file-${UUID.randomUUID()}" + if (extension.isEmpty()) "" else ".$extension"
        val url = origin.publish(name, Resource.file(file, CONTENT_TYPES[extension] ?: "application/octet-stream"))
        val served = Media(url, media.startAt, MediaFormat.File)
        return object : ResolvedMedia {
            override val media = served

            override fun close() = origin.withdraw(url)
        }
    }

    private companion object {
        val CONTENT_TYPES =
            mapOf(
                "mp4" to "video/mp4",
                "m4v" to "video/x-m4v",
                "mov" to "video/quicktime",
                "m4a" to "audio/mp4",
                "m4b" to "audio/mp4",
                "mp3" to "audio/mpeg",
                "aac" to "audio/aac",
                "wav" to "audio/wav",
                "flac" to "audio/flac",
            )
    }
}
