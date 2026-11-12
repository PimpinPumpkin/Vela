package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reroute's route that takes the car back the way it came turns it round ([RouteGeometry.goesBack]). */
class TurnBackTest {
    // Davis fixture: W 8th St runs east-west at this latitude.
    private val lat = 38.55073
    private val lng = -121.75211
    private val mLat = 111_320.0
    private val mLng = mLat * Math.cos(Math.toRadians(lat))

    /** A point [east] and [north] meters from the car. */
    private fun p(east: Double, north: Double) = LatLng(lat + north / mLat, lng + east / mLng)

    /** The car's track: 300 m driven east along 8th St, ending where it is. */
    private val track = (0..60).map { p(-300.0 + it * 5.0, 0.0) }

    private fun route(poly: List<LatLng>, seconds: Double): Route {
        val ms = listOf(Maneuver(ManeuverType.DEPART, "Head out", poly.first(), 100.0, 0.0), Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0))
        return Route(poly, listOf(RouteLeg(1000.0, seconds, null, ms)), 1000.0, seconds, null)
    }

    @Test fun `back to the missed street turns the car round`() {
        // A recorded answer, a car going east just past Oak Avenue: 56 m back west, then right up
        // Oak. Google's line had four vertices, the last a kilometer north.
        val back = listOf(p(0.0, 0.0), LatLng(38.55073, -121.75255), LatLng(38.55073, -121.75275), LatLng(38.56036, -121.75272))
        assertTrue(RouteGeometry.goesBack(back, track))
        assertTrue("read 80 m along the line, not at its next vertex", RouteGeometry.startsAgainst(back, 90.0))
    }

    @Test fun `the way on does not`() {
        // The open router's answer at the same moment: on east, then left up Douglass Avenue.
        val on = listOf(p(0.0, 0.0), LatLng(38.55073, -121.75154), LatLng(38.55072, -121.75145), LatLng(38.55303, -121.75141))
        assertFalse(RouteGeometry.goesBack(on, track))
        assertFalse(RouteGeometry.startsAgainst(on, 90.0))
    }

    @Test fun `a U-turn to the street just passed backs up`() {
        // A recorded answer, a car 15 m past the turn it missed: back to it, then up it.
        val uturn = listOf(p(0.0, 0.0), p(-15.0, 0.0), p(-15.0, 400.0))
        assertTrue(RouteGeometry.startsBehind(uturn, 90.0))
        assertFalse("too short a run back for the track test", RouteGeometry.goesBack(uturn, track))
        assertFalse("and it reads as sideways at 80 m", RouteGeometry.startsAgainst(uturn, 90.0))
        // The car a few meters into the junction, turning there: not backing up.
        assertFalse(RouteGeometry.startsBehind(listOf(p(0.0, 0.0), p(-5.0, 0.0), p(-5.0, 400.0)), 90.0))
        assertFalse(RouteGeometry.startsBehind(listOf(p(0.0, 0.0), p(5.0, 0.0), p(5.0, -400.0)), 90.0))
    }

    @Test fun `a U-turn onto the other side of a divided road turns round`() {
        val uturn = listOf(p(0.0, 0.0), p(30.0, 0.0), p(30.0, 20.0), p(-300.0, 20.0))
        assertTrue(RouteGeometry.goesBack(uturn, track))
    }

    @Test fun `round a small block and back along the road turns round`() {
        // Left 12 m, right 18 m, right 12 m, then back west along the road just driven.
        val loop = listOf(p(0.0, 0.0), p(10.0, 0.0), p(10.0, 12.0), p(28.0, 12.0), p(28.0, 0.0), p(-300.0, 0.0))
        assertTrue(RouteGeometry.goesBack(loop, track))
        assertFalse("it starts the car's way, which is how it got past the old test", RouteGeometry.startsAgainst(loop, 90.0))
    }

    @Test fun `a loop ramp does not`() {
        // A cloverleaf: off to the right, 270 degrees round, and away north over the road.
        val r = 50.0
        val ramp = ArrayList<LatLng>()
        ramp += p(0.0, 0.0); ramp += p(40.0, 0.0)
        for (k in 0..54) {
            val a = Math.toRadians(90.0 - k * 5.0) // clockwise from the top of a circle south of the road
            ramp += p(40.0 + r * Math.cos(a), -r + r * Math.sin(a))
        }
        ramp += p(40.0 - r, 300.0)
        assertFalse(RouteGeometry.goesBack(ramp, track))
        assertFalse(RouteGeometry.startsBehind(ramp, 90.0))
    }

    @Test fun `a turn off the road at once is not going back`() {
        val right = listOf(p(0.0, 0.0), p(5.0, 0.0), p(5.0, -400.0))
        assertFalse(RouteGeometry.goesBack(right, track))
    }

    @Test fun `a forward alternate of the open router leads while it costs little more`() {
        val back = route(listOf(p(0.0, 0.0), p(10.0, 0.0), p(10.0, 12.0), p(28.0, 12.0), p(28.0, 0.0), p(-300.0, 0.0)), 300.0)
        val on = route(listOf(p(0.0, 0.0), p(60.0, 0.0), p(60.0, 400.0)), 380.0)
        val turns = { x: Route -> RouteGeometry.goesBack(x.polyline, track) }
        val (google, open) = RouteGeometry.forwardPick(emptyList(), listOf(back, on), turns)
        assertTrue(google.isEmpty())
        assertSame(on, open.first())
        assertEquals(2, open.size)
        // Ten minutes more is not little: the way back stays first.
        val far = route(on.polyline, 900.0)
        assertSame(back, RouteGeometry.forwardPick(emptyList(), listOf(back, far), turns).second.first())
    }

    @Test fun `when the open route turns round too, Google's best is not set aside for it`() {
        val oBack = route(listOf(p(0.0, 0.0), p(30.0, 0.0), p(30.0, 20.0), p(-300.0, 20.0)), 300.0)
        val gBack = route(listOf(p(0.0, 0.0), p(-300.0, 0.0)), 240.0)
        val turns = { x: Route -> RouteGeometry.goesBack(x.polyline, track) || RouteGeometry.startsAgainst(x.polyline, 90.0) }
        val (google, _) = RouteGeometry.forwardPick(listOf(gBack), listOf(oBack), turns)
        assertSame(gBack, google.single())
        // One that goes on, for little more, is taken over it.
        val gOn = route(listOf(p(0.0, 0.0), p(60.0, 0.0), p(60.0, 400.0)), 300.0)
        assertSame(gOn, RouteGeometry.forwardPick(listOf(gBack, gOn), listOf(oBack), turns).first.single())
    }

    @Test fun `round a block beats turning round where the car is`() {
        val now = { x: Route -> RouteGeometry.startsAgainst(x.polyline, 90.0) || RouteGeometry.startsBehind(x.polyline, 90.0) }
        val turns = { x: Route -> now(x) || RouteGeometry.goesBack(x.polyline, track) }
        // Google: back west at once. The open router: right, right and back west along the road.
        val gHere = route(listOf(p(0.0, 0.0), p(-300.0, 0.0), p(-300.0, 400.0)), 240.0)
        val oBlock = route(listOf(p(0.0, 0.0), p(60.0, 0.0), p(60.0, -80.0), p(-40.0, -80.0), p(-40.0, 0.0), p(-300.0, 0.0), p(-300.0, 400.0)), 250.0)
        assertEquals(2, RouteGeometry.turnRank(gHere, turns, now))
        assertEquals(1, RouteGeometry.turnRank(oBlock, turns, now))
        val (google, open) = RouteGeometry.forwardPick(listOf(gHere), listOf(oBlock), turns, now)
        assertTrue("Google's turn on the spot is set aside", google.isEmpty())
        assertSame(oBlock, open.first())
        // A Google alternate round a block is taken before the open router's.
        val gBlock = route(oBlock.polyline, 270.0)
        assertSame(gBlock, RouteGeometry.forwardPick(listOf(gHere, gBlock), listOf(oBlock), turns, now).first.single())
        // One that goes on beats both.
        val gOn = route(listOf(p(0.0, 0.0), p(60.0, 0.0), p(60.0, 400.0)), 300.0)
        assertSame(gOn, RouteGeometry.forwardPick(listOf(gHere, gBlock, gOn), listOf(oBlock), turns, now).first.single())
        // Minutes more round the block is not little: Google's way stands.
        val far = route(oBlock.polyline, 900.0)
        assertSame(gHere, RouteGeometry.forwardPick(listOf(gHere), listOf(far), turns, now).first.first())
        // Without a test for where the car is, a route that turns round is one rank, as before.
        assertSame(gHere, RouteGeometry.forwardPick(listOf(gHere), listOf(oBlock), turns).first.single())
    }

    @Test fun `one router's answers alone lead with the one that turns round least`() {
        val now = { x: Route -> RouteGeometry.startsAgainst(x.polyline, 90.0) || RouteGeometry.startsBehind(x.polyline, 90.0) }
        val turns = { x: Route -> now(x) || RouteGeometry.goesBack(x.polyline, track) }
        val rank = { x: Route -> RouteGeometry.turnRank(x, turns, now) }
        val here = route(listOf(p(0.0, 0.0), p(-300.0, 0.0), p(-300.0, 400.0)), 240.0)
        val block = route(listOf(p(0.0, 0.0), p(60.0, 0.0), p(60.0, -80.0), p(-40.0, -80.0), p(-40.0, 0.0), p(-300.0, 0.0), p(-300.0, 400.0)), 280.0)
        val on = route(listOf(p(0.0, 0.0), p(60.0, 0.0), p(60.0, 400.0)), 330.0)
        assertSame(on, RouteGeometry.leastTurning(listOf(here, block, on), rank).first())
        assertSame(block, RouteGeometry.leastTurning(listOf(here, block), rank).first())
        assertEquals(2, RouteGeometry.leastTurning(listOf(here, block), rank).size)
        // The best that goes on is left as it is, and so is one whose way on costs minutes more.
        val first = listOf(on, here)
        assertSame(first, RouteGeometry.leastTurning(first, rank))
        val far = route(on.polyline, 900.0)
        assertSame(here, RouteGeometry.leastTurning(listOf(here, far), rank).first())
    }

    @Test fun `after its own U-turn the way on along the road is not turning round`() {
        // Recorded on Covell Blvd in Davis, a divided road: the car drove east past its turn and
        // took the median opening. The reroute from the crossover sent it west to the turn.
        val drove = listOf(
            LatLng(38.56074, -121.75087), LatLng(38.56074, -121.75049), LatLng(38.56073, -121.75011), LatLng(38.56072, -121.74973),
            LatLng(38.56072, -121.74935), LatLng(38.56071, -121.74897), LatLng(38.56071, -121.74859), LatLng(38.56071, -121.74822),
            LatLng(38.56070, -121.74784), LatLng(38.56070, -121.74748), LatLng(38.56078, -121.74744),
        )
        val west = listOf(
            LatLng(38.56080, -121.74749), LatLng(38.56083, -121.74982), LatLng(38.56088, -121.75013), LatLng(38.56090, -121.75021),
            LatLng(38.56094, -121.75028), LatLng(38.56102, -121.75037), LatLng(38.56119, -121.75044), LatLng(38.56178, -121.75043),
        )
        assertTrue("against the whole track it reads as going back", RouteGeometry.goesBack(west, drove))
        assertFalse(RouteGeometry.goesBack(west, RouteGeometry.sinceTurnRound(drove, 270.0)))
    }

    @Test fun `the track since a turn-round still catches going back again`() {
        // East 300 m, a U-turn across a 12 m median, then 100 m west: the car's own course is
        // read from its newest 15 m of track.
        val drove = track + listOf(p(0.0, 6.0), p(0.0, 12.0)) + (1..20).map { p(-it * 5.0, 12.0) }
        val since = RouteGeometry.sinceTurnRound(drove)
        assertTrue("the eastbound stretch is dropped", since.size < 25)
        assertFalse("on west", RouteGeometry.goesBack(listOf(p(-100.0, 12.0), p(-500.0, 12.0)), since))
        assertTrue("back east along it", RouteGeometry.goesBack(listOf(p(-100.0, 12.0), p(300.0, 12.0)), since))
    }

    @Test fun `a corner is not a turn-round`() {
        // East 300 m, then left and 60 m north: the whole track stays.
        val drove = track + (1..12).map { p(0.0, it * 5.0) }
        assertEquals(drove.size, RouteGeometry.sinceTurnRound(drove).size)
        assertEquals(drove.size, RouteGeometry.sinceTurnRound(drove, 0.0).size)
    }
}
