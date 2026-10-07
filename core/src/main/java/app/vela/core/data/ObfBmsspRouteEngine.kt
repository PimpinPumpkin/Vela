package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.model.TravelMode
import app.vela.core.routing.Bmssp
import app.vela.core.routing.DijkstraSssp
import app.vela.core.routing.RoadGraphBuilder
import app.vela.core.routing.SsspResult
import net.osmand.binary.BinaryMapIndexReader
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteSubregion
import net.osmand.binary.RouteDataObject
import net.osmand.util.MapUtils
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * The EXPERIMENTAL on-device engine of this fork: reads road geometry straight out of the
 * same OsmAnd `.obf` region files as [ObfRouteEngine], welds the ways into a directed
 * millisecond-weighted graph by exact coordinate snapping ([RoadGraphBuilder]), and answers
 * the trip with the sorting-barrier SSSP algorithm ([app.vela.core.routing.Bmssp],
 * arXiv:2504.17033) instead of a shortest-path search over OsmAnd's routing segments.
 *
 * What it deliberately does NOT do, compared to the shipping engine:
 *  - no turn-by-turn (the path carries DEPART + ARRIVE only - the route is drivable as a
 *    polyline, not spoken as steps), no routing-xml rules, no avoid flags (returns empty for
 *    an avoid request per the [RouteEngine] contract so the caller falls through),
 *  - no A* / goal-direction shortcut: BMSSP is a single-source algorithm, it finishes the
 *    WHOLE covered area, not a corridor to the destination,
 *  - no interruptibility inside the search: [route] honors [maxMs] at phase boundaries
 *    (refuses to start when the budget cannot possibly fit, re-checks after graph build and
 *    after the search); a running BMSSP batch recursion cannot be canceled mid-call, and any
 *    graph or search failure - OutOfMemoryError of a whole-state graph included - refuses to
 *    the shipping engine rather than crashing the app.
 *  - the region-select padding mirrors [ObfRouteEngine]'s `tripCandidates` rule (a quarter of
 *    the span each way, floored at ~0.27 degrees) rather than calling it, so this file stays
 *    free of Android references and unit-testable on a plain JVM.
 *
 * Speed model: each way's OSM maxspeed per direction (`getMaximumSpeed`, m/s), 40 km/h
 * fallback when the way carries none or is derestricted (`NONE_MAX_SPEED`); weights are
 * whole milliseconds. Non-car highway classes are skipped. Oneway follows `getOneway()`
 * (1 = with the geometry, -1 = against, 0 = both).
 *
 * Not thread-safe across routes (single graph cache guarded by [graphLock]); route() calls
 * serialize on that lock, mirroring the shipping engine's routeLock. The cached graph also
 * carries its prepared BMSSP transform ([app.vela.core.routing.Bmssp.prepareGraph] is
 * source-independent and execute() resets its scratch), so a reroute inside the same
 * coverage box reuses the transform instead of rebuilding it.
 */
class ObfBmsspRouteEngine(private val obfRootOf: () -> File) : RouteEngine {
    constructor(root: File) : this({ root })
    private val obfRoot: File get() = obfRootOf()

    private data class Region(val id: String, val s: Double, val w: Double, val n: Double, val e: Double)

    private val readers = ConcurrentHashMap<String, BinaryMapIndexReader>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val graphLock = Any()

    private class GraphCache(val key: String, val network: RoadGraphBuilder.RoadNetwork, val bmssp: Bmssp, val buildMs: Long)
    @Volatile private var graphCache: GraphCache? = null

    /** What the last route() spent, for the probe harness: build ms / search ms / sizes. */
    @Volatile internal var lastStats: String = ""
    @Volatile internal var lastOriginNode = -1
    @Volatile internal var lastDestNode = -1

    override fun isReady(mode: TravelMode): Boolean =
        mode == TravelMode.DRIVE && regions().any { it.id !in failed && hasObf(it.id) }

    override fun covers(origin: LatLng, destination: LatLng, mode: TravelMode): Boolean =
        mode == TravelMode.DRIVE && candidatesFor(origin, destination) != null

