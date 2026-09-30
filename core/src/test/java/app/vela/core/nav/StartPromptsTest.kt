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
}
