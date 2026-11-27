package app.vela.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressQueryTest {
    @Test fun parsesNumberAndStreet() {
        assertEquals("912" to "miller", AddressQuery.parse("912 Miller Dr"))
        assertEquals("1451" to "covell", AddressQuery.parse("1451 W Covell Blvd, Davis"))
        assertEquals("200" to "b", AddressQuery.parse("200 B St"))
        assertEquals("12" to "north", AddressQuery.parse("12 North St"))
        assertEquals("42" to "hauptstraße", AddressQuery.parse("Hauptstraße 42"))
        assertEquals("15a" to "berliner", AddressQuery.parse("Berliner Str. 15a, Berlin"))
        assertEquals("42" to "straße", AddressQuery.parse("Straße des 17. Juni 42"))
        assertEquals("42" to "hauptstraße", AddressQuery.parse("Hauptstraße 42 Berlin"))
        // A US address typed street first, town, state and ZIP after it (issue #1011).
        assertEquals("1451" to "covell", AddressQuery.parse("W Covell Blvd 1451, Davis, CA 95616"))
        assertEquals("222" to "oak", AddressQuery.parse("Oak Ln 222, Davis CA, 95616"))
        assertNull(AddressQuery.parse("coffee"))
        assertNull(AddressQuery.parse("5th street"))
        assertNull(AddressQuery.parse("Highway 50"))
        assertNull(AddressQuery.parse("Route 66"))
        assertNull(AddressQuery.parse("Davis 95616"))
        assertNull(AddressQuery.parse("Wien 1010"))
        assertNull(AddressQuery.parse("Zürich 8001"))
        // A name with a number is a name unless it carries a street word or a town after a comma.
        assertNull(AddressQuery.parse("Pier 39"))
        assertNull(AddressQuery.parse("Studio 54"))
        assertNull(AddressQuery.parse("Apollo 13"))
        assertEquals("5", AddressQuery.parse("Calle Mayor 5")?.first)
        assertEquals("12" to "via", AddressQuery.parse("Via Roma 12, Milano"))
        assertNull(AddressQuery.parse("Studio 54, New York"))
        assertNull(AddressQuery.parse("Wien 1010, Austria"))
        // Five-digit US house numbers street first; a ZIP after a street with no number is not one.
        assertEquals("12345" to "covell", AddressQuery.parse("W Covell Blvd 12345, Davis, CA 95616"))
        assertNull(AddressQuery.parse("Main St, Davis CA 95616"))
        assertNull(AddressQuery.parse("Davis CA 95616"))
        assertEquals("5", AddressQuery.parse("Karl Johans gate 5, Oslo")?.first)
        assertEquals("10", AddressQuery.parse("ul. Marszałkowska 10, Warszawa")?.first)
    }

    @Test fun anAddressIsLookedForByItsStreetsOwnName() {
        assertEquals(listOf("Legislative"), AddressQuery.streetNameWords("Legislative Ave 411, Dover, DE 19901"))
        assertEquals(listOf("Legislative"), AddressQuery.streetNameWords("411 Legislative Ave, Dover, DE 19901"))
        assertEquals(listOf("Covell"), AddressQuery.streetNameWords("W Covell Blvd 1451"))
        assertNull(AddressQuery.streetNameWords("coffee near me"))
        // One street word is not split, so only the whole query is matched against names.
        assertEquals(emptyList<String>(), app.vela.core.data.OfflineRank.queryWords("Legislative Ave 411, Dover, DE 19901"))
        assertEquals(listOf("Burger", "King"), app.vela.core.data.OfflineRank.queryWords("Burger King"))
    }

    @Test fun theAddressItselfMatches() {
        assertTrue(AddressQuery.matches("912 Miller Dr", "912 Miller Dr", "Davis, CA 95616"))
        assertTrue(AddressQuery.matches("1451 w covell blvd", "Some Market", "1451 W Covell Blvd, Davis, CA 95616"))
        assertTrue(AddressQuery.matches("Hauptstraße 42", "Hauptstraße 42", "10827 Berlin"))
        assertTrue(AddressQuery.matches("W Covell Blvd 1451, Davis, CA 95616", "1451 W Covell Blvd", "Davis, CA 95616"))
    }

    @Test fun digitsInsideAZipOrANeighborAreNotAMatch() {
        // "616" sits inside the ZIP, "12" inside the neighbor's number.
        assertFalse(AddressQuery.matches("616 Anderson Rd", "Anderson Plaza Cafe", "1900 Anderson Rd, Davis, CA 95616"))
        assertFalse(AddressQuery.matches("12 Oak Ave", "Oak Dental", "1200 Oak Ave, Davis, CA 95616"))
    }

    @Test fun sameNumberOnAnotherStreetIsNotAMatch() {
        assertFalse(AddressQuery.matches("530 Oak Ave", "Corner Bakery", "530 Elm St, Davis, CA 95616"))
    }

    @Test fun aBusinessNamedForTheStreetIsNotAMatch() {
        assertFalse(AddressQuery.matches("459 Ralston Ave", "Ralston Hardware", "88 Main St, Springfield"))
    }

    @Test fun `the result that agrees with the whole typed street scores highest`() {
        val q = "1451 W Covell Blvd, Davis, CA"
        val exact = AddressQuery.score(q, "1451 W Covell Blvd", "Davis, CA")
        val east = AddressQuery.score(q, "1451 East Covell Boulevard", "Davis, CA")
        val place = AddressQuery.score(q, "1451 Covell Place", "Davis, CA")
        assertTrue(exact > east && exact > place)
        assertTrue(east > place)
    }
}
