package app.vela.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The run-splitting, voice gating and CJK/Latin preservation of [SpokenScript] (issue #184). The
 * real ICU romanization is Android-only, so it is injected here as a tag so we can assert exactly
 * which substrings would be sent to it, and that everything else is passed through untouched. Runs
 * break at spaces/punctuation, so a multi-word foreign name tags per word - the real ICU output is
 * identical either way ("רחוב"+" "+"הרצל" romanizes to the same string as the whole phrase).
 */
class SpokenScriptTest {

    // Wrap each romanized run in <> so the test can see precisely what was handed to ICU.
    private fun tag(text: String, voiceLang: String?) =
        SpokenScript.forVoice(text, voiceLang) { "<$it>" }

    @Test fun `latin voice romanizes a hebrew name and keeps the english around it`() {
        assertEquals("Turn right onto <רחוב> <הרצל>", tag("Turn right onto רחוב הרצל", "en"))
    }

    @Test fun `a voice keeps its OWN script but romanizes scripts it cannot read`() {
        // Own-script text is left alone: a Hebrew voice reads Hebrew, a Russian voice reads Cyrillic.
        val heb = "פנה ימינה לרחוב הרצל"
        assertEquals(heb, tag(heb, "he"))
        assertEquals(heb, tag(heb, "iw"))
        assertEquals("Поверните на Тверскую", tag("Поверните на Тверскую", "ru"))
        // But a foreign script IS romanized even for a non-Latin voice - Latin is the universal
        // fallback (a Chinese/Russian driver in Israel gets Latin, not dropped Hebrew). issue #184.
        assertEquals("onto <רחוב> <הרצל>", tag("onto רחוב הרצל", "zh"))
        assertEquals("на <רחוב>", tag("на רחוב", "ru")) // Cyrillic kept, Hebrew romanized
    }

    @Test fun `a chinese voice keeps Han but romanizes hebrew in the same string`() {
        // Han is never romanized (would become pinyin), so a zh voice reads it; the Hebrew is Latinized.
        assertEquals("往 明治通り <רחוב>", tag("往 明治通り רחוב", "zh"))
    }

    @Test fun `pure english is returned unchanged - ICU never invoked`() {
        assertEquals("Turn right onto 5th Ave", tag("Turn right onto 5th Ave", "en"))
    }

    @Test fun `french accents survive - only the foreign run is touched`() {
        assertEquals("onto Champs-Élysées", tag("onto Champs-Élysées", "fr"))
        assertEquals("onto Champs then <דיזנגוף>", tag("onto Champs then דיזנגוף", "en"))
    }

    @Test fun `CJK is left for native voices, not mis-romanized`() {
        assertEquals("Turn onto 明治通り", tag("Turn onto 明治通り", "en"))
    }

    @Test fun `greek and cyrillic runs romanize under a latin voice`() {
        assertEquals("onto <Λεωφόρος> <Κηφισίας>", tag("onto Λεωφόρος Κηφισίας", "en"))
        assertEquals("onto <Тверская> <улица>", tag("onto Тверская улица", "de"))
    }

    @Test fun `region-tagged voice code is handled`() {
        assertEquals("onto <רחוב>", tag("onto רחוב", "en-US"))
        assertEquals("onto רחוב", tag("onto רחוב", "he-IL"))
    }

    @Test fun `separators between foreign words pass through`() {
        assertEquals("<שדרות>, <בן> <גוריון>", tag("שדרות, בן גוריון", "en"))
    }

    // --- Real romanized names from the basemap (dict) ---
    private val dict = mapOf("רחוב הרצל" to "Rehov Herzl")

    @Test fun `voice uses the real name from the dict, ICU only for the rest`() {
        // The dict name speaks properly; an unmapped foreign name still falls back to ICU (tagged).
        assertEquals(
            "Turn onto Rehov Herzl then <דיזנגוף>",
            SpokenScript.forVoice("Turn onto רחוב הרצל then דיזנגוף", "en", dict) { "<$it>" },
        )
    }

    @Test fun `display uses the real name but keeps local script when the dict has none`() {
        // No ICU on display: an unmapped name stays in Hebrew (a skeleton on a sign reads broken).
        assertEquals("Turn onto Rehov Herzl", SpokenScript.forDisplay("Turn onto רחוב הרצל", "en", dict))
        assertEquals("Turn onto דיזנגוף", SpokenScript.forDisplay("Turn onto דיזנגוף", "en", dict))
    }

    @Test fun `a hebrew UI keeps hebrew even when the dict has a latin name`() {
        assertEquals("פנה אל רחוב הרצל", SpokenScript.forDisplay("פנה אל רחוב הרצל", "he", dict))
    }

    // The dict path (issue #251 stall, 2026-09-03): the road-name table can hold a whole region's
    // names, and the banner applies it twice per GPS fix on the main thread.
    @Test fun `display dict swaps a local name for its latin form, longest name first`() {
        val dict = mapOf("רחוב" to "Rehov", "רחוב הרצל" to "Rehov Herzl")
        assertEquals("Turn right onto Rehov Herzl", SpokenScript.forDisplay("Turn right onto רחוב הרצל", "en", dict))
    }

    @Test fun `plain-script text returns as-is without touching a big dict`() {
        // Every matchable entry carries a foreign-script character, so text without one can never
        // match: it must come back identical (and, by construction, without a sort of the table).
        val big = HashMap<String, String>()
        for (i in 0 until 200_000) big["רחוב $i"] = "Rehov $i"
        val text = "Turn right onto Elm Street"
        assertEquals(text, SpokenScript.forDisplay(text, "en", big))
        val t0 = System.nanoTime()
        repeat(200) { SpokenScript.forDisplay(text, "en", big) }
        val perCallMs = (System.nanoTime() - t0) / 200 / 1e6
        org.junit.Assert.assertTrue("plain text took ${perCallMs} ms per call", perCallMs < 1.0)
    }

    @Test fun `a replaced dict instance is honoured, not the cached digest of the old one`() {
        val a = mapOf("רחוב" to "Rehov")
        val b = mapOf("רחוב" to "Street")
        assertEquals("onto Rehov", SpokenScript.forDisplay("onto רחוב", "en", a))
        assertEquals("onto Street", SpokenScript.forDisplay("onto רחוב", "en", b))
        assertEquals("onto Rehov", SpokenScript.forDisplay("onto רחוב", "en", a))
        // A reader of the name's own script keeps it, whatever the dict says.
        assertEquals("onto רחוב", SpokenScript.forDisplay("onto רחוב", "he", b))
    }
}
