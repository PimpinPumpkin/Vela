package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRankTest {
    private val here = LatLng(38.5449, -121.7405)
    private fun place(id: String, name: String, cat: String, lat: Double, lng: Double) = Place(id = id, name = name, location = LatLng(lat, lng), category = cat)

    @Test
    fun aCategoryRowAnswersItsPluralChip() {
        // "restaurants" is not a substring of "Restaurant": the far "... Restaurants" name used to lead.
        val near = place("a", "Taqueria", "Restaurant", 38.545, -121.741)
        val far = place("b", "Family Restaurants", "Restaurant", 38.9, -121.2)
        assertEquals(listOf("a", "b"), OfflineRank.rank("Restaurants", here, listOf(far, near), 30).map { it.id })
    }

    @Test
    fun aCategorySearchStaysLocal() {
        val across = place("pa", "Arby's", "Fast food", 40.9, -80.3)
        assertTrue(OfflineRank.rank("Restaurants", here, listOf(across), 30).isEmpty())
        // A name search is never cut by distance.
        assertEquals(1, OfflineRank.rank("Arby's", here, listOf(across), 30).size)
    }

    @Test
    fun twoSourcesOfOnePlaceShowOnce() {
        val tile = place("overture:1", "Steve's Pizza", "Pizza restaurant", 38.5450, -121.7400)
        val pack = place("osm:n5", "Steve's Pizza", "Restaurant", 38.5451, -121.7401)
        assertEquals(listOf("overture:1"), OfflineRank.rank("pizza", here, listOf(tile, pack), 30).map { it.id })
    }

    @Test
    fun matchesTheArchivesCategories() {
        assertTrue(OfflineRank.matches("Restaurants", "Pho Tasty", "Vietnamese restaurant", null))
        assertTrue(OfflineRank.matches("Coffee", "Mishka's", "Coffee shop", null))
        assertTrue(OfflineRank.matches("Groceries", "Davis Food Co-op", "Grocery store", null))
        assertTrue(!OfflineRank.matches("Restaurants", "Davis Ace Hardware", "Hardware store", null))
    }

    @Test
    fun aCategoryQueryIsNotSplitIntoItsWords() {
        // "Gas station" matched "Charging station" on the word "station" (issue #657).
        assertTrue(OfflineRank.matches("Gas station", "Petro-Canada", "Fuel", null))
        assertTrue(!OfflineRank.matches("Gas station", "SWTCH", "Charging station", null))
        val fuel = place("f", "Esso", "Fuel", 38.60, -121.70)
        val charger = place("c", "FLO", "Charging station", 38.545, -121.741)
        assertEquals(listOf("f"), OfflineRank.rank("Gas station", here, listOf(charger, fuel).filter { OfflineRank.matches("Gas station", it.name, it.category, null) }, 30).map { it.id })
        // A name that is not a category still matches by word.
        assertTrue(OfflineRank.matches("mexican restaurant", "Ixtapa Mexican Restaurant", "Restaurant", null))
    }

    @Test
    fun namesMatchWithoutTheirPunctuation() {
        assertTrue(OfflineRank.matches("mcdonalds", "McDonald's", "Fast food", null))
        assertTrue(OfflineRank.matches("7 eleven", "7-Eleven", "Convenience", null))
        assertTrue(OfflineRank.matches("st hubert", "St. Hubert", "Restaurant", null))
        // The restaurant nearby leads a parking lot that spells the name without the apostrophe.
        val real = place("r", "McDonald's", "Fast food", 38.56, -121.75)
        val lot = place("l", "McDonalds Parking Lot", "Parking", 39.4, -121.0)
        assertEquals(listOf("r", "l"), OfflineRank.rank("mcdonalds", here, listOf(lot, real), 30).map { it.id })
    }

    @Test
    fun namesMatchWithoutTheirAccents() {
        assertTrue(OfflineRank.matches("cafe", "Café Central", "Cafe", null))
        assertTrue(OfflineRank.matches("Café", "Cafe Roma", "Cafe", null))
        assertTrue(OfflineRank.matches("zurich", "Zürich HB", "Station", null))
        assertTrue(OfflineRank.matches("strasse", "Hauptstraße", "Bus stop", null))
        assertTrue(OfflineRank.matches("lodz", "Łódź Kaliska", "Station", null))
        assertTrue(OfflineRank.matches("istanbul", "İstanbul Kebap", "Restaurant", null))
        assertTrue(OfflineRank.matches("pho hoa", "Phở Hòa", "Restaurant", null))
        assertEquals("smorrebrod", OfflineRank.fold("SMØRREBRØD"))
    }

    @Test
    fun otherScriptsAreComparedAsTyped() {
        // Folding these would strip the marks the pack's names still carry.
        for (name in listOf("スターバックス", "がっこう", "스타벅스", "ร้านกาแฟ", "हिंदी", "مطعم أبو", "קפה", "星巴克")) {
            assertEquals(name, OfflineRank.fold(name))
            assertEquals("*$name*", OfflineRank.glob(OfflineRank.fold(name)))
        }
        // Cased scripts lowercase and match either case, letter for letter.
        assertEquals("музей", OfflineRank.fold("Музей"))
        assertEquals("*[мМ][уУ][зЗ][еЕ][йЙ]*", OfflineRank.glob(OfflineRank.fold("Музей")))
        assertEquals("καφέ", OfflineRank.fold("Καφέ"))
        assertTrue(OfflineRank.matches("музей", "Дом-музей", "Museum", null))
        assertTrue(OfflineRank.matches("ガソリン", "ガソリンスタンド", "Fuel", null))
        // A Latin letter typed as a letter and a separate accent folds the same way.
        assertEquals("cafe", OfflineRank.fold("Cafe\u0301"))
    }

    @Test
    fun thePackPatternCarriesEveryFormOfALetter() {
        val one = OfflineRank.glob("cafe")
        assertTrue(one.startsWith("*[cC") && one.endsWith("]*"))
        for (c in "çÇáÁéÉèêë") assertTrue("missing $c", c in one)
        // A letter with no case or accents stays itself; one with case only gets both.
        assertEquals("*7 [мМ]*", OfflineRank.glob("7 м"))
        // GLOB's own characters are matched as text.
        assertEquals("*[*][?][[][]]*", OfflineRank.glob("*?[]"))
        // "ss" may be one letter in the name, so it is also tried as ß.
        assertEquals(null, OfflineRank.globEszett("cafe"))
        assertTrue("[ßẞ]" in OfflineRank.globEszett("strasse")!! && "ß" !in OfflineRank.glob("strasse"))
    }

    @Test
    fun thePackQueryKeepsTheAccentPatternOffPlainNames() {
        val (sql, args) = OfflinePoiStore.nameMatch("cafe")
        // LIKE first; the pattern only behind the non-ASCII test. One "?" per argument.
        assertTrue(sql.indexOf(" LIKE ?") < sql.indexOf("length(name) <> length(CAST(name AS BLOB)) AND"))
        assertEquals(listOf("%cafe%", OfflineRank.glob("cafe")), args)
        assertEquals(args.size, sql.count { it == '?' })
        val (sqlSs, argsSs) = OfflinePoiStore.nameMatch("strasse")
        assertEquals(3, argsSs.size)
        assertEquals(argsSs.size, sqlSs.count { it == '?' })
    }

    @Test
    fun aNameThatStartsWithTheQueryLeadsOneThatOnlyHoldsIt() {
        val inside = place("s", "Seashell Cafe", "Cafe", 38.545, -121.741)
        val station = place("f", "Shell", "Fuel", 38.60, -121.70)
        assertEquals(listOf("f", "s"), OfflineRank.rank("shell", here, listOf(inside, station), 30).map { it.id })
        // Two names that both start with it stay tied, so the nearer one still leads.
        val nearBig = place("b", "Target Optical", "Optician", 38.545, -121.741)
        val farExact = place("t", "Target", "Department store", 38.60, -121.70)
        assertEquals(listOf("b", "t"), OfflineRank.rank("target", here, listOf(farExact, nearBig), 30).map { it.id })
    }
}
