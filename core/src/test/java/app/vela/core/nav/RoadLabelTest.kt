package app.vela.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadLabelTest {
    @Test fun `a numbered road with a name of its own shows the name`() {
        assertEquals("Richards Boulevard", roadLabel("Richards Boulevard", "CA 113"))
        assertEquals("Lincoln Highway", roadLabel("Lincoln Highway", "US 50"))
        assertEquals("9th Street", roadLabel("9th Street", "SR 9"))
    }

    @Test fun `a name that only says the number shows the number`() {
        assertEquals("SR 9", roadLabel("State Route 9", "SR 9"))
        assertEquals("SR 9", roadLabel("Highway 9", "SR 9"))
        assertEquals("CR 99D", roadLabel("County Road 99D", "CR 99D"))
        assertEquals("US 50", roadLabel("US Highway 50 East", "US 50"))
        assertEquals("B 27", roadLabel("Bundesstraße 27", "B 27"))
    }

    @Test fun `an Interstate shows its number even with a name`() {
        assertEquals("I 80", roadLabel("Dwight D. Eisenhower Highway", "I 80"))
        assertEquals("I-5 N", roadLabel("Golden State Freeway", "I-5 N"))
    }

    @Test fun `one of the two is enough, and neither is nothing`() {
        assertEquals("F Street", roadLabel("F Street", null))
        assertEquals("F Street", roadLabel("F Street", " "))
        assertEquals("CA 113", roadLabel(null, "CA 113"))
        assertEquals("CA 113", roadLabel("", "CA 113"))
        assertNull(roadLabel(null, " "))
    }
}
