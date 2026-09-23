package app.vela.core.util

import java.util.Random

/**
 * Random spread for timed requests. A client that asks Google again every 120 000 ms exactly, or
 * retries at exactly 300 then 600 ms, has a rhythm no person produces, and every install shares it.
 * Everything that waits a fixed time before a network call draws its wait through here instead.
 */
object Jitter {
    /** Replaceable so a test can make the spread deterministic. */
    @Volatile var random: Random = Random()

    /** A factor drawn uniformly from [1 - spread, 1 + spread]. */
    fun factor(spread: Double = DEFAULT_SPREAD): Double {
        val s = spread.coerceIn(0.0, 0.9)
        return 1.0 - s + random.nextDouble() * 2.0 * s
    }

    /** [ms] scaled by [factor], never below 0. */
    fun around(ms: Long, spread: Double = DEFAULT_SPREAD): Long = (ms * factor(spread)).toLong().coerceAtLeast(0L)

    const val DEFAULT_SPREAD = 0.25
}
