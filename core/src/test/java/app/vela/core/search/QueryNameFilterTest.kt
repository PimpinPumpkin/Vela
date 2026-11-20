package app.vela.core.search

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class QueryNameFilterTest {
    private fun place(name: String, category: String) = Place(id = name, name = name, location = LatLng(38.54, -121.74), category = category)

    private val gas = listOf(
        place("Chevron", "Gas station"),
        place("Shell", "Gas station"),
        place("Chevron - Station 4521", "Gas station"),
        place("Arco", "Gas station"),
        place("Valero", "Gas station"),
    )

    @Test
    fun aBrandKeepsOnlyItsOwnPlaces() {
        assertEquals(listOf("Chevron", "Chevron - Station 4521"), QueryNameFilter.apply("Chevron", gas).map { it.name })
    }

    @Test
    fun theStartOfAWordIsEnough() {
        assertEquals(listOf("Chevron", "Chevron - Station 4521"), QueryNameFilter.apply("chev", gas).map { it.name })
        val food = listOf(place("McDonald's", "Fast food restaurant"), place("Burger King", "Fast food restaurant"))
        assertEquals(listOf("McDonald's"), QueryNameFilter.apply("mcdo", food).map { it.name })
        assertEquals(listOf("McDonald's"), QueryNameFilter.apply("McDonalds", food).map { it.name })
    }

    @Test
    fun punctuationAndSpacingDoNotMatter() {
        val stores = listOf(place("Wal-Mart", "Department store"), place("Target", "Department store"))
        assertEquals(listOf("Wal-Mart"), QueryNameFilter.apply("wal mart", stores).map { it.name })
        assertEquals(listOf("Wal-Mart"), QueryNameFilter.apply("Wal-Mart", stores).map { it.name })
    }

    @Test
    fun accentsDoNotMatter() {
        val cafes = listOf(place("Café Roma", "Coffee shop"), place("Blue Bottle", "Coffee shop"))
        assertEquals(listOf("Café Roma"), QueryNameFilter.apply("cafe roma", cafes).map { it.name })
    }

    @Test
    fun aKindOfPlaceKeepsEverything() {
        // No name holds "gas station".
        assertEquals(gas.map { it.name }, QueryNameFilter.apply("gas station", gas).map { it.name })
        // Two names hold "pizza", but all three places have it as their category.
        val pizza = listOf(
            place("Pizza Hut", "Pizza restaurant"),
            place("Mamma Roma", "Pizza restaurant"),
            place("Domino's Pizza", "Pizza delivery"),
        )
        assertEquals(pizza.map { it.name }, QueryNameFilter.apply("pizza", pizza).map { it.name })
        // "Restaurant" is in some names and in every category.
        val eat = listOf(place("Restaurant Le Bistro", "Restaurant"), place("Chez Marie", "Restaurant"))
        assertEquals(eat.map { it.name }, QueryNameFilter.apply("restaurant", eat).map { it.name })
    }

    @Test
    fun aQueryThatMatchesNoNameKeepsEverything() {
        assertEquals(gas.map { it.name }, QueryNameFilter.apply("Sunoco", gas).map { it.name })
    }

    @Test
    fun aBlankQueryOrNoPlacesIsLeftAlone() {
        assertEquals(gas.map { it.name }, QueryNameFilter.apply("  ", gas).map { it.name })
        assertEquals(emptyList<Place>(), QueryNameFilter.apply("Chevron", emptyList()))
    }

    @Test
    fun everyWordOfTheQueryHasToMatch() {
        val two = listOf(place("Blue Bottle", "Coffee shop"), place("Blue's Garage", "Auto repair"), place("Red Rooster", "Coffee shop"))
        assertEquals(listOf("Blue Bottle"), QueryNameFilter.apply("blue bottle", two).map { it.name })
    }

    @Test
    fun aDescribingWordIsAKindOfPlaceEvenWhenNoCategoryHoldsIt() {
        // "Burger" is in two names and in no category here. It is still a kind of place.
        val burgers = listOf(
            place("Burger King", "Fast food restaurant"),
            place("McDonald's", "Fast food restaurant"),
            place("Bob's Burgers", "Hamburger restaurant"),
        )
        assertEquals(burgers.map { it.name }, QueryNameFilter.apply("burger", burgers).map { it.name })
        assertEquals(burgers.map { it.name }, QueryNameFilter.apply("burgers", burgers).map { it.name })
        val bbq = listOf(place("Smoky's BBQ", "Barbecue restaurant"), place("The Pit Stop", "Barbecue restaurant"))
        assertEquals(bbq.map { it.name }, QueryNameFilter.apply("bbq", bbq).map { it.name })
    }

    @Test
    fun aBrandWithADescribingWordStillNarrows() {
        val food = listOf(place("Burger King", "Fast food restaurant"), place("King Taco", "Mexican restaurant"), place("McDonald's", "Fast food restaurant"))
        assertEquals(listOf("Burger King"), QueryNameFilter.apply("burger king", food).map { it.name })
    }

    @Test
    fun aDescribingWordInANameQueryMayBeInTheCategory() {
        assertEquals(listOf("Shell"), QueryNameFilter.apply("shell gas station", gas).map { it.name })
    }
}
