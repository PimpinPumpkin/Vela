package app.vela.core.i18n

import app.vela.core.model.LaneSide
import app.vela.core.voice.SpeechText
import java.util.Locale
import kotlin.math.roundToInt

/**
 * All Vela-GENERATED spoken/banner nav text for ONE language. Vela builds turn instructions itself
 * (from OSRM step geometry) rather than scraping them, so localizing navigation = translating this
 * small, bounded set of templates - NOT machine-translating prose. The road/dest NAME passed in is
 * DATA (already in the local language) and is never translated; each method decides the word ORDER
 * around it (which differs by language - "Turn left onto X" vs "Tournez à gauche sur X"), which is why
 * this is per-language templates, not per-word substitution.
 *
 * Resolved by [NavStringsRegistry] (set explicitly from the app locale, never `Locale.getDefault()`,
 * because these run off the main thread - the nav loop + the TTS worker). Part of the app localization
 * effort (see the `project_vela_i18n` memory note).
 */
interface NavStrings {
    val locale: Locale

    /**
     * The full instruction for an OSRM maneuver - mirrors `RouteGeometry.osrmPhrase`. [type] is the
     * OSRM maneuver type ("turn", "off ramp", "roundabout", …, language-independent); [mod] is the OSRM
     * modifier token ("left", "slight right", "straight", …, language-independent - each language maps
     * it); [road] is the road being entered; [dest] a ramp's sign destination; [exitNo] a ramp exit
     * number; [rbExit] a roundabout exit count.
     */
    fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String

    /** Landmark lead-in "Pass the traffic light" / "Pass N traffic lights" for Google-style guidance, prepended
     * to a turn ("Pass the light, then turn left onto 5th Ave"). Default "" = feature omitted for that language
     * (English-first); only added when it makes sense (1–2 signals right before a surface-street turn). */
    fun passLights(count: Int): String = ""

    /** A distance phrased for SPEECH, honouring the imperial/metric preference - "500 feet" / "150 mètres". */
    fun spokenDistance(meters: Double, imperial: Boolean): String

    /** The pre-turn frame combining a distance phrase and the instruction - EN "In X, Y" / FR "Dans X, Y". */
    fun inThen(distancePhrase: String, instruction: String): String

    /** The at-arrival spoken callout - EN "You have arrived". */
    fun arrived(): String

    /** Spoken when navigation begins - EN "Starting navigation. <first instruction>". */
    fun startNav(firstInstruction: String): String

    /** Spoken as each intermediate stop is passed - EN "You've reached <label>" (blank → "your stop"). */
    fun reachedStop(label: String): String

    /** Spoken when auto-switching to a faster route - EN "Taking the faster route. <first instruction>". */
    fun fasterRoute(firstInstruction: String): String

    /** The "Test voice" sample - a short nav-style phrase to hear the selected voice. */
    fun voiceTest(): String

    /** "Rerouting" - spoken when the driver leaves the route and a new one is being fetched. */
    fun rerouting(): String = "Rerouting"

    /** The faster-route OFFER ("Faster route available, saving about N minutes"). */
    fun fasterRouteAvailable(minutes: Int): String = "Faster route available, saving about $minutes minutes"

    /** A reroute landed but couldn't route through the remaining stops. */
    fun stopsNotIncluded(): String = "Couldn't include your stops in this route. I'll keep trying."

    /** Approach cue for the FINAL destination, framed by [inThen] ("In 400 meters, <this>"). */
    fun destinationAhead(): String = "Your destination will be ahead"

    /** A spoken lane recommendation - EN "Use the right 2 lanes" / "Use the left lane". */
    fun useLanes(side: LaneSide, count: Int): String

    /** Lane guidance spoken as a PREFACE to the maneuver (Google-style: "Use the right 2 lanes to take
     * exit 172 toward Sacramento"), rather than appended after it. Default is a safe two-sentence,
     * lanes-first form that works in every language; [EnNavStrings] overrides it with the smooth
     * "…to <maneuver>" connective. */
    fun useLanesToDo(side: LaneSide, count: Int, instruction: String): String =
        useLanes(side, count) + ". " + instruction

    /**
     * Expand road abbreviations + numbers so the TTS engine SAYS them ("St"→"Street", "128th"→"one
     * twenty-eighth"). English-specific, so it's **opt-in**: the default is identity, and ONLY
     * [EnNavStrings] overrides it. Other languages must leave the text - including road-name DATA -
     * untouched, so an English rule can never mangle a foreign name (a French "Rue"/"Bd" is read
     * natively by the French voice).
     */
    fun expandForSpeech(text: String): String = text
}

/** English (source of truth) - byte-identical to the original `osrmPhrase`, so existing nav tests pass. */
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
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "At the roundabout, take exit $rbExit$onto" else "Enter the roundabout$onto"
            "roundabout turn" -> ("At the roundabout, turn $m").trim() + onto
            "uturn" -> "Make a U-turn$onto"
            else -> if (m.isNotBlank()) ("Turn $m").trim() + onto else "Continue$onto"
        }
    }

    // Feet under ~0.15 mi, else miles; metres under ~1 km, else kilometres (unchanged from NavEngine).
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} feet"
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

    override fun passLights(count: Int): String = if (count <= 1) "Pass the traffic light" else "Pass $count traffic lights"

    // Trailing SEMICOLON on purpose (spoken-only string - NavEngine Speak, never displayed): bare text
    // gives the Piper voice no final prosody contour and the callout ended oddly; the user A/B'd
    // punctuation and the semicolon's contour sounds best (period OK, semicolon better). 2026-07-06.
    override fun arrived(): String = "You have arrived;"

    override fun startNav(firstInstruction: String): String = "Starting navigation. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "You've reached $label" else "You've reached your stop"

    override fun fasterRoute(firstInstruction: String): String = "Taking the faster route. $firstInstruction"

    override fun voiceTest(): String = "Voice guidance is on. In a quarter mile, turn right."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "left"; LaneSide.RIGHT -> "right"; LaneSide.CENTER -> "center" }
        return if (count > 1) "Use the $sideWord $count lanes" else "Use the $sideWord lane"
    }

    override fun useLanesToDo(side: LaneSide, count: Int, instruction: String): String {
        val sideWord = when (side) { LaneSide.LEFT -> "left"; LaneSide.RIGHT -> "right"; LaneSide.CENTER -> "center" }
        val lanes = if (count > 1) "the $sideWord $count lanes" else "the $sideWord lane"
        // "Use the right 2 lanes to take exit 172 toward Sacramento" - lowercase the maneuver's first
        // word so it reads as one sentence after the "In <distance>, " frame.
        return "Use $lanes to " + instruction.replaceFirstChar { it.lowercaseChar() }
    }

    /** Whole-word road abbreviation → spoken form, "I-80"→"Interstate 80", and 3-digit street ordinals
     * ("128th"→"one twenty eighth"). Moved here from VoiceGuide.forSpeech so it's English-scoped. */
    override fun expandForSpeech(text: String): String {
        var s = text
        s = Regex("\\bI-(\\d+)").replace(s) { "Interstate ${it.groupValues[1]}" }
        s = Regex("\\bUS-(\\d+)").replace(s) { "US ${it.groupValues[1]}" }
        // State/province highway refs (CA-99, SR-99, WA-520, …) → "State Route N". Reading the bare 2-letter
        // code (e.g. "CA") makes espeak's G2P mangle the K/C onset - a cause of the "the K/T sounds off
        // sometimes" bug. Runs AFTER I-/US- so those keep their spoken forms (US- is already de-hyphenated).
        s = Regex("\\b[A-Z]{2}-(\\d+)").replace(s) { "State Route ${it.groupValues[1]}" }
        // Break the maneuver clause off the sign destination with a comma before " toward ":
        // espeak's G2P is context-sensitive, and on a full "take the ramp toward Woodland" it
        // mis-voweled "take" to "tyke" - chopping "toward Woodland" made it correct (upstream,
        // 2026-07-11). The comma is a fragment boundary (see SpeechText.speechFragments), so the
        // model phonemizes "take the ramp" in isolation, where it's right, and reads the
        // destination as its own beat (Google pauses there too). "toward" only appears on ramp/
        // exit/highway-sign steps, so plain turns ("... onto Main St") are untouched.
        s = Regex(" toward ", RegexOption.IGNORE_CASE).replace(s, ", toward ")
        EN_SPEECH_WORDS.forEach { (re, rep) -> s = re.replace(s, rep) }
        s = SpeechText.spokenNumbers(s) // "128th" → "one twenty eighth" (space, not hyphen - the compound got a mushy -ty), not a mangled "one hundred and 28th"
        return s
    }
}

