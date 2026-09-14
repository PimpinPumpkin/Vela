package app.vela.core.data

import app.vela.core.data.google.ambientProminence
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the OSM/Overture prominence ordering — the open-data stand-in for the review-count
 * ranking in `AmbientRankingTest`. These pin the ORDERING the dot layer depends on, not the
 * constants: the weights are untuned and expected to move, so assert relative order and never
 * an absolute score.
 */
class OsmProminenceTest {

    private fun place(
        name: String,
        category: String? = null,
        website: String? = null,
        phone: String? = null,
        hours: List<String> = emptyList(),
        address: String? = null,
        dist: Double? = null,
        brand: String? = null,
        wikidata: String? = null,
        wikipedia: String? = null,
    ) = Place(
        id = "osm:$name", name = name, location = LatLng(38.5, -121.7),
        category = category, website = website, phone = phone, hours = hours,
        address = address, distanceMeters = dist,
        brand = brand, wikidata = wikidata, wikipedia = wikipedia,
    )

    // ---- suppression -----------------------------------------------------------------------

    @Test fun `street furniture scores exactly zero`() {
        assertEquals(0.0, OsmProminence.score(category = "bench"), 0.0)
        assertEquals(0.0, OsmProminence.score(category = "post_box"), 0.0)
        assertEquals(0.0, OsmProminence.score(category = "parking_space"), 0.0)
    }

    @Test fun `a thoroughly tagged vending machine still loses to a bare cafe`() {
        // Suppression must be absolute — completeness bonuses cannot rescue furniture, or OSM's
        // best-mapped bench wins the label slot over the business next to it.
        val machine = OsmProminence.score(
            category = "vending_machine",
            hasWebsite = true, hasPhone = true, hasHours = true, hasAddress = true,
        )
        val cafe = OsmProminence.score(category = "cafe")
        assertTrue("furniture=$machine should lose to cafe=$cafe", machine < cafe)
    }

    @Test fun `a road intersection never outranks a business`() {
        // The Google layer's own comment names "a road intersection" as junk the fan-out drags in.
        assertTrue(
            OsmProminence.score(category = "traffic_signals") <
                OsmProminence.score(category = "restaurant"),
        )
    }

    // ---- category ordering -----------------------------------------------------------------

    @Test fun `priors descend anchor to strong to normal to minor`() {
        val hospital = OsmProminence.score(category = "hospital")
        val library = OsmProminence.score(category = "library")
        val restaurant = OsmProminence.score(category = "restaurant")
        val playground = OsmProminence.score(category = "playground")
        assertTrue("$hospital > $library", hospital > library)
        assertTrue("$library > $restaurant", library > restaurant)
        assertTrue("$restaurant > $playground", restaurant > playground)
    }

    @Test fun `an unknown category sits below a known business but above furniture`() {
        val unknown = OsmProminence.score(category = "quantum_widget_emporium")
        assertTrue(unknown < OsmProminence.score(category = "restaurant"))
        assertTrue(unknown > OsmProminence.score(category = "bench"))
    }

    @Test fun `a null category is treated as unknown, not suppressed`() {
        assertTrue(OsmProminence.score(category = null) > 0.0)
    }

    // ---- the humanized-category gotcha -----------------------------------------------------

    @Test fun `humanized category from toPlace scores the same as the raw tag`() {
        // OverpassPois.toPlace stores "Fast food", not "fast_food". If normalize() regresses,
        // EVERY OSM POI silently falls to PRIOR_UNKNOWN and the whole ranking flattens — which
        // would look like "the scorer does nothing" rather than a crash.
        assertEquals(
            OsmProminence.score(category = "fast_food"),
            OsmProminence.score(category = "Fast food"),
            0.0,
        )
        assertEquals(
            OsmProminence.score(category = "place_of_worship"),
            OsmProminence.score(category = "Place of worship"),
            0.0,
        )
    }

    @Test fun `humanized furniture is still suppressed`() {
        assertEquals(0.0, OsmProminence.score(category = "Vending machine"), 0.0)
    }

    // ---- notability ------------------------------------------------------------------------

    @Test fun `wikidata lifts a place above an identical one without it`() {
        val tagged = OsmProminence.score(category = "museum", wikidata = true)
        val plain = OsmProminence.score(category = "museum")
        assertTrue("$tagged > $plain", tagged > plain)
    }

    @Test fun `wikidata and wikipedia do not double count`() {
        // They co-occur on the same objects; summing would let one fact score twice.
        val both = OsmProminence.score(category = "museum", wikidata = true, wikipedia = true)
        val justWikidata = OsmProminence.score(category = "museum", wikidata = true)
        assertEquals(justWikidata, both, 0.0)
    }

    @Test fun `a branded chain outranks an unbranded peer in the same category`() {
        // The Safeway case: recognizable BECAUSE it is a chain, which is what the review-count
        // ranking surfaced first.
        assertTrue(
            OsmProminence.score(category = "supermarket", brand = true) >
                OsmProminence.score(category = "supermarket"),
        )
    }

    @Test fun `notability cannot lift furniture off zero`() {
        assertEquals(0.0, OsmProminence.score(category = "bench", wikidata = true), 0.0)
    }

    // ---- completeness and confidence -------------------------------------------------------

    @Test fun `each filled attribute raises the score`() {
        val bare = OsmProminence.score(category = "restaurant")
        val full = OsmProminence.score(
            category = "restaurant",
            hasWebsite = true, hasPhone = true, hasHours = true, hasAddress = true,
        )
        assertTrue("$full > $bare", full > bare)
    }

