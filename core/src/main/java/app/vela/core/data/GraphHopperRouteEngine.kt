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
import java.io.File

/**
 * On-device routing from a prebuilt GraphHopper graph (one [graphDir] per downloaded region).
 * Pure JVM — runs on ART, **validated end-to-end on a Pixel 5a** (see `:ghprobe` + ROADMAP). Three
 * Android workarounds, all here:
 *  1. **MMAP** data access — the default `RAMDataAccess` static-inits `VarHandle.withInvokeExactBehavior()`
 *     (JDK-16), absent on ART; `MMapDataAccess` doesn't. Set via [GraphHopperConfig].
 *  2. **No Janino** — v11 compiles custom-model weightings to JVM bytecode ART can't load. We override
 *     [GraphHopper.createWeightingFactory] to return a hand-rolled [SpeedWeighting] + an access block.
 *  3. **Swallow `close()`** — MMAP unmap uses `Unsafe.invokeCleaner`, absent on Android. We never close
 *     per-route (one engine for the process lifetime); [shutdown] guards it.
 *
 * Graphs are built OFF-device (the OSM-import path needs Android-hostile deps we exclude) and shipped
 * per region; the phone only loads + routes. DRIVE only for now (a car graph); other modes fall back
 * to the online engine. Loading (~140 ms) is lazy + once; routing is thread-safe afterwards.
 */
class GraphHopperRouteEngine(private val graphDir: File) : RouteEngine {

    @Volatile private var hopper: GraphHopper? = null
    @Volatile private var failed = false

    override fun isReady(mode: TravelMode): Boolean =
        mode == TravelMode.DRIVE && !failed && File(graphDir, "properties").exists()

    override fun route(origin: LatLng, destination: LatLng, mode: TravelMode): List<Route> {
        if (mode != TravelMode.DRIVE) return emptyList()
        val gh = engine() ?: return emptyList()
        return try {
            val rsp = gh.route(
                GHRequest(origin.lat, origin.lng, destination.lat, destination.lng).setProfile(PROFILE),
            )
            if (rsp.hasErrors()) emptyList() else listOf(toRoute(rsp.best))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Drop the loaded graph (e.g. when the region is deleted). Swallows the Android MMAP-unmap quirk. */
    fun shutdown() {
        synchronized(this) {
            try {
                hopper?.close()
            } catch (e: Throwable) {
                // MMAP unmap via Unsafe.invokeCleaner is absent on Android — harmless on teardown.
            }
            hopper = null
        }
    }

    private fun engine(): GraphHopper? {
        hopper?.let { return it }
        if (failed || !File(graphDir, "properties").exists()) return null
        synchronized(this) {
            hopper?.let { return it }
            return try {
                val gh = object : GraphHopper() {
                    override fun createWeightingFactory(): WeightingFactory =
                        WeightingFactory { _, _, _ ->
                            val speed = encodingManager.getDecimalEncodedValue(SPEED_EV)
                            val access = encodingManager.getBooleanEncodedValue(ACCESS_EV)
                            // SpeedWeighting is Janino-free but ignores access; add the access block so
                            // it matches the car custom model's "if !car_access: multiply_by 0".
                            object : SpeedWeighting(speed) {
                                override fun calcEdgeWeight(edge: EdgeIteratorState, reverse: Boolean): Double {
                                    val ok = if (reverse) edge.getReverse(access) else edge.get(access)
                                    return if (!ok) Double.POSITIVE_INFINITY else super.calcEdgeWeight(edge, reverse)
                                }
                            }
                        }
                }
                val cfg = GraphHopperConfig().apply {
                    putObject("graph.location", graphDir.absolutePath)
                    putObject("graph.dataaccess", "MMAP") // ART lacks RAMDataAccess's VarHandle method
                    putObject("graph.encoded_values", ENCODED_VALUES)
                    putObject("import.osm.ignored_highways", "") // import-only; required by init() validation
                    // car.json custom model is metadata only here (our factory ignores it for weighting);
                    // it keeps the profile version matching a standard car-built graph.
                    profiles = listOf(Profile(PROFILE).setCustomModel(GHUtility.loadCustomModelFromJar("car.json")))
                    // Contraction Hierarchies (prebuilt on the SAME SpeedWeighting) — flexible A* with our
                    // interpreted weighting was 7.6 s for a 24-mi trip on-device; CH makes it ~tens of ms.
                    setCHProfiles(listOf(CHProfile(PROFILE)))
                }
                gh.init(cfg)
                gh.importOrLoad()
                hopper = gh
                gh
            } catch (e: Throwable) {
                failed = true // a bad/incompatible graph shouldn't retry-thrash every route
                null
            }
        }
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
        fun ghPhrase(type: ManeuverType, road: String?): String {
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