/** Whole-word road-abbreviation → spoken form, applied only by [EnNavStrings.expandForSpeech]. Road-type
 * suffixes are case-insensitive; the directionals are uppercase (as they appear in road names) and come
 * LAST so they can't chew into a word an earlier rule expanded. */
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
 * French - the first non-English NavStrings, proving the per-language-template design (note the word
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
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "Au rond-point, prenez la ${rbExit}e sortie$sur" else "Engagez-vous sur le rond-point$sur"
            "roundabout turn" -> ("Au rond-point, tournez $m").trim() + sur
            "uturn" -> "Faites demi-tour$sur"
            else -> if (m.isNotBlank()) ("Tournez $m").trim() + sur else "Continuez$sur"
        }
    }

    // France is metric; the imperial branch is kept for parity. French uses a decimal COMMA ("1,2 km").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} pieds"
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
    override fun rerouting(): String = "Recalcul de l'itinéraire"
    override fun fasterRouteAvailable(minutes: Int): String = "Itinéraire plus rapide disponible, environ $minutes minutes de gagnées"
    override fun stopsNotIncluded(): String = "Impossible d'inclure vos étapes dans cet itinéraire. Je continue d'essayer."
    override fun destinationAhead(): String = "Votre destination sera devant vous"

    override fun voiceTest(): String = "Le guidage vocal est activé. Tournez à droite dans 400 mètres."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "de gauche"; LaneSide.RIGHT -> "de droite"; LaneSide.CENTER -> "du milieu" }
        return if (count > 1) "Empruntez les $count voies $sideWord" else "Empruntez la voie $sideWord"
    }

    // expandForSpeech is left as the interface default (identity) - French road names are read natively.

    private fun frNum(x: Double): String = x.toString().replace('.', ',')
}

object DeNavStrings : NavStrings {
    override val locale: Locale = Locale.GERMANY

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "links"
        "right" -> "rechts"
        "slight left" -> "leicht links"
        "slight right" -> "leicht rechts"
        "sharp left" -> "scharf links"
        "sharp right" -> "scharf rechts"
        "straight" -> "geradeaus"
        "uturn" -> "wenden"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val auf = if (road != null) " auf $road" else ""
        val richtung = when {
            dest != null -> " Richtung $dest"
            road != null -> " auf $road"
            else -> ""
        }
        val m = modWord(mod)
        return when (type) {
            "depart" -> if (road != null) "Fahren Sie los auf $road" else "Fahren Sie los"
            "arrive" -> "Sie haben Ihr Ziel erreicht"
            "turn", "end of road" -> if (m.isNotBlank()) "Biegen Sie $m ab$auf" else "Biegen Sie ab$auf"
            "continue", "new name" -> if (m.isNotBlank() && m != "geradeaus") "Halten Sie sich $m$auf" else "Fahren Sie geradeaus weiter$auf"
            "merge" -> "Fädeln Sie sich ein$richtung"
            "on ramp", "ramp" -> "Nehmen Sie die Auffahrt$richtung"
            "off ramp" -> if (exitNo != null) "Nehmen Sie die Ausfahrt $exitNo$richtung" else "Nehmen Sie die Ausfahrt$richtung"
            "fork" -> ("Halten Sie sich $m").trim() + richtung
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "Nehmen Sie im Kreisverkehr die ${deOrd(rbExit)} Ausfahrt$auf" else "Fahren Sie in den Kreisverkehr ein$auf"
            "roundabout turn" -> if (m.isNotBlank()) "Biegen Sie im Kreisverkehr $m ab$auf" else "Fahren Sie im Kreisverkehr$auf"
            "uturn" -> "Bitte wenden$auf"
            else -> if (m.isNotBlank()) "Biegen Sie $m ab$auf" else "Fahren Sie geradeaus weiter$auf"
        }
    }

    // Germany is metric; the imperial branch is kept for parity. German uses a decimal COMMA ("1,2 km").
    // Unit words are DATIVE, because the phrase is almost always spoken inside inThen ("In 400 Metern, …").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} Fuß"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 Meile" else "${deNum(miles)} Meilen"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} Metern"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 Kilometer" else "${deNum(km)} Kilometern"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "In $distancePhrase, $instruction"

    override fun arrived(): String = "Sie haben Ihr Ziel erreicht"

    override fun startNav(firstInstruction: String): String = "Navigation wird gestartet. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "Sie haben $label erreicht" else "Sie haben Ihren Zwischenstopp erreicht"

    override fun fasterRoute(firstInstruction: String): String = "Schnellere Route wird genommen. $firstInstruction"
    override fun rerouting(): String = "Route wird neu berechnet"
    override fun fasterRouteAvailable(minutes: Int): String = "Schnellere Route verfügbar, spart etwa $minutes Minuten"
    override fun stopsNotIncluded(): String = "Ihre Zwischenstopps konnten nicht aufgenommen werden. Ich versuche es weiter."
    override fun destinationAhead(): String = "Ihr Ziel liegt voraus"

    override fun voiceTest(): String = "Die Sprachausgabe ist aktiviert. Biegen Sie in 400 Metern rechts ab."

    override fun useLanes(side: LaneSide, count: Int): String {
        return if (count > 1) {
            val sideWordPl = when (side) { LaneSide.LEFT -> "linken"; LaneSide.RIGHT -> "rechten"; LaneSide.CENTER -> "mittleren" }
            "Benutzen Sie die $count $sideWordPl Spuren"
        } else {
            val sideWord = when (side) { LaneSide.LEFT -> "linke"; LaneSide.RIGHT -> "rechte"; LaneSide.CENTER -> "mittlere" }
            "Benutzen Sie die $sideWord Spur"
        }
    }

    // expandForSpeech is left as the interface default (identity) - German road names are read natively.

    private fun deNum(x: Double): String = x.toString().replace('.', ',')

    // Roundabout exit ordinal - feminine, agreeing with "die … Ausfahrt"; spelled out for the common
    // low counts a native driver hears, "N." otherwise.
    private fun deOrd(n: Int): String = when (n) {
        1 -> "erste"
        2 -> "zweite"
        3 -> "dritte"
        4 -> "vierte"
        5 -> "fünfte"
        6 -> "sechste"
        7 -> "siebte"
        8 -> "achte"
        9 -> "neunte"
        else -> "$n."
    }
}

object EsNavStrings : NavStrings {
    override val locale: Locale = Locale("es", "ES")

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "a la izquierda"
        "right" -> "a la derecha"
        "slight left" -> "ligeramente a la izquierda"
        "slight right" -> "ligeramente a la derecha"
        "sharp left" -> "cerrado a la izquierda"
        "sharp right" -> "cerrado a la derecha"
        "straight" -> "recto"
        "uturn" -> "cambio de sentido"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val por = if (road != null) " por $road" else ""
        val hacia = when {
            dest != null -> " hacia $dest"
            road != null -> " por $road"
            else -> ""
        }
        // Merges/incorporations take "a"/"en dirección a", never "hacia".
        val incorp = when {
            dest != null -> " en dirección a $dest"
            road != null -> " a $road"
            else -> ""
        }
        val m = modWord(mod)
        return when (type) {
            "depart" -> if (road != null) "Circule por $road" else "Inicie su ruta"
            "arrive" -> "Ha llegado a su destino"
            "turn", "end of road" -> ("Gire $m").trim() + por
            "continue", "new name" -> if (m.isNotBlank() && m != "recto") ("Manténgase $m").trim() + por else "Continúe$por"
            "merge" -> "Incorpórese$incorp"
            "on ramp", "ramp" -> "Tome el acceso$hacia"
            "off ramp" -> if (exitNo != null) "Tome la salida $exitNo$hacia" else "Tome la salida$hacia"
            "fork" -> ("Manténgase $m").trim() + hacia
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "En la rotonda, tome la $rbExit.ª salida$por" else "Incorpórese a la rotonda$por"
            "roundabout turn" -> ("En la rotonda, gire $m").trim() + por
            "uturn" -> "Haga un cambio de sentido$por"
            else -> if (m.isNotBlank()) ("Gire $m").trim() + por else "Continúe$por"
        }
    }

    // Spain is metric; the imperial branch is kept for parity. Spanish uses a decimal COMMA ("1,2 km").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} pies"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 milla" else "${esNum(miles)} millas"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} metros"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 kilómetro" else "${esNum(km)} kilómetros"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "En $distancePhrase, $instruction"

    override fun arrived(): String = "Ha llegado"

    override fun startNav(firstInstruction: String): String = "Iniciando la navegación. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "Ha llegado a $label" else "Ha llegado a su parada"

    override fun fasterRoute(firstInstruction: String): String = "Tomando la ruta más rápida. $firstInstruction"
    override fun rerouting(): String = "Recalculando la ruta"
    override fun fasterRouteAvailable(minutes: Int): String = "Ruta más rápida disponible, ahorra unos $minutes minutos"
    override fun stopsNotIncluded(): String = "No se pudieron incluir tus paradas en esta ruta. Seguiré intentándolo."
    override fun destinationAhead(): String = "Tu destino estará más adelante"

    override fun voiceTest(): String = "La guía por voz está activada. Dentro de 400 metros, gire a la derecha."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "de la izquierda"; LaneSide.RIGHT -> "de la derecha"; LaneSide.CENTER -> "del centro" }
        return if (count > 1) "Use los $count carriles $sideWord" else "Use el carril $sideWord"
    }

    // expandForSpeech is left as the interface default (identity) - Spanish road names are read natively.

    private fun esNum(x: Double): String = x.toString().replace('.', ',')
}

