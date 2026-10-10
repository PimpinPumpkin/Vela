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
