package app.vela.core.replay

import java.util.Locale

/**
 * The nav smoothness trace a recorded trip keeps beside it (Settings > Diagnostics > Nav
 * smoothness trace): one row per drawn navigation frame of the numbers behind the camera, in a
 * file of its own so the trip stays small enough to read whole.
 *
 * A row holds no position: a time, meters along the route, a speed, bearings and frame timing.
 * Strung together they still retrace the drive's shape (a bearing and a speed every frame), and
 * the meters along the route say how far the car was from where that route began, which for the
 * first route is the driveway. So a trimmed share ([trim]) keeps only rows beside a fix the
 * trip's own trim kept, on the trip's rebased clock, and counts each route's meters from its
 * first kept row.
 */
object TripNavTrace {

    /** The file's column line. `t_ms` counts from the trip's start, the time in its `META` line. */
    const val COLUMNS = "t_ms,progress_m,speed_mps,window_m,chord_deg,display_deg,camera_deg," +
        "camera_target_deg,frame_dt_ms,gyro_dps,hold_m"

    /** Values in a row, in [COLUMNS] order. */
    const val WIDTH = 11

    private const val T = 0
    private const val PROGRESS = 1
    private const val SPEED = 2
    private const val WINDOW = 3
    private const val HOLD = 10

    /** A row this far back along the route from the one before starts a new route (a reroute
     *  begins at the car). */
    const val NEW_ROUTE_BACK_M = 20.0

    /** A row this far on from the one before, or this long after it, also starts one: a frame
     *  covers well under a meter, so a jump this size is a new route or a gap in drawing. */
    const val NEW_ROUTE_JUMP_M = 200.0
    const val NEW_ROUTE_GAP_MS = 2_000L

    /** One row as a file line. NaN values are empty. */
    fun format(row: DoubleArray): String {
        val sb = StringBuilder(72)
        for (i in 0 until WIDTH) {
            if (i > 0) sb.append(',')
            val v = row.getOrElse(i) { Double.NaN }
            if (v.isNaN()) continue
            when (i) {
                T -> sb.append(v.toLong())
                SPEED -> sb.append(String.format(Locale.US, "%.2f", v))
                WINDOW -> sb.append(String.format(Locale.US, "%.0f", v))
                else -> sb.append(String.format(Locale.US, "%.1f", v))
            }
        }
        return sb.toString()
    }

    /**
     * The part of [trace] that may go out with a trimmed trip. [startedAt] is the trip's start
     * (its `META` time) and [keptFixTimes] the raw times of the fixes [TripScrub] kept, in order.
     *
     * A row is kept only between the first and last kept fix and within
     * [TripScrub.EVENT_NEAR_MS] of one, the rule for a walk's steps: a Home or Work zone passed
     * mid-drive takes its frames with its fixes. Times are rebased to the first kept fix, as the
     * trip's are. Each route's `progress_m` and `hold_m` count from that route's first kept row.
     * Comment lines and the column line are kept. Null when no row is left.
     */
    fun trim(trace: String, startedAt: Long, keptFixTimes: LongArray): String? {
        if (keptFixTimes.isEmpty()) return null
        val tZero = keptFixTimes.first()
        val from = keptFixTimes.first()
        val to = keptFixTimes.last()
        fun nearKeptFix(t: Long): Boolean {
            var lo = 0
            var hi = keptFixTimes.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (keptFixTimes[mid] < t) lo = mid + 1 else hi = mid
            }
            val a = keptFixTimes[lo]
            val b = if (lo > 0) keptFixTimes[lo - 1] else a
            return kotlin.math.abs(a - t) <= TripScrub.EVENT_NEAR_MS || kotlin.math.abs(b - t) <= TripScrub.EVENT_NEAR_MS
        }
        val out = StringBuilder()
        var rows = 0
        var prevT = Long.MIN_VALUE
        var prevP = Double.NaN
        // The meters a route's first kept row stood at; NaN until one is kept.
        var base = Double.NaN
        for (line in trace.lineSequence()) {
            if (line.isBlank()) continue
            if (line.startsWith("#") || line.startsWith("t_ms")) { out.append(line).append('\n'); continue }
            val f = line.split(',')
            val t = f.getOrNull(T)?.toLongOrNull() ?: continue
            val p = f.getOrNull(PROGRESS)?.toDoubleOrNull() ?: Double.NaN
            if (prevT == Long.MIN_VALUE || t - prevT > NEW_ROUTE_GAP_MS ||
                (!p.isNaN() && !prevP.isNaN() && (p < prevP - NEW_ROUTE_BACK_M || p > prevP + NEW_ROUTE_JUMP_M))
            ) base = Double.NaN
            prevT = t
            if (!p.isNaN()) prevP = p
            val at = startedAt + t
            if (at < from || at > to || !nearKeptFix(at)) continue
            if (base.isNaN() && !p.isNaN()) base = p
            val cols = f.toMutableList()
            cols[T] = (at - tZero).toString()
            if (!p.isNaN()) cols[PROGRESS] = String.format(Locale.US, "%.1f", p - base)
            val hold = cols.getOrNull(HOLD)?.toDoubleOrNull()
            if (hold != null) cols[HOLD] = if (base.isNaN()) "" else String.format(Locale.US, "%.1f", hold - base)
            out.append(cols.joinToString(",")).append('\n')
            rows++
        }
        return if (rows == 0) null else out.toString()
    }
}
