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
 * A ferry leg on a walk or a ride says so (issue #824). The open foot router marks the leg with
 * mode "ferry", the bicycle router with the intersection class "ferry" and mode "pushing bike";
 * both reach Vela as a "notification" step named after the ferry, which read "Turn straight onto
 * <ferry name>" and looked like a walk across the river.
 */
class FerryStepTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private fun east(m: Double) = LatLng(lat, lng0 + m / (111_320.0 * Math.cos(Math.toRadians(lat))))

    private fun step(type: String, mod: String, at: LatLng, dist: Double, mode: String, name: String = "Elm St", classes: String = "") =
        """{"maneuver":{"type":"$type","modifier":"$mod","location":[${at.lng},${at.lat}]},"name":"$name","mode":"$mode","distance":$dist,"duration":60.0,"intersections":[{"classes":[$classes]}]}"""

    private fun route(walkMode: String, ferryMode: String, ferryClasses: String): List<Pair<ManeuverType, String>> {
        val poly = (0..3).map { east(it * 400.0) }
        val json = """{"distance":1200.0,"duration":180.0,"geometry":"${PolylineCodec.encode(poly, 6)}","legs":[{"steps":[
            ${step("depart", "right", poly[0], 400.0, walkMode)},
            ${step("notification", "straight", poly[1], 400.0, ferryMode, "River Ferry", ferryClasses)},
            ${step("notification", "straight", poly[2], 400.0, walkMode, "Elm St")},
            ${step("arrive", "left", poly[3], 0.0, walkMode)}]}]}"""
        return RouteGeometry.parseOsrmRoute(Json.parseToJsonElement(json).jsonObject)!!.maneuvers.map { it.type to it.instruction }
    }

    @Test fun `a walk across a ferry says to take the ferry`() {
        val steps = route("walking", "ferry", "")
        assertEquals(ManeuverType.STRAIGHT to "Take the ferry", steps[1])
        // Leaving the ferry is no turn: a silent continue, folded into the ferry step.
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.STRAIGHT, ManeuverType.ARRIVE), steps.map { it.first })
    }

    @Test fun `a ride across a ferry says to take the ferry`() {
        val steps = route("cycling", "pushing bike", "\"ferry\"")
        assertEquals(ManeuverType.STRAIGHT to "Take the ferry", steps[1])
    }

    @Test fun `pushing the bike on a footway is not a ferry`() {
        val steps = route("cycling", "pushing bike", "")
        assertEquals(listOf(ManeuverType.DEPART, ManeuverType.ARRIVE), steps.map { it.first })
        assertEquals("Continue onto River Ferry", RouteGeometry.osrmPhrase("notification", "straight", "River Ferry", null, null, null))
    }
    // The online bicycle router is Valhalla: its ferry entry (type 28) was folded away silently.
    @Test fun `a Valhalla ferry entry says to take the ferry`() {
        val (type, mod) = app.vela.core.data.ValhallaRouter.osrmGrammar(28, "River Ferry", false)
        assertEquals("Take the ferry", RouteGeometry.osrmPhrase(type, mod, "River Ferry", null, null, null))
        assertEquals(ManeuverType.STRAIGHT, RouteGeometry.osrmType(type, mod))
        assertEquals("continue" to "straight", app.vela.core.data.ValhallaRouter.osrmGrammar(29, "Elm St", false))
    }
}
