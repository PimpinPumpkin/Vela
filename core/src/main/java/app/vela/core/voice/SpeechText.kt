package app.vela.core.voice

/**
 * Pure text helpers for the spoken-guidance path (kept in :core so they're unit-tested and reusable
 * across synths). No Android dependencies.
 */
object SpeechText {

    /**
     * Split [text] into sentences at a terminal ". "/"! "/"? ", but ONLY at a *real* sentence break —
     * never inside a name — so a neural synth can splice a pause between them at genuine periods.
     *
     * A period counts as a break only when the word before it is not an abbreviation / road-type /
     * directional word (`Jr.`, `Mt.`, `St.`, `Blvd.`, `N.`, and — because [VoiceGuide.forSpeech]
     * expands `St.`→`Street.`, `N.`→`North.` — the spelled-out forms too) AND the next clause starts
     * with a capital or digit. So `"Martin Luther King Jr. Boulevard"` stays whole while
     * `"…onto Main Street. Then merge…"` splits. Decimals (`"0.5 mi"`) and a trailing period never
     * split. Returns a single-element list when nothing qualifies, so single-sentence prompts are
     * untouched (and callers pay no extra synthesis cost).
     */
    fun splitSentences(text: String): List<String> {
        if (text.length < 2) return listOf(text)
        val cuts = ArrayList<Int>()
        for (m in SENTENCE_BREAK.findAll(text)) {
            val punct = m.range.first
            var j = punct - 1
            while (j >= 0 && text[j].isLetterOrDigit()) j--
            val word = text.substring(j + 1, punct)
            val nextStart = m.range.last + 1
            val next = text.getOrNull(nextStart) ?: continue
            if (word.length > 1 && word.lowercase() !in NO_SPLIT_BEFORE && (next.isUpperCase() || next.isDigit())) {
                cuts.add(nextStart)
            }
        }
        if (cuts.isEmpty()) return listOf(text)
        val parts = ArrayList<String>(cuts.size + 1)
        var start = 0
        for (c in cuts) { parts.add(text.substring(start, c).trim()); start = c }
        parts.add(text.substring(start).trim())
        return parts.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }

    // Terminal punctuation followed by whitespace (so "0.5 mi" and a trailing "." don't match).
    private val SENTENCE_BREAK = Regex("[.!?]+\\s+")

    // A period after one of these is an abbreviation/name dot, not a sentence end — don't split on it.
    // Road-type + directional + title abbreviations AND the words forSpeech expands them into.
    private val NO_SPLIT_BEFORE = setOf(
        "st", "ave", "av", "blvd", "rd", "dr", "ln", "ct", "pl", "ter", "cir", "sq", "trl",
        "hwy", "pkwy", "fwy", "expy", "pt", "ft", "mt", "jr", "sr", "mr", "mrs", "ms", "no", "vs",
        "n", "s", "e", "w", "ne", "nw", "se", "sw",
        "street", "avenue", "boulevard", "road", "drive", "lane", "court", "place", "terrace",
        "circle", "square", "trail", "highway", "parkway", "freeway", "expressway", "way", "alley",
        "plaza", "path", "walk", "row", "loop", "crossing", "point", "bend", "pass",
        "north", "south", "east", "west", "northeast", "northwest", "southeast", "southwest",
        "saint", "mount", "junior", "senior",
    )
}
