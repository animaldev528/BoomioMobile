package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.internal.Poko

/**
 * A receiver as its `_airplay._tcp` service record describes it. [properties] is the TXT record;
 * discovery fills it, and a receiver typed in by hand can leave it empty, which reads as
 * [Compatibility.Unknown].
 */
@Poko
public class Receiver(
    public val name: String,
    public val host: String,
    public val port: Int = DEFAULT_PORT,
    public val properties: Map<String, String> = emptyMap(),
) {
    /** The feature bits from `features` (or `ft`): `0xLOW,0xHIGH`. */
    public val features: Long get() = parseFeatures(properties["features"] ?: properties["ft"])

    /**
     * Whether this library can play a [Media] URL on the receiver ([Airkast.connect]), and if not,
     * why. A speaker reads as [Compatibility.NoVideo]: see [audioCompatibility].
     */
    public val compatibility: Compatibility get() = compatibilityOf(features, statusFlags, properties)

    /**
     * Whether this library can stream audio the app decodes to the receiver ([Airkast.connectAudio]),
     * and if not, why: a speaker, or a TV or Mac that plays AirPlay audio.
     */
    public val audioCompatibility: Compatibility get() = audioCompatibilityOf(features, statusFlags, properties)

    /**
     * Whether airkast can play on it, so a picker lists it: [Compatibility.Supported],
     * [Compatibility.NeedsPin] or [Compatibility.NeedsPassword]. The last two play once paired:
     * [Airkast.connect] pairs when given a way to ask for the PIN or password, and fails with
     * [AirkastException.PairingFailed] without one. A receiver typed in by hand is
     * [Compatibility.Unknown], not supported.
     */
    public val isSupported: Boolean
        get() = compatibility == Compatibility.Supported || secretFor(compatibility) != null

    /**
     * Whether airkast can stream audio to it, so an audio picker lists it, in the terms of
     * [isSupported]: [audioCompatibility] is [Compatibility.Supported], [Compatibility.NeedsPin] or
     * [Compatibility.NeedsPassword].
     */
    public val isAudioSupported: Boolean
        get() = audioCompatibility.let { it == Compatibility.Supported || secretFor(it) != null }

    /** What the receiver says it asks for before it pairs, or null for nothing. */
    internal val secret: Secret?
        get() = secretFor(compatibility) ?: secretFor(audioCompatibility)

    private fun secretFor(compatibility: Compatibility): Secret? =
        when (compatibility) {
            Compatibility.NeedsPin -> Secret.Pin
            Compatibility.NeedsPassword -> Secret.Password
            else -> null
        }

    public val model: String? get() = properties["model"]
    public val deviceId: String? get() = properties["deviceid"]

    /**
     * What to keep the receiver under, as the built-in [CredentialStore]s and `ReceiverDiscovery`
     * do: [deviceId], or `host:port` without one. With a `deviceid` in the TXT record it stays the
     * same when the receiver is renamed or moves to another address; without one, as for a
     * receiver typed in by hand, it is the address. [Airkast.describe] gives that receiver its
     * `deviceid`, and with it the ID discovery gives it.
     */
    public val id: String get() = deviceId ?: "$host:$port"

    /** The receiver's AirPlay version, `srcvers`. Compatibility reports quote it. */
    public val sourceVersion: String? get() = properties["srcvers"]

    /**
     * Which parts of [NowPlaying] the receiver shows, from its metadata feature bits: the track's
     * name, artist and album (bit 17), artwork (15) and progress (16). A receiver with no
     * feature bits to read, such as one typed in by hand, gets all three.
     */
    internal val takesTrackInfo: Boolean get() = takes(METADATA_TEXT)
    internal val takesArtwork: Boolean get() = takes(METADATA_ARTWORK)
    internal val takesProgress: Boolean get() = takes(METADATA_PROGRESS)

    private fun takes(bit: Int) =
        (properties["features"] ?: properties["ft"]).isNullOrBlank() || features and (1L shl bit) != 0L

    private val statusFlags: Long get() = (properties["flags"] ?: properties["sf"])?.let(::parseHex) ?: 0L

    public companion object {
        public const val DEFAULT_PORT: Int = 7000
        public const val SERVICE_TYPE: String = "_airplay._tcp"

        // Feature bits and status flags as pyatv names them (AirPlayFlags, utils.py).
        private const val VIDEO_V1 = 0
        private const val AUDIO = 9
        private const val SYSTEM_PAIRING = 43
        private const val CORE_UTILS_PAIRING = 48
        private const val VIDEO_V2 = 49
        private const val PIN_REQUIRED = 0x8L
        private const val PASSWORD_REQUIRED = 0x80L

        // Which metadata a receiver shows, as owntone reads these bits (MetadataFeatures_0 to _2).
        private const val METADATA_ARTWORK = 15
        private const val METADATA_PROGRESS = 16
        private const val METADATA_TEXT = 17

        /** `act`, the access control type: pyatv reads 2 as a Mac's "Current User". */
        private const val ACCESS_CURRENT_USER = "2"

        internal fun compatibilityOf(
            features: Long,
            flags: Long,
            properties: Map<String, String>,
        ): Compatibility {
            fun has(bit: Int) = features and (1L shl bit) != 0L
            return when {
                properties.isEmpty() -> Compatibility.Unknown
                !has(VIDEO_V2) -> if (has(VIDEO_V1)) Compatibility.VideoV1Only else Compatibility.NoVideo
                else -> pairingOf(features, flags, properties)
            }
        }

        internal fun audioCompatibilityOf(
            features: Long,
            flags: Long,
            properties: Map<String, String>,
        ): Compatibility =
            when {
                properties.isEmpty() -> Compatibility.Unknown
                features and (1L shl AUDIO) == 0L -> Compatibility.NoAudio
                else -> pairingOf(features, flags, properties)
            }

        /** What stands between the sender and a session, once the receiver plays what it is sent. */
        private fun pairingOf(
            features: Long,
            flags: Long,
            properties: Map<String, String>,
        ): Compatibility {
            fun has(bit: Int) = features and (1L shl bit) != 0L
            return when {
                properties["act"] == ACCESS_CURRENT_USER -> {
                    Compatibility.AccessRestricted
                }

                properties["pw"].equals("true", ignoreCase = true) || flags and PASSWORD_REQUIRED != 0L -> {
                    Compatibility.NeedsPassword
                }

                // pyatv also reads 0x200 as "pairing mandatory", but the LG CX sets it (flags 0x244)
                // and pairs transiently without a PIN.
                flags and PIN_REQUIRED != 0L -> {
                    Compatibility.NeedsPin
                }

                !has(SYSTEM_PAIRING) && !has(CORE_UTILS_PAIRING) -> {
                    Compatibility.NoTransientPairing
                }

                else -> {
                    Compatibility.Supported
                }
            }
        }

        /**
         * The TXT record properties a `GET /info` answer holds: the record itself where the
         * receiver includes it (`txtAirPlay`), as the LG CX, the Samsung HW-Q990B and a Mac do
         * when asked for it, and otherwise its keys under their TXT names, which leave out `pw`
         * and `act`.
         */
        internal fun propertiesFromInfo(info: Map<*, *>): Map<String, String> {
            (info["txtAirPlay"] as? ByteArray)?.let(::parseTxt)?.takeIf { it.isNotEmpty() }?.let { return it }
            return buildMap {
                (info["features"] as? Long)?.let { put("features", "0x%X,0x%X".format(it and 0xffffffffL, it ushr 32)) }
                (info["statusFlags"] as? Long)?.let { put("flags", "0x%X".format(it)) }
                (info["deviceID"] as? String)?.let { put("deviceid", it) }
                (info["model"] as? String)?.let { put("model", it) }
                (info["sourceVersion"] as? String)?.let { put("srcvers", it) }
                (info["manufacturer"] as? String)?.let { put("manufacturer", it) }
            }
        }

        /** A TXT record's strings, each its length then `key=value`. */
        private fun parseTxt(record: ByteArray): Map<String, String> {
            val properties = mutableMapOf<String, String>()
            var i = 0
            while (i < record.size) {
                val length = record[i].toInt() and 0xff
                if (i + 1 + length > record.size) break
                val entry = String(record, i + 1, length, Charsets.UTF_8)
                i += 1 + length
                val key = entry.substringBefore('=')
                if (key.isNotEmpty()) properties[key] = entry.substringAfter('=', "")
            }
            return properties
        }

        internal fun parseFeatures(value: String?): Long {
            if (value.isNullOrBlank()) return 0
            val parts = value.split(',')
            val low = parseHex(parts[0])
            val high = parts.getOrNull(1)?.let(::parseHex) ?: 0
            return (high shl 32) or (low and 0xffffffffL)
        }

        private fun parseHex(value: String): Long =
            value
                .trim()
                .removePrefix("0x")
                .removePrefix("0X")
                .toLongOrNull(16) ?: 0
    }
}

