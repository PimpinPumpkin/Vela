package app.vela.core.model

/**
 * A transit stop's live departure board — what leaves this station/stop soon, the way
 * Google shows it when you open a transit station and tap "See departure board".
 *
 * Parsed KEYLESS from the station's own place page (the same anonymous WebView +
 * `APP_INITIALIZATION_STATE` channel Vela already uses for transit itineraries and
 * photos): the schedule is embedded in the place-details payload, so there's no new
 * endpoint and no login (unlike popular times, which is stripped from anonymous
 * sessions). Calibrated against a live NYC subway-hub capture (2026-07-12).
 */
data class StopDepartures(
    val stationName: String? = null,
    val lines: List<StopDepartureLine> = emptyList(),
)

/** One line + direction served by the stop (Google lists each direction separately),
 *  with its next few departures and the running frequency. */
data class StopDepartureLine(
    val label: String? = null,        // route short name, "7" / "42" (null when Google omits it)
    val mode: TransitMode = TransitMode.GENERIC,
    val headsign: String? = null,     // direction / destination, "34 St-Hudson Yards"
    val colorHex: String? = null,     // line fill when present
    val headwayText: String? = null,  // running frequency, "20 min" (Google-localized)
    val upcoming: List<StopDeparture> = emptyList(),
)

/** A single upcoming departure: the shown clock time plus its epoch (for a countdown),
 *  flagged [realtime] when Google's live time differs from the timetable. */
data class StopDeparture(
    val clockText: String? = null,    // "4:35 AM" (already localized by Google)
    val epochSec: Long? = null,       // scheduled/real-time departure epoch (seconds)
    val realtime: Boolean = false,
)
