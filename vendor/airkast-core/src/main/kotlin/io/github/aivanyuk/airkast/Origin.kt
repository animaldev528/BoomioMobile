package io.github.aivanyuk.airkast

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * What a receiver fetches from the sender, for a [MediaResolver] to publish on: an HTTP server on
 * the address the session's control connection uses, so only the receiver's network reaches it.
 * Each session has its own, which listens from the first [publish] until nothing is published, and
 * stops when the session closes. Every path sits under a random prefix, so another device on the
 * network cannot guess one, but it serves in the clear, as an `http` CDN does.
 */
public interface Origin {
    /**
     * Publishes [resource] under [name], a relative path of letters, digits and `.`, `_`, `~`, `-`
     * such as `video/main.m3u8`, and returns the URL the receiver fetches it at. A second publish
     * under the same name replaces the first. The first opens a socket, so it runs off the main
     * thread, as [MediaResolver.resolve] does.
     */
    public fun publish(
        name: String,
        resource: Resource,
    ): String

    /** Stops serving [url], which [publish] returned. */
    public fun withdraw(url: String)
}

/**
 * Bytes an [Origin] serves. [open] runs on every request, on the origin's own thread, so a live
 * playlist can change between two. One that throws answers that request with 500.
 */
public interface Resource {
    /** The `Content-Type` it is served with, such as `video/mp4`. */
    public val contentType: String

    public fun open(): Content

    public companion object {
        /** [text] in UTF-8, written again on every request. */
        public fun text(
            contentType: String,
            text: () -> String,
        ): Resource =
            object : Resource {
                override val contentType = contentType

                override fun open(): Content {
                    val bytes = text().toByteArray(Charsets.UTF_8)
                    return Content(bytes.size.toLong()) { offset ->
                        ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
                    }
                }
            }

        /** [file], whose length is read again on every request, served in the ranges asked for. */
        public fun file(
            file: File,
            contentType: String,
        ): Resource =
            object : Resource {
                override val contentType = contentType

                override fun open(): Content =
                    Content(file.length()) { offset -> FileInputStream(file).apply { channel.position(offset) } }
            }
    }
}

/**
 * One request's bytes: their [length], or null when it is not known ahead, as for a stream passed
 * through; the origin then sends them whole, to the end of the connection. With a length, it
 * serves the byte range the receiver asks for, read [from] its offset.
 */
public class Content(
    public val length: Long?,
    private val open: (offset: Long) -> InputStream,
) {
    /** The bytes from [offset] on. The origin reads as many as it answers with, then closes them. */
    public fun from(offset: Long): InputStream = open(offset)
}
