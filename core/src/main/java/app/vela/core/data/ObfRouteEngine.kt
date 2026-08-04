package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.TravelMode
import net.osmand.binary.BinaryMapIndexReader
import net.osmand.data.LatLon
import net.osmand.router.RoutePlannerFrontEnd
import net.osmand.router.RouteSegmentResult
import net.osmand.router.RoutingConfiguration
import net.osmand.router.TurnType
import net.osmand.util.MapUtils
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * On-device routing from OsmAnd `.obf` region files - the successor to [GraphHopperRouteEngine]
 * (issue #214: a whole-country GraphHopper graph + place pack landed at ~8 GB; the obf routing
 * section for the same data measures ~4x smaller, and one obf will eventually also carry the POI +
 * address search the place packs do today). The router is OsmAnd's own pure-Java engine (GPLv3,
 * vendored jars in `core/libs`, fetched from the `obf-runtime` release in CI), which computes
 * routes DYNAMICALLY from attributes in the file - so avoid-tolls / avoid-motorways work offline
 * with no precomputed profiles, and bicycle/pedestrian come free from the same file.
 *
 * [obfRoot] holds `<regionId>.obf` files plus an `index.json` (`[{id, bbox:[S,W,N,E]}]`) written by
 * the app-side store on install; per trip the engine picks the smallest region covering BOTH
 * endpoints, same rule as GraphHopper (an obf routes only within itself; cross-region falls back
 * online). Readers are opened once per region and cached; the actual route calc is serialized on
 * one lock - the routing context is single-use and offline routing is one-at-a-time in practice.
 *
 * The trade against GraphHopper CH is calc time: no precomputed shortcuts, so a cross-city route
 * costs seconds instead of ~200 ms. Offline is Vela's FALLBACK router (online OSRM is primary), so
 * download size wins over calc speed here (user call, 2026-07-23). OsmAnd's HH precomputed mode is
 * the follow-up if long routes measure too slow on-device.
 */
class ObfRouteEngine(private val obfRoot: File) : RouteEngine {

    private data class Region(val id: String, val s: Double, val w: Double, val n: Double, val e: Double) {
        fun covers(p: LatLng) = GraphHopperRouteEngine.inBox(s, w, n, e, p.lat, p.lng)
    }

    private val readers = ConcurrentHashMap<String, BinaryMapIndexReader>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val routeLock = Any()

    init {
        // OsmAnd logs through commons-logging, whose runtime DISCOVERY NPEs on Android (it probes
        // system properties that are null on ART) - which broke BinaryMapIndexReader's static init
        // and with it every obf open. Pinning the implementation skips discovery entirely.
        if (System.getProperty(JCL_LOG_PROP) == null) {
            System.setProperty(JCL_LOG_PROP, "org.apache.commons.logging.impl.SimpleLog")
        }
        // searchRoute unconditionally asks PlatformUtil for the world-regions index (its
        // missing-maps suggestion feature, which Vela has no UI for) and the default lazy init
        // opens regions.ocbf off the working directory - on Android that is / and read-only, so
        // EROFS killed every route. Pre-seed the holder with the no-file constructor and keep the
        // feature off so the empty index is never consulted.
        RoutePlannerFrontEnd.CALCULATE_MISSING_MAPS = false
        runCatching { net.osmand.PlatformUtil.setOsmandRegions(net.osmand.map.OsmandRegions(false)) }
    }

    override fun isReady(mode: TravelMode): Boolean =
        profileFor(mode) != null && regions().any { it.id !in failed && hasObf(it.id) }

