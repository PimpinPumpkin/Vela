package app.vela.core.replay

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Publishing a recorded drive must not publish where its author lives.
 *
 * A trip leaks its endpoints in five different places, not one, and a scrubber that closes only
 * the obvious one is worse than none — it hands over a file its owner has been told is safe.
 * Most of the tests here are one of those five.
 *
 * Fixtures are the repo's standard Davis / Sacramento box (see the location-hygiene note in
 * CLAUDE.md): a straight eastward run along a line of latitude, ~28 m between fixes.
 */
class TripScrubTest {

    private val originLat = 38.5449
    private val originLng = -121.7405
    private val step = 0.00032 // ~28 m of longitude at this latitude

    /** A synthetic drive of [n] fixes heading east from the "home" origin. */
    private fun trip(
        n: Int = 200,
        label: String = "1451 W Covell Blvd",
    ): String = buildString {
        val poly = (0 until n).map { LatLng(originLat, originLng + step * it) }
        val dest = poly.last()
        append("META,$label,1756700000000,${dest.lat},${dest.lng},2770\n")
        append(
            TripLog.encodeRoute(
                Route(
                    poly,
                    listOf(
                        RouteLeg(
                            5000.0, 300.0, null,
                            listOf(
                                Maneuver(ManeuverType.DEPART, "Head east on Sesame Street", poly.first(), 100.0, 0.0),
                                Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Midpoint Road", poly[n / 2], 100.0, 0.0),
                                Maneuver(ManeuverType.ARRIVE, "Arrive at 1451 W Covell Blvd", poly.last(), 0.0, 0.0),
                            ),
                        ),
                    ),
                    5000.0, 300.0, null,
                ),
                "start",
            ),
        )
        append("S,1756700000000,Head east on Sesame Street\n")
        append("S,1756700100000,Turn right onto Midpoint Road\n")
        append("S,1756700199000,Arriving at 1451 W Covell Blvd\n")
        append("B,1756700100000,84\n")
        for (i in 0 until n) {
            append("$originLat,${originLng + step * i},${1756700000000L + i * 1000},90,15,0,4.0\n")
        }
    }

    private fun scrub(csv: String = trip(), radius: Double = 400.0) = TripScrub.scrub(csv, radiusM = radius)

    @Test fun `a drive ended early keeps the part of the route it drove`() {
        // A 400-vertex route of which only the first 100 fixes were driven: the zone round the last
        // fix cuts the route in two, and the longer piece is the part never driven.
        val n = 400
        val poly = (0 until n).map { LatLng(originLat, originLng + step * it) }
        val csv = buildString {
            append("META,somewhere,1756700000000,${poly.last().lat},${poly.last().lng},2770\n")
            append(TripLog.encodeRoute(Route(poly, listOf(RouteLeg(11000.0, 600.0, null, emptyList())), 11000.0, 600.0, null), "start"))
            for (k in 0 until 100) append("$originLat,${originLng + step * k},${1756700000000L + k * 1000},90,15,0,4.0\n")
        }
        val r = scrub(csv)!!
        val line = r.csv.lines().first { it.startsWith("RP,") }
        val kept = app.vela.core.data.google.PolylineCodec.decode(line.substring(3))
        val lastFixLng = originLng + step * 99
        assertTrue("the kept route is the driven stretch", kept.all { it.lng < lastFixLng })
        assertTrue("and most of it", kept.size > 50)
    }

    @Test fun `the ends of the drive are gone entirely`() {
        val r = scrub()!!
        assertTrue("something must survive", r.fixesAfter > 0)
        assertTrue("fixes must have been removed", r.fixesRemoved > 0)
        assertTrue("start trimmed by roughly the radius", r.trimmedStartM > 350.0)
        assertTrue("end trimmed by roughly the radius", r.trimmedEndM > 350.0)
        val pts = TripLog.parsePoints(r.csv.split('\n'))
        val home = LatLng(originLat, originLng)
        assertTrue("no surviving fix may be near home", pts.none { dist(it.latLng, home) <= 400.0 })
    }

    @Test fun `the destination coordinate is stripped from the header`() {
        assertNull(TripLog.parse(scrub()!!.csv).dest)
    }

