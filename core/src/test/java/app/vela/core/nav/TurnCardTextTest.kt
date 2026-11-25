package app.vela.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnCardTextTest {
    private val en = app.vela.core.i18n.EnNavStrings

    private fun card(full: String, road: String?, shields: List<String>) =
        TurnCardText.of(full, road, en.signDestination(full), shields)

    // Turns onto a named road.

    @Test
    fun turnPutsTheRoadOnItsOwn() {
        assertEquals(
            TurnCardText("Turn right onto", "W Covell Blvd", ""),
            card("Turn right onto W Covell Blvd", "W Covell Blvd", emptyList()),
        )
    }

    @Test
    fun wordsAfterTheRoadBecomeTheSub() {
        assertEquals(
            TurnCardText("Turn left onto", "Russell Blvd", "toward Downtown"),
            card("Turn left onto Russell Blvd toward Downtown", "Russell Blvd", emptyList()),
        )
    }

    @Test
    fun roundaboutKeepsItsExitPhrase() {
        assertEquals(
            TurnCardText("At the roundabout, take the 1st exit onto", "5th St", ""),
            card("At the roundabout, take the 1st exit onto 5th St", "5th St", listOf("Exit onto")),
        )
    }

    @Test
    fun roadFirstLanguagesKeepTheVerbAsTheSub() {
        assertEquals(
            TurnCardText("", "Russell Blvd", "を右折"),
            TurnCardText.split("Russell Blvd を右折", "Russell Blvd"),
        )
    }

    // Ramps, exits, forks and merges with a sign.

    @Test
    fun exitShowsOnlyTheDestinationsNotOnAShield() {
        // The exit tab and the I 5 shield are chips; the sentence and "I 5" are redundant.
        assertEquals(
            TurnCardText("", "Los Angeles, Sacramento", ""),
            card("Take exit 4A toward I 5 S: Los Angeles, Sacramento", null, listOf("Exit 4A", "I 5")),
        )
    }

    @Test
    fun exitDestinationsAreShortenedLikeASign() {
        assertEquals(
            TurnCardText("", "J St, Downtown", ""),
            card("Take exit 519B toward J Street, Downtown", null, listOf("Exit 519B")),
        )
    }

    @Test
    fun aTownIsNotShortenedLikeAStreet() {
        assertEquals(
            TurnCardText("", "Sacramento, South Lake Tahoe", ""),
            card("Keep slight left toward Sacramento, South Lake Tahoe", null, listOf("US 50")),
        )
    }

    @Test
    fun bothDirectionsOfAShieldAreLeftToIt() {
        // The ramp serves I 5 both ways and the card's shield is the bare "I 5".
        assertEquals(
            TurnCardText("", "Redding, Los Angeles", ""),
            card("Take exit 4A toward I 5 N, I 5 S: Redding, Los Angeles", null, listOf("Exit 4A", "I 5")),
        )
    }

    @Test
    fun forkWithoutAShieldShowsItsDestinations() {
        assertEquals(
            TurnCardText("", "Russell Blvd, Anderson Rd", ""),
            card("Keep slight right toward Russell Boulevard, Anderson Road", null, emptyList()),
        )
    }

    @Test
    fun aRouteRoadThatIsAShieldGivesWayToTheCities() {
        // road = "I 80" is already the chip: the cities are what is left to read.
        assertEquals(
            TurnCardText("", "Sacramento, Reno", ""),
            card("Keep slight left toward I 80 E: Sacramento, Reno", "I 80", listOf("I 80")),
        )
    }

    @Test
    fun aSignThatIsOnlyAShieldLeavesTheTextEmpty() {
        assertEquals(TurnCardText("", "", ""), card("Take the exit toward I 5", null, listOf("I 5")))
    }

    @Test
    fun aLongerRouteNumberIsNotTheShorterShield() {
        assertEquals(
            TurnCardText("", "I 50", ""),
            card("Take the ramp toward I 50", null, listOf("I 5")),
        )
    }

    // Fallbacks.

    @Test
    fun noRoadAndNoSignKeepsTheSentence() {
        assertNull(card("Head north", null, emptyList()))
        assertNull(card("Turn right", "  ", emptyList()))
        assertNull(card("Turn right onto Main St", "Elm St", emptyList()))
    }

    @Test
    fun aRoadInsideAnotherWordIsNotMatched() {
        assertNull(TurnCardText.split("Turn right onto Elmwood Dr", "Elm"))
        assertEquals(
            TurnCardText("Pass Elmwood Dr, then turn onto", "Elm", ""),
            TurnCardText.split("Pass Elmwood Dr, then turn onto Elm", "Elm"),
        )
    }

    @Test
    fun separatorsAroundTheRoadAreDropped() {
        assertEquals(
            TurnCardText("Continue on", "5th St", "toward Davis"),
            TurnCardText.split("Continue on 5th St, toward Davis", "5th St"),
        )
    }

    @Test
    fun englishSignDestination() {
        assertEquals("I 5 S: Los Angeles, Sacramento", en.signDestination("Take exit 4A toward I 5 S: Los Angeles, Sacramento"))
        assertNull(en.signDestination("Turn right onto Main St"))
    }
}
