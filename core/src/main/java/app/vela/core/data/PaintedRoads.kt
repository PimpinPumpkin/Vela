package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.bearingTo
import app.vela.core.model.destinationPoint
import app.vela.core.model.distanceTo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * PAINTED ROADS, a developer test (user 2026-10-03). Turns OpenStreetMap's road tags into the
 * markings Google draws at close zoom: road surface as wide as its lanes, a double yellow center
 * line, dashed white lane lines, green bike lanes, zebra crosswalks, white stop lines, a turn arrow
 * per lane before an intersection, and the median between the two halves of a divided road.
 *
 * Line marks carry an offset from the way's centerline in meters (positive = right of the way's
 * direction) so the map draws them at real scale; arrows and stop lines are placed geometry. Only
 * ways that say how many lanes they have (a lane count, per-direction counts or turn lanes) get lane
 * lines; a two-way street of tertiary class or above, or with painted bike lanes, gets a center line
 * untagged. Markings stop [JUNCTION_TRIM_M] short of an intersection.
 *
 * Read live from Overpass for the view, or from a California bake (`scripts/bake-painted-roads.sh`)
 * while the developer dial is on. Not shipped (ROADMAP "Richer roads").
 */
object PaintedRoads {
    enum class Kind { SURFACE, MEDIAN, CENTER, LANE, BIKE, CROSSWALK, STOP, ARROW }

    /** [widthM]: SURFACE and MEDIAN width. [icon], [rotDeg]: ARROW only (one point). */
    data class Mark(
        val kind: Kind,
        val points: List<LatLng>,
        val offsetM: Double = 0.0,
        val widthM: Double = 0.0,
        val icon: String = "",
        val rotDeg: Double = 0.0,
    )

    data class Way(val tags: Map<String, String>, val points: List<LatLng>)
    data class Node(val tags: Map<String, String>, val point: LatLng)

    /** Typical US travel-lane width, and how far a bike lane's middle sits outside the last lane. */
    const val LANE_M = 3.3
    const val BIKE_OUT_M = 0.9
    /** How far markings stop short of an intersection: half a crossing street plus a crosswalk. */
    const val JUNCTION_TRIM_M = 8.0
    /** Stop lines sit just behind the crosswalk; arrows a little further back. */
    const val STOP_BACK_M = 8.5
    const val ARROW_BACK_M = 18.0

    private val CENTERED_CLASSES = setOf("tertiary", "secondary", "primary", "trunk")
    private val NO_LANE_LINES = setOf("service", "living_street", "track", "path", "footway", "cycleway", "pedestrian", "steps")
    private val PAINTED_CROSSING = setOf("marked", "zebra", "uncontrolled", "traffic_signals", "pelican", "toucan", "pedestrian_signals")
    private val JUNCTION_CLASSES = Regex("^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)(_link)?$")

    private fun key(p: LatLng) = Math.round(p.lat * 1e7) * 3_600_000_000L + Math.round(p.lng * 1e7)

    /** A way's carriageway in its own frame: offsets right of the way's direction, meters. */
    private class Layout(
        val oneway: Boolean, val reversed: Boolean, val fwd: Int, val bwd: Int, val both: Int,
        val width: Double, val center: Double, val painted: Boolean,
        val turnFwd: List<String>?, val turnBwd: List<String>?,
        val bikeRight: Boolean, val bikeLeft: Boolean,
    ) {
        val left get() = -width / 2
    }

    private fun turnList(v: String?): List<String>? = v?.split('|')?.map { it.trim() }

