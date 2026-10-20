package app.vela.ui.map

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The drive camera's turn toward the next maneuver ([NavFraming.turnYaw], SPEC 4.7 Framing),
 *  and the maneuver point the phone and the car both frame ([NavTurnTarget.pointFor]). */
class TurnYawTest {
    @Test fun `the camera turns toward the turn it frames`() {
        // Heading north, the turn 30 degrees right, the turn's zoom a level under the speed's.
        assertEquals(30.0, NavFraming.turnYaw(0.0, 30.0, 16.0, 15.0), 1e-9)
        assertEquals(-30.0, NavFraming.turnYaw(0.0, 330.0, 16.0, 15.0), 1e-9)
    }

    @Test fun `no more than 90 degrees either way`() {
        assertEquals(80.0, NavFraming.turnYaw(0.0, 80.0, 16.0, 14.0), 1e-9)
        assertEquals(90.0, NavFraming.turnYaw(0.0, 120.0, 16.0, 14.0), 1e-9)
        assertEquals(-90.0, NavFraming.turnYaw(90.0, 0.0, 16.0, 14.0), 1e-9)
        // A maneuver behind the car: the shorter way around, clamped.
        assertEquals(-90.0, NavFraming.turnYaw(10.0, 200.0, 16.0, 14.0), 1e-9)
    }

    @Test fun `the yaw fades in over the last zoom level and is zero near the turn`() {
        assertEquals(0.0, NavFraming.turnYaw(0.0, 30.0, 16.0, 16.0), 1e-9)  // the speed zoom governs
        assertEquals(0.0, NavFraming.turnYaw(0.0, 30.0, 16.0, 17.0), 1e-9)  // nearer than the speed zoom frames
        assertEquals(15.0, NavFraming.turnYaw(0.0, 30.0, 16.0, 15.5), 1e-9) // halfway
        assertEquals(30.0, NavFraming.turnYaw(0.0, 30.0, 16.0, 12.0), 1e-9) // far out: all of it
        assertEquals(0.5, NavFraming.yawWeight(16.0, 15.5), 1e-9)
    }

    @Test fun `nothing to frame or straight ahead is no yaw`() {
        assertEquals(0.0, NavFraming.turnYaw(0.0, 30.0, 16.0, Double.NaN), 1e-9)
        assertEquals(0.0, NavFraming.turnYaw(350.0, 350.0, 16.0, 14.0), 1e-9)
    }

    @Test fun `the angle wraps through north`() {
        assertEquals(20.0, NavFraming.turnYaw(350.0, 10.0, 16.0, 14.0), 1e-9)
        assertEquals(-20.0, NavFraming.turnYaw(10.0, 350.0, 16.0, 14.0), 1e-9)
    }

    @Test fun `the target point skips quiet steps and counts the arrival`() {
        val a = LatLng(38.5400, -121.7400)
        val c = LatLng(38.5418, -121.7400)
        val b = LatLng(38.5418, -121.7377)
        fun m(type: ManeuverType, at: LatLng) = Maneuver(type, "", at, 0.0, 0.0)
        val route = Route(
            polyline = listOf(a, c, b),
            legs = listOf(RouteLeg(400.0, 60.0, null, listOf(m(ManeuverType.DEPART, a), m(ManeuverType.STRAIGHT, c), m(ManeuverType.ARRIVE, b)))),
            distanceMeters = 400.0, durationSeconds = 60.0, durationInTrafficSeconds = null,
        )
        assertEquals(a, NavTurnTarget.pointFor(route, 0))
        assertEquals(b, NavTurnTarget.pointFor(route, 1)) // the straight step is quiet: the arrival
        assertEquals(b, NavTurnTarget.pointFor(route, 2))
        assertNull(NavTurnTarget.pointFor(route, 3))
        assertNull(NavTurnTarget.pointFor(null, 0))
    }
}
