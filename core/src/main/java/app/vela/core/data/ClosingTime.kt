package app.vela.core.data

/**
 * Pulls today's CLOSING time out of Google's localized status text ("Open ⋅ Closes 9 PM",
 * "Ouvert ⋅ Ferme à 19:00", "Open ⋅ Closes soon ⋅ 7:30 PM") so navigation can warn when a
 * drive lands right at, or after, the destination's closing time. Text-based on purpose:
 * the status line is the one field that reliably carries the day's closing time in every
 * language the app scrapes, and holiday hours already flow into it.
 *
 * Only an OPEN place parses (the [closingMinuteOfDay] openNow gate): a closed place's status
 * times are OPENING times ("Closed ⋅ Opens 9 AM") and must not be mistaken for a closing.
 */
object ClosingTime {
    // "9 PM", "7:30 pm", "12 a.m." — the 12-hour shapes Google writes in English-style locales.
    private val T12 = Regex("""(\d{1,2})(?::(\d{2}))?\s*([ap])\.?\s?m\.?""", RegexOption.IGNORE_CASE)

    // "19:00", "19h00", "19.30" — the 24-hour shapes in the other scraped locales.
    private val T24 = Regex("""(\d{1,2})[:hH.](\d{2})""")

    /**
     * Today's closing time as a minute-of-day, or null when there is nothing to parse (place
     * closed, "Open 24 hours", no status). A midnight closing ("12 AM" / "00:00") returns 1440,
     * i.e. tonight's midnight, so it stays LATER than any same-day arrival in plain arithmetic.
     */
    fun closingMinuteOfDay(statusText: String?, openNow: Boolean?): Int? {
        if (openNow != true) return null
        val s = statusText ?: return null
        // The LAST time token wins: an open status carries the closing time as its final time
        // ("Open ⋅ Closes 9 PM", "Closes soon ⋅ 9 PM"); "Open 24 hours" carries none.
        val m12 = T12.findAll(s).lastOrNull()
        if (m12 != null) {
            val h = m12.groupValues[1].toInt()
            val min = m12.groupValues[2].ifEmpty { "0" }.toInt()
            if (h !in 1..12 || min > 59) return null
            val pm = m12.groupValues[3].equals("p", ignoreCase = true)
            val h24 = when {
                pm && h < 12 -> h + 12
                !pm && h == 12 -> 24 // "12 AM" as a closing means midnight TONIGHT
                else -> h
            }
            return h24 * 60 + min
        }
        val m24 = T24.findAll(s).lastOrNull() ?: return null
        val h = m24.groupValues[1].toInt()
        val min = m24.groupValues[2].toInt()
        if (h > 24 || min > 59) return null
        return (if (h == 0) 24 else h) * 60 + min // "00:00" closing is midnight tonight too
    }
}
