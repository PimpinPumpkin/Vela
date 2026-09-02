package app.vela.core.nav

/**
 * Deciding WHEN to warn about a speed camera coming up (issue #229).
 *
 * The reporter's ask was to be "informed about an incoming radar control", which a dot on the map
 * does not do while you are driving. This is the timing half, kept pure so the awkward cases are
 * testable: warning too late is useless, warning twice is nagging, and warning at all while you
 * are stopped beside one is just noise.
 */
object CameraAlerts {

    /**
     * Seconds of warning aimed for. Distance-based alone is wrong: 200 m is ample in town and
     * about two seconds on a motorway, which is not time to react.
     */
    const val LEAD_SECONDS = 12.0

    /** Floors and caps the speed-scaled lead. The floor keeps a warning useful at low speed; the
     *  cap stops a motorway warning arriving so early it is forgotten before the camera. */
    const val MIN_LEAD_M = 150.0
    const val MAX_LEAD_M = 600.0

    /** Below this we are not meaningfully driving toward anything - parked, queuing or crawling -
     *  and a spoken camera warning would be noise. */
    const val MOVING_FLOOR_MPS = 2.0

    /** How far the lead distance is for [speedMps]. */
    fun leadDistanceM(speedMps: Double): Double =
        (speedMps * LEAD_SECONDS).coerceIn(MIN_LEAD_M, MAX_LEAD_M)

    /**
     * The index of the camera to announce now, or null.
     *
     * [cameraMeters] are distances along the route, ascending. [spoken] are indices already
     * announced on this route - a camera is warned about ONCE; passing it, or creeping toward it
     * in traffic, must not re-trigger.
     *
     * Returns the NEAREST unannounced camera inside the lead window and still ahead. A camera
     * already behind you is never announced, even if it was missed: a warning about something you
     * have passed is worse than silence.
     */
    fun due(
        cameraMeters: List<Double>,
        traveledM: Double,
        speedMps: Double,
        spoken: Set<Int>,
    ): Int? {
        if (speedMps < MOVING_FLOOR_MPS) return null
        val lead = leadDistanceM(speedMps)
        var best: Int? = null
        var bestAhead = Double.MAX_VALUE
        for (i in cameraMeters.indices) {
            if (i in spoken) continue
            val ahead = cameraMeters[i] - traveledM
            if (ahead <= 0.0 || ahead > lead) continue
            if (ahead < bestAhead) {
                bestAhead = ahead
                best = i
            }
        }
        return best
    }
}
