package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteHealTest {
    private val line = listOf(LatLng(38.54, -121.74), LatLng(38.55, -121.74))

    private fun route(traffic: Boolean = true, abbreviated: Boolean = false, namesShort: Boolean = false) = Route(
        line, listOf(RouteLeg(1100.0, 90.0, if (traffic) 95.0 else null, emptyList())), 1100.0, 90.0, if (traffic) 95.0 else null,
        abbreviatedSteps = abbreviated, source = if (abbreviated) RouteSource.GOOGLE_ABBREVIATED else RouteSource.GOOGLE_HYBRID,
        namesShort = namesShort,
    )

    @Test fun `a healthy route is not degraded and takes nothing from a candidate`() {
        assertFalse(RouteHeal.degraded(route()))
        assertNull(RouteHeal.gains(route(), route()))
    }

    @Test fun `a route short of names is re-checked on the fast interval`() {
        assertTrue(RouteHeal.degraded(route(namesShort = true)))
    }

    @Test fun `the same course come back named takes its place`() {
        assertEquals("names", RouteHeal.gains(route(namesShort = true), route()))
    }

    @Test fun `a candidate also short of names does not`() {
        assertNull(RouteHeal.gains(route(namesShort = true), route(namesShort = true)))
    }

    @Test fun `names are never bought with traffic or steps`() {
        assertNull(RouteHeal.gains(route(namesShort = true), route(traffic = false)))
        assertNull(RouteHeal.gains(route(namesShort = true), route(abbreviated = true)))
    }

    @Test fun `traffic is never bought with names`() {
        assertNull(RouteHeal.gains(route(traffic = false), route(namesShort = true)))
    }

    @Test fun `names are taken only from a route that is Google's line`() {
        assertNull("the open router's own route may run through what Google avoided", RouteHeal.gains(route(namesShort = true), route().copy(source = RouteSource.OSRM)))
        assertEquals("names", RouteHeal.gains(route(namesShort = true), route().copy(source = RouteSource.GOOGLE_LINE_NAMED)))
        assertEquals("steps still heal from any source, as before", "steps", RouteHeal.gains(route(abbreviated = true), route().copy(source = RouteSource.OSRM)))
    }

    /** Meters east and north of the Davis fixture corner. */
    private fun m(e: Double, n: Double) = LatLng(38.54 + n / 111_320.0, -121.74 + e / (111_320.0 * Math.cos(Math.toRadians(38.54))))

    @Test fun `a re-check route through a block Google avoided reads as the same course, and the names heal still refuses it`() {
        // Google's line: 3 km north, then east. The open router's answer: the same, except that it
        // cuts one block east and back around a closed stretch 1.2 km in, 300 m of detour.
        val google = (0..30).map { m(0.0, it * 100.0) } + (1..10).map { m(it * 100.0, 3000.0) }
        val open = (0..11).map { m(0.0, it * 100.0) } + listOf(m(150.0, 1200.0), m(150.0, 1500.0)) + (15..30).map { m(0.0, it * 100.0) } + (1..10).map { m(it * 100.0, 3000.0) }
        fun r(line: List<LatLng>, source: RouteSource, namesShort: Boolean) = Route(
            line, listOf(RouteLeg(4000.0, 300.0, 310.0, emptyList())), 4000.0, 300.0, 310.0, source = source, namesShort = namesShort,
        )
        val current = r(google, RouteSource.GOOGLE_HYBRID, namesShort = true)
        val candidate = r(open, RouteSource.OSRM, namesShort = false)
        // The session's same-course test (NavSession.SAME_COURSE_M) samples five points of the
        // candidate: the block is between two of them, so the two routes pass as one course.
        assertFalse(app.vela.core.data.RouteGeometry.divergent(current, candidate, 250.0))
        assertNull("so the heal has to refuse the open router's line on its own", RouteHeal.gains(current, candidate))
        assertEquals("names", RouteHeal.gains(current, r(google, RouteSource.GOOGLE_HYBRID, namesShort = false)))
    }

    @Test fun `steps and traffic heal as before, and say which`() {
        assertEquals("steps", RouteHeal.gains(route(abbreviated = true), route()))
        assertEquals("traffic", RouteHeal.gains(route(traffic = false), route()))
        assertEquals("steps+names+traffic", RouteHeal.gains(route(traffic = false, abbreviated = true, namesShort = true), route()))
    }

    @Test fun `abbreviated steps still heal when the full ones are short of a name`() {
        // Abbreviated steps are a fraction of the turns. Every turn, some of them bare, is better.
        assertEquals("steps", RouteHeal.gains(route(abbreviated = true), route(namesShort = true)))
    }
}
