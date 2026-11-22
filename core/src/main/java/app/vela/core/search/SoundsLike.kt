package app.vela.core.search

import app.vela.core.data.OfflineRank

/**
 * A second reading of a dictated query that found nothing (issue #798). On-device dictation writes
 * a street name the way it heard it, often split in two and spelled by sound ("Kur fürsten damm",
 * "Friedrich strasse"). [rewrite] puts the name of a nearby street that sounds the same in place
 * of the words that sound like it. Two spellings sound the same when their Kölner Phonetik codes
 * ([code]) are equal.
 *
 * Kölner Phonetik was built for German. Its classes (b p, d t, f v w, g k q, l, m n, r, s z, the
 * vowels dropped) fit the other Latin-script languages too, so it is used for every app language
 * in [LANGS]. The others write in another script and get no rewrite.
 */
object SoundsLike {
    /** Shortest code a street may have. "Damm" is 26 and "Horst" 782; a code that short matches
     *  half the street names in a town. */
    const val MIN_CODE = 5

    /** Fewest letters a street name may have. */
    const val MIN_LETTERS = 6

    /** Most adjacent words joined into one name: a compound heard as up to three words. The whole
     *  transcript is tried as well. */
    const val MAX_SPAN = 3

    /** When two streets near the center share the heard words' code, the one closer in spelling
     *  wins only by this many edits, or by being the heard letters exactly. One edit is what
     *  separates one real name from another ("Bachstraße", "Buchstraße"). */
    const val CLEAR_MARGIN = 2

    /** App languages whose dictation comes back in Latin script. */
    val LANGS = setOf(
        "de", "en", "es", "et", "fr", "hu", "id", "in", "is", "it", "kmr", "mt", "nb", "nl", "nn", "no",
        "pl", "pt", "ro", "sk", "sl", "sv", "tr",
    )

    private val SPACE = Regex("\\s+")

    /**
     * [transcript] with the words that sound like one of [streets] replaced by that street's name,
     * or null when nothing qualifies. Words are tried alone, in runs of two and three, and all
     * together, with the spaces between them removed. A word with a digit (a house number) is never
     * part of a run and stays where it was, as does every word outside the run.
     *
     * A run qualifies when its code equals a street's code of at least [MIN_CODE] digits, the
     * street has at least [MIN_LETTERS] letters, the run is not already that street's name, and
     * the street is the only one with that code or clearly the closest in spelling
     * ([CLEAR_MARGIN]). The run that covers the most letters wins; a tie between two different
     * rewrites gives null.
     *
     * [whole] asks for the stricter reading used before the first search: the run must take in
     * every word but house numbers, so "Kur fürsten damm 21" qualifies and "Pizza Kur fürsten
     * damm" does not.
     */
    fun rewrite(transcript: String, lang: String, streets: Collection<String>, whole: Boolean = false): String? {
        if (lang.lowercase() !in LANGS) return null
        val words = transcript.trim().split(SPACE).filter { it.isNotEmpty() }
        if (words.isEmpty() || streets.isEmpty()) return null
        val byCode = HashMap<String, MutableList<Street>>()
        val seen = HashSet<String>()
        for (raw in streets) {
            val name = raw.trim().replace(SPACE, " ")
            val letters = latinLetters(name) ?: continue
            if (letters.length < MIN_LETTERS || !seen.add(letters)) continue
            val c = codeOfLetters(letters)
            if (c.length < MIN_CODE) continue
            byCode.getOrPut(c) { ArrayList() } += Street(name, letters, plain(name))
        }
        if (byCode.isEmpty()) return null
        val wordLetters = words.map { w -> if (w.any(Char::isDigit)) null else latinLetters(w) }

        val runs = ArrayList<IntRange>()
        for (start in words.indices) {
            for (len in 1..MAX_SPAN) if (start + len <= words.size) runs += start until start + len
        }
        if (words.size > MAX_SPAN) runs += words.indices
        var best: Found? = null
        var tied = false
        val wordsIdx = words.indices.filter { wordLetters[it] != null }
        for (run in runs) {
            if (whole && wordsIdx.any { it !in run }) continue
            val parts = run.map { wordLetters[it] ?: "" }
            if (parts.any { it.isEmpty() }) continue
            val joined = parts.joinToString("")
            val same = byCode[codeOfLetters(joined)] ?: continue
            val heard = plain(run.joinToString(" ") { words[it] })
            // The run already is a street's name: the search missed for another reason.
            if (same.any { it.plain == heard }) continue
            val maxEdits = maxOf(2, joined.length / 3)
            val ranked = same.map { it to edits(joined, it.letters) }.filter { it.second <= maxEdits }.sortedBy { it.second }
            if (ranked.isEmpty()) continue
            val clear = ranked.size == 1 || (ranked[0].second == 0 && ranked[1].second > 0) ||
                ranked[0].second + CLEAR_MARGIN <= ranked[1].second
            val found = Found(run, if (clear) ranked[0].first else null, joined.length)
            val b = best
            when {
                b == null || found.covered > b.covered -> { best = found; tied = false }
                found.covered == b.covered && (found.street == null || found.street.name != b.street?.name) -> tied = true
            }
        }
        val win = best ?: return null
        val street = win.street ?: return null
        if (tied) return null
        return (words.subList(0, win.run.first) + street.name + words.subList(win.run.last + 1, words.size)).joinToString(" ")
    }

