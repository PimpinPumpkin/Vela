package app.vela.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedPlaceStreetTest {
    private fun sp(name: String, address: String?, bare: Boolean = false, id: String = "x") =
        SavedPlace(id = id, name = name, lat = 0.0, lng = 0.0, address = address, bare = bare)

    @Test fun `a point's street moves into a locality-only address`() {
        assertEquals("Elm Street 5, 95616 Davis", sp("Elm Street 5", "95616 Davis", bare = true).addressKeepingStreet())
        assertEquals("Elm Street 5, 95616 Davis", sp("Elm Street 5", "95616 Davis", id = "pin:1").addressKeepingStreet())
    }

    @Test fun `an address that already starts with the street, a listing and a blank are left alone`() {
        assertEquals("Elm Street 5, 95616 Davis", sp("Elm Street 5", "Elm Street 5, 95616 Davis").addressKeepingStreet())
        assertEquals("95616 Davis", sp("Davis Food Co-op", "95616 Davis").addressKeepingStreet())
        assertEquals(null, sp("Elm Street 5", null, bare = true).addressKeepingStreet())
        assertEquals(" ", sp("Elm Street 5", " ", bare = true).addressKeepingStreet())
    }

    @Test fun `a rename moves the street once and a second rename leaves the address alone`() {
        val once = sp("Elm Street 5", "95616 Davis", bare = true).renamed("Peter")
        assertEquals("Peter", once.name)
        assertEquals("Elm Street 5, 95616 Davis", once.address)
        assertEquals(true, once.streetKept)
        val twice = once.renamed("Groceries")
        assertEquals("Elm Street 5, 95616 Davis", twice.address)
    }

    @Test fun `a label kept with only the locality gets its street back, once`() {
        val mended = sp("Elm Street 5", "95616 Davis").labelMended()!!
        assertEquals("Elm Street 5, 95616 Davis", mended.address)
        assertEquals(null, mended.labelMended())
        assertEquals("1451 W Covell Blvd, Davis, CA 95616", sp("1451 W Covell Blvd", "Davis, CA 95616").labelMended()!!.address)
    }

    @Test fun `a label that needs no mending is left alone`() {
        // The street is already there, the name is no street line, or there is no address.
        assertEquals(null, sp("Elm Street 5", "Elm Street 5, 95616 Davis").labelMended())
        assertEquals(null, sp("Davis Food Co-op", "620 G St, Davis, CA 95616").labelMended())
        // A number inside a name is not a house number; one at the end is taken as one.
        assertEquals(null, sp("7-Eleven", "Davis, CA 95616").labelMended())
        assertEquals("12A Main St, Davis, CA 95616", sp("12A Main St", "Davis, CA 95616").labelMended()!!.address)
        assertEquals(null, sp("38.5449, -121.7405", "Davis, CA 95616").labelMended())
        assertEquals(null, sp("Elm Street 5", null).labelMended())
    }

    @Test fun `an address saved as a listing still keeps its street when it is relabeled`() {
        // Google filed the address under a category, so it was saved as a listing, not a point.
        assertEquals("1451 W Covell Blvd, Davis, CA 95616", sp("1451 W Covell Blvd", "Davis, CA 95616").addressKeepingStreet())
        assertEquals("Davis, CA 95616", sp("Davis Food Co-op", "Davis, CA 95616").addressKeepingStreet())
    }
}
