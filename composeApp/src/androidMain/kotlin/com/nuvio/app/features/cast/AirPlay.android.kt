package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.CredentialStore
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Secret
import io.github.aivanyuk.airkast.SenderIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

private val log = Logger.withTag("AirPlay")

/**
 * Sends a video URL to an AirPlay 2 receiver — an Apple TV, a TV with AirPlay built
 * in, a Mac — and starts it.
 *
 * AirPlay 2, not the old `/play` POST this app used before. The receiver still
 * fetches the stream itself (so the URL has to carry its own token, as for DLNA
 * and Cast), but the sender has to hold an encrypted paired session open and
 * drive a play queue: the receiver ignores a URL alone.
 *
 * The protocol lives in `io.github.aivanyuk:airkast-core`, vendored under
 * `vendor/airkast-core` with a one-line fix for tvOS's SETUP timing — see that
 * module's README.
 *
 * ⚠️ **An Apple TV has to pair with a PIN the first time.** It refuses transient
 * pairing (HTTP 470) and does not advertise that, so [prepareAirPlay] pairs
 * explicitly for one, asking the picker for the code on screen. The pairing is
 * kept in a file in the app's no-backup storage and proved on every later cast.
 *
 * ⚠️ **Timing is per receiver.** A Mac rejects a session that announces no NTP
 * timing; an Apple TV stalls ~32 s and answers 400 when it does. The client is
 * built per device for that reason, sharing one credential store.
 */
internal object AirPlay {

    private const val DEFAULT_PORT = 7000

    /**
     * The budget for waiting on bsc's `master.m3u8` before handing the URL over.
     * AirPlay's sender allows the receiver ~10 s; bsc's first-segment gate is 15 s
     * plus a 30 s grace while a worker is alive, so the wait has to be ours.
     */
    private const val PREFLIGHT_BUDGET_MS = 35_000L
    private const val CONNECT_TIMEOUT_MS = 3_000L
    private const val READ_TIMEOUT_MS = 12_000L
    private const val PREFLIGHT_RETRY_MS = 1_000L

    private val lock = Any()

    @Volatile
    private var credentialStore: CredentialStore? = null

    /** The session the last cast opened, kept open: closing it stops playback. */
    @Volatile
    private var playing: AirkastSession? = null

    /** The live session, for the remote screen to poll and command. */
    fun session(): AirkastSession? = playing

    /** Drops the session reference after playback was ended on purpose. */
    fun release() {
        synchronized(lock) { playing = null }
    }