    /** The Kölner Phonetik code of [text]'s Latin letters, accents folded. Empty when it has none. */
    fun code(text: String): String = codeOfLetters(latinLetters(text) ?: "")

    private class Street(val name: String, val letters: String, val plain: String)
    private class Found(val run: IntRange, val street: Street?, val covered: Int)

    /** [s] folded to plain a to z with everything else dropped, or null when it holds a letter of
     *  another script. */
    private fun latinLetters(s: String): String? {
        val f = OfflineRank.fold(s)
        val out = StringBuilder(f.length)
        for (ch in f) {
            if (ch in 'a'..'z') out.append(ch) else if (Character.isLetter(ch)) return null
        }
        return out.toString().ifEmpty { null }
    }

    /** Folded, punctuation dropped, single spaces: two spellings of one name compare equal. */
    private fun plain(s: String): String =
        OfflineRank.fold(s).filter { it.isLetterOrDigit() || it == ' ' }.replace(SPACE, " ").trim()

    /**
     * Kölner Phonetik over lowercase a to z: each letter gets a digit by its neighbors, runs of
     * one digit collapse to one, and every 0 but a leading one is dropped.
     */
    private fun codeOfLetters(s: String): String {
        val raw = StringBuilder(s.length + 2)
        for (i in s.indices) {
            val c = s[i]
            val prev = if (i > 0) s[i - 1] else ' '
            val next = if (i + 1 < s.length) s[i + 1] else ' '
            when (c) {
                'a', 'e', 'i', 'j', 'o', 'u', 'y' -> raw.append('0')
                'h' -> Unit
                'b' -> raw.append('1')
                'p' -> raw.append(if (next == 'h') '3' else '1')
                'd', 't' -> raw.append(if (next == 'c' || next == 's' || next == 'z') '8' else '2')
                'f', 'v', 'w' -> raw.append('3')
                'g', 'k', 'q' -> raw.append('4')
                'c' -> raw.append(
                    when {
                        i == 0 -> if (next in "ahkloqrux") '4' else '8'
                        prev == 's' || prev == 'z' -> '8'
                        next in "ahkoqux" -> '4'
                        else -> '8'
                    },
                )
                'x' -> if (prev == 'c' || prev == 'k' || prev == 'q') raw.append('8') else raw.append("48")
                'l' -> raw.append('5')
                'm', 'n' -> raw.append('6')
                'r' -> raw.append('7')
                's', 'z' -> raw.append('8')
            }
        }
        val out = StringBuilder(raw.length)
        var last = ' '
        for ((i, d) in raw.withIndex()) {
            if (d == last) continue
            last = d
            if (d != '0' || i == 0) out.append(d)
        }
        return out.toString()
    }

    /** Levenshtein distance. */
    private fun edits(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val sub = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
