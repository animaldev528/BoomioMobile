package com.nuvio.app.features.cast

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which address bsc is asked for decides whether the renderer ever sees audio.
 *
 * A Roku's own player has no in-band fMP4/CMAF audio path, and the master is
 * the only address at which bsc declares the audio as a rendition (with its
 * codec) — so a Roku is handed `master.m3u8` from either of its doors, while
 * every other renderer keeps the media playlist it already plays.
 */
class CastPlaybackUrlTest {

    private val stream =
        "https://boomiopi-media.duckdns.org/bsc/proxy/abc123/video.mkv?token=t0k"

    private fun device(
        protocol: CastProtocol,
        name: String,
        ecosystem: String? = null,
        model: String? = null,
    ) = CastDevice(
        protocol = protocol,
        ip = "192.168.68.64",
        name = name,
        ecosystem = ecosystem,
        model = model,
    )

    @Test
    fun a_roku_from_the_ecp_list_is_handed_the_master() {
        val ecp = device(CastProtocol.ROKU, "Roku Streaming Stick", "Roku", "Streaming Stick 4K / Ultra")

        assertEquals(
            "https://boomiopi-media.duckdns.org/bsc/proxy/abc123/hls/master.m3u8?token=t0k",
            castPlaybackUrl(ecp, stream),
        )
    }

    @Test
    fun the_same_roku_from_the_airplay_list_takes_the_same_address() {
        val airplay = device(CastProtocol.AIRPLAY, "Roku Streaming Stick", "Roku", "Streaming Stick 4K / Ultra")

        assertEquals(
            "https://boomiopi-media.duckdns.org/bsc/proxy/abc123/hls/master.m3u8?token=t0k",
            castPlaybackUrl(airplay, stream),
        )
    }

    @Test
    fun a_roku_whose_row_did_not_resolve_is_still_recognised_by_name() {
        val unresolved = device(CastProtocol.AIRPLAY, "Roku Streaming Stick")

        assertEquals(
            "https://boomiopi-media.duckdns.org/bsc/proxy/abc123/hls/master.m3u8?token=t0k",
            castPlaybackUrl(unresolved, stream),
        )
    }

    @Test
    fun an_apple_tv_keeps_the_media_playlist() {
        val appleTv = device(CastProtocol.AIRPLAY, "Living Room", "Apple", "TV 4K (1st/2nd Gen)")

        assertEquals(
            "https://boomiopi-media.duckdns.org/bsc/proxy/abc123/hls/index.m3u8?token=t0k",
            castPlaybackUrl(appleTv, stream),
        )
    }

    @Test
    fun a_cast_receiver_keeps_the_media_playlist() {
        val chromecast = device(CastProtocol.CAST, "Kitchen speaker")

        assertEquals(
            "https://boomiopi-media.duckdns.org/bsc/proxy/abc123/hls/index.m3u8?token=t0k",
            castPlaybackUrl(chromecast, stream),
        )
    }

    @Test
    fun a_url_that_already_plays_is_returned_untouched() {
        val alreadyHls = "https://example.com/show/episode.m3u8?token=t0k"
        val directFile = "https://example.com/movie.mp4?token=t0k"
        val roku = device(CastProtocol.ROKU, "Roku Streaming Stick", "Roku", "Streaming Stick 4K / Ultra")

        assertEquals(alreadyHls, castPlaybackUrl(roku, alreadyHls))
        assertEquals(directFile, castPlaybackUrl(roku, directFile))
    }
}
