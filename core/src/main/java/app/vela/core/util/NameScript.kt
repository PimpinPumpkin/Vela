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

    /** The language a NAME's script implies, as Google's `hl` code, or null when the script does
     *  not say (Latin, or one this does not know). Kana is Japanese wherever it is; Han alone is
     *  Japanese inside Japan and Chinese elsewhere (Traditional over Taiwan, Hong Kong and Macau),
     *  so the place's position decides. Hebrew is `iw`, the code Android and Google both take. */
    fun scriptLanguage(name: String, lat: Double, lng: Double): String? {
        val counts = HashMap<UnicodeScript, Int>()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i); i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            val sc = runCatching { UnicodeScript.of(cp) }.getOrNull() ?: continue
            counts[sc] = (counts[sc] ?: 0) + 1
        }
        if ((counts[UnicodeScript.HIRAGANA] ?: 0) + (counts[UnicodeScript.KATAKANA] ?: 0) > 0) return "ja"
        val top = counts.maxByOrNull { it.value }?.key ?: return null
        return when (top) {
            UnicodeScript.HAN -> when {
                lat in 24.0..46.0 && lng in 122.5..146.5 -> "ja"
                lat in 21.5..25.5 && lng in 119.5..122.5 -> "zh-TW"
                lat in 22.1..22.6 && lng in 113.5..114.5 -> "zh-TW"
                else -> "zh-CN"
            }
            UnicodeScript.HANGUL -> "ko"
            UnicodeScript.CYRILLIC -> "ru"
            UnicodeScript.HEBREW -> "iw"
            UnicodeScript.THAI -> "th"
            UnicodeScript.ARABIC -> "ar"
            UnicodeScript.GREEK -> "el"
            else -> null
        }
    }

    /** True when [hl] names the app's own language [uiLang] (Hebrew is `iw` on Android and `he`
     *  elsewhere; region and script tags do not count). */
    fun sameLanguage(hl: String, uiLang: String): Boolean {
        fun base(s: String) = s.lowercase().substringBefore('-').substringBefore('_').let { if (it == "he") "iw" else it }
        return base(hl) == base(uiLang)
    }

    /** [google] unless it is in another script than the app's while [label] is in the app's. */
    fun prefer(language: String, google: String, label: String?): String {
        if (label.isNullOrBlank()) return google
        val script = scriptOf(language) ?: return google
        return if (!isIn(google, script) && isIn(label, script)) label else google
    }
}
