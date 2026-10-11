package app.vela.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarFramingTest {
    @Test fun `flat offsets are the screen offsets`() {
        val (ahead, left) = CarFraming.targetOffset(158.0, 40.0, 0.0, 720.0)
        assertEquals(158.0, ahead, 1e-9)
        assertEquals(40.0, left, 1e-9)
    }

    @Test fun `tilted offsets land the anchor on the wanted row`() {
        for (tilt in listOf(20.0, 40.0, 55.0)) for (down in listOf(0.0, 80.0, 158.0, 250.0)) {
            val (ahead, _) = CarFraming.targetOffset(down, 0.0, tilt, 720.0)
            assertEquals("tilt $tilt down $down", down, CarFraming.screenDown(ahead, tilt, 720.0), 1e-6)
        }
    }

    @Test fun `tilt brings the anchor row nearer the center in ground terms`() {
        val flat = CarFraming.targetOffset(158.0, 60.0, 0.0, 720.0)
        val tilted = CarFraming.targetOffset(158.0, 60.0, 55.0, 720.0)
        // Ground nearer the camera is drawn larger: fewer ground pixels per screen pixel, so
        // less ground ahead and less ground across for the same screen offsets.
        assertTrue(tilted.first < flat.first * 1.6 && tilted.first > flat.first)
        assertTrue(tilted.second < flat.second)
    }

    @Test fun `zoom by speed runs from near at rest to far on the highway`() {
        assertEquals(CarFraming.ZOOM_NEAR, CarFraming.navZoom(0.0), 1e-9)
        assertEquals(CarFraming.ZOOM_FAR, CarFraming.navZoom(110.0), 1e-9)
        assertEquals(CarFraming.ZOOM_FAR, CarFraming.navZoom(160.0), 1e-9)
        val mid = CarFraming.navZoom(55.0)
        assertTrue(mid < CarFraming.ZOOM_NEAR && mid > CarFraming.ZOOM_FAR)
    }
}
