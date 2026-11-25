package app.vela.core.nav

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackToRouteTest {
    private val lat = 38.5449
    private val lng0 = -121.7405 // Davis fixture
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun at(eastM: Double, northM: Double = 0.0) = LatLng(lat + northM / 111_320.0, lng0 + eastM / mPerDegLng)

    /** The route runs east 5 km. */
    private val line = (0..500).map { at(it * 10.0) }
    private val cum = RouteProjection.cumulative(line)

    @Test fun `a car north of the route heading east has it 300 m to its right`() {
        val b = BackToRoute.of(line, cum, at(2_000.0, 300.0), headingDeg = 90.0, fromM = 1_500.0)!!
        assertEquals(300.0, b.meters, 2.0)
        assertEquals(BackToRoute.Side.RIGHT, b.side)
    }

    @Test fun `heading north away from it, the route is behind`() {
        val b = BackToRoute.of(line, cum, at(2_000.0, 300.0), headingDeg = 0.0, fromM = 1_500.0)!!
        assertEquals(BackToRoute.Side.BEHIND, b.side)
    }

    @Test fun `heading west, it is on the left, and with no course no side`() {
        assertEquals(BackToRoute.Side.LEFT, BackToRoute.of(line, cum, at(2_000.0, 300.0), 270.0, 1_500.0)!!.side)
        assertNull(BackToRoute.of(line, cum, at(2_000.0, 300.0), null, 1_500.0)!!.side)
    }

    @Test fun `the part of the route already driven is not the way back`() {
        // Beside the start, but the car had driven 3 km: the nearest point is 3 km on.
        val b = BackToRoute.of(line, cum, at(0.0, 100.0), headingDeg = 90.0, fromM = 3_000.0)!!
        assertEquals(3_000.0, b.meters, 15.0)
        assertEquals(BackToRoute.Side.AHEAD, b.side)
    }
}
