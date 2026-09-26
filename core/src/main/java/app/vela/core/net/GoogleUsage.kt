package app.vela.core.net

import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many requests Vela sends to Google, by purpose and by day, counted on the phone and never
 * sent anywhere (2026-09-25). "Vela uses Google sparingly" should be a number: Settings > Privacy
 * shows it and the diagnostics export carries it, so the next cut in Google traffic goes where the
 * volume actually is.
 *
 * Counted: every request to a Google host through the shared HTTP client ([GoogleTransport.hook]
 * calls [record], whether Cronet or OkHttp carries it), tagged with its purpose by the caller
 * ([Kind]) or classified from the URL ([kindOf]); and every hidden Google page load (the app counts
 * those where they start, and every request such a page makes after it loads, as "page resources").
 * NOT counted: map tiles MapLibre fetches itself (the live-traffic layer, satellite imagery past
 * Esri's coverage).
 */
object GoogleUsage {
    /** OkHttp request tag naming the purpose of a Google request. */
    data class Kind(val name: String)

    private val days = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>>()

    /** Called after every change (the app saves on it, debounced). */
    @Volatile var onChange: (() -> Unit)? = null

    /** Retention: days kept by [restore] and [snapshot]. */
    const val KEEP_DAYS = 14

    fun today(): String = LocalDate.now().toString()

    fun record(kind: String, day: String = today()) {
        days.getOrPut(day) { ConcurrentHashMap() }.getOrPut(kind) { AtomicInteger() }.incrementAndGet()
        onChange?.invoke()
    }

    /** Is [host] Google's? The hosts Vela's requests and images reach. */
    fun isGoogle(host: String): Boolean {
        val h = host.lowercase()
        return listOf("google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "ggpht.com")
            .any { h == it || h.endsWith(".$it") }
    }

    /** The purpose of an untagged Google request, read off its URL. */
    fun kindOf(host: String, path: String, query: String?): String {
        val h = host.lowercase()
        val q = query.orEmpty()
        return when {
            h.endsWith("googleusercontent.com") || h.endsWith("ggpht.com") || h.endsWith("gstatic.com") -> "images"
            h.startsWith("streetviewpixels") || path.contains("GeoPhotoService") || path.contains("/photometa/") -> "street view"
            path.contains("batchexecute") && q.contains("hspqX") -> "photos"
            path.contains("batchexecute") && q.contains("qv9Egd") -> "reviews"
            path.startsWith("/maps/preview/directions") -> "directions"
            path == "/s" -> "suggestions"
            path == "/search" -> "search"
            path.startsWith("/maps") && q.isEmpty() || path == "/maps" -> "session"
            else -> "other"
        }
    }

    /** Day -> kind -> count, newest day first, at most [KEEP_DAYS] days. */
    fun snapshot(): Map<String, Map<String, Int>> =
        days.entries.sortedByDescending { it.key }.take(KEEP_DAYS)
            .associate { (day, kinds) -> day to kinds.mapValues { it.value.get() }.toSortedMap() }

    /** Load saved days (the app's store), dropping anything older than [KEEP_DAYS]. */
    fun restore(saved: Map<String, Map<String, Int>>) {
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS.toLong()).toString()
        saved.filterKeys { it > cutoff }.forEach { (day, kinds) ->
            val m = days.getOrPut(day) { ConcurrentHashMap() }
            kinds.forEach { (k, n) -> m.getOrPut(k) { AtomicInteger() }.addAndGet(n) }
        }
        days.keys.filter { it <= cutoff }.forEach { days.remove(it) }
    }

    /** Clear everything (tests, and a user resetting the count). */
    fun reset() { days.clear(); onChange?.invoke() }
}
