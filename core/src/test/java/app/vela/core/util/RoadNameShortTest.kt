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

    @Test fun theRoadInsideAnInstruction() {
        assertEquals("Turn right onto Court St", RoadNameShort.inText("Turn right onto Court Street", "Court Street"))
        assertEquals("Continue onto Russell Rd NE", RoadNameShort.inText("Continue onto Russell Road Northeast", "Russell Road Northeast"))
        assertEquals("Turn left", RoadNameShort.inText("Turn left", null))
        assertEquals("Turn left onto Broadway", RoadNameShort.inText("Turn left onto Broadway", "Broadway"))
        // The text names the road another way: untouched.
        assertEquals("Continue onto CA 113", RoadNameShort.inText("Continue onto CA 113", "Richards Boulevard"))
        assertEquals("Elm Jct", RoadNameShort.shorten("Elm Junction"))
    }

    @Test fun aCompassWordAfterARouteNumberIsItsLetter() {
        assertEquals("Take the ramp on the right toward US 169 N: I 44, Tulsa",
            RoadNameShort.inText("Take the ramp on the right toward US 169 North: I 44, Tulsa", null))
        assertEquals("Merge onto I-44 W", RoadNameShort.inText("Merge onto I-44 Westbound", null))
        assertEquals("Take exit 18 toward CA 113 S", RoadNameShort.inText("Take exit 18 toward CA 113 South", null))
        // A direction in a place's name, or with no route number before it, stays.
        assertEquals("Keep left toward North Bend", RoadNameShort.inText("Keep left toward North Bend", null))
        assertEquals("Turn right onto W Covell Blvd", RoadNameShort.inText("Turn right onto West Covell Boulevard", "West Covell Boulevard"))
    }

    @Test fun otherLanguagesPassThrough() {
        assertEquals("Rue de la Paix", RoadNameShort.shorten("Rue de la Paix"))
        assertEquals("Hauptstraße", RoadNameShort.shorten("Hauptstraße"))
    }
}
