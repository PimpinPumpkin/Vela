package app.vela.core.data

import app.vela.core.data.google.parse.TransitParser
import app.vela.core.model.TransitLine
import app.vela.core.model.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the issue #234 fix: interchangeable lines on ONE leg (S1/S11/S12) must merge into a
 *  single summary badge instead of reading as a journey with transfers. */
class TransitAlternativesTest {

    private fun line(name: String) = TransitLine(name = name, mode = TransitMode.TRAIN, colorHex = "#1A73E8", textColorHex = "#FFFFFF")

    @Test
    fun `alternatives around one ride merge into one slash badge`() {
        val merged = TransitParser.mergeAlternativeLines(
            listOf(line("S1"), line("S11"), line("S12")),
            rideNames = listOf("S11"),
        )
        assertEquals(1, merged.size)
        assertEquals("S1 / S11 / S12", merged[0].name)
    }

    @Test
    fun `alternatives cluster onto their own leg in a two-ride trip`() {
        val merged = TransitParser.mergeAlternativeLines(
            listOf(line("S1"), line("S11"), line("U6")),
            rideNames = listOf("S1", "U6"),
        )
        assertEquals(2, merged.size)
        assertEquals("S1 / S11", merged[0].name)
        assertEquals("U6", merged[1].name)
    }

    @Test
    fun `matching counts pass through untouched`() {
        val lines = listOf(line("9"), line("42"))
        assertEquals(lines, TransitParser.mergeAlternativeLines(lines, rideNames = listOf("9", "42")))
    }

    @Test
    fun `a ride line missing from the badges keeps the original list`() {
        val lines = listOf(line("S1"), line("S11"))
        assertEquals(lines, TransitParser.mergeAlternativeLines(lines, rideNames = listOf("U6")))
    }

    @Test
    fun `walk-only trips keep whatever the badges said`() {
        val lines = listOf(line("S1"))
        assertEquals(lines, TransitParser.mergeAlternativeLines(lines, rideNames = emptyList()))
    }
}
