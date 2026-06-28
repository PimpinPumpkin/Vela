package app.vela.core

import app.vela.core.data.google.parse.DirectionsParser
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.distanceTo
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the "Raising Cane's route mis-placed every turn" report (2026-06-28). The real
 * route had ~3.3 km of step metres on a ~6.4 km decoded polyline; `placeManeuvers` divided
 * cumulative step-metres by the POLYLINE length, which crammed all the turns into the first half
 * of the route and dropped them onto the wrong roads. The fix divides by the STEP-distance total,
 * so a turn lands at its proportional point regardless of the absolute-length mismatch.
 */
class CanesRouteTest {
    @Test
    fun placesTurnsByStepFractionNotPolylineLength() {
        // A straight ~1.1 km polyline, but the steps only describe 500 m (the mismatch). The middle
        // turn is at 200 m of 500 m of steps → it must land ~0.4 along, NOT ~0.18 (polyline-relative).
        val poly = (0..100).map { LatLng(47.86, -122.20 + it * 0.0001) }
        val mans = listOf(
            Maneuver(ManeuverType.DEPART, "Depart", LatLng(0.0, 0.0), 200.0, 0.0),
            Maneuver(ManeuverType.TURN_RIGHT, "Turn right", LatLng(0.0, 0.0), 300.0, 0.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", LatLng(0.0, 0.0), 0.0, 0.0),
        )
        val placed = DirectionsParser.placeManeuvers(mans, poly)
        val total = poly.first().distanceTo(poly.last())
        val frac = poly.first().distanceTo(placed[1].location) / total
        assertTrue("turn should sit ~0.4 along (step-fraction), was $frac", frac in 0.30..0.50)
        // The final maneuver is always pinned to the route end.
        assertTrue(poly.last().distanceTo(placed[2].location) < 5.0)
    }
}
