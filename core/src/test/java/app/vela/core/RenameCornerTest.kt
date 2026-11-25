package app.vela.core

import app.vela.core.data.RouteGeometry
import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two ways OSRM hides a corner: a rename read "straight" or "slight" at a node where the street
 * turns a right angle into the next one, and a nameless slip lane followed by "straight onto" the
 * street it joins. Google says "Turn left onto 2nd St" and "Turn right onto Mace Blvd" there.
 */
class RenameCornerTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val mPerLng = 111_320.0 * Math.cos(Math.toRadians(lat0))
    /** A point [e] meters east and [n] meters north of the origin. */
    private fun at(e: Double, n: Double) = LatLng(lat0 + n / 111_320.0, lng0 + e / mPerLng)

    private fun step(type: String, mod: String?, p: LatLng, dist: Double, name: String) =
        """{"maneuver":{"type":"$type",${mod?.let { "\"modifier\":\"$it\"," } ?: ""}"location":[${p.lng},${p.lat}]},"name":"$name","distance":$dist,"duration":${dist / 10},"intersections":[]}"""

    private fun parse(poly: List<LatLng>, steps: List<String>, dist: Double) = RouteGeometry.parseOsrmRoute(
        Json.parseToJsonElement("""{"distance":$dist,"duration":${dist / 10},"geometry":"${PolylineCodec.encode(poly, 6).replace("\\", "\\\\")}","legs":[{"steps":[${steps.joinToString(",")}]}]}""").jsonObject,
    )!!.maneuvers

    /** South 400 m, then a corner of [turnDeg] to the left, then on 400 m. */
    private fun corner(turnDeg: Double): List<LatLng> {
        val pts = ArrayList<LatLng>()
        for (i in 0..40) pts += at(0.0, 400.0 - i * 10.0) // heading south from (0, 400) to (0, 0)
        val h = Math.toRadians(180.0 - turnDeg) // compass heading after turning left from south
        for (i in 1..40) pts += at(Math.sin(h) * i * 10.0, Math.cos(h) * i * 10.0)
        return pts
    }

    @Test fun `a rename round a right-angle corner is a turn onto the new street`() {
        val poly = corner(90.0)
        val ms = parse(poly, listOf(
            step("depart", null, poly.first(), 400.0, "L Street"),
            step("new name", "slight left", poly[40], 400.0, "2nd Street"),
            step("arrive", null, poly.last(), 0.0, "2nd Street"),
        ), 800.0)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_LEFT, ManeuverType.ARRIVE), ms.map { it.type })
        assertEquals("Turn left onto 2nd Street", ms[1].instruction)
        assertEquals(800.0, ms.sumOf { it.distanceMeters }, 0.01)
    }

    @Test fun `a rename where the road runs on straight stays a silent rename`() {
        val poly = corner(5.0)
        val ms = parse(poly, listOf(
            step("depart", null, poly.first(), 400.0, "L Street"),
            step("new name", "slight left", poly[40], 400.0, "2nd Street"),
            step("arrive", null, poly.last(), 0.0, "2nd Street"),
        ), 800.0)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), ms.map { it.type })
        assertEquals("2nd Street", ms[0].renames.single().road)
    }

    @Test fun `a road that keeps its name round a corner is not a turn`() {
        val poly = corner(90.0)
        val ms = parse(poly, listOf(
            step("depart", null, poly.first(), 400.0, "L Street"),
            step("new name", "straight", poly[40], 400.0, "L Street"),
            step("arrive", null, poly.last(), 0.0, "L Street"),
        ), 800.0)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), ms.map { it.type })
    }

    @Test fun `a road that changes its name and curves 30 m later is not a turn`() {
        // The rename's node is 30 m before the corner.
        val poly = corner(80.0)
        val ms = parse(poly, listOf(
            step("depart", null, poly.first(), 370.0, "Underpass"),
            step("new name", "straight", poly[37], 430.0, "Parkway"),
            step("arrive", null, poly.last(), 0.0, "Parkway"),
        ), 800.0)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), ms.map { it.type })
    }

    @Test fun `a slip lane that joins the street going straight is one turn onto the street`() {
        // East 400 m, a nameless lane of 80 m bending right, then south on the street.
        val pts = ArrayList<LatLng>()
        for (i in 0..40) pts += at(i * 10.0, 0.0)
        for (i in 1..8) { val a = Math.toRadians(i * 11.25); pts += at(400.0 + Math.sin(a) * 51.0, -51.0 + Math.cos(a) * 51.0) }
        val join = pts.last()
        for (i in 1..40) pts += at(join.let { (it.lng - lng0) * mPerLng }, (join.lat - lat0) * 111_320.0 - i * 10.0)
        val laneM = 80.0
        val ms = parse(pts, listOf(
            step("depart", null, pts.first(), 400.0, "2nd Street"),
            step("turn", "slight right", pts[40], laneM, ""),
            step("turn", "straight", pts[48], 400.0, "Mace Boulevard"),
            step("arrive", null, pts.last(), 0.0, "Mace Boulevard"),
        ), 400.0 + laneM + 400.0)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_RIGHT, ManeuverType.ARRIVE), ms.map { it.type })
        assertEquals("Turn right onto Mace Boulevard", ms[1].instruction)
        assertEquals(480.0, ms[1].distanceMeters, 0.5)
    }

    @Test fun `a nameless turn with a straight step far after it stays as it is`() {
        val pts = ArrayList<LatLng>()
        for (i in 0..40) pts += at(i * 10.0, 0.0)
        for (i in 1..40) pts += at(400.0, -i * 10.0)
        val ms = parse(pts, listOf(
            step("depart", null, pts.first(), 400.0, "2nd Street"),
            step("turn", "right", pts[40], 200.0, ""),
            step("turn", "straight", pts[60], 200.0, "Mace Boulevard"),
            step("arrive", null, pts.last(), 0.0, "Mace Boulevard"),
        ), 800.0)
        assertEquals(ManeuverType.TURN_RIGHT, ms[1].type)
        assertEquals(null, ms[1].road)
    }
}
