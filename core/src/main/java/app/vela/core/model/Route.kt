package app.vela.core.model

enum class TravelMode { DRIVE, WALK, BICYCLE, TRANSIT }

enum class ManeuverType {
    DEPART, ARRIVE, CONTINUE, STRAIGHT,
    TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    UTURN, MERGE, FORK_LEFT, FORK_RIGHT, RAMP_LEFT, RAMP_RIGHT,
    ROUNDABOUT, EXIT_ROUNDABOUT, KEEP_LEFT, KEEP_RIGHT, UNKNOWN,
}

/** One step of a route. [instruction] is the human text fed to TTS + the banner. */
data class Maneuver(
    val type: ManeuverType,
    val instruction: String,
    val location: LatLng,
    val distanceMeters: Double,   // length of the step that ENDS at this maneuver
    val durationSeconds: Double,
    val road: String? = null,     // road being entered, for "… onto Elm Street"
    val laneHint: String? = null, // e.g. "Use the right 2 lanes" (from Google's step markup)
)

data class RouteLeg(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val durationInTrafficSeconds: Double?, // null when no live traffic available
    val maneuvers: List<Maneuver>,
)

/** One live-traffic congestion span along the route. [level] is Google's
 *  congestion grade (1 = moderate, 2 = heavy, 3+ = severe); free-flowing stretches
 *  are NOT listed (they're the gaps). [startMeters]..[startMeters]+[lengthMeters]
 *  locates it by distance from the route start — divide by the route distance for a
 *  fraction-along-route, which drives the per-segment colour of the route line. */
data class TrafficSpan(
    val level: Int,
    val startMeters: Double,
    val lengthMeters: Double,
)

/**
 * A full route. When [durationInTrafficSeconds] is non-null it came straight
 * out of Google's directions response — i.e. the traffic is already baked in,
 * which is the entire reason for scraping directions rather than self-routing.
 */
data class Route(
    val polyline: List<LatLng>,
    val legs: List<RouteLeg>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val durationInTrafficSeconds: Double?,
    val summary: String? = null,
    val trafficSpans: List<TrafficSpan> = emptyList(),
) {
    val hasLiveTraffic: Boolean get() = durationInTrafficSeconds != null
    val maneuvers: List<Maneuver> get() = legs.flatMap { it.maneuvers }

    /** How much slower the live, traffic-aware time is than the typical time
     *  (1.0 = no traffic; 1.4 = 40% slower). Null when no live traffic is known —
     *  drives the route line's congestion colour. */
    val trafficRatio: Double?
        get() = durationInTrafficSeconds?.let { t -> if (durationSeconds > 0) t / durationSeconds else null }
}
