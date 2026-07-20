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

    @Test fun `a hebrew or other non-latin voice is never romanized`() {
        val heb = "פנה ימינה לרחוב הרצל"
        assertEquals(heb, tag(heb, "he"))
        assertEquals(heb, tag(heb, "iw"))
        assertEquals("Поверните на Тверскую", tag("Поверните на Тверскую", "ru"))
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
}
