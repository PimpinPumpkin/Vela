package app.vela.core.data

import app.vela.core.model.LatLng
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A recheck on the same roads is never a different route. */
class SameRoadTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private fun pt(e: Double, n: Double) = LatLng(lat0 + n / 111_320.0, lng0 + e / mPerDegLng)

    // East 3 km, then north 2 km.
    private val current = (0..300).map { pt(it * 10.0, 0.0) } + (1..200).map { pt(3000.0, it * 10.0) }

    @Test fun `the rest of the route from the car is the same road`() {
        val ahead = (100..300).map { pt(it * 10.0, 3.0) } + (1..200).map { pt(3000.0 + 4.0, it * 10.0) }
        assertTrue(RouteGeometry.sameRoad(current, ahead))
        assertFalse("five samples at 250 m call it the same course", RouteGeometry.divergent(app.vela.core.model.Route(current, emptyList(), 0.0, 0.0, null), app.vela.core.model.Route(ahead, emptyList(), 0.0, 0.0, null), 250.0))
    }

    @Test fun `a parallel street a block over is a different road`() {
        // From 1 km, a street 150 m north for 1 km, back down, then the same road on.
        val detour = (100..120).map { pt(it * 10.0, 0.0) } + (1..15).map { pt(1200.0, it * 10.0) } +
            (1..100).map { pt(1200.0 + it * 10.0, 150.0) } + (1..15).map { pt(2200.0, 150.0 - it * 10.0) } +
            (1..80).map { pt(2200.0 + it * 10.0, 0.0) } + (1..200).map { pt(3000.0, it * 10.0) }
        assertFalse(RouteGeometry.sameRoad(current, detour))
        assertFalse("and the old test cannot see it", RouteGeometry.divergent(app.vela.core.model.Route(current, emptyList(), 0.0, 0.0, null), app.vela.core.model.Route(detour, emptyList(), 0.0, 0.0, null), 250.0))
    }
}
