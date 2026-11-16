package app.vela.ui.map

import android.os.SystemClock
import android.util.Log
import app.vela.core.data.transit.Transitous
import app.vela.core.model.BikeShareStatus
import app.vela.core.model.LatLng
import app.vela.core.model.TransitItinerary
import app.vela.core.model.distanceTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Bike-share trips for transit directions (SPEC 8, Bike share in directions), asked for while the
 * transit options' "Bike share" chip is on and the trip leaves now. The docked systems around the
 * trip come first ([Transitous.bikeShareGroups]); with none, nothing more is asked. They are kept
 * for [GROUPS_TTL_MS] for the same two ends ([areaKey]), so a trip asked again (a chip flipped,
 * the ends swapped) asks for the plan alone. A failed lookup is never kept as "no system here".
 * Once the trips are on screen, [counts] asks for their stations' free bikes and docks.
 *
 * Called from the view model's scope only, so the kept systems need no lock.
 */
internal class BikeShareTripSearch(
    http: OkHttpClient,
    /** The docked systems around two points; null on any failure. A test passes a fake. */
    private val groupsOf: suspend (LatLng, LatLng) -> List<String>? = { a, b ->
        withContext(Dispatchers.IO) { Transitous.bikeShareGroups(http, a, b) }
    },
    /** The trips on those systems; empty on any failure. A test passes a fake. */
    private val planOf: suspend (LatLng, LatLng, List<String>) -> List<TransitItinerary> = { a, b, g ->
        withContext(Dispatchers.IO) { Transitous.planBikeShare(http, a, b, g) }
    },
    /** Those systems' stations in a box `[south, west, north, east]`; null on any failure. */
    private val stationsOf: suspend (DoubleArray, List<String>) -> List<Transitous.BikeStation>? = { b, g ->
        withContext(Dispatchers.IO) { Transitous.bikeStationsInBox(http, b[0], b[1], b[2], b[3], g) }
    },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private var keptKey: String? = null
    private var keptGroups: List<String> = emptyList()
    private var keptAt = 0L

    suspend fun search(origin: LatLng, dest: LatLng): List<TransitItinerary> {
        val t0 = clock()
        val groups = groups(origin, dest) ?: return emptyList()
        if (groups.isEmpty()) {
            Log.i(TAG, "bike share: no docked system around the trip")
            return emptyList()
        }
        val trips = runCatching { planOf(origin, dest, groups) }.getOrDefault(emptyList())
        Log.i(TAG, "bike share: ${trips.size} trips on ${groups.size} systems in ${clock() - t0} ms")
        return trips
    }

    /**
     * The free bikes and docks at [trips]' stations, keyed for [BikeShareCounts]: the stations of
     * the trips' systems only, asked for one box per group of ends ([boxes]). A station is found by
     * [Transitous.stationFor]. Null when no request answered.
     */
    suspend fun counts(trips: List<TransitItinerary>): Map<String, BikeShareStatus>? {
        val ends = buildList {
            for (t in trips) for (s in t.steps) {
                val r = s.rental ?: continue
                s.boardStop?.let { add(r to it) }
                s.alightStop?.let { add(r to it) }
            }
        }
        val points = ends.mapNotNull { it.second.location }.distinct()
        if (points.isEmpty()) return null
        val groups = ends.map { it.first.groupId }.distinct()
        val t0 = clock()
        val stations = ArrayList<Transitous.BikeStation>()
        var answered = 0
        val boxes = boxes(points)
        for (b in boxes) {
            val got = runCatching { stationsOf(b, groups) }.getOrNull() ?: continue
            answered++
            stations += got
        }
        Log.i(TAG, "bike share: counts from $answered of ${boxes.size} boxes in ${clock() - t0} ms")
        if (answered == 0) return null
        val at = wallClock()
        return ends.mapNotNull { (r, stop) ->
            Transitous.stationFor(stations, r.providerId, stop.name, stop.location)
                ?.let { st -> BikeShareCounts.keyOf(r.providerId, stop.name) to BikeShareStations.statusOf(st, at) }
        }.toMap()
    }

    private suspend fun groups(origin: LatLng, dest: LatLng): List<String>? {
        val key = areaKey(origin, dest)
        if (key == keptKey && clock() - keptAt < GROUPS_TTL_MS) return keptGroups
        val got = runCatching { groupsOf(origin, dest) }.getOrNull()
        if (got == null) {
            Log.i(TAG, "bike share: the systems lookup failed")
            return null
        }
        keptKey = key
        keptGroups = got
        keptAt = clock()
        return got
    }

    companion object {
        /** How long the systems found around a trip are kept. */
        const val GROUPS_TTL_MS = 10 * 60_000L
        private const val TAG = "VelaTransit"

        /** A trip's two ends on a 0.01 degree grid (about 1 km), in either order. The lookup pads
         *  the trip's box by a kilometer, so ends in the same cells find the same systems. */
        internal fun areaKey(a: LatLng, b: LatLng): String {
            val ka = cell(a)
            val kb = cell(b)
            return if (ka <= kb) "$ka|$kb" else "$kb|$ka"
        }

        private fun cell(p: LatLng): String = "${Math.floor(p.lat * 100).toLong()},${Math.floor(p.lng * 100).toLong()}"

        /** Ends this close to the first end of a group share its box. */
        private const val CLUSTER_M = 1500.0
        /** A box reaches this far past its ends: a station sits within meters of its plan point. */
        private const val COUNTS_PAD_M = 100.0
        /** The most boxes asked for one search: two trips, each taking and returning a bike. */
        private const val MAX_BOXES = 4

        /**
         * The boxes to ask about the stations at [points]: an end joins the first group whose
         * first end is within [CLUSTER_M], so a trip whose bike waits at the far end of a train
         * ride asks two small boxes instead of one across the whole system. Each group's box is
         * padded by [COUNTS_PAD_M]. At most [MAX_BOXES].
         */
        internal fun boxes(points: List<LatLng>): List<DoubleArray> {
            val groups = ArrayList<MutableList<LatLng>>()
            for (p in points) {
                val g = groups.firstOrNull { it.first().distanceTo(p) <= CLUSTER_M }
                if (g != null) g += p else groups += mutableListOf(p)
            }
            return groups.take(MAX_BOXES).map { g ->
                Transitous.paddedBox(
                    LatLng(g.minOf { it.lat }, g.minOf { it.lng }),
                    LatLng(g.maxOf { it.lat }, g.maxOf { it.lng }),
                    COUNTS_PAD_M,
                )
            }
        }
    }
}