    override fun route(
        origin: LatLng,
        destination: LatLng,
        mode: TravelMode,
        avoidTolls: Boolean,
        avoidHighways: Boolean,
        avoidFerries: Boolean,
        departBearingDeg: Double?,
        maxMs: Long?,
    ): List<Route> {
        if (mode != TravelMode.DRIVE) return emptyList()
        // Contract: cannot honor an avoid request -> empty, caller falls through. Never a
        // silent route-through-the-toll.
        if (avoidTolls || avoidHighways || avoidFerries) return emptyList()
        // BMSSP cannot finish a region search under this floor; refuse instead of pretending.
        if (maxMs != null && maxMs < MIN_SEARCH_MS) {
            lastStats = "refused: budget ${maxMs} ms < $MIN_SEARCH_MS ms floor"
            return emptyList()
        }
        val startMs = System.currentTimeMillis()
        val deadline = maxMs?.let { startMs + it }
        val cands = candidatesFor(origin, destination) ?: run { lastStats = "no covering regions"; return emptyList() }
        val pairs = cands.mapNotNull { region -> reader(region)?.let { region to it } }
        if (pairs.isEmpty()) { lastStats = "no readable obf files"; return emptyList() }

        val box = tripBox(origin, destination)
        val cacheKey = pairs.joinToString(",") { it.first.id } + "|" + box.joinToString(",") { ((it * 100).toLong()).toString() }

        val network: RoadGraphBuilder.RoadNetwork
        val originNode: Int
        val destNode: Int
        val result: SsspResult
        // The graph build and the BMSSP search are the memory-heavy parts: a whole-state
        // search graph can exceed a phone heap (the Delaware probe OOMed a 512 MB JVM).
        // Catch everything, OutOfMemoryError included, and refuse: the shipped engine answers,
        // an experimental engine must never crash the app. Releasing the readers frees the
        // region memory the fallback then runs with.
        try {
            val built = synchronized(graphLock) {
                graphCache?.takeIf { it.key == cacheKey }?.also { lastBuildMs = it.buildMs }
                    ?: run {
                        // A fresh build must not coexist with the previous coverage box's prepared
                        // transform: two whole-state prepared graphs do not fit a phone heap together.
                        graphCache = null
                        val net = buildGraph(pairs, box, deadline) ?: run { lastStats = "graph build aborted"; return emptyList() }
                        GraphCache(cacheKey, net, Bmssp(net.graph).apply { prepareGraph(true) }, System.currentTimeMillis() - startMs).also { graphCache = it }
                    }
            }
            network = built.network
            originNode = network.nearestNode(
                RoadGraphBuilder.toE7(origin.lat), RoadGraphBuilder.toE7(origin.lng), SNAP_RADIUS_E7,
                accept = { network.graph.degree(it) > 0 }, // never snap onto a lone node
            ) ?: run { lastStats = "origin not within ${SNAP_RADIUS_E7 / 10000} m of a road"; return emptyList() }
            destNode = network.nearestNode(
                RoadGraphBuilder.toE7(destination.lat), RoadGraphBuilder.toE7(destination.lng), SNAP_RADIUS_E7,
                accept = { network.graph.degree(it) > 0 },
            ) ?: run { lastStats = "destination not within ${SNAP_RADIUS_E7 / 10000} m of a road"; return emptyList() }
            lastOriginNode = originNode
            lastDestNode = destNode
            if (deadline != null && System.currentTimeMillis() >= deadline) { lastStats = "budget spent before search"; return emptyList() }
            val searchStart = System.currentTimeMillis()
            result = built.bmssp.execute(originNode)
            lastSearchMs = System.currentTimeMillis() - searchStart
        } catch (t: Throwable) {
            lastStats = "graph or search failed: ${t::class.simpleName}"
            for ((_, reader) in pairs) runCatching { reader.close() }
            readers.clear()
            return emptyList()
        }

        val path = result.pathTo(destNode)
        lastStats = "nodes=${network.graph.vertexCount} edges=${lastEdgeCount} build=${lastBuildMs}ms search=${lastSearchMs}ms " +
            "o=$originNode(d=${network.graph.degree(originNode)}) d=$destNode(d=${network.graph.degree(destNode)})"
        if (path == null) return emptyList() // unreachable on the drivable graph
        if (deadline != null && System.currentTimeMillis() >= deadline) { lastStats += " (over budget, answer dropped)"; return emptyList() }

        val poly = ArrayList<LatLng>(path.size)
        for (node in path) poly.add(LatLng(RoadGraphBuilder.toDegrees(network.latE7(node)), RoadGraphBuilder.toDegrees(network.lonE7(node))))
        var dist = 0.0
        for (i in 0 until poly.size - 1) {
            dist += RoadGraphBuilder.distanceMeters(
                RoadGraphBuilder.toE7(poly[i].lat), RoadGraphBuilder.toE7(poly[i].lng),
                RoadGraphBuilder.toE7(poly[i + 1].lat), RoadGraphBuilder.toE7(poly[i + 1].lng),
            )
        }
        val durationSec = result.distanceTo(destNode) / 1000.0

        val maneuvers = listOf(
            Maneuver(
                type = ManeuverType.DEPART,
                instruction = OfflinePhrases.phrase(ManeuverType.DEPART, null),
                instructionNoRoad = OfflinePhrases.phrase(ManeuverType.DEPART, null),
                location = poly.first(),
                distanceMeters = dist,
                durationSeconds = durationSec,
            ),
            Maneuver(
                type = ManeuverType.ARRIVE,
                instruction = OfflinePhrases.phrase(ManeuverType.ARRIVE, null),
                location = poly.last(),
                distanceMeters = 0.0,
                durationSeconds = 0.0,
            ),
        )
        return listOf(
            Route(
                polyline = poly,
                legs = listOf(RouteLeg(dist, durationSec, null, maneuvers)),
                distanceMeters = dist,
                durationSeconds = durationSec,
                durationInTrafficSeconds = null, // offline: no live traffic
                summary = null,
                source = RouteSource.OBF,
            ),
        )
    }

