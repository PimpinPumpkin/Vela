package app.vela.offline

import app.vela.core.data.OfflineRank
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Offline search over the downloaded places archives, the ones the map draws its open places from
 * (Overture, AllThePlaces and OSM). The place packs are OSM alone, so before this a restaurant you
 * could tap on the map was not one you could search for with no signal.
 *
 * Reads the archive's deepest zoom, where every place is present, in rings of tiles around the
 * search point, and stops once it has enough matches or reaches [MAX_RINGS]. Past that radius the
 * packs, which cover a whole region, still answer.
 */
object PlacesArchiveSearch {
    const val LAYER = "places"
    /** At z17 a tile is about 240 m across at 40 degrees of latitude: 12 rings reach ~3 km. */
    const val MAX_RINGS = 12
    /** A streamed archive stops sooner: every ring is bytes over the network (8 rings, ~2 km). */
    const val MAX_RINGS_STREAMED = 8
    private const val MIN_RINGS = 2

    data class Feature(val props: Map<String, String>, val location: LatLng)

    fun search(files: List<File>, near: LatLng, query: String, want: Int = 60): List<Place> =
        files.flatMap { f -> runCatching { PmtilesReader.Archive.file(f).use { search(it, near, query, want, MAX_RINGS) } }.getOrDefault(emptyList()) }

    /** Rings of tiles out from [near] in one archive, a file or streamed. The first read takes
     *  rings 0-[MIN_RINGS] together and each later ring is one batch, so a streamed archive costs
     *  one index fetch (cached after the first search) and a few range requests. */
    fun search(archive: PmtilesReader.Archive, near: LatLng, query: String, want: Int = 60, maxRings: Int = MAX_RINGS): List<Place> {
        val out = ArrayList<Place>()
        val z = archive.header?.maxZoom ?: return out
        val (cx, cy) = PmtilesReader.tileOf(near.lat, near.lng, z)
        val n = 1 shl z
        fun ring(r: Int) = (cx - r..cx + r).flatMap { x -> (cy - r..cy + r).map { y -> x to y } }
            .filter { (x, y) -> maxOf(kotlin.math.abs(x - cx), kotlin.math.abs(y - cy)) == r && x in 0 until n && y in 0 until n }
        var r = 0
        while (r <= maxRings) {
            val rings = if (r == 0) (0..MIN_RINGS).toList() else listOf(r)
            val tiles = archive.tiles(z, rings.flatMap(::ring))
            for ((c, tile) in tiles) for (f in decode(tile, z, c.first, c.second)) {
                val p = f.props
                val name = p["name"] ?: continue
                if (!OfflineRank.matches(query, name, p["class"], p["addr"], p["brand"])) continue
                out.add(toPlace(f, name))
            }
            r = rings.last() + 1
            if (r > MIN_RINGS && out.size >= want) break
        }
        // A point near a tile edge is stored in the neighbor too (tippecanoe's buffer).
        return out.distinctBy { it.id }
    }

    /** The same Place a tap on the map builds from the feature (VelaMapView's open-places tap). */
    private fun toPlace(f: Feature, name: String): Place {
        val p = f.props
        return Place(
            id = "overture:" + (p["id"] ?: name.hashCode().toString()),
            name = name,
            location = f.location,
            category = p["class"],
            address = listOfNotNull(p["addr"], p["loc"]).joinToString(", ").ifBlank { null },
            phone = p["phone"],
            website = p["website"],
            hours = app.vela.core.util.OsmHours.lines(p["hours"]),
        )
    }

    /** The point features of the [LAYER] layer in one Mapbox Vector Tile, with their string and
     *  number properties. */
    fun decode(tile: ByteArray, z: Int, x: Int, y: Int): List<Feature> {
        val r = PmtilesReader.Varints(tile)
        while (r.hasMore()) {
            val key = r.next()
            val field = (key shr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> r.next()
                1 -> r.skip(8)
                2 -> {
                    val len = r.next().toInt()
                    if (len < 0 || r.position() + len > tile.size) return emptyList()
                    val body = tile.copyOfRange(r.position(), r.position() + len)
                    r.skip(len)
                    if (field == 3) layer(body, z, x, y)?.let { return it }
                }
                5 -> r.skip(4)
                else -> return emptyList()
            }
        }
        return emptyList()
    }

