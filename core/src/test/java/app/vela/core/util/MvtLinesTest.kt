package app.vela.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A tile written by hand: one layer, one line with a string and a number property. */
class MvtLinesTest {
    private fun varint(o: ByteArrayOutputStream, v: Long) { var x = v; while (x >= 0x80) { o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }; o.write(x.toInt()) }
    private fun field(o: ByteArrayOutputStream, n: Int, body: ByteArray) { varint(o, (n shl 3 or 2).toLong()); varint(o, body.size.toLong()); o.write(body) }
    private fun bytes(f: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(f).toByteArray()
    private fun zz(n: Int) = ((n shl 1) xor (n shr 31)).toLong()

    private fun tile(layerName: String) = bytes { t ->
        field(t, 3, bytes { l ->
            field(l, 1, layerName.toByteArray())
            field(l, 2, bytes { f ->
                field(f, 2, bytes { p -> listOf(0L, 0L, 1L, 1L).forEach { varint(p, it) } }) // maxspeed=v0, lanes=v1
                varint(f, (3 shl 3).toLong()); varint(f, 2) // a line
                // MoveTo(1) 0,2048 then LineTo(1) +4096,0: the tile's middle row, edge to edge
                field(f, 4, bytes { g -> listOf((1L shl 3) or 1, zz(0), zz(2048), (1L shl 3) or 2, zz(4096), zz(0)).forEach { varint(g, it) } })
            })
            field(l, 3, "maxspeed".toByteArray())
            field(l, 3, "lanes".toByteArray())
            field(l, 4, bytes { v -> field(v, 1, "35 mph".toByteArray()) })
            field(l, 4, bytes { v -> varint(v, (4 shl 3).toLong()); varint(v, 2) }) // int 2
        })
    }

    @Test fun readsTheLineItsPlaceAndItsProperties() {
        val lines = MvtLines.decode(tile("maxspeed"), 1, 0, 0, "maxspeed")
        assertEquals(1, lines.size)
        assertEquals("35 mph", lines[0].props["maxspeed"])
        assertEquals("2", lines[0].props["lanes"])
        // Tile 1/0/0 is the north-west quarter of the world: its middle row runs from 180 W to 0.
        assertEquals(-180.0, lines[0].points.first().lng, 1e-6)
        assertEquals(0.0, lines[0].points.last().lng, 1e-6)
        assertEquals(66.51, lines[0].points.first().lat, 0.01)
    }

    @Test fun anotherLayerIsNotRead() {
        assertEquals(0, MvtLines.decode(tile("roads"), 1, 0, 0, "maxspeed").size)
    }
}
