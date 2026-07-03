package app.vela.core.i18n

import java.util.Locale

/**
 * All Vela-GENERATED spoken/banner nav text for ONE language. Vela builds turn instructions itself
 * (from OSRM step geometry) rather than scraping them, so localizing navigation = translating this
 * small, bounded set of templates — NOT machine-translating prose. The road/dest NAME passed in is
 * DATA (already in the local language) and is never translated; each method decides the word ORDER
 * around it (which differs by language — "Turn left onto X" vs "Tournez à gauche sur X"), which is why
 * this is per-language templates, not per-word substitution.
 *
 * Resolved by [NavStringsRegistry] (set explicitly from the app locale, never `Locale.getDefault()`,
 * because these run off the main thread — the nav loop + the TTS worker). Part of the app localization
 * effort (see the `project_vela_i18n` memory note).
 */
interface NavStrings {
    val locale: Locale

    /**
     * The full instruction for an OSRM maneuver — mirrors `RouteGeometry.osrmPhrase`. [type] is the
     * OSRM maneuver type ("turn", "off ramp", "roundabout", …, language-independent); [mod] is the OSRM
     * modifier token ("left", "slight right", "straight", …, language-independent — each language maps
     * it); [road] is the road being entered; [dest] a ramp's sign destination; [exitNo] a ramp exit
     * number; [rbExit] a roundabout exit count.
     */
    fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String
}

/** English (source of truth) — byte-identical to the original `osrmPhrase`, so existing nav tests pass. */
object EnNavStrings : NavStrings {
    override val locale: Locale = Locale.US

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val onto = if (road != null) " onto $road" else ""
        val m = (mod ?: "").trim()
        val toward = when {
            dest != null -> " toward $dest"
            road != null -> " onto $road"
            else -> ""
        }
        val exitTab = if (exitNo != null) " $exitNo" else ""
        return when (type) {
            "depart" -> if (road != null) "Head out on $road" else "Start your route"
            "arrive" -> "Arrive at your destination"
            "turn", "end of road" -> ("Turn $m").trim() + onto
            "continue", "new name" -> if (m.isNotBlank() && m != "straight") ("Bear $m").trim() + onto else "Continue$onto"
            "merge" -> "Merge$toward"
            "on ramp", "ramp" -> "Take the ramp$toward"
            "off ramp" -> if (exitNo != null) "Take exit$exitTab$toward" else "Take the exit$toward"
            "fork" -> ("Keep $m").trim() + toward
            "roundabout", "rotary" -> if (rbExit != null) "At the roundabout, take exit $rbExit$onto" else "Enter the roundabout$onto"
            "roundabout turn" -> ("At the roundabout, turn $m").trim() + onto
            "uturn" -> "Make a U-turn$onto"
            else -> if (m.isNotBlank()) ("Turn $m").trim() + onto else "Continue$onto"
        }
    }
}

/**
 * French — the first non-English NavStrings, proving the per-language-template design (note the word
 * order: "à gauche **sur** X", the modifier folded into the verb phrase, roundabout ordinals "2e
 * sortie"). Road/dest names are passed through untranslated.
 */
object FrNavStrings : NavStrings {
    override val locale: Locale = Locale.FRANCE

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "à gauche"
        "right" -> "à droite"
        "slight left" -> "légèrement à gauche"
        "slight right" -> "légèrement à droite"
        "sharp left" -> "franchement à gauche"
        "sharp right" -> "franchement à droite"
        "straight" -> "tout droit"
        "uturn" -> "demi-tour"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val sur = if (road != null) " sur $road" else ""
        val vers = when {
            dest != null -> " vers $dest"
            road != null -> " sur $road"
            else -> ""
        }
        val m = modWord(mod)
        return when (type) {
            "depart" -> if (road != null) "Prenez $road" else "Démarrez votre itinéraire"
            "arrive" -> "Vous êtes arrivé à destination"
            "turn", "end of road" -> ("Tournez $m").trim() + sur
            "continue", "new name" -> if (m.isNotBlank() && m != "tout droit") ("Serrez $m").trim() + sur else "Continuez$sur"
            "merge" -> "Insérez-vous$vers"
            "on ramp", "ramp" -> "Prenez la bretelle$vers"
            "off ramp" -> if (exitNo != null) "Prenez la sortie $exitNo$vers" else "Prenez la sortie$vers"
            "fork" -> ("Restez $m").trim() + vers
            "roundabout", "rotary" -> if (rbExit != null) "Au rond-point, prenez la ${rbExit}e sortie$sur" else "Engagez-vous sur le rond-point$sur"
            "roundabout turn" -> ("Au rond-point, tournez $m").trim() + sur
            "uturn" -> "Faites demi-tour$sur"
            else -> if (m.isNotBlank()) ("Tournez $m").trim() + sur else "Continuez$sur"
        }
    }
}

/**
 * Holds the active [NavStrings] for the process. Set explicitly from the resolved app locale on startup
 * and on every language change — do NOT read `Locale.getDefault()` at the leaf, because nav/TTS text is
 * assembled off the main thread. Defaults to [EnNavStrings] so nothing (and no test) depends on the
 * device locale until a language is chosen.
 */
object NavStringsRegistry {
    @Volatile
    private var active: NavStrings = EnNavStrings

    fun current(): NavStrings = active

    fun setLocale(locale: Locale) { active = forLanguage(locale.language) }

    /** The NavStrings for a language code ("fr", "en", …); English for anything not yet translated. */
    fun forLanguage(language: String): NavStrings = when (language.lowercase()) {
        "fr" -> FrNavStrings
        else -> EnNavStrings
    }
}