    /** Pairs with [device] if it has not been paired with yet, and proves it. */
    suspend fun prepare(
        device: CastDevice,
        askSecret: suspend (AirPlaySecret) -> String?,
    ): CastPlayResult =
        withContext(Dispatchers.IO) {
            val context = CastAndroidContext.context
                ?: return@withContext CastPlayResult.failed("AirPlay is not ready yet; open the app and try again")
            val client = client(context, device)
            val receiver = device.airPlayReceiver()
            try {
                val kept = credentialStore(context).get(receiver)
                when {
                    kept != null -> client.connect(receiver).use { }
                    // tvOS refuses transient pairing and says nothing about it, so the
                    // first cast to an Apple TV always starts with its PIN.
                    device.isAppleTv() -> {
                        client.pair(receiver, Secret.Pin) { askFor(askSecret, AirPlaySecret.PIN) }
                        client.connect(receiver).use { }
                    }
                    else -> {
                        try {
                            client.connect(receiver) { secret -> askFor(askSecret, secret.asPrompt()) }.use { }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (cancelled: SecretNotGiven) {
                            throw cancelled
                        } catch (error: Exception) {
                            // A receiver can demand pairing by putting a code on
                            // its screen and *not* answering the bare connect —
                            // measured 2026-11-06 on a Roku Streaming Stick 4K,
                            // which shows the code but stays silent, so the
                            // secret callback never fires and the viewer has
                            // nowhere to type it. When the failure looks like
                            // pairing rather than a network fault, ask for the
                            // code and pair explicitly, the way tvOS is handled.
                            if (!error.asksForPairing()) throw error
                            log.w { "airplay: ${device.name} wants pairing (${error.message}); asking for its code" }
                            client.pair(receiver, Secret.Pin) { askFor(askSecret, AirPlaySecret.PIN) }
                            client.connect(receiver).use { }
                        }
                    }
                }
                log.d { "airplay: ${device.name} ready" }
                CastPlayResult.Ok
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: SecretNotGiven) {
                CastPlayResult.failed("Pairing was cancelled")
            } catch (error: Exception) {
                log.w { "airplay: pairing ${device.name} failed: ${error.message}" }
                CastPlayResult.failed(failureReason(device, error))
            }
        }

    /** Hands [url] to [device] and starts it, replacing any session already open. */
    suspend fun play(
        device: CastDevice,
        url: String,
        title: String,
    ): CastPlayResult =
        withContext(Dispatchers.IO) {
            val context = CastAndroidContext.context
                ?: return@withContext CastPlayResult.failed("AirPlay is not ready yet; open the app and try again")
            log.d { "airplay: ${device.name} media=${url.substringBefore('?')} query=${url.contains("token=")}" }
            // AirPlay's sender gives the receiver ~10 s to take the item, and bsc
            // answers master.m3u8 only once the source has produced its first
            // segment (measured 15 s + a 30 s grace on a cold debrid resolve) —
            // so a cold cast used to be cancelled by airkast at 9.9-10.0 s
            // (measured 2026-11-09). Wait for the playlist HERE first, on this
            // app's own clock, so the sender's short one starts warm.
            when (val readiness = awaitMasterReady(url)) {
                MasterReadiness.Ready -> Unit
                is MasterReadiness.NotReady ->
                    return@withContext CastPlayResult.failed(readiness.reason)
            }
            synchronized(lock) {
                playing?.let { runCatching { it.close() } }
                playing = null
            }
            try {
                val session = client(context, device).connect(device.airPlayReceiver())
                synchronized(lock) { playing = session }
                session.load(Media(url))
                log.d { "airplay: ${device.name} accepted the stream" }
                CastPlayResult.Ok
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.w { "airplay: ${device.name} failed: ${error.message}" }
                CastPlayResult.failed(failureReason(device, error))
            }
        }

    /**
     * A client for [device]. Built per call because `ntpTiming` is not an option a
     * session can change: an Apple TV must announce PTP, a Mac needs NTP. Building
     * does no I/O, and every client shares the one credential store.
     */
    private fun client(context: android.content.Context, device: CastDevice): Airkast =
        Airkast {
            identity = SenderIdentity(
                name = "Boomio",
                deviceId = SenderIdentity.deviceIdFrom(context.packageName),
            )
            credentialStore = credentialStore(context)
            ntpTiming = !device.isAppleTv()
            logger = airkastLogger
        }

    private fun credentialStore(context: android.content.Context): CredentialStore =
        credentialStore ?: synchronized(lock) {
            credentialStore ?: CredentialStore
                .file { File(context.noBackupFilesDir, "airplay-pairings.properties") }
                .also { credentialStore = it }
        }

    /** Whether the source can be handed to the receiver now. */
    private sealed interface MasterReadiness {
        object Ready : MasterReadiness
        class NotReady(val reason: String) : MasterReadiness
    }

    /**
     * Waits until [url] answers as a playlist, or gives up after [PREFLIGHT_BUDGET_MS].
     *
     * bsc answers `master.m3u8` with a 503 until the source has produced its first
     * segment — 15 s on a cold cast, up to 45 s while a worker is still alive — and
     * then keeps the answer cached, so once this returns [MasterReadiness.Ready] the
     * receiver's fetch is instant. A 5xx means "not yet" and is retried; a 4xx never
     * becomes a 200 (an expired token, the media gate) and fails at once. A direct
     * file URL needs no wait and is left alone: a pre-flight would download it.
     */
    private suspend fun awaitMasterReady(url: String): MasterReadiness {
        if (!url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            return MasterReadiness.Ready
        }
        val deadline = System.currentTimeMillis() + PREFLIGHT_BUDGET_MS
        var attempt = 0
        while (true) {
            attempt++
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            var connection: HttpURLConnection? = null
            try {
                connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = minOf(remaining, CONNECT_TIMEOUT_MS).toInt()
                    readTimeout = minOf(remaining, READ_TIMEOUT_MS).toInt()
                    useCaches = false
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*")
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    log.d { "airplay: master ready after $attempt attempt(s): ${url.substringBefore('?')} HTTP $code" }
                    return MasterReadiness.Ready
                }
                if (code in 400..499) {
                    log.w { "airplay: master refused, not retrying: ${url.substringBefore('?')} HTTP $code" }
                    return MasterReadiness.NotReady(
                        "This link was refused (HTTP $code) — go back and pick the title again",
                    )
                }
                log.d { "airplay: master not ready yet (HTTP $code), attempt $attempt" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.d { "airplay: master not ready yet (${error.message}), attempt $attempt" }
            } finally {
                connection?.disconnect()
            }
            delay(PREFLIGHT_RETRY_MS)
        }
        log.w { "airplay: master never opened within ${PREFLIGHT_BUDGET_MS / 1000} s: ${url.substringBefore('?')}" }
        return MasterReadiness.NotReady(
            "This source is taking too long to open — try it again, or pick another result",
        )
    }

    private suspend fun askFor(
        ask: suspend (AirPlaySecret) -> String?,
        secret: AirPlaySecret,
    ): String = ask(secret) ?: throw SecretNotGiven()

    /** The field the picker shows for a secret the library asked for. */
    private fun Secret.asPrompt(): AirPlaySecret =
        if (this == Secret.Pin) AirPlaySecret.PIN else AirPlaySecret.PASSWORD

    private fun failureReason(device: CastDevice, error: Exception): String =
        when (error) {
            is AirkastException.SecretRejected -> "The code was refused — try again, the TV shows a new one"
            is AirkastException.PairingFailed -> "${device.name} refused the AirPlay pairing"
            is AirkastException.Rejected -> "${device.name} refused the stream (HTTP ${error.status})"
            is AirkastException.Timeout -> "No answer from ${device.name}; it may be asking to allow this phone"
            is AirkastException.Unreachable -> "Could not reach ${device.name} at ${device.ip}"
            is AirkastException.Disconnected -> "Lost the connection to ${device.name}"
            else -> "${device.name} could not play this: ${error.message ?: error::class.simpleName}"
        }

    /** The viewer closed the code prompt instead of typing one. */
    private class SecretNotGiven : Exception("no secret entered")

    /**
     * True when a failed connect is the receiver asking to be paired rather than
     * the network being wrong. A silent timeout counts: a receiver that is
     * showing a code waits for a pair-setup it never asked for, which looks
     * exactly like no answer at all.
     */
    private fun Exception.asksForPairing(): Boolean = when (this) {
        is AirkastException.PairingFailed,
        is AirkastException.SecretRejected,
        is AirkastException.Timeout,
        is AirkastException.Disconnected,
        -> true
        is AirkastException.Rejected -> status == 401 || status == 403 || status == 470
        else -> false
    }

    private val airkastLogger =
        object : Airkast.Logger {
            override val minLevel: Airkast.Logger.Level = Airkast.Logger.Level.Debug

            override fun log(
                level: Airkast.Logger.Level,
                tag: String,
                message: String,
                error: Throwable?,
            ) = log.d { "airplay $tag: $message" + (error?.let { " ($it)" } ?: "") }
        }
}

/** The receiver as airkast knows it: its address and what its announcement said. */
private fun CastDevice.airPlayReceiver(): Receiver =
    Receiver(
        name = name,
        host = ip,
        port = port.takeIf { it > 0 } ?: 7000,
        properties = raw,
    )

/**
 * An Apple TV 6,2 and later: refuses transient pairing and rejects NTP timing.
 * Its mDNS record names the model (`AppleTV6,2`); the capability table does not
 * have to place it for this to hold.
 */
private fun CastDevice.isAppleTv(): Boolean =
    // `model` is what an Apple TV puts in its announcement; `am` is the older
    // spelling some of them still send. Either is enough.
    listOfNotNull(raw["model"], raw["am"], model)
        .any { it.startsWith("AppleTV", ignoreCase = true) }

/** Bridges a common `prepareAirPlay` call to this receiver implementation. */
internal actual suspend fun prepareAirPlay(
    device: CastDevice,
    askSecret: suspend (AirPlaySecret) -> String?,
): CastPlayResult = AirPlay.prepare(device, askSecret)
