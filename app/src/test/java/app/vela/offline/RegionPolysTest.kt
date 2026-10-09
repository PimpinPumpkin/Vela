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
        // Every routing row is a Geofabrik extract with a .poly published beside it, or a cut with
        // its polygon in tools/region-cuts;
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

    @Test fun `alaska stays in alaska`() {
        // Alaska's extract crosses the antimeridian, so its box runs -180..180 and, read literally,
        // covered everything between 49.8 N and 73 N: the Alaska address overlay claimed Germany
        // and the Netherlands and hid the basemap house numbers there (issue #257). The polygon
        // answers first, and a globe-wide box never covers by itself.
        assertTrue(covers("alaska", 61.22, -149.90)) // Anchorage
        assertFalse(covers("alaska", 52.52, 13.40)) // Berlin
        assertFalse(covers("alaska", 52.37, 4.90)) // Amsterdam
        assertFalse(RegionPolys.boxCovers(49.809, -180.0, 72.988, 180.0, 52.52, 13.40))
        assertFalse(RegionPolys.boxCovers(49.809, -180.0, 72.988, 180.0, 61.22, -149.90))
        assertTrue(RegionPolys.boxCovers(49.809, -180.0, 72.988, -129.9, 61.22, -149.90))
        assertFalse(RegionPolys.boxCovers(49.809, -180.0, 72.988, -129.9, 52.52, 13.40))
        assertTrue(RegionPolys.boxCovers(47.0, 5.8, 55.1, 15.1, 52.52, 13.40)) // an ordinary box still works
        assertTrue(RegionPolys.boxCovers(-85.0, -180.0, 85.0, 180.0, 52.52, 13.40)) // the world basemap row
        assertFalse(RegionPolys.boxCovers(-56.75, -179.99, -28.49, 179.99, -33.9, -70.7)) // New Zealand's box vs Santiago
        assertTrue(covers("new-zealand", -41.29, 174.78)) // Wellington, by polygon
    }

    @Test fun `a river border is honored where boxes overlap`() {
        // Kansas's box crosses the Missouri River into Kansas City, Missouri.
        assertTrue(covers("missouri", 39.10, -94.58))
        assertFalse(covers("kansas", 39.10, -94.58))
    }

    private val texasParts = listOf("texas-north", "texas-east", "texas-south", "texas-west")

    @Test fun `texas is cut into four parts by its own polygons`() {
        // Geofabrik has no Texas sub-extracts; the parts are cut with tools/region-cuts/*.poly.
        fun partsAt(lat: Double, lng: Double) = texasParts.filter { covers(it, lat, lng) }
        assertEquals(listOf("texas-north"), partsAt(32.78, -96.80)) // Dallas
        assertEquals(listOf("texas-north"), partsAt(33.91, -98.49)) // Wichita Falls
        assertEquals(listOf("texas-east"), partsAt(29.76, -95.37)) // Houston
        assertEquals(listOf("texas-east"), partsAt(32.35, -95.30)) // Tyler
        assertEquals(listOf("texas-east"), partsAt(30.63, -96.33)) // College Station
        assertEquals(listOf("texas-south"), partsAt(30.27, -97.74)) // Austin
        assertEquals(listOf("texas-south"), partsAt(31.55, -97.15)) // Waco
        assertEquals(listOf("texas-south"), partsAt(29.42, -98.49)) // San Antonio
        assertEquals(listOf("texas-south"), partsAt(25.90, -97.50)) // Brownsville
        assertEquals(listOf("texas-west"), partsAt(31.76, -106.49)) // El Paso
        assertEquals(listOf("texas-west"), partsAt(35.22, -101.83)) // Amarillo
        assertEquals(listOf("texas-west"), partsAt(32.45, -99.73)) // Abilene
        // the outer edge is the Texas extract's own: a neighbor's city is in no part
        assertTrue(partsAt(35.47, -97.52).isEmpty()) // Oklahoma City
        assertTrue(partsAt(32.52, -93.75).isEmpty()) // Shreveport
        assertTrue(partsAt(25.69, -100.32).isEmpty()) // Monterrey
    }

    @Test fun `neighboring texas parts overlap along their seam`() {
        // A few kilometers on each side, so a trip near a seam routes inside one part.
        fun both(a: String, b: String, lat: Double, lng: Double) =
            assertTrue("$a and $b at $lat,$lng", covers(a, lat, lng) && covers(b, lat, lng))
        both("texas-north", "texas-east", 32.85, -96.00)
        both("texas-east", "texas-south", 30.21, -96.12)
        both("texas-north", "texas-south", 31.74, -98.50)
        both("texas-north", "texas-west", 32.52, -99.11)
        both("texas-south", "texas-west", 30.84, -100.11)
    }

    @Test fun `the texas parts leave no gap inside the state`() {
        // Every point well inside the whole-state polygon is in at least one part.
        val uncovered = ArrayList<String>()
        var checked = 0
        var lat = 25.8
        while (lat < 36.6) {
            var lng = -106.8
            while (lng < -93.4) {
                val inner = covers("texas", lat, lng) && covers("texas", lat + 0.15, lng) && covers("texas", lat - 0.15, lng) &&
                    covers("texas", lat, lng + 0.15) && covers("texas", lat, lng - 0.15)
                if (inner) {
                    checked++
                    if (texasParts.none { covers(it, lat, lng) }) uncovered += "%.1f,%.1f".format(lat, lng)
                }
                lng += 0.1
            }
            lat += 0.1
        }
        assertTrue("grid read", checked > 5000)
        assertTrue("in no part: $uncovered", uncovered.isEmpty())
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
