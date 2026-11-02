package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.internal.Poko
import io.github.aivanyuk.airkast.session.DefaultAudioStream
import io.github.aivanyuk.airkast.session.DefaultVideoSession
import io.github.aivanyuk.airkast.session.Log
import io.github.aivanyuk.airkast.session.Opening
import io.github.aivanyuk.airkast.session.SessionOptions
import io.github.aivanyuk.airkast.session.report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Opens sessions to receivers, configured once: what the sender tells a receiver about itself, how
 * it reaches one, how long it waits, and where it keeps pairings. Build one for the app with
 * `Airkast { ... }` and share it; [copy] makes a variant. On Android, `Airkast(context)` from
 * `airkast-android` sets the platform's defaults.
 *
 * ```
 * val airkast = Airkast { logger = Airkast.Logger.println() }
 * val session = airkast.connect(receiver) { secret -> askTheUserFor(secret) }
 * ```
 */
public class Airkast private constructor(
    builder: Builder,
) {
    /** What the sender tells a receiver about itself. A TV shows its [SenderIdentity.name]. */
    public val identity: SenderIdentity = builder.identity

    public val connectTimeout: Duration = builder.connectTimeout
    public val requestTimeout: Duration = builder.requestTimeout

    /**
     * How long a connect waits for the receiver to take the session: a Mac or an Apple TV asks its
     * user whether to allow a sender it has not seen, and answers once they have. A minute by
     * default. It replaces [requestTimeout] for the base SETUP alone, so one shorter than
     * [requestTimeout] gives up on SETUP sooner than on any other request. A sender keeps its
     * [SenderIdentity.deviceId] across launches so the receiver asks once.
     */
    public val approvalTimeout: Duration = builder.approvalTimeout

    /** How long a load waits for the receiver to take the item. */
    public val loadTimeout: Duration = builder.loadTimeout

    /** Sends `/feedback` every two seconds, as Apple's senders do. */
    public val keepAlive: Boolean = builder.keepAlive

    /**
     * Answers the receiver's NTP timing requests, which needs it to reach this device over UDP. On
     * by default: a Mac answers SETUP with 500 without it, and send-airplay2 reports that tvOS
     * stalls SETUP. The LG CX plays either way.
     */
    public val ntpTiming: Boolean = builder.ntpTiming

    /**
     * How far ahead of a receiver's output an [AudioStream] sends: the time from [AudioStream.play]
     * to the first sound, and how long a gap on the network can be before the receiver runs dry.
     * One second by default, between the quarter second receivers take at least and the two
     * seconds they buffer at most.
     */
    public val audioLatency: Duration = builder.audioLatency

    /**
     * Picks the socket factory for the connections to a receiver, or null for the platform's
     * default. It runs before every connect, pairing and [describe], off the caller's thread, and
     * may refuse with an [AirkastException]. On Android, `Airkast(context)` sets one that fails with
     * [AirkastException.NotPermitted] without the local network permission, and binds the
     * connections to the Wi-Fi or Ethernet network the receiver is on, off mobile data and out of a
     * VPN. Setting another replaces both.
     */
    public val socketFactory: (Receiver) -> SocketFactory? = builder.socketFactory

    /**
     * Where [pair] keeps the credentials a PIN or password leaves, and [connect] finds them. In
     * memory by default; `Airkast(context)` keeps them in the app's files, sealed with a key in
     * the Android Keystore.
     */
    public val credentialStore: CredentialStore = builder.credentialStore

    /**
     * What [AirkastSession.load] asks, in order, for an item the receiver cannot fetch as given;
     * the first that handles it publishes what the receiver needs on the session's [Origin]. By
     * default a [LocalFileResolver], which serves `file:` URLs. An item none of them handles goes
     * to the receiver as it is, unless no receiver can load it: then `load` throws
     * [AirkastException.UnsupportedMedia].
     */
    public val resolvers: List<MediaResolver> = builder.resolvers.toList()

    /**
     * Takes the client's debugging lines, from every connect, pairing and session: one per protocol
     * step at [Logger.Level.Debug], and every message on the wire at [Logger.Level.Verbose]. A line
     * never holds the media URL, a key, a PIN, a password or anything a pairing derives. Null, the
     * default, logs nothing.
     */
    public val logger: Logger? = builder.logger

    /**
     * Hears what the client does, for an app's statistics: connects, pairings, loads and how
     * sessions end, each with how long it took ([Event]). Null, the default, hears nothing.
     */
    public val eventListener: ((Event) -> Unit)? = builder.eventListener

    /**
     * Pairs with [receiver] and opens a session in the protocol it speaks. A receiver this client
     * paired with before proves that pairing with the credentials in [credentialStore]. One that
     * asks for a PIN ([Compatibility.NeedsPin]) or a password ([Compatibility.NeedsPassword]) and
     * has none pairs first, as [pair] does, when [ask] is given; without [ask], it fails with
     * [AirkastException.PairingFailed]. Any other receiver pairs transiently.
     *
     * [ask] returns what the user typed for the [Secret] it is given. It runs in the caller's
     * context, so it may show a dialog and wait, and cancelling it gives up the connect. A wrong
     * secret throws [AirkastException.SecretRejected].
     *
     * A receiver may ask for its password again when the session starts, with an HTTP Digest
     * challenge: a Mac does, after pairing with it. It gets the password kept with the
     * credentials, and [ask] is asked only when there is none or the receiver refuses it; the one
     * it takes is kept for the next connect. A receiver that pairs transiently leaves no
     * credentials to keep it with, so it is asked on every connect.
     *
     * A receiver typed in by hand, with no [Receiver.properties], is [describe]d first, so a PIN
     * or password it asks for is known before pairing, and the session's [AirkastSession.receiver]
     * is the described one. If it answers that request with an error, or not at all, the connect
     * goes on with [receiver] as given.
     *
     * A receiver that refuses its credentials has forgotten this sender, or is not the receiver
     * they are for: they leave [credentialStore], and the next connect pairs again. Any other
     * [AirkastException.PairingFailed], such as a busy receiver's error status, keeps them. A
     * connect cancelled midway closes what it opened.
     */
    public suspend fun connect(
        receiver: Receiver,
        ask: (suspend (Secret) -> String)? = null,
    ): AirkastSession =
        open(receiver, ask) { described, options -> DefaultVideoSession.open(described, identity, options) }

    /**
     * Pairs with [receiver] and opens an [AudioStream] that plays the PCM the app writes, in
     * [format]: on a speaker or a soundbar, or on a TV or Mac that plays AirPlay audio
     * ([Receiver.audioCompatibility]). It pairs as [connect] does, and [ask] works the same way.
     * The receiver's NTP timing requests must reach this device over UDP, whatever [ntpTiming] says.
     * A receiver typed in by hand is [describe]d first, as for [connect].
     *
     * [format] is the one input that differs from stream to stream, so it is a parameter here;
     * how streams behave, such as [audioLatency], is the client's, on its builder.
     */
    public suspend fun connectAudio(
        receiver: Receiver,
        format: PcmFormat = PcmFormat(),
        ask: (suspend (Secret) -> String)? = null,
    ): AudioStream =
        open(receiver, ask) { described, options -> DefaultAudioStream.open(described, identity, options, format) }

    /**
     * Asks [receiver] what it is, with `GET /info`, and returns it with [Receiver.properties]
     * filled from the answer, so that [Receiver.compatibility], [Receiver.audioCompatibility],
     * [Receiver.model] and [Receiver.deviceId] say what discovery's record would. Use it for a
     * receiver typed in by hand, which is [Compatibility.Unknown] until then: a picker can then
     * tell a TV from a speaker. [Receiver.name], [Receiver.host] and [Receiver.port] stay as given,
     * and what the answer holds replaces properties of the same name.
     *
     * The receiver answers before pairing, so this asks for no PIN or password. The LG CX, the
     * Samsung HW-Q990B and a Mac answer with their whole TXT record. A receiver that sends only
     * its feature bits and status flags leaves out `pw` and `act`: one that restricts who may
     * cast without saying so in its status flags may then read as [Compatibility.Supported] and
     * fail to connect.
     *
     * Throws [AirkastException.Unreachable] when nothing answers at the address,
     * [AirkastException.Rejected] when the receiver refuses the request, and
     * [AirkastException.UnexpectedReply] when its answer cannot be read.
     */
    public suspend fun describe(receiver: Receiver): Receiver {
        val options = options(receiver, null)
        val properties = withContext(Dispatchers.IO) { Opening.info(receiver, identity, options) }
        return Receiver(receiver.name, receiver.host, receiver.port, receiver.properties + properties)
    }

    /**
     * [describe], or [receiver] as given when it answers `GET /info` with an error or not at all.
     * Nothing at the address, or no permission to reach it, fails the connect as it is.
     */
    private suspend fun describedForConnect(
        receiver: Receiver,
        log: Log,
    ): Receiver {
        if (receiver.properties.isNotEmpty()) return receiver
        return try {
            describe(receiver).also { log.debug { "described: ${it.model} srcvers ${it.sourceVersion}" } }
        } catch (e: AirkastException) {
            if (e is AirkastException.Unreachable || e is AirkastException.NotPermitted) throw e
            log.warn { "GET /info failed (${e.message}), connecting as typed in" }
            receiver
        }
    }

    /** What [connect] and [connectAudio] share: the pairing, the password, and their events. */
    private suspend fun <T : AutoCloseable> open(
        typedIn: Receiver,
        ask: (suspend (Secret) -> String)?,
        open: (Receiver, SessionOptions) -> T,
    ): T {
        val log = Log(logger, "connect")
        val start = TimeSource.Monotonic.markNow()
        // The user's typing, left out of how long the connect took.
        var asking = Duration.ZERO
        var credentials: Credentials? = null
        var receiver = typedIn
        // The receiver the credentials are filed under: a pairing made before it was described is
        // under its host and port, not under the device id that describing it found.
        var filedUnder = typedIn
        try {
            receiver = describedForConnect(typedIn, log)
            filedUnder = receiver
            val secret = receiver.secret
            credentials = credentialStore.get(receiver)
                ?: (if (receiver !== typedIn) credentialStore.get(typedIn)?.also { filedUnder = typedIn } else null)
                ?: if (ask != null && secret != null) {
                    val mark = TimeSource.Monotonic.markNow()
                    try {
                        pair(receiver, secret) { ask(secret) }
                    } finally {
                        asking = mark.elapsedNow()
                    }
                } else {
                    null
                }
            val options = options(receiver, credentials)
            val session =
                try {
                    opening { open(receiver, options) }
                } catch (e: AirkastException) {
                    if (ask == null || !asksForPassword(e)) throw e
                    log.debug { "the receiver asks for its password when the session starts" }
                    val mark = TimeSource.Monotonic.markNow()
                    val password =
                        try {
                            ask(Secret.Password)
                        } finally {
                            asking += mark.elapsedNow()
                        }
                    opening { open(receiver, options.withPassword(password)) }.also {
                        keep(filedUnder, credentials, password, it)
                    }
                }
            val took = start.elapsedNow() - asking
            val how = if (credentials != null) Event.Pairing.Verified else Event.Pairing.Transient
            log.info { "connected in ${took.inWholeMilliseconds} ms, ${how.name.lowercase()}" }
            eventListener.report(Event.Connected(receiver, how, took), log)
            return session
        } catch (e: AirkastException) {
            if (credentials != null && e is AirkastException.PairingFailed && e.credentialsRefused) {
                credentialStore.remove(filedUnder)
                log.warn { "the receiver refused its credentials, which leave the store" }
                eventListener.report(Event.CredentialsDropped(receiver), log)
            }
            val took = start.elapsedNow() - asking
            // A wrong PIN or password is the user's typo, which the app asks again for.
            if (e is AirkastException.SecretRejected) {
                log.debug { "connect failed after ${took.inWholeMilliseconds} ms: ${e.message}" }
            } else {
                log.error(e) { "connect failed after ${took.inWholeMilliseconds} ms" }
            }
            eventListener.report(Event.ConnectFailed(receiver, e, took), log)
            throw e
        }
    }

    /**
     * Whether the receiver asked for a password when the session started: with none to give, or
     * refusing the one kept with the credentials.
     */
    private fun asksForPassword(e: AirkastException): Boolean =
        (e is AirkastException.PairingFailed && e.passwordAsked) ||
            (e is AirkastException.SecretRejected && e.secret == Secret.Password)

    /** Keeps the [password] [session] opened with in [credentials], for the next connect. */
    private suspend fun keep(
        receiver: Receiver,
        credentials: Credentials?,
        password: String,
        session: AutoCloseable,
    ) {
        if (credentials == null) return
        try {
            credentialStore.put(receiver, credentials.withPassword(password))
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    /**
     * Pairs once with a receiver that asks for a [secret], and keeps the [Credentials] in
     * [credentialStore] for every [connect] after. A receiver asked for a [Secret.Pin] shows one
     * on its screen; a [Secret.Password] is the one set in its AirPlay settings. [ask] returns what
     * the user typed; it runs in the caller's context, so it may show a dialog and wait, and
     * cancelling it gives up the pairing. [connect] calls this itself for a receiver that says
     * what it asks for; call it directly for one that asks without saying so, such as a receiver
     * typed in by hand. A wrong secret throws [AirkastException.SecretRejected].
     */
    public suspend fun pair(
        receiver: Receiver,
        secret: Secret = Secret.Pin,
        ask: suspend () -> String,
    ): Credentials {
        val log = Log(logger, "pairing")
        val start = TimeSource.Monotonic.markNow()
        val credentials =
            try {
                val options = options(receiver, null)
                opening { Opening.startPairing(receiver, identity, options, secret) }.use { pairing ->
                    log.debug { if (secret == Secret.Pin) "the receiver shows a PIN" else "pairing with a password" }
                    val code = ask()
                    withContext(Dispatchers.IO) { Opening.finishPairing(pairing, code) }
                }
            } catch (e: AirkastException) {
                if (e is AirkastException.SecretRejected) {
                    log.debug { "pairing failed: ${e.message}" }
                } else {
                    log.error(e) { "pairing failed" }
                }
                eventListener.report(Event.PairingFailed(receiver, e), log)
                throw e
            }
        credentialStore.put(receiver, credentials)
        val took = start.elapsedNow()
        log.info { "paired in ${took.inWholeMilliseconds} ms" }
        eventListener.report(Event.Paired(receiver, took), log)
        return credentials
    }

    /** A copy with [block]'s changes: `airkast.copy { requestTimeout = 2.seconds }`. It shares the [credentialStore]. */
    public fun copy(block: Builder.() -> Unit): Airkast = Builder(this).apply(block).build()

    private suspend fun options(
        receiver: Receiver,
        credentials: Credentials?,
    ): SessionOptions {
        val factory = withContext(Dispatchers.IO) { socketFactory(receiver) }
        return SessionOptions(
            connectTimeout = connectTimeout,
            requestTimeout = requestTimeout,
            approvalTimeout = approvalTimeout,
            loadTimeout = loadTimeout,
            keepAlive = keepAlive,
            ntpTiming = ntpTiming,
            audioLatency = audioLatency,
            socketFactory = factory,
            resolvers = resolvers,
            logger = logger,
            eventListener = eventListener,
            credentials = credentials,
        )
    }

    /**
     * Where the client's debugging lines go: `Airkast { logger = Airkast.Logger.println() }`, or
     * `Airkast.Logger.logcat()` from `airkast-android`. The `tag` says which part wrote a line:
     * `connect`, `pairing`, `session`, `audio` (an [AudioStream]), `control` (the requests to the
     * receiver), `events` (what the receiver sends), `origin` (what the receiver fetches from the
     * sender), `timing`, `credentials` (a sealed
     * [CredentialStore]), `player` (`AirkastPlayer`) or `discovery` (`ReceiverDiscovery`).
     * More may join.
     *
     * A line comes on the thread that did the work: the caller's, an I/O thread, or the thread that
     * reads a session's events. [log] must not block it, so a logger that writes to a file or the
     * network hands the line to a thread of its own. One that throws loses the line, never the
     * session.
     */
    public fun interface Logger {
        /** The lowest level [log] takes. A line below it is never built. */
        public val minLevel: Level get() = Level.Debug

        public fun log(
            level: Level,
            tag: String,
            message: String,
            error: Throwable?,
        )

        /**
         * [Verbose] is every message on the wire, several a second while an item plays. [Debug] is
         * each protocol step, [Info] a connect, pairing or load that worked and a session the app
         * closed, [Warn] something the client recovered from, such as a late answer, and [Error] a
         * connect, pairing, load or session that failed.
         */
        public enum class Level { Verbose, Debug, Info, Warn, Error }

        public companion object {
            /** Prints lines at [minLevel] and above to standard output, for a desktop JVM or a test. */
            public fun println(minLevel: Level = Level.Debug): Logger {
                val lowest = minLevel
                return object : Logger {
                    override val minLevel: Level = lowest

                    override fun log(
                        level: Level,
                        tag: String,
                        message: String,
                        error: Throwable?,
                    ) {
                        kotlin.io.println("${level.name.first()} airkast/$tag: $message")
                        error?.printStackTrace(System.out)
                    }
                }
            }
        }
    }

    /**
     * What the client did, for an app's statistics, as [Airkast.eventListener] hears it. Each event
     * names its [receiver]: its `model`, `sourceVersion` and `compatibility` describe the device,
     * while its `name` and `host` are the user's own. The client times what it reports, so an app
     * needs no start event to match an end with. New events may join in a minor release, so a
     * `when` over them keeps an `else` branch.
     *
     * An event comes on the thread it happened on: the caller's for a connect, a pairing, a load,
     * and a session the app closes; an I/O thread, or the thread that reads the session's events,
     * for a session that ends by itself. The listener must not block it, so one that sends events
     * to a server hands them to a thread or scope of its own. One that throws loses the event,
     * never the session.
     */
    public sealed interface Event {
        public val receiver: Receiver

        /**
         * [Airkast.connect] opened a session, or [Airkast.connectAudio] a stream, [pairing] says how, in [took]. A pairing it ran first
         * is left out of [took], as [Event.Paired] reports it, and so is the user typing a password
         * the receiver asked for when the session started.
         */
        @Poko
        public class Connected(
            override val receiver: Receiver,
            public val pairing: Pairing,
            public val took: Duration,
        ) : Event

        /**
         * [Airkast.connect] or [Airkast.connectAudio] failed with [failure] after [took], a pairing it ran first and the user's
         * typing left out. A pairing that failed there reports [Event.PairingFailed] first. A connect that was
         * cancelled reports nothing.
         */
        @Poko
        public class ConnectFailed(
            override val receiver: Receiver,
            public val failure: AirkastException,
            public val took: Duration,
        ) : Event

        /**
         * A pairing with a PIN or password kept new [Credentials], [took] after it began, the user's
         * typing included.
         */
        @Poko
        public class Paired(
            override val receiver: Receiver,
            public val took: Duration,
        ) : Event

        /**
         * A pairing with a PIN or password failed with [failure]: [AirkastException.SecretRejected]
         * for a wrong one.
         */
        @Poko
        public class PairingFailed(
            override val receiver: Receiver,
            public val failure: AirkastException,
        ) : Event

        /**
         * The receiver refused the credentials in [Airkast.credentialStore], so they left it: it
         * forgot this sender, and the next connect pairs again.
         */
        @Poko
        public class CredentialsDropped(
            override val receiver: Receiver,
        ) : Event

        /** The receiver took an item that [AirkastSession.load] sent, [took] after the load began. */
        @Poko
        public class Loaded(
            override val receiver: Receiver,
            public val took: Duration,
        ) : Event

        /**
         * A load failed with [failure] after [took]: [AirkastException.Timeout] when the receiver
         * never took the item. A load that was cancelled reports nothing.
         */
        @Poko
        public class LoadFailed(
            override val receiver: Receiver,
            public val failure: AirkastException,
            public val took: Duration,
        ) : Event

        /**
         * A session or an [AudioStream] ended, [lasted] after it opened. [failure] is null when the app closed it, and
         * [AirkastException.Disconnected] when the receiver or the network ended it, with its reason.
         */
        @Poko
        public class SessionEnded(
            override val receiver: Receiver,
            public val lasted: Duration,
            public val failure: AirkastException?,
        ) : Event

        /** How a session's keys were made. New ways may join in a minor release. */
        public enum class Pairing {
            /** A pairing for this connection alone, as for a receiver that asks for no PIN or password. */
            Transient,

            /** Proof of a pairing made before with a PIN or password, from the [Airkast.credentialStore]. */
            Verified,
        }
    }

    /**
     * How to build an [Airkast]: `Airkast { logger = Airkast.Logger.println() }`. New options join
     * with defaults, so code that builds one keeps compiling across releases.
     */
    public class Builder() {
        public var identity: SenderIdentity = SenderIdentity()
        public var connectTimeout: Duration = 5.seconds
        public var requestTimeout: Duration = 5.seconds
        public var approvalTimeout: Duration = 60.seconds
        public var loadTimeout: Duration = 10.seconds
        public var keepAlive: Boolean = true
        public var ntpTiming: Boolean = true
        public var audioLatency: Duration = 1.seconds
        public var socketFactory: (Receiver) -> SocketFactory? = { null }
        public var credentialStore: CredentialStore = CredentialStore.inMemory()
        public var resolvers: List<MediaResolver> = listOf(LocalFileResolver())
        public var logger: Logger? = null
        public var eventListener: ((Event) -> Unit)? = null

        internal constructor(airkast: Airkast) : this() {
            identity = airkast.identity
            connectTimeout = airkast.connectTimeout
            requestTimeout = airkast.requestTimeout
            approvalTimeout = airkast.approvalTimeout
            loadTimeout = airkast.loadTimeout
            keepAlive = airkast.keepAlive
            ntpTiming = airkast.ntpTiming
            audioLatency = airkast.audioLatency
            socketFactory = airkast.socketFactory
            credentialStore = airkast.credentialStore
            resolvers = airkast.resolvers
            logger = airkast.logger
            eventListener = airkast.eventListener
        }

        public fun build(): Airkast = Airkast(this)
    }
}

/** Builds an [Airkast]: `Airkast()` with the defaults, or `Airkast { logger = Airkast.Logger.println() }`. */
public fun Airkast(block: Airkast.Builder.() -> Unit = {}): Airkast = Airkast.Builder().apply(block).build()

/**
 * Runs [open] on the IO dispatcher. A caller cancelled meanwhile never gets what [open] returns,
 * since `withContext` drops it, so this closes it.
 */
internal suspend fun <T : AutoCloseable> opening(open: () -> T): T {
    var opened: T? = null
    try {
        return withContext(Dispatchers.IO) { open().also { opened = it } }
    } catch (e: CancellationException) {
        opened?.close()
        throw e
    }
}
