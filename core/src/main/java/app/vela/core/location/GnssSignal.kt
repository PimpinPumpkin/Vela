package app.vela.core.location

/**
 * How strong the satellites behind the GPS fix are, from the last GNSS status the phone sent:
 * the mean signal-to-noise density (C/N0, dB-Hz) of the four strongest satellites used in the
 * fix, and how many were used. Under open sky the four strongest read about 40 or more; through a
 * roof they read far lower, while the fix itself can still claim half a meter. A trip records both
 * on each GPS fix line ([app.vela.core.replay.TripLog]). [LocationProvider] sets it while location
 * updates run; pure logic otherwise.
 */
object GnssSignal {
    @Volatile private var top4 = Double.NaN
    @Volatile private var usedCount = 0
    @Volatile private var atMs = 0L

    /** Take one status: the C/N0 of each satellite used in the fix, at [nowMs] on the boot clock. */
    fun update(usedCn0: List<Double>, nowMs: Long) {
        top4 = top4Of(usedCn0)
        usedCount = usedCn0.size
        atMs = nowMs
    }

    /** The mean of the four strongest, or NaN with none. */
    fun top4Of(cn0: List<Double>): Double =
        if (cn0.isEmpty()) Double.NaN else cn0.sortedDescending().take(4).average()

    /** The four strongest used satellites' mean C/N0 and the count used, or null when no status
     *  came in the last [FRESH_MS] (a mock or simulated fix, or a phone that sends none). */
    fun latest(nowMs: Long): Pair<Double, Int>? =
        if (atMs == 0L || nowMs - atMs > FRESH_MS || top4.isNaN()) null else top4 to usedCount

    fun reset() { top4 = Double.NaN; usedCount = 0; atMs = 0L }

    /** A status this old no longer describes the fix (they come about once a second). */
    const val FRESH_MS = 2_000L
}
