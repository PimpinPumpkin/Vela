package app.vela.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceNamesTest {
    @Test fun `a store number does not make a different business`() {
        assertTrue(PlaceNames.same("Shop", "Shop #1561"))
        assertTrue(PlaceNames.same("SHOP STORE 1561", "shop"))
        assertTrue(PlaceNames.same("Shop No 7", "Shop"))
    }

    @Test fun `punctuation and case do not either`() {
        assertTrue(PlaceNames.same("Joe's Diner", "JOE S DINER"))
        assertTrue(PlaceNames.same("7-Eleven", "7 Eleven"))
    }

    @Test fun `the brand's other listings are NOT the same business`() {
        // The whole point: these are what a tap on the store used to open.
        assertFalse(PlaceNames.same("Shop", "Shop Fuel Station"))
        assertFalse(PlaceNames.same("Shop", "Shop Pharmacy"))
        assertFalse(PlaceNames.same("Shop", "Coinstar"))
    }

    @Test fun `an empty name matches nothing`() {
        assertFalse(PlaceNames.same("", ""))
        assertFalse(PlaceNames.same(null, null))
        assertFalse(PlaceNames.same("   ", "Shop"))
    }

    @Test fun `a non-latin name survives normalization`() {
        assertTrue(PlaceNames.same("Кафе Уют", "кафе уют"))
        assertTrue(PlaceNames.same("東京駅", "東京駅"))
    }
}

/** Pairs from a side by side of Google and the open archive over the Davis fixture (2026-09-21). */
class PlaceNamesMatchTest {
    private fun m(a: String, b: String, city: Set<String> = setOf("davis", "ca")) = PlaceNames.match(a, b, city)

    @Test fun `normalization folds the spellings the two sources disagree on`() {
        assertEquals("university park inn and suites", PlaceNames.normalized("University Park Inn & Suites, an Ascend Collection Hotel"))
        assertEquals("g street wunderbar", PlaceNames.normalized("G St Wunderbar"))
        assertEquals("noahs new york bagels", PlaceNames.normalized("Noah's NY Bagels"))
        assertEquals("boheme clothing and gifts", PlaceNames.normalized("Bohème Clothing & Gifts"))
        assertEquals("yuchan shokudo", PlaceNames.normalized("Yuchan Shokudo (formerly Yakitori Yuchan)"))
        assertEquals("james w childress dds", PlaceNames.normalized("James W. Childress, DDS Inc."))
        assertEquals("us bank", PlaceNames.normalized("U.S. Bank"))
        assertEquals("nugget", PlaceNames.normalized("Nugget #12"))
        assertEquals("keith grote dmd", PlaceNames.normalized("Dr. Keith Grote, DMD"))
        assertEquals("la quinta inn and suites", PlaceNames.normalized("La Quinta Inn & Suites by Wyndham"))
    }

    @Test fun `a descriptor tail is the same business`() {
        assertEquals(PlaceNames.Match.VARIANT, m("Circle K | Gas Station", "Circle K"))
        assertEquals(PlaceNames.Match.VARIANT, m("U.S. Bank Branch", "U.S. Bank"))
        assertEquals(PlaceNames.Match.VARIANT, m("Wells Fargo Bank", "Wells Fargo"))
        assertEquals(PlaceNames.Match.VARIANT, m("Golden 1 Credit Union - Davis", "Golden 1 Credit Union"))
        assertEquals(PlaceNames.Match.VARIANT, m("Hilton Garden Inn Davis Downtown", "Hilton Garden Inn"))
        assertEquals(PlaceNames.Match.VARIANT, m("CVS", "CVS Pharmacy"))
        assertEquals(PlaceNames.Match.VARIANT, m("FIT House Davis", "FIT House"))
        assertEquals(PlaceNames.Match.EXACT, m("Bank of America (with Drive-thru ATM)", "Bank of America"))
        assertTrue(PlaceNames.agree("Raising Cane's Chicken Fingers", "Raising Cane's"))
        assertEquals(PlaceNames.Match.EXACT, m("SPCA | Yolo County Thrift Store", "SPCA Yolo County Thrift Store"))
        assertEquals(PlaceNames.Match.EXACT, m("Activities and Recreation Center | UC Davis", "Activities and Recreation Center UC Davis"))
    }