    private fun layout(t: Map<String, String>): Layout? {
        val hw = t["highway"] ?: return null
        if (!JUNCTION_CLASSES.matches(hw)) return null
        val reversed = t["oneway"] == "-1"
        val oneway = t["oneway"] in setOf("yes", "true", "1", "-1") || t["junction"] == "roundabout" ||
            hw == "motorway" || hw.endsWith("_link") && t["oneway"] != "no"
        val turnFwd = turnList(if (oneway) t["turn:lanes"] ?: t["turn:lanes:forward"] else t["turn:lanes:forward"])
        val turnBwd = if (oneway) null else turnList(t["turn:lanes:backward"])
        val lanes = t["lanes"]?.trim()?.toIntOrNull()?.takeIf { it in 1..10 }
        val both = t["lanes:both_ways"]?.toIntOrNull()?.coerceIn(0, 1) ?: 0
        val fwd: Int; val bwd: Int
        if (oneway) { fwd = lanes ?: turnFwd?.size ?: 1; bwd = 0 } else {
            val f = t["lanes:forward"]?.toIntOrNull() ?: turnFwd?.size
            val b = t["lanes:backward"]?.toIntOrNull() ?: turnBwd?.size
            val total = lanes ?: ((f ?: 1) + (b ?: 1) + both)
            val rest = (total - both).coerceAtLeast(2)
            fwd = f ?: (b?.let { (rest - it).coerceAtLeast(1) } ?: (rest + 1) / 2)
            bwd = b ?: (rest - fwd).coerceAtLeast(1)
        }
        fun lane(v: String?) = v == "lane"
        val bikeRight = lane(t["cycleway:right"]) || lane(t["cycleway:both"]) || lane(t["cycleway"])
        val bikeLeft = lane(t["cycleway:left"]) || lane(t["cycleway:both"]) || (lane(t["cycleway"]) && !oneway)
        val counted = lanes != null || t.containsKey("lanes:forward") || t.containsKey("lanes:backward") || turnFwd != null || turnBwd != null
        val painted = hw !in NO_LANE_LINES && (counted || (!oneway && (hw in CENTERED_CLASSES || bikeRight || bikeLeft)))
        val width = (fwd + bwd + both) * LANE_M
        return Layout(oneway, reversed, fwd, bwd, both, width, -width / 2 + bwd * LANE_M, painted, turnFwd, turnBwd, bikeRight, bikeLeft)
    }

    /** Points where streets cross: shared by three or more streets, or by two with different names.
     *  Service roads and driveways do not count, or every driveway would cut the center line. */
    private fun junctions(ways: List<Way>): Set<Long> {
        val names = HashMap<Long, MutableList<String>>()
        for ((i, w) in ways.withIndex()) {
            val hw = w.tags["highway"] ?: continue
            if (!JUNCTION_CLASSES.matches(hw)) continue
            val nm = w.tags["name"] ?: w.tags["ref"] ?: "#$i"
            for (p in w.points.toSet()) names.getOrPut(key(p)) { ArrayList(2) } += nm
        }
        return names.filterValues { it.size >= 3 || (it.size == 2 && it[0] != it[1]) }.keys
    }