object ItNavStrings : NavStrings {
    override val locale: Locale = Locale.ITALY

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "a sinistra"
        "right" -> "a destra"
        "slight left" -> "leggermente a sinistra"
        "slight right" -> "leggermente a destra"
        "sharp left" -> "a gomito a sinistra"
        "sharp right" -> "a gomito a destra"
        "straight" -> "dritto"
        "uturn" -> "inversione a U"
        else -> ""
    }

    // The bare feminine side NOUN ("la sinistra"/"la destra") for the keep/bear/fork frames,
    // where modWord's full adverbial ("a sinistra") would produce broken "Tieni la a sinistra".
    private fun sideNoun(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left", "slight left", "sharp left" -> "sinistra"
        "right", "slight right", "sharp right" -> "destra"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val su = if (road != null) " su $road" else ""
        val verso = when {
            dest != null -> " verso $dest"
            road != null -> " su $road"
            else -> ""
        }
        val m = modWord(mod)
        val side = sideNoun(mod)
        return when (type) {
            "depart" -> if (road != null) "Parti su $road" else "Avvia il percorso"
            "arrive" -> "Sei arrivato a destinazione"
            "turn", "end of road" -> ("Svolta $m").trim() + su
            "continue", "new name" -> if (side.isNotBlank()) "Tieni la $side$su" else "Prosegui$su"
            "merge" -> "Immettiti$verso"
            "on ramp", "ramp" -> "Prendi la rampa$verso"
            "off ramp" -> if (exitNo != null) "Prendi l'uscita $exitNo$verso" else "Prendi l'uscita$verso"
            "fork" -> if (side.isNotBlank()) "Tieni la $side$verso" else "Prosegui$verso"
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "Alla rotatoria, prendi la ${rbExit}ª uscita$su" else "Immettiti nella rotatoria$su"
            "roundabout turn" -> ("Alla rotatoria, svolta $m").trim() + su
            "uturn" -> "Fai inversione a U$su"
            else -> if (m.isNotBlank()) ("Svolta $m").trim() + su else "Prosegui$su"
        }
    }

    // Italy is metric; the imperial branch is kept for parity. Italian uses a decimal COMMA ("1,2 km").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} piedi"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 miglio" else "${itNum(miles)} miglia"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} metri"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 chilometro" else "${itNum(km)} chilometri"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "Tra $distancePhrase, $instruction"

    override fun arrived(): String = "Sei arrivato"

    override fun startNav(firstInstruction: String): String = "Avvio della navigazione. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "Sei arrivato a $label" else "Sei arrivato alla tua tappa"

    override fun fasterRoute(firstInstruction: String): String = "Percorso più veloce. $firstInstruction"
    override fun rerouting(): String = "Ricalcolo del percorso"
    override fun fasterRouteAvailable(minutes: Int): String = "Percorso più veloce disponibile, risparmi circa $minutes minuti"
    override fun stopsNotIncluded(): String = "Impossibile includere le tue tappe in questo percorso. Continuerò a provare."
    override fun destinationAhead(): String = "La tua destinazione sarà più avanti"

    override fun voiceTest(): String = "La guida vocale è attiva. Tra 400 metri, svolta a destra."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "di sinistra"; LaneSide.RIGHT -> "di destra"; LaneSide.CENTER -> "centrale" }
        return if (count > 1) "Usa le $count corsie $sideWord" else "Usa la corsia $sideWord"
    }

    // expandForSpeech is left as the interface default (identity) - Italian road names are read natively.

    private fun itNum(x: Double): String = x.toString().replace('.', ',')
}

/**
 * Portuguese (Brazil) - mirrors [FrNavStrings] structurally (note the word order: "à esquerda **na**
 * X", the modifier folded into the verb phrase, roundabout ordinals "2ª saída"). Road/dest names are
 * passed through untranslated. Brazil is metric and uses a decimal COMMA ("1,2 km").
 */
object PtNavStrings : NavStrings {
    override val locale: Locale = Locale("pt", "BR")

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "à esquerda"
        "right" -> "à direita"
        "slight left" -> "levemente à esquerda"
        "slight right" -> "levemente à direita"
        "sharp left" -> "acentuadamente à esquerda"
        "sharp right" -> "acentuadamente à direita"
        "straight" -> "em frente"
        "uturn" -> "meia-volta"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val na = if (road != null) " na $road" else ""
        val sentido = when {
            dest != null -> " sentido $dest"
            road != null -> " na $road"
            else -> ""
        }
        val m = modWord(mod)
        return when (type) {
            "depart" -> if (road != null) "Siga pela $road" else "Inicie sua rota"
            "arrive" -> "Você chegou ao seu destino"
            "turn", "end of road" -> ("Vire $m").trim() + na
            "continue", "new name" -> if (m.isNotBlank() && m != "em frente") ("Mantenha-se $m").trim() + na else "Continue$na"
            "merge" -> "Entre$sentido"
            "on ramp", "ramp" -> "Pegue o acesso$sentido"
            "off ramp" -> if (exitNo != null) "Pegue a saída $exitNo$sentido" else "Pegue a saída$sentido"
            "fork" -> ("Mantenha-se $m").trim() + sentido
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "Na rotatória, pegue a ${rbExit}ª saída$na" else "Entre na rotatória$na"
            "roundabout turn" -> ("Na rotatória, vire $m").trim() + na
            "uturn" -> "Faça o retorno$na"
            else -> if (m.isNotBlank()) ("Vire $m").trim() + na else "Continue$na"
        }
    }

    // Brazil is metric; the imperial branch is kept for parity. Portuguese uses a decimal COMMA ("1,2 km").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} pés"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 milha" else "${ptNum(miles)} milhas"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} metros"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 quilômetro" else "${ptNum(km)} quilômetros"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "Em $distancePhrase, $instruction"

    override fun arrived(): String = "Você chegou"

    override fun startNav(firstInstruction: String): String = "Iniciando a navegação. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "Você chegou a $label" else "Você chegou à sua parada"

    override fun fasterRoute(firstInstruction: String): String = "Pegando a rota mais rápida. $firstInstruction"
    override fun rerouting(): String = "Recalculando a rota"
    override fun fasterRouteAvailable(minutes: Int): String = "Rota mais rápida disponível, economiza cerca de $minutes minutos"
    override fun stopsNotIncluded(): String = "Não foi possível incluir suas paradas nesta rota. Vou continuar tentando."
    override fun destinationAhead(): String = "Seu destino estará adiante"

    override fun voiceTest(): String = "A orientação por voz está ativada. Em 400 metros, vire à direita."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "à esquerda"; LaneSide.RIGHT -> "à direita"; LaneSide.CENTER -> "do meio" }
        return if (count > 1) "Use as $count faixas $sideWord" else "Use a faixa $sideWord"
    }

    // expandForSpeech is left as the interface default (identity) - Portuguese road names are read natively.

    private fun ptNum(x: Double): String = x.toString().replace('.', ',')
}

object NlNavStrings : NavStrings {
    override val locale: Locale = Locale("nl", "NL")

