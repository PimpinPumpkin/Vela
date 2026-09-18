package app.vela.core.nav

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteProjectionBearingTest {
    private val northSouth = listOf(LatLng(38.5400, -121.7400), LatLng(38.5500, -121.7400))

    @Test fun `bearing along a northbound line`() {
        val cum = RouteProjection.cumulative(northSouth)
        assertEquals(0.0, RouteProjection.bearingAt(northSouth, cum, 100.0), 1.0)
    }

    @Test fun `a sign on your road counts, one across it does not`() {
        // Driving north: a sign on a north-south road is yours, one on the east-west street is not.
        assertTrue(RouteProjection.alignedWithRoad(0.0, 0))
        assertTrue(RouteProjection.alignedWithRoad(0.0, 175)) // 5 degrees off, wrapping
        assertTrue(RouteProjection.alignedWithRoad(180.0, 10)) // driving south, same road
        assertFalse(RouteProjection.alignedWithRoad(0.0, 90))
        assertFalse(RouteProjection.alignedWithRoad(270.0, 0))
        assertTrue(RouteProjection.alignedWithRoad(270.0, 90)) // driving west on an east-west road
    }
}
