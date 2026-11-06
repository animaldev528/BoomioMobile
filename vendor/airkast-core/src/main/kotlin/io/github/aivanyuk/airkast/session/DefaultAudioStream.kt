package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Airkast.Logger.Level
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AudioStream
import io.github.aivanyuk.airkast.DisconnectReason
import io.github.aivanyuk.airkast.NowPlaying
import io.github.aivanyuk.airkast.PcmFormat
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.wire.Alac
import io.github.aivanyuk.airkast.wire.BinaryPlist
import io.github.aivanyuk.airkast.wire.Dmap
import io.github.aivanyuk.airkast.wire.Rtp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * AirPlay 2 realtime audio: pair, SETUP the session with NTP timing and its event channel, RECORD,
 * SETUP a type-96 stream of ALAC, then send RTP packets of what the app wrote, sealed with the
 * stream's key, [latency] ahead of the receiver's output, with a sync packet each second that maps
 * their RTP clock to NTP time.
 *
 * One thread sends: it wakes every few milliseconds, sends every packet due within [latency], and
 * silence for one that falls due within a quarter of it with nothing written. Another reads the
 * receiver's retransmit requests. A pause or a flush sends FLUSH and starts a new timeline once the
 * receiver has answered.
 */
internal class DefaultAudioStream private constructor(
    override val receiver: Receiver,
    override val format: PcmFormat,
    private val options: SessionOptions,
    private val control: ControlConnection,
    private val timing: TimingResponder,
) : AudioStream {
    private val log = Log(options.logger, "audio")
    private val eventLog = log.tagged("events")
    private val rate = format.sampleRate.toLong()
    override val latency: Duration = options.audioLatency
    private val latencyFrames = frames(latency)
    private val leadFrames = latencyFrames / 4
    private val buffer = PcmBuffer((latencyFrames + rate).toInt())
    private val random = SecureRandom()
    private val key = ByteArray(32).also(random::nextBytes)
    private val ssrc = random.nextInt()
    private val uri = "rtsp://${control.localAddress.hostAddress}/${random.nextInt() and Int.MAX_VALUE}"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Runs FLUSH and the like in the order the app asked for them. */
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val space = Channel<Unit>(Channel.CONFLATED)
    private val closed = AtomicBoolean(false)

    /** Why the stream ended, set once and before [closed], so a call that finds it closed reads it. */
    private val endReason = AtomicReference<DisconnectReason?>(null)
    private val mutableEvents = MutableSharedFlow<ReceiverEvent>(extraBufferCapacity = 64)

    @Volatile
    private var disconnected: ReceiverEvent.Disconnected? = null

    override val events: Flow<ReceiverEvent> =
        mutableEvents
            .onSubscription { disconnected?.let { emit(it) } }
            .transformWhile { event ->
                emit(event)
                event !is ReceiverEvent.Disconnected
            }

    private var eventChannel: EventChannel? = null
    private var data: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var sender: Thread? = null

    @Volatile
    private var opened: TimeMark? = null

    // Everything below is guarded by `lock`.
    private val lock = ReentrantLock()

    /** Signalled when there is something to send, or the stream may start: a write, a play, a flush answered. */
    private val wake = lock.newCondition()
    private var playing = false

    /** Waiting for the receiver to answer a FLUSH, before a new timeline starts. */
    private var flushes = 0

    /** Whether the timeline below is set: from the first packet after a start or a flush. */
    private var anchored = false

    /** `System.nanoTime()` at which the receiver plays [anchorRtp]. */
    private var anchorNanos = 0L
    private var anchorRtp = 0L
    private var anchorSeq = 0

    /** The frame [anchorRtp] carries, and the position until the receiver plays it. */
    private var heldFrame = 0L
    private var marker = true
    private var syncFirst = true
    private var lastSync = 0L
    private var nextRtp = random.nextInt().toLong() and 0xffffffffL
    private var nextSeq = random.nextInt() and 0xffff
    private var nextFrame = 0L

    // What each packet in the last RING carried, by sequence number, to send again and to find the frame playing.
    private val sent = arrayOfNulls<ByteArray>(RING)
    private val seqOf = IntArray(RING) { -1 }
    private val rtpOf = LongArray(RING)
    private val frameOf = LongArray(RING)
    private val countOf = IntArray(RING)

    override val isPlaying: Boolean get() = lock.withLock { playing }

    override val position: Duration get() = duration(lock.withLock { playedFrame(System.nanoTime()) })

    override val buffered: Duration
        get() = duration(lock.withLock { buffer.end - playedFrame(System.nanoTime()) })

    private fun start(
        identity: SenderIdentity,
        sessionKey: ByteArray,
    ) {
        val base =
            Opening.setUp(
                control,
                uri,
                Opening.sessionBody(identity, UUID.randomUUID().toString().uppercase(), timing),
                options,
                log,
            )
        (base["timingPort"] as? Long)?.takeIf { it > 0 }?.let { timing.reach(it.toInt()) }
        val eventPort =
            (base["eventPort"] as? Long)?.toInt()
                ?: throw AirkastException.Rejected("SETUP without an event port", 200)
        val eventSocket = Opening.connectEvents(receiver, eventPort, options, log)
        eventChannel = EventChannel(eventSocket, sessionKey, ::onMessage, ::onEventChannelClosed, eventLog)

        // The Samsung HW-Q990B (AirPlay SDK 2.0.7, srcvers 366.0) answers a stream SETUP sent
        // before RECORD, takes RECORD after it, and then plays nothing.
        control
            .exchange(
                "RECORD",
                uri,
                headers =
                    listOf(
                        "X-Apple-ProtocolVersion" to "1",
                        "Range" to "npt=0-",
                        "RTP-Info" to "seq=$nextSeq;rtptime=$nextRtp",
                    ),
            ).requireSuccess("RECORD")

        val local = control.localAddress
        val controlSocket = DatagramSocket(InetSocketAddress(local, 0)).also { this.controlSocket = it }
        val stream =
            control
                .exchange(
                    "SETUP",
                    uri,
                    body =
                        BinaryPlist.encode(
                            mapOf(
                                "streams" to
                                    listOf(
                                        mapOf(
                                            "type" to STREAM_TYPE,
                                            "ct" to ALAC,
                                            "audioFormat" to if (rate == 48_000L) ALAC_48000 else ALAC_44100,
                                            "audioMode" to "default",
                                            "spf" to FRAMES_PER_PACKET.toLong(),
                                            "sr" to rate,
                                            "latencyMin" to rate / 4,
                                            "latencyMax" to rate * 2,
                                            "shk" to key,
                                            "controlPort" to controlSocket.localPort.toLong(),
                                            "isMedia" to true,
                                            "supportsDynamicStreamID" to false,
                                            "streamConnectionID" to (ssrc.toLong() and 0xffffffffL),
                                        ),
                                    ),
                            ),
                        ),
                    contentType = ControlConnection.BPLIST,
                ).requireSuccess("SETUP stream")
        val ports = (Opening.plist(stream)["streams"] as? List<*>)?.firstOrNull() as? Map<*, *>
        val dataPort =
            (ports?.get("dataPort") as? Long)?.toInt()
                ?: throw AirkastException.Rejected("SETUP stream without a data port", stream.status)
        val receiverControlPort =
            (ports["controlPort"] as? Long)?.toInt()
                ?: throw AirkastException.Rejected("SETUP stream without a control port", stream.status)
        log.debug { "stream set up, data port $dataPort, control port $receiverControlPort" }
        controlSocket.connect(InetSocketAddress(receiver.host, receiverControlPort))
        data =
            DatagramSocket(InetSocketAddress(local, 0)).apply {
                connect(InetSocketAddress(receiver.host, dataPort))
            }

        sender = Thread(::send, "airkast-audio").apply { isDaemon = true }
        Thread(::serveControl, "airkast-audio-control").apply { isDaemon = true }.start()
        if (options.keepAlive) {
            scope.launch {
                while (isActive && !closed.get()) {
                    delay(FEEDBACK_INTERVAL)
                    try {
                        control.exchange("POST", "/feedback")
                    } catch (_: LateReply) {
                    } catch (e: Exception) {
                        end(e)
                    }
                }
            }
        }
        opened = TimeSource.Monotonic.markNow()
        sender?.start()
    }

    override suspend fun write(
        pcm: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(length % format.frameSize == 0) { "Write whole frames of ${format.frameSize} bytes" }
        var at = offset
        val end = offset + length
        while (at < end) {
            at +=
                lock.withLock {
                    if (closed.get()) throw ended(null)
                    buffer.append(pcm, at, end - at, format.channels).also { if (it > 0) wake.signalAll() }
                } * format.frameSize
            // A closed channel wakes the writer to find the stream closed.
            if (at < end) space.receiveCatching()
        }
    }

    override fun offer(pcm: ByteBuffer): Int =
        lock.withLock {
            if (closed.get()) throw ended(null)
            buffer.append(pcm, format.channels).also { if (it > 0) wake.signalAll() } * format.frameSize
        }

    override fun play() {
        lock.withLock {
            if (playing || closed.get()) return
            playing = true
            wake.signalAll()
        }
        log.debug { "play" }
    }

    override fun pause() {
        lock.withLock {
            if (!playing) return
            val played = playedFrame(System.nanoTime())
            playing = false
            heldFrame = played
            nextFrame = played
            buffer.release(played)
            flushReceiver()
        }
        log.debug { "pause" }
    }

    override fun flush() {
        lock.withLock {
            buffer.reset()
            heldFrame = 0
            nextFrame = 0
            flushReceiver()
            space.trySend(Unit)
        }
        log.debug { "flush" }
    }

    /**
     * Has the receiver drop every packet sent, and holds the next timeline until it has answered.
     * Called under `lock`.
     */
    private fun flushReceiver() {
        if (!anchored && flushes == 0) return
        anchored = false
        flushes++
        val info = "seq=$nextSeq;rtptime=${nextRtp and 0xffffffffL}"
        requests.launch {
            try {
                control.exchange("FLUSH", uri, headers = listOf("RTP-Info" to info)).requireSuccess("FLUSH")
            } catch (e: Exception) {
                end(e)
            } finally {
                lock.withLock {
                    flushes--
                    wake.signalAll()
                }
            }
        }
    }

    override suspend fun setVolume(volume: Double) {
        // Receivers take decibels, from -30 to 0, and -144 for silent.
        val decibels = if (volume <= 0.0) -144.0 else -30.0 + 30.0 * volume.coerceAtMost(1.0)
        parameter("volume: ${String.format(Locale.ROOT, "%.6f", decibels)}\r\n".toByteArray(), "text/parameters")
    }

    override suspend fun volume(): Double? =
        request {
            val reply =
                control.exchange(
                    "GET_PARAMETER",
                    uri,
                    body = "volume\r\n".toByteArray(),
                    contentType = "text/parameters",
                )
            if (reply.status !in 200..299) return@request null
            val decibels =
                String(reply.body).substringAfter("volume:", "").trim().toDoubleOrNull() ?: return@request null
            ((decibels + 30) / 30).coerceIn(0.0, 1.0)
        }

    override suspend fun setNowPlaying(
        nowPlaying: NowPlaying,
        elapsed: Duration,
    ) {
        val now = lock.withLock { if (anchored) rtpAt(System.nanoTime()) else nextRtp } and 0xffffffffL
        val rtpInfo = "rtptime=$now"
        if (receiver.takesTrackInfo) {
            parameter(
                Dmap.track(nowPlaying.title, nowPlaying.artist, nowPlaying.album),
                "application/x-dmap-tagged",
                rtpInfo,
            )
        }
        nowPlaying.artwork?.takeIf { receiver.takesArtwork }?.let { artwork ->
            parameter(artwork, if (artwork.isPng()) "image/png" else "image/jpeg", rtpInfo)
        }
        nowPlaying.duration?.takeIf { receiver.takesProgress }?.let { duration ->
            val start = now - frames(elapsed)
            val end = start + frames(duration)
            val progress = "progress: ${start and 0xffffffffL}/$now/${end and 0xffffffffL}\r\n"
            parameter(progress.toByteArray(), "text/parameters", rtpInfo)
        }
    }

    private suspend fun parameter(
        body: ByteArray,
        contentType: String,
        rtpInfo: String? = null,
    ) = request {
        control
            .exchange(
                "SET_PARAMETER",
                uri,
                headers = listOfNotNull(rtpInfo?.let { "RTP-Info" to it }),
                body = body,
                contentType = contentType,
            ).requireSuccess("SET_PARAMETER $contentType")
    }

    /** Runs [block] on the control connection, failing as the rest of the API does. */
    private suspend fun <T> request(block: () -> T): T =
        withContext(Dispatchers.IO) {
            if (closed.get()) throw ended(null)
            try {
                block()
            } catch (e: LateReply) {
                throw AirkastException.Timeout(e.message.orEmpty())
            } catch (e: IOException) {
                end(e)
                throw ended(e)
            }
        }

    /** The sending thread: sends what is due, then sleeps a few milliseconds, or until there is something to play. */
    private fun send() {
        val packets = ArrayList<ByteArray>()
        val pcm = ByteArray(FRAMES_PER_PACKET * PcmBuffer.FRAME)
        try {
            while (!closed.get()) {
                packets.clear()
                var sync: ByteArray? = null
                lock.withLock {
                    while (!closed.get() && (!playing || flushes > 0)) wake.await()
                    if (closed.get()) return
                    val now = System.nanoTime()
                    if (!anchored) anchor(now)
                    val playhead = rtpAt(now)
                    val played = playedFrame(now)
                    if (played > buffer.start) {
                        buffer.release(played)
                        space.trySend(Unit)
                    }
                    while (nextRtp <= playhead + latencyFrames) {
                        val available = (buffer.end - nextFrame).toInt()
                        if (available < FRAMES_PER_PACKET && nextRtp > playhead + leadFrames) break
                        val frames = minOf(available, FRAMES_PER_PACKET)
                        pcm.fill(0)
                        buffer.read(nextFrame, frames, pcm)
                        packets += packet(pcm, frames)
                    }
                    if (syncFirst || now - lastSync >= SYNC_INTERVAL) {
                        sync = Rtp.sync(syncFirst, playhead, TimingResponder.ntpAt(now), playhead + latencyFrames)
                        syncFirst = false
                        lastSync = now
                    }
                }
                // The sync goes first, so the receiver can place the packets after it.
                sync?.let { datagram(controlSocket, it) }
                packets.forEach { datagram(data, it) }
                Thread.sleep(WAKE_MILLIS)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            end(e)
        }
    }

    /** When sends began to fail, or 0 while they go out. Written only by the sending thread and the control reader. */
    @Volatile
    private var failingSince = 0L

    /**
     * Sends [packet] on [socket]. A send that fails drops the packet, which the receiver asks for
     * again: Wi-Fi refuses a send for a moment, as WSL's network did once with "Network is
     * unreachable". Sends that fail for [SEND_FAILURES] end the stream.
     */
    private fun datagram(
        socket: DatagramSocket?,
        packet: ByteArray,
    ) {
        try {
            socket?.send(DatagramPacket(packet, packet.size))
            failingSince = 0
        } catch (e: IOException) {
            val now = System.nanoTime()
            if (failingSince == 0L) {
                failingSince = now
                log.warn(e) { "a packet did not go out" }
            } else if (now - failingSince > SEND_FAILURES.inWholeNanoseconds) {
                end(e)
            }
        }
    }

    /** Starts a timeline at [now]: the next packet plays [latency] later. Called under `lock`. */
    private fun anchor(now: Long) {
        anchored = true
        anchorNanos = now + latency.inWholeNanoseconds
        anchorRtp = nextRtp
        anchorSeq = nextSeq
        heldFrame = nextFrame
        marker = true
        syncFirst = true
        log.debug { "the next frame plays in ${latency.inWholeMilliseconds} ms" }
    }

    /** The next packet, of [frames] written frames in [pcm] and silence after them. Called under `lock`. */
    private fun packet(
        pcm: ByteArray,
        frames: Int,
    ): ByteArray {
        val packet = Rtp.audio(nextSeq, nextRtp, ssrc, marker, Alac.frame(pcm, 0, FRAMES_PER_PACKET), key)
        marker = false
        val slot = nextSeq and (RING - 1)
        sent[slot] = packet
        seqOf[slot] = nextSeq
        rtpOf[slot] = nextRtp
        frameOf[slot] = nextFrame
        countOf[slot] = frames
        nextFrame += frames
        nextRtp += FRAMES_PER_PACKET
        nextSeq = (nextSeq + 1) and 0xffff
        return packet
    }

    /** The RTP time the receiver plays at `System.nanoTime()` [now]. Called under `lock`. */
    private fun rtpAt(now: Long): Long = anchorRtp + Math.floorDiv((now - anchorNanos) * rate, NANOS)

    /** The written frame the receiver plays at [now], or the one it will play first. Called under `lock`. */
    private fun playedFrame(now: Long): Long {
        if (!playing || !anchored) return heldFrame
        val playhead = rtpAt(now)
        if (playhead < anchorRtp) return heldFrame
        val count = minOf((nextSeq - anchorSeq) and 0xffff, RING)
        for (back in 1..count) {
            val seq = (nextSeq - back) and 0xffff
            val slot = seq and (RING - 1)
            if (seqOf[slot] != seq || rtpOf[slot] > playhead) continue
            return frameOf[slot] + minOf(countOf[slot].toLong(), playhead - rtpOf[slot])
        }
        return heldFrame
    }

    /** Reads the receiver's retransmit requests and sends the packets again, while it still has them. */
    private fun serveControl() {
        val socket = controlSocket ?: return
        val buffer = ByteArray(64)
        while (!closed.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                // Closing the stream closes the socket; anything else leaves no way to answer the
                // receiver's retransmit requests, so the stream ends.
                if (!closed.get()) end(e)
                return
            }
            val (first, count) = Rtp.retransmitRequest(buffer, packet.length) ?: continue
            log.verbose { "the receiver asks for $count packets from $first again" }
            val again =
                lock.withLock {
                    (0 until minOf(count, RING)).mapNotNull { i ->
                        val seq = (first + i) and 0xffff
                        sent[seq and (RING - 1)]?.takeIf { seqOf[seq and (RING - 1)] == seq }
                    }
                }
            again.forEach { datagram(data, it) }
        }
    }

    private fun onMessage(message: Map<String, Any?>) {
        eventLog.at(Level.Debug, null) { "${message["type"]} ${message["name"] ?: ""}".trim() }
        val volume = (message["volume"] as? Number)?.toDouble()
        val remote = message["type"] == "sendMediaRemoteCommand"
        val command = if (remote) remoteCommand(message["value"]) else null
        val event =
            when {
                remote && message["value"] == "dvlc" && volume != null -> ReceiverEvent.VolumeChanged(volume)
                command != null -> ReceiverEvent.RemoteCommand(command)
                else -> ReceiverEvent.Other(message)
            }
        mutableEvents.tryEmit(event)
    }

    override fun close() = end(null, byApp = true)

    private fun onEventChannelClosed(cause: Throwable?) = end(cause)

    private fun end(cause: Throwable?) = end(cause, byApp = false)

    /** Ends the stream, once: by [close] when [byApp], tearing it down first, or by a failure. */
    private fun end(
        cause: Throwable?,
        byApp: Boolean,
    ) {
        val reason = if (byApp) DisconnectReason.ClosedByApp else Opening.disconnectReason(cause)
        if (!endReason.compareAndSet(null, reason)) return
        closed.set(true)
        lock.withLock {
            playing = false
            wake.signalAll()
        }
        space.close()
        val event = ReceiverEvent.Disconnected(cause, reason)
        disconnected = event
        mutableEvents.tryEmit(event)
        requests.cancel()
        scope.cancel()
        runCatching { eventChannel?.close() }
        if (byApp) {
            // TEARDOWN lets the receiver go back to its own input at once; the sockets close after it.
            Thread({
                runCatching {
                    control.exchange(
                        "TEARDOWN",
                        uri,
                        body = BinaryPlist.encode(emptyMap<String, Any?>()),
                        contentType = ControlConnection.BPLIST,
                    )
                }
                closeSockets()
            }, "airkast-teardown").apply { isDaemon = true }.start()
        } else {
            closeSockets()
        }
        val lasted = opened?.elapsedNow() ?: return
        val failure = if (byApp) null else AirkastException.Disconnected(cause, reason)
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
    }

    /** What a call on the ended stream throws, [cause] and all, with the reason it ended for. */
    private fun ended(cause: Throwable?) =
        AirkastException.Disconnected(cause, endReason.get() ?: DisconnectReason.Unknown)

    private fun closeSockets() {
        runCatching { data?.close() }
        runCatching { controlSocket?.close() }
        runCatching { control.close() }
        runCatching { timing.close() }
    }

    private fun frames(duration: Duration): Long = duration.inWholeNanoseconds * rate / NANOS

    private fun duration(frames: Long): Duration = (frames.coerceAtLeast(0) * NANOS / rate).nanoseconds

    private fun ByteArray.isPng(): Boolean = size > 4 && this[0] == 0x89.toByte() && this[1] == 'P'.code.toByte()

    companion object {
        private const val STREAM_TYPE = 96L
        private const val ALAC = 2L
        private const val ALAC_44100 = 0x40000L
        private const val ALAC_48000 = 0x100000L
        private const val FRAMES_PER_PACKET = 352
        private const val NANOS = 1_000_000_000L
        private const val RING = 1024
        private const val WAKE_MILLIS = 4L
        private val SYNC_INTERVAL = 1.seconds.inWholeNanoseconds
        private val FEEDBACK_INTERVAL = 2.seconds
        private val SEND_FAILURES = 3.seconds

        fun open(
            receiver: Receiver,
            identity: SenderIdentity,
            options: SessionOptions,
            format: PcmFormat,
        ): AudioStream {
            var paired: Paired? = null
            var timing: TimingResponder? = null
            var stream: DefaultAudioStream? = null
            try {
                paired = Opening.pair(receiver, identity, options)
                val control = paired.control
                timing = TimingResponder(control.localAddress, control.remoteAddress, Log(options.logger, "timing"))
                stream = DefaultAudioStream(receiver, format, options, control, timing)
                stream.start(identity, paired.sessionKey)
                return stream
            } catch (e: Exception) {
                runCatching { stream?.end(e) }
                runCatching { timing?.close() }
                runCatching { paired?.control?.close() }
                throw Opening.failure(e)
            }
        }
    }
}
