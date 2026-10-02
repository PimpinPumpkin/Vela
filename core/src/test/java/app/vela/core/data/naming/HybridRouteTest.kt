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

    @Test fun aLaneBesideTheStreetIsADifferentWay() {
        // Google drives an aisle about 18 m east of the open route's street for 300 m.
        val aisle = listOf(p(0.0, 0.0), p(0.001, 0.0002), p(0.0037, 0.0002), p(0.0047, 0.0), p(0.04, 0.0))
        assertEquals(1, HybridRoute.stretches(aisle, openLine).size)
    }

    @Test fun twoShortDeparturesCloseTogetherAreOne() {
        // Off for 70 m, across the open route, off again for 70 m: one stretch, not none.
        val weave = listOf(p(0.0, 0.0), p(0.0002, 0.0003), p(0.0008, 0.0003), p(0.001, -0.0003), p(0.0016, -0.0003), p(0.0018, 0.0), p(0.04, 0.0))
        assertEquals(1, HybridRoute.stretches(weave, openLine).size)
    }

    @Test fun twoTurnsOfOneSourceAFewMetersApartBothStay() {
        val s = HybridRoute.stretches(googleLine, openLine).single()
        val len = s.toM - s.fromM
        val named = listOf(
            man(ManeuverType.DEPART, p(0.01, 0.0), 100.0),
            man(ManeuverType.TURN_RIGHT, p(0.01, 0.0), 20.0),
            man(ManeuverType.TURN_LEFT, p(0.01, 0.0002), len - 120.0, "G Street"),
            man(ManeuverType.ARRIVE, p(0.02, 0.0), 0.0),
        )
        val r = HybridRoute.stitch(google, open, listOf(s to named))
        assertNotNull(r)
        assertTrue("the left onto the street survives: ${r!!.maneuvers.map { it.type to it.road }}", r.maneuvers.any { it.road == "G Street" })
        assertTrue(r.maneuvers.any { it.type == ManeuverType.TURN_RIGHT })
    }

    @Test fun theSameRoadDrawnALaneOverIsNot() {
        val lane = listOf(p(0.0, 0.0), p(0.001, 0.00008), p(0.039, 0.00008), p(0.04, 0.0)) // about 7 m
        assertTrue(HybridRoute.stretches(lane, openLine).isEmpty())
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

    @Test fun anOpenExitThatGoogleDoesNotTakeIsNeverReadOut() {
        // The open router leaves the shared road on a slow fork: its line peels off at 1.1 km and
        // is only 25 m away some 300 m later. Google goes straight on.
        val straight = listOf(p(0.0, 0.0), p(0.04, 0.0))
        val forked = listOf(p(0.0, 0.0), p(0.01, 0.0), p(0.013, 0.0003), p(0.02, 0.004), p(0.04, 0.004))
        val openForked = route(
            forked,
            listOf(
                man(ManeuverType.DEPART, p(0.0, 0.0), 1110.0, "First St"),
                man(ManeuverType.RAMP_RIGHT, p(0.01, 0.0), 3500.0, "Exit 12"),
                man(ManeuverType.ARRIVE, p(0.04, 0.004), 0.0),
            ),
            RouteSource.OSRM,
        )
        val g = route(straight, emptyList(), RouteSource.GOOGLE_NAMED)
        val st = HybridRoute.stretches(straight, forked)
        assertTrue(st.isNotEmpty())
        // The stretch reaches back toward the fork, well before where the lines are 25 m apart.
        assertTrue("from ${st[0].fromM}", st[0].fromM <= 1200.0)
        val named = st.map { it to listOf(man(ManeuverType.DEPART, p(0.0, 0.0), it.toM - it.fromM), man(ManeuverType.ARRIVE, p(0.04, 0.0), 0.0)) }
        val out = HybridRoute.stitch(g, openForked, named)!!
        assertTrue(out.maneuvers.none { it.road == "Exit 12" })
    }

    @Test fun aTurnBothMakeBeforeGoogleLeavesIsCoveredByTheStretch() {
        // Both turn right at 550 m; Google leaves that street 220 m later, the open route does not.
        val openL = listOf(p(0.0, 0.0), p(0.005, 0.0), p(0.005, 0.03))
        val googleL = listOf(p(0.0, 0.0), p(0.005, 0.0), p(0.005, 0.0025), p(0.02, 0.0025), p(0.02, 0.03))
        val o = route(
            openL,
            listOf(man(ManeuverType.DEPART, p(0.0, 0.0), 555.0, "First St"), man(ManeuverType.TURN_RIGHT, p(0.005, 0.0), 2600.0, "Pine St"), man(ManeuverType.ARRIVE, p(0.005, 0.03), 0.0)),
            RouteSource.OSRM,
        )
        val st = HybridRoute.stretchesFor(googleL, o)
        assertTrue("the shared right turn is inside a stretch: $st", st.any { 555.0 >= it.fromM && 555.0 <= it.toM })
    }

    @Test fun aRampNamedTwiceIsOneStep() {
        val s = HybridRoute.stretches(googleLine, openLine).single()
        val named = listOf(
            man(ManeuverType.DEPART, p(0.0, 0.0), 100.0),
            man(ManeuverType.RAMP_RIGHT, p(0.01, 0.0), 40.0, "Big Highway"),
            man(ManeuverType.RAMP_RIGHT, p(0.0101, 0.0003), 1500.0, "Big Highway"),
            man(ManeuverType.ARRIVE, p(0.021, 0.0), 0.0),
        )
        val out = HybridRoute.stitch(google, open, listOf(s to named))!!
        assertEquals(1, out.maneuvers.count { it.road == "Big Highway" })
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
