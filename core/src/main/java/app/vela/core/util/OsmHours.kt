package app.vela.core.util

/**
 * OSM `opening_hours` (the syntax AllThePlaces and OpenStreetMap carry: "Mo-Fr 08:00-17:00; Sa
 * 09:00-13:00", "24/7", "Mo-Su 06:00-22:00; Su off") to the per-day lines Google gives us
 * ("Monday: 8 AM–5 PM"), which is what the place sheet's hours section and [OpeningHours.statusAt]
 * (open/closed right now) already understand.
 *
 * Covered (measured against every tagged business in one US state's place pack, 2026-09-22):
 * day ranges and lists, lists written with spaces ("Mo-Th, Su"), wrapping ranges ("Su-We"), one or
 * more time ranges per rule, rule groups separated by commas as well as semicolons, `off` and
 * `closed`, `24/7`, `00:00-24:00` and `00:00-00:00`, open-ended times ("17:00+"), overnight ranges,
 * later rules overriding earlier days, rules with no day part meaning every day, and sun events
 * (sunrise, sunset, dawn, dusk) shown as words. Public holidays (`PH`, `SH`) are dropped from day
 * lists and a rule that is ONLY about holidays or dates ("PH off", "Dec 25 off", "Nov Th[4] off")
 * is skipped, since the lines describe an ordinary week. Anything else returns null and [lines]
 * shows the text itself (unquoted, so "by appointment" reads as a note), so nothing is invented.
 */
object OsmHours {
    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private const val DAY = "(?:Mo|Tu|We|Th|Fr|Sa|Su|PH|SH)"
    private val MONTH = Regex("""^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|week|\d{4})\b""")
    private val SPACED_LIST = Regex("""($DAY(?:-$DAY)?)\s*,\s+(?=$DAY\b)""")
    // A new rule group after a comma: "..., Fr 09:00-18:00" / "...off, Sa ...". Only after a time,
    // `off` or `closed`, so the comma inside a day list ("Mo,We") never splits.
    private val GROUP_COMMA = Regex("""(?<=\d|\+|off|closed)\s*,\s*(?=$DAY\b)""")
    private val SUN = Regex("""^\(?\s*(sunrise|sunset|dawn|dusk)\s*(?:[+-]\s*\d{1,2}:\d{2})?\s*\)?$""", RegexOption.IGNORE_CASE)
    private val GOOGLE_LINE = Regex("""^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday):""")

    /**
     * What the sheet shows for a raw hours value from any open source: per-day lines when the
     * syntax is understood, the lines themselves when they already ARE per-day lines (a saved
     * area stores converted lines), else the text as a single cleaned note.
     */
    fun lines(raw: String?): List<String> {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val split = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (split.isNotEmpty() && split.all { GOOGLE_LINE.containsMatchIn(it) }) return split
        toDayLines(s)?.let { return it }
        val note = s.replace('"', ' ').replace(Regex("\\s+"), " ").trim()
        return listOf(note.replaceFirstChar { it.uppercaseChar() })
    }

    fun toDayLines(spec: String): List<String>? {
        var s = spec.trim().replace('\n', ';')
        if (s.isEmpty()) return null
        // Normalize the two comma habits before splitting into rules.
        while (true) { val t = SPACED_LIST.replace(s, "$1,"); if (t == s) break; s = t }
        s = GROUP_COMMA.replace(s, ";")
        val byDay = arrayOfNulls<String>(7)
        var anyRule = false
        for (ruleRaw in s.split(';', '|')) {
            val rule = ruleRaw.trim().trim('|').trim()
            if (rule.isEmpty()) continue
            if (MONTH.containsMatchIn(rule)) continue // a date or month rule: not an ordinary week
            // "Mo-Fr 08:00-17:00" | "Sa,Su off" | "08:00-20:00" (every day) | "Mo-Su 24/7" | "PH off"
            val firstSpace = rule.indexOf(' ')
            val head = if (firstSpace < 0) rule else rule.substring(0, firstSpace)
            val days: List<Int>
            val timesPart: String
            if (head.length >= 2 && head[0].isUpperCase() && Regex("^$DAY").containsMatchIn(head)) {
                val parsed = parseDays(head) ?: return null
                if (parsed.isEmpty()) continue // holidays only
                days = parsed
                timesPart = if (firstSpace < 0) "" else rule.substring(firstSpace + 1).trim()
            } else {
                days = (0..6).toList()
                timesPart = rule
            }
            val text = times(timesPart) ?: return null
            for (d in days) byDay[d] = text
            anyRule = true
        }
        if (!anyRule || byDay.all { it == null }) return null
        return NAMES.indices.map { "${NAMES[it]}: ${byDay[it] ?: "Closed"}" }
    }

