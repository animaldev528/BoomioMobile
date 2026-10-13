package com.nuvio.app.features.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerCredentialsTest {

    @Test
    fun `a document with the deployment's ids reads all three`() {
        val ids = TrackerCredentials.parseConfigDocument(
            """{"config_version":1,"trackers":{"trakt_client_id":"t","simkl_client_id":"s","mdblist_client_id":"m"}}""",
        )

        assertEquals(TrackerIds("t", "s", "m"), ids)
        assertTrue(assertNotNull(ids).hasAny)
    }

    @Test
    fun `a document whose trackers object is absent means the deployment supplies none`() {
        val ids = TrackerCredentials.parseConfigDocument("""{"config_version":1,"max_bitrate_mbps":0}""")

        // Authoritative, not "unknown": this is the answer that restores the baked floor.
        assertEquals(TrackerIds.None, ids)
        assertFalse(assertNotNull(ids).hasAny)
    }

    @Test
    fun `individual nulls read as absent rather than as an empty id`() {
        val ids = TrackerCredentials.parseConfigDocument(
            """{"config_version":1,"trackers":{"trakt_client_id":"t","simkl_client_id":null,"mdblist_client_id":"  "}}""",
        )

        assertEquals(TrackerIds("t", null, null), ids)
    }

    @Test
    fun `a body that is not a config document is unknown, not empty`() {
        // ⚠️ The distinction these three cases draw is the whole safety story. A 200 is not proof of a
        // config document — a misrouted prefix answers with an SPA's HTML, and an error can answer with
        // JSON — and reading either as "the admin removed the ids" would blank out a cache that is
        // still correct.
        assertNull(TrackerCredentials.parseConfigDocument("<html><body>Nuvio</body></html>"))
        assertNull(TrackerCredentials.parseConfigDocument("""{"error":"not found"}"""))
        assertNull(TrackerCredentials.parseConfigDocument(""))
        assertNull(TrackerCredentials.parseConfigDocument("[1,2,3]"))
    }
}