    /** Drop cached readers and graph. */
    override fun shutdown() {
        synchronized(graphLock) {
            readers.values.forEach { runCatching { it.close() } }
            readers.clear()
            failed.clear()
            graphCache = null
        }
    }

    /**
     * The head-to-head harness for a real road network (used by ObfBmsspProbeTest and the
     * FORK.md benchmark): build the trip graph once, then run the Dijkstra baseline and
     * BMSSP on the SAME graph and report both wall times and both answers.
     */
    internal fun probeTrip(origin: LatLng, destination: LatLng): String = synchronized(graphLock) {
        // The head-to-head harness builds its own graph and its own fresh prepared transform;
        // drop the cached one first so a retained prepared graph does not coexist with it.
        graphCache = null
        val cands = candidatesFor(origin, destination) ?: return@synchronized "no covering regions"
        val pairs = cands.mapNotNull { region -> reader(region)?.let { region to it } }
        if (pairs.isEmpty()) return@synchronized "no readable obf files"
        val net = buildGraph(pairs, tripBox(origin, destination), null) ?: return@synchronized "graph build aborted"
        val o = net.nearestNode(RoadGraphBuilder.toE7(origin.lat), RoadGraphBuilder.toE7(origin.lng), SNAP_RADIUS_E7)
            ?: return@synchronized "origin not snappable"
        val d = net.nearestNode(RoadGraphBuilder.toE7(destination.lat), RoadGraphBuilder.toE7(destination.lng), SNAP_RADIUS_E7)
            ?: return@synchronized "destination not snappable"

        val adj = net.graph.adjacency()
        var t0 = System.nanoTime()
        val dij = DijkstraSssp.run(adj, o)
        val dijMs = (System.nanoTime() - t0) / 1_000_000
        t0 = System.nanoTime()
        val bm = Bmssp(net.graph).apply { prepareGraph(true) }.execute(o)
        val bmMs = (System.nanoTime() - t0) / 1_000_000

        val agree = dij.distanceTo(d) == bm.distanceTo(d)
        "nodes=${net.graph.vertexCount} edges=$lastEdgeCount build=${lastBuildMs}ms " +
            "dijkstra=${dijMs}ms bmssp=${bmMs}ms ratio=%.2fx distMs=${bm.distanceTo(d)} agree=$agree"
                .format(bmMs.toDouble() / maxOf(1L, dijMs))
    }

