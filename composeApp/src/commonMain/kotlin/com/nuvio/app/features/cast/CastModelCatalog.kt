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

    data class Row(val ecosystem: String, val model: String)

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
                Row("Roku TVs", "4K Models (Pre-2021)")
            } else {
                Row("Roku TVs", "1080p Models (TCL, Hisense, etc)")
            }
        }

        return when {
            name.contains("express", ignoreCase = true) ->
                Row("Roku", "Express / Express+")

            name.contains("ultra", ignoreCase = true) ||
                name.contains("4k", ignoreCase = true) ||
                name.contains("stick", ignoreCase = true) ->
                Row("Roku", "Streaming Stick 4K / Ultra")

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
        return when {
            value.contains("ultra", ignoreCase = true) -> Row("Google", "Chromecast Ultra")
            value.contains("streamer", ignoreCase = true) -> Row("Google", "TV Streamer")
            // "Chromecast" alone is the 1st–3rd gen dongle: 1080p, no HDR.
            value.equals("chromecast", ignoreCase = true) ||
                value.startsWith("Chromecast (", ignoreCase = true) ->
                Row("Google", "Chromecast (1st/2nd/3rd Gen)")

            else -> null
        }
    }

    // ── DLNA / UPnP ─────────────────────────────────────────────────────────
    // A DLNA description carries `manufacturer`, `modelName` and `modelNumber`,
    // and the table's TV rows are split by YEAR (Samsung 2015-20 / 2021+, LG
    // 2015-19 / 2020-22 / 2023+). A model number yields a year only through
    // vendor-specific patterns, and guessing one hands the viewer a stream the
    // panel cannot decode. So DLNA devices deliberately get NO row in v1 and
    // play at the server's floor. Add families here one at a time, with the
    // pattern that earns them.
    fun forDlna(@Suppress("UNUSED_PARAMETER") info: Map<String, String>): Row? = null

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
            CastProtocol.CAST -> forCast(md)
            CastProtocol.DLNA -> forDlna(facts)
            CastProtocol.AIRPLAY -> null
        }
        return CastDevice(
            protocol = protocol,
            ip = ip,
            name = name,
            port = port,
            ecosystem = row?.ecosystem,
            model = row?.model,
            raw = facts,
        )
    }
}

private fun Map<String, String>.lowercaseKeys(): Map<String, String> =
    entries.associate { (key, value) -> key.lowercase() to value }

private fun String.contains4k(): Boolean =
    contains("4k", ignoreCase = true) || contains("2160")
