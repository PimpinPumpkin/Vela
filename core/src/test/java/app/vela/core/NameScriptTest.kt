package app.vela.core

import app.vela.core.util.NameScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameScriptTest {
    @Test fun englishAppKeepsTheLatinLabelOverAHebrewListing() {
        assertEquals("Museum Of Illusions Tel Aviv", NameScript.prefer("en", "מוזיאון האשליות", "Museum Of Illusions Tel Aviv"))
    }

    @Test fun englishAppTakesGooglesNameWhenItIsLatin() {
        assertEquals("Mikuni", NameScript.prefer("en", "Mikuni", "Mikuni Japanese Restaurant"))
    }

    @Test fun hebrewAppKeepsGooglesHebrewName() {
        assertEquals("מוזיאון האשליות", NameScript.prefer("he", "מוזיאון האשליות", "Museum Of Illusions Tel Aviv"))
    }

    @Test fun noLabelOrUnknownLanguageMeansGoogle() {
        assertEquals("מוזיאון האשליות", NameScript.prefer("en", "מוזיאון האשליות", null))
        assertEquals("מוזיאון האשליות", NameScript.prefer("xx", "מוזיאון האשליות", "Museum"))
    }

    @Test fun japaneseCountsKanaAndKanji() {
        assertTrue(NameScript.isIn("東京タワー", NameScript.scriptOf("ja")!!))
        assertFalse(NameScript.isIn("Tokyo Tower", NameScript.scriptOf("ja")!!))
        assertEquals("Tokyo Tower", NameScript.prefer("en", "東京タワー", "Tokyo Tower"))
    }

    @Test fun mixedScriptLabelsFollowTheirMajority() {
        assertTrue(NameScript.isIn("Museum Of Illusions Tel Aviv - מוזיאון", NameScript.scriptOf("en")!!))
    }
}
