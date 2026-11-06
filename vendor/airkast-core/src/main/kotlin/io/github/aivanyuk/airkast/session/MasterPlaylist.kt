package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.AirkastException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Fetches an HLS master playlist over the default network. The receiver is not involved: it
 * fetches the same URL itself, from its own address.
 */
internal object MasterPlaylist {
    /** A master is a few kilobytes; anything past this is not one. */
    const val MAX_SIZE = 1 shl 20

    /**
     * Blocks until the master is read, or throws [AirkastException.ManifestUnreadable]. [timeout]
     * bounds the whole read, not each socket read, so a server that sends a byte now and then
     * cannot hold it past [timeout] by more than one read's wait.
     */
    fun read(
        url: String,
        timeout: Duration,
    ): String {
        val connection =
            try {
                URL(url).openConnection() as? HttpURLConnection
            } catch (e: IOException) {
                throw AirkastException.ManifestUnreadable("The master's URL could not be opened", cause = e)
            } ?: throw AirkastException.ManifestUnreadable("The master's URL is not an HTTP URL")
        val deadline = TimeSource.Monotonic.markNow() + timeout
        try {
            connection.connectTimeout = timeout.toSocketTimeout()
            connection.readTimeout = timeout.toSocketTimeout()
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw AirkastException.ManifestUnreadable("The master's server answered $status", status)
            }
            if (connection.contentLength > MAX_SIZE) throw tooLarge()
            val body = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (body.size() + read > MAX_SIZE) throw tooLarge()
                    body.write(buffer, 0, read)
                    if (deadline.hasPassedNow()) {
                        throw AirkastException.ManifestUnreadable("The master took longer than $timeout to read")
                    }
                }
            }
            val text = String(body.toByteArray(), Charsets.UTF_8).removePrefix("﻿")
            if (!text.startsWith("#EXTM3U")) throw AirkastException.ManifestUnreadable("The master is not a playlist")
            return text
        } catch (e: IOException) {
            throw AirkastException.ManifestUnreadable("The master could not be read", cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private fun tooLarge() = AirkastException.ManifestUnreadable("The master is larger than $MAX_SIZE bytes")
}
