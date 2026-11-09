package com.nuvio.app.features.cast

/**
 * What a target can actually be asked to do from this phone.
 *
 * The companion screen used to draw one fixed remote for whatever it was pointed
 * at. That is wrong twice over: a Roku has no "seek to position" (ECP only sends
 * keypresses, so the channel decides how far one press jumps — a scrubber there
 * moves a thumb and nothing else), and a renderer playing a URL has no audio to
 * fork to this phone. A control that cannot work is worse than a missing one: it
 * invites a press and answers with silence.
 *
 * So every control on the remote surface asks this first. A flag is true only
 * when the protocol has a working implementation behind it — never "it is a TV,
 * TVs have volume".
 */
data class RemoteCapabilities(
    /** Play/pause transport. */
    val transport: Boolean = false,
    /** Seek to an absolute position. */
    val seek: Boolean = false,
    /** End playback / leave the player. */
    val stop: Boolean = false,
    /** Set an absolute volume level. */
    val volume: Boolean = false,
    /** D-pad / OK / Back navigation of the TV's own UI. */
    val dpad: Boolean = false,
    /** Type into the TV's search field. */
    val search: Boolean = false,
    /** Switch audio / subtitle tracks. */
    val tracks: Boolean = false,
    /** Fork the TV's audio to this phone. */
    val privateListening: Boolean = false,
    /** Ask the server to name the song at the current position. */
    val musicIdentify: Boolean = false,
) {
    companion object {
        /**
         * An enrolled Boomio TV: the companion socket implements every one of
         * these, and the TV's manager answers them on its own UI.
         */
        val BoomioTv = RemoteCapabilities(
            transport = true,
            seek = true,
            stop = true,
            volume = true,
            dpad = true,
            search = true,
            tracks = false,
            privateListening = true,
            musicIdentify = true,
        )

        /**
         * Roku ECP.
         *
         * ⛔ **No seek.** ECP has `Rev`/`Fwd` keypresses but no "seek to
         * position"; the playing channel decides how far one press jumps. The
         * old surface drew a disabled slider here, which read as "broken". This
         * flag is what removes it.
         *
         * No volume either: ECP exposes `VolumeUp`/`VolumeDown` keypresses, but
         * the remote speaks in absolute percentages, and a slider that silently
         * became two keypresses would not land where the thumb was released.
         */
        val RokuEcp = RemoteCapabilities(
            transport = true,
            seek = false,
            stop = true,
            volume = false,
            dpad = false,
            search = false,
            tracks = false,
            privateListening = false,
            musicIdentify = true,
        )

        /**
         * AirPlay 2 through airkast: the receiver reports seekable ranges and
         * renditions, so both are real controls. It holds its own audio, so
         * private listening does not apply.
         */
        val AirPlay = RemoteCapabilities(
            transport = true,
            seek = true,
            stop = true,
            volume = true,
            dpad = false,
            search = false,
            tracks = true,
            privateListening = false,
            musicIdentify = true,
        )

        /**
         * A DLNA renderer. Playback is `AVTransport`, which offers
         * SetAVTransportURI/Play but not Pause/Seek in this client today — so
         * only the actions with an implementation are advertised.
         */
        val Dlna = RemoteCapabilities(
            transport = false,
            seek = false,
            stop = false,
            volume = false,
            dpad = false,
            search = false,
            tracks = false,
        )

        /**
         * Google Cast. The Default Media Receiver is driven over CASTV2, which
         * this client does not open a channel to yet.
         */
        val GoogleCast = RemoteCapabilities()

        fun of(protocol: CastProtocol): RemoteCapabilities = when (protocol) {
            CastProtocol.BOOMIO -> BoomioTv
            CastProtocol.ROKU -> RokuEcp
            CastProtocol.AIRPLAY -> AirPlay
            CastProtocol.DLNA -> Dlna
            CastProtocol.CAST -> GoogleCast
        }
    }
}
