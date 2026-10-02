package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Test

class StepAuditTest {
    // The Davis fixture corner: north 555 m, then east 435 m.
    private fun p(dLat: Double, dLng: Double) = LatLng(38.54 + dLat, -121.74 + dLng)
    private val line = listOf(p(0.0, 0.0), p(0.005, 0.0), p(0.005, 0.005))
    private fun man(type: ManeuverType, dist: Double) = Maneuver(type, type.name, p(0.0, 0.0), dist, dist / 10)
    private fun route(vararg ms: Maneuver) = Route(
        polyline = line, legs = listOf(RouteLeg(990.0, 99.0, null, ms.toList())), distanceMeters = 990.0,
        durationSeconds = 99.0, durationInTrafficSeconds = null,
    )

    @Test fun aRightWhereTheLineGoesRightAgrees() {
        val r = StepAudit.check(route(man(ManeuverType.DEPART, 555.0), man(ManeuverType.TURN_RIGHT, 435.0), man(ManeuverType.ARRIVE, 0.0)))
        assertEquals(1, r.agree); assertEquals(0, r.findings.size)
    }

    @Test fun aLeftWhereTheLineGoesRightIsFound() {
        val r = StepAudit.check(route(man(ManeuverType.DEPART, 555.0), man(ManeuverType.TURN_LEFT, 435.0), man(ManeuverType.ARRIVE, 0.0)))
        assertEquals(1, r.otherWay)
    }

    @Test fun aTurnOnAStraightRoadIsFound() {
        val r = StepAudit.check(route(man(ManeuverType.DEPART, 250.0), man(ManeuverType.TURN_RIGHT, 305.0), man(ManeuverType.TURN_RIGHT, 435.0), man(ManeuverType.ARRIVE, 0.0)))
        assertEquals(1, r.noBend); assertEquals(1, r.agree)
    }

    @Test fun aCornerWithNoStepIsFound() {
        val r = StepAudit.check(route(man(ManeuverType.DEPART, 990.0), man(ManeuverType.ARRIVE, 0.0)))
        assertEquals(1, r.unsaid)
    }
}
