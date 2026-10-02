package app.vela.core.data.naming

import app.vela.core.model.LatLng
import app.vela.core.model.Lane
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRouteTest {
    // The Davis fixture corner; 0.001 deg of latitude is about 111 m, of longitude about 87 m.
    private fun p(dLat: Double, dLng: Double) = LatLng(38.54 + dLat, -121.74 + dLng)

    // The open route: north 4.4 km, with a left turn at 3.3 km (kept going north for the test's
    // purposes; only the maneuver matters).
    private val openLine = listOf(p(0.0, 0.0), p(0.04, 0.0))
    // Google: the same, except it steps one block east between 1.1 km and 2.2 km.
    private val googleLine = listOf(p(0.0, 0.0), p(0.01, 0.0), p(0.01, 0.003), p(0.02, 0.003), p(0.02, 0.0), p(0.04, 0.0))

    private fun man(type: ManeuverType, at: LatLng, dist: Double, road: String? = null, lanes: List<Lane> = emptyList()) =
        Maneuver(type, type.name, at, dist, dist / 10, road = road, lanes = lanes)

    private fun route(line: List<LatLng>, ms: List<Maneuver>, source: RouteSource) = Route(
        polyline = line, legs = listOf(RouteLeg(4500.0, 450.0, 500.0, ms)), distanceMeters = 4500.0,
        durationSeconds = 450.0, durationInTrafficSeconds = 500.0, source = source,
    )

    private val lanes = listOf(Lane(listOf("left"), true), Lane(listOf("straight"), false))
    private val open = route(
        openLine,
        listOf(
            man(ManeuverType.DEPART, p(0.0, 0.0), 3330.0, "First St"),
            man(ManeuverType.TURN_LEFT, p(0.03, 0.0), 1110.0, "Oak Ave", lanes),
            man(ManeuverType.ARRIVE, p(0.04, 0.0), 0.0),
        ),
        RouteSource.OSRM,
    )
    private val google = route(googleLine, emptyList(), RouteSource.GOOGLE_NAMED)

    @Test fun findsTheBlockWhereGoogleStepsAside() {
        val s = HybridRoute.stretches(googleLine, openLine)
        assertEquals(1, s.size)
        // The jog starts 1.1 km along and is about 1.6 km of line; padded 90 m each side.
        assertTrue("from ${s[0].fromM}", s[0].fromM in 950.0..1150.0)
        assertTrue("to ${s[0].toM}", s[0].toM in 2600.0..2900.0)
    }

    @Test fun theSameWayHasNoStretches() {
        assertTrue(HybridRoute.stretches(openLine, openLine).isEmpty())
    }

    @Test fun stitchKeepsTheOpenTurnsOutsideAndTheNamedTurnsInside() {
        val s = HybridRoute.stretches(googleLine, openLine).single()
        val len = s.toM - s.fromM
        // What LineNamer would return for the slice: its own depart, four turns, its own arrive.
        val named = listOf(
            man(ManeuverType.DEPART, p(0.0, 0.0), 100.0),
            man(ManeuverType.TURN_RIGHT, p(0.01, 0.0), 260.0, "Elm St"),
            man(ManeuverType.TURN_LEFT, p(0.01, 0.003), 1110.0, "Second St"),
            man(ManeuverType.TURN_LEFT, p(0.02, 0.003), 260.0, "Pine St"),
            man(ManeuverType.TURN_RIGHT, p(0.02, 0.0), len - 1730.0, "First St"),
            man(ManeuverType.ARRIVE, p(0.021, 0.0), 0.0),
        )
        val out = HybridRoute.stitch(google, open, listOf(s to named))
        assertNotNull(out)
        val types = out!!.maneuvers.map { it.type }
        assertEquals(
            listOf(
                ManeuverType.DEPART, ManeuverType.TURN_RIGHT, ManeuverType.TURN_LEFT, ManeuverType.TURN_LEFT,
                ManeuverType.TURN_RIGHT, ManeuverType.TURN_LEFT, ManeuverType.ARRIVE,
            ),
            types,
        )
        assertEquals(RouteSource.GOOGLE_HYBRID, out.source)
        assertEquals(googleLine, out.polyline)
        // The open router's turn kept its lanes; the named ones have none.
        assertEquals(lanes, out.maneuvers[5].lanes)
        assertEquals("Elm St", out.maneuvers[1].road)
        // The step lengths tile Google's line.
        val total = out.maneuvers.sumOf { it.distanceMeters }
        assertEquals(4960.0, total, 80.0)
        assertEquals(0.0, out.maneuvers.last().distanceMeters, 0.0)
    }

    @Test fun anOpenTurnInsideTheStretchIsDropped() {
        // The open router turns at 1.6 km, in the block Google went around: that turn is not Google's.
        val open2 = open.copy(
            legs = listOf(
                RouteLeg(4500.0, 450.0, 500.0, listOf(
                    man(ManeuverType.DEPART, p(0.0, 0.0), 1600.0, "First St"),
                    man(ManeuverType.TURN_RIGHT, p(0.0145, 0.0), 2800.0, "Closed Rd"),
                    man(ManeuverType.ARRIVE, p(0.04, 0.0), 0.0),
                )),
            ),
        )
        val s = HybridRoute.stretches(googleLine, openLine).single()
        val named = listOf(
            man(ManeuverType.DEPART, p(0.0, 0.0), 100.0),
            man(ManeuverType.TURN_RIGHT, p(0.01, 0.0), 1000.0, "Elm St"),
            man(ManeuverType.ARRIVE, p(0.021, 0.0), 0.0),
        )
        val out = HybridRoute.stitch(google, open2, listOf(s to named))!!
        assertTrue(out.maneuvers.none { it.road == "Closed Rd" })
    }

    @Test fun nothingNamedMeansNoHybrid() {
        assertNull(HybridRoute.stitch(google, open, emptyList()))
    }

    @Test fun sliceCutsTheLineBetweenTwoDistances() {
        val s = HybridRoute.slice(openLine, 1000.0, 2000.0)
        assertEquals(2, s.size)
        assertTrue(s.first().lat > 38.548 && s.first().lat < 38.5495)
    }
}
