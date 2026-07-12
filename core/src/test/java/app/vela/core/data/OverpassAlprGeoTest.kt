package app.vela.core.data

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-geometry tests for the along-route flock filter: the segment-distance near test (which fixed
 *  the vertex-only undercount) and the route tiling (which fixed the huge-bbox timeout-to-zero). */
class OverpassAlprGeoTest {

    // Two shape points ~1.5 km apart on an east-west line near lat 47; a camera sits ON the line
    // roughly halfway between them, which the old vertex-only test (distance to the nearest shape
    // point) missed because both ends are ~700 m away.
    private val a = LatLng(47.0000, -122.0000)
    private val b = LatLng(47.0000, -121.9800) // ~1.5 km east

    @Test fun cameraOnLineBetweenSparseVertices_isNear() {
        val onLineMidway = LatLng(47.00000, -121.9900) // on the segment, ~750 m from either vertex
        // sanity: it really is far from both vertices, so a vertex-only test would drop it
        assertTrue(OverpassAlprCameras.segDistMeters(onLineMidway, a, a) > 300.0)
        assertTrue(OverpassAlprCameras.segDistMeters(onLineMidway, b, b) > 300.0)
        assertTrue(OverpassAlprCameras.nearPolyline(onLineMidway, listOf(a, b), 120.0))
    }

    @Test fun cameraFarFromLine_isNotNear() {
        val wayOff = LatLng(47.0100, -121.9900) // ~1.1 km north of the line
        assertFalse(OverpassAlprCameras.nearPolyline(wayOff, listOf(a, b), 120.0))
    }

    @Test fun cameraJustOffTheSegment_respectsThreshold() {
        // ~90 m north of the segment midpoint: inside 120 m, outside 50 m.
        val near = LatLng(47.00081, -121.9900)
        assertTrue(OverpassAlprCameras.nearPolyline(near, listOf(a, b), 120.0))
        assertFalse(OverpassAlprCameras.nearPolyline(near, listOf(a, b), 50.0))
    }

    @Test fun shortRoute_isOneTile() {
        val tiles = OverpassAlprCameras.routeTiles(listOf(a, b), padDeg = 0.003, maxSpanDeg = 0.25, maxTiles = 40)
        assertEquals(1, tiles.size)
        val t = tiles[0] // [s, w, n, e], padded
        assertTrue(t[0] < 47.0 && t[2] > 47.0)
        assertTrue(t[1] < -122.0 && t[3] > -121.98)
    }

    @Test fun longRoute_splitsIntoBoundedTiles() {
        // A ~110 km diagonal run: several points a quarter-degree apart.
        val line = (0..8).map { LatLng(47.0 + it * 0.12, -122.0 + it * 0.12) }
        val tiles = OverpassAlprCameras.routeTiles(line, padDeg = 0.003, maxSpanDeg = 0.25, maxTiles = 40)
        assertTrue("expected more than one tile for a long route", tiles.size > 1)
        assertTrue("tiles capped", tiles.size <= 40)
        // Every tile stays close to the max span (plus padding on both sides).
        for (t in tiles) {
            assertTrue(t[2] - t[0] <= 0.25 + 0.007)
            assertTrue(t[3] - t[1] <= 0.25 + 0.007)
        }
        // The diagonal touches a diagonal band, not the whole rows*cols grid.
        assertTrue("diagonal shouldn't fill the grid", tiles.size < 8 * 8)
    }
}
