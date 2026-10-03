package app.vela.core.net

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Proof of a working connection from Vela's own traffic (user 2026-10-03, "thinks it's offline
 * randomly"). Android's view of the network can say "none" while requests are getting through:
 * an app whose network is briefly blocked (backgrounding, doze, app standby) is handed no active
 * network at all. Any HTTP response, whatever its status, means the internet answered, so the
 * offline latch trusts this over the system's word for [FRESH_MS], unless a request has failed to reach its host since.
 */
object NetHealth {
    const val FRESH_MS = 15_000L

    @Volatile var lastResponseMs = 0L
        private set

    /** Called on every response; the view model clears a latched offline flag from here. */
    @Volatile var onResponse: (() -> Unit)? = null

    /** The last request that could not reach its host at all (no route, DNS, refused, timeout). */
    @Volatile var lastUnreachableMs = 0L
        private set

    /** A response in the last [FRESH_MS] and no unreachable host since: real offline (a failure
     *  after the last answer) is never hidden by stale success. */
    fun recentlyOnline(now: Long = System.currentTimeMillis()): Boolean =
        now - lastResponseMs < FRESH_MS && lastResponseMs > lastUnreachableMs

    /** How long until [recentlyOnline] could turn false by age alone. */
    fun freshForMs(now: Long = System.currentTimeMillis()): Long = (FRESH_MS - (now - lastResponseMs)).coerceAtLeast(0L)

    fun record() {
        lastResponseMs = System.currentTimeMillis()
        onResponse?.invoke()
    }

    /** Outermost interceptor on an HTTP client: records every response that comes back. */
    val interceptor = Interceptor { chain ->
        val r: Response = try {
            chain.proceed(chain.request())
        } catch (e: java.io.IOException) {
            if (e is java.net.UnknownHostException || e is java.net.ConnectException ||
                e is java.net.NoRouteToHostException || e is java.net.SocketTimeoutException
            ) lastUnreachableMs = System.currentTimeMillis()
            throw e
        }
        record()
        r
    }
}
