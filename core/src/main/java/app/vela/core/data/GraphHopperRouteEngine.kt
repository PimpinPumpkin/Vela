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
        // A single graph can't route across regions, so we need one region covering BOTH ends.
        val region = regions().firstOrNull { it.id !in failed && it.covers(origin) && it.covers(destination) }
            ?: return emptyList()
        val gh = hopper(region) ?: return emptyList()
        return try {
            val rsp = gh.route(GHRequest(origin.lat, origin.lng, destination.lat, destination.lng).setProfile(PROFILE))
            if (rsp.hasErrors()) emptyList() else listOf(toRoute(rsp.best))
        } catch (e: Exception) {
            emptyList()
        }
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
        /** A region's box [S,W,N,E] covers ([lat],[lng])? The engine routes a trip on the first installed
         *  region covering BOTH endpoints (a monolithic graph can't route across regions). */
        internal fun inBox(s: Double, w: Double, n: Double, e: Double, lat: Double, lng: Double) =
            lat in s..n && lng in w..e

        private const val PROFILE = "car"
        private const val ENCODED_VALUES = "car_access, car_average_speed, road_access"
        private const val SPEED_EV = "car_average_speed"
        private const val ACCESS_EV = "car_access"

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
            sign <= Instruction.U_TURN_UNKNOWN -> ManeuverType.UTURN // -98 and the -99/-100 u-turns
            else -> ManeuverType.CONTINUE // CONTINUE_ON_STREET (0) + anything unmapped
        }

        /** Synthesize the human instruction (GraphHopper ships none unless given a Translation). */
        internal fun ghPhrase(type: ManeuverType, road: String?): String {
            val onto = road?.let { " onto $it" } ?: ""
            return when (type) {
                ManeuverType.DEPART -> if (road != null) "Head out on $road" else "Head out"
                ManeuverType.ARRIVE -> "Arrive at your destination"
                ManeuverType.CONTINUE, ManeuverType.STRAIGHT -> if (road != null) "Continue onto $road" else "Continue"
                ManeuverType.ROUNDABOUT -> if (road != null) "At the roundabout, take the exit onto $road" else "Enter the roundabout"
                ManeuverType.KEEP_LEFT -> "Keep left$onto"
                ManeuverType.KEEP_RIGHT -> "Keep right$onto"
                ManeuverType.UTURN -> "Make a U-turn$onto"
                ManeuverType.TURN_LEFT -> "Turn left$onto"
                ManeuverType.TURN_RIGHT -> "Turn right$onto"
                ManeuverType.SLIGHT_LEFT -> "Slight left$onto"
                ManeuverType.SLIGHT_RIGHT -> "Slight right$onto"
                ManeuverType.SHARP_LEFT -> "Sharp left$onto"
                ManeuverType.SHARP_RIGHT -> "Sharp right$onto"
                else -> if (road != null) "Continue onto $road" else "Continue"
            }
        }
    }
}
