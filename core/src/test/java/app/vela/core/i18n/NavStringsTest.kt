package app.vela.core.i18n

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NavStringsTest {

    @Test fun `english phrases match the original osrmPhrase templates`() {
        val en = EnNavStrings
        assertEquals("Turn right onto 164th St SE", en.phrase("turn", "right", "164th St SE", null, null, null))
        assertEquals("Continue onto I 80", en.phrase("continue", "straight", "I 80", null, null, null))
        assertEquals("Take exit 15 toward Sacramento", en.phrase("off ramp", null, null, "Sacramento", "15", null))
        assertEquals("At the roundabout, take exit 2 onto Elm St", en.phrase("roundabout", null, "Elm St", null, null, 2))
        assertEquals("Make a U-turn onto Main St", en.phrase("uturn", null, "Main St", null, null, null))
        assertEquals("Head out on F St", en.phrase("depart", null, "F St", null, null, null))
        assertEquals("Arrive at your destination", en.phrase("arrive", null, null, null, null, null))
    }

    @Test fun `french reorders the modifier and keeps the road name untranslated`() {
        val fr = FrNavStrings
        assertEquals("Tournez à gauche sur Rue de Rivoli", fr.phrase("turn", "left", "Rue de Rivoli", null, null, null))
        assertEquals("Prenez la sortie 15 vers Sacramento", fr.phrase("off ramp", null, null, "Sacramento", "15", null))
        assertEquals("Au rond-point, prenez la 2e sortie sur Elm St", fr.phrase("roundabout", null, "Elm St", null, null, 2))
        assertEquals("Faites demi-tour sur Main St", fr.phrase("uturn", null, "Main St", null, null, null))
        assertEquals("Vous êtes arrivé à destination", fr.phrase("arrive", null, null, null, null, null))
        // The road NAME ("Rue de Rivoli") is data — never translated.
        assertEquals("Continuez sur Rue de Rivoli", fr.phrase("continue", "straight", "Rue de Rivoli", null, null, null))
    }

    @Test fun `registry defaults to english and switches by language`() {
        assertEquals(EnNavStrings, NavStringsRegistry.current()) // default, locale-independent
        assertEquals(FrNavStrings, NavStringsRegistry.forLanguage("fr"))
        assertEquals(EnNavStrings, NavStringsRegistry.forLanguage("de")) // untranslated → English
        NavStringsRegistry.setLocale(Locale.FRANCE)
        assertEquals(FrNavStrings, NavStringsRegistry.current())
        NavStringsRegistry.setLocale(Locale.US) // reset so other tests see English
        assertEquals(EnNavStrings, NavStringsRegistry.current())
    }
}
