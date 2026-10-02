package app.vela.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadNameShortTest {
    @Test fun typesAndTrailingDirections() {
        assertEquals("Covell Blvd", RoadNameShort.shorten("Covell Boulevard"))
        assertEquals("W Covell Blvd", RoadNameShort.shorten("West Covell Boulevard"))
        assertEquals("Russell Rd NE", RoadNameShort.shorten("Russell Road Northeast"))
        assertEquals("NE 8th St", RoadNameShort.shorten("Northeast 8th Street"))
    }

    @Test fun theNameItselfIsKept() {
        assertEquals("North Ave", RoadNameShort.shorten("North Avenue"))
        assertEquals("Court St", RoadNameShort.shorten("Court Street"))
        assertEquals("Broadway", RoadNameShort.shorten("Broadway"))
        assertEquals("I 80", RoadNameShort.shorten("I 80"))
    }

    @Test fun otherLanguagesPassThrough() {
        assertEquals("Rue de la Paix", RoadNameShort.shorten("Rue de la Paix"))
        assertEquals("Hauptstraße", RoadNameShort.shorten("Hauptstraße"))
    }
}
