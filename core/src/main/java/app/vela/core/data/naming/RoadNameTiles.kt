package app.vela.core.data.naming

import app.vela.core.model.LatLng
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A named street line from the map's vector tiles (OpenMapTiles `transportation_name`). */
data class NamedLine(val name: String, val ref: String?, val cls: String?, val points: List<LatLng>, val bridge: Boolean = false)

/**
 * The street names [LineNamer] labels a line with, read from the same vector tiles the map draws
 * (z14, OpenMapTiles schema). The app sets [fetch]: a downloaded region's basemap archive when one
 * holds the tile, else the live tile host. Decoded tiles are kept in a small LRU, so a reroute
 * along the same streets costs nothing.
 */
object RoadNameTiles {
    const val ZOOM = 14
    private const val MAX_TILES = 48

    /** Raw tile bytes (gzip or plain) for z/x/y, or null. Set by the app at start. */
    @Volatile var fetch: (suspend (z: Int, x: Int, y: Int) -> ByteArray?)? = null

    private val cache = object : LinkedHashMap<Long, List<NamedLine>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, List<NamedLine>>) = size > 96
    }

    /** Forget every tile read so far (tests). */
    internal fun clearCache() { synchronized(cache) { cache.clear() } }

    /** Every named line in the tiles [poly] passes through (plus a ~60 m margin), or null when the
     *  tiles could not be read or the line crosses more than [MAX_TILES] of them. With [fetchMissing]
     *  false only tiles already read are used and nothing is requested: for a route past its
     *  deadline, which takes the names that are in hand. */
    suspend fun linesAlong(poly: List<LatLng>, fetchMissing: Boolean = true): List<NamedLine>? {
        val f = fetch ?: return null
        val tiles = tilesAlong(poly)
        if (tiles.isEmpty() || tiles.size > MAX_TILES) return null
        val decoded = coroutineScope {
            tiles.map { (x, y) ->
                async {
                    val key = (x.toLong() shl 32) or y.toLong()
                    synchronized(cache) { cache[key] }?.let { return@async it }
                    if (!fetchMissing) return@async null
                    val bytes = runCatching { f(ZOOM, x, y) }.getOrNull() ?: return@async null
                    val lines = runCatching { decode(bytes, ZOOM, x, y) }.getOrNull() ?: return@async null
                    synchronized(cache) { cache[key] = lines }
                    lines
                }
            }.awaitAll()
        }
        if (decoded.count { it == null } * 2 > decoded.size) return null
        return decoded.filterNotNull().flatten()
    }

    internal fun tilesAlong(poly: List<LatLng>): Set<Pair<Int, Int>> {
        val out = LinkedHashSet<Pair<Int, Int>>()
        val pad = 60.0 / 111_320.0
        fun add(p: LatLng) {
            for (dy in listOf(-pad, 0.0, pad)) for (dx in listOf(-pad, 0.0, pad)) {
                out += tileX(p.lng + dx / cos(Math.toRadians(p.lat)).coerceAtLeast(0.2)) to tileY(p.lat + dy)
            }
        }
        for ((a, b) in poly.zipWithNext()) {
            val steps = (a.distance(b) / 200.0).toInt().coerceAtLeast(1)
            for (i in 0..steps) add(LatLng(a.lat + (b.lat - a.lat) * i / steps, a.lng + (b.lng - a.lng) * i / steps))
        }
        if (poly.size == 1) add(poly[0])
        return out
    }

    private fun tileX(lng: Double) = floor((lng + 180.0) / 360.0 * (1 shl ZOOM)).toInt()
    private fun tileY(lat: Double): Int {
        val r = Math.toRadians(lat)
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl ZOOM)).toInt()
    }

    private fun LatLng.distance(o: LatLng): Double {
        val dy = (lat - o.lat) * 111_320.0
        val dx = (lng - o.lng) * 111_320.0 * cos(Math.toRadians(lat))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** The named street lines of one tile. Pure protobuf reading, no library: Tile.layers (3) ->
     *  Layer name (1), features (2), keys (3), values (4), extent (5) -> Feature tags (2), type (3),
     *  geometry (4). Only line features of `transportation_name` that carry a name are kept. */
    fun decode(raw: ByteArray, z: Int, x: Int, y: Int): List<NamedLine> {
        val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() } else raw
        val out = ArrayList<NamedLine>()
        val bridges = ArrayList<Pair<String?, List<LatLng>>>()
        val tile = Pb(bytes, 0, bytes.size)
        while (tile.more()) {
            val (field, wire) = tile.key()
            if (field == 3 && wire == 2) {
                val (s, e) = tile.lenRange()
                readLayer(bytes, s, e, z, x, y, out, bridges)
            } else tile.skip(wire)
        }
        if (bridges.isEmpty()) return out
        // A named line lying on a bridge segment of the same class is elevated: a flyover's name must
        // not label the street a walker takes underneath it. The names layer carries no bridge flag.
        return out.map { l ->
            val segs = bridges.filter { it.first == l.cls }.map { it.second }
            if (segs.isEmpty()) l else {
                val on = l.points.count { p -> segs.any { b -> b.zipWithNext().any { (a, c) -> segDist(p, a, c) < 6.0 } } }
                if (on * 2 > l.points.size) l.copy(bridge = true) else l
            }
        }
    }

    private fun segDist(p: LatLng, a: LatLng, b: LatLng): Double {
        val k = 111_320.0 * cos(Math.toRadians(p.lat))
        val bx = (b.lng - a.lng) * k; val by = (b.lat - a.lat) * 111_320.0
        val px = (p.lng - a.lng) * k; val py = (p.lat - a.lat) * 111_320.0
        val l = bx * bx + by * by
        val t = if (l == 0.0) 0.0 else ((px * bx + py * by) / l).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(px - t * bx, py - t * by)
    }

    private fun readLayer(
        b: ByteArray, s: Int, e: Int, z: Int, x: Int, y: Int,
        out: MutableList<NamedLine>, bridges: MutableList<Pair<String?, List<LatLng>>>,
    ) {
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
        if (name != "transportation_name" && name != "transportation") return
        val bridgeLayer = name == "transportation"
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
            var fname: String? = null; var ref: String? = null; var cls: String? = null; var brunnel: String? = null
            var i = 0
            while (i + 1 < tags.size) {
                val k = keys.getOrNull(tags[i]); val v = values.getOrNull(tags[i + 1])
                when (k) { "name" -> fname = v; "ref" -> ref = v; "class" -> cls = v; "brunnel" -> brunnel = v }
                i += 2
            }
            if (bridgeLayer) {
                if (brunnel != "bridge") continue
                fname = "\u0000"
            } else if (fname.isNullOrBlank() && ref.isNullOrBlank()) continue
            for (part in lineParts(geom) { gx, gy -> toLatLng(gx, gy) }) out += NamedLine(fname ?: ref!!, ref, cls, part)
        }
        if (bridgeLayer) {
            for (l in out.filter { it.name == "\u0000" }) bridges += l.cls to l.points
            out.removeAll { it.name == "\u0000" }
        }
    }

    /** A line feature's geometry as its parts. Commands: MoveTo (1) starts a part, LineTo (2)
     *  extends it; zigzag deltas in tile units. A part needs two points. */
    private fun lineParts(geom: IntArray, toLatLng: (Int, Int) -> LatLng): List<List<LatLng>> {
        val parts = ArrayList<List<LatLng>>()
        var cx = 0; var cy = 0; var gi = 0
        var part = ArrayList<LatLng>()
        while (gi < geom.size) {
            val cmd = geom[gi] and 7; val count = geom[gi] ushr 3; gi++
            when (cmd) {
                1, 2 -> repeat(count) {
                    if (gi + 1 >= geom.size + 1) return@repeat
                    cx += zigzag(geom[gi]); cy += zigzag(geom[gi + 1]); gi += 2
                    if (cmd == 1 && part.size >= 2) { parts += part; part = ArrayList() }
                    else if (cmd == 1) part = ArrayList()
                    part += toLatLng(cx, cy)
                }
                else -> {}
            }
        }
        if (part.size >= 2) parts += part
        return parts
    }

    /** A road from the map's `transportation` layer with its class ("primary", "minor",
     *  "service", ...) and, for a service road, its kind ("parking_aisle", "driveway", "alley").
     *  Named or not: a parking aisle has no name, and that is the one this is read for. */
    data class RoadLine(val cls: String?, val service: String?, val points: List<LatLng>)

    // Lines a car does not drive on; a footpath along a parking aisle must not stand in for it.
    private val NOT_CAR_ROADS = setOf("path", "rail", "transit", "ferry", "aerialway", "bridleway", "cycleway", "pier")

    private val roadCache = object : LinkedHashMap<Long, List<RoadLine>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, List<RoadLine>>) = size > 16
    }

    /** Every car road in the tiles around [poly] (a short stretch: at most 8 tiles), or null when
     *  the tiles could not be read. */
    suspend fun roadsAlong(poly: List<LatLng>): List<RoadLine>? {
        val f = fetch ?: return null
        val tiles = tilesAlong(poly)
        if (tiles.isEmpty() || tiles.size > 8) return null
        val decoded = coroutineScope {
            tiles.map { (x, y) ->
                async {
                    val key = (x.toLong() shl 32) or y.toLong()
                    synchronized(roadCache) { roadCache[key] }?.let { return@async it }
                    val bytes = runCatching { f(ZOOM, x, y) }.getOrNull() ?: return@async null
                    val roads = runCatching { decodeRoads(bytes, ZOOM, x, y) }.getOrNull() ?: return@async null
                    synchronized(roadCache) { roadCache[key] = roads }
                    roads
                }
            }.awaitAll()
        }
        if (decoded.any { it == null }) return null
        return decoded.filterNotNull().flatten()
    }

    /** The car roads of one tile's `transportation` layer, with their class and service kind. */
    fun decodeRoads(raw: ByteArray, z: Int, x: Int, y: Int): List<RoadLine> {
        val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() } else raw
        val out = ArrayList<RoadLine>()
        val tile = Pb(bytes, 0, bytes.size)
        while (tile.more()) {
            val (field, wire) = tile.key()
            if (field == 3 && wire == 2) {
                val (s, e) = tile.lenRange()
                readRoadsLayer(bytes, s, e, z, x, y, out)
            } else tile.skip(wire)
        }
        return out
    }

    private fun readRoadsLayer(b: ByteArray, s: Int, e: Int, z: Int, x: Int, y: Int, out: MutableList<RoadLine>) {
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
        if (name != "transportation") return
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
            var cls: String? = null; var service: String? = null; var subclass: String? = null
            var i = 0
            while (i + 1 < tags.size) {
                val k = keys.getOrNull(tags[i]); val v = values.getOrNull(tags[i + 1])
                when (k) { "class" -> cls = v; "service" -> service = v; "subclass" -> subclass = v }
                i += 2
            }
            if (cls in NOT_CAR_ROADS || subclass in NOT_CAR_ROADS) continue
            for (part in lineParts(geom) { gx, gy -> toLatLng(gx, gy) }) out += RoadLine(cls, service, part)
        }
    }

    private fun readValue(b: ByteArray, s: Int, e: Int): String? {
        val p = Pb(b, s, e)
        while (p.more()) {
            val (field, wire) = p.key()
            if (field == 1 && wire == 2) return p.string()
            p.skip(wire)
        }
        return null
    }

    private fun zigzag(n: Int) = (n ushr 1) xor -(n and 1)

    /** A protobuf reader over bytes[pos, end). */
    private class Pb(val b: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0; var r = 0L
            while (true) {
                val c = b[pos++].toInt() and 0xff
                r = r or ((c and 0x7f).toLong() shl shift)
                if (c < 0x80) return r
                shift += 7
            }
        }
        fun key(): Pair<Int, Int> { val k = varint().toInt(); return (k ushr 3) to (k and 7) }
        fun lenRange(): Pair<Int, Int> { val n = varint().toInt(); val s = pos; pos += n; return s to pos }
        fun string(): String { val (s, e) = lenRange(); return String(b, s, e - s, Charsets.UTF_8) }
        fun packed(): IntArray {
            val (s, e) = lenRange()
            val q = Pb(b, s, e); val out = ArrayList<Int>()
            while (q.more()) out += q.varint().toInt()
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