    /** [pts] cut at junction vertices, each piece trimmed [JUNCTION_TRIM_M] at a junction end. */
    private fun pieces(pts: List<LatLng>, jn: Set<Long>): List<Triple<List<LatLng>, Boolean, Boolean>> {
        val cuts = pts.indices.filter { it == 0 || it == pts.size - 1 || key(pts[it]) in jn }
        val out = ArrayList<Triple<List<LatLng>, Boolean, Boolean>>()
        for (c in 0 until cuts.size - 1) {
            var piece = pts.subList(cuts[c], cuts[c + 1] + 1)
            val startJ = key(piece.first()) in jn
            val endJ = key(piece.last()) in jn
            if (startJ) piece = trimStart(piece, JUNCTION_TRIM_M) ?: continue
            if (endJ) piece = trimStart(piece.reversed(), JUNCTION_TRIM_M)?.reversed() ?: continue
            if (piece.size >= 2) out += Triple(piece, startJ, endJ)
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

    /** The point [m] meters along [pts] and the heading there, or null past the end. */
    private fun along(pts: List<LatLng>, m: Double): Pair<LatLng, Double>? {
        var left = m
        for (i in 0 until pts.size - 1) {
            val seg = pts[i].distanceTo(pts[i + 1])
            if (seg >= left && seg > 0) {
                val t = left / seg
                val p = LatLng(pts[i].lat + (pts[i + 1].lat - pts[i].lat) * t, pts[i].lng + (pts[i + 1].lng - pts[i].lng) * t)
                return p to pts[i].bearingTo(pts[i + 1])
            }
            left -= seg
        }
        return null
    }

    private fun side(p: LatLng, heading: Double, offsetM: Double): LatLng =
        if (offsetM == 0.0) p else p.destinationPoint(abs(offsetM), heading + if (offsetM > 0) 90.0 else -90.0)

    /** Canonical arrow name for one lane's turn:lanes value, or null for none/merge. */
    fun arrowIcon(v: String): String? {
        val parts = v.split(';').map { it.trim() }.mapNotNull {
            when (it) {
                "left", "sharp_left", "slight_left" -> "left"
                "right", "sharp_right", "slight_right" -> "right"
                "through" -> "through"
                "reverse" -> "uturn"
                else -> null
            }
        }.distinct()
        if (parts.isEmpty()) return null
        val order = listOf("uturn", "left", "through", "right")
        return "arrow-" + order.filter { it in parts }.joinToString("-")
    }

    fun build(ways: List<Way>, nodes: List<Node> = emptyList()): List<Mark> {
        val out = ArrayList<Mark>()
        val jn = junctions(ways)
        // By identity: a Way is a data class, and hashing one hashes every point it has.
        val layouts: MutableMap<Way, Layout> = java.util.IdentityHashMap()
        for (w0 in ways) {
            if (w0.points.size < 2) continue
            val t = w0.tags
            if (t["footway"] == "crossing" || t["cycleway"] == "crossing" || t["highway"] == "crossing") {
                val markings = t["crossing:markings"]
                val c = t["crossing"]
                val painted = if (markings != null) markings != "no" else c != null && c in PAINTED_CROSSING
                if (painted) out += Mark(Kind.CROSSWALK, w0.points)
                continue
            }
            val l = layout(t) ?: continue
            layouts[w0] = l
            if (l.painted || l.bikeRight || l.bikeLeft) {
                val bikes = (if (l.bikeRight) 1 else 0) + (if (l.bikeLeft) 1 else 0)
                out += Mark(Kind.SURFACE, w0.points, widthM = l.width + bikes * 2 * BIKE_OUT_M + 0.6)
            }
            for ((piece, startJ, endJ) in pieces(w0.points, jn)) {
                if (l.painted) {
                    if (l.oneway) {
                        for (k in 1 until l.fwd) out += Mark(Kind.LANE, piece, l.left + k * LANE_M)
                    } else {
                        for (k in 1 until l.bwd) out += Mark(Kind.LANE, piece, l.left + k * LANE_M)
                        out += Mark(Kind.CENTER, piece, l.center)
                        if (l.both > 0) out += Mark(Kind.CENTER, piece, l.center + LANE_M)
                        val fStart = l.center + l.both * LANE_M
                        for (k in 1 until l.fwd) out += Mark(Kind.LANE, piece, fStart + k * LANE_M)
                    }
                }
                if (l.bikeRight) out += Mark(Kind.BIKE, piece, l.width / 2 + BIKE_OUT_M)
                if (l.bikeLeft) out += Mark(Kind.BIKE, piece, -(l.width / 2 + BIKE_OUT_M))
                // Turn arrows, one per lane, before the junction the lane drives into. Travel along the
                // way = toward the piece's end; against it = toward its start. A one-way tagged -1 runs
                // against its geometry.
                val fwdTurns = if (l.reversed) null else l.turnFwd
                val bwdTurns = if (l.reversed) l.turnFwd else l.turnBwd
                if (endJ && fwdTurns != null) arrows(out, piece, fwdTurns, forward = true, firstLaneOffset = if (l.oneway) l.left else l.center + l.both * LANE_M)
                // Against the way: a one-way tagged -1 fills the whole carriageway, so its first lane (left
                // in its travel direction) is at the way's right edge.
                if (startJ && bwdTurns != null) arrows(out, piece.reversed(), bwdTurns, forward = false,
                    firstLaneOffset = if (l.oneway) l.width / 2 else l.center)
            }
        }
        // A crossing node is usually where a mapped crossing WAY meets the road: that way already
        // draws the crosswalk, so the node only draws one where no crossing way passes through it.
        val crossingWayVertices = HashSet<Long>()
        for (w in ways) if (w.tags["footway"] == "crossing" || w.tags["cycleway"] == "crossing") for (p in w.points) crossingWayVertices += key(p)
        stopsAndNodeCrossings(out, ways, layouts, nodes, jn, crossingWayVertices)
        medians(out, layouts)
        return out
    }

    /** One arrow per lane, [ARROW_BACK_M] - [JUNCTION_TRIM_M] before the end of [toward] (already
     *  trimmed, oriented in the travel direction). Lanes are listed left to right in that direction. */
    private fun arrows(out: MutableList<Mark>, toward: List<LatLng>, turns: List<String>, forward: Boolean, firstLaneOffset: Double) {
        val rev = toward.reversed()
        val (p, backHeading) = along(rev, ARROW_BACK_M - JUNCTION_TRIM_M) ?: return
        val heading = (backHeading + 180.0) % 360.0
        turns.forEachIndexed { i, v ->
            val icon = arrowIcon(v) ?: return@forEachIndexed
            // Offsets are in the way's own frame. Forward lanes count rightward from the first lane's
            // left edge; backward lanes, seen in their travel direction, count leftward in the way's frame.
            val wayOff = if (forward) firstLaneOffset + (i + 0.5) * LANE_M else firstLaneOffset - (i + 0.5) * LANE_M
            // `side` takes an offset to the right of the heading it is given: the way's heading for
            // forward lanes, the reverse for backward ones (so the sign flips).
            val pt = side(p, heading, if (forward) wayOff else -wayOff)
            out += Mark(Kind.ARROW, listOf(pt), icon = icon, rotDeg = heading)
        }
    }

    private fun stopsAndNodeCrossings(out: MutableList<Mark>, ways: List<Way>, layouts: Map<Way, Layout>, nodes: List<Node>, jn: Set<Long>, crossingWayVertices: Set<Long>) {
        if (nodes.isEmpty()) return
        val byVertex = HashMap<Long, MutableList<Pair<Way, Int>>>()
        for ((w, _) in layouts) for ((i, p) in w.points.withIndex()) byVertex.getOrPut(key(p)) { ArrayList(2) } += w to i
        for (n in nodes) {
            val hw = n.tags["highway"]
            val k = key(n.point)
            val on = byVertex[k] ?: continue
            if (hw == "crossing") {
                val markings = n.tags["crossing:markings"]
                val c = n.tags["crossing"]
                val painted = if (markings != null) markings != "no" else c != null && c in PAINTED_CROSSING
                if (!painted || k in jn || k in crossingWayVertices) continue
                val (w, i) = on.first()
                val l = layouts.getValue(w)
                val heading = if (i < w.points.size - 1) w.points[i].bearingTo(w.points[i + 1]) else w.points[i - 1].bearingTo(w.points[i])
                val half = l.width / 2 + 1.0 + (if (l.bikeLeft || l.bikeRight) 1.8 else 0.0)
                out += Mark(Kind.CROSSWALK, listOf(side(n.point, heading, -half), side(n.point, heading, half)))
                continue
            }
            if (hw != "stop" && hw != "traffic_signals") continue
            val dir = n.tags["direction"]
            for ((w, i) in on) {
                val l = layouts.getValue(w)
                val atJunction = k in jn
                // Approaches into this node: along the way from lower indices, against it from higher.
                val approaches = ArrayList<Boolean>(2)
                val canFwd = i > 0 && !(l.oneway && l.reversed)
                val canBwd = i < w.points.size - 1 && (!l.oneway || l.reversed)
                if (atJunction) {
                    if (canFwd) approaches += true
                    if (canBwd) approaches += false
                } else {
                    // A stop node set back from the junction: the approach is the one driving toward
                    // the nearer junction, unless the node says which way it faces.
                    when (dir) {
                        "forward" -> if (canFwd) approaches += true
                        "backward" -> if (canBwd) approaches += false
                        else -> {
                            val ahead = (i + 1 until w.points.size).firstOrNull { key(w.points[it]) in jn }
                            val behind = (i - 1 downTo 0).firstOrNull { key(w.points[it]) in jn }
                            val dAhead = ahead?.let { w.points[i].distanceTo(w.points[it]) } ?: Double.MAX_VALUE
                            val dBehind = behind?.let { w.points[i].distanceTo(w.points[it]) } ?: Double.MAX_VALUE
                            if (dAhead < dBehind && canFwd) approaches += true else if (dBehind < dAhead && canBwd) approaches += false
                        }
                    }
                }
                for (forward in approaches) {
                    // The approach as a polyline ending at the node, in travel direction.
                    val path = if (forward) w.points.subList(0, i + 1) else w.points.subList(i, w.points.size).reversed()
                    val back = if (atJunction) STOP_BACK_M else 0.0
                    val (p, h0) = along(path.reversed(), back) ?: continue
                    val heading = (h0 + 180.0) % 360.0
                    // Across the approaching lanes: from the center (or the left edge of a one-way) to the
                    // right edge, in the travel direction's own frame.
                    // Travel frame = the way's frame driving with it, mirrored driving against it.
                    val from = when {
                        l.oneway -> -l.width / 2
                        forward -> l.center + l.both * LANE_M
                        else -> -l.center
                    }
                    out += Mark(Kind.STOP, listOf(side(p, heading, from), side(p, heading, l.width / 2)))
                }
            }
        }
    }

    /** Medians: a one-way half of a divided road paired with the opposite-direction half of the same
     *  name 8 to 40 m away; the strip between their surfaces is filled. Each pair is drawn once. */
    private fun medians(out: MutableList<Mark>, layouts: Map<Way, Layout>) {
        val halves = layouts.entries.filter { (w, l) -> l.oneway && w.tags["name"] != null }.map { it.key }
        if (halves.size < 2) return
        val cell = 0.0006
        val grid = HashMap<Long, MutableList<Int>>()
        fun ck(lat: Double, lng: Double) = Math.floor(lat / cell).toLong() * 10_000_000L + Math.floor(lng / cell).toLong()
        // Index every cell each SEGMENT passes through, not just the vertices: a long straight half has
        // no vertex near the middle of its partner.
        for ((wi, w) in halves.withIndex()) {
            val cells = HashSet<Long>()
            for (j in 0 until w.points.size - 1) {
                val a = w.points[j]; val b = w.points[j + 1]
                val steps = (maxOf(abs(b.lat - a.lat), abs(b.lng - a.lng)) / (cell / 2)).toInt() + 1
                for (s2 in 0..steps) {
                    val t = s2.toDouble() / steps
                    cells += ck(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
                }
            }
            for (c in cells) grid.getOrPut(c) { ArrayList() } += wi
        }
        for ((ai, a) in halves.withIndex()) {
            val la = layouts.getValue(a)
            val name = a.tags["name"]
            val total = (1 until a.points.size).sumOf { a.points[it - 1].distanceTo(a.points[it]) }
            if (total < 30) continue
            val run = ArrayList<LatLng>(); var runW = 0.0; var runN = 0
            fun flush() {
                if (run.size >= 2) out += Mark(Kind.MEDIAN, ArrayList(run), widthM = (runW / runN).coerceIn(0.8, 14.0))
                run.clear(); runW = 0.0; runN = 0
            }
            var m = 10.0
            while (m < total - 10) {
                val (p, ha) = along(a.points, m) ?: break
                val headA = if (la.reversed) (ha + 180) % 360 else ha
                var best: Triple<LatLng, Double, Int>? = null
                val cLat = Math.floor(p.lat / cell).toLong(); val cLng = Math.floor(p.lng / cell).toLong()
                for (dy in -1..1) for (dx in -1..1) {
                    for (bi in grid[(cLat + dy) * 10_000_000L + (cLng + dx)] ?: continue) {
                        if (bi <= ai) continue // each pair once, from the lower index
                        val b = halves[bi]
                        if (b.tags["name"] != name) continue
                        val lb = layouts.getValue(b)
                        for (j in 0 until b.points.size - 1) {
                            val q = nearestOnSeg(p, b.points[j], b.points[j + 1])
                            val d = p.distanceTo(q)
                            if (d < 8.0 || d > 40.0 || (best != null && d >= best.second)) continue
                            val hb0 = b.points[j].bearingTo(b.points[j + 1])
                            val headB = if (lb.reversed) (hb0 + 180) % 360 else hb0
                            val diff = abs(((headA - headB + 540) % 360) - 180)
                            if (diff > 150) best = Triple(q, d, bi)
                        }
                    }
                }
                val bq = best
                if (bq != null) {
                    val lb = layouts.getValue(halves[bq.third])
                    val gap = bq.second - la.width / 2 - lb.width / 2
                    if (gap > 0.5) {
                        run += LatLng((p.lat + bq.first.lat) / 2, (p.lng + bq.first.lng) / 2); runW += gap; runN++
                    } else flush()
                } else flush()
                m += 15.0
            }
            flush()
        }
    }

    private fun nearestOnSeg(p: LatLng, a: LatLng, b: LatLng): LatLng {
        val kx = Math.cos(Math.toRadians(a.lat))
        val bx = (b.lng - a.lng) * kx; val by = b.lat - a.lat
        val px = (p.lng - a.lng) * kx; val py = p.lat - a.lat
        val l2 = bx * bx + by * by
        val t = if (l2 <= 0) 0.0 else ((px * bx + py * by) / l2).coerceIn(0.0, 1.0)
        return LatLng(a.lat + by * t, a.lng + (b.lng - a.lng) * t)
    }

    @Serializable private data class Geom(val lat: Double, val lon: Double)
    @Serializable private data class El(
        val type: String = "", val tags: Map<String, String>? = null, val geometry: List<Geom>? = null,
        val lat: Double? = null, val lon: Double? = null,
    )
    @Serializable private data class Resp(val elements: List<El> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** Streets, crossings, stop signs and signals in the box; null when every mirror failed. */
    @OptIn(ExperimentalSerializationApi::class)
    fun fetch(http: OkHttpClient, south: Double, west: Double, north: Double, east: Double): Pair<List<Way>, List<Node>>? {
        val box = "($south,$west,$north,$east)"
        val query = "[out:json][timeout:25];(" +
            "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)(_link)?$\"]$box;" +
            "way[\"footway\"=\"crossing\"]$box;way[\"cycleway\"=\"crossing\"]$box;" +
            "node[\"highway\"~\"^(stop|traffic_signals|crossing)$\"]$box;" +
            ");out tags geom;"
        val slow = http.newBuilder().callTimeout(40, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        return OverpassEndpoints.run(slow, query) { body -> parse(body.byteStream()) }
    }

    /** The same reply read from [url] (a saved answer served locally while iterating, so a test does
     *  not keep asking the public servers); null on any failure. */
    fun fetchFrom(http: OkHttpClient, url: String): Pair<List<Way>, List<Node>>? = runCatching {
        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.byteStream()?.let { parse(it) }
        }
    }.getOrNull()

    @OptIn(ExperimentalSerializationApi::class)
    private fun parse(stream: java.io.InputStream): Pair<List<Way>, List<Node>> {
        val els = json.decodeFromStream<Resp>(stream).elements
        val ways = els.mapNotNull { e ->
            val g = e.geometry ?: return@mapNotNull null
            if (e.type != "way" || g.size < 2) return@mapNotNull null
            Way(e.tags.orEmpty(), g.map { LatLng(it.lat, it.lon) })
        }
        val nodes = els.mapNotNull { e ->
            if (e.type != "node" || e.lat == null || e.lon == null) null else Node(e.tags.orEmpty(), LatLng(e.lat, e.lon))
        }
        return ways to nodes
    }
}
