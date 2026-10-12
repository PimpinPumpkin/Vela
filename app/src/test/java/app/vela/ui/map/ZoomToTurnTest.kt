package app.vela.ui.map

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Zoom to the next turn" ([NavFraming], SPEC 4.7 Framing). Fixture: a 1080 x 2340 px phone at
 *  420 dpi (2.625), the turn card ending 230 dp down, near Davis (latitude 38.54). */
class ZoomToTurnTest {
    private val heightDp = 2340 / 2.625
    private val arrowDp = heightDp * (1 + NavFraming.DEFAULT_PAD) / 2
    private val upDp = arrowDp - 230.0 - NavFraming.TURN_MARGIN_DP
    private val lat = 38.54

    /** Meters from the arrow to the row [upDp] above it at [zoom], with the camera tilted [tilt]. */
    private fun metersToEdge(zoom: Double, tilt: Double) = NavFraming.groundAhead(upDp, tilt, heightDp) * NavFraming.metersPerDp(zoom, lat)

    @Test fun `flat the ground is the screen`() {
        assertEquals(392.0, NavFraming.groundAhead(392.0, 0.0, heightDp), 1e-9)
        assertEquals(392.0, NavFraming.screenUp(392.0, 0.0, heightDp), 1e-9)
    }

    @Test fun `tilted rows round-trip through the projection`() {
        for (tilt in listOf(11.0, 33.0, 55.0)) for (up in listOf(0.0, 100.0, 392.3, 622.3)) {
            val g = NavFraming.groundAhead(up, tilt, heightDp)
            assertEquals("tilt $tilt up $up", up, NavFraming.screenUp(g, tilt, heightDp), 1e-6)
            assertTrue("tilt $tilt up $up", g >= up - 1e-9)
        }
        // At the drive tilt the 392 dp above the arrow reach about three times as much ground.
        assertEquals(1177.0, NavFraming.groundAhead(upDp, 55.0, heightDp), 1.0)
    }

    @Test fun `meters per dp follow the 512 px world`() {
        assertEquals(NavFraming.EARTH_CIRCUMFERENCE_M / 512, NavFraming.metersPerDp(0.0, 0.0), 1e-6)
        assertEquals(0.934, NavFraming.metersPerDp(16.0, lat), 0.001)
    }

    @Test fun `the turn zoom puts the turn at the edge`() {
        for (m in listOf(200.0, 1000.0, 2000.0)) {
            val z = NavFraming.turnZoom(m, upDp, heightDp, 55.0, lat)
            assertEquals("$m m", m, metersToEdge(z, 55.0), 0.01)
        }
        // On the fixture a turn 1 km ahead frames at about z16.1, and the speed zoom at 30 m/s
        // (z15.8) frames one about 1.26 km ahead.
        assertEquals(16.14, NavFraming.turnZoom(1000.0, upDp, heightDp, 55.0, lat), 0.01)
        assertEquals(1263.0, metersToEdge(15.8, 55.0), 1.0)
        // Farther is farther out.
        assertTrue(NavFraming.turnZoom(3000.0, upDp, heightDp, 55.0, lat) < NavFraming.turnZoom(2000.0, upDp, heightDp, 55.0, lat))
    }

    @Test fun `nothing to frame is NaN`() {
        assertTrue(NavFraming.turnZoom(0.0, upDp, heightDp, 55.0, lat).isNaN())
        assertTrue(NavFraming.turnZoom(500.0, 0.0, heightDp, 55.0, lat).isNaN())
        assertTrue(NavFraming.turnZoom(500.0, -10.0, heightDp, 55.0, lat).isNaN())
        assertTrue(NavFraming.turnZoom(500.0, upDp, 0.0, 55.0, lat).isNaN())
    }

    @Test fun `below z15 the camera flattens and the zoom allows for it`() {
        // Within the full tilt the two agree.
        val near = NavFraming.turnZoomFlattening(2000.0, upDp, heightDp, 55.0, lat)
        assertEquals(NavFraming.turnZoom(2000.0, upDp, heightDp, 55.0, lat), near, 1e-9)
        assertTrue(near > NAV_TILT_FULL_ZOOM)
        // 3 km: about z13.1 with the camera at 13.5 degrees, and the turn still at the edge there.
        val far = NavFraming.turnZoomFlattening(3000.0, upDp, heightDp, 55.0, lat)
        assertEquals(13.11, far, 0.01)
        assertEquals(13.5, NavFraming.tiltAt(far, 55.0), 0.1)
        assertEquals(3000.0, metersToEdge(far, NavFraming.tiltAt(far, 55.0)), 1.0)
        assertTrue(far < NavFraming.turnZoom(3000.0, upDp, heightDp, 55.0, lat))
        // 5 km: flat, about z12.2.
        val flat = NavFraming.turnZoomFlattening(5000.0, upDp, heightDp, 55.0, lat)
        assertEquals(12.23, flat, 0.01)
        assertEquals(0.0, NavFraming.tiltAt(flat, 55.0), 1e-9)
        assertEquals(5000.0, metersToEdge(flat, 0.0), 1.0)
        // A shove-set tilt is honored as the wanted one.
        assertEquals(20.0, NavFraming.tiltAt(16.0, 20.0), 1e-9)
    }

    @Test fun `the speed zoom wins near the turn and the floor far from it`() {
        val speed = 17.0
        assertEquals(speed, NavFraming.withTurn(speed, 18.4), 1e-9)
        assertEquals(speed, NavFraming.withTurn(speed, Double.NaN), 1e-9)
        assertEquals(15.1, NavFraming.withTurn(speed, 15.1), 1e-9)
        assertEquals(NavFraming.ZOOM_TURN_MIN, NavFraming.withTurn(speed, 10.2), 1e-9)
        // On the fixture the floor frames a turn about 5.9 km ahead; a farther one sits off the top.
        assertEquals(5863.0, metersToEdge(NavFraming.ZOOM_TURN_MIN, NavFraming.tiltAt(NavFraming.ZOOM_TURN_MIN, 55.0)), 1.0)
    }

    @Test fun `the target is the next step that asks something of the driver`() {
        val a = LatLng(38.54, -121.74)
        val b = LatLng(38.55, -121.74)
        val c = LatLng(38.56, -121.74)
        val end = LatLng(38.57, -121.74)
        fun m(type: ManeuverType, at: LatLng) = Maneuver(type, "", at, 1000.0, 60.0)
        val steps = listOf(m(ManeuverType.DEPART, a), m(ManeuverType.CONTINUE, b), m(ManeuverType.STRAIGHT, c), m(ManeuverType.TURN_LEFT, end))
        val route = Route(
            polyline = listOf(a, b, c, end),
            legs = listOf(RouteLeg(3000.0, 180.0, null, steps)),
            distanceMeters = 3000.0, durationSeconds = 180.0, durationInTrafficSeconds = null,
        )
        NavTurnTarget.update(route, 0)
        assertEquals(a, NavTurnTarget.point)
        NavTurnTarget.update(route, 1)
        assertEquals(end, NavTurnTarget.point)
        NavTurnTarget.update(route, 3)
        assertEquals(end, NavTurnTarget.point)
        NavTurnTarget.update(route, 4)
        assertNull(NavTurnTarget.point)
        NavTurnTarget.update(null, 0)
        assertNull(NavTurnTarget.point)
    }
}
