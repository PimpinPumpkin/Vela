package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * PAINTED ROADS, a test (user 2026-10-03, "crosswalks, medians, lanes, bike lanes"). Turns
 * OpenStreetMap's road tags into the markings Google draws at close zoom: a double yellow center
 * line on two-way roads with a lane count, dashed white lines between lanes, green bike-lane strips
 * at the road edge, and zebra crosswalks. Each mark carries an offset from the way's centerline in
 * meters (positive = right of the way's direction), so the map can draw it at real scale. Only ways
 * that SAY how many lanes they have get lane lines: guessing would paint lanes that are not there.
 *
 * Fetched from Overpass for the viewport while the developer dial is on; a shipping version would
 * bake these into tiles (ROADMAP "Richer roads").
 */
object PaintedRoads {
    enum class Kind { SURFACE, CENTER, LANE, BIKE, CROSSWALK }

    /** [widthM] is set for SURFACE only: the carriageway the markings need, lanes plus bike lanes. */
    data class Mark(val kind: Kind, val points: List<LatLng>, val offsetM: Double, val widthM: Double = 0.0)

    data class Way(val tags: Map<String, String>, val points: List<LatLng>)

    /** Typical US travel-lane width, and how far a bike lane's middle sits outside the last lane. */
    const val LANE_M = 3.3
    const val BIKE_OUT_M = 0.9

    private val CENTERED_CLASSES = setOf("tertiary", "secondary", "primary", "trunk")
    private val NO_LANE_LINES = setOf("service", "living_street", "track", "path", "footway", "cycleway", "pedestrian", "steps")
    private val PAINTED_CROSSING = setOf("marked", "zebra", "uncontrolled", "traffic_signals", "pelican", "toucan", "pedestrian_signals")

    /** How far markings stop short of an intersection: half a crossing street plus a crosswalk. */
    const val JUNCTION_TRIM_M = 8.0
    private val JUNCTION_CLASSES = Regex("^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)(_link)?$")

    private fun key(p: LatLng) = Math.round(p.lat * 1e7) * 3_600_000_000L + Math.round(p.lng * 1e7)

    /** Points where streets cross: shared by three or more streets, or by two with different names.
     *  Service roads and driveways do not count, or every driveway would cut the center line. */
    private fun junctions(ways: List<Way>): Set<Long> {
        val names = HashMap<Long, MutableList<String>>()
        for (w in ways) {
            val hw = w.tags["highway"] ?: continue
            if (!JUNCTION_CLASSES.matches(hw)) continue
            val nm = w.tags["name"] ?: w.tags["ref"] ?: "#" + System.identityHashCode(w)
            for (p in w.points.toSet()) names.getOrPut(key(p)) { ArrayList() } += nm
        }
        return names.filterValues { it.size >= 3 || (it.size == 2 && it[0] != it[1]) }.keys
    }

    /** [pts] cut at junction vertices, each piece trimmed [JUNCTION_TRIM_M] at a junction end. */
    private fun pieces(pts: List<LatLng>, jn: Set<Long>): List<List<LatLng>> {
        val cuts = pts.indices.filter { it == 0 || it == pts.size - 1 || key(pts[it]) in jn }
        val out = ArrayList<List<LatLng>>()
        for (c in 0 until cuts.size - 1) {
            var piece = pts.subList(cuts[c], cuts[c + 1] + 1)
            if (key(piece.first()) in jn) piece = trimStart(piece, JUNCTION_TRIM_M) ?: continue
            if (key(piece.last()) in jn) piece = trimStart(piece.reversed(), JUNCTION_TRIM_M)?.reversed() ?: continue
            if (piece.size >= 2) out += piece
        }
        return out
    }

    private fun trimStart(pts: List<LatLng>, m: Double): List<LatLng>? {
        var left = m
        for (i in 0 until pts.size - 1) {
            val seg = pts[i].distanceTo(pts[i + 1])
            if (seg > left) {
                val t = left / seg
                val cut = LatLng(pts[i].lat + (pts[i + 1].lat - pts[i].lat) * t, pts[i].lng + (pts[i + 1].lng - pts[i].lng) * t)
                return listOf(cut) + pts.subList(i + 1, pts.size)
            }
            left -= seg
        }
        return null // shorter than the trim: nothing left to paint
    }

    fun build(ways: List<Way>): List<Mark> {
        val out = ArrayList<Mark>()
        val jn = junctions(ways)
        for (w0 in ways) for ((pi, piece) in (if (w0.tags.containsKey("highway") && !w0.tags.containsKey("footway") && w0.tags["highway"] != "crossing") pieces(w0.points, jn) else listOf(w0.points)).withIndex()) {
            // Markings stop short of intersections; the surface is the whole street, once.
            val w = Way(w0.tags, piece)
            val surfacePoints = if (pi == 0) w0.points else null
            if (w.points.size < 2) continue
            val t = w.tags
            if (t["footway"] == "crossing" || t["cycleway"] == "crossing" || t["highway"] == "crossing") {
                val c = t["crossing"]
                val markings = t["crossing:markings"]
                val painted = when {
                    markings != null -> markings != "no"
                    c != null -> c in PAINTED_CROSSING
                    else -> false
                }
                if (painted) out += Mark(Kind.CROSSWALK, w.points, 0.0)
                continue
            }
            val hw = t["highway"] ?: continue
            if (hw in NO_LANE_LINES) continue
            val oneway = t["oneway"] in setOf("yes", "true", "1", "-1") || t["junction"] == "roundabout" ||
                hw == "motorway" || hw.endsWith("_link") && t["oneway"] != "no"
            val lanes = t["lanes"]?.trim()?.toIntOrNull()?.takeIf { it in 1..10 }
            val both = t["lanes:both_ways"]?.toIntOrNull()?.coerceIn(0, 1) ?: 0
            // Lanes per side: the tags when present, else an even split of the total.
            val fwd: Int; val bwd: Int
            if (oneway) { fwd = lanes ?: 1; bwd = 0 } else {
                val f = t["lanes:forward"]?.toIntOrNull()
                val b = t["lanes:backward"]?.toIntOrNull()
                val total = lanes ?: ((f ?: 1) + (b ?: 1) + both)
                val rest = (total - both).coerceAtLeast(2)
                fwd = f ?: (b?.let { (rest - it).coerceAtLeast(1) } ?: (rest + 1) / 2)
                bwd = b ?: (rest - fwd).coerceAtLeast(1)
            }
            val width = (fwd + bwd + both) * LANE_M
            val left = -width / 2
            // Bike lanes: cycleway=lane is both sides on a two-way road and the right side on a one-way.
            fun lane(v: String?) = v == "lane"
            val right = lane(t["cycleway:right"]) || lane(t["cycleway:both"]) || lane(t["cycleway"])
            val leftBike = lane(t["cycleway:left"]) || lane(t["cycleway:both"]) || (lane(t["cycleway"]) && !oneway)
            // A two-way street of tertiary class or above, or one with painted bike lanes, is painted
            // with a center line even untagged: downtown streets rarely carry a lane count, and a
            // street that has bike lanes painted has its center painted too.
            val assumeTwo = !oneway && lanes == null && (hw in CENTERED_CLASSES || right || leftBike)
            if (lanes != null || assumeTwo || t.containsKey("lanes:forward") || t.containsKey("lanes:backward")) {
                if (oneway) {
                    for (k in 1 until fwd) out += Mark(Kind.LANE, w.points, left + k * LANE_M)
                } else {
                    // Backward lanes on the left of the way's direction, then the center, then forward.
                    val center = left + bwd * LANE_M
                    for (k in 1 until bwd) out += Mark(Kind.LANE, w.points, left + k * LANE_M)
                    if (both > 0) {
                        out += Mark(Kind.CENTER, w.points, center)
                        out += Mark(Kind.CENTER, w.points, center + LANE_M)
                    } else out += Mark(Kind.CENTER, w.points, center)
                    val fStart = center + both * LANE_M
                    for (k in 1 until fwd) out += Mark(Kind.LANE, w.points, fStart + k * LANE_M)
                }
            }
            if (right) out += Mark(Kind.BIKE, w.points, width / 2 + BIKE_OUT_M)
            if (leftBike) out += Mark(Kind.BIKE, w.points, -(width / 2 + BIKE_OUT_M))
            if (surfacePoints != null && (lanes != null || right || leftBike)) {
                // The asphalt those markings need, centered on the way (a bike lane on one side only
                // still widens it symmetrically; the extra half-lane reads as a shoulder).
                val bikes = (if (right) 1 else 0) + (if (leftBike) 1 else 0)
                out += Mark(Kind.SURFACE, surfacePoints, 0.0, width + bikes * 2 * BIKE_OUT_M + 0.6)
            }
        }
        return out
    }

    @Serializable private data class Geom(val lat: Double, val lon: Double)
    @Serializable private data class El(val type: String = "", val tags: Map<String, String>? = null, val geometry: List<Geom>? = null)
    @Serializable private data class Resp(val elements: List<El> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** Ways with lanes, bike lanes or a crossing in the box; null when every mirror failed. */
    @OptIn(ExperimentalSerializationApi::class)
    fun fetch(http: OkHttpClient, south: Double, west: Double, north: Double, east: Double): List<Way>? {
        val box = "($south,$west,$north,$east)"
        val query = "[out:json][timeout:25];(" +
            "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)(_link)?$\"]$box;" +
            "way[\"footway\"=\"crossing\"]$box;way[\"cycleway\"=\"crossing\"]$box;" +
            ");out tags geom;"
        val slow = http.newBuilder().callTimeout(40, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        return OverpassEndpoints.run(slow, query) { body -> parse(body.byteStream()) }
    }

    /** The same reply read from [url] (a saved Overpass answer served locally while iterating, so a
     *  test does not keep asking the public servers); null on any failure. */
    fun fetchFrom(http: OkHttpClient, url: String): List<Way>? = runCatching {
        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.byteStream()?.let { parse(it) }
        }
    }.getOrNull()

    @OptIn(ExperimentalSerializationApi::class)
    private fun parse(stream: java.io.InputStream): List<Way> =
        json.decodeFromStream<Resp>(stream).elements.mapNotNull { e ->
            val g = e.geometry ?: return@mapNotNull null
            if (e.type != "way" || g.size < 2) return@mapNotNull null
            Way(e.tags.orEmpty(), g.map { LatLng(it.lat, it.lon) })
        }
}
