package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Airkast.Logger.Level
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.DisconnectReason
import io.github.aivanyuk.airkast.HlsMaster
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.MediaFormat
import io.github.aivanyuk.airkast.PlaybackInfo
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Reason
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.Rendition
import io.github.aivanyuk.airkast.ResolvedMedia
import io.github.aivanyuk.airkast.Secret
import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.Track
import io.github.aivanyuk.airkast.TrackKind
import io.github.aivanyuk.airkast.crypto.Hkdf
import io.github.aivanyuk.airkast.wire.BinaryPlist
import io.github.aivanyuk.airkast.wire.HttpMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * AirPlay video v2: pair transiently, SETUP the session and its event channel, SETUP a type-130
 * stream, then drive playback with `/command` messages whose answers come on the event channel.
 */
internal class DefaultVideoSession private constructor(
    override val receiver: Receiver,
    private val options: SessionOptions,
    private val control: ControlConnection,
    private val timing: TimingResponder?,
) : AirkastSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = Log(options.logger, "session")
    private val eventLog = log.tagged("events")
    private val mutableEvents = MutableSharedFlow<ReceiverEvent>(extraBufferCapacity = 64)

    @Volatile
    private var disconnected: ReceiverEvent.Disconnected? = null

    override val events: Flow<ReceiverEvent> =
        mutableEvents
            // Subscribed before the check, so a session that ends between the two is heard either way.
            .onSubscription { disconnected?.let { emit(it) } }
            .transformWhile { event ->
                emit(event)
                event !is ReceiverEvent.Disconnected
            }
    private val mutableState = MutableStateFlow(PlaybackState.Unknown)
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private val sessionId = UUID.randomUUID().toString().uppercase()
    private val commandSessionId = UUID.randomUUID().toString().uppercase()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Map<String, Any?>>>()
    private val nextMessageId = AtomicLong(1)
    private val closed = AtomicBoolean(false)

    /** Why the session ended, set once and before [closed], so a call that finds it closed reads it. */
    private val endReason = AtomicReference<DisconnectReason?>(null)
    private var eventChannel: EventChannel? = null
    private var streamId = 0L
    private val origin = HttpOrigin(control.localAddress, log.tagged("origin"))
    private val rtspUri = "rtsp://${control.localAddress.hostAddress}/${SecureRandom().nextInt() and Int.MAX_VALUE}"

    @Volatile
    private var itemId: String? = null

    @Volatile
    private var itemTaken: CompletableDeferred<Unit>? = null

    /** The item [load] sent last, with its renditions once [renditions] has read them. */
    @Volatile
    private var loaded: Loaded? = null

    /** Whether the rate is 0: set by [pause], [play], [load] and [stop], and by the TV remote's rate changes. */
    @Volatile
    private var paused = false

    /** When [start] finished, which the session's length counts from. A session that never started reports no end. */
    @Volatile
    private var opened: TimeMark? = null

    private fun start(
        identity: SenderIdentity,
        sessionKey: ByteArray,
    ) {
        val base = Opening.setUp(control, rtspUri, Opening.sessionBody(identity, sessionId, timing), options, log)
        val eventPort =
            (base["eventPort"] as? Long)?.toInt()
                ?: throw AirkastException.Rejected("SETUP without an event port", 200)
        val eventSocket = Opening.connectEvents(receiver, eventPort, options, log)
        log.debug { "event channel connected to $eventPort from ${eventSocket.localPort}" }
        eventChannel = EventChannel(eventSocket, sessionKey, ::onMessage, ::onEventChannelClosed, eventLog)

        // Without RECORD the LG plays the item but never sends an event about it.
        control.exchange("RECORD", rtspUri).requireSuccess("RECORD")

        val stream =
            control
                .exchange(
                    "SETUP",
                    rtspUri,
                    body =
                        BinaryPlist.encode(
                            mapOf(
                                "streams" to
                                    listOf(
                                        mapOf(
                                            "type" to STREAM_TYPE,
                                            "controlType" to 1L,
                                            "clientTypeUUID" to URL_STREAM_CLIENT_TYPE,
                                            "channelID" to "${identity.deviceId}-RCS-1",
                                            "clientUUID" to UUID.randomUUID().toString().uppercase(),
                                        ),
                                    ),
                            ),
                        ),
                    contentType = ControlConnection.BPLIST,
                ).requireSuccess("SETUP stream")
        streamId =
            ((Opening.plist(stream)["streams"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("streamID") as? Long
                ?: throw AirkastException.Rejected("SETUP stream without an ID", stream.status)

        if (options.keepAlive) {
            scope.launch {
                while (isActive && !closed.get()) {
                    delay(FEEDBACK_INTERVAL)
                    try {
                        control.exchange("POST", "/feedback")
                    } catch (_: LateReply) {
                        // The LG CX (webOS 04.64.00) once went quiet for over 5 s, 13 minutes into a session, and played on.
                    } catch (e: Exception) {
                        end(e)
                    }
                }
            }
        }
        opened = TimeSource.Monotonic.markNow()
    }

    override suspend fun load(item: Media) {
        val start = TimeSource.Monotonic.markNow()
        try {
            insert(item)
        } catch (e: AirkastException) {
            val took = start.elapsedNow()
            log.error(e) { "load failed after ${took.inWholeMilliseconds} ms" }
            options.eventListener.report(Airkast.Event.LoadFailed(receiver, e, took), log)
            throw e
        }
        val took = start.elapsedNow()
        log.info { "the receiver took the item in ${took.inWholeMilliseconds} ms" }
        options.eventListener.report(Airkast.Event.Loaded(receiver, took), log)
    }

    private suspend fun insert(item: Media) {
        val resolved = resolve(item)
        val media = resolved?.media ?: item
        val mediaType =
            try {
                fetchable(media)
            } catch (e: AirkastException) {
                resolved?.let(::release)
                throw e
            }
        val id = UUID.randomUUID().toString().uppercase()
        val taken = CompletableDeferred<Unit>()
        val previous = loaded
        val current = Loaded(media, resolved)
        itemId = id
        itemTaken = taken
        loaded = current
        paused = false
        var moved = false
        try {
            send(id, media, mediaType)
            withTimeoutOrNull(options.loadTimeout) { taken.await() }
                ?: throw AirkastException.Timeout("The receiver did not take the item")
            moved = true
        } finally {
            val earlier = previous?.take().orEmpty()
            if (moved) {
                earlier.forEach(::release)
            } else {
                // A receiver that did not take the new item may still play the one before, so it stays served,
                // unless the session ended meanwhile, after end() took what the current item held.
                current.keep(earlier)
                if (closed.get()) current.take().forEach(::release)
            }
        }
    }

    /** What the first of the client's resolvers that handles [item] returns, or null when none does. */
    private suspend fun resolve(item: Media): ResolvedMedia? {
        if (options.resolvers.isEmpty()) return null
        var resolved: ResolvedMedia? = null
        try {
            return withContext(Dispatchers.IO) {
                for (resolver in options.resolvers) {
                    resolved = resolving(item) { resolver.resolve(item, receiver, origin) } ?: continue
                    log.debug { "${resolver::class.simpleName ?: "a resolver"} resolved the item" }
                    break
                }
                resolved
            }
        } catch (e: CancellationException) {
            // withContext drops what it returns when the caller is cancelled meanwhile.
            resolved?.let(::release)
            throw e
        }
    }

    /** What [resolve] returns for [item], with any failure but cancellation as an [AirkastException]. */
    private suspend fun resolving(
        item: Media,
        resolve: suspend () -> ResolvedMedia?,
    ): ResolvedMedia? =
        try {
            resolve()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AirkastException) {
            throw e
        } catch (e: Exception) {
            // The origin refuses a publish once the session has closed it.
            if (closed.get()) throw ended(e)
            throw AirkastException.UnsupportedMedia(item.format, "A resolver could not serve the item", e)
        }

    /** The `mediaType` [media] loads as, or why the receiver cannot fetch it. */
    private fun fetchable(media: Media): String {
        val wire =
            media.format.wire
                ?: throw AirkastException.UnsupportedMedia(media.format, "No receiver loads ${media.format} media")
        val scheme = media.url.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") {
            throw AirkastException.UnsupportedMedia(
                media.format,
                "The receiver cannot fetch a${if (scheme.isEmpty()) " relative" else " $scheme:"} URL, and no resolver serves it",
            )
        }
        return wire
    }

    private fun release(resolved: ResolvedMedia) {
        try {
            resolved.close()
        } catch (e: Exception) {
            log.warn(e) { "the resolved item did not close" }
        }
    }

    private suspend fun send(
        id: String,
        item: Media,
        mediaType: String,
    ) {
        io {
            command(
                mapOf(
                    "type" to "insertPlayQueueItem",
                    "item" to
                        mapOf(
                            "uuid" to id,
                            "mediaType" to mediaType,
                            "Content-Location" to item.url,
                            "Start-Position" to cmTime(item.startAt),
                        ),
                ),
            )
            command(
                mapOf(
                    "type" to "setProperty",
                    "property" to "isInterestedInDateRange",
                    "value" to true,
                    "item" to mapOf("uuid" to id),
                ),
            )
            command(mapOf("type" to "setProperty", "property" to "actionAtItemEnd", "value" to 1L))
            command(mapOf("type" to "setRate", "rate" to 1.0))
        }
    }

    override suspend fun play() {
        paused = false
        io { command(mapOf("type" to "setRate", "rate" to 1.0)) }
    }

    override suspend fun pause() {
        paused = true
        io { command(mapOf("type" to "setRate", "rate" to 0.0)) }
    }

    override suspend fun seek(position: Duration): Duration? {
        val zero = cmTime(Duration.ZERO)
        val reply =
            request(
                mapOf(
                    "type" to "seek",
                    "time" to cmTime(position),
                    "toleranceBefore" to zero,
                    "toleranceAfter" to zero,
                    "item" to mapOf("uuid" to itemId),
                ),
            )
        return duration(reply["position"])
    }

    override suspend fun playbackInfo(): PlaybackInfo {
        val info = request(mapOf("type" to "playbackInfo"))["info"] as? Map<*, *> ?: emptyMap<String, Any?>()
        return PlaybackInfo(
            state = shown(state(info["playbackState"] as? String)),
            rate = (info["rate"] as? Number)?.toDouble() ?: 0.0,
            position = duration(info["position"]),
            duration = duration(info["duration"]),
            buffered = ranges(info["loadedTimeRanges"]),
            seekable = ranges(info["seekableTimeRanges"]),
            itemId = (info["item"] as? Map<*, *>)?.get("uuid") as? String,
        )
    }

    override suspend fun tracks(): List<Track> {
        val value = request(mapOf("type" to "property", "property" to "selectedMediaArray"))["value"] as? List<*>
        return value.orEmpty().mapNotNull { option ->
            option as? Map<*, *> ?: return@mapNotNull null
            val kind =
                TrackKind.entries.firstOrNull { it.wire == option["MediaSelectionGroupMediaType"] }
                    ?: return@mapNotNull null
            Track(
                kind = kind,
                id = option["MediaSelectionOptionsPersistentID"] as? Long ?: return@mapNotNull null,
                name = option["MediaSelectionOptionsName"] as? String,
                language = option["MediaSelectionOptionsExtendedLanguageTag"] as? String,
                forced = option["MediaSelectionOptionsForced"] == true,
            )
        }
    }

    override suspend fun renditions(): List<Rendition> {
        val current = loaded ?: return emptyList()
        if (current.media.format != MediaFormat.Hls) return emptyList()
        return current.lock.withLock {
            current.renditions ?: readRenditions(current.media.url).also { current.renditions = it }
        }
    }

    private suspend fun readRenditions(url: String): List<Rendition> {
        val start = TimeSource.Monotonic.markNow()
        val renditions =
            try {
                io { HlsMaster.renditions(MasterPlaylist.read(url, options.requestTimeout)) }
            } catch (e: AirkastException) {
                log.error(e) { "master unreadable after ${start.elapsedNow().inWholeMilliseconds} ms" }
                throw e
            }
        log.debug { "master read in ${start.elapsedNow().inWholeMilliseconds} ms: ${renditions.size} renditions" }
        return renditions
    }

    override suspend fun selectTrack(
        kind: TrackKind,
        id: Long?,
    ) = io {
        val selection =
            buildMap<String, Any?> {
                put("MediaSelectionGroupMediaType", kind.wire)
                id?.let { put("MediaSelectionOptionsPersistentID", it) }
            }
        command(
            mapOf(
                "type" to "setProperty",
                "property" to "selectedMediaArray",
                "value" to listOf(selection),
                "item" to mapOf("uuid" to itemId),
            ),
        )
    }

    override suspend fun volume(): Double? =
        io {
            if (closed.get()) throw ended(null)
            val reply =
                try {
                    control.exchange(
                        "GET_PARAMETER",
                        rtspUri,
                        body = "volume\r\n".toByteArray(),
                        contentType = "text/parameters",
                    )
                } catch (e: LateReply) {
                    throw AirkastException.Timeout(e.message.orEmpty())
                } catch (e: IOException) {
                    throw end(e)
                }
            if (reply.status !in 200..299) return@io null
            // The receiver answers in decibels, from -30 to 0, and -144 for muted.
            val decibels = String(reply.body).substringAfter("volume:", "").trim().toDoubleOrNull() ?: return@io null
            ((decibels + 30) / 30).coerceIn(0.0, 1.0)
        }

    override suspend fun stop() {
        paused = false
        io { command(mapOf("type" to "stop")) }
        loaded?.take()?.forEach(::release)
    }

    override fun close() {
        end(null, byApp = true)
    }

    private suspend fun request(payload: Map<String, Any?>): Map<String, Any?> {
        val id = nextMessageId.getAndIncrement()
        val reply = CompletableDeferred<Map<String, Any?>>()
        pending[id] = reply
        try {
            io { command(payload + mapOf("kind" to "request", "messageID" to id)) }
            return withTimeoutOrNull(options.requestTimeout) { reply.await() }
                ?: throw AirkastException.Timeout("No answer to ${payload["type"]}")
        } finally {
            pending.remove(id)
        }
    }

    private fun command(payload: Map<String, Any?>) {
        if (closed.get()) throw ended(null)
        val response =
            try {
                control.exchange(
                    "POST",
                    "/command",
                    ControlConnection.HTTP,
                    headers =
                        listOf(
                            "User-Agent" to COMMAND_USER_AGENT,
                            "X-Apple-ProtocolVersion" to "1",
                            "X-Apple-Session-ID" to commandSessionId,
                            "X-Apple-StreamID" to streamId.toString(),
                        ),
                    body = BinaryPlist.encode(mapOf("params" to mapOf("data" to BinaryPlist.encode(payload)))),
                    contentType = ControlConnection.BPLIST,
                )
            } catch (e: LateReply) {
                throw AirkastException.Timeout("No answer to ${payload["type"]}")
            } catch (e: Exception) {
                throw end(e)
            }
        val level =
            when {
                response.status !in 200..299 -> Level.Warn

                // A player asks for its position every second, which would drown the steps.
                payload["type"] == "playbackInfo" -> Level.Verbose

                else -> Level.Debug
            }
        log.at(level, null) { "command ${payload["type"]} ${response.status}" }
        response.requireSuccess(payload["type"].toString())
    }

    private fun onMessage(message: Map<String, Any?>) {
        val response = message["kind"] == "response"
        eventLog.at(if (response) Level.Verbose else Level.Debug, null) {
            "${message["kind"] ?: ""} ${message["type"]} ${message["name"] ?: ""}".trim()
        }
        if (response) {
            (message["messageID"] as? Long)?.let { pending[it]?.complete(message) }
            return
        }
        val item = (message["item"] as? Map<*, *>)?.get("uuid") as? String
        val reason = (message["reason"] as? String)?.let(::Reason)
        val event =
            when (message["type"]) {
                "playbackState" -> {
                    ReceiverEvent.StateChanged(shown(state(message["name"] as? String)), reason)
                }

                // The LG CX sends BACK as `pbpr` (pressed) then `pbal` (released), and its volume
                // keys as `dvlc` with the volume from 0 to 1.
                "sendMediaRemoteCommand" -> {
                    val volume = (message["volume"] as? Number)?.toDouble()
                    val command = remoteCommand(message["value"])
                    when {
                        message["value"] == "pbal" -> ReceiverEvent.Back
                        message["value"] == "dvlc" && volume != null -> ReceiverEvent.VolumeChanged(volume)
                        command != null -> ReceiverEvent.RemoteCommand(command)
                        else -> ReceiverEvent.Other(message)
                    }
                }

                "notification" -> {
                    when (message["name"]) {
                        "currentItemChanged" -> {
                            ReceiverEvent.ItemChanged(item, reason)
                        }

                        "itemPlayedToEnd" -> {
                            ReceiverEvent.ItemEnded(item)
                        }

                        "rateChanged" -> {
                            val rate = (message["rate"] as? Number)?.toDouble() ?: 0.0
                            paused = rate == 0.0
                            ReceiverEvent.RateChanged(rate, duration(message["position"]))
                        }

                        "timeJumped" -> {
                            ReceiverEvent.TimeJumped(duration(message["position"]))
                        }

                        else -> {
                            ReceiverEvent.Other(message)
                        }
                    }
                }

                else -> {
                    ReceiverEvent.Other(message)
                }
            }
        // Any message about the item says the receiver took it. The Mac (960.13.25) names it in
        // every notification but `currentItemChanged`, the one the LG CX names it in first.
        if (item != null && item == itemId) itemTaken?.complete(Unit)
        if (event is ReceiverEvent.StateChanged) mutableState.value = event.state
        mutableEvents.tryEmit(event)
    }

    /**
     * The LG CX (webOS 04.64.00) reports a paused `streaming` item as `loading` with rate 0, for as
     * long as the pause lasts (docs/compatibility.md). While the rate is 0, that reads as paused.
     */
    private fun shown(state: PlaybackState): PlaybackState =
        if (state == PlaybackState.Loading && paused) PlaybackState.Paused else state

    private fun onEventChannelClosed(cause: Throwable?) {
        end(cause)
    }

    /**
     * Ends the session, once: by [close] when [byApp], or by a failure or the receiver hanging up.
     * Returns what a call that found it ended throws.
     */
    private fun end(
        cause: Throwable?,
        byApp: Boolean = false,
    ): AirkastException.Disconnected {
        val reason = if (byApp) DisconnectReason.ClosedByApp else Opening.disconnectReason(cause)
        if (!endReason.compareAndSet(null, reason)) return ended(cause)
        closed.set(true)
        val error = AirkastException.Disconnected(cause, reason)
        pending.values.forEach { it.completeExceptionally(error) }
        itemTaken?.completeExceptionally(error)
        mutableState.value = PlaybackState.Stopped
        val event = ReceiverEvent.Disconnected(cause, reason)
        disconnected = event
        mutableEvents.tryEmit(event)
        runCatching { eventChannel?.close() }
        runCatching { control.close() }
        runCatching { timing?.close() }
        loaded?.take()?.forEach(::release)
        origin.close()
        scope.cancel()
        val lasted = opened?.elapsedNow() ?: return error
        val failure = if (byApp) null else error
        when {
            failure == null -> {
                log.info { "closed after ${lasted.inWholeMilliseconds} ms" }
            }

            // Another sender took it, or it was turned off: nothing failed.
            reason == DisconnectReason.ClosedByReceiver -> {
                log.info {
                    "the receiver closed it after ${lasted.inWholeMilliseconds} ms"
                }
            }

            else -> {
                log.error(cause) { "ended after ${lasted.inWholeMilliseconds} ms: $reason" }
            }
        }
        options.eventListener.report(Airkast.Event.SessionEnded(receiver, lasted, failure), log)
        return error
    }

    /** What a call on the ended session throws, [cause] and all, with the reason it ended for. */
    private fun ended(cause: Throwable?) =
        AirkastException.Disconnected(cause, endReason.get() ?: DisconnectReason.Unknown)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private class Loaded(
        val media: Media,
        resolved: ResolvedMedia?,
    ) {
        val lock = Mutex()
        var renditions: List<Rendition>? = resolved?.renditions

        /**
         * What a resolver served the item as, and the items before it a failed load left served,
         * until [take] hands them over to be closed.
         */
        private val serving = mutableListOf<ResolvedMedia>().apply { resolved?.let(::add) }

        fun take(): List<ResolvedMedia> = synchronized(serving) { serving.toList().also { serving.clear() } }

        fun keep(earlier: List<ResolvedMedia>) = synchronized(serving) { serving += earlier }
    }

    companion object {
        private const val STREAM_TYPE = 130L
        private const val URL_STREAM_CLIENT_TYPE = "A6B27562-B43A-4F2D-B75F-82391E250194"
        private val FEEDBACK_INTERVAL = 2.seconds

        /** The version Apple's senders use for `/command`. */
        private const val COMMAND_USER_AGENT = "AirPlay/870.14.1"

        fun open(
            receiver: Receiver,
            identity: SenderIdentity,
            options: SessionOptions,
        ): AirkastSession {
            var paired: Paired? = null
            var timing: TimingResponder? = null
            var session: DefaultVideoSession? = null
            try {
                paired = Opening.pair(receiver, identity, options)
                val control = paired.control
                if (options.ntpTiming) {
                    timing = TimingResponder(control.localAddress, control.remoteAddress, Log(options.logger, "timing"))
                }
                session = DefaultVideoSession(receiver, options, control, timing)
                session.start(identity, paired.sessionKey)
                return session
            } catch (e: Exception) {
                // A session that got as far as its event channel closes that too.
                runCatching { session?.close() }
                runCatching { timing?.close() }
                runCatching { paired?.control?.close() }
                throw Opening.failure(e)
            }
        }

        fun cmTime(time: Duration): Map<String, Any?> =
            mapOf("value" to time.inWholeMilliseconds, "timescale" to 1000L, "flags" to 1L, "epoch" to 0L)

        /** A CMTime as a duration, or null unless it is valid and finite (flags bit 0 set, bit 4 clear). */
        fun duration(time: Any?): Duration? {
            val map = time as? Map<*, *> ?: return null
            val flags = (map["flags"] as? Number)?.toLong() ?: return null
            val timescale = (map["timescale"] as? Number)?.toLong() ?: return null
            val value = (map["value"] as? Number)?.toLong() ?: return null
            if (flags and 1L == 0L || flags and 0x1CL != 0L || timescale <= 0) return null
            return (value * 1000 / timescale).milliseconds
        }

        fun state(name: String?): PlaybackState =
            when (name) {
                "loading" -> PlaybackState.Loading
                "playing" -> PlaybackState.Playing
                "paused" -> PlaybackState.Paused
                "stopped" -> PlaybackState.Stopped
                else -> PlaybackState.Unknown
            }

        private fun ranges(value: Any?): List<ClosedRange<Duration>> =
            (value as? List<*>).orEmpty().mapNotNull { range ->
                val map = range as? Map<*, *> ?: return@mapNotNull null
                val start = duration(map["start"]) ?: return@mapNotNull null
                val length = duration(map["duration"]) ?: return@mapNotNull null
                start..start + length
            }
    }
}
