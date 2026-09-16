package app.vela.core.util

/**
 * OSM `opening_hours` (the syntax AllThePlaces and OpenStreetMap carry: "Mo-Fr 08:00-17:00; Sa
 * 09:00-13:00", "24/7", "Mo-Su 06:00-22:00; Su off") to the per-day lines Google gives us
 * ("Monday: 8 AM–5 PM"), which is what the place sheet's hours section and [OpeningHours.statusAt]
 * (open/closed right now) already understand. Only the common subset: day ranges and lists,
 * one or more time ranges per rule, `off`/`closed`, `24/7`, `00:00-24:00`, later rules
 * overriding earlier days, and rules with no day part meaning every day. Anything else (months,
 * week numbers, public holidays, "sunrise", comments) returns null, and the caller shows the raw
 * string instead so nothing is invented.
 */
object OsmHours {
    private val DAYS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    fun toDayLines(spec: String): List<String>? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        if (s == "24/7") return NAMES.map { "$it: Open 24 hours" }
        val byDay = arrayOfNulls<String>(7)
        for (ruleRaw in s.split(';', '|')) {
            val rule = ruleRaw.trim()
            if (rule.isEmpty()) continue
            // "Mo-Fr 08:00-17:00" | "Sa,Su off" | "08:00-20:00" (every day) | "Mo-Su 24/7"
            val firstSpace = rule.indexOf(' ')
            val head = if (firstSpace < 0) rule else rule.substring(0, firstSpace)
            val days: List<Int>
            val timesPart: String
            if (head.isNotEmpty() && head[0].isUpperCase() && head.substring(0, 2) in DAYS) {
                days = parseDays(head) ?: return null
                timesPart = if (firstSpace < 0) "" else rule.substring(firstSpace + 1).trim()
            } else {
                days = (0..6).toList()
                timesPart = rule
            }
            val text = when {
                timesPart.isEmpty() || timesPart.equals("off", true) || timesPart.equals("closed", true) -> "Closed"
                timesPart == "24/7" || timesPart == "00:00-24:00" -> "Open 24 hours"
                else -> {
                    val ranges = timesPart.split(',').map { range ->
                        val ends = range.trim().split('-')
                        if (ends.size != 2) null else {
                            val a = clock(ends[0]); val b = clock(ends[1])
                            if (a == null || b == null) null else "$a–$b"
                        }
                    }
                    if (ranges.any { it == null }) return null
                    ranges.joinToString(", ")
                }
            }
            for (d in days) byDay[d] = text
        }
        if (byDay.all { it == null }) return null
        return NAMES.indices.map { "${NAMES[it]}: ${byDay[it] ?: "Closed"}" }
    }

    /** "Mo-Fr", "Sa,Su", "Mo-We,Fr" → day indices; null on anything else (PH, months, weeks). */
    private fun parseDays(head: String): List<Int>? {
        val out = LinkedHashSet<Int>()
        for (part in head.split(',')) {
            val ends = part.split('-')
            if (ends.size == 1) {
                out += DAYS.indexOf(ends[0]).takeIf { it >= 0 } ?: return null
            } else if (ends.size == 2) {
                val a = DAYS.indexOf(ends[0]).takeIf { it >= 0 } ?: return null
                val b = DAYS.indexOf(ends[1]).takeIf { it >= 0 } ?: return null
                var i = a
                while (true) { out += i; if (i == b) break; i = (i + 1) % 7 }
            } else return null
        }
        return out.toList()
    }

    /** "08:00" → "8 AM", "17:30" → "5:30 PM", "24:00" → "12 AM"; null when it is not a clock time. */
    private fun clock(t: String): String? {
        val p = t.trim().split(':')
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        if (h !in 0..24 || m !in 0..59) return null
        val hh = h % 24
        val mer = if (hh < 12) "AM" else "PM"
        val h12 = when { hh == 0 -> 12; hh > 12 -> hh - 12; else -> hh }
        return if (m == 0) "$h12 $mer" else "$h12:${m.toString().padStart(2, '0')} $mer"
    }
}
