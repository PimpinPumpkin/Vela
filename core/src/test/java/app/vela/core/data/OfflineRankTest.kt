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
}