    @Test fun `the identifying words agreeing is the same business`() {
        assertEquals(PlaceNames.Match.OVERLAP, m("Davis Dental Creations -Dr. Harsimran Bains", "Davis Dental Creations -dr. Simran Bains"))
        assertEquals(PlaceNames.Match.OVERLAP, m("Dunloe Brewing - The Local", "The Local by Dunloe Brewing"))
        assertEquals(PlaceNames.Match.OVERLAP, m("SpeeDee-Midas", "SpeeDee"))
        assertTrue(PlaceNames.agree("Sam's Mediterranean Cuisine", "Sam's Cuisine")) // a cuisine word is generic: VARIANT
        assertEquals(PlaceNames.Match.OVERLAP, m("Jennifer P. Clary, M.D.", "Jennifer Papazian Clary, M.d."))
    }

    @Test fun `shared generic words are not a match`() {
        assertEquals(PlaceNames.Match.NONE, m("Russell Park Apartments", "Orchard Park Apartments"))
        assertEquals(PlaceNames.Match.NONE, m("Havana Mini Mart", "Kobe Mini Mart"))
        assertEquals(PlaceNames.Match.NONE, m("Davis Senior High School", "Davis Adult & Community Education School"))
        assertEquals(PlaceNames.Match.NONE, m("Ergash Dental - Dr Nasrin Ergash", "Davis Dental"))
        assertEquals(PlaceNames.Match.NONE, m("Hair Studio", "Hair"))
        assertEquals(PlaceNames.Match.NONE, m("Arroyo Park", "Arroyo Pool"))
        assertEquals(PlaceNames.Match.NONE, m("Avid & Co.", "The Avid Reader Bookstore"))
        assertFalse(PlaceNames.agree("Fast & Easy Mart", "Chevron"))
    }

    @Test fun `a brand's other listings are variants, never exact`() {
        assertEquals(PlaceNames.Match.VARIANT, m("Zorpmart Fuel Station", "Zorpmart"))
        assertEquals(PlaceNames.Match.VARIANT, m("Petco Grooming", "Petco"))
        assertFalse(PlaceNames.same("Zorpmart Fuel Station", "Zorpmart"))
    }

    @Test fun `an overlap across two known kinds is two businesses on one lot`() {
        assertFalse(PlaceNames.sameBusiness("Cathcart Station Alfy's", "fuel", "Cathcart Station LLC", "food"))
        assertTrue(PlaceNames.sameBusiness("Cathcart Station Alfy's", "fuel", "Cathcart Station LLC", null))
        assertTrue(PlaceNames.sameBusiness("Safeway Pharmacy", "health", "Safeway", "shop")) // a VARIANT crosses kinds
        assertTrue(PlaceNames.sameFuelLot("fuel", "fuel", 11.0))
        assertTrue(PlaceNames.sameFuelLot("fuel", "fuel", 11.0, "16315", "16315"))
        assertTrue(PlaceNames.sameFuelLot("fuel", "fuel", 11.0, "16315", null))
        // Across the street: a different house number is a different lot at any distance.
        assertFalse(PlaceNames.sameFuelLot("fuel", "fuel", 11.0, "16315", "16326"))
        assertFalse(PlaceNames.sameFuelLot("fuel", "food", 11.0))
        assertFalse(PlaceNames.sameFuelLot("fuel", "fuel", 45.0))
        assertEquals("16315", PlaceNames.houseNumber("16315 State Route 9 SE"))
        assertEquals(null, PlaceNames.houseNumber("State Route 9 SE"))
    }

    @Test fun `city words come out of an address`() {
        assertEquals(setOf("davis", "ca"), PlaceNames.cityWords("239 G St, Davis, CA 95616"))
        assertTrue(PlaceNames.cityWords("239 G St").isEmpty())
    }
}
