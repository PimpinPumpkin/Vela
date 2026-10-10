package app.vela.core

import app.vela.core.data.RouteGeometry
import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A step folded into another keeps its time as well as its length. The remaining-time figure is
 * the sum of the steps ahead (`NavEngine.remainingDuration`), so a dropped step's time made the
 * drive run short: a trip through one stop ran a fifth short (2026-10-10).
 */
class StepFoldTimesTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private fun east(m: Double) = LatLng(lat, lng0 + m / (111_320.0 * Math.cos(Math.toRadians(lat))))

    private fun step(type: String, mod: String, at: LatLng, dist: Double, dur: Double, name: String = "Elm St") =
        """{"maneuver":{"type":"$type","modifier":"$mod","location":[${at.lng},${at.lat}]},"name":"$name","distance":$dist,"duration":$dur,"intersections":[]}"""

    @Test fun `a via's leaving step folds its time into the step before`() {
        val poly = (0..4).map { east(it * 500.0) }
        val geometry = PolylineCodec.encode(poly, 6)
        // Two legs through a via at 1000 m: the via's ARRIVE and DEPART are dropped, and the
        // DEPART carries the 120 s from the stop to the next turn.
        val json = """{"distance":2000.0,"duration":400.0,"geometry":"$geometry","legs":[
            {"steps":[${step("depart", "straight", poly[0], 500.0, 60.0)},${step("turn", "left", poly[1], 500.0, 80.0)},${step("arrive", "straight", poly[2], 0.0, 0.0)}]},
            {"steps":[${step("depart", "straight", poly[2], 600.0, 120.0)},${step("turn", "right", east(1600.0), 400.0, 140.0)},${step("arrive", "straight", poly[4], 0.0, 0.0)}]}]}"""
        val r = RouteGeometry.parseOsrmRoute(Json.parseToJsonElement(json).jsonObject)!!
        assertEquals(400.0, r.durationSeconds, 0.0)
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.ARRIVE), r.maneuvers.map { it.type })
        assertEquals("the steps' lengths tile the line", 2000.0, r.maneuvers.sumOf { it.distanceMeters }, 0.01)
        assertEquals("the steps' times add up to the trip's", 400.0, r.maneuvers.sumOf { it.durationSeconds }, 0.01)
    }

    @Test fun `an exit complex folded into its ramp keeps the folded steps' time`() {
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", east(0.0), 1000.0, 60.0),
            Maneuver(ManeuverType.RAMP_RIGHT, "Take exit 15", east(1000.0), 200.0, 20.0),
            Maneuver(ManeuverType.FORK_RIGHT, "Keep right", east(1200.0), 150.0, 15.0),
            Maneuver(ManeuverType.MERGE, "Merge onto I 80", east(1350.0), 3000.0, 150.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", east(4350.0), 0.0, 0.0),
        )
        val out = RouteGeometry.consolidateExits(ms)
        assertEquals(3, out.size)
        assertEquals(3350.0, out[1].distanceMeters, 0.01)
        assertEquals(185.0, out[1].durationSeconds, 0.01)
    }
}
