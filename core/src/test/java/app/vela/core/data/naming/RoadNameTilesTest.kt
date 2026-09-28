package app.vela.core.data.naming

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadNameTilesTest {
    private fun fixture(): ByteArray =
        javaClass.getResourceAsStream("/tiles/davis-14-2651-6288.pbf")!!.use { it.readBytes() }

    @Test fun decodesTheStreetNamesOfARealTile() {
        val lines = RoadNameTiles.decode(fixture(), 14, 2651, 6288)
        val names = lines.map { it.name }.toSet()
        for (n in listOf("2nd Street", "3rd Street", "B Street", "Richards Boulevard")) assertTrue("missing $n", n in names)
        // Every point lands inside the tile (Davis downtown), a few meters of buffer allowed.
        for (l in lines) for (p in l.points) {
            assertTrue(p.lat in 38.52..38.57)
            assertTrue(p.lng in -121.77..-121.72)
        }
    }

    @Test fun thirdStreetRunsEastWest() {
        val third = RoadNameTiles.decode(fixture(), 14, 2651, 6288).filter { it.name == "3rd Street" }.flatMap { it.points }
        val latSpan = third.maxOf { it.lat } - third.minOf { it.lat }
        val lngSpan = third.maxOf { it.lng } - third.minOf { it.lng }
        assertTrue(lngSpan > latSpan * 2)
    }

    @Test fun aFlyoverIsMarkedAsABridge() {
        val bytes = javaClass.getResourceAsStream("/tiles/dhaka-14-12307-7079.pbf")!!.use { it.readBytes() }
        val lines = RoadNameTiles.decode(bytes, 14, 12307, 7079)
        // Khilgaon Flyover, the deck over the street the #478 walk takes. (The Moghbazar flyover's
        // touchdown stub at ground level is rightly not a bridge.)
        val flyover = lines.filter { it.name == "খিলগাও ফ্লাইওভার" }
        assertTrue(flyover.isNotEmpty())
        assertTrue(flyover.all { it.bridge })
        assertTrue(lines.any { !it.bridge })
    }

    @Test fun aLineCrossingATileEdgeAsksForBothTiles() {
        // West to east across the x = 2651/2652 boundary at z14 (lng -121.7285).
        val tiles = RoadNameTiles.tilesAlong(listOf(LatLng(38.5449, -121.7320), LatLng(38.5449, -121.7250)))
        assertTrue(2651 to 6288 in tiles)
        assertTrue(2652 to 6288 in tiles)
        assertEquals(tiles.size, tiles.toSet().size)
    }
}
