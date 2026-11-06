package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Airkast.Logger.Level

/**
 * The client's [Airkast.Logger] under one tag. A line is built only when the logger takes its
 * level, and a logger that throws loses the line, never the session.
 */
internal class Log(
    val logger: Airkast.Logger?,
    val tag: String,
) {
    fun takes(level: Level): Boolean = logger != null && level >= logger.minLevel

    inline fun verbose(message: () -> String) = at(Level.Verbose, null, message)

    inline fun debug(message: () -> String) = at(Level.Debug, null, message)

    inline fun info(message: () -> String) = at(Level.Info, null, message)

    inline fun warn(
        error: Throwable? = null,
        message: () -> String,
    ) = at(Level.Warn, error, message)

    inline fun error(
        error: Throwable?,
        message: () -> String,
    ) = at(Level.Error, error, message)

    inline fun at(
        level: Level,
        error: Throwable?,
        message: () -> String,
    ) {
        if (takes(level)) write(level, message(), error)
    }

    fun write(
        level: Level,
        message: String,
        error: Throwable?,
    ) {
        try {
            logger?.log(level, tag, message, error)
        } catch (_: Exception) {
        }
    }

    fun tagged(tag: String): Log = Log(logger, tag)

    companion object {
        val NONE = Log(null, "")
    }
}

/** Hands [event] to the client's listener, if it has one. A listener that throws loses the event, never the session. */
internal fun ((Airkast.Event) -> Unit)?.report(
    event: Airkast.Event,
    log: Log,
) {
    val listener = this ?: return
    try {
        listener(event)
    } catch (e: Exception) {
        log.warn(e) { "the event listener threw on ${event::class.simpleName}" }
    }
}
