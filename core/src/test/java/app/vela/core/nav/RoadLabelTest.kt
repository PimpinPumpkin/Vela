package app.vela.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType

class RoadLabelTest {
    @Test fun `a numbered road with a name of its own shows the name`() {
        assertEquals("Richards Boulevard", roadLabel("Richards Boulevard", "CA 113"))
        assertEquals("Lincoln Highway", roadLabel("Lincoln Highway", "US 50"))
        assertEquals("9th Street", roadLabel("9th Street", "SR 9"))
    }

    @Test fun `a name that only says the number shows the number`() {
        assertEquals("SR 9", roadLabel("State Route 9", "SR 9"))
        assertEquals("SR 9", roadLabel("Highway 9", "SR 9"))
        assertEquals("CR 99D", roadLabel("County Road 99D", "CR 99D"))
        assertEquals("B 27", roadLabel("Bundesstraße 27", "B 27"))
    }

    @Test fun `an Interstate shows its number even with a name`() {
        assertEquals("I 80", roadLabel("Dwight D. Eisenhower Highway", "I 80"))
        assertEquals("I-5 N", roadLabel("Golden State Freeway", "I-5 N"))
    }

    @Test fun `one of the two is enough, and neither is nothing`() {
        assertEquals("F Street", roadLabel("F Street", null))
        assertEquals("F Street", roadLabel("F Street", " "))
        assertEquals("CA 113", roadLabel(null, "CA 113"))
        assertEquals("CA 113", roadLabel("", "CA 113"))
        assertNull(roadLabel(null, " "))
    }

    @Test fun `a named freeway shows its number, an expressway or a highway its name`() {
        assertEquals("US 50", roadLabel("Capital City Freeway", "US 50"))
        assertEquals("US 101", roadLabel("Bayshore Freeway", "US 101"))
        assertEquals("A 7", roadLabel("Autoroute du Soleil", "A 7"))
        assertEquals("Lawrence Expressway", roadLabel("Lawrence Expressway", "CR G2"))
        assertEquals("Lincoln Highway", roadLabel("Lincoln Highway", "US 50"))
        assertEquals("Capital City Freeway", roadLabel("Capital City Freeway", null))
        assertEquals("Freeway", roadLabel("Freeway", "US 50"))
    }

    @Test fun `a number takes the sign's compass letter, a name never does`() {
        assertEquals("I 80 E", roadLabel(null, "I 80", "E"))
        assertEquals("US 50 E", roadLabel("Capital City Freeway", "US 50", "E"))
        assertEquals("CA 113 N", roadLabel("State Route 113", "CA 113", "N"))
        assertEquals("Richards Boulevard", roadLabel("Richards Boulevard", "CA 113", "N"))
        assertEquals("I-5 N", roadLabel(null, "I-5 N", "S")) // the number already says
    }

    @Test fun `a name that carries the direction lends it to the number`() {
        assertEquals("US 50 E", roadLabel("US Highway 50 East", "US 50"))
        assertEquals("I 80 W", roadLabel("I 80 West", null))
        assertEquals("CA 113 N", roadLabel("CA 113 North", " "))
        assertEquals("Lake Boulevard West", roadLabel("Lake Boulevard West", null))
        assertEquals("East", roadLabel("East", null))
        assertEquals("I 80 E", roadLabel("I 80 East", "I 80 East"))
        assertEquals("I 80 E", roadLabel(null, "I 80 East", "W")) // the road's own word wins
        assertEquals("US 50 W", roadLabel("US 50 West", "US 50 West"))
    }

    private fun step(text: String, lat: Double, road: String? = null, ref: String? = null, lng: Double = -121.74) =
        Maneuver(ManeuverType.CONTINUE, text, LatLng(lat, lng), 2_000.0, 90.0, road = road, ref = ref)

    @Test fun `the ramp's sign gives the direction`() {
        val mans = listOf(
            step("Turn right onto Richards Boulevard", 38.540, "Richards Boulevard"),
            step("Take the ramp on the right toward I 80 East: Sacramento", 38.541, "I 80", "I 80", lng = -121.73),
            step("Take exit 81 toward Enterprise Boulevard", 38.560, lng = -121.60),
        )
        assertEquals("E", signedHeading(mans, 1, "I 80"))
        assertEquals("I 80 E", roadLabelAt(mans, 2, 500.0))
        assertEquals("Richards Boulevard", roadLabelAt(mans, 1, 500.0))
    }

    @Test fun `the sign is found on the unnamed steps before the road`() {
        val mans = listOf(
            step("Merge onto I 80", 38.545, "I 80", "I 80"),
            step("Take exit 70 toward CA 113: Woodland", 38.546),
            step("Keep slight right toward CA 113 North: Woodland", 38.548),
            step("Merge onto CA 113", 38.550, "State Route 113", "CA 113"),
            step("Take the exit", 38.600),
        )
        assertEquals("N", signedHeading(mans, 3, "CA 113"))
        assertEquals("CA 113 N", roadLabelAt(mans, 4, 900.0))
        // On the fork itself the pill names the road it leads onto, with the same letter.
        assertEquals("CA 113 N", roadLabelAt(mans, 3, 100.0, orNext = true))
        assertNull(roadLabelAt(mans, 3, 100.0))
    }

    @Test fun `a plain turn onto a numbered road has no direction`() {
        val mans = listOf(
            step("Take the ramp toward CA 113 North: Woodland", 38.540, "State Route 113", "CA 113"),
            step("Turn right onto Covell Boulevard", 38.560, "Covell Boulevard"),
            step("Turn left onto State Route 113", 38.561, "State Route 113", "CA 113"),
            step("Turn right", 38.540),
        )
        assertNull("the earlier sign is behind another road", signedHeading(mans, 2, "CA 113"))
        assertEquals("CA 113", roadLabelAt(mans, 3, 900.0))
    }

    @Test fun `a sign with both directions is settled by which way the leg runs`() {
        fun trip(nextLat: Double, nextLng: Double) = listOf(
            step("Take exit 4A toward I 5 North, I 5 South: Redding, Los Angeles", 38.570),
            step("Merge onto I 5", 38.571, "I 5", "I 5", lng = -121.51),
            step("Take the exit", nextLat, lng = nextLng),
        )
        assertEquals("N", signedHeading(trip(38.590, -121.51), 1, "I 5"))
        assertEquals("S", signedHeading(trip(38.550, -121.51), 1, "I 5"))
        assertNull("running east, it is neither", signedHeading(trip(38.571, -121.48), 1, "I 5"))
        assertNull("too short to tell", signedHeading(trip(38.5725, -121.51), 1, "I 5"))
        assertEquals("I 5 N", roadLabelAt(trip(38.590, -121.51), 2, 400.0))
    }

    @Test fun `a sign the road runs against gives none`() {
        val against = listOf(
            step("Take the exit toward CA 113 North: Woodland", 38.60),
            step("Turn right onto State Route 113", 38.60, "State Route 113", "CA 113"),
            step("Turn left", 38.55),
        )
        assertNull("signed north, driven south", signedHeading(against, 1, "CA 113"))
        assertEquals("CA 113", roadLabelAt(against, 2, 900.0))
    }

    @Test fun `an exit number is not read as a route`() {
        val mans = listOf(
            step("Take exit 80 east of town toward US 50", 38.57),
            step("Merge onto I 80", 38.571, "I 80", "I 80"),
        )
        assertNull(signedHeading(mans, 1, "I 80"))
    }
}
