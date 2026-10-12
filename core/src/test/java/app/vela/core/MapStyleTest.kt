package app.vela.core

import app.vela.core.data.tiles.MapStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStyleTest {
    @Test fun `every look is found by its key and its url`() {
        for (s in MapStyle.entries) {
            assertEquals(s, MapStyle.fromKey(s.key))
            assertEquals(s, MapStyle.byUri(s.uri))
        }
        assertNull(MapStyle.fromKey("streets"))
        assertNull(MapStyle.fromKey(null))
        assertNull(MapStyle.byUri("https://example.com/style.json"))
    }

    @Test fun `only the vela look is painted and the dark looks are fiord and dark`() {
        assertTrue(MapStyle.LIBERTY.painted)
        assertTrue(MapStyle.entries.filter { it != MapStyle.LIBERTY }.none { it.painted })
        assertEquals(setOf(MapStyle.FIORD, MapStyle.DARK), MapStyle.entries.filter { it.darkLook }.toSet())
        assertFalse(MapStyle.DEFAULT.darkLook)
    }
}
