package app.vela.core.data

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Parses an OSM `maxspeed` tag value into km/h - the "Speed B" online source reads these raw strings out
 * of the hosted speed-limit PMTiles overlay (built by `scripts/build-maxspeed-region.sh`), unlike the
 * offline obf path, which reads the way's own maxspeed off the route data.
 *
 * OSM maxspeed is messy: a bare number is km/h ("50"), an explicit unit may follow ("30 mph", "50 km/h"),
 * and a lot of non-numeric forms exist. We deliberately return **null** (unknown) rather than guess for:
 *  - `none` (a derestricted autobahn) - a real number is not known, and a fake one is worse than a blank;
 *  - implicit country codes (`DE:urban`, `GB:nsl_single`, `RO:motorway`) - resolving those needs a
 *    per-country default table we don't ship;
 *  - `signals` / `variable` / `unknown` and anything else without a leading number.
 * Same spirit as the offline path blanking on the ambiguous 150 km/h cap.
 *
 * `maxspeed:conditional` is read for its time rules only ([conditionalKmh]).
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

    /** [fromTags] with a `maxspeed:conditional` applied at local time [at]: the conditional value
     *  when one of its time rules holds, else the plain limit. A road with only a conditional limit
     *  and no rule in force has no known limit. */
    fun fromTags(maxspeed: String?, forward: String?, backward: String?, conditional: String?, at: LocalDateTime): Double? {
        val rule = ruleInForce(conditional, at) ?: return fromTags(maxspeed, forward, backward)
        // A rule in force whose value is not a number ("none @ (19:00-06:00)") means no known
        // limit now: the plain limit is the one thing known not to apply.
        return rule.kmh
    }

    private class Rule(val kmh: Double?)

    /**
     * The km/h a `maxspeed:conditional` value sets at local time [at], or null when no rule holds.
     * Rules are `<value> @ <condition>` separated by `;`, and the last one that holds wins.
     *
     * Only time conditions are read: hour ranges ("19:00-06:00", past midnight included, several
     * joined by commas), optionally after weekdays ("Mo-Fr", "Sa,Su"). Anything else is never in
     * force: weather ("wet", "snow"), weight, date ranges, and a rule with a quoted comment, which
     * the Netherlands uses for "100 at busy times", a limit the overhead signs set, not the clock.
     * Skipping a rule shows the plain limit, which is what the sign at the roadside says.
     */
    fun conditionalKmh(raw: String?, at: LocalDateTime): Double? = ruleInForce(raw, at)?.kmh

    /** The last rule of [raw] whose time condition holds at [at], or null when none does. */
    private fun ruleInForce(raw: String?, at: LocalDateTime): Rule? {
        if (raw.isNullOrBlank()) return null
        var result: Rule? = null
        for (rule in splitRules(raw)) {
            val atSign = rule.indexOf('@')
            if (atSign < 0) continue
            var cond = rule.substring(atSign + 1).trim()
            if (cond.startsWith("(") && cond.endsWith(")")) cond = cond.substring(1, cond.length - 1).trim()
            if (timeHolds(cond, at)) result = Rule(parseKmh(rule.substring(0, atSign)))
        }
        return result
    }

    /** Split on `;` outside parentheses and quotes. */
    private fun splitRules(raw: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quoted = false
        val cur = StringBuilder()
        for (c in raw) {
            when {
                c == '"' -> { quoted = !quoted; cur.append(c) }
                quoted -> cur.append(c)
                c == '(' -> { depth++; cur.append(c) }
                c == ')' -> { depth--; cur.append(c) }
                c == ';' && depth <= 0 -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    private val DAYS = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
    private val DAY_PART = Regex("""^([A-Za-z]{2})(?:-([A-Za-z]{2}))?$""")
    private val HOURS = Regex("""^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})$""")

    /** True when [cond] is "[days] hh:mm-hh:mm[,hh:mm-hh:mm…]" and [at] falls inside it. A range
     *  past midnight belongs to the day it starts on. Any other condition is false. */
    private fun timeHolds(cond: String, at: LocalDateTime): Boolean {
        if (cond.isEmpty() || '"' in cond) return false
        val firstDigit = cond.indexOfFirst { it.isDigit() }
        if (firstDigit < 0) return false
        val daysPart = cond.substring(0, firstDigit).trim()
        val days: Set<DayOfWeek>? = if (daysPart.isEmpty()) null else parseDays(daysPart) ?: return false
        val minute = at.hour * 60 + at.minute
        val today = at.dayOfWeek
        val yesterday = today.minus(1)
        // Read the whole condition before testing any of it: a tail that cannot be read
        // ("...,14:30-16:00; PH off; SH off") makes the rule one that is never in force, at every
        // hour, not only when the clock is past its first range.
        val ranges = ArrayList<IntArray>()
        for (part in cond.substring(firstDigit).split(',')) {
            val m = HOURS.matchEntire(part.trim()) ?: return false
            val (h1, m1, h2, m2) = m.destructured
            if (m1.toInt() > 59 || m2.toInt() > 59) return false
            val start = h1.toInt() * 60 + m1.toInt()
            val end = h2.toInt() * 60 + m2.toInt()
            if (start > 24 * 60 || end > 24 * 60) return false
            ranges += intArrayOf(start, end)
        }
        for ((start, end) in ranges) {
            val holds = if (start <= end) {
                minute in start until end && (days == null || today in days)
            } else {
                (minute >= start && (days == null || today in days)) ||
                    (minute < end && (days == null || yesterday in days))
            }
            if (holds) return true
        }
        return false
    }

    /** "Mo-Fr", "Sa,Su", "Mo-We,Fr" to a set of days, or null when anything else is in it. */
    private fun parseDays(s: String): Set<DayOfWeek>? {
        val out = HashSet<DayOfWeek>()
        for (part in s.split(',')) {
            val m = DAY_PART.matchEntire(part.trim()) ?: return null
            val a = DAYS.indexOf(m.groupValues[1].lowercase()).takeIf { it >= 0 } ?: return null
            val b = if (m.groupValues[2].isEmpty()) a else DAYS.indexOf(m.groupValues[2].lowercase()).takeIf { it >= 0 } ?: return null
            var i = a
            while (true) {
                out += DayOfWeek.of(i + 1)
                if (i == b) break
                i = (i + 1) % 7
            }
        }
        return out
    }
}
