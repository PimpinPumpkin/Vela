package app.vela.core.data

import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** One turn split in two by a turn lane mapped as its own way. */
class TurnLaneFoldTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * cos(Math.toRadians(lat))
    private fun pt(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / mPerDegLng)

    @Before fun english() = NavStringsRegistry.setLocale(Locale.US)

    /** East 300 m, a lane [laneLen] m long [peelDeg] off to the right, then [turnDeg] from east
     *  for 300 m. Steps: depart, an unnamed slight right, a slight right onto Elm Street. */
    private fun case(peelDeg: Double, turnDeg: Double, laneLen: Double = 90.0, laneName: String? = null): Pair<List<LatLng>, List<Maneuver>> {
        fun go(from: Pair<Double, Double>, brg: Double, len: Double) =
            from.first + len * sin(Math.toRadians(brg)) to from.second + len * cos(Math.toRadians(brg))
        val a = 300.0 to 0.0
        val b = go(a, 90.0 + peelDeg, laneLen)
        val c = go(b, 90.0 + turnDeg, 300.0)
        val line = listOf(pt(0.0, 0.0), pt(a.first, a.second), pt(b.first, b.second), pt(c.first, c.second))
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", line[0], 300.0, 30.0),
            Maneuver(ManeuverType.SLIGHT_RIGHT, if (laneName == null) "Turn slight right" else "Turn slight right onto $laneName", line[1], laneLen, 9.0, road = laneName),
            Maneuver(ManeuverType.SLIGHT_RIGHT, "Turn slight right onto Elm Street", line[2], 300.0, 30.0, road = "Elm Street"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", line[3], 0.0, 0.0),
        )
        return line to ms
    }

    @Test fun `a lane peeling off before a right turn folds into one right turn`() {
        val (line, ms) = case(peelDeg = 25.0, turnDeg = 90.0)
        val out = RouteGeometry.foldTurnLanes(line, ms)
        assertEquals(3, out.size)
        val t = out[1]
        assertEquals(ManeuverType.TURN_RIGHT, t.type)
        assertEquals("Turn right onto Elm Street", t.instruction)
        assertEquals("Turn right", t.instructionNoRoad)
        assertEquals(ms[1].location, t.location)
        assertEquals(390.0, t.distanceMeters, 1e-9)
        assertEquals(39.0, t.durationSeconds, 1e-9)
        // The steps still tile the line.
        assertEquals(ms.sumOf { it.distanceMeters }, out.sumOf { it.distanceMeters }, 1e-9)
    }

    @Test fun `two real turns stay two`() {
        // A first step that is a real slight turn, not a lane peeling off.
        assertEquals(4, RouteGeometry.foldTurnLanes(case(peelDeg = 50.0, turnDeg = 110.0).first, case(peelDeg = 50.0, turnDeg = 110.0).second).size)
        // Two gentle bends that add up to less than a turn.
        assertEquals(4, RouteGeometry.foldTurnLanes(case(peelDeg = 20.0, turnDeg = 40.0).first, case(peelDeg = 20.0, turnDeg = 40.0).second).size)
        // A first step onto a road of its own.
        val named = case(peelDeg = 25.0, turnDeg = 90.0, laneName = "Oak Avenue")
        assertEquals(4, RouteGeometry.foldTurnLanes(named.first, named.second).size)
        // A first step longer than a turn lane.
        val long = case(peelDeg = 25.0, turnDeg = 90.0, laneLen = 200.0)
        assertEquals(4, RouteGeometry.foldTurnLanes(long.first, long.second).size)
    }
}
