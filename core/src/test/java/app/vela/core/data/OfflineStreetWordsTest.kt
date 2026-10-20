package app.vela.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The street words the offline geocoder tries for a typed address (OfflineAddressStore). */
class OfflineStreetWordsTest {
    private fun tries(s: String) = OfflineAddressStore.streetWordTries(s)

    @Test fun `a town, state or ZIP after a comma is not part of the street`() {
        assertEquals(listOf("west", "covell", "boulevard"), tries("W Covell Blvd, Davis, CA 95616").first())
    }

    @Test fun `trailing words are dropped one at a time when there is no comma`() {
        assertEquals(
            listOf(
                listOf("main", "street", "davis", "ca"),
                listOf("main", "street", "davis"),
                listOf("main", "street"),
                listOf("main"),
            ),
            tries("Main Street Davis CA"),
        )
    }

    @Test fun `a half-typed last word falls back to the name`() {
        assertEquals(listOf("covell"), tries("Covell Bl").last())
        assertEquals(listOf(listOf("main", "south"), listOf("main")), tries("Main S"))
    }

    @Test fun `it never ends on a direction or a street type alone`() {
        assertEquals(listOf(listOf("north", "street")), tries("North Street"))
        assertEquals(listOf(listOf("street")), tries("Street"))
        assertEquals(emptyList<List<String>>(), tries("A"))
    }

    @Test fun `abbreviations match their full forms`() {
        assertEquals(OfflineAddressStore.normalizeStreet("W Covell Blvd"), OfflineAddressStore.normalizeStreet("West Covell Boulevard"))
    }
}
