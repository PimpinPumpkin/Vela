package app.vela.core.data

import net.osmand.binary.BinaryMapRouteReaderAdapter
import net.osmand.binary.RouteDataObject
import net.osmand.util.MapUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObfSpeedLimitMatchTest {
    private fun roadWithPoints(id: Long, coords: List<Pair<Double, Double>>): RouteDataObject {
        val r = RouteDataObject(BinaryMapRouteReaderAdapter.RouteRegion())
        r.id = id
        r.pointsX = IntArray(coords.size) { MapUtils.get31TileNumberX(coords[it].second) }
        r.pointsY = IntArray(coords.size) { MapUtils.get31TileNumberY(coords[it].first) }
        return r
    }

    @Test
    fun matchDirectionHonorsHeadingAndOneway() {
        // Northbound segment (0 deg) on a two-way road: accepts north (forward) and south (backward), rejects cross-street.
        assertEquals(true, ObfRouteEngine.matchDirection(10.0, 0.0, 0))
        assertEquals(false, ObfRouteEngine.matchDirection(185.0, 0.0, 0))
        assertNull(ObfRouteEngine.matchDirection(90.0, 0.0, 0))

        // Forward one-way road rejects driving opposite its direction.
        assertEquals(true, ObfRouteEngine.matchDirection(15.0, 0.0, 1))
        assertNull(ObfRouteEngine.matchDirection(180.0, 0.0, 1))

        // Reverse one-way road rejects forward heading and accepts reverse heading.
        assertNull(ObfRouteEngine.matchDirection(0.0, 0.0, -1))
        assertEquals(false, ObfRouteEngine.matchDirection(180.0, 0.0, -1))
    }

    @Test
    fun roadsConnected31DistinguishesJunctionFromOverpass() {
        val northA = roadWithPoints(1L, listOf(38.5500 to -121.7400, 38.5510 to -121.7400))
        val northB = roadWithPoints(2L, listOf(38.5510 to -121.7400, 38.5520 to -121.7400))
        val overpass = roadWithPoints(3L, listOf(38.5505 to -121.7410, 38.5505 to -121.7390))

        assertTrue(ObfRouteEngine.roadsConnected31(northA, northB))
        assertFalse(ObfRouteEngine.roadsConnected31(northA, overpass))
    }
}