    private fun layer(b: ByteArray, z: Int, tx: Int, ty: Int): List<Feature>? {
        var name: String? = null
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<String?>()
        val feats = ArrayList<ByteArray>()
        val r = PmtilesReader.Varints(b)
        while (r.hasMore()) {
            val key = r.next()
            val field = (key shr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> { val v = r.next(); if (field == 5) extent = v.toInt() }
                1 -> r.skip(8)
                2 -> {
                    val len = r.next().toInt()
                    if (len < 0 || r.position() + len > b.size) return null
                    val sub = b.copyOfRange(r.position(), r.position() + len)
                    r.skip(len)
                    when (field) {
                        1 -> name = String(sub)
                        2 -> feats.add(sub)
                        3 -> keys.add(String(sub))
                        4 -> values.add(value(sub))
                    }
                }
                5 -> r.skip(4)
                else -> return null
            }
        }
        if (name != LAYER) return null
        return feats.mapNotNull { feature(it, keys, values, extent, z, tx, ty) }
    }

    private fun value(b: ByteArray): String? {
        val r = PmtilesReader.Varints(b)
        while (r.hasMore()) {
            val key = r.next()
            val field = (key shr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> {
                    val v = r.next()
                    return when (field) {
                        6 -> ((v ushr 1) xor -(v and 1)).toString() // sint64
                        7 -> (v != 0L).toString()
                        else -> v.toString()
                    }
                }
                1 -> {
                    val at = r.position(); r.skip(8)
                    return java.lang.Double.longBitsToDouble(PmtilesReader.le64(b, at)).toString()
                }
                2 -> { val len = r.next().toInt(); return r.string(len) }
                5 -> {
                    val at = r.position(); r.skip(4)
                    var bits = 0
                    for (i in 3 downTo 0) bits = (bits shl 8) or (b[at + i].toInt() and 0xFF)
                    return java.lang.Float.intBitsToFloat(bits).toString()
                }
                else -> return null
            }
        }
        return null
    }

    private fun feature(b: ByteArray, keys: List<String>, values: List<String?>, extent: Int, z: Int, tx: Int, ty: Int): Feature? {
        val tags = ArrayList<Int>()
        var geom: IntArray? = null
        var type = 0
        val r = PmtilesReader.Varints(b)
        while (r.hasMore()) {
            val key = r.next()
            val field = (key shr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> { val v = r.next(); if (field == 3) type = v.toInt() }
                1 -> r.skip(8)
                2 -> {
                    val len = r.next().toInt()
                    val end = r.position() + len
                    if (len < 0 || end > b.size) return null
                    val packed = ArrayList<Int>()
                    while (r.position() < end) packed.add(r.next().toInt())
                    if (field == 2) tags.addAll(packed) else if (field == 4) geom = packed.toIntArray()
                }
                5 -> r.skip(4)
                else -> return null
            }
        }
        if (type != 1) return null // points only
        val g = geom ?: return null
        if (g.size < 3 || (g[0] and 7) != 1) return null // MoveTo
        val px = (g[1] ushr 1) xor -(g[1] and 1)
        val py = (g[2] ushr 1) xor -(g[2] and 1)
        val n = (1 shl z).toDouble()
        val lng = (tx + px.toDouble() / extent) / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (ty + py.toDouble() / extent) / n))))
        val props = HashMap<String, String>()
        var i = 0
        while (i + 1 < tags.size) {
            val k = keys.getOrNull(tags[i]); val v = values.getOrNull(tags[i + 1])
            if (k != null && v != null && v != "null") props[k] = v
            i += 2
        }
        return Feature(props, LatLng(lat, lng))
    }
}
