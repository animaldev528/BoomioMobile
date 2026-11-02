package io.github.aivanyuk.airkast

/**
 * Why talking to a receiver failed. Every failure the public API reports is one of these. New
 * subclasses may join in a minor release, so a `when` over them keeps an `else` branch.
 */
public sealed class AirkastException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The receiver refused the pairing, or answered it with something unexpected. A receiver that
     * asks for a password ends a connect here when no prompt was given to ask for it.
     */
    public class PairingFailed internal constructor(
        message: String,
        /** The receiver refused the credentials themselves, so only pairing again gets in. */
        internal val credentialsRefused: Boolean,
        /** The receiver answered SETUP with an HTTP Digest challenge, which only a password answers. */
        internal val passwordAsked: Boolean = false,
    ) : AirkastException(message) {
        public constructor(message: String) : this(message, credentialsRefused = false)
    }

    /**
     * The receiver did not take the [secret] the user gave: a wrong PIN or password. Pairing again
     * starts over.
     */
    public class SecretRejected(
        public val secret: Secret,
    ) : AirkastException(
            when (secret) {
                Secret.Pin -> "The receiver did not take the PIN"
                Secret.Password -> "The receiver did not take the password"
            },
        )

    /** The receiver answered a request with an error status. */
    public class Rejected(
        public val request: String,
        public val status: Int,
    ) : AirkastException("$request answered $status")

    /**
     * The receiver did not answer in time. The session stays open: the late answer is dropped when
     * it comes, and the session ends with [Disconnected] only if the receiver is still silent at
     * the next request. A load the receiver does not take in time ends here too, and so does a
     * connect whose SETUP the receiver holds past [Airkast.approvalTimeout], as a Mac does while
     * it asks its user whether to allow the sender.
     */
    public class Timeout(
        message: String,
    ) : AirkastException(message)

    /**
     * No connection to the receiver could be opened: it is off or asleep, on another network, or a
     * firewall or VPN is in the way.
     */
    public class Unreachable(
        cause: Throwable,
    ) : AirkastException("The receiver could not be reached", cause)

    /** The receiver answered with something this library cannot read. */
    public class UnexpectedReply(
        cause: Throwable,
    ) : AirkastException("The receiver's answer could not be read", cause)

    /**
     * The connection to the receiver is gone, for [reason]: the receiver closed it, as when another
     * sender takes over, or the network failed, or the app closed the session.
     */
    public class Disconnected(
        cause: Throwable?,
        public val reason: DisconnectReason,
    ) : AirkastException("The receiver connection closed ($reason)", cause)

    /**
     * A manifest could not be read from the phone. For an HLS master, [AirkastSession.renditions]
     * then has nothing to list: its server answered with another status than 200, or not in time,
     * or sent more than a master holds, or something that is not a playlist. The receiver fetches
     * the URL itself, so it may still play one the phone cannot read.
     */
    public class ManifestUnreadable(
        message: String,
        /** The status the server answered with, when it answered with another than 200. */
        public val status: Int? = null,
        cause: Throwable? = null,
    ) : AirkastException(message, cause)

    /**
     * [AirkastSession.load] cannot give the item to the receiver, so it sent nothing, and what it
     * played before plays on: the item is in a [format] no receiver loads, such as
     * [MediaFormat.Dash], or at a URL the receiver cannot fetch, such as a `content:` URI, and no
     * resolver in [Airkast.resolvers] serves it; or a resolver handles it but cannot serve it, as
     * [LocalFileResolver] for a file it cannot read, or fails with another exception, which is the
     * [cause]. [message] says which.
     */
    public class UnsupportedMedia(
        public val format: MediaFormat,
        message: String,
        cause: Throwable? = null,
    ) : AirkastException(message, cause)

    /**
     * The platform does not let this app reach the local network. On Android 17, an app that
     * targets SDK 37 needs `ACCESS_LOCAL_NETWORK` granted first (docs/compatibility.md).
     */
    public class NotPermitted(
        message: String,
    ) : AirkastException(message)

    /**
     * Scanning for receivers could not start. [code] is the platform's error, such as one of
     * `NsdManager`'s `FAILURE_*` codes on Android.
     */
    public class DiscoveryFailed(
        public val code: Int,
    ) : AirkastException("Discovery failed to start: $code")
}
