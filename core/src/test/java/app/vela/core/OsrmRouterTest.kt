package app.vela.core

import app.vela.core.data.RouteGeometry
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The open router (OSRM) is now the PRIMARY turn-by-turn source — Google's keyless endpoint returns
 * abbreviated steps for longer routes, OSRM gives them all with street names. These cover the bits
 * with logic: mapping OSRM's `maneuver.type`+`modifier` to Vela's [ManeuverType] (arrow + haptic),
 * and synthesizing the human instruction (OSRM ships none).
 */
class OsrmRouterTest {
    @Test fun typesMapToVela() {
        assertEquals(ManeuverType.DEPART, RouteGeometry.osrmType("depart", "left"))
        assertEquals(ManeuverType.ARRIVE, RouteGeometry.osrmType("arrive", null))
        assertEquals(ManeuverType.TURN_RIGHT, RouteGeometry.osrmType("turn", "right"))
        assertEquals(ManeuverType.SLIGHT_LEFT, RouteGeometry.osrmType("turn", "slight left"))
        assertEquals(ManeuverType.STRAIGHT, RouteGeometry.osrmType("new name", "straight"))
        assertEquals(ManeuverType.ROUNDABOUT, RouteGeometry.osrmType("roundabout", "right"))
        assertEquals(ManeuverType.RAMP_RIGHT, RouteGeometry.osrmType("on ramp", "slight right"))
        assertEquals(ManeuverType.MERGE, RouteGeometry.osrmType("merge", "left"))
        assertEquals(ManeuverType.UTURN, RouteGeometry.osrmType("turn", "uturn"))
    }

    @Test fun phrasesReadNaturally() {
        assertEquals("Turn right onto the local street", RouteGeometry.osrmPhrase("turn", "right", "the local street", null))
        assertEquals("Continue onto Olive Dr", RouteGeometry.osrmPhrase("new name", "straight", "Olive Dr", null))
        assertEquals("Arrive at your destination", RouteGeometry.osrmPhrase("arrive", null, null, null))
        assertEquals("At the roundabout, take exit 2 onto Main St", RouteGeometry.osrmPhrase("roundabout", null, "Main St", 2))
        assertEquals("Head out on Elm St", RouteGeometry.osrmPhrase("depart", "left", "Elm St", null))
    }
}
