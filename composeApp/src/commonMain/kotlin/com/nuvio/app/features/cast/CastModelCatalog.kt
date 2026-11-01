package com.nuvio.app.features.cast

/**
 * Maps what a renderer says about itself onto the `(ecosystem, model)` strings in
 * bsc's `device_capabilities` table (`bsc/db.js`, seeded 2026-10 and editable from
 * the dashboard). Those strings are the contract: bsf posts nothing but these two
 * values to `GET /api/bsm/device-caps`, and a miss is not an error — the server
 * answers with a conservative floor (1080p / H.264 / AAC / no HDR).
 *
 * So this file deliberately errs towards returning `null`. A wrong row is worse
 * than no row: "no row" degrades to something that always plays, while a row that
 * claims more than the panel can do produces a stream the TV accepts and then
 * fails to decode — on the couch, mid-sentence, with no error to show.
 */
object CastModelCatalog {

    /**
     * A row of `device_capabilities`, plus the ceiling to show in the picker.
     *
     * [ceiling] is DISPLAY ONLY. The gates are applied in bsf from the stored
     * row; nothing in the client is allowed to decide what plays. It exists
     * because "CAST · Chromecast (1st/2nd/3rd Gen)" tells a person nothing about
     * what that screen will actually get, and the answer — 1080p, H.264, AAC —
     * is the whole reason the picker exists.
     *
     * ⚠️ When a row's caps change in `bsc/db.js`, change the ceiling with it.
     * A stale label misdescribes the stream; it cannot mis-serve it.
     */
    data class Row(val ecosystem: String, val model: String, val ceiling: String = "")

    // ── Roku ────────────────────────────────────────────────────────────────
    // Facts come from ECP `GET http://<ip>:8060/query/device-info`, which is rich
    // enough to place a device: `is-tv`, `is-stick`, `model-name` and
    // `ui-resolution` ("1080p" / "4k"). Keys are read case-insensitively.
    //
    // ⚠️ The 4K TV split is by YEAR (Pre-2021 vs 2021+) and ECP does not report
    // one. We take Pre-2021 — the row without AV1 — because the gates only ever
    // remove what a row allows: the older row costs a viewer AV1 titles on a
    // modern TV, while the newer row would hand AV1 to a 2020 set that cannot
    // decode it.
    fun forRoku(info: Map<String, String>): Row? {
        val fields = info.lowercaseKeys()
        val name = fields["model-name"].orEmpty()
        val isTv = fields["is-tv"].equals("true", ignoreCase = true)
        val resolution = fields["ui-resolution"].orEmpty()

        if (isTv) {
            return if (resolution.contains4k()) {
                Row("Roku TVs", "4K Models (Pre-2021)", "4K · HEVC · HDR10")
            } else {
                Row("Roku TVs", "1080p Models (TCL, Hisense, etc)", "1080p · H.264 only")
            }
        }

        return when {
            name.contains("express", ignoreCase = true) ->
                Row("Roku", "Express / Express+", "1080p · HEVC · H.264")

            name.contains("ultra", ignoreCase = true) ||
                name.contains("4k", ignoreCase = true) ||
                name.contains("stick", ignoreCase = true) ->
                Row("Roku", "Streaming Stick 4K / Ultra", "4K · HEVC · HDR10")

            else -> null
        }
    }

    // ── Google Cast ─────────────────────────────────────────────────────────
    // `md` is the model string in the `_googlecast._tcp` TXT record. Only the
    // versions that say what they are get a row; "Chromecast with Google TV"
    // and friends report the same `md` as older dongles, so they fall through to
    // the floor rather than borrowing a profile they may not match.
    fun forCast(md: String?): Row? {
        val value = md?.trim().orEmpty()
        if (value.isEmpty()) return null
        // Order matters: "Chromecast with Google TV" is not the 1080p dongle,
        // and "Chromecast Audio" is not a screen at all.
        return when {
            // No video out. Casting a film to it plays sound and nothing else.
            value.contains("audio", ignoreCase = true) &&
                !value.contains("video", ignoreCase = true) -> null

            // ⚠️ `md` says "SHIELD Android TV" for the 2015, 2017 and 2019
            // boxes alike, so the generation — and with it Dolby Vision, which
            // only the 2019 has — cannot be read off discovery. The Pro row is
            // the one that carries that caveat in its HDR column.
            value.contains("shield", ignoreCase = true) ->
                Row("Nvidia", "Shield TV Pro (2015/17/19)", "4K · HEVC · HDR10")

            value.contains("ultra", ignoreCase = true) ->
                Row("Google", "Chromecast Ultra", "4K · HEVC · HDR10")

            value.contains("streamer", ignoreCase = true) ->
                Row("Google", "TV Streamer", "4K · AV1/HEVC · HDR10+")

            value.contains("google tv", ignoreCase = true) ->
                if (value.contains("hd", ignoreCase = true)) {
                    Row("Google", "CCwGTV (HD)", "1080p · AV1/HEVC · HDR10")
                } else {
                    Row("Google", "CCwGTV (4K)", "4K · HEVC · HDR10+")
                }

            // "Chromecast" alone is the 1st–3rd gen dongle: 1080p, no HDR.
            value.equals("chromecast", ignoreCase = true) ||
                value.startsWith("Chromecast (", ignoreCase = true) ->
                Row("Google", "Chromecast (1st/2nd/3rd Gen)", "1080p · H.264 · AAC")

            else -> null
        }
    }

