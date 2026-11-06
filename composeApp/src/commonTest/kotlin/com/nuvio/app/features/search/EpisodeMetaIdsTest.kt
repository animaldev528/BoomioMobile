package com.nuvio.app.features.search

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeMetaIdsTest {
    @Test
    fun `episode catalog ids resolve to the parent show`() {
        assertEquals(
            "tt0386676",
            resolveMetaPreviewDetailId("tt0386676:6:9"),
        )
    }

    @Test
    fun `show, movie and tmdb ids pass through unchanged`() {
        assertEquals("tt0386676", resolveMetaPreviewDetailId("tt0386676"))
        assertEquals("tt0133093", resolveMetaPreviewDetailId("tt0133093"))
        assertEquals("tmdb:603", resolveMetaPreviewDetailId("tmdb:603"))
        assertEquals("", resolveMetaPreviewDetailId(""))
    }

    @Test
    fun `ids that only look episode-shaped pass through unchanged`() {
        assertEquals("tt0386676:6", resolveMetaPreviewDetailId("tt0386676:6"))
        assertEquals("ttabc:6:9", resolveMetaPreviewDetailId("ttabc:6:9"))
    }
}
