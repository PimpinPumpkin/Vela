package app.vela.ui.map

import android.os.SystemClock
import android.util.Log
import app.vela.core.data.transit.Transitous
import app.vela.core.model.LatLng
import app.vela.core.model.TransitItinerary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Bike-share trips for transit directions (SPEC 8, Bike share in directions), asked for while the
 * transit options' "Bike share" chip is on and the trip leaves now. The docked systems around the
 * trip come first ([Transitous.bikeShareGroups]); with none, nothing more is asked. They are kept
 * for [GROUPS_TTL_MS] for the same two ends ([areaKey]), so a trip asked again (a chip flipped,
 * the ends swapped) asks for the plan alone. A failed lookup is never kept as "no system here".
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
    private val clock: () -> Long = SystemClock::elapsedRealtime,
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
    }
}