    // Plain directional phrase - the verb is folded into each template (Dutch "afslaan" is separable),
    // so modWord must NOT carry a verb. Mirrors the French approach.
    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "links"
        "right" -> "rechts"
        "slight left" -> "schuin naar links"
        "slight right" -> "schuin naar rechts"
        "sharp left" -> "scherp naar links"
        "sharp right" -> "scherp naar rechts"
        "straight" -> "rechtdoor"
        "uturn" -> "keer om"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        // Turning ONTO a road reads "... de <road> op" in Dutch ("Sla linksaf de Hoofdstraat op").
        val deOp = if (road != null) " de $road op" else ""
        // Continue/depart use "op <road>" (staying on / setting off on a road).
        val op = if (road != null) " op $road" else ""
        val richting = when {
            dest != null -> " richting $dest"
            road != null -> " op $road"
            else -> ""
        }
        val m = modWord(mod)
        return when (type) {
            "depart" -> if (road != null) "Vertrek op $road" else "Start uw route"
            "arrive" -> "U bent op uw bestemming aangekomen"
            "turn", "end of road" -> when (m) {
                "links" -> "Sla linksaf$deOp"
                "rechts" -> "Sla rechtsaf$deOp"
                "rechtdoor", "" -> "Ga rechtdoor$op"
                else -> "Ga $m$deOp"
            }
            "continue", "new name" -> when {
                m == "links" || m == "rechts" -> "Houd $m aan$op"
                m.isNotBlank() && m != "rechtdoor" -> "Ga $m$op"
                else -> "Ga rechtdoor$op"
            }
            "merge" -> "Voeg in$richting"
            "on ramp", "ramp" -> "Neem de oprit$richting"
            "off ramp" -> if (exitNo != null) "Neem afrit $exitNo$richting" else "Neem de afrit$richting"
            // Derive the SIDE from the full modWord - OSRM forks are almost always "slight left"/
            // "slight right" (→ "schuin naar links/rechts"), which the old exact match on
            // "links"/"rechts" missed, falling to a hardcoded "Houd links aan" - KEEP-LEFT guidance
            // at a keep-RIGHT freeway split (audit 2026-07-06, safety bug). endsWith covers the
            // plain/schuin/scherp forms; a straight fork says "Ga rechtdoor" instead of a made-up left.
            "fork" -> when {
                m.endsWith("links") -> "Houd links aan$richting"
                m.endsWith("rechts") -> "Houd rechts aan$richting"
                m == "rechtdoor" -> "Ga rechtdoor$richting"
                else -> "Houd links aan$richting"
            }
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "Neem op de rotonde de ${rbExit}e afslag$op" else "Ga de rotonde op$op"
            "roundabout turn" -> when (m) {
                "links" -> "Sla op de rotonde linksaf$deOp"
                "rechts" -> "Sla op de rotonde rechtsaf$deOp"
                else -> if (m.isNotBlank()) "Ga op de rotonde $m$deOp" else "Ga de rotonde op$op"
            }
            "uturn" -> "Keer om$op"
            else -> when (m) {
                "links" -> "Sla linksaf$deOp"
                "rechts" -> "Sla rechtsaf$deOp"
                "" , "rechtdoor" -> "Ga rechtdoor$op"
                else -> "Ga $m$deOp"
            }
        }
    }

    // Nederland is metrisch; de imperiale tak blijft voor pariteit. Nederlands gebruikt een decimale KOMMA ("1,2 km").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} voet"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 mijl" else "${nlNum(miles)} mijl"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} meter"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 kilometer" else "${nlNum(km)} kilometer"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "Over $distancePhrase, $instruction"

    override fun arrived(): String = "U bent aangekomen"

    override fun startNav(firstInstruction: String): String = "Navigatie wordt gestart. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "U hebt $label bereikt" else "U hebt uw tussenstop bereikt"

    override fun fasterRoute(firstInstruction: String): String = "Snellere route gekozen. $firstInstruction"
    override fun rerouting(): String = "Route wordt opnieuw berekend"
    override fun fasterRouteAvailable(minutes: Int): String = "Snellere route beschikbaar, bespaart ongeveer $minutes minuten"
    override fun stopsNotIncluded(): String = "Je tussenstops konden niet in deze route worden opgenomen. Ik blijf het proberen."
    override fun destinationAhead(): String = "Je bestemming ligt verderop"

    override fun voiceTest(): String = "Gesproken navigatie staat aan. Sla over 400 meter rechtsaf."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "linker"; LaneSide.RIGHT -> "rechter"; LaneSide.CENTER -> "middelste" }
        return if (count > 1) "Gebruik de $count ${sideWord}rijstroken" else "Gebruik de ${sideWord}rijstrook"
    }

    // expandForSpeech blijft de interface-standaard (identiteit) - Nederlandse straatnamen worden natief voorgelezen.

    private fun nlNum(x: Double): String = x.toString().replace('.', ',')
}

object RuNavStrings : NavStrings {
    override val locale: Locale = Locale("ru", "RU")

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "налево"
        "right" -> "направо"
        "slight left" -> "левее"
        "slight right" -> "правее"
        "sharp left" -> "резко налево"
        "sharp right" -> "резко направо"
        "straight" -> "прямо"
        "uturn" -> "разворот"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val na = if (road != null) " на $road" else ""
        val vStoronu = when {
            dest != null -> " в сторону $dest"
            road != null -> " на $road"
            else -> ""
        }
        val m = modWord(mod)
        return when (type) {
            "depart" -> if (road != null) "Начните движение по $road" else "Начните движение"
            "arrive" -> "Вы прибыли в пункт назначения"
            "turn", "end of road" -> ("Поверните $m").trim() + na
            "continue", "new name" -> if (m.isNotBlank() && m != "прямо") ("Держитесь $m").trim() + na else "Продолжайте движение$na"
            "merge" -> "Перестройтесь$vStoronu"
            "on ramp", "ramp" -> "Выезжайте на автомагистраль$vStoronu"
            "off ramp" -> if (exitNo != null) "Съезжайте на съезде $exitNo$vStoronu" else "Уходите на съезд$vStoronu"
            "fork" -> ("Держитесь $m").trim() + vStoronu
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "На кольце - ${rbOrdinal(rbExit)} съезд$na" else "Выезжайте на круговое движение$na"
            "roundabout turn" -> ("На кольце поверните $m").trim() + na
            "uturn" -> "Выполните разворот$na"
            else -> if (m.isNotBlank()) ("Поверните $m").trim() + na else "Продолжайте движение$na"
        }
    }

    // Russia is metric; the imperial branch is kept for parity. Russian uses a decimal COMMA ("1,2 км").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) ruFeet((if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50))
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            ruMiles(miles)
        }
    } else {
        if (meters < 950) ruMeters((meters / 10).roundToInt() * 10)
        else {
            val km = (meters / 100).roundToInt() / 10.0
            ruKm(km)
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "Через $distancePhrase $instruction"

    override fun arrived(): String = "Вы на месте"

    override fun startNav(firstInstruction: String): String = "Начинаю навигацию. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "Вы прибыли в $label" else "Вы прибыли к остановке"

    override fun fasterRoute(firstInstruction: String): String = "Перехожу на более быстрый маршрут. $firstInstruction"
    override fun rerouting(): String = "Перестроение маршрута"
    override fun fasterRouteAvailable(minutes: Int): String = "Доступен более быстрый маршрут, экономия около $minutes минут"
    override fun stopsNotIncluded(): String = "Не удалось включить остановки в маршрут. Продолжаю попытки."
    override fun destinationAhead(): String = "Пункт назначения будет впереди"

    override fun voiceTest(): String = "Голосовые подсказки включены. Через 400 метров поверните направо."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "левую"; LaneSide.RIGHT -> "правую"; LaneSide.CENTER -> "центральную" }
        return if (count > 1) {
            val sideWordPl = when (side) { LaneSide.LEFT -> "левые"; LaneSide.RIGHT -> "правые"; LaneSide.CENTER -> "центральные" }
            "Займите $count $sideWordPl ${lanePlural(count)}"
        } else {
            "Займите $sideWord полосу"
        }
    }

    // expandForSpeech is left as the interface default (identity) - Russian road names are read natively.

    // Russian ordinal for the roundabout exit, agreeing with masculine "съезд" ("первый/второй/…").
    // Falls back to the numeric "N-й" form beyond the common range.
    private fun rbOrdinal(n: Int): String = when (n) {
        1 -> "первый"
        2 -> "второй"
        3 -> "третий"
        4 -> "четвёртый"
        5 -> "пятый"
        6 -> "шестой"
        7 -> "седьмой"
        8 -> "восьмой"
        9 -> "девятый"
        10 -> "десятый"
        11 -> "одиннадцатый"
        12 -> "двенадцатый"
        else -> "$n-й"
    }

    // Russian decimal comma with a bare-integer shortcut ("2.0" → "2", "1.2" → "1,2").
    private fun ruNum(x: Double): String {
        val s = x.toString().replace('.', ',')
        return if (s.endsWith(",0")) s.dropLast(2) else s
    }

    // Russian noun pluralization: one / few (2-4) / many (0, 5-20, …).
    private fun pluralForm(n: Int): Int {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> 2
            mod10 == 1 -> 0
            mod10 in 2..4 -> 1
            else -> 2
        }
    }

    private fun ruMeters(n: Int): String {
        val unit = when (pluralForm(n)) { 0 -> "метр"; 1 -> "метра"; else -> "метров" }
        return "$n $unit"
    }

    private fun ruFeet(n: Int): String {
        val unit = when (pluralForm(n)) { 0 -> "фут"; 1 -> "фута"; else -> "футов" }
        return "$n $unit"
    }

    // Whole km take full noun agreement; fractional km read genitive-singular ("1,2 километра").
    private fun ruKm(x: Double): String {
        val whole = x % 1.0 == 0.0
        return if (whole) {
            val n = x.toInt()
            val unit = when (pluralForm(n)) { 0 -> "километр"; 1 -> "километра"; else -> "километров" }
            "$n $unit"
        } else {
            "${ruNum(x)} километра"
        }
    }

    private fun ruMiles(x: Double): String {
        val whole = x % 1.0 == 0.0
        return if (whole) {
            val n = x.toInt()
            val unit = when (pluralForm(n)) { 0 -> "миля"; 1 -> "мили"; else -> "миль" }
            "$n $unit"
        } else {
            "${ruNum(x)} мили"
        }
    }

    private fun lanePlural(n: Int): String = when (pluralForm(n)) { 0 -> "полосу"; 1 -> "полосы"; else -> "полос" }
}

