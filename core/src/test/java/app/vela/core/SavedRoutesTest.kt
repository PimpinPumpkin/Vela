package app.vela.core

import app.vela.core.model.LatLng
import app.vela.core.model.SavedRoute
import app.vela.core.model.TravelMode
import app.vela.core.nav.SavedRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedRoutesTest {
    // A grid in the Davis fixture box: 0.001 deg of latitude is about 111 m.
    private fun p(dLat: Double, dLng: Double) = LatLng(38.54 + dLat, -121.74 + dLng)

    // The router's way: straight north 2.2 km.
    private val fastest = listOf(p(0.0, 0.0), p(0.02, 0.0))
    // The saved way: jogs one block (~260 m) east for the middle kilometre, then back.
    private val saved = listOf(p(0.0, 0.0), p(0.005, 0.0), p(0.005, 0.003), p(0.015, 0.003), p(0.015, 0.0), p(0.02, 0.0))

    @Test fun oneViaInTheMiddleOfTheStretchThatDiffers() {
        val vias = SavedRoutes.viasAgainst(saved, fastest)
        assertEquals(1, vias.size)
        val v = vias.single()
        assertEquals(0.003, v.lng - (-121.74), 1e-4) // on the jog, not at a junction
        assertTrue(v.lat - 38.54 in 0.008..0.012)
    }

    @Test fun theSameWayNeedsNoVias() {
        assertTrue(SavedRoutes.viasAgainst(fastest, fastest).isEmpty())
        assertTrue(SavedRoutes.sameWay(fastest, fastest))
        assertFalse(SavedRoutes.sameWay(saved, fastest))
    }

    @Test fun aLongDifferentStretchGetsMoreThanOneVia() {
        val long = listOf(p(0.0, 0.0), p(0.0, 0.005), p(0.08, 0.005), p(0.08, 0.0))
        val direct = listOf(p(0.0, 0.0), p(0.08, 0.0))
        val vias = SavedRoutes.viasAgainst(long, direct)
        assertTrue("got ${vias.size}", vias.size >= 3)
        assertTrue(vias.size <= SavedRoutes.MAX_VIAS)
    }

    @Test fun aRunLimitKeepsTheBlockDetourAndDropsTheOtherRoad() {
        // The one-block jog (about 1.1 km off the direct line) is a local detour.
        assertEquals(1, SavedRoutes.viasAgainst(saved, fastest, maxRunM = 3_000.0).size)
        // A different road for nearly 9 km is not.
        val long = listOf(p(0.0, 0.0), p(0.0, 0.005), p(0.08, 0.005), p(0.08, 0.0))
        val direct = listOf(p(0.0, 0.0), p(0.08, 0.0))
        assertTrue(SavedRoutes.viasAgainst(long, direct, maxRunM = 3_000.0).isEmpty())
    }

    @Test fun aOneJunctionWobbleIsNotAWay() {
        val wobble = listOf(p(0.0, 0.0), p(0.01, 0.0), p(0.0105, 0.0009), p(0.011, 0.0), p(0.02, 0.0))
        assertTrue(SavedRoutes.viasAgainst(wobble, fastest).isEmpty())
    }

    @Test fun offeredOnlyForTheSameTrip() {
        val r = SavedRoute("a", "Commute", "DRIVE", 38.54, -121.74, 38.56, -121.74, "Work", "", 0)
        assertTrue(SavedRoutes.matches(r, p(0.005, 0.0), p(0.0205, 0.0), TravelMode.DRIVE)) // 555 m from the start, 55 m from the end
        assertFalse(SavedRoutes.matches(r, p(0.0, 0.0), p(0.0205, 0.0), TravelMode.BICYCLE))
        assertFalse(SavedRoutes.matches(r, p(0.0, 0.0), p(0.03, 0.0), TravelMode.DRIVE))
        assertFalse(SavedRoutes.matches(r, p(-0.02, 0.0), p(0.02, 0.0), TravelMode.DRIVE))
    }

    @Test fun aRunIsNeverOfferedAsAnAlternate() {
        val run = SavedRoute("b", "Milk run", "DRIVE", 38.54, -121.74, 38.56, -121.74, "Depot", "", 0,
            stops = listOf(app.vela.core.model.SavedStop("A", 38.55, -121.74)))
        assertTrue(run.isRun)
        assertFalse(SavedRoutes.matches(run, p(0.0, 0.0), p(0.02, 0.0), TravelMode.DRIVE))
    }

    @Test fun aDriveThatLeftThePlannedRouteIsOffered() {
        assertTrue(SavedRoutes.droveOwnWay(saved, fastest))
        assertFalse(SavedRoutes.droveOwnWay(fastest, fastest)) // drove what was planned
        val wobble = listOf(p(0.0, 0.0), p(0.01, 0.0), p(0.0105, 0.0009), p(0.011, 0.0), p(0.02, 0.0))
        assertFalse(SavedRoutes.droveOwnWay(wobble, fastest)) // one junction
        assertFalse(SavedRoutes.droveOwnWay(listOf(p(0.0, 0.0), p(0.003, 0.0)), fastest)) // too short
    }
}
