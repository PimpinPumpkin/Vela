package app.vela.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stop approach phrases ([NavStrings.stopAhead], [NavStrings.thenStop], [NavStrings.intoLotThen]) in every language. */
class StopCueStringsTest {
    private val langs = listOf("en", "fr", "de", "es", "it", "pt", "nl", "ru", "pl", "sv", "uk", "hu", "zh", "zh-tw", "ja", "he")

    @Test fun `english reads as the feedback asked`() {
        val en = EnNavStrings
        assertEquals("Davis Food Co-op will be on your right", en.stopAhead("Davis Food Co-op", false))
        assertEquals("Davis Food Co-op will be on your left", en.stopAhead("Davis Food Co-op", true))
        assertEquals("Davis Food Co-op will be ahead", en.stopAhead("Davis Food Co-op", null))
        assertEquals("Your stop will be ahead", en.stopAhead("", null))
        assertEquals(
            "Turn left onto Covell Boulevard, then Davis Food Co-op will be on your right",
            en.thenStop("Turn left onto Covell Boulevard", "Davis Food Co-op", false),
        )
        assertEquals("Turn left, then your stop will be ahead", en.thenStop("Turn left", " ", null))
        assertEquals("Turn left into the parking lot, then Davis Food Co-op is on your right", en.intoLotThen(true, "Davis Food Co-op", false))
        assertEquals("Turn right into the parking lot, then Davis Food Co-op is ahead", en.intoLotThen(false, "Davis Food Co-op", null))
    }

    @Test fun `every language names the stop, keeps the instruction and tells the sides apart`() {
        for (code in langs) {
            val ns = NavStringsRegistry.forLanguage(code)
            val name = "Davis Food Co-op"
            val left = ns.stopAhead(name, true)
            val right = ns.stopAhead(name, false)
            val ahead = ns.stopAhead(name, null)
            for (s in listOf(left, right, ahead)) assertTrue("$code: $s", s.contains(name))
            assertNotEquals("$code left/right", left, right)
            assertNotEquals("$code right/ahead", right, ahead)
            val then = ns.thenStop("INSTRUCTION", name, false)
            assertTrue("$code: $then", then.startsWith("INSTRUCTION") && then.contains(name))
            val lotL = ns.intoLotThen(true, name, false)
            val lotR = ns.intoLotThen(false, name, false)
            assertTrue("$code: $lotL", lotL.contains(name))
            assertNotEquals("$code lot turn side", lotL, lotR)
            // A stop with no name still reads as a sentence.
            for (s in listOf(ns.stopAhead("", false), ns.thenStop("X", "", null), ns.intoLotThen(true, "", null))) {
                assertTrue("$code blank: '$s'", s.isNotBlank() && !s.contains("null") && s == s.trim())
                assertFalse("$code blank double space: '$s'", s.contains("  "))
            }
        }
    }
}
