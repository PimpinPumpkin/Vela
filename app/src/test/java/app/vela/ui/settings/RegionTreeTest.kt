package app.vela.ui.settings

import app.vela.offline.RoutingRegion
import app.vela.ui.settings.sections.regionTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionTreeTest {
    private fun r(id: String, name: String) = RoutingRegion(id = id, name = name, url = "", sizeMb = 100, s = 0.0, w = 0.0, n = 1.0, e = 1.0)

    private val catalog = listOf(
        r("delaware", "Delaware (state)"),
        r("texas", "Texas (state)"),
        r("texas-north", "North Texas (Texas)"),
        r("texas-east", "East Texas and Gulf Coast (Texas)"),
        r("texas-south", "Central and South Texas (Texas)"),
        r("texas-west", "West Texas and Panhandle (Texas)"),
        r("california-norcal", "Northern California (California)"),
        r("california-socal", "Southern California (California)"),
        r("puerto-rico", "Puerto Rico (US)"),
        r("de-bayern", "Bayern (Germany)"),
        r("de-berlin", "Berlin (Germany)"),
        r("australia", "Australia"),
        r("australia-victoria", "Victoria (Australia)"),
        r("australia-tasmania", "Tasmania (Australia)"),
        r("andorra", "Andorra"),
    )

    @Test
    fun `a state's parts sit under the United States with the other states`() {
        val us = regionTree(catalog).single { it.title == "United States" }
        val ids = us.pieces.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("texas-north", "texas-east", "texas-south", "texas-west", "california-norcal", "california-socal", "delaware", "puerto-rico")))
        assertNull(regionTree(catalog).firstOrNull { it.title == "Texas" || it.title == "California" })
    }

    @Test
    fun `the whole state is not offered beside its parts`() {
        val us = regionTree(catalog).single { it.title == "United States" }
        assertFalse(us.pieces.any { it.id == "texas" })
        // Download all takes every piece, so the state must not be in there twice
        assertEquals(8, us.pieces.size)
    }

    @Test
    fun `a phone that has the whole state keeps its row`() {
        val us = regionTree(catalog, installed = setOf("texas")).single { it.title == "United States" }
        assertTrue(us.pieces.any { it.id == "texas" })
        assertTrue(us.pieces.any { it.id == "texas-north" })
    }

    @Test
    fun `a state with no parts in the catalog is listed as before`() {
        val whole = catalog.filterNot { it.id.startsWith("texas-") }
        val us = regionTree(whole).single { it.title == "United States" }
        assertTrue(us.pieces.any { it.id == "texas" })
    }

    @Test
    fun `countries keep their own parents and whole files`() {
        val tree = regionTree(catalog)
        assertEquals(listOf("de-bayern", "de-berlin"), tree.single { it.title == "Germany" }.pieces.map { it.id })
        val au = tree.single { it.title == "Australia" }
        assertEquals("australia", au.whole?.id)
        assertEquals(2, au.pieces.size)
        assertFalse(tree.single { it.title == "Andorra" }.parent)
    }
}