/**
 * Whether airkast can play on a receiver, read from its service record. A picker lists the
 * receivers whose [Receiver.isSupported] is true, and [Unknown] ones the user typed in.
 * docs/compatibility.md has the receivers behind each case. New cases may join in a minor
 * release, as support grows.
 */
public enum class Compatibility {
    /** Plays what airkast sends, a URL or a stream of audio, and pairs without a code. */
    Supported,

    /** No TXT record to read, as for a receiver typed in by hand. Connecting is the only test. */
    Unknown,

    /**
     * Asks for a PIN shown on its screen, once: [Airkast.connect] pairs it, given a way to ask
     * the user, and keeps the [Credentials] for every connect after.
     */
    NeedsPin,

    /**
     * Asks for a password set in its AirPlay settings, once: [Airkast.connect] pairs with it,
     * given a way to ask the user, and keeps the [Credentials] for every connect after.
     */
    NeedsPassword,

    /**
     * Lets in only the devices of its owner's Apple Account, which no third-party sender can be:
     * a Mac's AirPlay Receiver set to "Current User", its default. Setting it to "Anyone on the
     * same network" or "Everyone" changes that.
     */
    AccessRestricted,

    /** Plays what airkast sends, but advertises no transient pairing, the only kind this version starts with. */
    NoTransientPairing,

    /** Takes URLs only over AirPlay video v1 (`POST /play`), which this version does not speak. */
    VideoV1Only,

    /**
     * Plays no video from a URL: a speaker, or a receiver that only mirrors a screen. A speaker
     * takes audio the app decodes and streams instead ([Receiver.audioCompatibility]).
     */
    NoVideo,

    /** Plays no AirPlay audio stream: [Receiver.audioCompatibility] of a receiver that only shows video or a screen. */
    NoAudio,
}
