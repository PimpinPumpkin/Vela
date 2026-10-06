package app.vela.core.data

/** The 12/24-hour clock in use (the app's own setting, else the device's), pushed in by the app
 *  (a plain holder, like [LowDataMode]): :core has no Context, and the transit code formats times. */
object ClockFormat {
    @Volatile var use24h: Boolean = false

    // Google's transit pages are fetched in English and carry their times as text ("4:35 PM",
    // sometimes with a no-break or narrow no-break space before the marker).
    private val AMPM = Regex("""^(\d{1,2}):(\d{2})[\s\u00A0\u202F]?([AaPp])[Mm]$""")

    /** The moment [epochSec] in [zone] as the clock in use shows it ("4:35 PM" or "16:35"). The
     *  transit payload carries every time as a number beside its text, so the text's language
     *  (and Google's own 12/24-hour choice for it) never has to be read. */
    fun at(epochSec: Long, zone: java.time.ZoneId): String =
        java.time.format.DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a", java.util.Locale.US)
            .format(java.time.Instant.ofEpochSecond(epochSec).atZone(zone))

    /** [text] as the clock in use shows it: "4:35 PM" becomes "16:35" under a 24-hour clock.
     *  Anything that is not a plain 12-hour time is returned unchanged. */
    fun show(text: String?): String? {
        if (text == null || !use24h) return text
        val m = AMPM.find(text.trim()) ?: return text
        val h12 = m.groupValues[1].toInt()
        if (h12 !in 1..12) return text
        val pm = m.groupValues[3].equals("p", ignoreCase = true)
        val h = (h12 % 12) + if (pm) 12 else 0
        return "%02d:%s".format(h, m.groupValues[2])
    }
}
