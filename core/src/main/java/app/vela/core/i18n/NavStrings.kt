package app.vela.core.i18n

import app.vela.core.model.LaneSide
import app.vela.core.voice.SpeechText
import java.util.Locale
import kotlin.math.roundToInt

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

    /** A distance phrased for SPEECH, honouring the imperial/metric preference — "500 feet" / "150 mètres". */
    fun spokenDistance(meters: Double, imperial: Boolean): String

    /** The pre-turn frame combining a distance phrase and the instruction — EN "In X, Y" / FR "Dans X, Y". */
    fun inThen(distancePhrase: String, instruction: String): String

    /** The at-arrival spoken callout — EN "You have arrived". */
    fun arrived(): String

    /** Spoken when navigation begins — EN "Starting navigation. <first instruction>". */
    fun startNav(firstInstruction: String): String

    /** Spoken as each intermediate stop is passed — EN "You've reached <label>" (blank → "your stop"). */
    fun reachedStop(label: String): String

    /** Spoken when auto-switching to a faster route — EN "Taking the faster route. <first instruction>". */
    fun fasterRoute(firstInstruction: String): String

    /** The "Test voice" sample — a short nav-style phrase to hear the selected voice. */
    fun voiceTest(): String

    /** A spoken lane recommendation — EN "Use the right 2 lanes" / "Use the left lane". */
    fun useLanes(side: LaneSide, count: Int): String

    /**
     * Expand road abbreviations + numbers so the TTS engine SAYS them ("St"→"Street", "128th"→"one
     * twenty-eighth"). English-specific, so it's **opt-in**: the default is identity, and ONLY
     * [EnNavStrings] overrides it. Other languages must leave the text — including road-name DATA —
     * untouched, so an English rule can never mangle a foreign name (a French "Rue"/"Bd" is read
     * natively by the French voice).
     */
    fun expandForSpeech(text: String): String = text
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

    // Feet under ~0.15 mi, else miles; metres under ~1 km, else kilometres (unchanged from NavEngine).
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(feet / 50).roundToInt() * 50} feet"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 mile" else "$miles miles"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} meters"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 kilometer" else "$km kilometers"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "In $distancePhrase, $instruction"

    override fun arrived(): String = "You have arrived"

    override fun startNav(firstInstruction: String): String = "Starting navigation. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "You've reached $label" else "You've reached your stop"

    override fun fasterRoute(firstInstruction: String): String = "Taking the faster route. $firstInstruction"

    override fun voiceTest(): String = "Voice guidance is on. In a quarter mile, turn right."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "left"; LaneSide.RIGHT -> "right"; LaneSide.CENTER -> "center" }
        return if (count > 1) "Use the $sideWord $count lanes" else "Use the $sideWord lane"
    }

    /** Whole-word road abbreviation → spoken form, "I-80"→"Interstate 80", and 3-digit street ordinals
     *  ("128th"→"one twenty-eighth"). Moved here from VoiceGuide.forSpeech so it's English-scoped. */
    override fun expandForSpeech(text: String): String {
        var s = text
        s = Regex("\\bI-(\\d+)").replace(s) { "Interstate ${it.groupValues[1]}" }
        s = Regex("\\bUS-(\\d+)").replace(s) { "US ${it.groupValues[1]}" }
        EN_SPEECH_WORDS.forEach { (re, rep) -> s = re.replace(s, rep) }
        s = SpeechText.spokenNumbers(s) // "128th" → "one twenty-eighth", not a mangled "one hundred and 28th"
        return s
    }
}

/** Whole-word road-abbreviation → spoken form, applied only by [EnNavStrings.expandForSpeech]. Road-type
 *  suffixes are case-insensitive; the directionals are uppercase (as they appear in road names) and come
 *  LAST so they can't chew into a word an earlier rule expanded. */
private val EN_SPEECH_WORDS: List<Pair<Regex, String>> = listOf(
    Regex("\\bSt\\b", RegexOption.IGNORE_CASE) to "Street",
    Regex("\\bAve\\b", RegexOption.IGNORE_CASE) to "Avenue",
    Regex("\\bBlvd\\b", RegexOption.IGNORE_CASE) to "Boulevard",
    Regex("\\bRd\\b", RegexOption.IGNORE_CASE) to "Road",
    Regex("\\bDr\\b", RegexOption.IGNORE_CASE) to "Drive",
    Regex("\\bLn\\b", RegexOption.IGNORE_CASE) to "Lane",
    Regex("\\bCt\\b", RegexOption.IGNORE_CASE) to "Court",
    Regex("\\bPkwy\\b", RegexOption.IGNORE_CASE) to "Parkway",
    Regex("\\bHwy\\b", RegexOption.IGNORE_CASE) to "Highway",
    Regex("\\bPl\\b", RegexOption.IGNORE_CASE) to "Place",
    Regex("\\bTer\\b", RegexOption.IGNORE_CASE) to "Terrace",
    Regex("\\bCir\\b", RegexOption.IGNORE_CASE) to "Circle",
    Regex("\\bSq\\b", RegexOption.IGNORE_CASE) to "Square",
    Regex("\\bTrl\\b", RegexOption.IGNORE_CASE) to "Trail",
    Regex("\\bExpy\\b", RegexOption.IGNORE_CASE) to "Expressway",
    Regex("\\bFwy\\b", RegexOption.IGNORE_CASE) to "Freeway",
    Regex("\\bNE\\b") to "Northeast",
    Regex("\\bNW\\b") to "Northwest",
    Regex("\\bSE\\b") to "Southeast",
    Regex("\\bSW\\b") to "Southwest",
    Regex("\\bN\\b") to "North",
    Regex("\\bS\\b") to "South",
    Regex("\\bE\\b") to "East",
    Regex("\\bW\\b") to "West",
)

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

    // France is metric; the imperial branch is kept for parity. French uses a decimal COMMA ("1,2 km").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(feet / 50).roundToInt() * 50} pieds"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 mile" else "${frNum(miles)} miles"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} mètres"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 kilomètre" else "${frNum(km)} kilomètres"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "Dans $distancePhrase, $instruction"

    override fun arrived(): String = "Vous êtes arrivé"

    override fun startNav(firstInstruction: String): String = "Démarrage de la navigation. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "Vous êtes arrivé à $label" else "Vous êtes arrivé à votre étape"

    override fun fasterRoute(firstInstruction: String): String = "Itinéraire plus rapide. $firstInstruction"

    override fun voiceTest(): String = "Le guidage vocal est activé. Tournez à droite dans 400 mètres."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "de gauche"; LaneSide.RIGHT -> "de droite"; LaneSide.CENTER -> "du milieu" }
        return if (count > 1) "Empruntez les $count voies $sideWord" else "Empruntez la voie $sideWord"
    }

    // expandForSpeech is left as the interface default (identity) — French road names are read natively.

    private fun frNum(x: Double): String = x.toString().replace('.', ',')
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
