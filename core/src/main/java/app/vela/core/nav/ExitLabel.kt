package app.vela.core.nav

/**
 * The exit NUMBER out of a maneuver's instruction ("Take exit 12B toward ...", "Ausfahrt 7 Richtung
 * ..."), for the green callout the map draws on the exit you are taking. Just the number, the way
 * the shields on the road say it; the banner already carries the words.
 *
 * The routers phrase the instruction in the app language, so the word before the number is matched
 * from a small table rather than by parsing grammar. No word, no callout: a bare number in an
 * instruction is more often a road ref than an exit.
 */
object ExitLabel {
    private val WORDS = listOf(
        "exit", "sortie", "ausfahrt", "abfahrt", "salida", "uscita", "saída", "saida", "afrit", "afslag",
        "выход", "съезд", "виїзд", "з'їзд", "zjazd", "wyjazd", "avfart", "kijárat", "יציאה", "出口", "出口ランプ",
    )
    private val NUM = "([0-9]{1,3}[A-Za-z]?(?:\\s*[-–/]\\s*[0-9]{1,3}[A-Za-z]?)?)"
    private val PATTERNS = WORDS.map { w -> Regex("(?i)" + Regex.escape(w) + "[\\s:\u00a0]*" + NUM) }
    // Chinese and Japanese put the number BEFORE the word ("12B出口"), with no space.
    private val SUFFIX = Regex("(?i)" + NUM + "\\s*(出口|出口ランプ)")

    /** The exit number in [instruction], or null when it names no numbered exit. */
    fun of(instruction: String?): String? {
        val s = instruction?.trim().orEmpty()
        if (s.isEmpty()) return null
        for (p in PATTERNS) p.find(s)?.let { return clean(it.groupValues[1]) }
        SUFFIX.find(s)?.let { return clean(it.groupValues[1]) }
        return null
    }

    private fun clean(raw: String) = raw.replace(Regex("\\s*([-–/])\\s*"), "$1").uppercase().take(12)
}
