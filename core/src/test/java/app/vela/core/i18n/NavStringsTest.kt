package app.vela.core.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NavStringsTest {

    // The registry is process-global — never leave it non-English for the other (English-asserting) nav tests.
    @After fun resetToEnglish() = NavStringsRegistry.setLocale(Locale.ENGLISH)

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

    @Test fun `spoken distance is unit-aware and localized`() {
        assertEquals("500 feet", EnNavStrings.spokenDistance(152.4, true))
        assertEquals("150 meters", EnNavStrings.spokenDistance(150.0, false))
        assertEquals("500 pieds", FrNavStrings.spokenDistance(152.4, true))
        assertEquals("150 mètres", FrNavStrings.spokenDistance(150.0, false))
    }

    @Test fun `the frame and arrival are localized`() {
        assertEquals("In 500 feet, Turn right", EnNavStrings.inThen("500 feet", "Turn right"))
        assertEquals("Dans 150 mètres, Tournez à droite", FrNavStrings.inThen("150 mètres", "Tournez à droite"))
        assertEquals("You have arrived", EnNavStrings.arrived())
        assertEquals("Vous êtes arrivé", FrNavStrings.arrived())
    }

    @Test fun `session lines are localized`() {
        assertEquals("Starting navigation. Head east on F St", EnNavStrings.startNav("Head east on F St"))
        assertEquals("Démarrage de la navigation. Prenez F St", FrNavStrings.startNav("Prenez F St"))
        assertEquals("You've reached Costco", EnNavStrings.reachedStop("Costco"))
        assertEquals("You've reached your stop", EnNavStrings.reachedStop(""))
        assertEquals("Vous êtes arrivé à Costco", FrNavStrings.reachedStop("Costco"))
        assertEquals("Vous êtes arrivé à votre étape", FrNavStrings.reachedStop(""))
        assertEquals("Taking the faster route. Turn right", EnNavStrings.fasterRoute("Turn right"))
        assertEquals("Itinéraire plus rapide. Tournez à droite", FrNavStrings.fasterRoute("Tournez à droite"))
    }

    @Test fun `expandForSpeech is English-only opt-in`() {
        assertEquals("Turn right onto Main Street", EnNavStrings.expandForSpeech("Turn right onto Main St"))
        assertEquals("one twenty-eighth Street", EnNavStrings.expandForSpeech("128th St"))
        // French leaves the text — including a road abbreviation — untouched (interface default identity).
        assertEquals("Tournez sur Rue St", FrNavStrings.expandForSpeech("Tournez sur Rue St"))
    }
}
