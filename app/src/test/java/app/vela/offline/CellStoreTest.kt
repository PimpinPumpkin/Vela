package app.vela.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cell manifest shape (SPEC 7.6) and the frame test the area picker uses. */
class CellStoreTest {
    private val manifest = """
        {"version":1,"regions":[{"id":"delaware","name":"Delaware","rev":20260927,"cells":[
          {"id":"delaware.n38.0w075.5","bbox":[38.0,-75.5,38.5,-75.0],"url":"https://x/a.zip","sizeMb":3.2,"installedMb":6.1,"rev":20260927},
          {"id":"delaware.n39.5w075.5","bbox":[39.5,-75.5,39.85,-75.0],"url":"https://x/b.zip","sizeMb":13.7,"parts":["obf","pack"]}
        ]},{"id":"empty","name":"Empty","rev":1}]}
    """.trimIndent()

    @Test
    fun `manifest flattens regions to cells with region defaults`() {
        val cells = CellStore.parseManifest(manifest)
        assertEquals(2, cells.size)
        val a = cells[0]
        assertEquals("delaware", a.regionId)
        assertEquals("Delaware", a.regionName)
        assertEquals(6.1, a.installedMb, 1e-9)
        assertEquals(20260927, a.rev)
        assertEquals(listOf("obf", "pack", "places"), a.parts)
        val b = cells[1]
        assertEquals(13.7, b.installedMb, 1e-9) // no installedMb: the zip size stands in
        assertEquals(20260927, b.rev) // the region's rev
        assertEquals(listOf("obf", "pack"), b.parts)
    }

    @Test
    fun `a frame touching a cell picks it, one beside it does not`() {
        val cells = CellStore.parseManifest(manifest)
        val dover = cells.filter { it.intersects(39.1, -75.6, 39.2, -75.5) }
        assertTrue(dover.isEmpty()) // between the two cells' rows
        val north = cells.filter { it.intersects(39.7, -75.6, 39.8, -75.4) }
        assertEquals(listOf("delaware.n39.5w075.5"), north.map { it.id })
        assertFalse(cells[0].intersects(38.5, -75.0, 39.0, -74.5)) // sharing only an edge is not inside
    }

    @Test
    fun `a bad manifest is an empty list`() {
        assertTrue(CellStore.parseManifest("{}").isEmpty())
    }
}
