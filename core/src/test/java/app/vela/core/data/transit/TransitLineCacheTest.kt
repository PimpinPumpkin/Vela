package app.vela.core.data.transit

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TransitLineCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000_000_000L
    private fun cache(dir: File = File(tmp.root, "transit_lines")) = TransitLineCache({ dir }, { clock })

    // Two stretches of New York's 1 line, with more decimals than the file keeps.
    private val lines = listOf(
        Transitous.MapLine(
            listOf(LatLng(40.8155812345, -73.9583798765), LatLng(40.8077212345, -73.9641198765)),
            listOf("#d82233"), Transitous.Kind.METRO, listOf("1" to "#d82233", "2" to "#d82233"),
        ),
        Transitous.MapLine(
            listOf(LatLng(40.75, -73.99), LatLng(40.76, -73.98), LatLng(40.77, -73.97)),
            emptyList(), Transitous.Kind.TRAIN,
        ),
    )

    @Test fun aCellReadBackIsTheCellThatWriteReturned() {
        val c = cache()
        val written = c.write("12:816:-1480", lines)
        val read = c.read("12:816:-1480")!!
        // The same numbers both ways: a stretch shared by two cells is recognized by its ends.
        assertEquals(written.lines, read.lines)
        assertEquals(written.fetchedAt, read.fetchedAt)
        assertEquals(2, read.lines.size)
        assertEquals(Transitous.Kind.METRO, read.lines[0].kind)
        assertEquals(listOf("1" to "#d82233", "2" to "#d82233"), read.lines[0].labels)
        assertEquals(listOf("#d82233"), read.lines[0].colors)
        assertEquals(3, read.lines[1].points.size)
        // Kept to 0.1 m.
        assertEquals(40.815581, read.lines[0].points[0].lat, 1e-9)
        assertEquals(-73.958380, read.lines[0].points[0].lng, 1e-9)
    }

    @Test fun aCellThatWasNeverKeptReadsAsNothing() {
        assertNull(cache().read("12:1:1"))
    }

    @Test fun anEmptyCellIsKeptToo() {
        // A cell over open water has no lines; knowing that saves the request next time.
        val c = cache()
        c.write("8:10:-20", emptyList())
        assertEquals(0, c.read("8:10:-20")!!.lines.size)
    }

    @Test fun aCellIsFreshForAWeek() {
        val c = cache()
        val cell = c.write("12:1:1", lines)
        assertTrue(c.isFresh(cell))
        clock += TransitLineCache.FRESH_MS - 1
        assertTrue(c.isFresh(cell))
        clock += 1
        assertFalse(c.isFresh(cell))
        // Still readable when old: it is shown while the new copy is fetched.
        assertEquals(2, c.read("12:1:1")!!.lines.size)
        // A clock set back makes it old, not fresh forever.
        clock -= 2 * TransitLineCache.FRESH_MS
        assertFalse(c.isFresh(cell))
    }

    @Test fun aFileThatCannotBeReadIsDropped() {
        val dir = File(tmp.root, "transit_lines").apply { mkdirs() }
        val broken = File(dir, "12_1_1.json").apply { writeText("{\"v\":1,\"at\":5,\"lines\":[{\"p\":") }
        val other = File(dir, "12_2_2.json").apply { writeText("{\"v\":999,\"at\":5,\"lines\":[]}") }
        val c = cache(dir)
        assertNull(c.read("12:1:1"))
        assertNull(c.read("12:2:2"))
        assertFalse(broken.exists())
        assertFalse(other.exists())
    }

    @Test fun theFolderKeepsTheNewestCells() {
        val dir = File(tmp.root, "transit_lines").apply { mkdirs() }
        // More old cells than the folder holds, oldest first.
        for (i in 0 until TransitLineCache.MAX_CELLS + 5) {
            File(dir, "8_${i}_0.json").apply { writeText("{\"v\":1,\"at\":$i,\"lines\":[]}"); setLastModified(1_000_000L + i * 1000L) }
        }
        val c = cache(dir)
        c.write("12:7:7", lines)
        val left = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(TransitLineCache.MAX_CELLS, left.size)
        assertTrue("the cell just written stays", "12_7_7.json" in left)
        assertFalse("the oldest goes", "8_0_0.json" in left)
        assertFalse("8_5_0.json" in left)
        assertTrue("8_6_0.json" in left)
    }

    @Test fun aKeyBecomesASafeFileName() {
        val dir = File(tmp.root, "transit_lines")
        cache(dir).write("12:-816:1480/../x", lines)
        assertEquals(listOf("12_-816_1480____x.json"), dir.listFiles()!!.map { it.name })
    }
}
