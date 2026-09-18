package app.vela.core.util

/**
 * Place-name comparison for "is this the same business".
 *
 * The tap resolve needs it because a brand's listings on one lot all agree loosely by name - the
 * store, its fuel station, its pharmacy, the coffee counter inside it - so "which listing did the
 * user actually tap" cannot be answered by distance alone. The same rule the places bake uses for
 * its snap key: lowercase, punctuation out, a trailing store number dropped, so "SHOP", "Shop
 * #1561" and "SHOP STORE 1561" are one name while "Shop Fuel Station" is another.
 */
object PlaceNames {
    private val PUNCT = Regex("[^\\p{L}\\p{N} ]")
    private val SPACES = Regex("\\s+")
    private val TRAILING_NUMBER = Regex(" (no|num|store|unit)? ?\\d{1,6}$")

    fun normalized(name: String?): String =
        (name ?: "")
            .lowercase()
            .replace(PUNCT, " ")
            .replace(SPACES, " ")
            .trim()
            .replace(TRAILING_NUMBER, "")
            .trim()

    /** True when two names identify the same business under [normalized]. */
    fun same(a: String?, b: String?): Boolean {
        val na = normalized(a)
        return na.isNotEmpty() && na == normalized(b)
    }
}
