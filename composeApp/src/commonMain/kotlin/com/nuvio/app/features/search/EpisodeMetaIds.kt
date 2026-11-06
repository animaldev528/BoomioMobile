package com.nuvio.app.features.search

/** Matches bsf's per-episode catalog ids, e.g. "tt0386676:6:9". */
private val episodeMetaIdPattern = Regex("""^(tt\d+):\d+:\d+$""")

/**
 * bsf's `ai-search-episodes` catalog returns one result per episode, keyed
 * "tt<show>:<season>:<episode>". Details, season lists and streams all hang off the
 * show, so a tap on an episode result opens the parent series instead.
 */
internal fun resolveMetaPreviewDetailId(id: String): String {
    val trimmed = id.trim()
    val match = episodeMetaIdPattern.matchEntire(trimmed) ?: return trimmed
    return match.groupValues[1]
}
