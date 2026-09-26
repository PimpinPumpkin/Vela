package app.vela.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleUsageTest {
    @Test fun `google hosts are recognized, others are not`() {
        assertTrue(GoogleUsage.isGoogle("www.google.com"))
        assertTrue(GoogleUsage.isGoogle("lh3.googleusercontent.com"))
        assertTrue(GoogleUsage.isGoogle("streetviewpixels-pa.googleapis.com"))
        assertFalse(GoogleUsage.isGoogle("router.project-osrm.org"))
        assertFalse(GoogleUsage.isGoogle("notgoogle.com"))
    }

    @Test fun `urls classify by purpose`() {
        assertEquals("photos", GoogleUsage.kindOf("www.google.com", "/maps/_/MapsWizUi/data/batchexecute", "rpcids=hspqX&hl=en"))
        assertEquals("reviews", GoogleUsage.kindOf("www.google.com", "/maps/_/MapsWizUi/data/batchexecute", "rpcids=qv9Egd"))
        assertEquals("directions", GoogleUsage.kindOf("www.google.com", "/maps/preview/directions", "authuser=0"))
        assertEquals("suggestions", GoogleUsage.kindOf("www.google.com", "/s", "tbm=map"))
        assertEquals("search", GoogleUsage.kindOf("www.google.com", "/search", "tbm=map"))
        assertEquals("images", GoogleUsage.kindOf("lh3.googleusercontent.com", "/p/abc", null))
        assertEquals("street view", GoogleUsage.kindOf("maps.googleapis.com", "/maps/api/js/GeoPhotoService.SingleImageSearch", "pb=1"))
        assertEquals("session", GoogleUsage.kindOf("www.google.com", "/maps", "hl=en&gl=us"))
    }

    @Test fun `counts accumulate per day and restore merges and trims`() {
        GoogleUsage.reset()
        GoogleUsage.record("search"); GoogleUsage.record("search"); GoogleUsage.record("photos")
        val today = GoogleUsage.today()
        assertEquals(mapOf("photos" to 1, "search" to 2), GoogleUsage.snapshot()[today])
        GoogleUsage.restore(mapOf(today to mapOf("search" to 3), "2000-01-01" to mapOf("search" to 9)))
        assertEquals(5, GoogleUsage.snapshot()[today]!!["search"])
        assertFalse(GoogleUsage.snapshot().containsKey("2000-01-01"))
        GoogleUsage.reset()
    }
}
