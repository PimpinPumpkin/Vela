package app.vela.core.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKindTest {
    @Test fun aChainIsAName() {
        assertTrue(SearchKind.isName("Safeway", listOf("Safeway", "Safeway", "Safeway Fuel Station", "Safeway Pharmacy")))
        assertTrue(SearchKind.isName("peet's coffee", listOf("Peet's Coffee", "Peet's Coffee")))
    }

    @Test fun aKindOfPlaceIsNot() {
        assertFalse(SearchKind.isName("pharmacy", listOf("CVS", "Davis Pharmacy", "Rite Aid", "Kaiser Pharmacy")))
        assertFalse(SearchKind.isName("food", listOf("Burgers and Brew", "Zia's Delicatessen", "Food Co-op")))
        assertFalse(SearchKind.isName("Restaurants", listOf("Restaurants Plus", "Restaurants R Us")))
    }

    @Test fun nothingFoundIsNotAName() {
        assertFalse(SearchKind.isName("Safeway", emptyList()))
    }
}
