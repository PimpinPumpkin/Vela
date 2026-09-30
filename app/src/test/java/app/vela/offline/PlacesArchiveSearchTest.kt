package app.vela.offline

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class PlacesArchiveSearchTest {
    private fun varint(o: ByteArrayOutputStream, v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { o.write(x.toInt()); return }
            o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
    }
    private fun bytes(o: ByteArrayOutputStream, field: Int, b: ByteArray) { varint(o, (field.toLong() shl 3) or 2); varint(o, b.size.toLong()); o.write(b) }
    private fun num(o: ByteArrayOutputStream, field: Int, v: Long) { varint(o, (field.toLong() shl 3)); varint(o, v) }
    private fun packed(vals: List<Int>) = ByteArrayOutputStream().also { o -> vals.forEach { varint(o, it.toLong()) } }.toByteArray()
    private fun zz(v: Int) = (v shl 1) xor (v shr 31)

    /** One `places` layer, extent 4096, a point at the tile's center with a name and a class. */
    private fun tile(): ByteArray {
        val feature = ByteArrayOutputStream().also { f ->
            num(f, 1, 7)
            bytes(f, 2, packed(listOf(0, 0, 1, 1)))
            num(f, 3, 1)
            bytes(f, 4, packed(listOf((1 shl 3) or 1, zz(2048), zz(2048))))
        }.toByteArray()
        fun strValue(s: String) = ByteArrayOutputStream().also { bytes(it, 1, s.toByteArray()) }.toByteArray()
        val layer = ByteArrayOutputStream().also { l ->
            bytes(l, 1, "places".toByteArray())
            bytes(l, 2, feature)
            bytes(l, 3, "name".toByteArray())
            bytes(l, 3, "class".toByteArray())
            bytes(l, 4, strValue("Taqueria Guadalajara"))
            bytes(l, 4, strValue("Mexican restaurant"))
            num(l, 5, 4096)
        }.toByteArray()
        return ByteArrayOutputStream().also { bytes(it, 3, layer) }.toByteArray()
    }

    @Test
    fun decodesAPlacesPoint() {
        val z = 17
        val (x, y) = PmtilesReader.tileOf(38.5449, -121.7405, z)
        val f = PlacesArchiveSearch.decode(tile(), z, x, y).single()
        assertEquals("Taqueria Guadalajara", f.props["name"])
        assertEquals("Mexican restaurant", f.props["class"])
        // The tile center: within one z17 tile (~240 m) of the point the tile was picked for.
        assertTrue(f.location.lat in 38.5425..38.5475)
        assertTrue(f.location.lng in -121.743..-121.738)
    }

    /** On demand, streamed like the map streams it (Range requests to the release host):
     *  ./gradlew :app:testDebugUnitTest --tests '*PlacesArchiveSearchTest' -DvelaArchiveUrl=<https .pmtiles> -DvelaLat=.. -DvelaLng=.. */
    @Test
    fun searchesAStreamedArchive() {
        val url = System.getProperty("velaArchiveUrl")
        assumeTrue(url != null)
        val near = LatLng(System.getProperty("velaLat")!!.toDouble(), System.getProperty("velaLng")!!.toDouble())
        var calls = 0
        var bytes = 0L
        val client = okhttp3.OkHttpClient.Builder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { r -> if (r.code == 206) { calls++; bytes += r.body?.contentLength() ?: 0 } }
        }.build()
        for (round in 1..2) {
            calls = 0; bytes = 0
            val t = System.currentTimeMillis()
            val found = PmtilesReader.Archive.http(client, url!!).use { PlacesArchiveSearch.search(it, near, "Restaurants", maxRings = PlacesArchiveSearch.MAX_RINGS_STREAMED) }
            println("streamed round $round: ${found.size} matches, $calls range requests, ${bytes / 1024} KB, ${System.currentTimeMillis() - t} ms")
            found.sortedBy { it.location.distanceTo(near) }.take(5).forEach { println("  ${it.name} | ${it.category} | ${"%.0f".format(it.location.distanceTo(near))} m") }
            assertTrue(found.isNotEmpty())
        }
    }

    /** On demand, against a real places archive:
     *  ./gradlew :app:testDebugUnitTest --tests '*PlacesArchiveSearchTest' -DvelaArchive=<places .pmtiles> -DvelaLat=.. -DvelaLng=.. */
    @Test
    fun searchesARealArchive() {
        val path = System.getProperty("velaArchive")
        assumeTrue(path != null && File(path).exists())
        val near = LatLng(System.getProperty("velaLat")!!.toDouble(), System.getProperty("velaLng")!!.toDouble())
        val t = System.currentTimeMillis()
        val found = PlacesArchiveSearch.search(listOf(File(path!!)), near, "Restaurants")
        println("archive search: ${found.size} matches in ${System.currentTimeMillis() - t} ms")
        found.sortedBy { it.location.distanceTo(near) }.take(10).forEach {
            println("  ${it.name} | ${it.category} | ${"%.0f".format(it.location.distanceTo(near))} m")
        }
        assertTrue(found.isNotEmpty())
    }
}
