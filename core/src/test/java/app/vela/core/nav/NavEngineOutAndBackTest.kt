package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.distanceTo
import org.junit.Assert.assertTrue
import org.junit.Test

/** A road driven out and back: east 600 m to a roundabout, round it, and west again down the
 *  same centerline (a U-turn by roundabout). Progress must go round the ring, not jump to the
 *  way back while still on the way in. */
class NavEngineOutAndBackTest {
    private fun p(dLat: Double, dLng: Double) = LatLng(38.54 + dLat, -121.74 + dLng)
    private val out = (0..12).map { p(0.0, it * 0.000575) }                       // 50 m apart, east
    private val ring = listOf(p(0.0002, 0.0071), p(0.0004, 0.0074), p(0.0002, 0.0077), p(-0.0002, 0.0077), p(-0.0004, 0.0074), p(-0.0002, 0.0071))
    private val back = (12 downTo 0).map { p(0.0, it * 0.000575) } + listOf(p(-0.005, 0.0))
    private val line = out + ring + back
    private fun man(type: ManeuverType, at: LatLng, d: Double) = Maneuver(type, type.name, at, d, d / 10)
    private val total = line.zipWithNext { a, b -> a.distanceTo(b) }.sum()
    private val route = Route(
        polyline = line,
        legs = listOf(RouteLeg(total, total / 10, null, listOf(
            man(ManeuverType.DEPART, line.first(), 600.0),
            man(ManeuverType.ROUNDABOUT, out.last(), 200.0),
            man(ManeuverType.EXIT_ROUNDABOUT, back.first(), 600.0),
            man(ManeuverType.TURN_LEFT, p(0.0, 0.0), total - 1400.0),
            man(ManeuverType.ARRIVE, line.last(), 0.0),
        ))),
        distanceMeters = total, durationSeconds = total / 10, durationInTrafficSeconds = null,
    )

    @Test fun progressGoesRoundTheRing() {
        val cum = NavEngine.cumulative(line)
        var state = NavState()
        var at = 0.0
        var last = 0.0
        while (at <= total - 100.0) { // short of the end: arriving stops the count
            val fix = RouteProjection.pointAt(line, cum, at)
            state = NavEngine.update(route, state, fix, true).first
            assertTrue("at $at m the engine reads ${state.traveledM}", kotlin.math.abs(state.traveledM - at) < 40.0)
            assertTrue(state.traveledM >= last - 1.0)
            last = state.traveledM
            at += 20.0
        }
    }
}
