package app.vela.core

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Test

class SameRoadMergeTest {
    private fun m(t: ManeuverType, text: String, dist: Double, road: String? = null, ref: String? = null) =
        Maneuver(t, text, LatLng(0.0, 0.0), dist, dist / 25.0, road = road, ref = ref)

    @Test fun `a merge onto the freeway a fork pointed toward, far down it, is folded`() {
        val steps = listOf(
            m(ManeuverType.RAMP_RIGHT, "Take the exit toward I 80 West: Davis", 1500.0),
            m(ManeuverType.FORK_LEFT, "Keep slight left toward I 80 West: Davis", 12700.0),
            m(ManeuverType.MERGE, "Merge onto I 80", 1800.0, ref = "I 80"),
            m(ManeuverType.RAMP_RIGHT, "Take the exit toward Downtown", 600.0),
        )
        val out = RouteGeometry.foldSameRoadMerges(steps)
        assertEquals(listOf(ManeuverType.RAMP_RIGHT, ManeuverType.FORK_LEFT, ManeuverType.RAMP_RIGHT), out.map { it.type })
        assertEquals(14500.0, out[1].distanceMeters, 0.01)
        assertEquals("I 80", out[1].ref)
    }

    @Test fun `the merge at the end of the ramp, just after its fork, is kept`() {
        val steps = listOf(
            m(ManeuverType.FORK_LEFT, "Keep slight left toward I 80 West: Davis", 150.0),
            m(ManeuverType.MERGE, "Merge onto I 80", 12000.0, ref = "I 80"),
        )
        assertEquals(2, RouteGeometry.foldSameRoadMerges(steps).size)
    }

    @Test fun `a second merge onto the road already entered is folded, spelling aside`() {
        val steps = listOf(
            m(ManeuverType.MERGE, "Merge onto I 80", 9000.0, ref = "I 80"),
            m(ManeuverType.MERGE, "Merge onto I-80", 1000.0, ref = "I-80"),
            m(ManeuverType.TURN_RIGHT, "Turn right onto Main Street", 300.0, road = "Main Street"),
        )
        val out = RouteGeometry.foldSameRoadMerges(steps)
        assertEquals(listOf(ManeuverType.MERGE, ManeuverType.TURN_RIGHT), out.map { it.type })
        assertEquals(10000.0, out[0].distanceMeters, 0.01)
    }

    @Test fun `a merge onto a different road stays`() {
        val steps = listOf(
            m(ManeuverType.MERGE, "Merge onto I 80", 9000.0, ref = "I 80"),
            m(ManeuverType.MERGE, "Merge onto US 50", 1000.0, ref = "US 50"),
        )
        assertEquals(2, RouteGeometry.foldSameRoadMerges(steps).size)
    }
}
