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

    @Test
    fun aWordInManyDifferentNamesIsAKindOfPlace() {
        // Captured along I-80 from Davis: bubble tea shops, only some named for it.
        val boba = listOf(
            place("Teaspoon Davis Boba Cafe", "Bubble tea store"),
            place("Why Not Boba Cafe?", "Bubble tea store"),
            place("Wushiland Boba Sacramento", "Bubble tea store"),
            place("T4 Tea 4", "Bubble tea store"),
            place("E Ka Tea", "Bubble tea store"),
        )
        assertEquals(boba.map { it.name }, QueryNameFilter.apply("boba", boba).map { it.name })
        val tacos = listOf(
            place("Lenchitos Tacos", "Mexican restaurant"),
            place("Sal's Tacos", "Taco restaurant"),
            place("Taqueria Maya's", "Mexican restaurant"),
            place("Casa Jalisco", "Mexican restaurant"),
        )
        assertEquals(tacos.map { it.name }, QueryNameFilter.apply("tacos", tacos).map { it.name })
        val pho = listOf(
            place("Pho Tasty", "Vietnamese restaurant"),
            place("Pho King 4", "Vietnamese restaurant"),
            place("Amber Hazel Vietnamese Kitchen", "Vietnamese restaurant"),
        )
        assertEquals(pho.map { it.name }, QueryNameFilter.apply("pho", pho).map { it.name })
    }

    @Test
    fun aStoreNamedForItsKindDoesNotNarrowTheList() {
        val stores = listOf(
            place("Bob's Groceries", "Grocery store"),
            place("Safeway", "Grocery store"),
            place("Nugget Markets", "Grocery store"),
        )
        assertEquals(stores.map { it.name }, QueryNameFilter.apply("groceries", stores).map { it.name })
    }

    @Test
    fun aBrandNarrowsWithStoreNumbersAndDescribingWords() {
        val drug = listOf(
            place("CVS Pharmacy", "Pharmacy"),
            place("CVS", "Pharmacy"),
            place("CVS Pharmacy #9876", "Pharmacy"),
            place("Walgreens", "Pharmacy"),
        )
        assertEquals(listOf("CVS Pharmacy", "CVS", "CVS Pharmacy #9876"), QueryNameFilter.apply("cvs", drug).map { it.name })
    }

    @Test
    fun aNumberAtTheEndOfANameIsPartOfIt() {
        // "Hoang 99" and "Hoang 2000" are two places named for a word, not a chain of "Hoang".
        val numbered = listOf(
            place("Hoang 99", "Vietnamese restaurant"),
            place("Hoang 2000", "Vietnamese restaurant"),
            place("Hoang Kitchen", "Vietnamese restaurant"),
            place("Saigon Bowl", "Vietnamese restaurant"),
        )
        assertEquals(numbered.map { it.name }, QueryNameFilter.apply("hoang", numbered).map { it.name })
        // A store number marked as one is not part of the name.
        val chain = listOf(place("Jiffy Lube #1234", "Oil change service"), place("Jiffy Lube #88", "Oil change service"), place("Valvoline", "Oil change service"))
        assertEquals(listOf("Jiffy Lube #1234", "Jiffy Lube #88"), QueryNameFilter.apply("jiffy lube", chain).map { it.name })
    }

    @Test
    fun aPlaceNamedJustTheFoodIsNotABrand() {
        val tacos = listOf(place("Tacos", "Mexican restaurant"), place("Casa Jalisco", "Mexican restaurant"), place("Taqueria Maya's", "Mexican restaurant"))
        assertEquals(tacos.map { it.name }, QueryNameFilter.apply("tacos", tacos).map { it.name })
        val boba = listOf(place("Boba", "Bubble tea store"), place("T4 Tea 4", "Bubble tea store"))
        assertEquals(boba.map { it.name }, QueryNameFilter.apply("boba", boba).map { it.name })
    }

    @Test
    fun aBrandKeepsWholeWordsNotTheStartOfOtherNames() {
        val arco = listOf(place("ARCO", "Gas station"), place("Arco", "Gas station"), place("Arcos Tacos", "Mexican restaurant"), place("Shell", "Gas station"))
        assertEquals(listOf("ARCO", "Arco"), QueryNameFilter.apply("arco", arco).map { it.name })
        // A name typed in part still finds its places when no name has the word whole.
        val food = listOf(place("McDonald's", "Fast food restaurant"), place("McDonald's", "Fast food restaurant"), place("Burger King", "Fast food restaurant"))
        assertEquals(listOf("McDonald's", "McDonald's"), QueryNameFilter.apply("mcdo", food).map { it.name })
    }

    @Test
    fun theRemoteListOfFoodWordsReplacesTheCompiledOne() {
        val tacos = listOf(place("Tacos", "Mexican restaurant"), place("Tacos", "Mexican restaurant"), place("Casa Jalisco", "Mexican restaurant"))
        // Compiled list: "taco" describes, every place stays.
        assertEquals(3, QueryNameFilter.apply("tacos", tacos, QueryNameFilter.FOOD).size)
        // A remote list without it: "Tacos" is the whole name of most matches, so the list narrows.
        assertEquals(2, QueryNameFilter.apply("tacos", tacos, setOf("boba")).size)
        val c = app.vela.core.config.CalibrationStore.parseBundle("""{"version": 99, "nameFilterFoodWords": ["boba", "Phở", ""]}""")!!
        assertEquals(listOf("boba", "Phở"), c.nameFilterFoodWords)
    }
}
