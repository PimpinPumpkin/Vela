package app.vela.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #599: a region is picked by its real boundary, not its bounding box. These read the
 * SHIPPED asset, so a re-bake that broke a polygon fails here rather than on a phone.
 */
class RegionPolysTest {

    private val table by lazy {
        val f = listOf("src/main/assets/region_polys.json", "app/src/main/assets/region_polys.json")
            .map(::File).first { it.exists() }
        RegionPolys.parse(f.readText())
    }

    private fun covers(id: String, lat: Double, lng: Double): Boolean {
        val e = table.getValue(id)
        return e.outers.any { RegionPolys.inside(lat, lng, it) } && e.holes.none { RegionPolys.inside(lat, lng, it) }
    }

    @Test fun `every catalog row has a polygon`() {
        // Every routing row is a Geofabrik extract and Geofabrik publishes a .poly beside each one;
        // a row missing here is a fetch that failed during the bake, or a row added without re-running
        // scripts/region-polys.py, and that region would silently go back to its box.
        val cat = listOf("../tools/routing-regions.json", "tools/routing-regions.json").map(::File).first { it.exists() }
        val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(cat.readText()).map { it.groupValues[1] }.toList()
        assertTrue("catalog read", ids.size > 400)
        val missing = ids.filter { it !in table }
        assertTrue("no polygon for: $missing", missing.isEmpty())
    }

    @Test fun `hong kong has its own row and wins over guangdong`() {
        // Geofabrik cuts Hong Kong and Macau as their own extracts AND folds them into Guangdong's;
        // both polygons cover the point, and the smaller box is the specific region (issue #599).
        assertTrue(covers("china-hong-kong", 22.32, 114.17))
        assertTrue(covers("china-guangdong", 22.32, 114.17))
        assertTrue(covers("china-macau", 22.19, 113.54))
        assertFalse(covers("china-hong-kong", 22.19, 113.54))
    }

    @Test fun `hong kong is not in vietnam`() {
        // The report: Vietnam's box reaches the island claims at 114.6 E and swallowed Hong Kong.
        assertFalse(covers("vietnam", 22.32, 114.17))
        assertTrue(covers("vietnam", 21.03, 105.85)) // Hanoi
        assertTrue(covers("china", 22.32, 114.17))
    }

    @Test fun `a river border is honored where boxes overlap`() {
        // Kansas's box crosses the Missouri River into Kansas City, Missouri.
        assertTrue(covers("missouri", 39.10, -94.58))
        assertFalse(covers("kansas", 39.10, -94.58))
    }

    @Test fun `ray cast handles a concave ring and a point outside its box`() {
        // A U shape: the notch at the top is outside even though it is inside the bounding box.
        val u = doubleArrayOf(0.0, 0.0, 0.0, 3.0, 3.0, 3.0, 3.0, 2.0, 1.0, 2.0, 1.0, 1.0, 3.0, 1.0, 3.0, 0.0)
        assertTrue(RegionPolys.inside(0.5, 1.5, u))   // the base of the U
        assertFalse(RegionPolys.inside(2.0, 1.5, u))  // the notch
        assertFalse(RegionPolys.inside(5.0, 5.0, u))
        assertFalse(RegionPolys.inside(0.5, 0.5, doubleArrayOf(0.0, 0.0, 1.0, 1.0))) // not a ring
    }

    @Test fun `no polygon means no answer, so callers fall back to the box`() {
        assertNull(RegionPolys.covers("no-such-region", 0.0, 0.0))
        assertEquals(emptyMap<String, RegionPolys.Entry>(), RegionPolys.parse("{}"))
    }
}
