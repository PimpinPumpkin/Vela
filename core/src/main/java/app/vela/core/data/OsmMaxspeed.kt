package app.vela.core.data

/**
 * Parses an OSM `maxspeed` tag value into km/h - the "Speed B" online source reads these raw strings out
 * of the hosted speed-limit PMTiles overlay (built by `scripts/build-maxspeed-region.sh`), unlike the
 * offline GraphHopper path which reads a pre-decoded encoded value.
 *
 * OSM maxspeed is messy: a bare number is km/h ("50"), an explicit unit may follow ("30 mph", "50 km/h"),
 * and a lot of non-numeric forms exist. We deliberately return **null** (unknown) rather than guess for:
 *  - `none` (a derestricted autobahn) - a real number is not known, and a fake one is worse than a blank;
 *  - implicit country codes (`DE:urban`, `GB:nsl_single`, `RO:motorway`) - resolving those needs a
 *    per-country default table we don't ship;
 *  - `signals` / `variable` / `unknown` and anything else without a leading number.
 * Same spirit as the offline path blanking on the ambiguous 150 km/h cap.
 */
object OsmMaxspeed {
    private const val MPH_TO_KMH = 1.609344
    // A leading number, optional decimal, optional unit. Implicit codes ("de:urban") have no leading
    // digit so they never match → null. A list/range ("50; 30") takes the first value.
    private val NUM = Regex("""^\s*(\d+(?:\.\d+)?)\s*(mph|km/?h|kph)?""")

    /** [raw] OSM maxspeed string → km/h, or null when it isn't a knowable posted number. Range-checked
     *  to 1..200 km/h so a garbage tag can't drive a nonsense sign. */
    fun parseKmh(raw: String?): Double? {
        val s = raw?.trim()?.lowercase() ?: return null
        if (s.isEmpty() || s == "none" || s == "signals" || s == "variable" || s == "unknown") return null
        if (s == "walk") return 7.0 // OSM's living-street "walking pace" convention
        val m = NUM.find(s) ?: return null
        val num = m.groupValues[1].toDoubleOrNull() ?: return null
        val kmh = if (m.groupValues[2] == "mph") num * MPH_TO_KMH else num // bare or km/h ⇒ km/h
        return kmh.takeIf { it in 1.0..200.0 }
    }

    /** Pick the best posted value from a maxspeed feature's tags, preferring the plain `maxspeed`, then a
     *  directional one (we don't yet know travel direction on the overlay, so forward wins as the common
     *  carriageway sense). Returns km/h or null. */
    fun fromTags(maxspeed: String?, forward: String? = null, backward: String? = null): Double? =
        parseKmh(maxspeed) ?: parseKmh(forward) ?: parseKmh(backward)
}
