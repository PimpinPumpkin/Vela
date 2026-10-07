package app.vela.core.nav

/** Which highway-shield SHAPE to draw for a route ref pulled out of a maneuver instruction. */
enum class ShieldType { INTERSTATE, US_ROUTE, STATE, GENERIC }

/** A parsed route reference: the shield kind, the number that goes inside it, and an optional
 *  trailing cardinal ("I-80 E" → INTERSTATE / "80" / "E"). [raw] is the original label, used as
 *  the fallback text for a [ShieldType.GENERIC] ref we don't have a shape for. */
data class RouteRef(val type: ShieldType, val number: String, val direction: String?, val raw: String)

/** US states (+ DC) and Canadian provinces/territories — a 2-letter prefix in this set is a
 *  state/provincial route and gets the generic state shield. (Country-specific shapes — a US
 *  state circle vs Ontario's crown, etc. — are the long-tail follow-up; v1 draws one neutral
 *  state marker for all of them.) */
private val STATE_PROVINCE: Set<String> = setOf(
    // US
    "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN",
    "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH",
    "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT",
    "VT", "VA", "WA", "WV", "WI", "WY",
    // Canada
    "ON", "QC", "BC", "AB", "MB", "SK", "NS", "NB", "NL", "PE", "NT", "YT", "NU",
)

/** Parse a route-shield label ("I-80 E", "US 50", "CA-99", "ON-401", "SR 1") into the shield
 *  kind + number + direction. Network is inferred from the prefix (+ the state set) — no OSM
 *  lookup. Anything unrecognized comes back [ShieldType.GENERIC] so the caller can fall back to
 *  the plain bordered chip. */
fun parseRouteRef(label: String): RouteRef {
    val t = label.trim()
    val dir = Regex("""\s([NSEW])$""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.uppercase()
    val core = (if (dir != null) t.dropLast(2) else t).trim()
    val number = Regex("""(\d+)""").find(core)?.value ?: ""
    val prefix = core.takeWhile { !it.isDigit() }.filter { it.isLetter() }.uppercase()
    val type = when {
        prefix == "I" -> ShieldType.INTERSTATE
        prefix == "US" || prefix == "USHWY" -> ShieldType.US_ROUTE
        prefix == "SR" || prefix == "HWY" || prefix in STATE_PROVINCE -> ShieldType.STATE
        else -> ShieldType.GENERIC
    }
    return RouteRef(type, number, dir, t)
}

/** Words a road name is made of when it only says its route number again ("State Route 9"). */
private val ROUTE_WORDS: Set<String> = setOf(
    "state", "route", "rte", "rt", "highway", "hwy", "sr", "sh", "us", "interstate", "county", "road", "cr",
    "trunk", "provincial", "national", "nationale", "departementale", "bundesstrasse", "bundesstraße", "autobahn",
    "autoroute", "carretera", "strada", "statale", "rodovia", "north", "south", "east", "west", "n", "s", "e", "w",
)

/** True when [name] is the road's number in words: one number, the one in [ref], and nothing
 *  else but route words. "9th Street" on SR 9 has a name of its own. */
internal fun restatesRef(name: String, ref: String): Boolean {
    val tokens = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    val numbers = tokens.filter { t -> t.any { it.isDigit() } }
    if (numbers.size != 1) return false
    val refNumber = Regex("""\d+\p{L}?""").find(ref.lowercase().replace(" ", ""))?.value ?: return false
    return numbers.single() == refNumber && tokens.all { it == refNumber || it in ROUTE_WORDS }
}

/**
 * What to call the road being driven, from its [name] and its route number [ref]. The name when
 * the road has one of its own: that is what its street signs say, and what Google's road label
 * shows on a numbered road through a town. The number on an Interstate, which is not known by a
 * name, and where the name only says the number again.
 */
fun roadLabel(name: String?, ref: String?): String? {
    val n = name?.trim()?.takeIf { it.isNotEmpty() }
    val r = ref?.trim()?.takeIf { it.isNotEmpty() }
    if (n == null) return r
    if (r == null) return n
    if (parseRouteRef(r).type == ShieldType.INTERSTATE) return r
    return if (restatesRef(n, r)) r else n
}
