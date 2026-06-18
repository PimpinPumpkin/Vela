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
 * One public-transit option from origin to destination: a departure/arrival
 * time window, total duration/distance, the operating agency, and the ordered
 * list of lines you ride. The per-stop drill-down (intermediate stops + the
 * ridden polyline) is a separate, heavier RPC and is intentionally not modelled
 * here yet — this is the results-board view Google shows first.
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
)
