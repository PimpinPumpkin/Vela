package app.vela.core.i18n

import app.vela.core.model.Lane
import app.vela.core.model.LaneSide
import app.vela.core.model.laneGuidance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(EnNavStrings, NavStringsRegistry.forLanguage("ja")) // untranslated → English fallback
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
        // EN carries a trailing semicolon ON PURPOSE — spoken-only string; the punctuation shapes the
        // Piper voice's final prosody contour (user A/B'd: semicolon > period > bare). See EnNavStrings.
        assertEquals("You have arrived;", EnNavStrings.arrived())
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

    @Test fun `lane guidance derives side + count and speaks it`() {
        // 3 lanes, right 2 valid → "Use the right 2 lanes"
        val right2 = listOf(Lane(listOf("straight"), false), Lane(listOf("right"), true), Lane(listOf("right"), true))
        val g = laneGuidance(right2)!!
        assertEquals(LaneSide.RIGHT, g.side)
        assertEquals(2, g.count)
        assertEquals("Use the right 2 lanes", EnNavStrings.useLanes(g.side, g.count))
        assertEquals("Empruntez les 2 voies de droite", FrNavStrings.useLanes(g.side, g.count))
        // single left lane
        val left1 = laneGuidance(listOf(Lane(listOf("left"), true), Lane(listOf("straight"), false)))!!
        assertEquals(LaneSide.LEFT, left1.side)
        assertEquals(1, left1.count)
        assertEquals("Use the left lane", EnNavStrings.useLanes(left1.side, left1.count))
        assertEquals("Empruntez la voie de gauche", FrNavStrings.useLanes(left1.side, left1.count))
        // Lane guidance PREFACES the maneuver (Google-style), not appended after it.
        assertEquals(
            "Use the right 2 lanes to take exit 172 toward Sacramento",
            EnNavStrings.useLanesToDo(g.side, g.count, "Take exit 172 toward Sacramento"),
        )
        // Other languages fall back to a safe two-sentence, lanes-first form.
        assertEquals(
            "Empruntez les 2 voies de droite. Prenez la sortie 172",
            FrNavStrings.useLanesToDo(g.side, g.count, "Prenez la sortie 172"),
        )
        // nothing useful: all valid, non-contiguous, or too few lanes → null
        assertEquals(null, laneGuidance(listOf(Lane(listOf("s"), true), Lane(listOf("s"), true))))
        assertEquals(null, laneGuidance(listOf(Lane(listOf("l"), true), Lane(listOf("s"), false), Lane(listOf("r"), true))))
        assertEquals(null, laneGuidance(listOf(Lane(listOf("s"), true))))
    }

    @Test fun `expandForSpeech is English-only opt-in`() {
        assertEquals("Turn right onto Main Street", EnNavStrings.expandForSpeech("Turn right onto Main St"))
        assertEquals("one twenty-eighth Street", EnNavStrings.expandForSpeech("128th St"))
        // French leaves the text — including a road abbreviation — untouched (interface default identity).
        assertEquals("Tournez sur Rue St", FrNavStrings.expandForSpeech("Tournez sur Rue St"))
        // Other languages also leave text untouched (they use the interface default).
        assertEquals("Biegen Sie ab auf Hauptstr", DeNavStrings.expandForSpeech("Biegen Sie ab auf Hauptstr"))
    }

    @Test fun `every registered language produces non-blank nav strings and keeps road names`() {
        val langs = listOf("fr", "de", "es", "it", "pt", "nl", "ru", "pl", "sv", "uk")
        for (code in langs) {
            val ns = NavStringsRegistry.forLanguage(code)
            assertEquals("$code should map to its own NavStrings", code, ns.locale.language)
            // Every generated string non-blank for representative maneuvers + values.
            assertTrue("$code turn", ns.phrase("turn", "left", "Main St", null, null, null).isNotBlank())
            assertTrue("$code exit", ns.phrase("off ramp", null, null, "Downtown", "12", null).isNotBlank())
            assertTrue("$code roundabout", ns.phrase("roundabout", null, "Elm St", null, null, 2).isNotBlank())
            assertTrue("$code merge", ns.phrase("merge", null, "I 5", null, null, null).isNotBlank())
            assertTrue("$code uturn", ns.phrase("uturn", null, "Main St", null, null, null).isNotBlank())
            assertTrue("$code dist metric near", ns.spokenDistance(150.0, false).isNotBlank())
            assertTrue("$code dist metric far", ns.spokenDistance(2400.0, false).isNotBlank())
            assertTrue("$code dist imperial", ns.spokenDistance(150.0, true).isNotBlank())
            assertTrue("$code inThen", ns.inThen("X", "Y").isNotBlank())
            assertTrue("$code arrived", ns.arrived().isNotBlank())
            assertTrue("$code startNav", ns.startNav("Z").isNotBlank())
            assertTrue("$code reachedStop", ns.reachedStop("Costco").isNotBlank())
            assertTrue("$code reachedStop blank", ns.reachedStop("").isNotBlank())
            assertTrue("$code fasterRoute", ns.fasterRoute("Z").isNotBlank())
            assertTrue("$code voiceTest", ns.voiceTest().isNotBlank())
            assertTrue("$code lanes plural", ns.useLanes(LaneSide.RIGHT, 2).isNotBlank())
            assertTrue("$code lane single", ns.useLanes(LaneSide.LEFT, 1).isNotBlank())
            // The road NAME is data — it must survive untranslated in the output.
            assertTrue("$code keeps road name", ns.phrase("turn", "left", "Rue de Rivoli", null, null, null).contains("Rue de Rivoli"))
        }
    }

    /** OSRM forks are almost always "slight left"/"slight right" — EVERY language's fork phrase must
     *  come out on the correct SIDE for those (the Dutch exact-match on "links"/"rechts" fell to a
     *  hardcoded keep-LEFT at a keep-RIGHT freeway split — a safety bug, audit 2026-07-06). */
    @Test fun `fork guidance keeps the correct side for slight modifiers in every language`() {
        val sideWords = mapOf(
            "en" to ("left" to "right"), "fr" to ("gauche" to "droite"), "de" to ("links" to "rechts"),
            "es" to ("izquierda" to "derecha"), "it" to ("sinistra" to "destra"), "pt" to ("esquerda" to "direita"),
            "nl" to ("links" to "rechts"), "ru" to ("лев" to "прав"), "pl" to ("lew" to "praw"),
            "sv" to ("vänster" to "höger"), "uk" to ("лів" to "прав"),
        )
        for ((code, words) in sideWords) {
            val ns = NavStringsRegistry.forLanguage(code)
            val (l, r) = words
            val keepRight = ns.phrase("fork", "slight right", "I 80", null, null, null).lowercase()
            val keepLeft = ns.phrase("fork", "slight left", "I 80", null, null, null).lowercase()
            assertTrue("$code slight-right fork must mention '$r' (was: $keepRight)", keepRight.contains(r))
            assertTrue("$code slight-right fork must NOT read as left (was: $keepRight)", !keepRight.contains(l) || l == r)
            assertTrue("$code slight-left fork must mention '$l' (was: $keepLeft)", keepLeft.contains(l))
        }
    }

    /** Ukrainian fractional distances take the GENITIVE SINGULAR ("1,2 кілометра"), not the
     *  nominative plural the old 3-form ukPlural returned ("кілометри") — RU/PL had this right. */
    @Test fun `ukrainian fractional km uses genitive singular`() {
        val uk = NavStringsRegistry.forLanguage("uk")
        assertTrue(uk.spokenDistance(1200.0, imperial = false).contains("кілометра"))
        assertTrue(uk.spokenDistance(3000.0, imperial = false).contains("кілометри"))
        assertTrue(uk.spokenDistance(5000.0, imperial = false).contains("кілометрів"))
    }
}
