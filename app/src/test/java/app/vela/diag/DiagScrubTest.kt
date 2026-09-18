package app.vela.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The export scrub, both levels (issue #507). Fixture coordinates are Davis, CA. */
class DiagScrubTest {
    @Test fun plainExportRoundsToTwoPlacesAndKeepsTheRest() {
        val s = "\"dumplins\" near 38.54490,-121.74051 → 12 results (page1 6)"
        assertEquals("\"dumplins\" near 38.54,-121.74 → 12 results (page1 6)", DiagScrub.summary(s, redact = false))
    }

    @Test fun redactedExportHidesTheQueryAndCoarsensCoordinates() {
        val s = "\"dumplins\" near 38.54490,-121.74051 → 12 results (page1 6)"
        assertEquals("\"[redacted]\" near 38.5,-121.7 → 12 results (page1 6)", DiagScrub.summary(s, redact = true))
    }

    @Test fun zoomLevelsAndCountsAreNeverTouched() {
        val s = "showing 3 camera(s) at z16.5"
        assertEquals(s, DiagScrub.summary(s, redact = true))
    }

    @Test fun navStartLosesItsDestinationLabel() {
        assertEquals("start → [redacted]", DiagScrub.summary("start → Mikuni Davis", redact = true))
        assertEquals("start → Mikuni Davis", DiagScrub.summary("start → Mikuni Davis", redact = false))
    }

    @Test fun urlsKeepOnlyTheirHostAndCidsGo() {
        val d = "https://www.google.com/maps/preview/place?q=Mikuni&cid=123456789 failed"
        assertEquals("https://www.google.com/[redacted] failed", DiagScrub.detail("drift", d, redact = true))
        assertEquals("load hl=en cid=[redacted]", DiagScrub.summary("load hl=en cid=123", redact = true))
    }

    @Test fun reviewsProbesDropTheirDetailWhenRedacting() {
        assertEquals("[redacted]", DiagScrub.detail("reviews", "page text with a business name", redact = true))
        assertEquals("page text with a business name", DiagScrub.detail("reviews", "page text with a business name", redact = false))
        assertNull(DiagScrub.detail("reviews", null, redact = true))
    }
}
