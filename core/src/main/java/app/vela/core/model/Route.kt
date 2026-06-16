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
)

data class RouteLeg(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val durationInTrafficSeconds: Double?, // null when no live traffic available
    val maneuvers: List<Maneuver>,
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
) {
    val hasLiveTraffic: Boolean get() = durationInTrafficSeconds != null
    val maneuvers: List<Maneuver> get() = legs.flatMap { it.maneuvers }
}
