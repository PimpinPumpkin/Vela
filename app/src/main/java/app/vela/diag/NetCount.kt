package app.vela.diag

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Counts the map's own network requests by what they were for, so a recorded drive says what was
 * being fetched while it ran: a flood of overlay requests looks like "the map is slow" from the
 * driver's seat and like nothing at all in a frame count. The bucket is the host, or for a file
 * on Vela's releases the release it belongs to ("maxspeed-overlays"). Never a path or a file
 * name: those carry tile coordinates and region names.
 */
object NetCount : Interceptor {
    private val counts = HashMap<String, Int>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val seg = url.pathSegments
        val i = seg.indexOf("download")
        val bucket = if (url.host == "github.com" && i >= 0 && i + 1 < seg.size) seg[i + 1] else url.host
        synchronized(counts) { counts[bucket] = (counts[bucket] ?: 0) + 1 }
        return chain.proceed(chain.request())
    }

    /** What was asked for since the last call, busiest first, or null when nothing was. */
    fun drain(): String? = synchronized(counts) {
        if (counts.isEmpty()) return null
        val total = counts.values.sum()
        val parts = counts.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { "${it.key} ${it.value}" }
        counts.clear()
        "$total map requests ($parts)"
    }
}