    @Test fun `the label is stripped, because trips are named after where they went`() {
        val r = scrub()!!
        assertTrue("the address must not survive in the label", !r.csv.contains("1451 W Covell"))
        assertEquals("Shared trip", TripLog.parse(r.csv).label)
    }

    @Test fun `spoken lines naming the destination are dropped`() {
        val r = scrub()!!
        assertTrue("the arrival announcement names the address", !r.csv.contains("Arriving at"))
        assertTrue("the departure street is named too", !r.csv.contains("Sesame Street"))
        assertTrue("but the middle of the drive survives", r.csv.contains("Midpoint Road"))
        assertTrue(r.spokenDropped >= 2)
    }

    @Test fun `maneuvers at either end are dropped, the middle one is kept`() {
        val r = scrub()!!
        assertEquals(2, r.maneuversDropped)
        val route = TripLog.parseRoute(r.csv.split('\n'))
        assertNotNull(route)
        assertTrue(route!!.maneuvers.none { it.instruction.contains("1451 W Covell") })
    }

    @Test fun `the route line no longer starts at the door`() {
        val r = scrub()!!
        assertTrue(r.routeTrimmed)
        val poly = TripLog.parseRoute(r.csv.split('\n'))!!.polyline
        val home = LatLng(originLat, originLng)
        assertTrue("route must not enter the trim zone", poly.none { dist(it, home) <= 400.0 })
        assertTrue("...and must still be a usable line", poly.size >= 2)
    }

    @Test fun `timestamps are rebased so the file does not say when its author drives`() {
        val r = scrub()!!
        assertEquals(0L, TripLog.parse(r.csv).startedAt)
        val pts = TripLog.parsePoints(r.csv.split('\n'))
        assertEquals("first surviving fix sits at zero", 0L, pts.first().t)
        assertTrue("later fixes keep their spacing", pts.last().t > 0)
        assertTrue("no absolute wall-clock survives", !r.csv.contains("17567000"))
    }

    @Test fun `the scrubbed trip still parses and still replays`() {
        val p = TripLog.parse(scrub()!!.csv)
        assertTrue("fixes survive", p.points.size > 10)
        assertNotNull("a route survives", p.route)
        val gaps = p.points.zipWithNext { a, b -> b.t - a.t }
        assertTrue("all gaps stay positive and sane", gaps.all { it in 1..5000 })
    }

    @Test fun `the build that recorded the trip is preserved`() {
        assertTrue("versionCode identifies nobody and is diagnosis-critical", scrub()!!.csv.contains(",2770"))
    }

    @Test fun `a drive entirely inside the radius is refused, not silently emptied`() {
        assertNull(scrub(trip(n = 10), radius = 400.0))
    }

    @Test fun `a trip with no fixes is refused`() {
        assertNull(TripScrub.scrub("META,x,0,,,\n"))
    }

    @Test fun `notes from the trimmed ends are kept, with their own times`() {
        // What the drive decided and what it ran on says nothing about a place, and the start of
        // a drive is where its route is planned and named.
        val csv = trip() +
            "K,1756700000500,recheck: kept current route (named 1 of 12 turns)\n" +
            "J,1756700001000,60,2,18\n" +
            "K,1756700199500,arrived (trip 199s)\n"
        val r = TripScrub.scrub(csv)!!
        val events = TripLog.parse(r.csv).events
        val first = events.first { it.text.startsWith("recheck: kept") }
        assertTrue("a note from before the first surviving fix has a time before zero", first.t < 0)
        assertTrue(events.any { it.tag == "J" && it.t < 0 })
        val lastFix = TripLog.parsePoints(r.csv.split('\n')).last().t
        assertTrue("and one from after the last", events.first { it.text.startsWith("arrived") }.t > lastFix)
        assertTrue("no absolute wall-clock survives", !r.csv.contains("17567000"))
    }

    @Test fun `a spoken line from a trimmed end still goes`() {
        val r = scrub()!!
        assertTrue(!r.csv.contains("Sesame Street"))
        assertTrue(!r.csv.contains("Arriving at"))
    }