/**
 * Polish - mirrors [FrNavStrings] structurally. The modifier folds into the imperative verb
 * ("Skręć w lewo w X"); roundabout ordinals are "1. zjazdem / 2. zjazdem" (instrumental of "zjazd").
 * Road/dest names are passed through untranslated. Register is the Google-Maps-PL informal
 * 2nd-person imperative ("Skręć", "Jedź"). Number-noun agreement follows Polish rules (see [plUnit]).
 */
object PlNavStrings : NavStrings {
    override val locale: Locale = Locale("pl", "PL")

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "w lewo"
        "right" -> "w prawo"
        "slight left" -> "lekko w lewo"
        "slight right" -> "lekko w prawo"
        "sharp left" -> "ostro w lewo"
        "sharp right" -> "ostro w prawo"
        "straight" -> "prosto"
        "uturn" -> "zawróć"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val w = if (road != null) " w $road" else ""
        val ku = when {
            dest != null -> " w kierunku $dest"
            road != null -> " w $road"
            else -> ""
        }
        val m = modWord(mod)
        return when (type) {
            "depart" -> if (road != null) "Jedź drogą $road" else "Rozpocznij trasę"
            "arrive" -> "Dojazd do celu"
            "turn", "end of road" -> ("Skręć $m").trim() + w
            "continue", "new name" -> if (m.isNotBlank() && m != "prosto") ("Trzymaj się $m").trim() + w else "Jedź dalej$w"
            "merge" -> "Włącz się do ruchu$ku"
            "on ramp", "ramp" -> "Wjedź na łącznicę$ku"
            "off ramp" -> if (exitNo != null) "Zjedź zjazdem $exitNo$ku" else "Zjedź zjazdem$ku"
            "fork" -> ("Trzymaj się $m").trim() + ku
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "Na rondzie zjedź ${rbExit}. zjazdem$w" else "Wjedź na rondo$w"
            "roundabout turn" -> ("Na rondzie skręć $m").trim() + w
            "uturn" -> "Zawróć$w"
            else -> if (m.isNotBlank()) ("Skręć $m").trim() + w else "Jedź dalej$w"
        }
    }

    // Poland is metric; the imperial branch is kept for parity. Polish uses a decimal COMMA ("1,2 km").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} stóp"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 mila" else "${plNum(miles)} ${plUnit(miles, "mila", "mile", "mil", "mili")}"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} metrów"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 kilometr" else "${plNum(km)} ${plUnit(km, "kilometr", "kilometry", "kilometrów", "kilometra")}"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "Za $distancePhrase $instruction"

    override fun arrived(): String = "Dotarłeś do celu"

    override fun startNav(firstInstruction: String): String = "Rozpoczynam nawigację. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "Dotarłeś do $label" else "Dotarłeś do przystanku"

    override fun fasterRoute(firstInstruction: String): String = "Wybieram szybszą trasę. $firstInstruction"
    override fun rerouting(): String = "Przeliczanie trasy"
    override fun fasterRouteAvailable(minutes: Int): String = "Dostępna szybsza trasa, oszczędność około $minutes minut"
    override fun stopsNotIncluded(): String = "Nie udało się uwzględnić przystanków na tej trasie. Będę próbować dalej."
    override fun destinationAhead(): String = "Cel podróży będzie przed tobą"

    override fun voiceTest(): String = "Nawigacja głosowa jest włączona. Za 400 metrów skręć w prawo."

    override fun useLanes(side: LaneSide, count: Int): String {
        val laneInst = when (side) { LaneSide.LEFT -> "lewym"; LaneSide.RIGHT -> "prawym"; LaneSide.CENTER -> "środkowym" }
        val lanesInst = when (side) { LaneSide.LEFT -> "lewymi"; LaneSide.RIGHT -> "prawymi"; LaneSide.CENTER -> "środkowymi" }
        return if (count > 1) "Jedź $count $lanesInst pasami" else "Jedź $laneInst pasem"
    }

    // expandForSpeech is left as the interface default (identity) - Polish road names are read natively.

    private fun plNum(x: Double): String = x.toString().replace('.', ',')

    /**
     * Polish quantity agreement. A decimal (fractional part ≠ 0) always takes the genitive-singular
     * [gsg] ("1,2 kilometra", "2,5 mili"). Whole numbers: 1 → [one]; 2–4 (but not 12–14) → [few];
     * everything else → [many] (genitive plural).
     */
    private fun plUnit(x: Double, one: String, few: String, many: String, gsg: String): String {
        if (x != kotlin.math.floor(x)) return gsg
        val n = x.toInt()
        val u = n % 10
        val t = n % 100
        return when {
            n == 1 -> one
            u in 2..4 && t !in 12..14 -> few
            else -> many
        }
    }
}