    @Test fun `completeness cannot promote a shop past a landmark category`() {
        val fullShop = OsmProminence.score(
            category = "florist",
            hasWebsite = true, hasPhone = true, hasHours = true, hasAddress = true,
        )
        val bareHospital = OsmProminence.score(category = "hospital")
        assertTrue("$fullShop should stay under $bareHospital", fullShop < bareHospital)
    }

    @Test fun `low Overture confidence scores below high confidence`() {
        assertTrue(
            OsmProminence.score(category = "restaurant", overtureConfidence = 0.2) <
                OsmProminence.score(category = "restaurant", overtureConfidence = 0.95),
        )
    }

    @Test fun `absent confidence lands between the extremes`() {
        val neutral = OsmProminence.score(category = "restaurant")
        assertTrue(neutral > OsmProminence.score(category = "restaurant", overtureConfidence = 0.0))
        assertTrue(neutral < OsmProminence.score(category = "restaurant", overtureConfidence = 1.0))
    }

    @Test fun `confidence out of range is clamped, not extrapolated`() {
        assertEquals(
            OsmProminence.score(category = "cafe", overtureConfidence = 1.0),
            OsmProminence.score(category = "cafe", overtureConfidence = 4.2),
            0.0,
        )
    }

    // ---- the Place overload ----------------------------------------------------------------

    @Test fun `the Place overload reads the tags toPlace actually keeps`() {
        val rich = place("Co-op", "supermarket", website = "https://x", phone = "555", hours = listOf("Mo-Fr 9-5"), address = "1 Main St")
        val bare = place("Co-op Two", "supermarket")
        assertTrue(OsmProminence.score(rich) > OsmProminence.score(bare))
    }

    @Test fun `the Place overload can now reach a landmark tier`() {
        // Before brand/wikidata/wikipedia were plumbed through toPlace this overload topped out
        // below a notable place's score, and the whole open dot layer ranked flat. A wikidata-tagged
        // museum must now beat an exhaustively tagged ordinary shop.
        val museum = place("City Museum", "museum", wikidata = "Q12345")
        val shop = place(
            "Best Florist", "florist",
            website = "https://x", phone = "555", hours = listOf("Mo-Fr 9-5"), address = "1 Main St",
        )
        assertTrue(
            "museum=${OsmProminence.score(museum)} should beat shop=${OsmProminence.score(shop)}",
            OsmProminence.score(museum) > OsmProminence.score(shop),
        )
    }

    @Test fun `a branded Place outranks an unbranded peer`() {
        assertTrue(
            OsmProminence.score(place("Safeway", "supermarket", brand = "Safeway")) >
                OsmProminence.score(place("Corner Grocer", "supermarket")),
        )
    }

    @Test fun `notability on a Place still cannot lift furniture`() {
        assertEquals(0.0, OsmProminence.score(place("Odd bench", "bench", wikidata = "Q1")), 0.0)
    }

    @Test fun `blank notability tags read as absent`() {
        val blank = place("X", "museum", brand = "  ", wikidata = "", wikipedia = " ")
        assertEquals(OsmProminence.score(place("Y", "museum")), OsmProminence.score(blank), 0.0)
    }

    @Test fun `the Place overload maps each field to the right parameter`() {
        // Guards the wiring itself: if a field is ever hooked to the wrong parameter these diverge.
        val p = place("Museum", "museum", website = "https://x", phone = "555", address = "1 Main St", wikidata = "Q1")
        assertEquals(
            OsmProminence.score(
                category = "museum",
                hasWebsite = true, hasPhone = true, hasAddress = true, wikidata = true,
            ),
            OsmProminence.score(p),
            0.0,
        )
    }

    @Test fun `blank strings do not count as filled attributes`() {
        val blank = place("X", "cafe", website = "  ", phone = "")
        assertEquals(OsmProminence.score(place("Y", "cafe")), OsmProminence.score(blank), 0.0)
    }

    // ---- ranking ---------------------------------------------------------------------------

    @Test fun `a landmark leads a nearer piece of furniture`() {
        val bench = place("A bench", "bench", dist = 10.0)
        val hospital = place("County Hospital", "hospital", dist = 3000.0)
        assertEquals("County Hospital", rankOsmPlaces(listOf(bench, hospital)).first().name)
    }

    @Test fun `distance only breaks an exact prominence tie`() {
        val far = place("Far", "cafe", dist = 900.0)
        val near = place("Near", "cafe", dist = 100.0)
        assertEquals("Near", rankOsmPlaces(listOf(far, near)).first().name)
    }

    @Test fun `empty list is safe`() {
        assertEquals(emptyList<Place>(), rankOsmPlaces(emptyList()))
    }

    // ---- scale compatibility with the Google layer -----------------------------------------

    @Test fun `scores share the ambient prominence range so one pool can sort`() {
        // A mixed rollout sorts Google-sourced and OSM-sourced POIs together. If the ranges
        // diverge, one source wins every collision regardless of merit.
        val topOsm = OsmProminence.score(
            category = "hospital", wikidata = true,
            hasWebsite = true, hasPhone = true, hasHours = true, hasAddress = true,
            overtureConfidence = 1.0,
        )
        val bigGoogle = ambientProminence(
            Place(id = "g", name = "Mega Mall", location = LatLng(38.5, -121.7), rating = 4.3, reviewCount = 10_000),
        )
        // Same order of magnitude, neither source dominating by construction.
        assertTrue("osm top=$topOsm vs google big=$bigGoogle", topOsm in 5.0..12.0)
        assertTrue("google big=$bigGoogle", bigGoogle in 5.0..12.0)
    }
}
