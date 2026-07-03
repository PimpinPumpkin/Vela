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

    /**
     * Split [text] at CLAUSE boundaries — a comma or semicolon followed by whitespace — so a neural
     * synth can splice a short beat there (Piper reads straight through commas otherwise, running
     * "In a quarter mile, turn right" together). Requires the space, so a grouped number like "1,000"
     * (comma-digit, no space) is never split. A `;` is always a break; a `,` is too (nav commas are
     * clause boundaries). Single-fragment inputs return unchanged.
     */
    fun splitClauses(text: String): List<String> {
        if (text.length < 2) return listOf(text)
        val cuts = CLAUSE_BREAK.findAll(text).map { it.range.last + 1 }.toList()
        if (cuts.isEmpty()) return listOf(text)
        val parts = ArrayList<String>(cuts.size + 1)
        var start = 0
        for (c in cuts) { parts.add(text.substring(start, c).trim()); start = c }
        parts.add(text.substring(start).trim())
        return parts.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }

    /**
     * The speakable fragments of [text], each tagged with the SILENCE (seconds) to splice AFTER it:
     * a strong [sentenceGap] at sentence ends (`. ! ?`), a shorter [clauseGap] at commas/semicolons,
     * and 0 after the final fragment. Lets the neural voice phrase a prompt naturally ("In a quarter
     * mile, ‹beat› turn right onto Main Street. ‹beat› Then …") in one pass. Falls back to the whole
     * text with no gap when nothing splits (so single-clause prompts pay no cost).
     */
    fun speechFragments(text: String, sentenceGap: Float, clauseGap: Float): List<Pair<String, Float>> {
        val sentences = splitSentences(text)
        val out = ArrayList<Pair<String, Float>>()
        for ((si, sentence) in sentences.withIndex()) {
            val clauses = splitClauses(sentence)
            for ((ci, clause) in clauses.withIndex()) {
                if (clause.isBlank()) continue
                val gap = when {
                    ci != clauses.lastIndex -> clauseGap // between clauses of the same sentence
                    si != sentences.lastIndex -> sentenceGap // between sentences
                    else -> 0f // the very end
                }
                out.add(clause to gap)
            }
        }
        return out.ifEmpty { listOf(text to 0f) }
    }

    /**
     * Read 3-digit street ordinals the way people (and Google's voice) say them — "128th" → "one
     * twenty-eighth", not "one hundred and twenty-eighth", which the neural G2P mangles into a stuttery
     * "one, hundred and 28th". Only touches 3-digit ordinals (100-999); espeak reads 1-2 digit ordinals
     * ("5th", "42nd") fine on its own, and 4-digit+ are left alone (rare in street names, and the
     * hundreds-word convention breaks down). Whole-number only (won't touch "1.28th").
     */
    fun spokenNumbers(text: String): String =
        STREET_ORDINAL.replace(text) { m ->
            val h = m.groupValues[1].toInt()
            val r = m.groupValues[2].toInt()
            when {
                r == 0 -> "${CARD[h]} hundredth"      // 100th → "one hundredth"
                r < 10 -> "${CARD[h]} oh ${ORD1[r]}"  // 105th → "one oh fifth"
                else -> "${CARD[h]} ${twoDigitOrdinal(r)}" // 128th → "one twenty-eighth"
            }
        }

    private fun twoDigitOrdinal(r: Int): String = when {
        r in 10..19 -> TEEN_ORD[r - 10]
        r % 10 == 0 -> TENS_ORD[r / 10]
        else -> "${TENS_CARD[r / 10]}-${ORD1[r % 10]}"
    }

    private val STREET_ORDINAL = Regex("\\b([1-9])(\\d\\d)(?:st|nd|rd|th)\\b")
    private val CARD = arrayOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val ORD1 = arrayOf("", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
    private val TEEN_ORD = arrayOf(
        "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth",
        "fifteenth", "sixteenth", "seventeenth", "eighteenth", "nineteenth",
    )
    private val TENS_ORD = arrayOf(
        "", "", "twentieth", "thirtieth", "fortieth",
        "fiftieth", "sixtieth", "seventieth", "eightieth", "ninetieth",
    )
    private val TENS_CARD = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
    )

    // Terminal punctuation followed by whitespace (so "0.5 mi" and a trailing "." don't match).
    private val SENTENCE_BREAK = Regex("[.!?]+\\s+")

    // Comma/semicolon followed by whitespace — a clause beat. The required space means a grouped
    // number ("1,000") is never split (comma-digit has no space).
    private val CLAUSE_BREAK = Regex("[,;]\\s+")

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
