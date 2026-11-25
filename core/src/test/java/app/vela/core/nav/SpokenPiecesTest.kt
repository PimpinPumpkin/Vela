package app.vela.core.nav

import app.vela.core.i18n.EnNavStrings
import app.vela.core.i18n.FrNavStrings
import app.vela.core.i18n.RuNavStrings
import app.vela.core.voice.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phrases kept for a drive must be the very phrases the voice cuts an approach line into,
 *  or a line never finds them stored. */
class SpokenPiecesTest {
    private fun firstPhrase(line: String) = SpeechText.speechFragments(line, 0.32f, 0.16f).first().first

    @Test fun `rerouting comes first`() {
        assertEquals("Rerouting", SpokenPieces.forDrive(imperial = true, words = EnNavStrings).first())
    }

    @Test fun `every distance from 100 feet to a mile is kept as the voice cuts it`() {
        val kept = SpokenPieces.forDrive(imperial = true, words = EnNavStrings).toSet()
        var m = 30.5
        while (m < 1650.0) {
            val line = EnNavStrings.inThen(EnNavStrings.spokenDistance(m, true), "Turn right onto Main Street, then turn left")
            assertTrue("$m m: ${firstPhrase(line)}", firstPhrase(line) in kept)
            m += 1.0
        }
    }

    @Test fun `metric keeps the fifties and the first kilometers`() {
        val kept = SpokenPieces.forDrive(imperial = false, words = EnNavStrings)
        assertTrue("In 450 meters," in kept)
        assertTrue("In 1 kilometer," in kept)
        assertTrue(firstPhrase(EnNavStrings.inThen(EnNavStrings.spokenDistance(300.0, false), "Turn right")) in kept)
    }

    @Test fun `another language keeps its own clause`() {
        val kept = SpokenPieces.forDrive(imperial = false, words = FrNavStrings)
        assertEquals(FrNavStrings.rerouting(), kept.first())
        assertTrue(firstPhrase(FrNavStrings.inThen(FrNavStrings.spokenDistance(200.0, false), "tournez à droite")) in kept)
    }

    @Test fun `a language with no clause break keeps only its rerouting line`() {
        assertEquals(listOf(RuNavStrings.rerouting()), SpokenPieces.forDrive(imperial = false, words = RuNavStrings))
    }
}
