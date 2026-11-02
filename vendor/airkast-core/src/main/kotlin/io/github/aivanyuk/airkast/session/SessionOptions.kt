package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.MediaResolver
import javax.net.SocketFactory
import kotlin.time.Duration

/**
 * What one connect takes from its `Airkast`: the client's settings, with the socket factory and
 * credentials for this receiver, and the password for one that asks with an HTTP Digest challenge:
 * the one kept with the credentials, unless the user typed another.
 */
internal class SessionOptions(
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val approvalTimeout: Duration,
    val loadTimeout: Duration,
    val keepAlive: Boolean,
    val ntpTiming: Boolean,
    val audioLatency: Duration,
    val socketFactory: SocketFactory?,
    val resolvers: List<MediaResolver>,
    val logger: Airkast.Logger?,
    val eventListener: ((Airkast.Event) -> Unit)?,
    val credentials: Credentials?,
    val password: String? = credentials?.password,
) {
    fun withPassword(password: String): SessionOptions =
        SessionOptions(
            connectTimeout,
            requestTimeout,
            approvalTimeout,
            loadTimeout,
            keepAlive,
            ntpTiming,
            audioLatency,
            socketFactory,
            resolvers,
            logger,
            eventListener,
            credentials,
            password,
        )
}
