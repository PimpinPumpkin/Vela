package app.vela.core.util

import app.vela.core.model.LatLng
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * The line features of ONE layer of a vector tile, with their properties, read straight from the
 * protobuf (Tile.layers 3 -> Layer name 1, features 2, keys 3, values 4, extent 5 -> Feature tags
 * 2, type 3, geometry 4). For data Vela reads itself instead of mounting on the map: a mounted
 * source loads every tile the tilted view covers, where a lookup under the car needs one.
 */
object MvtLines {
    class Line(val props: Map<String, String>, val points: List<LatLng>)

    fun decode(raw: ByteArray, z: Int, x: Int, y: Int, layer: String): List<Line> {
        val b = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() } else raw
        val out = ArrayList<Line>()
        val tile = Pb(b, 0, b.size)
        while (tile.more()) {
            val (field, wire) = tile.key()
            if (field == 3 && wire == 2) {
                val (s, e) = tile.lenRange()
                readLayer(b, s, e, z, x, y, layer, out)
            } else tile.skip(wire)
        }
        return out
    }

    private fun readLayer(b: ByteArray, s: Int, e: Int, z: Int, x: Int, y: Int, want: String, out: MutableList<Line>) {
        var name = ""
        val features = ArrayList<IntRange>()
        val keys = ArrayList<String>()
        val values = ArrayList<String?>()
        var extent = 4096
        val p = Pb(b, s, e)
        while (p.more()) {
            val (field, wire) = p.key()
            when {
                field == 1 && wire == 2 -> name = p.string()
                field == 2 && wire == 2 -> p.lenRange().let { features += it.first until it.second }
                field == 3 && wire == 2 -> keys += p.string()
                field == 4 && wire == 2 -> p.lenRange().let { values += readValue(b, it.first, it.second) }
                field == 5 && wire == 0 -> extent = p.varint().toInt()
                else -> p.skip(wire)
            }
        }
        if (name != want) return
        val n = (1 shl z).toDouble()
        fun toLatLng(gx: Int, gy: Int): LatLng {
            val lng = (x + gx.toDouble() / extent) / n * 360.0 - 180.0
            val my = PI * (1 - 2 * (y + gy.toDouble() / extent) / n)
            return LatLng(Math.toDegrees(atan(sinh(my))), lng)
        }
        for (range in features) {
            val f = Pb(b, range.first, range.last + 1)
            var tags = IntArray(0)
            var type = 0
            var geom = IntArray(0)
            while (f.more()) {
                val (field, wire) = f.key()
                when {
                    field == 2 && wire == 2 -> tags = f.packed()
                    field == 3 && wire == 0 -> type = f.varint().toInt()
                    field == 4 && wire == 2 -> geom = f.packed()
                    else -> f.skip(wire)
                }
            }
            if (type != 2) continue // lines only
            val props = HashMap<String, String>()
            var i = 0
            while (i + 1 < tags.size) {
                val k = keys.getOrNull(tags[i]); val v = values.getOrNull(tags[i + 1])
                if (k != null && v != null) props[k] = v
                i += 2
            }
            // Geometry commands: MoveTo (1) starts a part, LineTo (2) extends it; zigzag deltas.
            var cx = 0; var cy = 0; var gi = 0
            var part = ArrayList<LatLng>()
            while (gi < geom.size) {
                val cmd = geom[gi] and 7; val count = geom[gi] ushr 3; gi++
                if (cmd != 1 && cmd != 2) continue
                var k = 0
                while (k < count && gi + 1 < geom.size) {
                    cx += zigzag(geom[gi]); cy += zigzag(geom[gi + 1]); gi += 2
                    if (cmd == 1) { if (part.size >= 2) out += Line(props, part); part = ArrayList() }
                    part += toLatLng(cx, cy)
                    k++
                }
            }
            if (part.size >= 2) out += Line(props, part)
        }
    }

    /** A value as text: strings as they are, numbers printed (a whole number without ".0"). */
    private fun readValue(b: ByteArray, s: Int, e: Int): String? {
        val p = Pb(b, s, e)
        while (p.more()) {
            val (field, wire) = p.key()
            when {
                field == 1 && wire == 2 -> return p.string()
                field == 2 && wire == 5 -> return num(java.lang.Float.intBitsToFloat(p.fixed32()).toDouble())
                field == 3 && wire == 1 -> return num(java.lang.Double.longBitsToDouble(p.fixed64()))
                (field == 4 || field == 5) && wire == 0 -> return p.varint().toString()
                field == 6 && wire == 0 -> return p.varint().let { (it ushr 1) xor -(it and 1) }.toString()
                field == 7 && wire == 0 -> return (p.varint() != 0L).toString()
                else -> p.skip(wire)
            }
        }
        return null
    }

    private fun num(d: Double) = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)

    private class Pb(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var r = 0L; var shift = 0
            while (pos < end) {
                val c = b[pos++].toInt()
                r = r or ((c and 0x7f).toLong() shl shift)
                if (c and 0x80 == 0) break
                shift += 7
            }
            return r
        }
        fun fixed32(): Int { var r = 0; for (i in 0 until 4) r = r or ((b[pos + i].toInt() and 0xff) shl (8 * i)); pos += 4; return r }
        fun fixed64(): Long { var r = 0L; for (i in 0 until 8) r = r or ((b[pos + i].toLong() and 0xff) shl (8 * i)); pos += 8; return r }
        fun key(): Pair<Int, Int> { val k = varint().toInt(); return (k ushr 3) to (k and 7) }
        fun lenRange(): Pair<Int, Int> { val n = varint().toInt(); val s = pos; pos += n; return s to pos }
        fun string(): String { val (s, e) = lenRange(); return String(b, s, e - s, Charsets.UTF_8) }
        fun packed(): IntArray {
            val (s, e) = lenRange()
            val p = Pb(b, s, e)
            val out = ArrayList<Int>()
            while (p.more()) out += p.varint().toInt()
            return out.toIntArray()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> lenRange()
                5 -> pos += 4
                else -> pos = end
            }
        }
    }
}
