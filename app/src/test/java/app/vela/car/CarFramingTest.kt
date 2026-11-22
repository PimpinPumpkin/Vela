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

    @Test fun `a flat camera draws every point and a tilted one stops well below the screen`() {
        assertEquals(Double.POSITIVE_INFINITY, CarFraming.behindLimitPx(0.0, 720.0), 0.0)
        val limit = CarFraming.behindLimitPx(CarFraming.NAV_TILT_DEG, 720.0)
        // In front of the camera: the point level with it is cameraDistance / sin(tilt) back.
        assertTrue(limit < CarFraming.cameraDistancePx(720.0) / Math.sin(Math.toRadians(CarFraming.NAV_TILT_DEG)))
        // And off the bottom edge by several screens, so the cut is never seen.
        assertTrue(CarFraming.screenDown(limit, CarFraming.NAV_TILT_DEG, 720.0) > 5 * 720.0)
    }

    @Test fun `the clip keeps what is ahead and drops what is far behind the camera`() {
        // Davis, facing north, 1 km of ground behind the target.
        val north = FrontClip(38.5449, -121.7405, 0.0, 1000.0)
        val mPerDegLat = 111_320.0
        assertTrue(north.inFront(38.5449 + 50_000 / mPerDegLat, -121.7405))
        assertTrue(north.inFront(38.5449 - 500 / mPerDegLat, -121.7405))
        assertTrue(!north.inFront(38.5449 - 2_000 / mPerDegLat, -121.7405))
        assertEquals(500.0, north.behindM(38.5449 - 500 / mPerDegLat, -121.7405), 0.5)
        // Facing east, west is behind and north is abeam.
        val east = FrontClip(38.5449, -121.7405, 90.0, 1000.0)
        val mPerDegLng = mPerDegLat * Math.cos(Math.toRadians(38.5449))
        assertTrue(!east.inFront(38.5449, -121.7405 - 2_000 / mPerDegLng))
        assertTrue(east.inFront(38.5449 + 2_000 / mPerDegLat, -121.7405))
        // A flat camera has no limit.
        assertTrue(FrontClip(38.5449, -121.7405, 0.0, Double.POSITIVE_INFINITY).inFront(-38.0, 58.0))
    }

    @Test fun `a segment that passes behind the camera is cut at the limit`() {
        val clip = FrontClip(38.5449, -121.7405, 0.0, 1000.0)
        val m = 111_320.0
        val t = clip.crossing(38.5449 - 500 / m, -121.7405, 38.5449 - 1_500 / m, -121.7405)
        assertEquals(0.5, t, 1e-3)
        // The same segment walked the other way crosses at the same place.
        assertEquals(0.5, clip.crossing(38.5449 - 1_500 / m, -121.7405, 38.5449 - 500 / m, -121.7405), 1e-3)
    }

    @Test fun `a box fits the visible area, not one size for every screen`() {
        // Davis to Sacramento, a wide box.
        val wide = CarFraming.fitZoom(38.54, 38.60, -121.75, -121.48, 1000.0, 600.0)
        val narrow = CarFraming.fitZoom(38.54, 38.60, -121.75, -121.48, 250.0, 600.0)
        assertEquals(2.0, wide - narrow, 1e-9) // a quarter of the width, two zoom levels out
        // At the fitted zoom the box's width in pixels is the margin's share of the area.
        val px = 512.0 * Math.pow(2.0, wide) * (0.27 / 360.0)
        assertEquals(1000.0 * CarFraming.FIT_MARGIN, px, 1e-6)
    }

    @Test fun `a tall box is fitted on its height`() {
        val z = CarFraming.fitZoom(38.50, 38.70, -121.75, -121.74, 1000.0, 300.0)
        val taller = CarFraming.fitZoom(38.50, 38.70, -121.75, -121.74, 1000.0, 600.0)
        assertEquals(1.0, taller - z, 1e-9)
    }
}
