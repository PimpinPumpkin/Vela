package app.vela.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The order and hidden rules behind the quick-category chips (Settings > Search > Quick search
 *  buttons), on keys alone. The fixture is a short default list; the real one has 16. */
class QuickCategoriesTest {
    private val defaults = listOf("Restaurants", "Coffee", "Gas station", "Parking", "Bars")

    @Test fun `no saved order is the default order`() {
        assertEquals(defaults, QuickChipOrder.arrange(defaults, emptyList()))
    }

    @Test fun `a saved order leads and the rest keep their default places`() {
        assertEquals(
            listOf("Parking", "Gas station", "Restaurants", "Coffee", "Bars"),
            QuickChipOrder.arrange(defaults, listOf("Parking", "Gas station")),
        )
    }

    @Test fun `a saved key that is no longer a chip is skipped`() {
        assertEquals(
            listOf("Parking", "Restaurants", "Coffee", "Gas station", "Bars"),
            QuickChipOrder.arrange(defaults, listOf("Parking", "Casinos")),
        )
    }

    @Test fun `a chip that comes back takes the end of the list`() {
        // Bars is not in the catalog while "Hide adult categories" is on; an order saved then
        // omits it, and it returns at the end once the catalog has it again.
        val withoutBars = defaults - "Bars"
        val saved = QuickChipOrder.moved(QuickChipOrder.arrange(withoutBars, emptyList()), "Parking", -3)
        assertEquals(listOf("Parking", "Restaurants", "Coffee", "Gas station"), saved)
        assertEquals(listOf("Parking", "Restaurants", "Coffee", "Gas station", "Bars"), QuickChipOrder.arrange(defaults, saved))
    }

    @Test fun `a repeated key counts once`() {
        assertEquals(
            listOf("Coffee", "Restaurants", "Gas station", "Parking", "Bars"),
            QuickChipOrder.arrange(defaults, listOf("Coffee", "Coffee")),
        )
    }

    @Test fun `moving goes by one place either way`() {
        assertEquals(listOf("Restaurants", "Gas station", "Coffee", "Parking", "Bars"), QuickChipOrder.moved(defaults, "Gas station", -1))
        assertEquals(listOf("Restaurants", "Coffee", "Parking", "Gas station", "Bars"), QuickChipOrder.moved(defaults, "Gas station", 1))
    }

    @Test fun `moving past an end stops at it`() {
        assertEquals(listOf("Parking", "Restaurants", "Coffee", "Gas station", "Bars"), QuickChipOrder.moved(defaults, "Parking", -9))
        assertEquals(listOf("Restaurants", "Coffee", "Gas station", "Bars", "Parking"), QuickChipOrder.moved(defaults, "Parking", 9))
    }

    @Test fun `a move that changes nothing returns the same list`() {
        assertSame(defaults, QuickChipOrder.moved(defaults, "Restaurants", -1))
        assertSame(defaults, QuickChipOrder.moved(defaults, "Bars", 1))
        assertSame(defaults, QuickChipOrder.moved(defaults, "Coffee", 0))
        assertSame(defaults, QuickChipOrder.moved(defaults, "Casinos", 1))
    }

    @Test fun `hidden chips leave the rows but keep their place in the order`() {
        val arranged = QuickChipOrder.arrange(defaults, listOf("Parking"))
        val hidden = setOf("Gas station")
        assertEquals(listOf("Parking", "Restaurants", "Coffee", "Bars"), arranged.filter { it !in hidden })
        assertEquals(listOf("Parking", "Restaurants", "Coffee", "Gas station", "Bars"), arranged)
    }

    @Test fun `the fuel chip is keyed by one name whatever its query says`() {
        assertEquals("Gas station", QuickCategories.FUEL_KEY)
    }

    @Test fun `a chip's own query is a kind of place, a brand is not`() {
        for (q in listOf("EV charging station", "Things to do", "Groceries", "Gas station", "restaurants ")) {
            assertEquals(q, true, QuickCategories.isChipQuery(q))
        }
        assertEquals(false, QuickCategories.isChipQuery("EVgo"))
        assertEquals(false, QuickCategories.isChipQuery("Chevron"))
    }

    @Test fun `a store named for a chip's kind does not cut the list to itself`() {
        // "groceries" is not on the generic list (its singular is), so a store named for it is the
        // only name that holds it; it is not the whole name of most matches, so the filter keeps
        // every store, and a chip's query skips the filter anyway.
        fun p(id: String, name: String) = app.vela.core.model.Place(id = id, name = name, location = app.vela.core.model.LatLng(38.54, -121.74), category = "Grocery store")
        val food = listOf(p("a", "Groceries Plus"), p("b", "Davis Food Co-op"), p("c", "Nugget Markets"))
        assertEquals(3, app.vela.core.search.QueryNameFilter.apply("Groceries", food).size)
        assertEquals(true, QuickCategories.isChipQuery("Groceries"))
    }
}
