package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.distanceTo
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

    /** The destination's approach line and the line on arriving are prepared, with and without a
     *  known side. */
    @Test
    fun theArrivalLinesArePrepared() {
        for (side in listOf(null, "right")) for (speed in listOf(5.0, 14.0)) {
            val ms = route.maneuvers.toMutableList()
            ms[ms.lastIndex] = ms.last().copy(side = side)
            val r = route.copy(legs = listOf(route.legs.single().copy(maneuvers = ms)))
            var st = NavState()
            var predicted = NavEngine.upcomingPrompts(r, 1, emptySet(), speed, imperial = true)
            val said = ArrayList<String>()
            for (p in poly) {
                val (s2, ev) = NavEngine.update(r, st, p, imperial = true, speedMps = speed)
                for (line in ev.filterIsInstance<NavEvent.Speak>().map { it.text }) {
                    said += line
                    assertTrue("side $side at $speed m/s not predicted: \"$line\" (had: $predicted)", line in predicted)
                }
                st = s2
                if (st.arrived) break
                predicted = NavEngine.upcomingPrompts(r, st.stepIndex, st.spoken, speed, imperial = true)
            }
            assertTrue("never arrived: $said", st.arrived)
            assertTrue("no approach line for the destination: $said", said.any { "destination will be" in it.lowercase() })
        }
    }

    /** Points along [line] [stepM] meters apart: a fix a second at [stepM] m/s. */
    private fun walk(line: List<LatLng>, stepM: Double): List<LatLng> {
        val out = ArrayList<LatLng>()
        var carry = 0.0
        for (k in 0 until line.size - 1) {
            val a = line[k]
            val b = line[k + 1]
            val len = a.distanceTo(b)
            var t = carry
            while (t < len) {
                val f = t / len
                out += LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
                t += stepM
            }
            carry = t - len
        }
        return out + line.last()
    }

    /** A turn too soon after the one before for its approach bands says its first line at the
     *  real distance ("In 900 feet"). A roundabout is never another turn's "then", so the step
     *  before one is where that happens mid-drive. That line is among those prepared before the car
     *  reaches the turn before it, so it has the whole approach to that turn to be rendered. */
    @Test
    fun aShortStepsFirstLineIsPreparedBeforeTheTurnBeforeIt() {
        for (gap in listOf(200.0, 280.0, 330.0)) for (speed in listOf(5.0, 10.0, 14.0)) {
            val r = closeTurns(gap).let { r ->
                val ms = r.maneuvers.map { it.copy(lightAtTurn = false, stopAtTurn = false) }.toMutableList()
                ms[2] = ms[2].copy(type = ManeuverType.ROUNDABOUT, instruction = "At the roundabout, take the 1st exit onto Oak Avenue", instructionNoRoad = "At the roundabout, take the 1st exit")
                r.copy(legs = listOf(r.legs.single().copy(maneuvers = ms)))
            }
            var st = NavState()
            val early = LinkedHashSet<String>() // predicted on the first turn's step, short of its line at the turn
            var firstRing: String? = null
            for (p in walk(r.polyline, speed).dropLast(5)) {
                val (s2, ev) = NavEngine.update(r, st, p, imperial = true, speedMps = speed)
                if (firstRing == null) firstRing = ev.filterIsInstance<NavEvent.Speak>().map { it.text }.firstOrNull { "roundabout" in it }
                st = s2
                if (st.stepIndex == 1 && st.distanceToNextManeuver > 40.0) {
                    early += NavEngine.upcomingPrompts(r, st.stepIndex, st.spoken, speed, imperial = true, toTurnM = st.distanceToNextManeuver)
                }
            }
            val ring = firstRing!!
            assertTrue("gap $gap at $speed m/s: \"$ring\" not prepared early (had: $early)", ring in early)
        }
    }

    /** A drive that starts close to its first turn says that turn's first line at once, at the
     *  real distance: it is among the lines prepared while the route is previewed. */
    @Test
    fun aFirstTurnCloseToTheStartIsPrepared() {
        for (startM in listOf(60.0, 120.0, 200.0, 300.0)) {
            val p = (0..(startM / 10).toInt()).map { at(it * 10.0, 0.0) } + (1..80).map { at(startM, it * 10.0) } + (1..30).map { at(startM + it * 10.0, 800.0) }
            val r = Route(
                p,
                listOf(
                    RouteLeg(
                        startM + 1100.0, 110.0, null,
                        listOf(
                            Maneuver(ManeuverType.DEPART, "Head east on 1st Street", p.first(), startM, startM / 10, road = "1st Street"),
                            Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto E Street", at(startM, 0.0), 800.0, 80.0, road = "E Street"),
                            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto 5th Street", at(startM, 800.0), 300.0, 30.0, road = "5th Street"),
                            Maneuver(ManeuverType.ARRIVE, "Arrive", p.last(), 0.0, 0.0),
                        ),
                    ),
                ),
                startM + 1100.0, 110.0, null,
            )
            val prepared = NavEngine.startPrompts(r, imperial = true)
            var st = NavState()
            var first: String? = null
            for (q in walk(p, 1.5)) { // pulling away
                val (s2, ev) = NavEngine.update(r, st, q, imperial = true, speedMps = 1.5)
                st = s2
                first = ev.filterIsInstance<NavEvent.Speak>().map { it.text }.firstOrNull()
                if (first != null) break
            }
            assertTrue("$startM m out: \"$first\" not prepared (had: $prepared)", first in prepared)
        }
    }

    /** A turn said as the "then" of the line at the turn before it has nothing more of its own
     *  prepared than update will say: no approach lines, from the first fix on its step. */
    @Test
    fun aTurnAlreadySaidAsAThenGetsNoApproachLinesPrepared() {
        val r = closeTurns(80.0).let { r ->
            r.copy(legs = listOf(r.legs.single().copy(maneuvers = r.maneuvers.map { it.copy(lightAtTurn = false, stopAtTurn = false) })))
        }
        var st = NavState()
        var checked = false
        for (p in walk(r.polyline, 10.0)) {
            val (s2, _) = NavEngine.update(r, st, p, imperial = true, speedMps = 10.0)
            st = s2
            if (st.stepIndex == 2 && st.chainedStep == 2 && !checked) {
                checked = true
                val ready = NavEngine.upcomingPrompts(r, 2, st.spoken, 10.0, imperial = true, toTurnM = st.distanceToNextManeuver, chainedStep = st.chainedStep, chainedStepBands = st.chainedBands)
                assertTrue("approach lines prepared for a turn already said: $ready", ready.none { it.startsWith("In ") && "Oak Avenue" in it })
            }
        }
        assertTrue("the second turn was never said as a then", checked)
    }
}
