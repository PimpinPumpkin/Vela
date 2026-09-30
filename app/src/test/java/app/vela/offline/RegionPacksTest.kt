package app.vela.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionPacksTest {
    private fun r(id: String, name: String, s: Double, w: Double, n: Double, e: Double, mb: Int = 100) =
        RoutingRegion(id = id, name = name, url = "", sizeMb = mb, s = s, w = w, n = n, e = e)

    private val packs = listOf(
        r("california", "California (state)", 32.48, -125.89, 42.02, -114.13, 366),
        r("germany", "Germany", 47.27, 5.86, 55.15, 15.05, 1877),
        r("spain", "Spain", 35.15, -9.78, 44.15, 5.10, 271),
        r("delaware", "Delaware (state)", 38.45, -75.79, 39.84, -75.05, 20),
    )

    @Test
    fun ownPackFirst() = assertEquals("delaware", RegionPacks.packFor(r("delaware", "Delaware (state)", 38.45, -75.79, 39.84, -75.05), packs)?.id)

    @Test
    fun splitRegionUsesItsParent() {
        val norcal = r("california-norcal", "Northern California (California)", 35.79, -126.43, 42.43, -115.60)
        val pack = RegionPacks.packFor(norcal, packs)!!
        assertEquals("california", pack.id)
        assertTrue(RegionPacks.isShared(norcal, pack))
        assertTrue(RegionPacks.autoWith(norcal, pack))
    }

    @Test
    fun aHugeParentWaitsToBeAsked() {
        val bayern = r("de-bayern", "Bayern (Germany)", 47.23, 8.98, 50.57, 14.09)
        val pack = RegionPacks.packFor(bayern, packs)!!
        assertEquals("germany", pack.id)
        assertFalse(RegionPacks.autoWith(bayern, pack))
    }

    @Test
    fun noParentByBoxAlone() = assertNull(RegionPacks.packFor(r("andorra", "Andorra", 42.43, 1.41, 42.66, 1.79), packs))

    @Test
    fun anInstalledParentKeepsServingOnceThePieceGetsItsOwn() {
        val norcal = r("california-norcal", "Northern California (California)", 35.79, -126.43, 42.43, -115.60)
        val withOwn = packs + r("california-norcal", "Northern California (California)", 35.79, -126.43, 42.43, -115.60)
        assertEquals("california", RegionPacks.packFor(norcal, withOwn, setOf("california"))?.id)
        assertEquals("california-norcal", RegionPacks.packFor(norcal, withOwn, setOf("delaware"))?.id)
        assertEquals("california-norcal", RegionPacks.packFor(norcal, withOwn)?.id)
    }
}
