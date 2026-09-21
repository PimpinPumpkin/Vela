package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.TravelMode
import net.osmand.binary.BinaryMapIndexReader
import net.osmand.data.LatLon
import net.osmand.binary.RouteDataObject
import net.osmand.router.RoutePlannerFrontEnd
import net.osmand.router.RoutingContext
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
        fun covers(p: LatLng) = OfflinePhrases.inBox(s, w, n, e, p.lat, p.lng)
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

    override fun route(origin: LatLng, destination: LatLng, mode: TravelMode, avoidTolls: Boolean, avoidHighways: Boolean, avoidFerries: Boolean, departBearingDeg: Double?): List<Route> {
        val profile = profileFor(mode) ?: return emptyList()
        val all = regions()
        // MULTI-FILE routing (2026-08-03): OsmAnd's router reads across obf files natively - the
        // OsmAnd app itself ships one file per region and routes over all of them - so hand it
        // EVERY installed region that intersects the trip's padded bounding box rather than
        // demanding one file cover both endpoints. This is what lets big countries ship as
        // CI-bakeable sub-region pieces behind a single country button: a route across a Land
        // border pulls road tiles from both files. The pad (a quarter of the span, floored at
        // ~30 km) covers detours that bow outside the endpoints' box.
        val padLat = kotlin.math.max(0.27, kotlin.math.abs(origin.lat - destination.lat) * 0.25)
        val padLng = kotlin.math.max(0.27, kotlin.math.abs(origin.lng - destination.lng) * 0.25)
        val bs = kotlin.math.min(origin.lat, destination.lat) - padLat
        val bn = kotlin.math.max(origin.lat, destination.lat) + padLat
        val bw = kotlin.math.min(origin.lng, destination.lng) - padLng
        val be = kotlin.math.max(origin.lng, destination.lng) + padLng
        val cands = all.filter { it.id !in failed && it.s < bn && it.n > bs && it.w < be && it.e > bw }
        android.util.Log.d(TAG, "route $mode: ${all.size} installed, ${cands.size} intersecting trip box")
        // The UNION must cover both endpoints, else the trip genuinely leaves the installed data.
        // Both early outs log: a silent empty here read as "No drive route found" with nothing to
        // go on (2026-09-14, a simulated origin outside the region looked like a broken file).
        val originIn = cands.any { it.covers(origin) }
        val destIn = cands.any { it.covers(destination) }
        if (!originIn || !destIn) {
            android.util.Log.d(TAG, "route $mode: endpoint outside installed data (origin in=$originIn, destination in=$destIn; origin ${"%.4f".format(origin.lat)},${"%.4f".format(origin.lng)})")
            return emptyList()
        }
        val readers = cands.mapNotNull { reader(it) }
        if (readers.isEmpty()) {
            android.util.Log.w(TAG, "route $mode: no readable file among ${cands.map { it.id }} (failed=${failed})")
            return emptyList()
        }
        run {
            try {
                val startMs = System.currentTimeMillis()
                val segments = synchronized(routeLock) {
                    val params = buildMap {
                        // routing.xml parameter ids - the router excludes matching roads at
                        // calc time, no baked profiles needed (unlike the GraphHopper CH pair).
                        if (avoidTolls) put("avoid_toll", "true")
                        if (avoidHighways) put("avoid_motorway", "true") // the car profile's id (avoid_highway is horse riding only)
                        // `avoid_ferries` is the car profile parameter id in the vendored
                        // routing.xml. DRIVE only, like the chip.
                        if (avoidFerries && mode == TravelMode.DRIVE) put("avoid_ferries", "true")
                    }
                    val config = builder().build(
                        profile,
                        RoutingConfiguration.RoutingMemoryLimits(MEMORY_MB, NATIVE_MEMORY_MB),
                        params,
                    )
                    // A reroute starts the way the car is pointing (issue #557 fallback). OsmAnd's
                    // own app sets this from Location.getBearing() / 180 * PI: compass radians,
                    // clockwise from north, which is what RouteDataObject.directionRoute returns
                    // (checked in the vendored bytecode). A soft preference, not a hard filter.
                    if (departBearingDeg != null) config.initialDirection = departBearingDeg / 180.0 * Math.PI
                    val fe = RoutePlannerFrontEnd()
                    val ctx = fe.buildRoutingContext(
                        config, null, readers.toTypedArray(),
                        RoutePlannerFrontEnd.RouteCalculationMode.NORMAL,
                    )
                    fe.searchRoute(ctx, LatLon(origin.lat, origin.lng), LatLon(destination.lat, destination.lng), null)
                        ?.list.orEmpty()
                }
                android.util.Log.d(TAG, "route over ${readers.size} file(s): ${segments.size} segments in ${System.currentTimeMillis() - startMs} ms")
                if (segments.isNotEmpty()) return listOf(toRoute(segments))
            } catch (e: Throwable) {
                // Log loudly; a corrupt file latches failed via reader(). Silent swallowing made
                // a routing failure un-diagnosable on a release build (canary 2026-07-23).
                android.util.Log.w(TAG, "route over ${readers.size} file(s) failed", e)
            }
        }
        return emptyList()
    }

    // Speed-limit lookup context: findRouteSegment wants a RoutingContext over the covering files.
    // Built once per set of covering regions and kept (it holds the road tiles around the puck, so
    // the next lookup 18 m on is a cache hit); a small memory limit so a long drive unloads behind
    // itself. Dropped with the readers in shutdown().
    private var limitCtx: RoutingContext? = null
    private var limitCtxKey = ""

    /** The posted limit (km/h) of the road nearest [lat],[lng] from the installed obf files, or
     *  null when no file covers the point, no road lies within [LIMIT_SNAP_M], the way carries no
     *  maxspeed, or it is derestricted (`maxspeed=none`, which OsmAnd stores as
     *  [RouteDataObject.NONE_MAX_SPEED]; a wrong number on an autobahn is worse than a blank).
     *  Forward direction, like the GraphHopper lookup: few ways tag a directional limit. */
    override fun currentRoadLimit(lat: Double, lng: Double): Double? {
        val p = LatLng(lat, lng)
        val cands = regions().filter { it.id !in failed && it.covers(p) }.sortedBy { (it.n - it.s) * (it.e - it.w) }
        if (cands.isEmpty()) return null
        val readers = cands.mapNotNull { reader(it) }
        if (readers.isEmpty()) return null
        return runCatching {
            synchronized(routeLock) {
                val key = cands.joinToString(",") { it.id }
                val ctx = limitCtx?.takeIf { limitCtxKey == key } ?: RoutePlannerFrontEnd().buildRoutingContext(
                    builder().build(
                        profileFor(TravelMode.DRIVE)!!,
                        RoutingConfiguration.RoutingMemoryLimits(LIMIT_MEMORY_MB, NATIVE_MEMORY_MB),
                        emptyMap(),
                    ),
                    null, readers.toTypedArray(), RoutePlannerFrontEnd.RouteCalculationMode.NORMAL,
                ).also { limitCtx = it; limitCtxKey = key }
                val seg = RoutePlannerFrontEnd().findRouteSegment(lat, lng, ctx, null) ?: return@synchronized null
                // distToProj is the SQUARED distance in meters from the point to its projection.
                if (seg.distToProj > LIMIT_SNAP_M * LIMIT_SNAP_M) return@synchronized null
                val mps = seg.road.getMaximumSpeed(true)
                if (mps <= 0f || mps >= RouteDataObject.NONE_MAX_SPEED - 0.5f) return@synchronized null
                val kmh = mps * 3.6
                if (kmh < 150.0) kmh else null
            }
        }.getOrNull()
    }

    /** What the speed-limit lookup sees at a point, for the on-demand harness: which files cover it,
     *  the nearest road (name, highway class, distance) and its raw maxspeed, or the exception the
     *  lookup swallowed. Never called by the app. */
    internal fun probeRoadLimit(lat: Double, lng: Double): String {
        val p = LatLng(lat, lng)
        val cands = regions().filter { it.id !in failed && it.covers(p) }
        val readers = cands.mapNotNull { reader(it) }
        if (readers.isEmpty()) return "no readable file covers the point (regions=${regions().size}, covering=${cands.map { it.id }}, failed=$failed)"
        return try {
            synchronized(routeLock) {
                val ctx = RoutePlannerFrontEnd().buildRoutingContext(
                    builder().build(profileFor(TravelMode.DRIVE)!!, RoutingConfiguration.RoutingMemoryLimits(LIMIT_MEMORY_MB, NATIVE_MEMORY_MB), emptyMap()),
                    null, readers.toTypedArray(), RoutePlannerFrontEnd.RouteCalculationMode.NORMAL,
                )
                val seg = RoutePlannerFrontEnd().findRouteSegment(lat, lng, ctx, null)
                    ?: return@synchronized "no road segment found near the point"
                val r = seg.road
                "road=${r.getName()} highway=${r.getHighway()} distToProj=${"%.1f".format(seg.distToProj)} m^2 " +
                    "maxspeed fwd=${r.getMaximumSpeed(true)} m/s back=${r.getMaximumSpeed(false)} m/s"
            }
        } catch (e: Throwable) {
            "lookup threw ${e::class.java.name}: ${e.message}\n" + e.stackTrace.take(6).joinToString("\n") { "  at $it" }
        }
    }

    /** Drop cached readers (after an install/delete changes the set). */
    fun shutdown() {
        synchronized(routeLock) {
            limitCtx = null
            limitCtxKey = ""
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
     *  the online path uses ([OfflinePhrases.phrase]). */
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
                val type = if (isFirst) ManeuverType.DEPART else spokenType(turn!!)
                val rbExit = turn?.takeIf { it.isRoundAbout }?.exitOut?.takeIf { it > 0 }
                val at = LatLng(
                    MapUtils.get31LatitudeY(obj.getPoint31YTile(seg.startPointIndex)),
                    MapUtils.get31LongitudeX(obj.getPoint31XTile(seg.startPointIndex)),
                )
                maneuvers.add(
                    Maneuver(
                        type = type,
                        instruction = OfflinePhrases.phrase(type, road, rbExit, dest, null),
                        instructionNoRoad = OfflinePhrases.phrase(type, null, rbExit, dest, null),
                        roundaboutExit = rbExit,
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
                    instruction = OfflinePhrases.phrase(ManeuverType.ARRIVE, null),
                    location = end,
                    distanceMeters = 0.0,
                    durationSeconds = 0.0,
                    road = null,
                    ref = null,
                ),
            )
        }
        val folded = RouteGeometry.foldRenames(maneuvers)
        // Every road the route drives, local name -> Latin alias, from the file's own name:en /
        // name:latin tags (the obf keeps them per way, so no sidecar download is needed).
        val latin = LinkedHashMap<String, String>()
        for (seg in segments) {
            val obj = seg.`object`
            val local = obj.getName()?.takeIf { it.isNotBlank() } ?: continue
            if (local in latin) continue
            val alias = latinAlias(local, runCatching { obj.getName("en") }.getOrNull())
                ?: latinAlias(local, runCatching { obj.getName("latin") }.getOrNull())
                ?: continue
            latin[local] = alias
        }
        return Route(
            source = app.vela.core.model.RouteSource.OBF,
            roadNamesLatin = latin,
            polyline = poly,
            legs = listOf(RouteLeg(totalDist, totalTime, null, folded)),
            distanceMeters = totalDist,
            durationSeconds = totalTime,
            durationInTrafficSeconds = null, // offline: no live traffic
            summary = folded.asReversed().firstNotNullOfOrNull { it.road },
        )
    }

    internal companion object {
        /** [alias] as the Latin form of [local], or null when it is missing, the same string, or
         *  carries a letter from another script (a non-Latin alias must never be stored as if it
         *  were romanized). Same rule as the tile path's `latinAliasOf` and the sidecar bake. */
        internal fun latinAlias(local: String, alias: String?): String? {
            val v = alias?.trim()
            if (v.isNullOrEmpty() || v == local) return null
            val hasLatinLetter = v.any { it in 'a'..'z' || it in 'A'..'Z' }
            val noForeignLetter = v.none { Character.isLetter(it) && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }
            return if (hasLatinLetter && noForeignLetter) v else null
        }
        const val LIMIT_SNAP_M = 25.0 // a fix farther than this from any road is off the network (a lot, a driveway)
        const val LIMIT_MEMORY_MB = 32 // the lookup context only ever holds the tiles around the puck
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

        /** [obfType], except that a turn OsmAnd itself would not announce is a CONTINUE. The
         *  router sets `skipToSpeak` on a turn type it emitted for the road's own bend when there
         *  is nothing to choose at that point (the road curves left and changes its name, no side
         *  road worth the name), and OsmAnd's voice skips those. Vela mapped the bare type, so an
         *  offline drive said "turn left onto X" on a road that only renamed itself (user
         *  2026-09-19). As CONTINUE it folds into the previous maneuver as a rename
         *  (`RouteGeometry.foldRenames`), which is what the online routers produce there.
         *  Roundabouts keep their type: the exit is the instruction.
         *  And a LEFT or RIGHT with under [STRAIGHT_TURN_DEG] of actual turn is a rename too:
         *  probed on a real state file, OsmAnd emitted `Turn left` with a turn angle of 0.7 and
         *  0.005 degrees where a one-way carriageway joins its two-way continuation under lane
         *  markings (`+TL|C|C|C`), `skipToSpeak` false, and the drive is dead straight. The
         *  angle is what the router measured; a real left is tens of degrees. */
        internal fun spokenType(t: TurnType): ManeuverType = when {
            t.isRoundAbout -> obfType(t)
            t.isSkipToSpeak -> ManeuverType.CONTINUE
            (t.value == TurnType.TL || t.value == TurnType.TR) && kotlin.math.abs(t.turnAngle) < STRAIGHT_TURN_DEG -> ManeuverType.CONTINUE
            else -> obfType(t)
        }

        /** A left/right turn type carrying less actual turn than this is the road continuing. */
        const val STRAIGHT_TURN_DEG = 20f

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
