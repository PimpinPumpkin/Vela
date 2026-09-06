package app.vela.ui

import java.util.Locale

/**
 * The search query behind a category chip. The chips send a STABLE English query, not their
 * localized label, because Google's search understands English category words in any locale.
 * "Gas" is the one word that is not stable English: in the UK, Ireland, Australia, New Zealand,
 * India and South Africa it means the utility, and searching it there returned gas suppliers and
 * car parks (issue #338). So the fuel chip asks for what that region calls it.
 */
object CategoryQuery {
    /** Regions whose English says "petrol"; the query and the en-GB label agree on it. */
    private val PETROL_REGIONS = setOf("GB", "IE", "AU", "NZ", "IN", "ZA", "SG", "MY", "PK", "KE", "NG")

    fun fuel(locale: Locale = AppLocale.effective()): String =
        if (locale.country.uppercase(Locale.ROOT) in PETROL_REGIONS) "Petrol station" else "Gas station"
}
