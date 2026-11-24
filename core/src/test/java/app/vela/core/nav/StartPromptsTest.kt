package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NavEngine.startPrompts] must name the very lines [NavEngine.update] speaks for the first turns
 *  at starting speed, or the prepared audio never plays. */
class StartPromptsTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private val mPerDegLat = 111_320.0

    // East 600 m, right (south) 800 m, left (east) 300 m, arrive.
    private fun at(east: Double, south: Double) = LatLng(lat - south / mPerDegLat, lng0 + east / mPerDegLng)
    private val poly = (0..60).map { at(it * 10.0, 0.0) } + (1..80).map { at(600.0, it * 10.0) } + (1..30).map { at(600.0 + it * 10.0, 800.0) }
    private val route = Route(
        poly,
        listOf(
            RouteLeg(
                1700.0, 200.0, null,
                listOf(
                    Maneuver(ManeuverType.DEPART, "Head east on 1st Street", poly.first(), 600.0, 60.0, road = "1st Street"),
                    Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto E Street", at(600.0, 0.0), 800.0, 80.0, road = "E Street"),
                    Maneuver(ManeuverType.TURN_LEFT, "Turn left onto 5th Street", at(600.0, 800.0), 300.0, 30.0, road = "5th Street"),
                    Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
                ),
            ),
        ),
        1700.0, 200.0, null,
    )

    @Test
    fun theFirstTurnsSpokenAtStartingSpeedAreAllPrepared() {
        for (imperial in listOf(true, false)) {
            val prepared = NavEngine.startPrompts(route, imperial)
            var st = NavState()
            val spoken = ArrayList<String>()
            for (p in poly.dropLast(35)) { // through the second turn, short of the arrival
                val (s2, ev) = NavEngine.update(route, st, p, imperial = imperial, speedMps = 5.0)
                st = s2
                ev.filterIsInstance<NavEvent.Speak>().forEach { spoken += it.text }
            }
            assertTrue("spoke nothing", spoken.isNotEmpty())
            for (line in spoken) assertTrue("not prepared: \"$line\" (prepared: $prepared)", line in prepared)
        }
    }

    /** Mid-drive: every line spoken was among the lines predicted on the fix before it. */
    @Test
    fun theLinesSpokenAlongTheDriveWerePredictedTheFixBefore() {
        for (speed in listOf(5.0, 14.0)) {
            var st = NavState()
            var predicted = NavEngine.upcomingPrompts(route, 1, emptySet(), speed, imperial = true)
            var spokenN = 0
            for (p in poly.dropLast(35)) {
                val (s2, ev) = NavEngine.update(route, st, p, imperial = true, speedMps = speed)
                for (line in ev.filterIsInstance<NavEvent.Speak>().map { it.text }) {
                    spokenN++
                    assertTrue("at $speed m/s not predicted: \"$line\" (had: $predicted)", line in predicted)
                }
                st = s2
                predicted = NavEngine.upcomingPrompts(route, st.stepIndex, st.spoken, speed, imperial = true)
            }
            assertTrue("spoke nothing", spokenN >= 4)
        }
    }

    /** East 1000 m, right onto Elm Street at a light, south [gap] m, left onto Oak Avenue at a
     *  stop sign, east 600 m. */
    private fun closeTurns(gap: Double): Route {
        val p = (0..100).map { at(it * 10.0, 0.0) } + (1..(gap / 10).toInt()).map { at(1000.0, it * 10.0) } + (1..60).map { at(1000.0 + it * 10.0, gap) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", p.first(), 1000.0, 100.0),
            Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Elm Street", at(1000.0, 0.0), gap, gap / 10, road = "Elm Street", instructionNoRoad = "Turn right", lightAtTurn = true),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Oak Avenue", at(1000.0, gap), 600.0, 60.0, road = "Oak Avenue", instructionNoRoad = "Turn left", stopAtTurn = true),
            Maneuver(ManeuverType.ARRIVE, "Arrive", p.last(), 0.0, 0.0),
        )
        return Route(p, listOf(RouteLeg(1600.0 + gap, 160.0, null, ms)), 1600.0 + gap, 160.0, null)
    }

    /** Two turns said on one line ("then"), within a block or due at once, are prepared too, in
     *  the full voice and the shorter one. */
    @Test
    fun aTurnSaidInTheThenOfTheOneBeforeIsPrepared() {
        try {
            for (mode in listOf(SpokenDetail.Mode.FULL, SpokenDetail.Mode.BRIEF)) {
                SpokenDetail.mode = mode
                thenLinesPrepared(mustJoin = mode == SpokenDetail.Mode.FULL)
            }
        } finally {
            SpokenDetail.mode = SpokenDetail.Mode.FULL
        }
    }

    private fun thenLinesPrepared(mustJoin: Boolean) {
        for (gap in listOf(60.0, 80.0, 120.0, 250.0)) for (speed in listOf(5.0, 10.0, 14.0)) {
            val r = closeTurns(gap)
            var st = NavState()
            var predicted = NavEngine.upcomingPrompts(r, 1, emptySet(), speed, imperial = true)
            val joined = ArrayList<String>()
            for (p in r.polyline.dropLast(20)) {
                val (s2, ev) = NavEngine.update(r, st, p, imperial = true, speedMps = speed)
                for (line in ev.filterIsInstance<NavEvent.Speak>().map { it.text }) {
                    if (", then " in line) joined += line
                    assertTrue("${SpokenDetail.mode} gap $gap at $speed m/s not predicted: \"$line\" (had: $predicted)", line in predicted)
                }
                st = s2
                predicted = NavEngine.upcomingPrompts(r, st.stepIndex, st.spoken, speed, imperial = true)
            }
            if (mustJoin) assertTrue("gap $gap at $speed m/s said no joined line", joined.isNotEmpty())
        }
    }
}
