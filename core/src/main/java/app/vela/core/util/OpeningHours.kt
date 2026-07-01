package app.vela.core.util

import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Compute **open/closed right now** from the human hours strings Google gives us ("Monday: 6 AM–1 AM"),
 * because Google's live status *string* is unreliable for some places — most visibly ones that close
 * **past midnight** (a store open till 1 AM read as "Closed" at 11 PM) — while the weekly hours list is
 * the source of truth. Handles AM/PM, minutes, "Open 24 hours", "Closed", multiple ranges per day, an
 * implicit meridian ("5–10 PM"), and intervals that run past midnight (close ≤ open → into the next day).
 *
 * Returns `null` when it can't parse confidently, so the caller falls back to Google's status string.
 * Times are minutes-from-midnight; a close that runs into the next day is stored as `close + 1440`.
 */
object OpeningHours {

    /** [open] now? [detail] is the next transition — "Closes 1 AM" (open) or "Opens 6 AM" (closed). */
    data class Status(val open: Boolean, val detail: String)

    fun statusAt(hours: List<String>, now: LocalDateTime): Status? {
        val byDay = parse(hours)?.takeIf { it.isNotEmpty() } ?: return null
        val today = now.dayOfWeek
        val nowMin = now.hour * 60 + now.minute

        // Open if a today-interval contains now, OR a yesterday-interval spills past midnight over now.
        val todayIv = byDay[today].orEmpty()
        val yestIv = byDay[today.minus(1)].orEmpty()
        val hitToday = todayIv.firstOrNull { nowMin >= it.first && nowMin < it.second }
        val hitYest = yestIv.firstOrNull { it.second > DAY && nowMin < it.second - DAY }
        if (hitToday != null || hitYest != null) {
            val closeMin = (hitToday?.second ?: hitYest!!.second) % DAY
            return Status(true, "Closes ${fmt(closeMin)}")
        }

        // Closed → the next opening: later today, else the first opening on a following day.
        todayIv.filter { it.first > nowMin }.minByOrNull { it.first }
            ?.let { return Status(false, "Opens ${fmt(it.first)}") }
        for (d in 1..7) {
            val day = today.plus(d.toLong())
            val iv = byDay[day].orEmpty().minByOrNull { it.first } ?: continue
            val dayName = day.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
            return Status(false, "Opens ${fmt(iv.first)} ${if (d == 1) "tomorrow" else dayName}")
        }
        return Status(false, "Closed")
    }

    private const val DAY = 1440

    /** "Monday: 6 AM–1 AM" lines → DayOfWeek → intervals (minutes; a past-midnight close is `close+1440`).
     *  Returns null if any day's spec is present but unparseable (→ caller falls back to Google's status). */
    private fun parse(hours: List<String>): Map<DayOfWeek, List<Pair<Int, Int>>>? {
        val map = HashMap<DayOfWeek, List<Pair<Int, Int>>>()
        for (line in hours) {
            val i = line.indexOf(": ")
            if (i < 0) continue
            val day = dayOf(line.substring(0, i).trim()) ?: continue
            val ivs = intervals(line.substring(i + 2).trim()) ?: return null
            map[day] = ivs
        }
        return map
    }

    private fun intervals(specRaw: String): List<Pair<Int, Int>>? {
        val spec = specRaw.trim()
        if (spec.isBlank()) return null
        val low = spec.lowercase()
        if (low.startsWith("closed")) return emptyList()
        if (low.contains("24 hour") || low == "open 24 hours" || low == "open 24 hours a day") return listOf(0 to DAY)
        return spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { range ->
            val parts = range.split("–", "—", "-", " to ").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            // implicit meridian: "5–10 PM" → the 5 inherits PM from the 10.
            val endMer = meridian(parts[1])
            val open = parseTime(parts[0], inheritMeridian = if (hasMeridian(parts[0])) null else endMer) ?: return null
            var close = parseTime(parts[1], null) ?: return null
            if (close <= open) close += DAY // past midnight
            open to close
        }
    }

    private fun hasMeridian(s: String) = s.lowercase().let { it.contains("am") || it.contains("pm") }
    private fun meridian(s: String): String? = s.lowercase().let { if (it.contains("pm")) "pm" else if (it.contains("am")) "am" else null }

    /** "6 AM" / "6:30 PM" / "12 PM"(noon) / "12 AM"(midnight) → minutes from midnight. */
    private fun parseTime(sRaw: String, inheritMeridian: String?): Int? {
        val s = sRaw.trim().lowercase().replace(".", "")
        val mer = when {
            s.contains("pm") -> "pm"
            s.contains("am") -> "am"
            else -> inheritMeridian
        } ?: return null
        val digits = s.replace("am", "").replace("pm", "").trim()
        val (h, m) = if (digits.contains(":")) {
            val p = digits.split(":"); (p[0].toIntOrNull() ?: return null) to (p.getOrNull(1)?.toIntOrNull() ?: return null)
        } else {
            (digits.toIntOrNull() ?: return null) to 0
        }
        if (h < 1 || h > 12 || m < 0 || m > 59) return null
        val h24 = when {
            mer == "am" && h == 12 -> 0        // 12 AM = midnight
            mer == "am" -> h
            mer == "pm" && h == 12 -> 12       // 12 PM = noon
            else -> h + 12                     // pm
        }
        return h24 * 60 + m
    }

    private fun fmt(min: Int): String {
        val h = min / 60
        val m = min % 60
        val mer = if (h < 12) "AM" else "PM"
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return if (m == 0) "$h12 $mer" else "$h12:${m.toString().padStart(2, '0')} $mer"
    }

    private fun dayOf(nameRaw: String): DayOfWeek? = when (nameRaw.trim().lowercase().take(3)) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY
        else -> null
    }
}
