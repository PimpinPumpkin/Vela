package app.vela.ui.map

import app.vela.core.data.OfflineAddressStore
import app.vela.core.model.LatLng
import app.vela.core.search.SoundsLike
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The second try for a dictated search that found nothing (issue #798, SPEC 9.3): the heard words
 * are matched against the street names near the search center ([SoundsLike]), and the view model
 * searches once more with the rewrite.
 */
internal object VoiceStreetRetry {
    /** Streets within this many meters of the search center (a box) are candidates. */
    const val RADIUS_M = 3_000.0

    /**
     * The street names in the basemap tiles the map has loaded, within the given meters of a
     * point. Wired by the map view while it is on screen. It blocks on the render thread, so it is
     * called on the main thread, once per retry.
     */
    @Volatile var mapStreets: ((center: LatLng, radiusM: Double) -> List<String>)? = null

    /** [heard] with a nearby street that sounds like part of it put in, or null. Call on the main thread. */
    suspend fun rewrite(heard: String, lang: String, center: LatLng, addresses: OfflineAddressStore, whole: Boolean = false): String? {
        if (lang.lowercase() !in SoundsLike.LANGS) return null
        val t0 = android.os.SystemClock.elapsedRealtime()
        val fromMap = runCatching { mapStreets?.invoke(center, RADIUS_M) }.getOrNull().orEmpty()
        val mapMs = android.os.SystemClock.elapsedRealtime() - t0
        val fromPacks = withContext(Dispatchers.IO) {
            runCatching { addresses.streetNamesNear(center, RADIUS_M) }.getOrDefault(emptyList())
        }
        // Counts and times only: a log line never carries typed text or a place name.
        android.util.Log.i("VelaSearch", "voice street names: ${fromMap.size} from the map in $mapMs ms, ${fromPacks.size} from downloads, " +
            "${android.os.SystemClock.elapsedRealtime() - t0} ms in all")
        if (fromMap.isEmpty() && fromPacks.isEmpty()) return null
        return withContext(Dispatchers.Default) { SoundsLike.rewrite(heard, lang, fromMap + fromPacks, whole) }
    }
}
