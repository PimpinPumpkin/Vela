package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.TravelMode
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.ResponsePath
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.util.EdgeFilter
import com.graphhopper.routing.weighting.SpeedWeighting
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.GHUtility
import com.graphhopper.util.Instruction
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device routing from prebuilt GraphHopper graphs — **one self-contained graph per downloaded
 * region** (a GraphHopper graph is monolithic; a trip must fit inside a single region's graph, so the
 * whole world can't be one file, but you download the regions you travel). [graphsRoot] holds
 * `<regionId>/` graph folders plus an `index.json` (`[{id, bbox:[S,W,N,E]}]`) that [RoutingGraphStore]
 * writes on install; this engine reads it to pick, per trip, the region whose box covers BOTH endpoints.
 *
 * Pure JVM — runs on ART, **validated end-to-end on a Pixel 5a** (see `:ghprobe` + ROADMAP). Per graph:
 *  1. **MMAP** data access — the default `RAMDataAccess` static-inits `VarHandle.withInvokeExactBehavior()`
 *     (JDK-16), absent on ART; `MMapDataAccess` doesn't.
 *  2. **No Janino** — v11 compiles custom-model weightings to JVM bytecode ART can't load. We override
 *     [GraphHopper.createWeightingFactory] to a hand-rolled [SpeedWeighting] + access block, and graphs
 *     bake **Contraction Hierarchies** on that same weighting (CH = ~200 ms on-device vs 7.6 s flexible).
 *  3. **Swallow `close()`** — MMAP unmap uses `Unsafe.invokeCleaner`, absent on Android.
 *
 * Each region's graph is loaded lazily + once (~150 ms), cached, and routing is thread-safe afterwards.
 * DRIVE only for now (a car graph); other modes fall back to the online engine.
 */
class GraphHopperRouteEngine(private val graphsRoot: File) : RouteEngine {

    private data class Region(val id: String, val s: Double, val w: Double, val n: Double, val e: Double) {
        fun covers(p: LatLng) = inBox(s, w, n, e, p.lat, p.lng)
    }

    private val hoppers = ConcurrentHashMap<String, GraphHopper>()
    private val failed = ConcurrentHashMap.newKeySet<String>() // graphs that errored on load — don't retry-thrash

    override fun isReady(mode: TravelMode): Boolean =
        mode == TravelMode.DRIVE && regions().any { it.id !in failed && hasGraph(it.id) }

    override fun route(origin: LatLng, destination: LatLng, mode: TravelMode): List<Route> {
        if (mode != TravelMode.DRIVE) return emptyList()
        // A single graph can't route across regions, so we need one region covering BOTH ends. Region boxes
        // overlap at borders (Geofabrik extracts carry a buffer — British Columbia's box dips into Seattle),
        // so try the most specific (smallest-box) covering region first and fall through to the next if its
        // graph can't actually make the trip; only give up when none can.
        val candidates = regions()
            .filter { it.id !in failed && it.covers(origin) && it.covers(destination) }
            .sortedBy { (it.n - it.s) * (it.e - it.w) }
        for (region in candidates) {
            val gh = hopper(region) ?: continue
            try {
                val rsp = gh.route(GHRequest(origin.lat, origin.lng, destination.lat, destination.lng).setProfile(PROFILE))
                if (!rsp.hasErrors()) return listOf(toRoute(rsp.best))
            } catch (e: Exception) {
                // try the next covering region
            }
        }
        return emptyList()
    }

    /**
     * The POSTED speed limit (km/h) of the road nearest ([lat],[lng]), from the OSM `maxspeed` tag baked
     * into the graph's `max_speed` encoded value — or `null` if unknown (untagged road, no covering graph,
     * or a graph built before `max_speed` was added). Snaps the fix to the nearest edge and reads the EV off
     * the **base graph** (encoded values live there, not on the CH overlay), so it's CH-safe and independent
     * of any active route — it tracks the road under the puck even off-route. Call OFF the main thread
     * (LocationIndex snap does I/O on the mmap'd graph). Returns km/h; convert to mph at the UI boundary.
     */
    override fun currentRoadLimit(lat: Double, lng: Double): Double? {
        val p = LatLng(lat, lng)
        for (region in regions()
            .filter { it.id !in failed && it.covers(p) }
            .sortedBy { (it.n - it.s) * (it.e - it.w) }) {
            val gh = hopper(region) ?: continue
            // A graph built before max_speed was added has no such EV → getDecimalEncodedValue throws;
            // swallow it so an un-rebuilt region degrades to "unknown", never a crash.
            val ev = runCatching { gh.encodingManager.getDecimalEncodedValue(MAX_SPEED_EV) }.getOrNull() ?: continue
            val snap = runCatching { gh.locationIndex.findClosest(lat, lng, EdgeFilter.ALL_EDGES) }.getOrNull() ?: continue
            if (!snap.isValid) continue
            // Forward direction is fine for v1 (few ways tag directional maxspeed). Per GraphHopper's
            // OSMMaxSpeedParser: an untagged edge reads +Infinity (filtered by isFinite), EVERY value is
            // capped at 150 km/h, AND `maxspeed=none` (derestricted) also stores exactly 150 — so 150 is
            // ambiguous (a real 150 zone vs. derestricted). We deliberately blank on 150 (strict `< 150`):
            // a wrong "150" on a derestricted autobahn is worse than a blank on the rare true-150 road.
            val kmh = runCatching { snap.closestEdge.get(ev) }.getOrNull() ?: continue
            if (kmh.isFinite() && kmh > 0.0 && kmh < 150.0) return kmh
        }
        return null
    }

    /** Drop all loaded graphs (e.g. after install/delete changes the set). Swallows the Android unmap quirk. */
    fun shutdown() {
        synchronized(this) {
            hoppers.values.forEach { runCatching { it.close() } }
            hoppers.clear()
            failed.clear()
        }
    }

    private fun hasGraph(id: String) = File(File(graphsRoot, id), "properties").exists()

    /** Installed regions + their bounding boxes, from `graphsRoot/index.json` (written by the store). */
    private fun regions(): List<Region> = runCatching {
        val f = File(graphsRoot, "index.json")
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val b = o.getJSONArray("bbox")
            Region(o.getString("id"), b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
        }
    }.getOrDefault(emptyList())

    private fun hopper(region: Region): GraphHopper? {
        hoppers[region.id]?.let { return it }
        if (region.id in failed) return null
        synchronized(this) {
            hoppers[region.id]?.let { return it }
            val dir = File(graphsRoot, region.id)
            if (!File(dir, "properties").exists()) return null
            return try {
                load(dir).also { hoppers[region.id] = it }
            } catch (e: Throwable) {
                failed.add(region.id)
                null
            }
        }
    }

    /** Load one prebuilt CH graph with the three ART workarounds. */
    private fun load(dir: File): GraphHopper {
        val hopper = object : GraphHopper() {
            override fun createWeightingFactory(): WeightingFactory =
                WeightingFactory { _, _, _ ->
                    val speed = encodingManager.getDecimalEncodedValue(SPEED_EV)
                    val access = encodingManager.getBooleanEncodedValue(ACCESS_EV)
                    object : SpeedWeighting(speed) {
                        override fun calcEdgeWeight(edge: EdgeIteratorState, reverse: Boolean): Double {
                            val ok = if (reverse) edge.getReverse(access) else edge.get(access)
                            return if (!ok) Double.POSITIVE_INFINITY else super.calcEdgeWeight(edge, reverse)
                        }

                        // car_average_speed is km/h; SpeedWeighting reports time as if it were m/s (3.6x
                        // too fast). Report real ms — routing/CH still use the proportional weight above.
                        override fun calcEdgeMillis(edge: EdgeIteratorState, reverse: Boolean): Long {
                            val kmh = if (reverse) edge.getReverse(speed) else edge.get(speed)
                            return if (kmh <= 0.0) Long.MAX_VALUE else (edge.distance * 3600.0 / kmh).toLong()
                        }
                    }
                }
        }
        val cfg = GraphHopperConfig().apply {
            putObject("graph.location", dir.absolutePath)
            putObject("graph.dataaccess", "MMAP") // ART lacks RAMDataAccess's VarHandle method
            putObject("graph.encoded_values", ENCODED_VALUES)
            putObject("import.osm.ignored_highways", "") // import-only; required by init() validation
            profiles = listOf(Profile(PROFILE).setCustomModel(GHUtility.loadCustomModelFromJar("car.json")))
            setCHProfiles(listOf(CHProfile(PROFILE))) // prebuilt CH → fast on-device routing
        }
        hopper.init(cfg)
        hopper.importOrLoad()
        return hopper
    }

    private fun toRoute(path: ResponsePath): Route {
        val poly = path.points.let { pts -> (0 until pts.size()).map { LatLng(pts.getLat(it), pts.getLon(it)) } }
        val maneuvers = path.instructions.mapIndexed { i, ins ->
            val type = ghType(ins.sign, first = i == 0)
            val road = ins.name?.takeIf { it.isNotBlank() }
            val at = ins.points.let { if (it.size() > 0) LatLng(it.getLat(0), it.getLon(0)) else poly.firstOrNull() ?: LatLng(0.0, 0.0) }
            Maneuver(
                type = type,
                instruction = ghPhrase(type, road),
                location = at,
                distanceMeters = ins.distance,
                durationSeconds = ins.time / 1000.0,
                road = road,
            )
        }
        return Route(
            polyline = poly,
            legs = listOf(RouteLeg(path.distance, path.time / 1000.0, null, maneuvers)),
            distanceMeters = path.distance,
            durationSeconds = path.time / 1000.0,
            durationInTrafficSeconds = null, // offline: no live traffic
            summary = maneuvers.asReversed().firstNotNullOfOrNull { it.road },
        )
    }

    internal companion object {
        /** A region's box [S,W,N,E] covers ([lat],[lng])? The engine routes a trip on the smallest installed
         *  region covering BOTH endpoints (boxes overlap at borders; a monolithic graph can't route across
         *  regions), falling through to the next-smallest if that graph can't make the trip. */
        internal fun inBox(s: Double, w: Double, n: Double, e: Double, lat: Double, lng: Double) =
            lat in s..n && lng in w..e

        private const val PROFILE = "car"
        private const val ENCODED_VALUES = "car_access, car_average_speed, road_access, max_speed"
        private const val SPEED_EV = "car_average_speed"
        private const val ACCESS_EV = "car_access"
        private const val MAX_SPEED_EV = "max_speed" // OSM posted limit (km/h); == GraphHopper MaxSpeed.KEY

        /** GraphHopper [Instruction] sign → Vela [ManeuverType]. The first step is always a depart. */
        internal fun ghType(sign: Int, first: Boolean): ManeuverType = when {
            first -> ManeuverType.DEPART
            sign == Instruction.FINISH || sign == Instruction.REACHED_VIA -> ManeuverType.ARRIVE
            sign == Instruction.TURN_SHARP_LEFT -> ManeuverType.SHARP_LEFT
            sign == Instruction.TURN_LEFT -> ManeuverType.TURN_LEFT
            sign == Instruction.TURN_SLIGHT_LEFT -> ManeuverType.SLIGHT_LEFT
            sign == Instruction.TURN_SLIGHT_RIGHT -> ManeuverType.SLIGHT_RIGHT
            sign == Instruction.TURN_RIGHT -> ManeuverType.TURN_RIGHT
            sign == Instruction.TURN_SHARP_RIGHT -> ManeuverType.SHARP_RIGHT
            sign == Instruction.KEEP_LEFT -> ManeuverType.KEEP_LEFT
            sign == Instruction.KEEP_RIGHT -> ManeuverType.KEEP_RIGHT
            sign == Instruction.USE_ROUNDABOUT -> ManeuverType.ROUNDABOUT
            sign == Instruction.LEAVE_ROUNDABOUT -> ManeuverType.EXIT_ROUNDABOUT
            // ManeuverType.CONTINUE is VOICE-SILENT in NavEngine ("keep driving straight, nothing
            // to do"), so only the true straight-on sign may map to it. The old else-branch
            // funnelled U_TURN_LEFT(-8)/U_TURN_RIGHT(8)/FERRY(9)/PT signs into CONTINUE — a
            // u-turn keeps its road name, so the engine's silence would have swallowed it whole.
            sign == Instruction.U_TURN_LEFT || sign == Instruction.U_TURN_RIGHT ||
                sign <= Instruction.U_TURN_UNKNOWN -> ManeuverType.UTURN
            sign == Instruction.CONTINUE_ON_STREET -> ManeuverType.CONTINUE
            else -> ManeuverType.UNKNOWN // FERRY / PT / anything new: spoken, never silenced
        }

        /** Synthesize the human instruction (GraphHopper ships none unless given a Translation). */
        /** Instruction text for an offline GraphHopper maneuver. Delegates to the ACTIVE language's
         *  [app.vela.core.i18n.NavStrings] table (like OSRM's [RouteGeometry.osrmPhrase]) by mapping each
         *  [ManeuverType] back to the OSRM (type, mod) token pair — so offline routes localize through the
         *  same 11 tables, with zero new translations (audit 2026-07-06: this used to hardcode English, so
         *  offline routes were never localized unlike the OSRM path). English output is byte-identical to
         *  the old literals for the shipped cases. */
        internal fun ghPhrase(type: ManeuverType, road: String?): String {
            val (t, mod) = when (type) {
                ManeuverType.DEPART -> "depart" to null
                ManeuverType.ARRIVE -> "arrive" to null
                ManeuverType.CONTINUE, ManeuverType.STRAIGHT -> "continue" to "straight"
                ManeuverType.TURN_LEFT -> "turn" to "left"
                ManeuverType.TURN_RIGHT -> "turn" to "right"
                ManeuverType.SLIGHT_LEFT -> "turn" to "slight left"
                ManeuverType.SLIGHT_RIGHT -> "turn" to "slight right"
                ManeuverType.SHARP_LEFT -> "turn" to "sharp left"
                ManeuverType.SHARP_RIGHT -> "turn" to "sharp right"
                ManeuverType.UTURN -> "uturn" to null
                ManeuverType.MERGE -> "merge" to null
                ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT -> "fork" to "left"
                ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT -> "fork" to "right"
                ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT -> "ramp" to null
                ManeuverType.ROUNDABOUT -> "roundabout" to null
                ManeuverType.EXIT_ROUNDABOUT -> "exit roundabout" to null
                ManeuverType.UNKNOWN -> "continue" to null
            }
            return app.vela.core.i18n.NavStringsRegistry.current().phrase(t, mod, road, null, null, null)
        }
    }
}