object SvNavStrings : NavStrings {
    override val locale: Locale = Locale("sv", "SE")

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "vänster"
        "right" -> "höger"
        "slight left" -> "svagt vänster"
        "slight right" -> "svagt höger"
        "sharp left" -> "skarpt vänster"
        "sharp right" -> "skarpt höger"
        "straight" -> "rakt fram"
        "uturn" -> "U-sväng"
        else -> ""
    }

    // Swedish ordinals: 1:a, 2:a, 3:e, 4:e … - Google Maps Sweden speaks them out
    // ("ta första/andra/tredje avfarten"), so give the small counts their word forms
    // and fall back to the "N:a/N:e" digit-ordinal for larger exit numbers.
    private fun svOrd(n: Int): String = when (n) {
        1 -> "första"
        2 -> "andra"
        3 -> "tredje"
        4 -> "fjärde"
        5 -> "femte"
        6 -> "sjätte"
        7 -> "sjunde"
        8 -> "åttonde"
        9 -> "nionde"
        10 -> "tionde"
        else -> if (n % 100 in 1..2) "$n:a" else "$n:e"
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val pa = if (road != null) " in på $road" else ""
        val mot = when {
            dest != null -> " mot $dest"
            road != null -> " in på $road"
            else -> ""
        }
        // A merge/ramp connects TO a road or heads TOWARD a sign - "till"/"mot", never "in på".
        val till = when {
            dest != null -> " mot $dest"
            road != null -> " till $road"
            else -> ""
        }
        val m = modWord(mod)
        return when (type) {
            "depart" -> if (road != null) "Kör ut på $road" else "Starta rutten"
            "arrive" -> "Du är framme vid din destination"
            "turn", "end of road" -> ("Sväng $m").trim() + pa
            "continue", "new name" -> if (m.isNotBlank() && m != "rakt fram") ("Håll $m").trim() + pa else "Fortsätt$pa"
            "merge" -> "Anslut$till"
            "on ramp", "ramp" -> "Ta påfarten$till"
            "off ramp" -> if (exitNo != null) "Ta avfart $exitNo$mot" else "Ta avfarten$mot"
            "fork" -> ("Håll $m").trim() + mot
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "Ta ${svOrd(rbExit)} avfarten i rondellen$pa" else "Kör in i rondellen$pa"
            "roundabout turn" -> ("Sväng $m i rondellen").trim() + pa
            "uturn" -> "Gör en U-sväng$pa"
            else -> if (m.isNotBlank()) ("Sväng $m").trim() + pa else "Fortsätt$pa"
        }
    }

    // Sweden is metric; the imperial branch is kept for parity. Swedish uses a decimal COMMA ("1,2 km").
    // NB "mil" in Swedish is the 10 km Scandinavian mile, so an English mile is disambiguated as
    // "engelsk mil"; "mil" is invariant in the plural ("två engelska mil").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} fot"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 engelsk mil" else "${svNum(miles)} engelska mil"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} meter"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 kilometer" else "${svNum(km)} kilometer"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "Om $distancePhrase, $instruction"

    override fun arrived(): String = "Du är framme"

    override fun startNav(firstInstruction: String): String = "Startar navigeringen. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "Du är framme vid $label" else "Du är framme vid ditt stopp"

    override fun fasterRoute(firstInstruction: String): String = "Byter till en snabbare rutt. $firstInstruction"
    override fun rerouting(): String = "Räknar om rutten"
    override fun fasterRouteAvailable(minutes: Int): String = "Snabbare rutt tillgänglig, sparar cirka $minutes minuter"
    override fun stopsNotIncluded(): String = "Kunde inte ta med dina stopp på denna rutt. Jag fortsätter försöka."
    override fun destinationAhead(): String = "Din destination ligger framför dig"

    override fun voiceTest(): String = "Röstvägledningen är på. Om 400 meter, sväng höger."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "vänstra"; LaneSide.RIGHT -> "högra"; LaneSide.CENTER -> "mittersta" }
        return if (count > 1) "Använd de $count $sideWord filerna" else "Använd den $sideWord filen"
    }

    // expandForSpeech is left as the interface default (identity) - Swedish road names are read natively.

    private fun svNum(x: Double): String = x.toString().replace('.', ',')
}

object UkNavStrings : NavStrings {
    override val locale: Locale = Locale("uk", "UA")

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "ліворуч"
        "right" -> "праворуч"
        "slight left" -> "трохи ліворуч"
        "slight right" -> "трохи праворуч"
        "sharp left" -> "різко ліворуч"
        "sharp right" -> "різко праворуч"
        "straight" -> "прямо"
        "uturn" -> "у зворотному напрямку"
        else -> ""
    }

    // For "keep"/"bear" maneuvers Ukrainian nav uses comparative forms: правіше/лівіше.
    private fun keepWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left", "slight left", "sharp left" -> "ліворуч"
        "right", "slight right", "sharp right" -> "праворуч"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val na = if (road != null) " на $road" else ""
        val vers = when {
            dest != null -> " у напрямку $dest"
            road != null -> " на $road"
            else -> ""
        }
        val m = modWord(mod)
        val keep = keepWord(mod)
        return when (type) {
            "depart" -> if (road != null) "Рушайте по $road" else "Почніть маршрут"
            "arrive" -> "Ви прибули до пункту призначення"
            "turn", "end of road" -> ("Поверніть $m").trim() + na
            "continue", "new name" -> if (keep.isNotBlank()) "Тримайтеся $keep$na" else "Продовжуйте рух$na"
            "merge" -> "Приєднайтеся до потоку$vers"
            "on ramp", "ramp" -> "Виїжджайте на з'їзд$vers"
            "off ramp" -> if (exitNo != null) "З'їжджайте на з'їзд $exitNo$vers" else "З'їжджайте зі з'їзду$vers"
            "fork" -> if (keep.isNotBlank()) "Тримайтеся $keep$vers" else "Продовжуйте рух$vers"
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "На кільцевій розв'язці зверніть на ${rbExit}-й з'їзд$na" else "В'їжджайте на кільцеву розв'язку$na"
            "roundabout turn" -> ("На кільцевій розв'язці поверніть $m").trim() + na
            "uturn" -> "Розверніться$na"
            else -> if (m.isNotBlank()) ("Поверніть $m").trim() + na else "Продовжуйте рух$na"
        }
    }

    // Ukraine is metric; the imperial branch is kept for parity. Ukrainian uses a decimal COMMA ("1,2 км").
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} футів"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            "${ukNum(miles)} ${ukPlural(miles, "миля", "милі", "миль", "милі")}"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} метрів"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            "${ukNum(km)} ${ukPlural(km, "кілометр", "кілометри", "кілометрів", "кілометра")}"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "Через $distancePhrase $instruction"

    override fun arrived(): String = "Ви прибули"

    override fun startNav(firstInstruction: String): String = "Починаємо навігацію. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "Ви прибули до $label" else "Ви прибули до зупинки"

    override fun fasterRoute(firstInstruction: String): String = "Переходимо на швидший маршрут. $firstInstruction"
    override fun rerouting(): String = "Перебудова маршруту"
    override fun fasterRouteAvailable(minutes: Int): String = "Доступний швидший маршрут, економія близько $minutes хвилин"
    override fun stopsNotIncluded(): String = "Не вдалося включити зупинки в маршрут. Продовжую спроби."
    override fun destinationAhead(): String = "Пункт призначення буде попереду"

    override fun voiceTest(): String = "Голосові підказки увімкнено. Через 400 метрів поверніть праворуч."

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "лівою"; LaneSide.RIGHT -> "правою"; LaneSide.CENTER -> "центральною" }
        val sideWordPl = when (side) { LaneSide.LEFT -> "лівими"; LaneSide.RIGHT -> "правими"; LaneSide.CENTER -> "центральними" }
        return if (count > 1) "Рухайтеся $count $sideWordPl смугами" else "Рухайтеся $sideWord смугою"
    }

    // expandForSpeech is left as the interface default (identity) - Ukrainian road names are read natively.

    private fun ukNum(x: Double): String {
        val s = if (x == x.toLong().toDouble()) x.toLong().toString() else x.toString()
        return s.replace('.', ',')
    }

    // Ukrainian 3-form plural: one / few (2-4) / many (0, 5-20, …). Non-integers (decimals) take
    // the genitive-singular "few" form - spoken "1,2 кілометра", "5,5 милі".
    // 4th param = the GENITIVE-SINGULAR form for fractional values ("1,2 кілометрА"), the same
    // 4-form shape as plUnit - the old 3-form version returned the nominative-plural `few`
    // ("кілометри") for decimals, which is wrong Ukrainian (audit 2026-07-06; RU/PL had it right).
    private fun ukPlural(x: Double, one: String, few: String, many: String, gsg: String): String {
        if (x != x.toLong().toDouble()) return gsg
        val n = x.toLong()
        val mod100 = (n % 100).toInt()
        val mod10 = (n % 10).toInt()
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }
}

/**
 * Holds the active [NavStrings] for the process. Set explicitly from the resolved app locale on startup
 * and on every language change - do NOT read `Locale.getDefault()` at the leaf, because nav/TTS text is
 * assembled off the main thread. Defaults to [EnNavStrings] so nothing (and no test) depends on the
 * device locale until a language is chosen.
 */
