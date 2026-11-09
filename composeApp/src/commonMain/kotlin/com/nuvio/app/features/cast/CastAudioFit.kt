package com.nuvio.app.features.cast

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem

/**
 * Keeps releases whose declared audio a cast target can actually be handed.
 *
 * ⛔ The server already refuses these — `bsf/lib/cast-target.js`
 * (`PIPELINE_DELIVERABLE.audio_codecs`) intersects the renderer's row with what
 * the cast pipeline can carry, and `lib/score.js` drops a release whose NAME
 * declares lossless audio when the target cannot take it. Measured 2026-11-09:
 * the live Roku-gated list (`/stream/…?targetEcosystem=Roku&…`) returned 52
 * releases and **zero** naming DTS or lossless audio.
 *
 * But that gate only runs on lists BSF builds. Two lists reach the screen
 * without it: one fetched before a renderer was picked (`CastSession.current()`
 * is null then, so no target rides the request) and one from a THIRD-PARTY addon
 * (BSF never sees it and the phone deliberately sends it no target). Either way
 * the viewer can pick a DTS-HD release for a Roku, and the cast then dies:
 * bsc has to decode lossless DTS and re-encode it to AAC, which breaks the
 * timestamps it muxes (`Queue input is backward in time`, `Non-monotonous DTS
 * in output stream 0:1`) and the receiver stops after ~40 s.
 *
 * So the same rule is applied here, to EVERY group, and only while a cast is
 * selected. It mirrors the server's list rather than inventing one: `dts`,
 * `dts-hd`, `dts-x`, `truehd`, `atmos` and `flac` are what the pipeline cannot
 * hand over intact. Plain `aac`/`ac3`/`eac3`/`mp3` all pass.
 *
 * ⚠️ A group that would be emptied is returned untouched: a release the target
 * has to work hard for still beats "no stream found" on a title with one source.
 */
internal object CastAudioFit {

    /** Lossless families: always re-encoded to AAC by the cast pipeline. */
    private val LOSSLESS = Regex(
        """\b(true[- ]?hd|dts[- ]?hd|dts[- ]?x|dtsma|dtshd|dtsha|dolby[\s.-]*atmos|atmos)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Core DTS. The receiver may decode it, but bsc still has to re-encode it. */
    private val DTS = Regex("""\bdts\b""", RegexOption.IGNORE_CASE)

    /** FLAC is not in the pipeline's deliverable set either. */
    private val FLAC = Regex("""\bflac\b""", RegexOption.IGNORE_CASE)

    /**
     * Filter one addon's group for the currently selected renderer.
     *
     * `casting` is a parameter rather than a `CastSession` read so the rule can
     * be tested without installing a device; callers pass the default.
     */
    fun group(group: AddonStreamGroup, casting: Boolean = CastSession.current() != null): AddonStreamGroup {
        if (!casting || group.streams.isEmpty()) return group
        val kept = keep(group.streams, casting = true)
        return if (kept.size == group.streams.size) group else group.copy(streams = kept)
    }

    /** The streams that survive the rule; the original list when it would empty. */
    internal fun keep(streams: List<StreamItem>, casting: Boolean): List<StreamItem> {
        if (!casting || streams.isEmpty()) return streams
        val kept = streams.filter(::playable)
        return kept.ifEmpty { streams }
    }

    /**
     * ⚠️ Reads the same three display fields the server's gate reads, plus the
     * release filename — some addons put the codec only in `behaviorHints`.
     */
    private fun playable(stream: StreamItem): Boolean {
        val text = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints.filename,
        ).joinToString(" ")
        if (text.isBlank()) return true
        if (LOSSLESS.containsMatchIn(text)) return false
        if (FLAC.containsMatchIn(text)) return false
        if (DTS.containsMatchIn(text)) return false
        return true
    }
}
