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

    @Test fun aNameScriptNamesTheSearchLanguage() {
        assertEquals("ja", NameScript.scriptLanguage("東京ミッドタウン", 35.66, 139.73))
        assertEquals("ja", NameScript.scriptLanguage("虎の門病院", 35.66, 139.74)) // Han only, inside Japan
        assertEquals("zh-CN", NameScript.scriptLanguage("星巴克咖啡", 31.23, 121.47))
        assertEquals("zh-TW", NameScript.scriptLanguage("星巴克咖啡", 25.03, 121.56))
        assertEquals("ko", NameScript.scriptLanguage("스타벅스 강남점", 37.50, 127.03))
        assertEquals("ru", NameScript.scriptLanguage("Аптека Ригла", 55.75, 37.62))
        assertEquals("iw", NameScript.scriptLanguage("מוזיאון האשליות", 32.07, 34.78))
        assertEquals("th", NameScript.scriptLanguage("ร้านกาแฟ", 13.75, 100.50))
        assertEquals(null, NameScript.scriptLanguage("Tokyo Midtown", 35.66, 139.73))
        assertEquals(null, NameScript.scriptLanguage("#12", 35.66, 139.73))
        assertTrue(NameScript.sameLanguage("iw", "he"))
        assertTrue(NameScript.sameLanguage("zh-TW", "zh"))
        assertFalse(NameScript.sameLanguage("ja", "en"))
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
