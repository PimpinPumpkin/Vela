package app.vela.core

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.nav.NavEngine
import app.vela.core.nav.NavEvent
import app.vela.core.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for the 2026-07-04 navigation audit remediation. Everything here mirrors a concrete
 * field failure:
 *  - fixed 400/150/25 m prompt distances gave a 75 mph driver 12 s of warning (speed-scaling);
 *  - a short fresh step fired BOTH prompt bands back-to-back announcing the literal threshold
 *    ("In 400 meters" for a turn 50 m away);
 *  - a GPS gap replayed every missed turn as an at-the-turn command, one per fix;
 *  - parking 30-50 m short of the road-snapped destination never arrived, then "Rerouting"
 *    fired in the parking lot;
 *  - red-light multipath drift >45 m for 4 fixes rerouted a provably stationary car;
 *  - ETA divided remaining distance by the WHOLE-ROUTE average speed (downtown tail read 4 min
 *    when it was 15).
 */
class NavAuditRegressionTest {

    private val lng = -122.0000
    private fun latAt(m: Double) = 37.0 + m / 111_195.0 // haversine metres per degree at R=6371 km
    private fun at(m: Double) = LatLng(latAt(m), lng)

    /** Straight-north polyline with a vertex every 50 m up to [totalM]. */
    private fun line(totalM: Double) = (0..(totalM / 50.0).toInt()).map { at(it * 50.0) }

    private fun route(totalM: Double, maneuvers: List<Maneuver>, durationSeconds: Double = totalM / 13.0): Route =
        Route(
            polyline = line(totalM),
            legs = listOf(RouteLeg(totalM, durationSeconds, null, maneuvers)),
            distanceMeters = totalM,
            durationSeconds = durationSeconds,
            durationInTrafficSeconds = null,
        )

    private fun turn(atM: Double, afterM: Double, name: String, dur: Double = afterM / 13.0) =
        Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto $name", at(atM), afterM, dur, road = name)

    @Test fun `prompt distances scale with speed`() {
        // Exit 900 m ahead at highway speed: fixed 400 m gave 12 s of warning; at 34 m/s the far
        // band is ~850 m, so the prompt must fire HERE — and must NOT fire at city speed.
        val r = route(
            3000.0,
            listOf(
                Maneuver(ManeuverType.DEPART, "Head north", at(0.0), 2000.0, 80.0),
                turn(2000.0, 1000.0, "Exit Rd"),
                Maneuver(ManeuverType.ARRIVE, "Arrive", at(3000.0), 0.0, 0.0),
            ),
        )
        val state = NavState(stepIndex = 1, traveledM = 1100.0)
        val loc = at(1160.0) // dtn ≈ 840 m
        val (_, slowEvents) = NavEngine.update(r, state, loc, speedMps = 13.0) // city: far band = 400
        assertTrue("no prompt at 840 m at city speed", slowEvents.none { it is NavEvent.Speak })
        val (_, fastEvents) = NavEngine.update(r, state, loc, speedMps = 34.0) // highway: far ≈ 850
        assertTrue(
            "prompt fires at 840 m at highway speed",
            fastEvents.filterIsInstance<NavEvent.Speak>().any { it.text.contains("Turn right onto Exit Rd") },
        )
    }

    @Test fun `a short fresh step speaks ONE prompt with the true distance`() {
        // Right-then-immediate-left, 60 m apart: the old loop fired BOTH bands in one update,
        // each announcing its literal threshold — "In 400 meters… In 150 meters…" for a turn
        // 50 m away, queued back-to-back.
        val r = route(
            1000.0,
            listOf(
                Maneuver(ManeuverType.DEPART, "Head north", at(0.0), 300.0, 23.0),
                turn(300.0, 60.0, "First St"),
                turn(360.0, 640.0, "Second St"),
                Maneuver(ManeuverType.ARRIVE, "Arrive", at(1000.0), 0.0, 0.0),
            ),
        )
        val state = NavState(stepIndex = 2, traveledM = 305.0) // just turned onto First
        val (next, events) = NavEngine.update(r, state, at(310.0)) // dtn(Second) ≈ 50 m
        val speaks = events.filterIsInstance<NavEvent.Speak>()
        assertEquals("exactly one prompt for a short step", 1, speaks.size)
        assertFalse("never announces a distance larger than the truth", speaks[0].text.contains("400"))
        assertTrue("speaks the real ~50 m", speaks[0].text.contains("50 meters"))
        assertEquals("both bands consumed — no second prompt next fix", setOf(0, 1), next.spoken)
    }

