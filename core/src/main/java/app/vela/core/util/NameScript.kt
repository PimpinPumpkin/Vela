package app.vela.core.util

import java.lang.Character.UnicodeScript

/**
 * Which name to show when Google's listing and the map's own label disagree in SCRIPT. A tap on
 * an open place or a basemap label in Israel, Japan or Greece resolves to the Google listing, and
 * Google answers with the local-script name even under `hl=en`, so an English app titled the
 * sheet "מוזיאון האשליות" over a pin the map had labeled "Museum Of Illusions Tel Aviv" (user
 * 2026-09-15). Rule: prefer the candidate whose letters are in the script the app language is
 * written in; when Google's name is in another script and the map's label is in the app's,
 * keep the label. When neither matches (a Greek app in Israel), Google's name stands, as before.
 */
object NameScript {
    /** The script an app language is written in; null for a language this does not know. */
    fun scriptOf(language: String): UnicodeScript? = when (language.lowercase().substringBefore('-').substringBefore('_')) {
        "en", "fr", "de", "es", "it", "pt", "nl", "pl", "sv", "hu", "cs", "da", "fi", "no", "nb", "tr", "ro", "id", "ms", "vi", "tl" -> UnicodeScript.LATIN
        "ru", "uk", "bg", "sr", "mk", "be", "kk" -> UnicodeScript.CYRILLIC
        "he", "iw" -> UnicodeScript.HEBREW
        "ar", "fa", "ur" -> UnicodeScript.ARABIC
        "el" -> UnicodeScript.GREEK
        "ja" -> UnicodeScript.HIRAGANA
        "zh" -> UnicodeScript.HAN
        "ko" -> UnicodeScript.HANGUL
        "th" -> UnicodeScript.THAI
        "hi", "mr", "ne" -> UnicodeScript.DEVANAGARI
        else -> null
    }

    /** True when most of [s]'s letters are written in [script]; Japanese counts kana and kanji. */
    fun isIn(s: String, script: UnicodeScript): Boolean {
        var hit = 0; var letters = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i); i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            letters++
            val sc = runCatching { UnicodeScript.of(cp) }.getOrNull() ?: continue
            val match = when (script) {
                UnicodeScript.HIRAGANA -> sc == UnicodeScript.HIRAGANA || sc == UnicodeScript.KATAKANA || sc == UnicodeScript.HAN
                else -> sc == script
            }
            if (match) hit++
        }
        return letters > 0 && hit * 2 > letters
    }

    /** [google] unless it is in another script than the app's while [label] is in the app's. */
    fun prefer(language: String, google: String, label: String?): String {
        if (label.isNullOrBlank()) return google
        val script = scriptOf(language) ?: return google
        return if (!isIn(google, script) && isIn(label, script)) label else google
    }
}
