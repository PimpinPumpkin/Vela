package app.vela.offline

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A streamed archive rebaked under the same address is read again, never at the old offsets. */
class StreamedPmtilesTest {
    /** The file the fake host serves right now; a test swaps it to play a rebake. */
    @Volatile private var served = archive("first bake")
    private var requests = 0

    private val host = OkHttpClient.Builder().addInterceptor { chain ->
        requests++
        val req = chain.request()
        val file = served
        val (from, to) = req.header("Range")!!.removePrefix("bytes=").split('-').map { it.toInt() }
        val part = file.copyOfRange(from, minOf(to + 1, file.size))
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(206).message("Partial Content")
            .header("Content-Range", "bytes $from-$to/${file.size}")
            .body(part.toResponseBody("application/octet-stream".toMediaType()))
            .build()
    }.build()

    @After fun clean() { StreamedPmtiles.clientOverride = null }

    @Test fun `a reader stops at a replaced file instead of reading it at the old offsets`() {
        val a = PmtilesReader.Archive.http(host, "https://example.invalid/reader.pmtiles")
        assertEquals("first bake", String(a.tile(12, 100, 200)!!))
        served = archive("the second bake, longer")
        assertNull(a.tile(12, 100, 200))
        assertTrue(a.replaced)
        assertNull(a.tile(12, 100, 200))
    }

    @Test fun `a streamed source opens the rebaked file and keeps serving its tiles`() {
        StreamedPmtiles.clientOverride = host
        val key = StreamedPmtiles.register("https://example.invalid/streamed.pmtiles")!!
        assertEquals("first bake", String(StreamedPmtiles.tileBytes(key, 12, 100, 200)!!))
        served = archive("the second bake, longer")
        assertEquals("the second bake, longer", String(StreamedPmtiles.tileBytes(key, 12, 100, 200)!!))
        val before = requests
        assertEquals("the second bake, longer", String(StreamedPmtiles.tileBytes(key, 12, 100, 200)!!))
        assertEquals("one range read a tile once the new file is open", before + 1, requests)
        assertNull(StreamedPmtiles.tileBytes(key, 12, 101, 200))
    }

    @Test fun `the same length is the same file, and the ETag decides only without one`() {
        assertFalse(PmtilesReader.HttpRangeReader.differs(100L to "a", 100L, "b"))
        assertTrue(PmtilesReader.HttpRangeReader.differs(100L to "a", 101L, "a"))
        assertTrue(PmtilesReader.HttpRangeReader.differs(null to "a", null, "b"))
        assertFalse(PmtilesReader.HttpRangeReader.differs(null to null, 100L, "b"))
    }

    /** A v3 archive holding one uncompressed tile at 12/100/200 whose bytes are [tile]. */
    private fun archive(tile: String): ByteArray {
        val body = tile.toByteArray()
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
        varint(1)
        varint(PmtilesReader.tileId(12, 100, 200))
        varint(1) // run length
        varint(body.size.toLong())
        varint(1) // offset 0, written plus one
        val d = dir.toByteArray()
        val head = ByteArray(127)
        "PMTiles".toByteArray().copyInto(head)
        head[7] = 3
        fun le64(at: Int, v: Long) { for (i in 0 until 8) head[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
        le64(8, 127L)
        le64(16, d.size.toLong())
        le64(40, 127L + d.size)
        le64(56, 127L + d.size)
        le64(64, body.size.toLong())
        head[97] = 1
        head[98] = 1
        head[100] = 0
        head[101] = 14
        return head + d + body
    }
}