    // ------------------------------------------------------------------------

    private var lastBuildMs = 0L
    private var lastSearchMs = 0L
    private var lastEdgeCount = 0

    private fun hasObf(id: String) = File(obfRoot, "$id.obf").let { it.exists() && it.length() > 0 }

    // index.json is written by ObfStore as `[{"id":"...","bbox":[S,W,N,E]}]`; parsed with a
    // targeted regex (no JSON dep here) because this file must compile on a plain JVM.
    private val regionRegex = Regex(
        "\"id\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"bbox\"\\s*:\\s*\\[\\s*([-\\d.E]+)\\s*,\\s*([-\\d.E]+)\\s*,\\s*([-\\d.E]+)\\s*,\\s*([-\\d.E]+)\\s*\\]",
    )
    @Volatile private var regionsCache: Pair<String, List<Region>>? = null

    private fun regions(): List<Region> = runCatching {
        val f = File(obfRoot, "index.json")
        if (!f.exists()) return emptyList()
        val stamp = "${f.path}|${f.lastModified()}|${f.length()}"
        regionsCache?.let { if (it.first == stamp) return it.second }
        val list = regionRegex.findAll(f.readText()).map { m ->
            Region(
                m.groupValues[1],
                m.groupValues[2].toDouble(), m.groupValues[3].toDouble(),
                m.groupValues[4].toDouble(), m.groupValues[5].toDouble(),
            )
        }.toList()
        regionsCache = stamp to list
        list
    }.getOrDefault(emptyList())

    /** Mirrors [ObfRouteEngine]'s `tripCandidates` padding (quarter-span pad, ~30 km floor). */
    private fun tripBox(origin: LatLng, destination: LatLng): DoubleArray {
        val padLat = kotlin.math.max(0.27, kotlin.math.abs(origin.lat - destination.lat) * 0.25)
        val padLng = kotlin.math.max(0.27, kotlin.math.abs(origin.lng - destination.lng) * 0.25)
        return doubleArrayOf(
            kotlin.math.min(origin.lat, destination.lat) - padLat,
            kotlin.math.min(origin.lng, destination.lng) - padLng,
            kotlin.math.max(origin.lat, destination.lat) + padLat,
            kotlin.math.max(origin.lng, destination.lng) + padLng,
        )
    }

    private fun candidatesFor(origin: LatLng, destination: LatLng): List<Region>? {
        val all = regions().filter { it.id !in failed && hasObf(it.id) }
        if (all.isEmpty()) return null
        val b = tripBox(origin, destination)
        val cands = all.filter { it.s < b[2] && it.n > b[0] && it.w < b[3] && it.e > b[1] }
        val originIn = cands.any { origin.lat in it.s..it.n && origin.lng in it.w..it.e }
        val destIn = cands.any { destination.lat in it.s..it.n && destination.lng in it.w..it.e }
        return if (originIn && destIn) cands else null
    }

    private fun reader(region: Region): BinaryMapIndexReader? {
        readers[region.id]?.let { return it }
        if (region.id in failed) return null
        synchronized(graphLock) {
            readers[region.id]?.let { return it }
            val f = File(obfRoot, "${region.id}.obf")
            if (!f.exists()) return null
            return try {
                BinaryMapIndexReader(RandomAccessFile(f, "r"), f).also { readers[region.id] = it }
            } catch (e: Throwable) {
                System.err.println("ObfBmsspRouteEngine: open ${f.name} failed: $e")
                failed.add(region.id)
                null
            }
        }
    }