    // ── AirPlay ─────────────────────────────────────────────────────────────
    // `_airplay._tcp` carries `model=AppleTV6,2` — the identifier Apple also
    // publishes as `am` on `_raop` and `rpMd` on `_companion-link`. It names the
    // generation exactly, which is the only thing an Apple TV's ceiling turns
    // on, so this is the one protocol where the answer is never a guess.
    fun forAirplay(info: Map<String, String>): Row? {
        val fields = info.lowercaseKeys()
        val identifier = (fields["model"] ?: fields["am"]).orEmpty().trim().uppercase()
        return when (identifier) {
            "APPLETV5,3" -> Row("Apple", "TV HD (4th Gen)", "1080p · H.264")
            // 6,2 is the 2017 4K; 11,1 the 2021. Same row in the table.
            "APPLETV6,2", "APPLETV11,1" ->
                Row("Apple", "TV 4K (1st/2nd Gen)", "4K · HEVC · HDR10/DV")

            "APPLETV14,1" -> Row("Apple", "TV 4K (3rd Gen)", "4K · HEVC · HDR10+")

            // HomePods, Macs and iPhones answer AirPlay as well. They are not a
            // screen, and the floor would describe a device that cannot show
            // video at all — so they get no row and the picker leaves them out.
            else -> null
        }
    }

    /**
     * An AirPlay receiver that has no screen. A HomePod answers `_airplay._tcp`
     * exactly like an Apple TV, so without this it appears in the picker as a
     * cast target that will accept the film and play its sound.
     */
    fun isAudioOnlyAirplay(info: Map<String, String>): Boolean {
        val fields = info.lowercaseKeys()
        val identifier = (fields["model"] ?: fields["am"]).orEmpty().trim().uppercase()
        return identifier.startsWith("AUDIOACCESSORY")
    }

    // ── DLNA / UPnP ─────────────────────────────────────────────────────────
    // A DLNA description carries `manufacturer`, `modelName` and `modelNumber`,
    // and the table's TV rows are split by YEAR (Samsung 2015-20 / 2021+, LG
    // 2015-19 / 2020-22 / 2023+). A model number yields a year only through
    // vendor-specific patterns, and guessing one hands the viewer a stream the
    // panel cannot decode. So DLNA devices get NO row unless the manufacturer
    // alone settles it. Add families here one at a time, with the pattern that
    // earns them.
    fun forDlna(info: Map<String, String>): Row? {
        val fields = info.lowercaseKeys()
        val manufacturer = fields["manufacturer"].orEmpty()
        val modelName = fields["modelname"].orEmpty()
        // Amazon Fire TV answers SSDP as a MediaRenderer and its description
        // names the family only through an `AFT…` code. A Lite stick and a 4K
        // Max are different rows and the code does not say which, so take the
        // 1080p stick row: under-claiming still plays, over-claiming decodes
        // badly on the couch with nothing to show for it.
        if (manufacturer.contains("amazon", ignoreCase = true) ||
            modelName.startsWith("AFT", ignoreCase = true)
        ) {
            return Row("Amazon", "Fire TV Stick (1st/2nd/3rd/Lite)", "1080p · HEVC · HDR10")
        }
        return null
    }

    /** Builds a [CastDevice] from whatever a protocol reported. */
    fun device(
        protocol: CastProtocol,
        ip: String,
        name: String,
        port: Int = 0,
        facts: Map<String, String> = emptyMap(),
        md: String? = null,
    ): CastDevice {
        val row = when (protocol) {
            CastProtocol.ROKU -> forRoku(facts)
            // A DIAL description is the only record some Cast receivers answer
            // with, and it names the hardware rather than the Cast product: an
            // Amazon Fire TV answers DIAL (and opens :8009, so it is a valid
            // target) while its `md`, which is what the Cast table keys on, is
            // not there at all. The Amazon rows are matched on the DIAL facts.
            CastProtocol.CAST -> forCast(md) ?: forDlna(facts)
            CastProtocol.DLNA -> forDlna(facts)
            // `_airplay._tcp` reports the model in the same record, and the
            // discovery layer passes it as `md`; read the facts so `_raop`'s
            // `am` works too.
            CastProtocol.AIRPLAY -> forAirplay(facts) ?: forCast(md)
        }
        return CastDevice(
            protocol = protocol,
            ip = ip,
            name = name,
            port = port,
            ecosystem = row?.ecosystem,
            model = row?.model,
            ceiling = row?.ceiling,
            raw = facts,
        )
    }
}

private fun Map<String, String>.lowercaseKeys(): Map<String, String> =
    entries.associate { (key, value) -> key.lowercase() to value }

private fun String.contains4k(): Boolean =
    contains("4k", ignoreCase = true) || contains("2160")