    @Test fun `the totals line says how many turns are named and how the steps were made`() {
        val poly = (0 until 200).map { LatLng(originLat, originLng + step * it) }
        val route = Route(
            poly,
            listOf(
                RouteLeg(
                    5000.0, 300.0, null,
                    listOf(
                        Maneuver(ManeuverType.DEPART, "Head east", poly.first(), 100.0, 0.0),
                        Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Midpoint Road", poly[100], 100.0, 0.0, road = "Midpoint Road"),
                        Maneuver(ManeuverType.TURN_LEFT, "Turn left", poly[150], 100.0, 0.0),
                        // A ramp is named by its sign and is not a plain turn: not counted.
                        Maneuver(ManeuverType.RAMP_RIGHT, "Take the ramp toward Sacramento", poly[180], 50.0, 0.0),
                        Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
                    ),
                ),
            ),
            5000.0, 300.0, null,
            made = "stretches=1;bare=1;naming=5501ms;namingLate",
        )
        assertEquals(2, route.turns)
        assertEquals(1, route.namedTurns)
        val rd = TripLog.encodeRoute(route, "start").split('\n').first { it.startsWith("RD,") }
        assertTrue(rd, rd.endsWith("steps=5;named=1of2;stretches=1;bare=1;naming=5501ms;namingLate"))
        assertEquals("the flags stay one field", 6, rd.split(',').size)
    }

    @Test fun `an unknown line kind is dropped rather than published`() {
        val csv = trip().replace("B,1756700100000,84\n", "B,1756700100000,84\nZZ,1756700100000,something new\n")
        val r = TripScrub.scrub(csv)!!
        assertTrue("a tag this scrubber predates must not pass through", !r.csv.contains("something new"))
        assertTrue(r.otherLinesDropped >= 1)
    }

    @Test fun `an extra private place is trimmed too`() {
        val mid = LatLng(originLat, originLng + step * 100)
        val plain = TripScrub.scrub(trip())!!
        val withWork = TripScrub.scrub(trip(), extraZones = listOf(mid))!!
        assertTrue("naming a third place must remove more", withWork.fixesAfter < plain.fixesAfter)
        assertTrue(TripLog.parsePoints(withWork.csv.split('\n')).none { dist(it.latLng, mid) <= 400.0 })
    }

    @Test fun `a spoken line inside a mid-trip private zone goes with its fixes`() {
        // A zone in the MIDDLE of the drive (a Work address passed on the way) deletes fixes
        // there; the spoken line at that moment is inside the kept window by time but names the
        // street at that zone, so it must go too.
        val n = 200
        val mid = LatLng(originLat, originLng + step * (n / 2))
        val csv = trip(n) + "S,${1756700000000L + (n / 2) * 1000},Passing the office on Zone Street\n"
        val r = TripScrub.scrub(csv, radiusM = 400.0, extraZones = listOf(mid))!!
        assertTrue(!r.csv.contains("Zone Street"))
        assertTrue("a spoken line beside a surviving fix stays", r.csv.contains("Midpoint Road") || r.spokenDropped >= 3)
    }

    @Test fun `a route block trimmed to nothing takes its totals and maneuvers with it`() {
        // A short second segment entirely inside the destination zone: its RP is dropped, so its
        // RD and M lines must not survive to be folded into the previous block by the parser.
        val n = 200
        val tail = (0 until 3).map { LatLng(originLat, originLng + step * (n - 3 + it)) }
        val csv = trip(n) + TripLog.encodeRoute(
            Route(
                tail,
                listOf(
                    RouteLeg(
                        50.0, 10.0, null,
                        listOf(Maneuver(ManeuverType.ARRIVE, "Arrive at the door on Door Lane", tail.last(), 0.0, 0.0)),
                    ),
                ),
                50.0, 10.0, null,
            ),
            "reroute",
        )
        val r = scrub(csv)!!
        assertTrue(!r.csv.contains("Door Lane"))
        val lines = r.csv.split('\n')
        assertEquals(lines.count { it.startsWith("RP,") }, lines.count { it.startsWith("RD,") })
    }

    private fun dist(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lng - a.lng)
        val h = Math.sin(dp / 2) * Math.sin(dp / 2) +
            Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }
}
