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
}
