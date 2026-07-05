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
    val distanceMeters: Double,   // travel AFTER this maneuver, to the next one (OSRM step.distance;
                                  // the Google parser places each turn at the START of its step)
    val durationSeconds: Double,
    val road: String? = null,     // road being entered, for "… onto Elm Street"
    val ref: String? = null,      // highway ref of the road entered ("I 80") for the shield badge —
                                  // separate from [road] because a highway can have a name AND a ref
    val laneHint: String? = null, // e.g. "Use the right 2 lanes" (from Google's step markup)
    val lanes: List<Lane> = emptyList(), // per-lane turn guidance (from OSRM) for the Google-style diagram
)

/** One approach lane's turn guidance: the arrow directions it permits ([indications], OSRM's set —
 *  "straight", "left", "slight right", "sharp left", "uturn", "none", …) and whether it's a valid lane
 *  for THIS maneuver ([valid] → drawn bright/highlighted; the others dimmed). */
data class Lane(val indications: List<String>, val valid: Boolean)

/** Which side of the roadway the lanes to use are on (for spoken/written lane guidance). */
enum class LaneSide { LEFT, RIGHT, CENTER }

/** A spoken-lane recommendation derived from OSRM's per-lane [Lane.valid] flags — the side the valid
 *  lanes sit on and how many there are ("use the right 2 lanes"). */
data class LaneGuidance(val side: LaneSide, val count: Int)

/**
 * Reduce a maneuver's [lanes] to a simple "use the <side> <n> lane(s)" hint, or null when there's
 * nothing useful to say — fewer than 2 lanes, no valid lane, every lane valid (any lane works), or the
 * valid lanes aren't a contiguous block at one edge (too fiddly to phrase; the arrow diagram covers it).
 * Mirrors the bright/dim logic the banner already uses, so the spoken hint matches the arrows.
 */
fun laneGuidance(lanes: List<Lane>): LaneGuidance? {
    if (lanes.size < 2) return null
    val valid = lanes.indices.filter { lanes[it].valid }
    if (valid.isEmpty() || valid.size == lanes.size) return null
    if (valid.last() - valid.first() != valid.size - 1) return null // not contiguous
    val side = when {
        valid.first() == 0 -> LaneSide.LEFT
        valid.last() == lanes.size - 1 -> LaneSide.RIGHT
        else -> LaneSide.CENTER
    }
    return LaneGuidance(side, valid.size)
}

/**
 * For a CONTINUE/STRAIGHT maneuver, whether the lanes represent a GENUINE fork worth announcing —
 * an "off" (invalid) lane that itself offers a straight/slight onward path, i.e. a parallel road you
 * could accidentally follow ("use the left 2 lanes to stay on I-80"). This is the case Google DOES
 * voice. It is FALSE for a plain turn bay at an intersection (an off lane marked only left/right/uturn):
 * you're sailing straight through, the turn lane is irrelevant, and Google stays silent — exactly the
 * "it says use the lanes to continue when nothing changes but the name" report. Requires a real valid
 * subset ([laneGuidance] != null) AND at least one off lane pointing straight-ish. When there are no
 * lanes, or every lane continues, [laneGuidance] is null → false → the continue is silenced.
 */
fun continueHasGenuineFork(lanes: List<Lane>): Boolean {
    if (laneGuidance(lanes) == null) return false
    return lanes.any { lane ->
        // Only an EXPLICIT straight/slight arrow on an off lane signals a parallel onward path. OSRM's
        // "none" means the lane has NO painted arrow (its API's own wording), NOT "continues straight" — a
        // plain turn bay or an unmarked outer lane is commonly emitted as "none", and treating it as
        // straight-ish would re-speak the exact turn-bay case this gate silences. ("through" is never
        // emitted — OSRM normalises the OSM `turn:lanes` value `through` to `straight` — so it's omitted.)
        !lane.valid && lane.indications.any { ind -> ind == "straight" || ind.startsWith("slight") }
    }
}

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
    // Google's *typical* best-case→worst-case spread for this trip ("usually 1 hr 8 min
    // to 1 hr 27 min"), independent of the current moment — its own depart-time planning
    // hint, from the response's summary[10][4]. Null when Google ships no range (short
    // trips, walk/bike). This is the keyless stand-in for a per-departure prediction:
    // the future-departure request field is login/app-only (see DirectionsPb), so we
    // surface the range Google itself shows rather than a false-precision single ETA.
    val typicalLowSeconds: Double? = null,
    val typicalHighSeconds: Double? = null,
    // A "provisional" alternate: its polyline + ETA are good (Google's), but its turn-by-turn is a
    // placeholder — it gets NAMED (map-matched / snapped) only when you actually pick it to navigate,
    // so the picker loads fast and we don't snap routes you never drive. Primary route is never provisional.
    val provisional: Boolean = false,
) {
    val hasLiveTraffic: Boolean get() = durationInTrafficSeconds != null
    val maneuvers: List<Maneuver> get() = legs.flatMap { it.maneuvers }

    /** Google's typical low→high spread, when present (and actually a spread, not a
     *  degenerate point) — drives the depart-time chooser's "usually X–Y" hint. */
    val typicalRangeSeconds: Pair<Double, Double>?
        get() {
            val lo = typicalLowSeconds ?: return null
            val hi = typicalHighSeconds ?: return null
            return if (hi - lo >= 30.0) lo to hi else null
        }

    /** How much slower the live, traffic-aware time is than the typical time
     *  (1.0 = no traffic; 1.4 = 40% slower). Null when no live traffic is known —
     *  drives the route line's congestion colour. */
    val trafficRatio: Double?
        get() = durationInTrafficSeconds?.let { t -> if (durationSeconds > 0) t / durationSeconds else null }
}
