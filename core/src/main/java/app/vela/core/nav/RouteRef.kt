package app.vela.core.nav

import app.vela.core.model.bearingTo
import app.vela.core.model.distanceTo

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

/** A compass word on a guide sign, to the letter shown after a route number. */
private val CARDINALS: Map<String, String> = mapOf(
    "north" to "N", "south" to "S", "east" to "E", "west" to "W",
    "nord" to "N", "sud" to "S", "est" to "E", "ouest" to "O",
    "norte" to "N", "sur" to "S", "este" to "E", "oeste" to "O",
)

/** A name that says the road is a freeway: its last word in English, its first elsewhere. */
private val FREEWAY_LAST: Set<String> = setOf("freeway", "fwy", "motorway")
private val FREEWAY_FIRST: Set<String> = setOf("autoroute", "autostrada", "autopista", "autovia", "autovía", "autobahn")

private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
/** A route number and nothing else: "I 80", "US-50", "CA 113", "A7". */
private val BARE_REF = Regex("""\p{L}{1,3}[ -]?\d+\p{L}?""")
private fun words(text: String): List<String> = text.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }

/** True for "Capital City Freeway" and "Autoroute du Soleil". An expressway, a parkway or a
 *  turnpike is left out: many are known by the name alone ("Lawrence Expressway" is CR G2). */
internal fun isFreewayName(name: String): Boolean {
    val w = words(name)
    return w.size >= 2 && (w.last() in FREEWAY_LAST || w.first() in FREEWAY_FIRST)
}

/**
 * What to call the road being driven, from its [name] and its route number [ref]. The name when
 * the road has one of its own: that is what its street signs say, and what Google's road label
 * shows on a numbered road through a town. The number on an Interstate or a named freeway, which
 * are signed by number, and where the name only says the number again. A number is followed by
 * [heading], the compass letter a sign gave for this stretch ([signedHeading]), or by the one
 * the name itself ends in ("US Highway 50 East"). A name is never given a letter.
 */
fun roadLabel(name: String?, ref: String?, heading: String? = null): String? {
    val n = name?.trim()?.takeIf { it.isNotEmpty() }
    val given = ref?.trim()?.takeIf { it.isNotEmpty() }
    // A router can send "I 80 East" as the number, as the name, or as both.
    if (given == null) {
        val (route, letter) = splitHeading(n)
        return if (letter != null) "$route $letter" else n
    }
    val (route, own) = splitHeading(given)
    val r = route ?: return n
    val h = own ?: heading
    if (n == null || n == given || parseRouteRef(r).type == ShieldType.INTERSTATE || isFreewayName(n)) return numbered(r, h)
    return if (restatesRef(n, r)) numbered(r, h ?: CARDINALS[n.substringAfterLast(' ').lowercase()]) else n
}

/** "I 80 East" as the route "I 80" and the letter "E"; anything else unchanged, with no letter. */
private fun splitHeading(ref: String?): Pair<String?, String?> {
    if (ref == null) return null to null
    val letter = CARDINALS[ref.substringAfterLast(' ').lowercase()]
    val route = ref.substringBeforeLast(' ', "").trim()
    return if (letter != null && BARE_REF.matches(route)) route to letter else ref to null
}

private fun numbered(ref: String, heading: String?): String =
    if (heading == null || parseRouteRef(ref).direction != null) ref else "$ref $heading"

/** How far back from a step a sign is looked for, in steps. */
private const val SIGN_LOOKBACK = 8
/** A signed direction is dropped when the stretch runs more than this far from it, in degrees. */
private const val HEADING_MAX_OFF_DEG = 135.0
/** ...measured only on a stretch at least this long. */
private const val HEADING_CHECK_MIN_M = 300.0
/** Between two signed directions, the leg has to run within this of one, in degrees. */
private const val HEADING_PICK_DEG = 60.0

/** The compass letters [text] puts after route number [number]: "E" for 80 in "toward I 80
 *  East: Sacramento", both for "I 5 North, I 5 South". The word before the number must be a
 *  route prefix, so "exit 80 east" is not read as one. */
private fun headingsIn(text: String, number: String): Set<String> {
    val w = words(text)
    val found = LinkedHashSet<String>()
    for (i in 1 until w.size - 1) {
        if (w[i] != number) continue
        val prefix = w[i - 1]
        if (!(prefix in ROUTE_WORDS || (prefix.length <= 3 && prefix.all { it.isLetter() }))) continue
        CARDINALS[w[i + 1]]?.let { found += it }
    }
    return found
}

private fun compassDeg(letter: String): Double = when (letter) { "N" -> 0.0; "E" -> 90.0; "S" -> 180.0; else -> 270.0 }
private fun degOff(a: Double, b: Double): Double = kotlin.math.abs(((a - b + 540.0) % 360.0) - 180.0)

/**
 * The compass letter a guide sign gave for route [ref], entered by `maneuvers[k]`: read from that
 * step's text, else from the steps before it that are unnamed (the ramp, the fork) or already on
 * the route. Router sign text is the only source. The letter is never worked out from the map
 * alone, because a route signed east can run north for miles. A sign that names one direction
 * is taken unless the leg runs the opposite way. A sign that names both ("I 5 North, I 5 South")
 * is settled by the leg's own bearing, which has to be within [HEADING_PICK_DEG] of one of them.
 * Null with no sign, or when the leg is too short to tell.
 */
fun signedHeading(maneuvers: List<app.vela.core.model.Maneuver>, k: Int, ref: String): String? {
    val refWords = words(ref)
    val number = refWords.firstOrNull { w -> w.any { it.isDigit() } }
    if (number == null || k !in maneuvers.indices) return null
    var signed: Set<String> = emptySet()
    var j = k
    while (j >= 0 && k - j <= SIGN_LOOKBACK) {
        val m = maneuvers[j]
        val onRoute = m.ref?.let { words(it) == refWords } == true
        if (j < k && m.road != null && !onRoute) break
        signed = headingsIn(m.instruction, number)
        if (signed.isNotEmpty()) break
        j--
    }
    if (signed.isEmpty() || signed.size > 2) return null
    val from = maneuvers[k].location
    val to = maneuvers.getOrNull(k + 1)?.location
    val bearing = to?.takeIf { from.distanceTo(it) >= HEADING_CHECK_MIN_M }?.let { from.bearingTo(it) }
    if (signed.size == 1) {
        val letter = signed.first()
        return letter.takeIf { bearing == null || degOff(bearing, compassDeg(letter)) <= HEADING_MAX_OFF_DEG }
    }
    if (bearing == null) return null
    return signed.firstOrNull { degOff(bearing, compassDeg(it)) <= HEADING_PICK_DEG }
}

/**
 * The label of the road being driven: `maneuvers[stepIndex]` is the next maneuver and
 * [toNextM] the distance to it. The leg's road, following its silent renames. On an unnamed
 * stretch such as a ramp, with [orNext], the road the next maneuver enters; else null.
 */
fun roadLabelAt(maneuvers: List<app.vela.core.model.Maneuver>, stepIndex: Int, toNextM: Double, orNext: Boolean = false): String? {
    maneuvers.getOrNull(stepIndex - 1)?.let { m ->
        val (name, ref) = m.roadAt(m.distanceMeters - toNextM)
        roadLabel(name, ref, ref?.let { signedHeading(maneuvers, stepIndex - 1, it) })?.let { return it }
    }
    if (!orNext) return null
    return maneuvers.getOrNull(stepIndex)?.let { next -> roadLabel(next.road, next.ref, next.ref?.let { signedHeading(maneuvers, stepIndex, it) }) }
}
