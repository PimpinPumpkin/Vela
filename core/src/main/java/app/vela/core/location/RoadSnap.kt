package app.vela.core.location

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pulls the no-route dot onto the road under it (issue #803). With a route the arrow rides the
 * route line. With none it is the fix, and a weak GPS (a head unit's) puts it a lane or a row of
 * houses off the road.
 *
 * Each fix while driving is matched against the drawn roads near it ([Road], read by the map) and
 * moves onto the nearest one that runs the way the car is going. Nothing moves when no road
 * agrees (a turn, a parking lot), when two different roads are about as near (a fork, a frontage
 * road), or under [START_MPS]. A snap held through one fix with no answer lets go on the next.
 * Slowing or stopping keeps the offset until the fix has moved [HOLD_M] from where it was set,
 * so the dot stays on the road at a red light.
 *
 * The result is an offset in degrees ([offLat], [offLng]) for the caller to ease in and out.
 */
class RoadSnap {
    /** One drawn road line. [oneway] 1 runs with the points, -1 against them, 0 both ways.
     *  [penaltyM] is added to its distance, so a service road loses to a street a little farther. */
    class Road(val lats: DoubleArray, val lngs: DoubleArray, val oneway: Int, val penaltyM: Double)

    var offLat = 0.0
        private set
    var offLng = 0.0
        private set
    val snapped: Boolean get() = offLat != 0.0 || offLng != 0.0
    /** Meters from the last fix to the road it was moved onto, or NaN with no snap on it. */
    var lastDistM = Double.NaN
        private set

    private var setLat = Double.NaN // the fix the held offset was set at
    private var setLng = Double.NaN
    private var footLat = Double.NaN // where that fix landed on the road
    private var footLng = Double.NaN
    private var footAtMs = 0L
    private var misses = 0

    fun clear() {
        offLat = 0.0; offLng = 0.0; lastDistM = Double.NaN
        setLat = Double.NaN; setLng = Double.NaN; footLat = Double.NaN; footLng = Double.NaN
        footAtMs = 0L; misses = 0
    }

    /** Takes one fix. [roads] null means the map could not say (no style, the fix off screen),
     *  which counts as no answer. Returns whether an offset is applied after it. */
    fun onFix(lat: Double, lng: Double, speedMps: Double, courseDeg: Double?, roads: List<Road>?, nowMs: Long): Boolean {
        val moving = courseDeg != null && speedMps >= START_MPS
        if (!moving) {
            // Slow or stopped: hold the road while the car is still about where it was put on it.
            if (snapped && !setLat.isNaN() && meters(lat, lng, setLat, setLng) > HOLD_M) clear()
            lastDistM = Double.NaN
            return snapped
        }
        val hit = if (roads == null) null else pick(lat, lng, courseDeg!!, speedMps, roads, nowMs)
        if (hit == null) {
            lastDistM = Double.NaN
            if (++misses >= RELEASE_MISSES) clear()
            return snapped
        }
        misses = 0
        offLat = hit[0] - lat; offLng = hit[1] - lng
        lastDistM = meters(lat, lng, hit[0], hit[1])
        setLat = lat; setLng = lng; footLat = hit[0]; footLng = hit[1]; footAtMs = nowMs
        return true
    }

    /** The point on the chosen road, or null. Local flat frame in meters around the fix. */
    private fun pick(lat: Double, lng: Double, course: Double, speed: Double, roads: List<Road>, nowMs: Long): DoubleArray? {
        val ky = M_PER_DEG
        val kx = M_PER_DEG * cos(Math.toRadians(lat))
        // Where the last snap would be now, moved along the course: a road that continues it is
        // preferred, so the dot does not hop between two lines that both nearly fit.
        var predX = Double.NaN; var predY = Double.NaN
        if (!footLat.isNaN() && nowMs - footAtMs <= STICK_FRESH_MS) {
            val run = speed * (nowMs - footAtMs) / 1000.0
            val br = Math.toRadians(course)
            predX = (footLng - lng) * kx + run * sin(br)
            predY = (footLat - lat) * ky + run * cos(br)
        }
        val effs = ArrayList<Double>(); val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        var best = -1
        for (r in roads) {
            val n = minOf(r.lats.size, r.lngs.size)
            for (i in 0 until n - 1) {
                val ax = (r.lngs[i] - lng) * kx; val ay = (r.lats[i] - lat) * ky
                val bx = (r.lngs[i + 1] - lng) * kx; val by = (r.lats[i + 1] - lat) * ky
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (len2 < MIN_SEG_M * MIN_SEG_M) continue
                val t = ((-ax) * dx + (-ay) * dy) / len2
                val tc = t.coerceIn(0.0, 1.0)
                val fx = ax + dx * tc; val fy = ay + dy * tc
                val d = hypot(fx, fy)
                if (d > MAX_M) continue
                val segBr = Math.toDegrees(atan2(dx, dy))
                val off = when (r.oneway) {
                    1 -> angle(segBr, course)
                    -1 -> angle(segBr + 180.0, course)
                    else -> minOf(angle(segBr, course), angle(segBr + 180.0, course))
                }
                if (off > MAX_ANGLE_DEG) continue
                var eff = d + r.penaltyM
                if (!predX.isNaN() && hypot(fx - predX, fy - predY) <= STICK_M) eff -= STICK_BONUS_M
                effs.add(eff); xs.add(fx); ys.add(fy)
                if (best < 0 || eff < effs[best]) best = effs.size - 1
            }
        }
        if (best < 0) return null
        // Another road about as near, somewhere else: a fork or a parallel road. Do not guess.
        for (j in effs.indices) {
            if (j == best) continue
            if (effs[j] <= effs[best] + AMBIGUOUS_M && hypot(xs[j] - xs[best], ys[j] - ys[best]) > SAME_ROAD_M) return null
        }
        return doubleArrayOf(lat + ys[best] / ky, lng + xs[best] / kx)
    }

    companion object {
        /** Speed at which a snap starts, m/s (about 14 km/h). Under it a snap is only held. */
        const val START_MPS = 4.0
        /** Farthest a fix is moved onto a road. */
        const val MAX_M = 30.0
        /** Road direction against the GPS course, degrees. */
        const val MAX_ANGLE_DEG = 35.0
        /** A second road within this many meters of the best one, elsewhere, makes it a guess. */
        const val AMBIGUOUS_M = 8.0
        /** Two foot points closer than this are the same road (its casing, the next piece). */
        const val SAME_ROAD_M = 6.0
        /** Bonus for the road that continues the last snap, and how near the prediction it must be. */
        const val STICK_BONUS_M = 6.0
        const val STICK_M = 10.0
        const val STICK_FRESH_MS = 3_000L
        /** Fixes in a row with no road before the snap lets go. */
        const val RELEASE_MISSES = 2
        /** How far a slow or stopped car may move before a held snap lets go. */
        const val HOLD_M = 25.0
        /** Added to a service road's distance. */
        const val SERVICE_PENALTY_M = 20.0
        private const val MIN_SEG_M = 0.5
        private const val M_PER_DEG = 111_320.0

        /** The penalty for an OpenMapTiles `transportation` line, or null for one a car does not
         *  drives at speed (paths, tracks, busways, rail, ferries, construction, driveways and
         *  parking aisles). */
        fun penaltyFor(cls: String?, service: String?): Double? = when (cls) {
            "motorway", "trunk", "primary", "secondary", "tertiary", "minor" -> 0.0
            "service" -> if (service == "driveway" || service == "parking_aisle") null else SERVICE_PENALTY_M
            else -> null
        }

        private fun angle(a: Double, b: Double): Double {
            val d = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)
            return d
        }

        private fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val ky = M_PER_DEG; val kx = M_PER_DEG * cos(Math.toRadians(aLat))
            return hypot((bLat - aLat) * ky, (bLng - aLng) * kx)
        }
    }
}