    @Test fun `maneuvers passed during a GPS gap advance silently`() {
        val r = route(
            1200.0,
            listOf(
                Maneuver(ManeuverType.DEPART, "Head north", at(0.0), 300.0, 23.0),
                turn(300.0, 300.0, "First St"),
                turn(600.0, 300.0, "Second St"),
                turn(900.0, 300.0, "Third St"),
                Maneuver(ManeuverType.ARRIVE, "Arrive", at(1200.0), 0.0, 0.0),
            ),
        )
        // Tunnel: last seen before First St, next fix lands past Third St's approach.
        val state = NavState(stepIndex = 1, traveledM = 100.0)
        val (next, events) = NavEngine.update(r, state, at(950.0))
        val spokenTexts = events.filterIsInstance<NavEvent.Speak>().map { it.text }
        assertTrue("First St never replayed", spokenTexts.none { it.contains("First St") })
        assertTrue("Second St never replayed", spokenTexts.none { it.contains("Second St") })
        assertTrue("caught up past the passed maneuvers", next.stepIndex >= 3)
    }

    @Test fun `parking short of the snapped destination arrives by proximity`() {
        val r = route(
            1000.0,
            listOf(
                Maneuver(ManeuverType.DEPART, "Head north", at(0.0), 1000.0, 77.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", at(1000.0), 0.0, 0.0),
            ),
        )
        // Parked 35 m crow-flies from the snapped endpoint (dtn along-route ~35 > 25): the old
        // engine never arrived here — and then off-route counting "Rerouted" in the parking lot.
        val state = NavState(stepIndex = 1, traveledM = 960.0)
        val (next, events) = NavEngine.update(r, state, at(965.0), speedMps = 0.4)
        assertTrue("arrives on proximity", next.arrived)
        assertTrue(events.any { it is NavEvent.Arrived })
    }

    @Test fun `stationary off-route drift never reroutes, moving does`() {
        val r = route(
            2000.0,
            listOf(
                Maneuver(ManeuverType.DEPART, "Head north", at(0.0), 2000.0, 154.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", at(2000.0), 0.0, 0.0),
            ),
        )
        val off = LatLng(latAt(500.0), lng + 0.0009) // ~80 m east of the line
        // Red light: multipath biases fixes 80 m toward a parallel street while the doppler
        // proves the car is parked — no reroute, ever.
        var state = NavState(stepIndex = 0, traveledM = 500.0)
        repeat(8) {
            val (next, events) = NavEngine.update(r, state, off, speedMps = 0.3)
            assertTrue("stationary drift must not reroute", events.none { it is NavEvent.RerouteNeeded })
            state = next
        }
        // Same drift while MOVING is a genuine exit → reroutes after the debounce.
        var moving = NavState(stepIndex = 0, traveledM = 500.0)
        var rerouted = false
        repeat(5) {
            val (next, events) = NavEngine.update(r, moving, off, speedMps = 10.0)
            if (events.any { it is NavEvent.RerouteNeeded }) rerouted = true
            moving = next
        }
        assertTrue("moving off-route reroutes", rerouted)
    }

    @Test fun `remaining time comes from the remaining steps, not the whole-route average`() {
        // 1 km fast (40 s @ 25 m/s) + 1 km slow (200 s @ 5 m/s): halfway down the FAST leg the
        // remaining time is ~20 s + 200 s. The old remaining/avgSpeed said 1500 m / 8.33 m/s = 180 s.
        val r = route(
            2000.0,
            listOf(
                Maneuver(ManeuverType.DEPART, "Head north", at(0.0), 1000.0, 40.0),
                turn(1000.0, 1000.0, "Slow St", dur = 200.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", at(2000.0), 0.0, 0.0),
            ),
            durationSeconds = 240.0,
        )
        val state = NavState(stepIndex = 1, traveledM = 400.0)
        val (next, _) = NavEngine.update(r, state, at(500.0))
        assertTrue(
            "ETA reflects the slow tail (~220 s), got ${next.remainingDuration}",
            next.remainingDuration in 195.0..245.0,
        )
    }

    @Test fun `arrival gets a single approach cue`() {
        val r = route(
            1000.0,
            listOf(
                Maneuver(ManeuverType.DEPART, "Head north", at(0.0), 1000.0, 77.0),
                Maneuver(ManeuverType.ARRIVE, "Arrive", at(1000.0), 0.0, 0.0),
            ),
        )
        // 120 m out (inside the near band): "your destination will be ahead" — the old engine
        // excluded ARRIVE from prompts entirely (silence from the last turn to the arrival line).
        val state = NavState(stepIndex = 1, traveledM = 850.0)
        val (next, events) = NavEngine.update(r, state, at(880.0))
        assertTrue(
            events.filterIsInstance<NavEvent.Speak>().any { it.text.contains("Your destination will be ahead") },
        )
        assertFalse("not arrived yet at 120 m", next.arrived)
    }
}