    /** One rule's time part to the sheet's text; null when it is not understood. */
    private fun times(raw: String): String? {
        val t = raw.trim().trim('"').trim()
        return when {
            t.isEmpty() || t.equals("off", true) || t.equals("closed", true) -> "Closed"
            t == "24/7" || t == "00:00-24:00" || t == "00:00-00:00" || t == "00:00+" -> "Open 24 hours"
            else -> {
                val ranges = t.split(',').map { range(it) }
                if (ranges.any { it == null }) null else ranges.joinToString(", ")
            }
        }
    }

    /** "08:00-17:00" / "17:00+" (until midnight) / "07:00-dusk" / "sunrise-sunset" → "8 AM–5 PM". */
    private fun range(rRaw: String): String? {
        val r = rRaw.trim()
        if (r.endsWith("+") && !r.contains('-')) return clock(r.dropLast(1))?.let { "$it–12 AM" }
        // Split on the dash BETWEEN the two ends, not one inside an offset "(sunset-00:30)".
        val cut = splitRange(r) ?: return null
        val a = end(cut.first) ?: return null
        val b = end(cut.second.removeSuffix("+")) ?: return null
        return "$a–$b"
    }

    private fun splitRange(r: String): Pair<String, String>? {
        var depth = 0
        for (i in r.indices) {
            when (r[i]) {
                '(' -> depth++
                ')' -> depth--
                '-' -> if (depth == 0 && i > 0) return r.substring(0, i) to r.substring(i + 1)
            }
        }
        return null
    }

    private fun end(e: String): String? {
        val x = e.trim().trim('"').trim()
        clock(x)?.let { return it }
        return SUN.find(x)?.groupValues?.get(1)?.lowercase()?.replaceFirstChar { it.uppercaseChar() }
    }

    /** "Mo-Fr", "Sa,Su", "Mo-We,Fr", "Su-We" → day indices; `PH`/`SH` dropped; null on anything else. */
    private fun parseDays(head: String): List<Int>? {
        val out = LinkedHashSet<Int>()
        for (part in head.split(',')) {
            val p = part.trim()
            if (p == "PH" || p == "SH" || p.isEmpty()) continue
            val ends = p.split('-')
            if (ends.size == 1) {
                out += DAYS.indexOf(ends[0].take(2)).takeIf { it >= 0 && ends[0].length == 2 } ?: return null
            } else if (ends.size == 2) {
                val a = DAYS.indexOf(ends[0]).takeIf { it >= 0 } ?: return null
                val b = DAYS.indexOf(ends[1]).takeIf { it >= 0 } ?: return null
                var i = a
                while (true) { out += i; if (i == b) break; i = (i + 1) % 7 }
            } else return null
        }
        return out.toList()
    }

    /** "08:00" → "8 AM", "17:30" → "5:30 PM", "24:00" → "12 AM", "7:00" → "7 AM"; null when it is not a clock time. */
    private fun clock(t: String): String? {
        val p = t.trim().split(':')
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..48 || m !in 0..59) return null
        val hh = h % 24
        val mer = if (hh < 12) "AM" else "PM"
        val h12 = when { hh == 0 -> 12; hh > 12 -> hh - 12; else -> hh }
        return if (m == 0) "$h12 $mer" else "$h12:${m.toString().padStart(2, '0')} $mer"
    }
}
