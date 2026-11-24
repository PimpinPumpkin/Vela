package app.vela.core.data

import java.time.format.DateTimeFormatter
import java.util.Locale

/** The 12/24-hour clock in use (the app's own setting, else the device's) and the language times
 *  are written in, pushed in by the app (a plain holder, like [LowDataMode]): :core has no
 *  Context, and the transit code formats times. */
object ClockFormat {
    @Volatile var use24h: Boolean = false

    /** The app's language, for the day period's words and place ("下午7:30"). US English until
     *  the app sets it. */
    @Volatile var locale: Locale = Locale.US

    // Google's transit pages are fetched in English and carry their times as text ("4:35 PM",
    // sometimes with a no-break or narrow no-break space before the marker).
    private val AMPM = Regex("""^(\d{1,2}):(\d{2})[\s  ]?([AaPp])[Mm]$""")

    /**
     * [locale]'s pattern for a time of day on the chosen clock, whatever clock the language
     * prefers: "h:mm a" in English, "ah:mm" in Chinese, "H.mm" in Finnish at 24 hours. [date] is a
     * date skeleton to go with it ("MMMd", "yMMMd"), or empty for the time alone. Android picks the
     * pattern (`getBestDateTimePattern`). Where it cannot (a unit test), the language's short time
     * stands when it already has the chosen clock, else "HH:mm" or "h:mm a", after "MMM d, " for a
     * date. java.time before Android 14 has no flexible day period ("B", "in the evening"), so the
     * plain AM/PM stands in.
     */
    fun pattern(use24h: Boolean = this.use24h, locale: Locale = this.locale, date: String = ""): String {
        val skeleton = date + if (use24h) "Hm" else "hm"
        val best = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull()
        if (best != null) return plainDayPeriod(best)
        val time = runCatching {
            java.time.format.DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                null, java.time.format.FormatStyle.SHORT, java.time.chrono.IsoChronology.INSTANCE, locale,
            )
        }.getOrNull()?.takeIf { lp -> lp.any { it in (if (use24h) "Hk" else "hK") } }?.let(::plainDayPeriod)
            ?: if (use24h) "HH:mm" else "h:mm a"
        return when {
            date.isEmpty() -> time
            'y' in date -> "MMM d, y, $time"
            else -> "MMM d, $time"
        }
    }

    /** A formatter for [pattern] in [locale]. */
    fun formatter(use24h: Boolean = this.use24h, locale: Locale = this.locale, date: String = ""): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern(use24h, locale, date), locale)

    /** [pattern] with each day period outside quotes made one plain AM/PM ("a"): a flexible one
     *  ("B", "b") or a long one ("aaaa"), which java.time before Android 14 rejects. */
    fun plainDayPeriod(pattern: String): String {
        val sb = StringBuilder(pattern.length)
        var quoted = false
        var prev = ' '
        for (c in pattern) {
            if (c == '\'') quoted = !quoted
            val period = !quoted && (c == 'a' || c == 'B' || c == 'b')
            if (!(period && prev == 'a')) sb.append(if (period) 'a' else c)
            prev = if (period) 'a' else c
        }
        return sb.toString()
    }

    /** The moment [epochSec] in [zone] as the clock in use shows it ("4:35 PM" or "16:35"). The
     *  transit payload carries every time as a number beside its text, so the text's language
     *  (and Google's own 12/24-hour choice for it) never has to be read. */
    fun at(epochSec: Long, zone: java.time.ZoneId): String =
        formatter().format(java.time.Instant.ofEpochSecond(epochSec).atZone(zone))

    /** [text] as the clock in use shows it: Google's "4:35 PM" becomes "16:35" under a 24-hour
     *  clock and "下午4:35" in Chinese. Anything that is not a plain 12-hour time is returned
     *  unchanged. */
    fun show(text: String?): String? {
        if (text == null) return null
        val m = AMPM.find(text.trim()) ?: return text
        val h12 = m.groupValues[1].toInt()
        if (h12 !in 1..12) return text
        val pm = m.groupValues[3].equals("p", ignoreCase = true)
        val h = (h12 % 12) + if (pm) 12 else 0
        return java.time.LocalTime.of(h, m.groupValues[2].toInt()).format(formatter())
    }
}
