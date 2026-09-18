package app.vela.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * The coverage probe behind the mount rule (issue #552). The synthetic cases pin the format
 * handling; [probeRealArchive] runs against a real published archive when one is pointed at, which
 * is the only way to check the reader against what the bake actually writes:
 *
 *   ./gradlew :app:testReleaseUnitTest --tests '*PmtilesReaderTest*' --rerun-tasks \
 *       -DvelaPmtiles=/path/basemap-hawaii.pmtiles -DvelaLat=20.79 -DvelaLng=-156.33
 */
class PmtilesReaderTest {

    @Test fun `tile ids follow the hilbert order`() {
        assertEquals(0L, PmtilesReader.tileId(0, 0, 0))
        assertEquals(1L, PmtilesReader.tileId(1, 0, 0))
        assertEquals(2L, PmtilesReader.tileId(1, 0, 1))
        assertEquals(3L, PmtilesReader.tileId(1, 1, 1))
        assertEquals(4L, PmtilesReader.tileId(1, 1, 0))
        assertEquals(5L, PmtilesReader.tileId(2, 0, 0))
    }

    @Test fun `a coordinate maps to its web mercator tile`() {
        assertEquals(0 to 0, PmtilesReader.tileOf(85.0, -180.0, 0))
        assertEquals(1 to 1, PmtilesReader.tileOf(-0.0001, 0.0001, 1))
    }

    @Test fun `a tile inside a run is found and one outside it is not`() {
        val f = archive(entries = listOf(Triple(PmtilesReader.tileId(12, 100, 200), 1L, 1L)), gzip = false)
        assertEquals(true, PmtilesReader.hasTile(f, 12, 100, 200))
        assertEquals(false, PmtilesReader.hasTile(f, 12, 101, 200))
    }

    @Test fun `a gzipped directory reads the same`() {
        val f = archive(entries = listOf(Triple(PmtilesReader.tileId(12, 7, 9), 1L, 1L)), gzip = true)
        assertEquals(true, PmtilesReader.hasTile(f, 12, 7, 9))
        assertEquals(false, PmtilesReader.hasTile(f, 12, 8, 9))
    }

    @Test fun `a zoom the archive does not carry cannot be answered`() {
        val f = archive(entries = listOf(Triple(PmtilesReader.tileId(12, 7, 9), 1L, 1L)), gzip = false)
        // The fixture is baked to z14, so a deeper ask has no answer - as opposed to "no tile",
        // which is what keeps a shallow archive from being ruled out by a probe it cannot serve.
        assertNull(PmtilesReader.hasTile(f, 15, 1, 1))
    }

    @Test fun `a file that is not an archive cannot be answered`() {
        val f = File.createTempFile("vela", ".pmtiles").apply { writeBytes(ByteArray(200)) }
        assertNull(PmtilesReader.hasTile(f, 12, 1, 1))
    }

    @Test fun probeRealArchive() {
        val path = System.getProperty("velaPmtiles")
        assumeTrue("set -DvelaPmtiles=<a basemap archive> to run this", path != null)
        val f = File(path!!)
        assumeTrue("no such file: $path", f.exists())
        val h = PmtilesReader.header(f)
        println("header: minZoom=${h?.minZoom} maxZoom=${h?.maxZoom} compression=${h?.internalCompression}")
        val lat = System.getProperty("velaLat")?.toDouble() ?: 0.0
        val lng = System.getProperty("velaLng")?.toDouble() ?: 0.0
        val z = BasemapTileStore.COVERAGE_PROBE_Z
        val (x, y) = PmtilesReader.tileOf(lat, lng, z)
        println("probe $lat,$lng -> z$z/$x/$y tile=${PmtilesReader.hasTile(f, z, x, y)} roads=${PmtilesReader.hasRoads(f, z, x, y)}")
        assertTrue("the header must at least parse", h != null)
    }

    /** A minimal v3 archive: header plus one root directory. The probe never reads tile data. */
    private fun archive(entries: List<Triple<Long, Long, Long>>, gzip: Boolean): File {
        val dir = ByteArrayOutputStream()
        fun varint(v: Long) {
            var x = v
            while (true) {
                val b = (x and 0x7F).toInt()
                x = x ushr 7
                if (x == 0L) { dir.write(b); return }
                dir.write(b or 0x80)
            }
        }
        varint(entries.size.toLong())
        var last = 0L
        entries.forEach { (id, _, _) -> varint(id - last); last = id }
        entries.forEach { (_, run, _) -> varint(run) }
        entries.forEach { (_, _, len) -> varint(len) }
        entries.forEachIndexed { i, _ -> varint(if (i == 0) 1L else 0L) }
        val body = if (gzip) {
            ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(dir.toByteArray()) } }.toByteArray()
        } else dir.toByteArray()

        val head = ByteArray(127)
        "PMTiles".toByteArray().copyInto(head)
        head[7] = 3
        fun le64(at: Int, v: Long) { for (i in 0 until 8) head[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
        le64(8, 127L)
        le64(16, body.size.toLong())
        le64(40, 127L + body.size)
        head[97] = if (gzip) 2 else 1
        head[100] = 0
        head[101] = 14
        val f = File.createTempFile("vela", ".pmtiles")
        f.outputStream().use { it.write(head); it.write(body) }
        return f
    }
}
