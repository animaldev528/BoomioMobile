package com.nuvio.app.features.player

/**
 * iOS implementation: returns subtitles unchanged (no caching needed).
 * iOS players like Infuse accept remote subtitle URLs directly via their URL scheme,
 * so we just pass through the original HTTP URLs without downloading.
 */
actual object SubtitleCacheProvider {
    // Headers are ignored rather than forwarded: iOS does not download the subtitle at all, and the
    // remote URL is handed to the external player as-is, so the player's own request carries whatever
    // credentials it needs. Accepting the parameter keeps the shared contract honest for the
    // platform that does download.
    actual suspend fun cacheForExternalPlayer(
        subtitles: List<SubtitleInput>,
        headers: Map<String, String>,
    ): List<SubtitleInput>? {
        return subtitles.ifEmpty { null }
    }
}
