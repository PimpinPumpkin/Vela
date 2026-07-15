package app.vela.core

import app.vela.core.model.LatLng
import app.vela.core.nav.NavSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The arrive-step destination lines must degrade gracefully: a business has a name AND an
 * address, an offline geocoder hit has only an address (often just "123 Main St" or a bare
 * street), and a tapped point may have nothing but coordinates. Something must always show,
 * and no line may just repeat another. */
class DestinationDisplayTest {
    private val dest = LatLng(38.54491, -121.74052)

    @Test fun businessShowsNameAndAddress() {
        val (p, s) = NavSession.destinationDisplay("In-N-Out Burger", "1020 Olive Dr, Davis, CA 95616", dest)
        assertEquals("In-N-Out Burger", p)
        assertEquals("1020 Olive Dr, Davis, CA 95616", s)
    }

    @Test fun addressSearchDoesNotPrintTheAddressTwice() {
        // An address search's "name" IS the address (same string) - one line, no dup.
        val (p, s) = NavSession.destinationDisplay("1020 Olive Dr, Davis, CA 95616", "1020 olive dr, davis, ca 95616", dest)
        assertEquals("1020 Olive Dr, Davis, CA 95616", p)
        assertNull(s)
    }

    @Test fun addressOnlyPromotesToPrimary() {
        val (p, s) = NavSession.destinationDisplay("", "123 Main St", dest)
        assertEquals("123 Main St", p)
        assertNull(s)
    }

    @Test fun streetOnlyStillShows() {
        // Offline street-fallback tier: no house number, just the street name as the label.
        val (p, s) = NavSession.destinationDisplay("Main Street", null, dest)
        assertEquals("Main Street", p)
        assertNull(s)
    }

    @Test fun coordinatesAreTheLastResort() {
        val (p, s) = NavSession.destinationDisplay(null, "", dest)
        assertEquals("38.54491, -121.74052", p)
        assertNull(s)
    }

    @Test fun blankEverythingAndNoDestIsEmptyNotCrash() {
        val (p, s) = NavSession.destinationDisplay("  ", null, null)
        assertEquals("", p)
        assertNull(s)
    }

    @Test fun whitespaceIsTrimmedBeforeComparing() {
        val (p, s) = NavSession.destinationDisplay(" Mishka's Café ", " Mishka's Café ", dest)
        assertEquals("Mishka's Café", p)
        assertNull(s)
    }
}
