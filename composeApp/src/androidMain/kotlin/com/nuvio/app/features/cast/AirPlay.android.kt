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
import kotlinx.coroutines.withContext
import java.io.File

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
                    else -> client.connect(receiver) { secret -> askFor(askSecret, secret.asPrompt()) }.use { }
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
