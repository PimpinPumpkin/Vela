package app.vela.core.voice

import android.icu.text.Transliterator

/**
 * Romanize foreign-script names in a SPOKEN nav string so a Latin-script voice can actually say
 * them, the way Google reads "Turn onto Rehov Herzl" for an English driver in Israel (issue #184).
 *
 * The problem: nav guidance is generated in the app language (say English), but the road NAME comes
 * from the map in its local script (Hebrew "רחוב הרצל"). A single-language Piper/English voice has no
 * phonemes for those glyphs, so it drops the name entirely, exactly the words the driver needs.
 *
 * This transliterates only the FOREIGN-letter runs to Latin (ICU "Any-Latin; Latin-ASCII"), leaving
 * existing Latin text, its accents, digits and punctuation untouched, so a French "Rue de Rivoli" or
 * an English "5th Ave" is never mangled. It runs on the spoken string ONLY; the on-screen banner
 * keeps the real local-script name (that is what matches the street sign).
 *
 * Scope: applied only when the speaking voice is Latin-script (a Hebrew/Russian/Greek voice
 * pronounces its own script natively, so it is skipped). CJK ideographs and kana are deliberately
 * NOT romanized: ICU reads Han as Chinese pinyin ("明治通り" becomes "ming zhitongri", not
 * "Meiji-dori"), a confidently-wrong reading. Those are left to the native CJK voices Vela ships;
 * a proper kanji->romaji step is a possible follow-up.
 */
object SpokenScript {

    /** Voice languages whose own script is NOT Latin: they read their native script directly, so we
     *  never romanize for them. (Anything not here is treated as Latin-script and triggers romanizing
     *  of foreign runs.) Legacy "iw" kept alongside "he" for Hebrew, same as the status tables. */
    private val NON_LATIN_VOICE_LANGS =
        setOf("he", "iw", "ar", "fa", "ur", "ru", "uk", "bg", "sr", "mk", "el", "zh", "ja", "ko", "th", "hi")

    // Transliterator instances are not thread-safe; nav prompts fire ~once per maneuver on the main
    // thread, so a lazily-built, synchronized single instance is plenty (and avoids per-call setup).
    private val latinizer: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
    }

    /** Romanize foreign-script runs of [text] iff [voiceLang] is a Latin-script voice. No-op (returns
     *  [text] unchanged) for a non-Latin voice, an all-Latin string, or if ICU is unavailable. */
    fun forVoice(text: String, voiceLang: String?): String {
        val tl = latinizer ?: return text
        return forVoice(text, voiceLang) { run ->
            synchronized(tl) { runCatching { tl.transliterate(run) }.getOrDefault(run) }
        }
    }

    /** Testable core: the ICU call is injected as [romanize] so the run-splitting, voice gating and
     *  CJK/Latin preservation can be unit-tested on the JVM (android.icu is unavailable there). */
    internal fun forVoice(text: String, voiceLang: String?, romanize: (String) -> String): String {
        val lang = voiceLang?.lowercase()?.substringBefore('-')?.substringBefore('_')
        if (lang != null && lang in NON_LATIN_VOICE_LANGS) return text
        if (text.none { needsRomanizing(it) }) return text

        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            if (needsRomanizing(text[i])) {
                val start = i
                while (i < n && needsRomanizing(text[i])) i++
                out.append(romanize(text.substring(start, i)))
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** A character that a Latin voice can't pronounce and ICU can romanize well: a letter whose script
     *  is neither Latin nor a CJK ideograph/kana (those ICU mis-reads, see the class note). */
    private fun needsRomanizing(c: Char): Boolean {
        if (!Character.isLetter(c)) return false
        return when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.LATIN,
            Character.UnicodeScript.COMMON,
            Character.UnicodeScript.INHERITED,
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            -> false
            else -> true
        }
    }
}
