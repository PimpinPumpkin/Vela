package app.vela.offline

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers where a release file's download address redirects. Every archive Vela streams is a
 * GitHub release asset, and each range read of one was two round trips: the release address, which
 * answers with a redirect to a signed address on the storage host, then the read itself. A drive
 * over an area with nothing downloaded makes hundreds of such reads, all of the same few files.
 * The signed address is good until the time it carries; it is reused until shortly before then,
 * and anything but a success from it drops it and asks the release address again.
 */
object ReleaseRedirects : Interceptor {
    private class Kept(val url: HttpUrl, val untilMs: Long)
    private val kept = ConcurrentHashMap<String, Kept>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        if (req.method != "GET" || url.host != "github.com" || "/releases/download/" !in url.encodedPath) return chain.proceed(req)
        val key = url.toString()
        val now = System.currentTimeMillis()
        kept[key]?.let { k ->
            if (k.untilMs > now) {
                val direct = runCatching { chain.proceed(req.newBuilder().url(k.url).build()) }.getOrNull()
                if (direct != null && direct.isSuccessful) return direct
                direct?.close()
            }
            kept.remove(key)
        }
        val resp = chain.proceed(req)
        val landed = resp.request.url
        if (resp.isSuccessful && landed.host != url.host) {
            val until = expiry(landed, now)
            if (until > now) kept[key] = Kept(landed, until)
        }
        return resp
    }

    /** When the signed address stops working, less a minute; at most [MAX_KEEP_MS] from now. */
    internal fun expiry(landed: HttpUrl, now: Long): Long {
        val se = landed.queryParameter("se")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        val amz = landed.queryParameter("X-Amz-Expires")?.toLongOrNull()?.let { now + it * 1000 }
        val end = se ?: amz ?: (now + FALLBACK_KEEP_MS + 60_000)
        return minOf(end - 60_000, now + MAX_KEEP_MS)
    }

    private const val MAX_KEEP_MS = 30 * 60_000L
    private const val FALLBACK_KEEP_MS = 3 * 60_000L
}
