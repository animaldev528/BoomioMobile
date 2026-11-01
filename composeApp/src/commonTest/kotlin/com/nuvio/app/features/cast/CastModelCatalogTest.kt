package com.nuvio.app.features.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CastModelCatalogTest {

    // ── Roku ────────────────────────────────────────────────────────────────

    @Test
    fun `roku tv without 4k is the 1080p family`() {
        val row = CastModelCatalog.forRoku(
            mapOf("is-tv" to "true", "model-name" to "Roku TV", "ui-resolution" to "1080p"),
        )
        assertEquals(CastModelCatalog.Row("Roku TVs", "1080p Models (TCL, Hisense, etc)", "1080p · H.264 only"), row)
    }

    @Test
    fun `roku tv reports 4k in ui-resolution`() {
        val row = CastModelCatalog.forRoku(
            mapOf("is-tv" to "true", "model-name" to "TCL Roku TV", "ui-resolution" to "4k"),
        )
        // Pre-2021 on purpose: ECP does not report a model year, and the newer
        // row would advertise AV1 to a set that may not decode it.
        assertEquals(CastModelCatalog.Row("Roku TVs", "4K Models (Pre-2021)", "4K · HEVC · HDR10"), row)
    }

    @Test
    fun `roku stick and express are different rows`() {
        assertEquals(
            CastModelCatalog.Row("Roku", "Streaming Stick 4K / Ultra", "4K · HEVC · HDR10"),
            CastModelCatalog.forRoku(mapOf("model-name" to "Roku Streaming Stick 4K", "is-tv" to "false")),
        )
        assertEquals(
            CastModelCatalog.Row("Roku", "Express / Express+", "1080p · HEVC · H.264"),
            CastModelCatalog.forRoku(mapOf("model-name" to "Roku Express 3960X", "is-tv" to "false")),
        )
    }

    @Test
    fun `roku device-info keys are read case-insensitively`() {
        val row = CastModelCatalog.forRoku(
            mapOf("IS-TV" to "true", "Model-Name" to "Roku TV", "UI-Resolution" to "4k"),
        )
        assertEquals("Roku TVs", row?.ecosystem)
    }

    @Test
    fun `an unrecognisable roku gets no row`() {
        assertNull(CastModelCatalog.forRoku(mapOf("model-name" to "Roku", "is-tv" to "false")))
    }

    // ── Google Cast ─────────────────────────────────────────────────────────

    @Test
    fun `cast md names the dongle`() {
        assertEquals(
            CastModelCatalog.Row("Google", "Chromecast Ultra", "4K · HEVC · HDR10"),
            CastModelCatalog.forCast("Chromecast Ultra"),
        )
        assertEquals(
            CastModelCatalog.Row("Google", "Chromecast (1st/2nd/3rd Gen)", "1080p · H.264 · AAC"),
            CastModelCatalog.forCast("Chromecast"),
        )
    }

    @Test
    fun `a shield is placed by its md`() {
        assertEquals(
            "Shield TV Pro (2015/17/19)",
            CastModelCatalog.forCast("SHIELD Android TV")?.model,
        )
        assertEquals("Nvidia", CastModelCatalog.forCast("SHIELD Android TV")?.ecosystem)
    }

    @Test
    fun `google tv dongles split on the HD suffix`() {
        assertEquals("CCwGTV (4K)", CastModelCatalog.forCast("Chromecast with Google TV")?.model)
        assertEquals("CCwGTV (HD)", CastModelCatalog.forCast("Chromecast with Google TV (HD)")?.model)
    }

    @Test
    fun `a cast device that does not say what it is gets the floor`() {
        assertNull(CastModelCatalog.forCast("Google Nest Mini"))
        assertNull(CastModelCatalog.forCast(null))
        assertNull(CastModelCatalog.forCast(" "))
        // No video out: offering it as a cast target plays sound and no picture.
        assertNull(CastModelCatalog.forCast("Chromecast Audio"))
    }

    // ── AirPlay ─────────────────────────────────────────────────────────────

    @Test
    fun `airplay names the apple tv generation`() {
        assertEquals(
            CastModelCatalog.Row("Apple", "TV 4K (1st/2nd Gen)", "4K · HEVC · HDR10/DV"),
            CastModelCatalog.forAirplay(mapOf("model" to "AppleTV6,2")),
        )
        assertEquals("TV 4K (3rd Gen)", CastModelCatalog.forAirplay(mapOf("model" to "AppleTV14,1"))?.model)
        assertEquals("TV HD (4th Gen)", CastModelCatalog.forAirplay(mapOf("model" to "AppleTV5,3"))?.model)
        // `_raop` spells the same identifier `am`.
        assertEquals("TV 4K (1st/2nd Gen)", CastModelCatalog.forAirplay(mapOf("am" to "AppleTV11,1"))?.model)
    }

    @Test
    fun `an airplay receiver with no screen is not a target`() {
        assertNull(CastModelCatalog.forAirplay(mapOf("model" to "AudioAccessory5,1")))
        assertTrue(CastModelCatalog.isAudioOnlyAirplay(mapOf("model" to "AudioAccessory5,1")))
        assertFalse(CastModelCatalog.isAudioOnlyAirplay(mapOf("model" to "AppleTV6,2")))
        assertNull(CastModelCatalog.forAirplay(mapOf("model" to "Whatever1,1")))
    }

    // ── DLNA ────────────────────────────────────────────────────────────────

    @Test
    fun `dlna tvs stay unmapped without a year`() {
        assertNull(
            CastModelCatalog.forDlna(
                mapOf("manufacturer" to "LG Electronics", "modelName" to "LG webOS TV", "modelNumber" to "OLED55C1PUB"),
            ),
        )
    }

    @Test
    fun `amazon renderers take the conservative stick row`() {
        assertEquals(
            "Fire TV Stick (1st/2nd/3rd/Lite)",
            CastModelCatalog.forDlna(mapOf("manufacturer" to "Amazon", "modelname" to "AFTMM"))?.model,
        )
        assertEquals(
            "Amazon",
            CastModelCatalog.forDlna(mapOf("modelname" to "AFTKA"))?.ecosystem,
        )
        assertNull(CastModelCatalog.forDlna(mapOf("manufacturer" to "Sony")))
    }

    // ── Device and target ───────────────────────────────────────────────────

    @Test
    fun `a placed device carries the table strings`() {
        val device = CastModelCatalog.device(
            protocol = CastProtocol.ROKU,
            ip = "192.168.1.50",
            name = "Living Room",
            port = 8060,
            facts = mapOf("is-tv" to "true", "model-name" to "Roku TV", "ui-resolution" to "4k"),
        )
        assertTrue(device.hasCapabilityRow)
        assertEquals("Roku TVs", device.ecosystem)
        assertEquals("4K Models (Pre-2021)", device.model)
    }

    @Test
    fun `an unplaced device still produces a cast request`() {
        val device = CastModelCatalog.device(
            protocol = CastProtocol.DLNA,
            ip = "192.168.1.60",
            name = "Bedroom TV",
        )
        assertFalse(device.hasCapabilityRow)

        val target = CastTarget.of(device)

        // bsf only treats a request as a cast when an ecosystem or model is
        // present; an IP alone would bind the stream to the phone instead.
        assertEquals("DLNA", target.ecosystem)
        assertEquals("Bedroom TV", target.model)
        assertEquals("192.168.1.60", target.ip)
    }

    @Test
    fun `a placed device sends the capability row`() {
        val device = CastModelCatalog.device(
            protocol = CastProtocol.CAST,
            ip = "192.168.1.70",
            name = "Living Room",
            md = "Chromecast Ultra",
        )
        val target = CastTarget.of(device)
        assertEquals("Google", target.ecosystem)
        assertEquals("Chromecast Ultra", target.model)
    }

    @Test
    fun `an amazon receiver discovered over dial takes the amazon row`() {
        // A Fire TV answers DIAL and opens :8009, so it reaches us as a CAST
        // device — but its DIAL description never carries a Cast `md`. Without
        // this fallback the only device that says what it is in words we can
        // read was showing as "not identified".
        val device = CastModelCatalog.device(
            protocol = CastProtocol.CAST,
            ip = "192.168.1.51",
            name = "Kyle's FireTVStick",
            facts = mapOf("friendlyname" to "Kyle's FireTVStick", "manufacturer" to "Amazon", "modelname" to "AFTKA"),
        )
        assertEquals("Amazon", device.ecosystem)
        assertEquals("Fire TV Stick (1st/2nd/3rd/Lite)", device.model)
    }

    // ── Merging two records for one screen ──────────────────────────────────

    @Test
    fun `the record that can name the device wins over the one that cannot`() {
        // A television with Cast built in answers DIAL with Google's generic
        // "Eureka Dongle", while its `_googlecast._tcp` record says Chromecast.
        val dial = CastModelCatalog.device(
            protocol = CastProtocol.CAST,
            ip = "192.168.1.61",
            name = "Family room TV",
            md = "Eureka Dongle",
        )
        val mdns = CastModelCatalog.device(
            protocol = CastProtocol.CAST,
            ip = "192.168.1.61",
            name = "Family room TV",
            md = "Chromecast",
        )
        assertFalse(dial.hasCapabilityRow)
        assertTrue(mdns.hasCapabilityRow)

        val merged = listOf(dial, mdns).distinctByIdentified()
        assertEquals(1, merged.size)
        assertEquals("Chromecast (1st/2nd/3rd Gen)", merged.single().model)
    }

    @Test
    fun `merging keeps a record that is already identified`() {
        val first = CastDevice(
            protocol = CastProtocol.CAST,
            ip = "192.168.1.55",
            name = "SHIELD",
            ecosystem = "Nvidia",
            model = "Shield TV Pro (2015/17/19)",
        )
        val second = CastDevice(CastProtocol.CAST, "192.168.1.55", "SHIELD")
        assertEquals("Nvidia", listOf(first, second).distinctByIdentified().single().ecosystem)
        // Order does not matter: an unidentified record never displaces one that names a row.
        assertEquals("Nvidia", listOf(second, first).distinctByIdentified().single().ecosystem)
    }

    @Test
    fun `two different protocols on one address are two devices`() {
        // A Roku answers ECP and DLNA; both are real ways to reach it.
        val roku = CastDevice(CastProtocol.ROKU, "192.168.1.50", "Roku")
        val dlna = CastDevice(CastProtocol.DLNA, "192.168.1.50", "Roku")
        assertEquals(2, listOf(roku, dlna).distinctByIdentified().size)
    }

    @Test
    fun `device identity is stable across rediscovery`() {
        val first = CastModelCatalog.device(CastProtocol.ROKU, "192.168.1.50", "Roku")
        val second = CastModelCatalog.device(CastProtocol.ROKU, "192.168.1.50", "Roku (renamed)")
        assertEquals(first.id, second.id)
        assertEquals("roku:192.168.1.50", first.id)
    }

    // ── Query encoding ──────────────────────────────────────────────────────

    @Test
    fun `query values are percent-encoded for the wire`() {
        val target = CastTarget(
            ip = "192.168.1.50",
            ecosystem = "Roku TVs",
            model = "1080p Models (TCL, Hisense, etc)",
        )
        assertEquals(
            "targetEcosystem=Roku%20TVs" +
                "&targetModel=1080p%20Models%20%28TCL%2C%20Hisense%2C%20etc%29" +
                "&targetIp=192.168.1.50",
            target.queryString(),
        )
    }

    @Test
    fun `unreserved characters survive encoding untouched`() {
        assertEquals("A-z_0.9~", encode("A-z_0.9~"))
        assertEquals("%2F", encode("/"))
        assertEquals("%C3%A9", encode("é"))
    }
}
