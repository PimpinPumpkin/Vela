package app.vela.core.i18n

import kotlin.math.abs

/**
 * "5 min late" / "2 min early" for a transit stop, in the app's language. The parsers that
 * compute a delay live in :core, which has no string resources, so the app sets [formatter]
 * at start (minutes late, negative = early) and the English form stands in for unit tests.
 */
object DelayText {
    @Volatile var formatter: ((Int) -> String?)? = null

    fun of(minutes: Int): String? {
        if (minutes == 0) return null
        formatter?.let { f -> runCatching { return f(minutes) } }
        return if (minutes > 0) "$minutes min late" else "${abs(minutes)} min early"
    }
}
