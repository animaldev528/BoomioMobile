package com.nuvio.app.features.cast

import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals

class CastAudioFitTest {

    private fun stream(name: String, filename: String? = null) = StreamItem(
        name = name,
        title = name,
        description = name,
        url = "https://example.invalid/$name",
        addonName = "Boomio",
        addonId = "com.boomio",
        behaviorHints = StreamBehaviorHints(filename = filename),
    )

    @Test
    fun `nothing is withheld until a renderer is picked`() {
        val all = listOf(stream("Dune.2021.1080p.BluRay.DTS-HD.MA.5.1.x264-GRP"))
        assertEquals(all, CastAudioFit.keep(all, casting = false))
    }

    @Test
    fun `lossless and DTS taps are withheld from a renderer`() {
        val lossless = listOf(
            stream("Dune.2021.1080p.BluRay.DTS-HD.MA.5.1.x264-GRP"),
            stream("Dune.2021.1080p.BluRay.DTS.X.7.1.x264-GRP"),
            stream("Dune.2021.1080p.BluRay.TrueHD.7.1.Atmos.x264-GRP"),
            stream("Dune.2021.1080p.BluRay.DTS.5.1.x264-GRP"),
            stream("Dune.2021.1080p.BluRay.FLAC.2.0.x264-GRP"),
        )
        val kept = CastAudioFit.keep(lossless + stream("Dune.2021.1080p.BluRay.x264-GRP"), casting = true)
        assertEquals(listOf("Dune.2021.1080p.BluRay.x264-GRP"), kept.map { it.name })
    }

    @Test
    fun `common cast-decodable audio is kept`() {
        val fine = listOf(
            stream("Dune.2021.1080p.WEB-DL.DDP5.1.H.264-GRP"),
            stream("Dune.2021.1080p.BluRay.AC3.5.1.x264-GRP"),
            stream("Dune.2021.1080p.BluRay.EAC3.5.1.x264-GRP"),
            stream("Dune.2021.1080p.BluRay.AAC.2.0.x264-GRP"),
            stream("Dune.2021.1080p.BluRay.x264-GRP"),
        )
        assertEquals(fine, CastAudioFit.keep(fine, casting = true))
    }

    @Test
    fun `a codec that lives only in the filename is still read`() {
        val onlyFilename = stream("Dune 2021 1080p", filename = "Dune.2021.1080p.BluRay.DTS-HD.MA.5.1.x264-GRP.mkv")
        val kept = CastAudioFit.keep(listOf(onlyFilename, stream("Dune 2021 1080p WEB")), casting = true)
        assertEquals(listOf("Dune 2021 1080p WEB"), kept.map { it.name })
    }

    @Test
    fun `a title whose only release is lossless still shows it`() {
        val only = listOf(stream("Dune.2021.1080p.BluRay.DTS-HD.MA.5.1.x264-GRP"))
        assertEquals(only, CastAudioFit.keep(only, casting = true))
    }
}
