package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reroute's first turn, measured from the car, and when it is too close to make. */
class LateTurnTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private fun pt(e: Double, n: Double) = LatLng(lat0 + n / 111_320.0, lng0 + e / mPerDegLng)

    /** East 14 m, a left turn, north 20 m, a U-turn, south 20 m, a right turn, west 300 m. */
    private fun sideStreetTurnRound(firstType: ManeuverType = ManeuverType.TURN_LEFT): Route {
        val line = listOf(pt(0.0, 0.0), pt(14.0, 0.0), pt(14.0, 20.0), pt(14.0, 0.5), pt(-286.0, 0.5))
        val steps = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", line[0], 14.0, 2.0),
            Maneuver(firstType, "Turn left", line[1], 20.0, 4.0),
            Maneuver(ManeuverType.UTURN, "Make a U-turn", line[2], 19.5, 6.0),
            Maneuver(ManeuverType.TURN_RIGHT, "Turn right", line[3], 300.0, 30.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", line[4], 0.0, 0.0),
        )
        return Route(line, listOf(RouteLeg(353.5, 42.0, null, steps)), 353.5, 42.0, null)
    }

    @Test fun `the side street a meter ahead at 10 m s is out of reach`() {
        val r = sideStreetTurnRound()
        val ahead = NavSession.firstTurnAheadM(r, pt(13.0, 0.0))!!
        assertEquals(1.0, ahead, 1.5)
        assertTrue(NavSession.turnOutOfReach(ahead, 10.0))
    }

    @Test fun `a car already past the corner reads as level with it`() {
        val ahead = NavSession.firstTurnAheadM(sideStreetTurnRound(), pt(23.0, 0.0))!!
        assertEquals(0.0, ahead, 0.5)
        assertTrue(NavSession.turnOutOfReach(ahead, 10.0))
    }

    @Test fun `the same turn from far enough back, or from a crawl, is makeable`() {
        assertFalse(NavSession.turnOutOfReach(60.0, 10.0))
        // 47 m at 10 m/s: the first line comes after "Rerouting", 3 s before the turn. Too close.
        assertTrue(NavSession.turnOutOfReach(47.0, 10.0))
        // A crawl or a stop: no heading to trust, any turn can be made.
        assertFalse(NavSession.turnOutOfReach(1.0, 3.0))
        // At 5 m/s the floor applies: 25 m.
        assertTrue(NavSession.turnOutOfReach(24.0, 5.0))
        assertFalse(NavSession.turnOutOfReach(26.0, 5.0))
        // No turn found: nothing to judge.
        assertFalse(NavSession.turnOutOfReach(null, 10.0))
    }

    @Test fun `the ask again waits until the car is past the turn`() {
        // 33 m ahead at 10 m/s: 4.3 s to be 10 m past it.
        assertEquals(4_300L, NavSession.lateTurnWaitMs(33.0, 10.0))
        // The longest wait, a turn just inside the 25 m floor at 5 m/s, fits an urgent deadline.
        assertTrue(NavSession.lateTurnWaitMs(24.9, 5.0) < 7_000L)
    }

    @Test fun `a slight bend is skipped and the U-turn after it counts`() {
        val ahead = NavSession.firstTurnAheadM(sideStreetTurnRound(ManeuverType.SLIGHT_LEFT), pt(0.0, 0.0))!!
        assertEquals(34.0, ahead, 1.5)
    }

    @Test fun `a car beside the line is not judged`() {
        assertNull(NavSession.firstTurnAheadM(sideStreetTurnRound(), pt(0.0, -120.0)))
    }
}
