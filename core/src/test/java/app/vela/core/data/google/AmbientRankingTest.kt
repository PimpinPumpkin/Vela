package app.vela.core.data.google

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the ambient map-POI ranking so the "tiny sushi place beats the Safeway that contains it" bug
 *  can't silently come back (e.g. from reordering the category-term fan-out). */
class AmbientRankingTest {

    private fun place(name: String, reviews: Int?, rating: Double?, dist: Double?, category: String? = null) =
        Place(
            id = name, name = name, location = LatLng(38.5, -121.7),
            rating = rating, reviewCount = reviews, distanceMeters = dist, category = category,
        )

    @Test fun `anchor store beats the in-store tenant at the same spot`() {
        val safeway = place("Safeway", 2000, 4.1, 500.0)
        val sushi = place("a sushi counter", 40, 4.6, 500.0) // same point, higher rating, far fewer reviews
        assertEquals("Safeway", rankAmbientPlaces(listOf(sushi, safeway)).first().name)
    }

    @Test fun `a landmark leads even a nearer low-signal place`() {
        // A map wants the recognizable place first, not whatever's nearest the center — this is the
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

    // The KIND of place counts too (user 2026-09-18), on the same scale the open-places bake uses,
    // so the two places sources rank alike instead of Google's review count deciding everything.
    @Test fun `an anchor outranks a busier neighbor of an everyday kind`() {
        val hospital = place("General Hospital", 300, 4.0, 200.0, "Hospital")
        val tacos = place("Busy Tacos", 1500, 4.5, 200.0, "Mexican restaurant")
        assertEquals("General Hospital", rankAmbientPlaces(listOf(tacos, hospital)).first().name)
    }

    @Test fun `a supermarket outranks the sushi counter inside it even on reviews alone`() {
        val market = place("Market", 400, 4.0, 100.0, "Supermarket")
        val sushi = place("Counter", 600, 4.6, 100.0, "Sushi restaurant")
        assertEquals("Market", rankAmbientPlaces(listOf(sushi, market)).first().name)
    }

    @Test fun `an everyday business is ranked exactly as before the prior existed`() {
        // NEUTRAL_PRIOR anchors the scale: the zoom x prominence label tiers are tuned against
        // these numbers, so a plain restaurant must not drift.
        val reviewsOnly = ln(201.0) * (0.6 + 4.2 / 10.0)
        assertEquals(reviewsOnly, ambientProminence(place("Cafe", 200, 4.2, null, "Coffee shop")), 1e-9)
    }

    @Test fun `no category at all sinks below an everyday business`() {
        val known = place("Shop", 50, 4.0, null, "Hardware store")
        val unknown = place("Whatever", 50, 4.0, null, null)
        assertTrue(ambientProminence(known) > ambientProminence(unknown))
    }

    @Test fun `a real landmark still beats an anchor kind with nothing behind it`() {
        val mall = place("Mega Mall", 9000, 4.3, 400.0, "Shopping mall")
        val cornerMarket = place("Corner Market", 12, 4.0, 100.0, "Grocery store")
        assertEquals("Mega Mall", rankAmbientPlaces(listOf(cornerMarket, mall)).first().name)
    }

    @Test fun `empty list is safe`() {
        assertEquals(emptyList<Place>(), rankAmbientPlaces(emptyList()))
    }
}
