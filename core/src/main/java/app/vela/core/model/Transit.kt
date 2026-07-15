package app.vela.core.model

/** Coarse vehicle class for a transit line, used to pick a glyph + default colour. */
enum class TransitMode { WALK, BUS, TRAM, SUBWAY, TRAIN, FERRY, GENERIC }

/** One coloured line you ride on a transit itinerary (Google draws these as
 * colour-filled pills, e.g. a blue "Amtrak Thruway" or a green "Route 42B"). */
data class TransitLine(
    val name: String,
    val mode: TransitMode = TransitMode.GENERIC,
    val colorHex: String? = null,     // line fill, e.g. "#cae4f1"
    val textColorHex: String? = null, // legible text on the fill, e.g. "#000000"
)

/** A single stop on a transit leg (board / alight / intermediate), with its name,
 * agency stop code ("Stop ID"), and the time the vehicle calls there. When the
 * live (real-time) time differs from the timetable, [scheduledText] carries the
 * original so the UI can show "4:30 → 4:35 (5 min late)" like Google. */
data class TransitStopTime(
    val name: String,
    val code: String? = null,           // agency stop code, e.g. "A10V1752"
    val timeText: String? = null,       // the shown (real-time if live) time, "4:35 PM"
    val scheduledText: String? = null,  // the timetable time when it differs, "4:30 PM"
    val location: LatLng? = null,       // stop position (for drawing / walk-leg routing)
    val cancelled: Boolean = false,     // this call is cancelled (GTFS-Realtime; Google data never sets it)
    val delayMin: Int? = null,          // realtime minus timetable, minutes; negative = running early
)

/**
 * One leg of a transit itinerary - a single walk or ride. The drill-down view
 * lists these in order: "Walk 7 min → Bus 42B 5:48–6:41 AM → Walk 7 min".
 * Ride legs also carry the full stop detail Google shows: the board/alight stops
 * (with codes + times), the ridden headsign ("towards …"), the number of stops,
 * the delay, and every intermediate stop - all from the same keyless payload.
 */
data class TransitStep(
    val mode: TransitMode,
    val durationText: String? = null, // "53 min" / "7 min"
    val distanceText: String? = null, // "0.3 mi" (walk legs)
    val line: TransitLine? = null,    // the ridden line (transit legs only)
    val departText: String? = null,   // board time, "5:48 AM" (transit legs)
    val arriveText: String? = null,   // alight time, "6:41 AM"
    val headsign: String? = null,             // "Aventura Mall Terminal Via M. Gardens Dr"
    val boardStop: TransitStopTime? = null,   // where you get on
    val alightStop: TransitStopTime? = null,  // where you get off
    val numStops: Int? = null,                // "Ride 17 stops"
    val delayText: String? = null,            // "5 min late" / "2 min early" (real-time)
    val intermediateStops: List<TransitStopTime> = emptyList(), // the in-between stops
    // Stops the run calls at BEFORE the boarding stop (the stop-timeline view shows them greyed
    // above your stop, Google-style). Empty for itinerary-sourced steps.
    val priorStops: List<TransitStopTime> = emptyList(),
    // Walk-leg endpoints (from the adjacent stops / itinerary origin+dest) - used to fetch
    // turn-by-turn walking directions for the leg on demand (OSRM foot). Null for ride legs.
    val walkFrom: LatLng? = null,
    val walkTo: LatLng? = null,
)

/**
 * One public-transit option from origin to destination: a departure/arrival
 * time window, total duration/distance, the operating agency, the ordered list
 * of lines you ride, and (for the drill-down) the ordered [steps]. All of it
 * comes from one keyless WebView fetch - Google embeds both the summary and the
 * per-leg detail in the same `APP_INITIALIZATION_STATE` payload.
 */
data class TransitItinerary(
    val departureEpochSec: Long? = null,
    val arrivalEpochSec: Long? = null,
    val departureText: String? = null, // "6:10 AM" (already localised by Google)
    val arrivalText: String? = null,   // "6:55 AM"
    val durationText: String? = null,  // "45 min"
    val distanceText: String? = null,  // "15.0 miles"
    val agency: String? = null,        // "Amtrak Chartered Vehicle"
    val agencyPhone: String? = null,   // "1 (305) 891-3131" - dialable "Tickets and information"
    val alerts: List<String> = emptyList(), // service alerts ("Route 9 - Southbound Detour")
    val fare: String? = null,          // "$2.25" when the agency provides it (often absent)
    val lines: List<TransitLine> = emptyList(),
    val steps: List<TransitStep> = emptyList(),
)
