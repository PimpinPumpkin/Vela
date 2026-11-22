package app.vela.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressLineTest {
    @Test fun `a house number is a plain number, never an ordinal`() {
        assertTrue(hasHouseNumber("1004 24th St, Sacramento, CA 95816"))
        assertTrue(hasHouseNumber("403 3rd St, Davis, CA 95616"))
        assertTrue(hasHouseNumber("Hauptstraße 5, 10115 Berlin"))
        assertTrue(hasHouseNumber("12-14 King St, London"))
        assertTrue(hasHouseNumber("505 1/2 L St, Davis, CA 95616"))
        assertFalse(hasHouseNumber("East 8th Street, Davis, CA"))
        assertFalse(hasHouseNumber("24th St, Sacramento, CA 95816"))
        assertFalse(hasHouseNumber("Davis, CA 95616"))
        assertFalse(hasHouseNumber(null))
    }

    @Test fun `a contact shows the address saved in it`() {
        assertEquals("403 3rd St, Davis, CA 95616", contactAddressLine("403 3rd St\nDavis, CA 95616", "Davis, CA 95616"))
        assertEquals("403 3rd St, Davis, CA 95616", contactAddressLine("403 3rd St, Davis, CA 95616", null))
    }

    @Test fun `a contact with only a street line takes the town from the geocoder`() {
        assertEquals("403 3rd St, Davis, CA 95616", contactAddressLine("403 3rd St", "Davis, CA 95616"))
        assertEquals("403 3rd St, Davis, CA 95616", contactAddressLine("403 3rd St", "403 3rd St, Davis, CA 95616"))
    }

    @Test fun `a contact with no address shows the geocoder's`() {
        assertEquals("Davis, CA 95616", contactAddressLine("  ", "Davis, CA 95616"))
    }
}
