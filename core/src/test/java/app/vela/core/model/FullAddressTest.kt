package app.vela.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FullAddressTest {
    private fun p(name: String, address: String?, category: String? = null) =
        Place(id = "x", name = name, location = LatLng(38.56, -121.76), address = address, category = category)

    @Test fun `a place named by its street line gets the whole address`() {
        assertEquals("1451 W Covell Blvd, Davis, CA 95616", p("1451 W Covell Blvd", "Davis, CA 95616").fullAddress())
    }

    @Test fun `an address that is already whole, and a business, are left alone`() {
        assertEquals("1451 W Covell Blvd, Davis, CA 95616", p("1451 W Covell Blvd", "1451 W Covell Blvd, Davis, CA 95616").fullAddress())
        assertEquals("620 W Covell Blvd, Davis, CA", p("7 Eleven", "620 W Covell Blvd, Davis, CA", category = "Convenience store").fullAddress())
        assertEquals("Davis, CA", p("Central Park", "Davis, CA").fullAddress())
        assertEquals(null, p("1451 W Covell Blvd", null).fullAddress())
    }

    @Test fun `an address filed under a category still gets its street`() {
        // A house Google lists with a category: its address field is only the locality.
        assertEquals("1451 W Covell Blvd, Davis, CA 95616", p("1451 W Covell Blvd", "Davis, CA 95616", category = "Building").fullAddress())
        // A business with a street line of its own is left as it is, whatever its name.
        assertEquals("620 W Covell Blvd, Davis, CA", p("24 Hour Fitness", "620 W Covell Blvd, Davis, CA", category = "Gym").fullAddress())
    }
}
