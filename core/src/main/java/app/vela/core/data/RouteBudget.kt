package app.vela.core.data

import app.vela.core.model.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * A monotonic deadline for one routing fetch (issues #258 / #557).
 *
 * A mid-drive reroute runs under a hard deadline in NavSession, but the fetch underneath used to
 * know nothing about it: the open router got the shared client's 12 s call timeout per try, three
 * tries on the escalated attempt, and only THEN were Google's answer or the on-device engine
 * consulted. With FOSSGIS hanging, the deadline expired before any fallback was even asked. The
 * budget travels into the fetch so each stage takes only its share and the fallbacks get the rest.
 * [NONE] is a planning fetch: no deadline, behaviour unchanged.
 */
class RouteBudget private constructor(
    private val endNanos: Long?,
    private val clock: () -> Long,
) {
    private val startNanos = clock()

    val bounded: Boolean get() = endNanos != null

    /** Milliseconds left, never negative; null when unbounded. */
    fun remainingMs(): Long? = endNanos?.let { ((it - clock()) / 1_000_000L).coerceAtLeast(0L) }

    fun elapsedMs(): Long = (clock() - startNanos) / 1_000_000L

    /** A child budget of at most [ms] (or what is left of this one, whichever is smaller). */
    fun slice(ms: Long): RouteBudget {
        val left = remainingMs() ?: return of(ms, clock)
        return of(minOf(ms, left), clock)
    }

    companion object {
        /** An attempt with less than this left is not worth starting: a TLS handshake alone can
         *  take most of a second on a weak cell link. */
        const val MIN_TRY_MS = 1_500L

        val NONE = RouteBudget(null, System::nanoTime)

        fun of(ms: Long?, clock: () -> Long = System::nanoTime): RouteBudget =
            if (ms == null) RouteBudget(null, clock) else RouteBudget(clock() + ms.coerceAtLeast(0L) * 1_000_000L, clock)

        /** May another attempt start with [remainingMs] left? Unbounded always may. */
        fun canTry(remainingMs: Long?): Boolean = remainingMs == null || remainingMs >= MIN_TRY_MS

        /** The call timeout for the next attempt: the smaller of the per-try cap and what is left,
         *  or null to keep the shared client's own timeouts (a planning fetch). */
        fun tryTimeoutMs(perTryMs: Long?, remainingMs: Long?): Long? =
            listOfNotNull(perTryMs, remainingMs).minOrNull()

        /** [http] with every timeout pulled in to [ms]. `newBuilder` shares the connection pool and
         *  dispatcher, so this is cheap enough for the few calls a reroute makes. */
        fun bounded(http: OkHttpClient, ms: Long): OkHttpClient {
            val t = ms.coerceAtLeast(1L)
            return http.newBuilder()
                .callTimeout(t, TimeUnit.MILLISECONDS)
                .connectTimeout(t, TimeUnit.MILLISECONDS)
                .readTimeout(t, TimeUnit.MILLISECONDS)
                .writeTimeout(t, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}

/**
 * What a bounded reroute falls back to once the open router has come back empty (issue #557):
 * (a) Google's route from the same fetch if it has already arrived, (b) the on-device engine if a
 * downloaded region covers the trip, (c) nothing. While both are still out, whichever produces a
 * route first inside the budget wins, so a slow Google cannot hold back a ready offline route and
 * a slow offline compute cannot hold back Google.
 *
 * The on-device compute is UNSTRUCTURED on purpose, the same trap as the avoid path
 * (AVOID_ONDEVICE_TIMEOUT_MS): the native router ignores cancellation, and a structured child
 * would keep the caller open until it finished. Past the budget it is abandoned and finishes into
 * the void.
 */
object RerouteFallback {
    enum class Source { GOOGLE_READY, GOOGLE, ON_DEVICE, NONE }

    data class Outcome(val routes: List<Route>, val source: Source, val waitedMs: Long, val onDeviceTried: Boolean)

    suspend fun pick(
        google: Deferred<List<Route>>,
        onDevice: (() -> List<Route>)?,
        budgetMs: Long,
        clock: () -> Long = System::nanoTime,
    ): Outcome {
        val t0 = clock()
        fun waited() = (clock() - t0) / 1_000_000L
        // (a) Google already answered: no reason to spin up a native compute at all.
        if (google.isCompleted) {
            val g = runCatching { google.await() }.getOrDefault(emptyList())
            if (g.isNotEmpty()) return Outcome(g, Source.GOOGLE_READY, waited(), onDeviceTried = false)
        }
        val offline: Deferred<List<Route>>? = onDevice?.let { block ->
            CoroutineScope(Dispatchers.IO).async { runCatching { block() }.getOrDefault(emptyList()) }
        }
        var g: List<Route>? = if (google.isCompleted) emptyList() else null
        var o: List<Route>? = if (offline == null) emptyList() else null
        withTimeoutOrNull(budgetMs.coerceAtLeast(0L)) {
            while (g.isNullOrEmpty() && o.isNullOrEmpty() && (g == null || o == null)) {
                select<Unit> {
                    if (g == null) google.onAwait { g = it }
                    if (o == null) offline?.onAwait { o = it }
                }
            }
        }
        val gr = g
        val or = o
        return when {
            !or.isNullOrEmpty() && gr.isNullOrEmpty() -> Outcome(or, Source.ON_DEVICE, waited(), true)
            !gr.isNullOrEmpty() -> {
                offline?.cancel() // best effort; a native compute already running ignores it
                Outcome(gr, Source.GOOGLE, waited(), offline != null)
            }
            else -> {
                offline?.cancel()
                Outcome(emptyList(), Source.NONE, waited(), offline != null)
            }
        }
    }
}
