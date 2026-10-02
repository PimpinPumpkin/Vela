package app.vela.core.data

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map-match reply (a live `trace_route` capture, Davis to Sacramento) and the check that a
 *  match really follows the line it was given. */
class ValhallaMatchTest {
    private val reply = javaClass.getResource("/valhalla_trace_davis_sacramento.json")!!.readText()

    @Test fun signsBecomeExitNumbersAndDestinations() {
        val r = ValhallaRouter.parse(reply).firstOrNull()
        assertNotNull(r)
        val text = r!!.maneuvers.map { it.instruction }
        assertTrue("an exit number is read out: $text", text.any { it.contains("4A") })
        assertTrue("and where the sign says it goes: $text", text.any { it.contains("toward I 5 North") })
        // A numbered road's shield comes from the names; the road keeps its real name.
        val fwy = r.maneuvers.firstOrNull { it.road == "Capital City Freeway" }
        assertNotNull("the freeway is named, not called by its number: ${r.maneuvers.map { it.road }}", fwy)
        assertEquals("US 50", fwy!!.ref)
    }

    // A straight kilometer east from the Davis fixture, one point per 100 m.
    private val line = (0..10).map { LatLng(38.5449, -121.7405 + it * 0.00115) }

    @Test fun theSameRoadDrawnTwiceFollows() {
        val lane = line.map { LatLng(it.lat + 0.00006, it.lng) } // about 7 m to one side
        assertTrue(ValhallaRouter.followsLine(lane, line))
    }

    @Test fun theNextStreetOverDoesNot() {
        val other = line.map { LatLng(it.lat + 0.0004, it.lng) } // about 44 m away
        assertFalse(ValhallaRouter.followsLine(other, line))
    }

    @Test fun aMatchThatStopsHalfwayDoesNot() {
        assertFalse(ValhallaRouter.followsLine(line.take(6), line))
    }

    @Test fun aMatchWithADetourInItDoesNot() {
        val detour = line.take(5) + listOf(LatLng(38.5460, -121.7350)) + line.drop(5)
        assertFalse(ValhallaRouter.followsLine(detour, line))
    }
}