object ZhNavStrings : NavStrings {
    override val locale: Locale = Locale.SIMPLIFIED_CHINESE

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "向左转"
        "right" -> "向右转"
        "slight left" -> "稍向左转"
        "slight right" -> "稍向右转"
        "sharp left" -> "向左急转"
        "sharp right" -> "向右急转"
        "straight" -> "直行"
        "uturn" -> "掉头"
        else -> ""
    }

    private fun sideWord(mod: String?): String = when {
        (mod ?: "").contains("left") -> "靠左"
        (mod ?: "").contains("right") -> "靠右"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val onto = if (road != null) "，进入$road" else ""
        val toward = when {
            dest != null -> "，前往$dest"
            road != null -> "，进入$road"
            else -> ""
        }
        val m = modWord(mod)
        val side = sideWord(mod)
        return when (type) {
            "depart" -> if (road != null) "沿${road}出发" else "开始行程"
            "arrive" -> "到达目的地"
            "turn", "end of road" -> (m.ifBlank { "转弯" }) + onto
            "continue", "new name" -> if (side.isNotBlank() && m != "直行") "${side}行驶$onto" else "继续直行$onto"
            "merge" -> "并入道路$toward"
            "on ramp", "ramp" -> "走匝道$toward"
            "off ramp" -> if (exitNo != null) "从 $exitNo 号出口驶出$toward" else "从出口驶出$toward"
            "fork" -> "在岔路口${side.ifBlank { "直行" }}$toward"
            "roundabout", "rotary", "exit roundabout", "exit rotary" ->
                if (rbExit != null) "进入环岛，从第 $rbExit 个出口驶出$onto" else "进入环岛$onto"
            "roundabout turn" -> "在环岛${m.ifBlank { "转弯" }}$onto"
            "uturn" -> "掉头$onto"
            else -> if (m.isNotBlank()) m + onto else "继续直行$onto"
        }
    }

    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} 英尺"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 英里" else "$miles 英里"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} 米"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 公里" else "$km 公里"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "${distancePhrase}后，$instruction"

    override fun arrived(): String = "您已到达目的地"


    override fun startNav(firstInstruction: String): String = "开始导航。$firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "已到达$label" else "已到达途经点"

    override fun fasterRoute(firstInstruction: String): String = "已切换到更快的路线。$firstInstruction"
    override fun rerouting(): String = "正在重新规划路线"
    override fun fasterRouteAvailable(minutes: Int): String = "发现更快的路线，约可节省 $minutes 分钟"
    override fun stopsNotIncluded(): String = "无法在此路线中包含您的途经点，将继续尝试。"
    override fun destinationAhead(): String = "目的地就在前方"

    override fun voiceTest(): String = "语音导航已开启。400 米后，向右转。"

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "左侧"; LaneSide.RIGHT -> "右侧"; LaneSide.CENTER -> "中间" }
        return if (count > 1) "请走$sideWord $count 条车道" else "请走${sideWord}车道"
    }

    override fun useLanesToDo(side: LaneSide, count: Int, instruction: String): String =
        useLanes(side, count) + "，" + instruction

    // expandForSpeech stays the interface default (identity) — road names are read natively.
}

/**
 * Traditional Chinese, Taiwan wording (issue #55) — 迴轉 not 掉頭-北方 mixes, 公尺 not 米,
 * 圓環 not 环岛. Shares the "zh" spoken-voice pairing with [ZhNavStrings] (a Mandarin voice
 * reads both scripts). Road/dest names pass through untranslated.
 */
object ZhTwNavStrings : NavStrings {
    override val locale: Locale = Locale.TAIWAN

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "向左轉"
        "right" -> "向右轉"
        "slight left" -> "稍向左轉"
        "slight right" -> "稍向右轉"
        "sharp left" -> "向左急轉"
        "sharp right" -> "向右急轉"
        "straight" -> "直行"
        "uturn" -> "迴轉"
        else -> ""
    }

    private fun sideWord(mod: String?): String = when {
        (mod ?: "").contains("left") -> "靠左"
        (mod ?: "").contains("right") -> "靠右"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val onto = if (road != null) "，進入$road" else ""
        val toward = when {
            dest != null -> "，前往$dest"
            road != null -> "，進入$road"
            else -> ""
        }
        val m = modWord(mod)
        val side = sideWord(mod)
        return when (type) {
            "depart" -> if (road != null) "沿${road}出發" else "開始行程"
            "arrive" -> "到達目的地"
            "turn", "end of road" -> (m.ifBlank { "轉彎" }) + onto
            "continue", "new name" -> if (side.isNotBlank() && m != "直行") "${side}行駛$onto" else "繼續直行$onto"
            "merge" -> "匯入道路$toward"
            "on ramp", "ramp" -> "走匝道$toward"
            "off ramp" -> if (exitNo != null) "從 $exitNo 號出口駛出$toward" else "從出口駛出$toward"
            "fork" -> "在岔路口${side.ifBlank { "直行" }}$toward"
            "roundabout", "rotary", "exit roundabout", "exit rotary" ->
                if (rbExit != null) "進入圓環，從第 $rbExit 個出口駛出$onto" else "進入圓環$onto"
            "roundabout turn" -> "在圓環${m.ifBlank { "轉彎" }}$onto"
            "uturn" -> "迴轉$onto"
            else -> if (m.isNotBlank()) m + onto else "繼續直行$onto"
        }
    }

    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} 英尺"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 英里" else "$miles 英里"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} 公尺"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 公里" else "$km 公里"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "${distancePhrase}後，$instruction"

    override fun arrived(): String = "您已到達目的地"


    override fun startNav(firstInstruction: String): String = "開始導航。$firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "已到達$label" else "已到達途經點"

    override fun fasterRoute(firstInstruction: String): String = "已切換到更快的路線。$firstInstruction"
    override fun rerouting(): String = "正在重新規劃路線"
    override fun fasterRouteAvailable(minutes: Int): String = "找到更快的路線，約可節省 $minutes 分鐘"
    override fun stopsNotIncluded(): String = "無法在此路線中包含您的途經點，將繼續嘗試。"
    override fun destinationAhead(): String = "目的地就在前方"

    override fun voiceTest(): String = "語音導航已開啟。400 公尺後，向右轉。"

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "左側"; LaneSide.RIGHT -> "右側"; LaneSide.CENTER -> "中間" }
        return if (count > 1) "請走$sideWord $count 條車道" else "請走${sideWord}車道"
    }

    override fun useLanesToDo(side: LaneSide, count: Int, instruction: String): String =
        useLanes(side, count) + "，" + instruction

    // expandForSpeech stays the interface default (identity) — road names are read natively.
}

/**
 * Japanese — Google-Maps-style guidance ("500 メートル先、左方向です" register, imperative-neutral
 * です/ます). No Piper voice exists for Japanese yet, so spoken guidance routes through the
 * system TTS fallback in VoiceGuide; these strings also feed the banner. Road/dest names pass
 * through untranslated.
 */
object JaNavStrings : NavStrings {
    override val locale: Locale = Locale.JAPAN

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "左"
        "right" -> "右"
        "slight left" -> "斜め左"
        "slight right" -> "斜め右"
        "sharp left" -> "大きく左"
        "sharp right" -> "大きく右"
        "straight" -> "直進"
        "uturn" -> "Uターン"
        else -> ""
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val onto = if (road != null) "、$road に入ります" else ""
        val toward = when {
            dest != null -> "、$dest 方面へ進みます"
            road != null -> "、$road に入ります"
            else -> ""
        }
        val m = modWord(mod)
        return when (type) {
            "depart" -> if (road != null) "$road から出発します" else "ルートを開始します"
            "arrive" -> "目的地に到着"
            "turn", "end of road" -> if (m.isNotBlank() && m != "直進") "${m}に曲がります$onto" else "直進します$onto"
            "continue", "new name" -> if (m.isNotBlank() && m != "直進") "${m}方向に進みます$onto" else if (road != null) "$road を直進します" else "直進します"
            "merge" -> "合流します$toward"
            "on ramp", "ramp" -> "ランプに入ります$toward"
            "off ramp" -> if (exitNo != null) "出口 $exitNo を出ます$toward" else "出口を出ます$toward"
            "fork" -> if (m.isNotBlank()) "分岐を${m}方向へ進みます$toward" else "分岐を直進します$toward"
            "roundabout", "rotary", "exit roundabout", "exit rotary" ->
                if (rbExit != null) "ラウンドアバウトで $rbExit 番目の出口を出ます$onto" else "ラウンドアバウトに入ります$onto"
            "roundabout turn" -> if (m.isNotBlank()) "ラウンドアバウトで${m}に曲がります$onto" else "ラウンドアバウトを直進します$onto"
            "uturn" -> "Uターンします$onto"
            else -> if (m.isNotBlank()) "${m}に曲がります$onto" else "直進します$onto"
        }
    }

    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} フィート"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "1 マイル" else "$miles マイル"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} メートル"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "1 キロメートル" else "$km キロメートル"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "${distancePhrase}先、$instruction"

    override fun arrived(): String = "目的地に到着しました"


    override fun startNav(firstInstruction: String): String = "ナビを開始します。$firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "$label に到着しました" else "経由地に到着しました"

    override fun fasterRoute(firstInstruction: String): String = "より速いルートに切り替えます。$firstInstruction"
    override fun rerouting(): String = "ルートを再検索しています"
    override fun fasterRouteAvailable(minutes: Int): String = "より速いルートが見つかりました。約 $minutes 分短縮できます"
    override fun stopsNotIncluded(): String = "このルートに経由地を含められませんでした。引き続き試します。"
    override fun destinationAhead(): String = "この先に目的地があります"

    override fun voiceTest(): String = "音声案内が有効です。400 メートル先、右方向です。"

    override fun useLanes(side: LaneSide, count: Int): String {
        val sideWord = when (side) { LaneSide.LEFT -> "左側"; LaneSide.RIGHT -> "右側"; LaneSide.CENTER -> "中央" }
        return if (count > 1) "${sideWord}の $count 車線を進んでください" else "${sideWord}の車線を進んでください"
    }

    override fun useLanesToDo(side: LaneSide, count: Int, instruction: String): String =
        useLanes(side, count) + "。" + instruction

    // expandForSpeech stays the interface default (identity) — road names are read natively.
}

