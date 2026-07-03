package app.vela.core.data.google

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the ambient map-POI ranking so the "tiny sushi place beats the Safeway that contains it" bug
 *  can't silently come back (e.g. from reordering the category-term fan-out). */
class AmbientRankingTest {

    private fun place(name: String, reviews: Int?, rating: Double?, dist: Double?) =
        Place(
            id = name, name = name, location = LatLng(38.5, -121.7),
            rating = rating, reviewCount = reviews, distanceMeters = dist,
        )

    @Test fun `anchor store beats the in-store tenant at the same spot`() {
        val safeway = place("Safeway", 2000, 4.1, 500.0)
        val sushi = place("Zen Sushi", 40, 4.6, 500.0) // same point, higher rating, far fewer reviews
        assertEquals("Safeway", rankAmbientPlaces(listOf(sushi, safeway)).first().name)
    }

    @Test fun `a landmark leads even a nearer low-signal place`() {
        // A map wants the recognizable place first, not whatever's nearest the centre — this is the
        // real device case: Safeway(1273) must lead a near 0-review mobile mechanic / care home.
        val nearJunk = place("Always Mobile Mechanics", 0, null, 80.0)
        val mall = place("Mega Mall", 9000, 4.3, 4000.0)
        assertEquals("Mega Mall", rankAmbientPlaces(listOf(nearJunk, mall)).first().name)
    }

    @Test fun `rating breaks ties among equal review counts`() {
        val a = place("A", 300, 3.9, 300.0)
        val b = place("B", 300, 4.7, 300.0)
        assertEquals("B", rankAmbientPlaces(listOf(a, b)).first().name)
    }

    @Test fun `distance only breaks an exact prominence tie`() {
        val far = place("Far", 200, 4.2, 900.0)
        val near = place("Near", 200, 4.2, 100.0) // identical prominence → nearer wins
        assertEquals("Near", rankAmbientPlaces(listOf(far, near)).first().name)
    }

    @Test fun `prominence rises with review count`() {
        assertTrue(
            ambientProminence(place("big", 5000, 4.0, null)) >
                ambientProminence(place("small", 20, 4.0, null)),
        )
    }

    @Test fun `empty list is safe`() {
        assertEquals(emptyList<Place>(), rankAmbientPlaces(emptyList()))
    }
}
