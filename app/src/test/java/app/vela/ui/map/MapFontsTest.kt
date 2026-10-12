package app.vela.ui.map

import app.vela.core.data.tiles.MapStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFontsTest {
    @Test fun `a cached look loads as itself and the asset is not read`() {
        val dark = MapFonts.offlineStyle(MapStyle.DARK, "{dark}") { error("asset read") }!!
        assertEquals("{dark}", dark.json)
        assertFalse(dark.painted)
        val liberty = MapFonts.offlineStyle(MapStyle.LIBERTY, "{liberty}") { error("asset read") }!!
        assertEquals("{liberty}", liberty.json)
        assertTrue(liberty.painted)
    }

    @Test fun `a look with no cached style loads the liberty asset painted as the vela look`() {
        for (cached in listOf(null, "", " ")) {
            val s = MapFonts.offlineStyle(MapStyle.FIORD, cached) { "{asset}" }!!
            assertEquals("{asset}", s.json)
            assertTrue(s.painted)
        }
        assertTrue(MapFonts.offlineStyle(MapStyle.LIBERTY, null) { "{asset}" }!!.painted)
    }

    @Test fun `no cache and no asset is no offline style`() {
        assertNull(MapFonts.offlineStyle(MapStyle.FIORD, null) { null })
        assertNull(MapFonts.offlineStyle(MapStyle.FIORD, null) { "" })
    }
}
