package app.vela.core.data.naming

import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineNamerTest {
    // A small grid at the Davis fixture: A Street runs north, Main runs east from its top end.
    private val o = LatLng(38.5400, -121.7400)
    private fun north(m: Double) = m / 111_320.0
    private fun east(m: Double) = m / (111_320.0 * Math.cos(Math.toRadians(38.54)))
    private val aStreet = NamedLine("A Street", null, "minor", listOf(o, LatLng(o.lat + north(600.0), o.lng)))
    private val main = NamedLine("Main Street", null, "secondary", listOf(LatLng(o.lat + north(400.0), o.lng - east(100.0)), LatLng(o.lat + north(400.0), o.lng + east(700.0))))

    private fun route(poly: List<LatLng>) = Route(poly, listOf(RouteLeg(1000.0, 800.0, null, emptyList())), 1000.0, 800.0, null, source = RouteSource.GOOGLE_PROVISIONAL, provisional = true)

    /** Walk north up A Street 400 m (drawn 8 m to the side of it, like a sidewalk), turn right onto Main for 500 m. */
    private fun lShape(offset: Double = 8.0): List<LatLng> {
        val pts = ArrayList<LatLng>()
        var m = 0.0
        while (m <= 400.0) { pts += LatLng(o.lat + north(m), o.lng + east(offset)); m += 20.0 }
        m = 20.0
        while (m <= 500.0) { pts += LatLng(o.lat + north(400.0 + offset), o.lng + east(offset + m)); m += 20.0 }
        return pts
    }

    @Test fun anLShapeIsDepartTurnRightArrive() {
        val r = LineNamer.name(route(lShape()), listOf(aStreet, main), TravelMode.WALK)!!
        val m = r.maneuvers
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_RIGHT, ManeuverType.ARRIVE), m.map { it.type })
        assertEquals("A Street", m[0].road)
        assertEquals("Main Street", m[1].road)
        assertTrue(m[1].instruction.contains("Main Street"))
        assertTrue(m[1].location.lat > o.lat + north(370.0))
        assertEquals(RouteSource.GOOGLE_LINE_NAMED, r.source)
        assertEquals(false, r.provisional)
        assertEquals(r.polyline, lShape()) // the line itself is never moved
    }

    @Test fun aLineAwayFromAnyNamedStreetIsNotNamed() {
        val far = lShape().map { LatLng(it.lat + north(300.0), it.lng + east(300.0)) }
        assertNull(LineNamer.name(route(far), listOf(aStreet, main), TravelMode.WALK))
    }

    @Test fun aRenameWithNoTurnIsFoldedNotAnnounced() {
        val south = NamedLine("A Street", null, "minor", listOf(o, LatLng(o.lat + north(300.0), o.lng)))
        val northPart = NamedLine("Oak Avenue", null, "minor", listOf(LatLng(o.lat + north(300.0), o.lng), LatLng(o.lat + north(800.0), o.lng)))
        val straight = (0..35).map { LatLng(o.lat + north(it * 20.0), o.lng + east(6.0)) }
        val r = LineNamer.name(route(straight), listOf(south, northPart), TravelMode.WALK)!!
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), r.maneuvers.map { it.type })
        assertEquals("Oak Avenue", r.maneuvers[0].renames.single().road)
    }

    // A street the way a tile draws it: a vertex every 25 m, running north [offset] m east of A Street.
    private fun northStreet(name: String, offset: Double) =
        NamedLine(name, null, "minor", (0..24).map { LatLng(o.lat + north(it * 25.0), o.lng + east(offset)) })
    private fun northLine(offset: Double) = (0..20).map { LatLng(o.lat + north(it * 20.0), o.lng + east(offset)) }
    private fun pieces(lines: List<NamedLine>) = lines.flatMap { l -> l.points.zipWithNext().map { (a, b) -> l.copy(points = listOf(a, b)) } }
    private fun strictRoads(line: List<LatLng>, lines: List<NamedLine>): List<Pair<ManeuverType, String?>> =
        LineNamer.name(route(line), lines, TravelMode.DRIVE, strict = true)!!.maneuvers.map { it.type to it.road }

    @Test fun strictNamingIsTheSameInAnyOrderOfTheSegments() {
        // The line runs 4 m from A Street and 14 m from B Street. B is past the match distance and
        // still about as close as A, so the map is not sure and strict naming gives no name.
        val a = northStreet("A Street", 0.0)
        val b = northStreet("B Street", 18.0)
        val unnamed = listOf<Pair<ManeuverType, String?>>(ManeuverType.DEPART to null, ManeuverType.ARRIVE to null)
        val segments = pieces(listOf(a, b))
        for (order in listOf(listOf(a, b), listOf(b, a), segments, segments.reversed())) {
            assertEquals(unnamed, strictRoads(northLine(4.0), order))
        }
    }

    @Test fun aStreetWellFartherOffLeavesTheNearOneNamed() {
        // B Street 30 m from the line is not about as close as A Street at 4 m.
        val a = northStreet("A Street", 0.0)
        val b = northStreet("B Street", 34.0)
        for (order in listOf(listOf(a, b), listOf(b, a))) {
            assertEquals(listOf(ManeuverType.DEPART to "A Street", ManeuverType.ARRIVE to null), strictRoads(northLine(4.0), order))
        }
    }

    @Test fun theOtherCarriagewayOfTheSameStreetIsNotAnotherStreet() {
        val near = northStreet("A Street", 0.0)
        val far = northStreet("A Street", 18.0)
        for (order in listOf(listOf(near, far), listOf(far, near))) {
            assertEquals("A Street", strictRoads(northLine(4.0), order).first().second)
        }
    }

    @Test fun anglesClassify() {
        assertEquals(ManeuverType.SLIGHT_LEFT, LineNamer.classify(-40.0).first)
        assertEquals(ManeuverType.TURN_RIGHT, LineNamer.classify(90.0).first)
        assertEquals(ManeuverType.SHARP_LEFT, LineNamer.classify(-150.0).first)
        assertEquals(ManeuverType.UTURN, LineNamer.classify(175.0).first)
    }
}
