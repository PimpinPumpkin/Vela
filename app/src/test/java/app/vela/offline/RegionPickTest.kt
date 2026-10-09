package app.vela.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No polygons are loaded in a unit test, so every region here answers by its box. */
class RegionPickTest {
    private fun r(id: String, s: Double, w: Double, n: Double, e: Double) =
        RoutingRegion(id = id, name = id, url = "", sizeMb = 100, s = s, w = w, n = n, e = e)

    private fun a(id: String, s: Double, w: Double, n: Double, e: Double) =
        PmtilesRegionStore.Region(id, id, "", 100.0, s, w, n, e)

    private val routing = listOf(
        r("texas", 25.7, -106.9, 36.5, -93.0),
        r("texas-north", 31.4, -99.2, 34.2, -95.7),
        r("texas-east", 25.7, -96.7, 34.0, -93.0),
        r("oklahoma", 33.6, -103.0, 37.0, -94.4),
    )

    // a point in the first part only, and one where two parts' boxes overlap
    private val lat = 32.8; private val lng = -97.0
    private val seamLat = 32.9; private val seamLng = -96.0

    @Test
    fun `the smallest covering region is the pick`() {
        val p = RegionPick.routing(routing, lat, lng, emptySet())!!
        assertEquals("texas-north", p.region.id)
        assertFalse(p.installed)
    }

    @Test
    fun `a phone with the whole state is not offered a part`() {
        val p = RegionPick.routing(routing, lat, lng, setOf("texas"))!!
        assertEquals("texas", p.region.id)
        assertTrue(p.installed)
    }

    @Test
    fun `an installed part answers for itself`() {
        val p = RegionPick.routing(routing, lat, lng, setOf("texas", "texas-north"))!!
        assertEquals("texas-north", p.region.id)
        assertTrue(p.installed)
    }

    @Test
    fun `on a seam the installed neighbor answers`() {
        assertEquals("texas-north", RegionPick.routing(routing, seamLat, seamLng, emptySet())!!.region.id)
        val p = RegionPick.routing(routing, seamLat, seamLng, setOf("texas-east"))!!
        assertEquals("texas-east", p.region.id)
        assertTrue(p.installed)
    }

    @Test
    fun `an installed region elsewhere changes nothing`() {
        val p = RegionPick.routing(routing, lat, lng, setOf("oklahoma"))!!
        assertEquals("texas-north", p.region.id)
        assertFalse(p.installed)
    }

    @Test
    fun `no covering region is no pick`() = assertNull(RegionPick.routing(routing, 48.85, 2.35, setOf("texas")))

    @Test
    fun `the world basemap never stands in for a region`() {
        val maps = listOf(a("world", -85.0, -180.0, 85.0, 180.0), a("texas", 25.7, -106.9, 36.5, -93.0), a("texas-north", 31.4, -99.2, 34.2, -95.7))
        val skip = setOf("world")
        val fresh = RegionPick.archive(maps, lat, lng, setOf("world"), skip)!!
        assertEquals("texas-north", fresh.region.id)
        assertFalse(fresh.installed)
        val has = RegionPick.archive(maps, lat, lng, setOf("world", "texas"), skip)!!
        assertEquals("texas", has.region.id)
        assertTrue(has.installed)
        assertNull(RegionPick.archive(maps, 48.85, 2.35, setOf("world"), skip))
    }
}
