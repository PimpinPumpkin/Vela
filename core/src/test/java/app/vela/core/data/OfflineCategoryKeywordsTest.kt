package app.vela.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every quick-category chip query must expand offline to the OSM values the packs store
 *  (formatted like OverpassPois: "camp_site" -> "Camp site", matched with a case-insensitive LIKE). */
class OfflineCategoryKeywordsTest {

    private fun kw(q: String) = OfflinePoiStore.categoryKeywords(q)

    @Test fun chipQueriesExpand() {
        // The chip queries in app/.../ui/QuickCategories.kt. "EV charging station" and the fuel
        // query expand word by word in search(), so they are checked through their words.
        mapOf(
            "Restaurants" to "restaurant",
            "Coffee" to "cafe",
            "Gas station" to "fuel",
            "Petrol station" to "fuel",
            "Groceries" to "supermarket",
            "Things to do" to "museum",
            "Hotels" to "hotel",
            "Bars" to "bar",
            "charging" to "charging station",
            "Parking" to "parking",
            "Pharmacy" to "pharmacy",
            "ATMs" to "atm",
            "Parks" to "park",
            "Hospitals" to "hospital",
            "Banks" to "bank",
            "Post office" to "post office",
            "Campgrounds" to "camp site",
        ).forEach { (q, want) -> assertTrue("'$q' -> ${kw(q)}", want in kw(q)) }
    }

    @Test fun newExpansions() {
        assertEquals(listOf("bar", "pub", "biergarten"), kw("bars"))
        assertEquals(listOf("hospital"), kw("  HOSPITALS "))
        assertEquals(listOf("camp site", "caravan site"), kw("campground"))
        assertEquals(listOf("post office"), kw("post offices"))
        assertTrue(kw("Things to do").containsAll(listOf("attraction", "viewpoint", "theme park", "zoo")))
    }

    @Test fun pluralFallback() {
        assertEquals(kw("gym"), kw("gyms"))
        assertEquals(kw("school"), kw("Schools"))
        // Short words are never trimmed ("gas" is its own key, not a plural of "ga").
        assertEquals(listOf("fuel", "charging station"), kw("gas"))
        assertEquals(emptyList<String>(), kw("xyzs"))
    }
}
