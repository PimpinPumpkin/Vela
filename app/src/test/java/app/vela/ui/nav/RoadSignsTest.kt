package app.vela.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadSignsTest {
    @Test fun `the ref and the same route in the text are one badge`() {
        val signs = roadSigns("Take the ramp toward I-5 North: Vancouver", explicitRef = "I 5")
        assertEquals(listOf("I 5"), signs.filter { !it.isExit }.map { it.label })
    }

    @Test fun `different routes stay separate`() {
        val signs = roadSigns("Take exit 72B toward I-80 East / US-50", explicitRef = "I 80")
        assertEquals(listOf("Exit 72B", "I 80", "US-50"), signs.map { it.label })
    }

    @Test fun `a direction letter does not make a second shield`() {
        assertEquals(routeKey("I-80 E"), routeKey("I 80"))
        assertEquals("CA99", routeKey("CA-99"))
    }

    @Test fun `the text's direction letter stays on the ref's shield`() {
        val signs = roadSigns("Keep slight left toward I 80 E: Sacramento", explicitRef = "I 80")
        assertEquals(listOf("I 80 E"), signs.map { it.label })
    }

    @Test fun `a ramp toward both directions of one route gets one shield with no direction`() {
        val signs = roadSigns("Take exit 4A toward I 5 N, I 5 S: Redding, Los Angeles", explicitRef = "I 5")
        assertEquals(listOf("Exit 4A", "I 5"), signs.map { it.label })
    }

    @Test fun `the exit tab needs a number and reads Romanian too`() {
        assertEquals(listOf("Exit 4A", "I 5 N"), roadSigns("Luați ieșirea 4A spre I 5 N: Redding").map { it.label })
        assertEquals(emptyList<String>(), roadSigns("At the roundabout, take the 1st exit onto 5th St").map { it.label })
        assertEquals(listOf("I 80"), roadSigns("Take the exit toward I 80").map { it.label })
    }

    @Test fun `a historic route gets no shield`() {
        assertEquals(emptyList<String>(), roadSigns("Turn left onto Russell Blvd", explicitRef = "US 40 Hist").map { it.label })
        assertEquals(emptyList<String>(), roadSigns("Continue on US 40 Hist").map { it.label })
        assertEquals(listOf("US 40"), roadSigns("Continue on US 40").map { it.label })
    }
}
