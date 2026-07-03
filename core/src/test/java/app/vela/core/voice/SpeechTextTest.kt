package app.vela.core.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {

    private fun split(t: String) = SpeechText.splitSentences(t)

    // --- Real sentence ends SHOULD split (so a pause is spliced) ---

    @Test fun `vela nav prefix splits after navigation`() {
        assertEquals(
            listOf("Starting navigation.", "Turn right onto Main Street"),
            split("Starting navigation. Turn right onto Main Street"),
        )
    }

    @Test fun `faster route prefix splits`() {
        assertEquals(
            listOf("Taking the faster route.", "In a quarter mile, turn left"),
            split("Taking the faster route. In a quarter mile, turn left"),
        )
    }

    @Test fun `test-voice phrase splits at the real period`() {
        assertEquals(
            listOf("Voice guidance is on.", "In a quarter mile, turn right."),
            split("Voice guidance is on. In a quarter mile, turn right."),
        )
    }

    @Test fun `digit-started next clause splits`() {
        assertEquals(
            listOf("Recalculating.", "3 miles to your destination"),
            split("Recalculating. 3 miles to your destination"),
        )
    }

    // --- A road-type word before the period is NOT split, on purpose ---
    // "Street."/"Avenue." etc. are ambiguous: they're a genuine sentence end in "…Main Street. Then…",
    // but forSpeech ALSO produces them from an abbreviation ("St. Mary" → "Street. Mary"). Since we
    // can't tell them apart, we conservatively don't split there — no spurious mid-name pause. Real
    // Vela multi-sentence prompts break after ordinary words ("navigation."/"route."/"on."), so this
    // costs nothing in practice.
    @Test fun `road-type word before period is left whole to avoid false name splits`() {
        assertEquals(
            listOf("Turn right onto Main Street. Then merge onto Interstate 80."),
            split("Turn right onto Main Street. Then merge onto Interstate 80."),
        )
    }

    @Test fun `three sentences yield three chunks`() {
        assertEquals(listOf("One.", "Two.", "Three."), split("One. Two. Three."))
    }

    // --- Abbreviations / names must NOT split (the review-caught bug) ---

    @Test fun `abbreviation Jr in a street name does not split`() {
        assertEquals(
            listOf("Turn right onto Martin Luther King Jr. Boulevard"),
            split("Turn right onto Martin Luther King Jr. Boulevard"),
        )
    }

    @Test fun `abbreviation Mt in a street name does not split`() {
        assertEquals(listOf("Turn left onto Mt. Vernon Avenue"), split("Turn left onto Mt. Vernon Avenue"))
    }

    @Test fun `forSpeech-expanded Street dot before a name does not split`() {
        // VoiceGuide.forSpeech turns "St. Mary" (Saint) into "Street. Mary"; must stay one chunk.
        assertEquals(listOf("Turn onto Street. Mary's Road"), split("Turn onto Street. Mary's Road"))
    }

    @Test fun `directional abbreviation N dot does not split`() {
        assertEquals(listOf("Continue on North. Baker Avenue"), split("Continue on North. Baker Avenue"))
    }

    @Test fun `single-letter initial does not split`() {
        assertEquals(listOf("Head to U.S. Route 50"), split("Head to U.S. Route 50"))
    }

    @Test fun `lowercase continuation is not a sentence break`() {
        assertEquals(listOf("Continue on Elm St. then turn right"), split("Continue on Elm St. then turn right"))
    }

    // --- Numbers / trailing punctuation must NOT split ---

    @Test fun `decimal does not split`() {
        assertEquals(listOf("In 0.5 miles turn right"), split("In 0.5 miles turn right"))
    }

    @Test fun `trailing period is a single sentence`() {
        assertEquals(listOf("You have arrived."), split("You have arrived."))
    }

    @Test fun `single instruction is returned unchanged`() {
        val s = "In a quarter mile, turn right onto Main Street"
        assertEquals(listOf(s), split(s))
    }

    @Test fun `blank and tiny inputs are safe`() {
        assertEquals(listOf(""), split(""))
        assertEquals(listOf("."), split("."))
    }

    // --- Street-ordinal reading (128th → "one twenty-eighth") ---

    private fun num(t: String) = SpeechText.spokenNumbers(t)

    @Test fun `three-digit street ordinal reads the street way`() {
        assertEquals("Turn right onto one twenty-eighth Street", num("Turn right onto 128th Street"))
        assertEquals("one forty-fifth Avenue", num("145th Avenue"))
        assertEquals("two thirty-third", num("233rd"))
    }

    @Test fun `round hundred and low remainder`() {
        assertEquals("one hundredth Street", num("100th Street"))
        assertEquals("one oh fifth", num("105th"))
        assertEquals("one oh first", num("101st"))
        assertEquals("one tenth", num("110th"))
        assertEquals("two twentieth", num("220th"))
    }

    @Test fun `one and two digit ordinals are left for espeak`() {
        assertEquals("5th Street", num("5th Street"))
        assertEquals("42nd", num("42nd"))
        assertEquals("Head east on Main Street", num("Head east on Main Street"))
    }

    @Test fun `four-digit numbers are untouched`() {
        assertEquals("1280th", num("1280th"))
    }
}