/**
 * Hebrew (עברית) — RTL. Spoken/banner nav text uses masculine-singular imperatives, the register
 * Israeli drivers hear from Waze/Google Maps ("פנה ימינה", "המשך ישר"). Road/dest NAMES are DATA and
 * never translated; the templates only decide the word ORDER around them ("פנה שמאלה אל <רחוב>").
 * Metric only in practice, but the imperial branch is kept for parity.
 */
object HeNavStrings : NavStrings {
    override val locale: Locale = Locale("he", "IL")

    private fun modWord(mod: String?): String = when ((mod ?: "").trim().lowercase()) {
        "left" -> "שמאלה"
        "right" -> "ימינה"
        "slight left" -> "קלות שמאלה"
        "slight right" -> "קלות ימינה"
        "sharp left" -> "בחדות שמאלה"
        "sharp right" -> "בחדות ימינה"
        "straight" -> "ישר"
        "uturn" -> "פניית פרסה"
        else -> ""
    }

    // Feminine ordinals for roundabout exits ("ביציאה השלישית"); falls back to "ה-N".
    private fun exitOrdinal(n: Int): String = when (n) {
        1 -> "הראשונה"; 2 -> "השנייה"; 3 -> "השלישית"; 4 -> "הרביעית"
        5 -> "החמישית"; 6 -> "השישית"; 7 -> "השביעית"; 8 -> "השמינית"
        else -> "ה-$n"
    }

    override fun phrase(type: String, mod: String?, road: String?, dest: String?, exitNo: String?, rbExit: Int?): String {
        val onto = if (road != null) " אל $road" else ""
        val toward = when {
            dest != null -> " לכיוון $dest"
            road != null -> " אל $road"
            else -> ""
        }
        val m = modWord(mod)
        return when (type) {
            "depart" -> if (road != null) "צא לדרך על $road" else "התחל את המסלול"
            "arrive" -> "הגעת ליעד"
            "turn", "end of road" -> ("פנה $m").trim() + onto
            "continue", "new name" -> if (m.isNotBlank() && m != "ישר") ("סטה $m").trim() + onto else "המשך$onto"
            "merge" -> "השתלב$toward"
            "on ramp", "ramp" -> "עלה ברמפה$toward"
            "off ramp" -> if (exitNo != null) "צא ביציאה $exitNo$toward" else "צא ביציאה$toward"
            "fork" -> ("היצמד $m").trim() + toward
            "roundabout", "rotary", "exit roundabout", "exit rotary" -> if (rbExit != null) "בכיכר, צא ביציאה ${exitOrdinal(rbExit)}$onto" else "היכנס לכיכר$onto"
            "roundabout turn" -> ("בכיכר, פנה $m").trim() + onto
            "uturn" -> "בצע פניית פרסה$onto"
            else -> if (m.isNotBlank()) ("פנה $m").trim() + onto else "המשך$onto"
        }
    }

    // Israel is metric; the imperial branch is kept for parity. Colloquial nav register keeps the unit
    // singular after a number ("300 מטר", "2 קילומטר"), as Waze/Google Maps Hebrew speak it.
    override fun spokenDistance(meters: Double, imperial: Boolean): String = if (imperial) {
        val feet = meters * 3.28084
        if (feet < 800) "${(if (feet < 100) maxOf(10, (feet / 10).roundToInt() * 10) else (feet / 50).roundToInt() * 50)} רגל"
        else {
            val miles = (meters / 1609.34 * 10).roundToInt() / 10.0
            if (miles == 1.0) "מייל" else "$miles מייל"
        }
    } else {
        if (meters < 950) "${(meters / 10).roundToInt() * 10} מטר"
        else {
            val km = (meters / 100).roundToInt() / 10.0
            if (km == 1.0) "קילומטר" else "$km קילומטר"
        }
    }

    override fun inThen(distancePhrase: String, instruction: String): String = "בעוד $distancePhrase, $instruction"

    override fun arrived(): String = "הגעת"


    override fun startNav(firstInstruction: String): String = "מתחיל ניווט. $firstInstruction"

    override fun reachedStop(label: String): String =
        if (label.isNotBlank()) "הגעת ל$label" else "הגעת לעצירה שלך"

    override fun fasterRoute(firstInstruction: String): String = "עובר למסלול המהיר יותר. $firstInstruction"
    override fun rerouting(): String = "מחשב מסלול מחדש"
    override fun fasterRouteAvailable(minutes: Int): String = "מסלול מהיר יותר זמין, חוסך בערך $minutes דקות"
    override fun stopsNotIncluded(): String = "לא הצלחתי לכלול את העצירות שלך במסלול הזה. אמשיך לנסות."
    override fun destinationAhead(): String = "היעד שלך יהיה לפניך"

    override fun voiceTest(): String = "ההנחיה הקולית מופעלת. פנה ימינה בעוד ארבע מאות מטר."

    override fun useLanes(side: LaneSide, count: Int): String {
        val one = when (side) { LaneSide.LEFT -> "השמאלי"; LaneSide.RIGHT -> "הימני"; LaneSide.CENTER -> "האמצעי" }
        val many = when (side) { LaneSide.LEFT -> "השמאליים"; LaneSide.RIGHT -> "הימניים"; LaneSide.CENTER -> "האמצעיים" }
        return if (count > 1) "השתמש ב-$count הנתיבים $many" else "השתמש בנתיב $one"
    }

    // expandForSpeech is left as the interface default (identity) — Hebrew road names are read natively.
}

object NavStringsRegistry {
    @Volatile
    private var active: NavStrings = EnNavStrings

    fun current(): NavStrings = active

    fun setLocale(locale: Locale) { active = forLanguage(tagOf(locale)) }

    /** Language key for a locale - plain language code, except Chinese where the SCRIPT matters:
     *  Traditional-script regions (TW/HK/MO, or an explicit Hant script) map to "zh-tw". */
    fun tagOf(locale: Locale): String {
        val lang = locale.language.lowercase()
        if (lang != "zh") return lang
        val hant = locale.script.equals("Hant", ignoreCase = true) ||
            locale.country.uppercase() in setOf("TW", "HK", "MO")
        return if (hant) "zh-tw" else "zh"
    }

    /** The NavStrings for a language code ("fr", "en", …); English for anything not yet translated. */
    fun forLanguage(language: String): NavStrings = when (language.lowercase()) {
        "fr" -> FrNavStrings
        "de" -> DeNavStrings
        "es" -> EsNavStrings
        "it" -> ItNavStrings
        "pt" -> PtNavStrings
        "nl" -> NlNavStrings
        "ru" -> RuNavStrings
        "pl" -> PlNavStrings
        "sv" -> SvNavStrings
        "uk" -> UkNavStrings
        "zh" -> ZhNavStrings
        "zh-tw" -> ZhTwNavStrings
        "ja" -> JaNavStrings
        "he", "iw" -> HeNavStrings // JDK 17+ normalizes the old code "iw" -> "he"; accept both
        else -> EnNavStrings
    }
}
