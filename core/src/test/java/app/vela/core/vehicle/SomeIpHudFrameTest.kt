package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SomeIpHudFrameTest {
    // A field read back off the wire: varint fields carry a Long, length fields carry bytes.
    private data class Field(val num: Int, val v: Long?, val bytes: ByteArray?)

    /** Minimal protobuf reader: returns the fields in wire order (no map, so order is checkable). */
    private fun parse(data: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        fun varint(): Long { var shift = 0; var r = 0L; while (true) { val b = data[i++].toInt() and 0xff; r = r or ((b and 0x7f).toLong() shl shift); if (b < 0x80) return r; shift += 7 } }
        while (i < data.size) {
            val tag = varint(); val num = (tag shr 3).toInt(); val wire = (tag and 0x7).toInt()
            when (wire) {
                0 -> out.add(Field(num, varint(), null))
                2 -> { val len = varint().toInt(); val b = data.copyOfRange(i, i + len); i += len; out.add(Field(num, null, b)) }
                else -> error("unexpected wire $wire")
            }
        }
        return out
    }

    /** The one outer field (1) is length-delimited; return the inner message bytes. */
    private fun inner(frame: ByteArray): List<Field> {
        val outer = parse(frame)
        assertEquals(1, outer.size)
        assertEquals(1, outer[0].num)
        return parse(outer[0].bytes!!)
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @Test fun `service and topic ids are the HUD contract`() {
        assertEquals(0x000B010A00010000L, SomeIpHudFrame.SERVICE_ID)
        assertEquals(0x0004010A00018001L, SomeIpHudFrame.TOPIC_ROAD_INFO)
    }

    @Test fun `guidance frame carries the proven fields in order`() {
        val f = inner(SomeIpHudFrame.guidance(f28 = 1, distanceMeters = 300, road = "Russell Blvd", etaHhMm = "14:05", png = png))
        assertEquals(listOf(2, 6, 7, 8, 9, 10, 16, 26, 28), f.map { it.num })
        assertEquals(2L, f[0].v)          // counter
        assertEquals(1L, f[1].v)          // layout = picture
        assertArrayEquals(ByteArray(0), f[2].bytes)   // lane picture empty
        assertArrayEquals(png, f[3].bytes)            // maneuver PNG in field 8
        assertEquals(300L, f[4].v)        // distance
        assertEquals("Russell Blvd", String(f[5].bytes!!, Charsets.UTF_8))
        assertEquals(2L, f[6].v)          // NavigatingStatus guiding
        assertEquals("14:05", String(f[7].bytes!!, Charsets.UTF_8))
        assertEquals(1L, f[8].v)          // f28 maneuver id
    }

    @Test fun `distance is floored at 11 and road capped at 200`() {
        val f = inner(SomeIpHudFrame.guidance(1, 5, "x".repeat(260), "14:05", png))
        assertEquals(11L, f.first { it.num == 9 }.v)
        assertEquals(200, f.first { it.num == 10 }.bytes!!.size)
    }

    @Test fun `a null picture emits an empty field 8, never omitted`() {
        val f = inner(SomeIpHudFrame.guidance(1, 300, "Main St", "14:05", png = null))
        assertArrayEquals(ByteArray(0), f.first { it.num == 8 }.bytes)
    }

    @Test fun `clear frame is status idle, layout 255`() {
        val f = inner(SomeIpHudFrame.clear())
        assertEquals(listOf(2, 6, 16), f.map { it.num })
        assertEquals(255L, f[1].v)
        assertEquals(1L, f[2].v)   // idle
    }

    @Test fun `field 16 and 28 use two-byte tags (protobuf varint over 15)`() {
        // Outer wrap (0x0A) then inner. Field 16 tag = 0x80 0x01, field 28 tag = 0xE0 0x01.
        val frame = SomeIpHudFrame.guidance(1, 300, "A", "14:05", png)
        fun contains(seq: List<Int>): Boolean {
            val b = frame.map { it.toInt() and 0xff }
            for (s in 0..b.size - seq.size) if (b.subList(s, s + seq.size) == seq) return true
            return false
        }
        assert(contains(listOf(0x80, 0x01))) // field 16 tag
        assert(contains(listOf(0xE0, 0x01))) // field 28 tag
    }

    @Test fun `maneuver id uses the native-arrow table`() {
        val left = Maneuver(ManeuverType.TURN_LEFT, "", LatLng(38.5449, -121.7405), 0.0, 0.0)
        val uturn = Maneuver(ManeuverType.UTURN, "", LatLng(38.5449, -121.7405), 0.0, 0.0)
        assertEquals(SomeIpHudArrows.of(left, SomeIpHudArrows.Table.NATIVE_ARROW), SomeIpHudFrame.maneuverId(left))
        assertEquals(7, SomeIpHudFrame.maneuverId(uturn)) // native-arrow U-turn = 7 (not the stock 9)
        assertEquals(0, SomeIpHudFrame.maneuverId(null))
        assertNull(null)
    }
}
