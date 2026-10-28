package app.vela.core.nav

import app.vela.core.data.RouteGeometry
import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** A fork on an exit ramp is said, not folded into the exit. */
class ExitForkTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private fun pt(e: Double) = LatLng(lat0, lng0 + e / mPerDegLng)

    @Before fun english() = NavStringsRegistry.setLocale(Locale.US)

    // Due east: the highway, the exit, its fork [forkM] on, a merge, a turn, the arrival.
    private fun route(forkM: Double): Route {
        val total = 4000.0
        val poly = (0..400).map { pt(it * 10.0) }
        val raw = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", pt(0.0), 1500.0, 60.0),
            Maneuver(ManeuverType.RAMP_RIGHT, "Take the exit toward Downtown", pt(1500.0), forkM, 10.0),
            Maneuver(ManeuverType.FORK_LEFT, "Keep left toward Downtown", pt(1500.0 + forkM), 100.0, 5.0),
            Maneuver(ManeuverType.MERGE, "Merge onto Oak Street", pt(1600.0 + forkM), 900.0 - forkM, 40.0),
            Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Elm Street", pt(2500.0), total - 2500.0, 60.0),
            Maneuver(ManeuverType.ARRIVE, "You have arrived", pt(total), 0.0, 0.0),
        )
        val ms = RouteGeometry.consolidateExits(raw)
        return Route(poly, listOf(RouteLeg(total, 175.0, null, ms)), total, 175.0, null)
    }

    private fun said(r: Route): List<String> {
        var st = NavState()
        val out = ArrayList<String>()
        for (m in generateSequence(0.0) { it + 10.0 }.takeWhile { it <= 4000.0 }) {
            val (s, ev) = NavEngine.update(r, st, pt(m), speedMps = 20.0, bearingDeg = 90.0)
            st = s
            ev.filterIsInstance<NavEvent.Speak>().forEach { out += it.text }
            if (st.arrived) break
        }
        return out
    }

    @Test fun `a fork just past the exit rides on the exit's line`() {
        val lines = said(route(forkM = 60.0))
        assertTrue(lines.toString(), lines.any { "Take the exit toward Downtown" in it && "then keep left" in it.lowercase() })
    }

    @Test fun `a fork further down the ramp gets its own line`() {
        val lines = said(route(forkM = 300.0))
        assertTrue(lines.toString(), lines.any { "Keep left toward Downtown" in it })
        assertTrue(lines.toString(), lines.any { "Take the exit toward Downtown" in it })
    }
}
