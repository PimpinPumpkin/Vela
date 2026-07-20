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
 * This transliterates only the runs a given voice CAN'T pronounce to Latin (ICU
 * "Any-Latin; Latin-ASCII"), leaving existing Latin text, its accents, digits and punctuation
 * untouched, so a French "Rue de Rivoli" or an English "5th Ave" is never mangled. It runs on the
 * spoken string ONLY; the on-screen banner keeps the real local-script name (that is what matches
 * the street sign).
 *
 * Scope: Latin is the universal fallback the way Google romanizes foreign signs for EVERY user, not
 * just Latin-script ones (user 2026-07-19: a Chinese driver in Israel would rather hear the streets
 * in Latin letters than have the Hebrew dropped). So a run is romanized whenever it is in a script
 * the SPEAKING voice can't read: a Hebrew run is romanized for an English, Russian or Chinese voice
 * alike; a Russian voice keeps Cyrillic (its own script) but still romanizes Hebrew. Only the voice's
 * OWN script is left in place. CJK ideographs and kana are the one exception, NEVER romanized for any
 * voice: ICU reads Han as Chinese pinyin ("明治通り" becomes "ming zhitongri", not "Meiji-dori"), a
 * confidently-wrong reading, so kanji/kana are left to the native CJK voices Vela ships.
 */
object SpokenScript {

    /** A voice language's own script (the one it pronounces natively, so we never romanize it). Any
     *  language not listed reads Latin, which is also the fallback ICU romanizes everything else into.
     *  Legacy "iw" kept alongside "he" for Hebrew, same as the status tables. */
    private val VOICE_SCRIPT: Map<String, Character.UnicodeScript> = mapOf(
        "he" to Character.UnicodeScript.HEBREW, "iw" to Character.UnicodeScript.HEBREW,
        "ar" to Character.UnicodeScript.ARABIC, "fa" to Character.UnicodeScript.ARABIC,
        "ur" to Character.UnicodeScript.ARABIC,
        "ru" to Character.UnicodeScript.CYRILLIC, "uk" to Character.UnicodeScript.CYRILLIC,
        "bg" to Character.UnicodeScript.CYRILLIC, "sr" to Character.UnicodeScript.CYRILLIC,
        "mk" to Character.UnicodeScript.CYRILLIC,
        "el" to Character.UnicodeScript.GREEK,
        "th" to Character.UnicodeScript.THAI,
        "hi" to Character.UnicodeScript.DEVANAGARI,
        "ko" to Character.UnicodeScript.HANGUL,
        // zh/ja read Han (kana is always left alone below), so their own script is HAN.
        "zh" to Character.UnicodeScript.HAN, "ja" to Character.UnicodeScript.HAN,
    )

    // Transliterator instances are not thread-safe; nav prompts fire ~once per maneuver on the main
    // thread, so a lazily-built, synchronized single instance is plenty (and avoids per-call setup).
    private val latinizer: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
    }

    /** Romanize the runs of [text] that the [voiceLang] voice can't pronounce. No-op (returns [text]
     *  unchanged) when every run is in the voice's own script or Latin, or if ICU is unavailable. */
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
        val voiceScript = VOICE_SCRIPT[lang] ?: Character.UnicodeScript.LATIN
        if (text.none { needsRomanizing(it, voiceScript) }) return text

        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            if (needsRomanizing(text[i], voiceScript)) {
                val start = i
                while (i < n && needsRomanizing(text[i], voiceScript)) i++
                out.append(romanize(text.substring(start, i)))
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    /** A letter the [voiceScript] voice can't pronounce and ICU can romanize well: its script is
     *  neither the voice's own, nor Latin, nor a CJK ideograph/kana (those ICU mis-reads as pinyin,
     *  so they are left to a native CJK voice regardless of who is speaking; see the class note). */
    private fun needsRomanizing(c: Char, voiceScript: Character.UnicodeScript): Boolean {
        if (!Character.isLetter(c)) return false
        val script = Character.UnicodeScript.of(c.code)
        if (script == voiceScript) return false // the voice reads its own script natively
        return when (script) {
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
