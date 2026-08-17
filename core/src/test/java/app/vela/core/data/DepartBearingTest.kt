package app.vela.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The OSRM departure-bearing constraint (real-drive report 2026-08-17: after a wrong turn the
 * reroute kept answering "go back the way you came"). The whole feature is one query parameter, and
 * a malformed one is not an error - OSRM ignores it and routes exactly as before, so the fix would
 * silently do nothing. Hence tests on the string itself.
 */
class DepartBearingTest {

    private fun p(b: Double?, n: Int) = RouteGeometry.departBearingParam(b, n)

    @Test fun `a two point route constrains only the departure`() {
        // One entry per waypoint, semicolon separated; the trailing empty entry leaves the
        // destination unconstrained - a destination is a place, not a direction of travel.
        assertEquals("&bearings=90,65;", p(90.0, 2))
    }

    @Test fun `every extra waypoint gets its own empty entry`() {
        // OSRM REJECTS the whole request when the count does not match the coordinates, which
        // would take the reroute down with it - so a multi-stop reroute must pad exactly.
        assertEquals("&bearings=90,65;;", p(90.0, 3))
        assertEquals("&bearings=90,65;;;;", p(90.0, 5))
    }

    @Test fun `bearings are normalized into 0-359`() {
        assertEquals("&bearings=10,65;", p(370.0, 2))
        assertEquals("&bearings=350,65;", p(-10.0, 2))
        assertEquals("&bearings=0,65;", p(360.0, 2))
    }

    @Test fun `no heading sends nothing at all`() {
        // Stationary, or a fix with no bearing: constraining to a stale or invented heading would
        // be worse than not constraining, so the request is left exactly as it was.
        assertEquals("", p(null, 2))
    }

    @Test fun `a degenerate point count sends nothing`() {
        assertEquals("", p(90.0, 1))
        assertEquals("", p(90.0, 0))
    }

    @Test fun `the tolerance excludes a u-turn but allows real heading noise`() {
        // The one thing this must never do is permit a 180 degree answer, which is the bug.
        val tol = p(0.0, 2).substringAfter(",").substringBefore(";").toInt()
        assert(tol in 30..89) { "tolerance $tol should absorb GPS noise without allowing a U-turn" }
    }
}
