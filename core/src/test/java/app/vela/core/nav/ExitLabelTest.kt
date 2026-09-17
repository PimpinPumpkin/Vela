package app.vela.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitLabelTest {
    @Test fun `english exits`() {
        assertEquals("12B", ExitLabel.of("Take exit 12B toward Folsom Blvd"))
        assertEquals("7A-7B", ExitLabel.of("Keep right to take exit 7A - 7B"))
        assertEquals("172", ExitLabel.of("Use the right 2 lanes to take exit 172 toward Sacramento"))
    }

    @Test fun `other languages`() {
        assertEquals("24", ExitLabel.of("Prendre la sortie 24 vers Lyon"))
        assertEquals("7", ExitLabel.of("Nehmen Sie die Ausfahrt 7 Richtung Bonn"))
        assertEquals("12B", ExitLabel.of("12B出口を出ます"))
    }

    @Test fun `no exit number`() {
        assertNull(ExitLabel.of("Turn right onto Main Street"))
        assertNull(ExitLabel.of("Merge onto I 80 E"))
        assertNull(ExitLabel.of(null))
        assertNull(ExitLabel.of("Take the exit toward the airport"))
    }
}
