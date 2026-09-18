package app.vela.core.util

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
