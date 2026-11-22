package app.vela.ui.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitPaddingBesideTest {
    @Test fun `with no side panel it is the plain fit`() {
        val fp = fitPadding(1080, 2200, 300, 1200, 140)
        assertArrayEquals(intArrayOf(fp.side, fp.top, fp.side, fp.bottom), fitPaddingBeside(1080, 2200, 0, 300, 1200, 140, 132))
    }

    @Test fun `beside a landscape panel the fit starts past it and keeps the buttons clear`() {
        // An 800 by 480 head unit at mdpi: a 420 px panel, the top card inside it.
        val p = fitPaddingBeside(752, 480, 420, 145, 0, 140, 88)
        assertTrue(p[0] > 420) // the route's start is not under the panel
        assertEquals(88, p[2]) // the map buttons' column
        assertTrue(p[1] < 145) // the top card is in the panel, not over the map
        assertTrue(752 - p[0] - p[2] > 0) // room left to draw
    }

    @Test fun `the button margin never takes more than a third of what the panel leaves`() {
        val p = fitPaddingBeside(500, 300, 380, 0, 0, 140, 88)
        assertEquals((500 - 380) / 3, p[2])
    }
}