    override fun route(origin: LatLng, destination: LatLng, mode: TravelMode, avoidTolls: Boolean, avoidHighways: Boolean): List<Route> {
        val profile = profileFor(mode) ?: return emptyList()
        val all = regions()
        val candidates = all
            .filter { it.id !in failed && it.covers(origin) && it.covers(destination) }
            .sortedBy { (it.n - it.s) * (it.e - it.w) }
        android.util.Log.d(TAG, "route $mode: ${all.size} installed, ${candidates.size} covering")
        for (region in candidates) {
            val reader = reader(region) ?: continue
            try {
                val startMs = System.currentTimeMillis()
                val segments = synchronized(routeLock) {
                    val params = buildMap {
                        // routing.xml parameter ids - the router excludes matching roads at
                        // calc time, no baked profiles needed (unlike the GraphHopper CH pair).
                        if (avoidTolls) put("avoid_toll", "true")
                        if (avoidHighways) put("avoid_highway", "true")
                    }
                    val config = builder().build(
                        profile,
                        RoutingConfiguration.RoutingMemoryLimits(MEMORY_MB, NATIVE_MEMORY_MB),
                        params,
                    )
                    val fe = RoutePlannerFrontEnd()
                    val ctx = fe.buildRoutingContext(
                        config, null, arrayOf(reader),
                        RoutePlannerFrontEnd.RouteCalculationMode.NORMAL,
                    )
                    fe.searchRoute(ctx, LatLon(origin.lat, origin.lng), LatLon(destination.lat, destination.lng), null)
                        ?.list.orEmpty()
                }
                android.util.Log.d(TAG, "route ${region.id}: ${segments.size} segments in ${System.currentTimeMillis() - startMs} ms")
                if (segments.isNotEmpty()) return listOf(toRoute(segments))
            } catch (e: Throwable) {
                // Log loudly, then try the next covering region; a corrupt file latches failed
                // via reader(). Silent swallowing made a routing failure un-diagnosable on a
                // release build (canary 2026-07-23).
                android.util.Log.w(TAG, "route ${region.id} failed", e)
            }
        }
        return emptyList()
    }

    /** Drop cached readers (after an install/delete changes the set). */
    fun shutdown() {
        synchronized(routeLock) {
            readers.values.forEach { runCatching { it.close() } }
            readers.clear()
            failed.clear()
        }
    }

    private fun hasObf(id: String) = File(obfRoot, "$id.obf").let { it.exists() && it.length() > 0 }

