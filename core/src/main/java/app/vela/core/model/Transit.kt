package app.vela.core.model

/** Coarse vehicle class for a transit line, used to pick a glyph + default colour. */
enum class TransitMode { WALK, BUS, TRAM, SUBWAY, TRAIN, FERRY, GENERIC }

/** One coloured line you ride on a transit itinerary (Google draws these as
 *  colour-filled pills, e.g. a blue "Amtrak Thruway" or a green "Route 42B"). */
data class TransitLine(
    val name: String,
    val mode: TransitMode = TransitMode.GENERIC,
    val colorHex: String? = null,     // line fill, e.g. "#cae4f1"
    val textColorHex: String? = null, // legible text on the fill, e.g. "#000000"
)

/**
 * One leg of a transit itinerary — a single walk or ride. The drill-down view
 * lists these in order: "Walk 7 min → Bus 42B 5:48–6:41 AM → Walk 7 min".
 * Intermediate stop names + the ridden polyline live in the same payload too
 * (a future enrichment); this is the leg-summary layer.
 */
data class TransitStep(
    val mode: TransitMode,
    val durationText: String? = null, // "53 min" / "7 min"
    val distanceText: String? = null, // "0.3 mi" (walk legs)
    val line: TransitLine? = null,    // the ridden line (transit legs only)
    val departText: String? = null,   // board time, "5:48 AM" (transit legs)
    val arriveText: String? = null,   // alight time, "6:41 AM"
)

/**
 * One public-transit option from origin to destination: a departure/arrival
 * time window, total duration/distance, the operating agency, the ordered list
 * of lines you ride, and (for the drill-down) the ordered [steps]. All of it
 * comes from one keyless WebView fetch — Google embeds both the summary and the
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
    val lines: List<TransitLine> = emptyList(),
    val steps: List<TransitStep> = emptyList(),
)
