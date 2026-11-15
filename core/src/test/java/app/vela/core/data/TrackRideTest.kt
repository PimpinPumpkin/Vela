package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Riding a GPX track: parsing, joining matched pieces, seams, re-timing, and the route being the
 *  whole track. Fixture: Davis, a straight east-west line. */
class TrackRideTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat0))
    private fun pt(e: Double, n: Double) = LatLng(lat0 + n / 110_574.0, lng0 + e / mPerDegLng)

    @After fun reset() = TrackRide.clear()

    private fun route(from: Double, to: Double, turnAt: Double): Route {
        val poly = listOf(pt(from, 0.0), pt(turnAt, 0.0), pt(to, 0.0))
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", poly[0], turnAt - from, (turnAt - from) / 5),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto B Street", poly[1], to - turnAt, (to - turnAt) / 5, road = "B Street"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly[2], 0.0, 0.0),
        )
        val len = to - from
        return Route(polyline = poly, legs = listOf(RouteLeg(len, len / 5, null, ms)), distanceMeters = len, durationSeconds = len / 5, durationInTrafficSeconds = null)
    }

    @Test fun parsesTrackPointsBeforeRoutePoints() {
        val gpx = """<?xml version="1.0"?><gpx version="1.1"><metadata><name>Loop</name></metadata>
            <rte><rtept lat="1" lon="1"/><rtept lat="2" lon="2"/></rte>
            <trk><name>Loop</name><trkseg><trkpt lat="38.5449" lon="-121.7405"><ele>15.0</ele></trkpt>
            <trkpt lon="-121.7300" lat="38.5449"/></trkseg></trk></gpx>"""
        assertTrue(GpxTrack.looksLikeGpx(gpx))
        val (name, line) = GpxTrack.parse(gpx)
        assertEquals("Loop", name)
        assertEquals(2, line.size)
        assertEquals(-121.73, line[1].lng, 1e-9)
    }

    @Test fun routePointsWhenThereIsNoTrack() {
        val (_, line) = GpxTrack.parse("""<gpx><rte><rtept lat="38.5" lon="-121.7"/><rtept lat="38.6" lon="-121.8"/></rte></gpx>""")
        assertEquals(2, line.size)
    }

    @Test fun joinDropsTheSeamAndKeepsTheLength() {
        val a = route(0.0, 1000.0, 400.0); val b = route(1000.0, 2500.0, 1800.0)
        val j = TrackRide.join(listOf(a, b))
        assertEquals(2500.0, j.distanceMeters, 1e-6)
        val types = j.maneuvers.map { it.type }
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_LEFT, ManeuverType.TURN_LEFT, ManeuverType.ARRIVE), types)
        // The second piece's departure run (800 m) is added to the step before the seam.
        assertEquals(600.0 + 800.0, j.maneuvers[1].distanceMeters, 1e-6)
        assertEquals(j.distanceMeters, j.maneuvers.sumOf { it.distanceMeters }, 1e-6)
    }

    @Test fun theRouteIsAlwaysTheWholeTrack() = kotlinx.coroutines.runBlocking {
        // Asked from beside the track's end (or anywhere else), the answer is still the whole track:
        // Vela does no routing of its own on a track ride.
        val line = listOf(pt(0.0, 0.0), pt(2000.0, 0.0))
        val t = TrackRide.start("Line", line)!!
        t.matched = route(0.0, 2000.0, 800.0)
        val r = TrackRide.routeFor(okhttp3.OkHttpClient(), pt(2000.0, 0.0), emptyList())!!.single()
        assertEquals(2000.0, r.distanceMeters, 1e-6)
        assertTrue(TrackRide.isTrack(r))
        assertNull(TrackRide.routeFor(okhttp3.OkHttpClient(), pt(2000.0, 0.0), listOf(pt(500.0, 300.0)))) // a trip with stops is not the track
    }

    @Test fun thinDropsCloseRepeatsButKeepsTheEnd() {
        val line = listOf(pt(0.0, 0.0), pt(1.0, 0.0), pt(2.0, 0.0), pt(100.0, 0.0), pt(101.0, 0.0))
        val thin = TrackRide.thin(line)
        assertEquals(listOf(line[0], line[3], line[4]), thin)
    }

    @Test fun aTurnAtASeamIsKept() {
        // Piece one runs east, piece two north from the seam: the second departure becomes a left turn.
        val a = route(0.0, 1000.0, 400.0)
        val polyB = listOf(pt(1000.0, 0.0), pt(1000.0, 800.0))
        val b = Route(polyline = polyB, legs = listOf(RouteLeg(800.0, 160.0, null, listOf(
            Maneuver(ManeuverType.DEPART, "Head north on C Street", polyB[0], 800.0, 160.0, road = "C Street"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", polyB[1], 0.0, 0.0)))), distanceMeters = 800.0, durationSeconds = 160.0, durationInTrafficSeconds = null)
        val j = TrackRide.join(listOf(a, b))
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_LEFT, ManeuverType.TURN_LEFT, ManeuverType.ARRIVE), j.maneuvers.map { it.type })
        assertEquals("Turn left onto C Street", j.maneuvers[2].instruction)
    }

    @Test fun seamsFallOnStraightTrack() {
        // 12 km east with a right-angle jog at 4.9 km: the first seam avoids the corner.
        val line = (0..120).map { k -> pt(k * 100.0, if (k >= 49) 50.0 else 0.0) }
        val t = TrackRide.start("Long", line)!!
        val i1 = TrackRide.seamAfter(t, 0)
        val at = t.cum[i1]
        assertTrue("seam near 5 km: $at", at in 4_600.0..5_400.0)
        assertTrue("not on the jog", kotlin.math.abs(TrackRide.turnDeg(t.line[i1 - 1], t.line[i1], t.line[i1 + 1])) < 1.0)
    }

    @Test fun trackRoutesAreMarked() {
        val r = route(0.0, 1000.0, 400.0)
        assertTrue(TrackRide.isTrack(r.copy(made = "track matched 3/3 bends 0/3")))
        assertTrue(!TrackRide.isTrack(r) && !TrackRide.isTrack(null))
    }

    /** A real track against the public matcher: `-DvelaGpx=<file.gpx>` (kept out of the repo). Prints
     *  how many pieces matched and the steps; skipped without the property. */
    @Test fun liveTrack() = kotlinx.coroutines.runBlocking {
        val path = System.getProperty("velaGpx") ?: return@runBlocking
        val (name, line) = GpxTrack.parse(java.io.File(path).readText())
        val t = TrackRide.start(name ?: "track", line)!!
        val r = TrackRide.routeFor(okhttp3.OkHttpClient(), line.last(), emptyList())!!.first()
        println("live: ${t.matchNote}, ${r.maneuvers.size} steps, ${r.turns} turns, ${(r.distanceMeters / 1000).toInt()} km, track ${(t.length / 1000).toInt()} km")
        r.maneuvers.take(12).forEach { println("  ${it.type} ${it.instruction.replace(Regex("[A-Za-z]+ (Street|Road|Ave)"), "R")} ${it.distanceMeters.toInt()} m") }
        assertTrue("turn-by-turn steps", r.maneuvers.size > 2)
    }

    @Test fun walkingTimesAreRetimedForRiding() {
        // A 4 km piece matched on foot (about 5 km/h) is re-timed at riding speed: about 16 minutes, not 50.
        val poly = listOf(pt(0.0, 0.0), pt(4000.0, 0.0))
        val walk = Route(polyline = poly, legs = listOf(RouteLeg(4000.0, 2880.0, null, listOf(
            Maneuver(ManeuverType.DEPART, "Walk east", poly[0], 4000.0, 2880.0), Maneuver(ManeuverType.ARRIVE, "Arrive", poly[1], 0.0, 0.0)))),
            distanceMeters = 4000.0, durationSeconds = 2880.0, durationInTrafficSeconds = null)
        val r = TrackRide.ridden(walk)
        assertEquals(4000.0 / TrackRide.SPEED_MS, r.durationSeconds, 1.0)
        assertTrue("about 16 min: ${r.durationSeconds / 60}", r.durationSeconds / 60 in 14.0..18.0)
        assertEquals(r.durationSeconds, r.maneuvers.sumOf { it.durationSeconds }, 1.0)
    }

    @Test fun noTrackNoAnswer() = kotlinx.coroutines.runBlocking {
        assertNull(TrackRide.routeFor(okhttp3.OkHttpClient(), pt(100.0, 0.0), emptyList()))
    }
}
