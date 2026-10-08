package app.vela.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

/** The drive camera lies flatter the further out the user has pinched ([navTiltCap]). */
class NavTiltCapTest {
    @Test fun `street zoom keeps the whole tilt`() {
        assertEquals(55.0, navTiltCap(18.0), 1e-9)
        assertEquals(55.0, navTiltCap(NAV_TILT_FULL_ZOOM), 1e-9)
        // The camera's own zoom at freeway speed: untouched.
        assertEquals(55.0, navTiltCap(15.8), 1e-9)
    }

    @Test fun `city-wide zoom is flat`() {
        assertEquals(0.0, navTiltCap(NAV_TILT_FLAT_ZOOM), 1e-9)
        assertEquals(0.0, navTiltCap(9.0), 1e-9)
    }

    @Test fun `between the two it comes down in a straight line`() {
        assertEquals(27.5, navTiltCap((NAV_TILT_FULL_ZOOM + NAV_TILT_FLAT_ZOOM) / 2), 1e-9)
        assertEquals(22.0, navTiltCap(13.5), 1e-9)
    }
}