    private fun regions(): List<Region> = runCatching {
        val f = File(obfRoot, "index.json")
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val b = o.getJSONArray("bbox")
            Region(o.getString("id"), b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3))
        }
    }.getOrDefault(emptyList())

    private fun reader(region: Region): BinaryMapIndexReader? {
        readers[region.id]?.let { return it }
        if (region.id in failed) return null
        synchronized(routeLock) {
            readers[region.id]?.let { return it }
            val f = File(obfRoot, "${region.id}.obf")
            if (!f.exists()) return null
            return try {
                BinaryMapIndexReader(RandomAccessFile(f, "r"), f).also { readers[region.id] = it }
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "open ${f.name} failed", e)
                failed.add(region.id)
                null
            }
        }
    }

    /** Segment list -> Vela [Route]: polyline from the 31-bit tile coords, one [Maneuver] per turn
     *  (plus depart/arrive), phrased through the SAME localized token tables the OSRM and
     *  GraphHopper paths use ([GraphHopperRouteEngine.ghPhrase]). */
    private fun toRoute(segments: List<RouteSegmentResult>): Route {
        val poly = ArrayList<LatLng>(segments.size * 4)
        for (seg in segments) {
            val obj = seg.`object`
            val step = if (seg.startPointIndex <= seg.endPointIndex) 1 else -1
            var i = seg.startPointIndex
            while (true) {
                poly.add(LatLng(MapUtils.get31LatitudeY(obj.getPoint31YTile(i)), MapUtils.get31LongitudeX(obj.getPoint31XTile(i))))
                if (i == seg.endPointIndex) break
                i += step
            }
        }
        val totalDist = segments.sumOf { it.distance.toDouble() }
        val totalTime = segments.sumOf { it.segmentTime.toDouble() }

        val maneuvers = ArrayList<Maneuver>()
        var pendingDist = 0.0
        var pendingTime = 0.0
        segments.forEachIndexed { i, seg ->
            val turn = seg.turnType
            val isFirst = i == 0
            if (isFirst || turn != null) {
                // Close out the previous maneuver's distance: each maneuver's distance is the road
                // driven FROM it to the next one, same convention as the OSRM/GraphHopper paths.
                if (maneuvers.isNotEmpty()) {
                    val last = maneuvers.removeAt(maneuvers.size - 1)
                    maneuvers.add(last.copy(distanceMeters = pendingDist, durationSeconds = pendingTime))
                }
                pendingDist = 0.0
                pendingTime = 0.0
                val obj = seg.`object`
                val forward = seg.startPointIndex <= seg.endPointIndex
                val name = obj.getName()?.takeIf { it.isNotBlank() }
                val ref = runCatching { obj.getRef(null, false, forward) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.split(';', ',')?.first()?.trim()?.takeIf { it.isNotEmpty() }
                val dest = runCatching { obj.getDestinationName(null, false, forward) }.getOrNull()?.takeIf { it.isNotBlank() }
                val road = name ?: ref
                val type = if (isFirst) ManeuverType.DEPART else obfType(turn!!)
                val rbExit = turn?.takeIf { it.isRoundAbout }?.exitOut?.takeIf { it > 0 }
                val at = LatLng(
                    MapUtils.get31LatitudeY(obj.getPoint31YTile(seg.startPointIndex)),
                    MapUtils.get31LongitudeX(obj.getPoint31XTile(seg.startPointIndex)),
                )
                maneuvers.add(
                    Maneuver(
                        type = type,
                        instruction = GraphHopperRouteEngine.ghPhrase(type, road, rbExit, dest, null),
                        location = at,
                        distanceMeters = 0.0,
                        durationSeconds = 0.0,
                        road = road,
                        ref = ref,
                    ),
                )
            }
            pendingDist += seg.distance
            pendingTime += seg.segmentTime
        }
        if (maneuvers.isNotEmpty()) {
            val last = maneuvers.removeAt(maneuvers.size - 1)
            maneuvers.add(last.copy(distanceMeters = pendingDist, durationSeconds = pendingTime))
        }
        // OsmAnd's last segment carries no arrive turn - append one at the route end, like both
        // other engines' outputs (NavEngine keys arrival off it).
        poly.lastOrNull()?.let { end ->
            maneuvers.add(
                Maneuver(
                    type = ManeuverType.ARRIVE,
                    instruction = GraphHopperRouteEngine.ghPhrase(ManeuverType.ARRIVE, null),
                    location = end,
                    distanceMeters = 0.0,
                    durationSeconds = 0.0,
                    road = null,
                    ref = null,
                ),
            )
        }
        val folded = RouteGeometry.foldRenames(maneuvers)
        return Route(
            polyline = poly,
            legs = listOf(RouteLeg(totalDist, totalTime, null, folded)),
            distanceMeters = totalDist,
            durationSeconds = totalTime,
            durationInTrafficSeconds = null, // offline: no live traffic
            summary = folded.asReversed().firstNotNullOfOrNull { it.road },
        )
    }

    internal companion object {
        private const val TAG = "VelaObf"
        private const val JCL_LOG_PROP = "org.apache.commons.logging.Log"

        // Route-calc heap budget passed to the OsmAnd router. Modest on purpose: the browse map
        // already runs near the ceiling (CLAUDE.md memory rules) and the router allocates within
        // this bound, spilling to more tile loads instead of OOMing.
        private const val MEMORY_MB = 256
        private const val NATIVE_MEMORY_MB = 64

        // routing.xml profile names.
        private fun profileFor(mode: TravelMode): String? = when (mode) {
            TravelMode.DRIVE -> "car"
            TravelMode.BICYCLE -> "bicycle"
            TravelMode.WALK -> "pedestrian"
            else -> null
        }

        // RoutingConfiguration.getDefault() parses the bundled routing.xml - cache the parsed
        // builder; config builds from it per call are cheap.
        @Volatile private var cachedBuilder: RoutingConfiguration.Builder? = null
        private fun builder(): RoutingConfiguration.Builder =
            cachedBuilder ?: RoutingConfiguration.getDefault().also { cachedBuilder = it }

        /** OsmAnd [TurnType] -> Vela [ManeuverType]. Roundabouts map by flag (value carries the
         *  exit); KL/KR are lane keeps = our KEEP_*; TU/TRU both read as a u-turn. */
        internal fun obfType(t: TurnType): ManeuverType = when {
            t.isRoundAbout -> ManeuverType.ROUNDABOUT
            else -> when (t.value) {
                TurnType.C -> ManeuverType.CONTINUE
                TurnType.TL -> ManeuverType.TURN_LEFT
                TurnType.TSLL -> ManeuverType.SLIGHT_LEFT
                TurnType.TSHL -> ManeuverType.SHARP_LEFT
                TurnType.TR -> ManeuverType.TURN_RIGHT
                TurnType.TSLR -> ManeuverType.SLIGHT_RIGHT
                TurnType.TSHR -> ManeuverType.SHARP_RIGHT
                TurnType.KL -> ManeuverType.KEEP_LEFT
                TurnType.KR -> ManeuverType.KEEP_RIGHT
                TurnType.TU, TurnType.TRU -> ManeuverType.UTURN
                TurnType.OFFR -> ManeuverType.UNKNOWN // off-road start: spoken, never silenced
                else -> ManeuverType.UNKNOWN
            }
        }
    }
}