    private fun buildGraph(
        pairs: List<Pair<Region, BinaryMapIndexReader>>,
        trip: DoubleArray,
        deadlineMs: Long?,
    ): RoadGraphBuilder.RoadNetwork? {
        val builder = RoadGraphBuilder()
        for ((region, rd) in pairs) {
            // Clip the trip box to this region, then to 31-bit tiles.
            val s = maxOf(trip[0], region.s); val w = maxOf(trip[1], region.w)
            val n = minOf(trip[2], region.n); val e = minOf(trip[3], region.e)
            if (s >= n || w >= e) continue
            val request = BinaryMapIndexReader.buildSearchRouteRequest(
                MapUtils.get31TileNumberX(w), MapUtils.get31TileNumberY(n),
                MapUtils.get31TileNumberX(e), MapUtils.get31TileNumberY(s),
                null,
            )
            // searchRouteIndexTree filters a SUBREGION LIST - the tree of one RouteRegion -
            // against the request box; an empty/absent list silently yields nothing (verified
            // against the vendored jar's source). The canonical walk is per routing index.
            for (routeRegion in rd.routingIndexes) {
                runCatching { rd.initRouteRegion(routeRegion) } // subregions are null until read
                val subregions = rd.searchRouteIndexTree(request, routeRegion.subregions ?: continue)
                for (sub in subregions) {
                    val roads = runCatching { rd.loadRouteIndexData(sub) }.getOrNull() ?: continue
                    for (rdo in roads) if (rdo != null) addRoad(builder, rdo) // the loader yields nulls for skipped ways
                    if (builder.nodeCount > MAX_NODES) {
                        System.err.println("ObfBmsspRouteEngine: graph exceeded $MAX_NODES node cap - aborting")
                        return null
                    }
                    if (deadlineMs != null && System.currentTimeMillis() >= deadlineMs) return null
                }
            }
        }
        if (builder.nodeCount < 2) return null
        lastBuildMs = -1 // overwritten by the caller's cache entry
        lastEdgeCount = builder.addedEdges
        return builder.build()
    }

    private fun addRoad(builder: RoadGraphBuilder, rdo: RouteDataObject) {
        if (rdo.isRoadDeleted) return
        val n = rdo.getPointsLength()
        if (n < 2) return
        val highway = rdo.getHighway()
        if (highway != null && highway in NON_CAR_HIGHWAYS) return

        val keys = LongArray(n) { i ->
            RoadGraphBuilder.packE7(
                RoadGraphBuilder.toE7(MapUtils.get31LatitudeY(rdo.getPoint31YTile(i))),
                RoadGraphBuilder.toE7(MapUtils.get31LongitudeX(rdo.getPoint31XTile(i))),
            )
        }
        val fwdKmh = speedKmh(rdo.getMaximumSpeed(true))
        val backKmh = speedKmh(rdo.getMaximumSpeed(false))

        val fwd = LongArray(n - 1) { i -> hopMs(keys[i], keys[i + 1], fwdKmh) }
        val back = if (backKmh == fwdKmh) null else LongArray(n - 1) { i -> hopMs(keys[i + 1], keys[i], backKmh) }

        val oneway = rdo.getOneway() // 1 = with geometry, -1 = against, 0 = both
        builder.addPolyline(
            RoadGraphBuilder.RoadPolyline(
                pointKeys = keys,
                weightMs = fwd,
                backwardWeightMs = back,
                forward = oneway != -1,
                backward = oneway != 1,
            ),
        )
    }

    private fun hopMs(fromKey: Long, toKey: Long, kmh: Double): Long =
        RoadGraphBuilder.weightMs(
            RoadGraphBuilder.distanceMeters(
                RoadGraphBuilder.pointLat(fromKey), RoadGraphBuilder.pointLon(fromKey),
                RoadGraphBuilder.pointLat(toKey), RoadGraphBuilder.pointLon(toKey),
            ),
            kmh,
        )

    private fun speedKmh(mps: Float): Double =
        if (mps <= 0f || mps >= RouteDataObject.NONE_MAX_SPEED - 0.5f) FALLBACK_KMH else mps * 3.6

    private companion object {
        const val SNAP_RADIUS_E7 = 50_000 // 500 m
        const val MAX_NODES = 3_000_000
        const val FALLBACK_KMH = 40.0
        const val MIN_SEARCH_MS = 150
        val NON_CAR_HIGHWAYS = setOf(
            "proposed", "construction", "footway", "path", "steps", "bridleway", "raceway", "cycleway",
            "pedestrian",
        )
    }
}
