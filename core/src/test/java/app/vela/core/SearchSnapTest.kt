package app.vela.core

import app.vela.core.config.Calibration
import app.vela.core.data.google.parse.PopularTimesParser
import app.vela.core.data.google.parse.SearchParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSnapTest {

    /** A JSON array literal of [size] nulls with the given index → raw-JSON overrides. */
    private fun arr(size: Int, vararg at: Pair<Int, String>): String {
        val m = at.toMap()
        return (0 until size).joinToString(",", "[", "]") { m[it] ?: "null" }
    }

    /** Searching a bare address that's a business must snap to the business listed
     *  "at this place" (`[0][1][0][14][68][i][0]`), not return the geocoded address. */
    @Test fun addressSnapsToTheBusinessAtThisPlace() {
        // place node: name [11], lat/lng at [9][2]/[9][3] — the minimum toPlace needs.
        val place = arr(12, 9 to "[null,null,38.54,-121.73]", 11 to "\"In-N-Out Burger\"")
        val node14 = arr(69, 68 to "[[$place]]") // [68] = at-this-place list = [[placeNode]]
        val z = arr(15, 14 to node14) // [14] = the geocoded node
        val y = "[$z]" // [0] = z
        val x = arr(2, 1 to y) // [1] = y
        val root = "[$x]" // length 1 → no [64] results list, so the snap path runs

        val result = SearchParser.parse("1020 Olive Dr Davis", Json.parseToJsonElement(root))
        assertEquals(1, result.places.size)
        assertEquals("In-N-Out Burger", result.places[0].name)
        assertEquals(38.54, result.places[0].location.lat, 1e-9)
    }

    /** "People also search for": the focused result's sibling list at root[2][11][0], each
     *  entry [featureId, name, [[_,_,lat,lng], …, rating@6]]. (Verified on-device against a
     *  real response — 8 related salons for "a nail salon".) */
    @Test fun parsesPeopleAlsoSearchFor() {
        val geo = arr(7, 0 to "[null,null,38.58,-121.49]", 6 to "4.7")     // [0]=coords, [6]=rating
        val entry = arr(3, 0 to "\"0xAAA:0xBBB\"", 1 to "\"Glow Nails Spa\"", 2 to geo)
        val node2 = arr(12, 11 to "[[$entry]]")                            // node[11][0] = [entry]
        val root = arr(3, 2 to node2)                                      // root[2] = node2

        val sims = SearchParser.parseSimilarPlaces(Json.parseToJsonElement(root), Calibration.DEFAULT_PATHS)
        assertEquals(1, sims.size)
        assertEquals("Glow Nails Spa", sims[0].name)
        assertEquals("0xAAA:0xBBB", sims[0].featureId)
        assertEquals(4.7, sims[0].rating!!, 1e-9)
        assertEquals(38.58, sims[0].location.lat, 1e-9)
        assertEquals(-121.49, sims[0].location.lng, 1e-9)
    }

    /** parse() attaches the similar list to the PRIMARY place (a focused single result). */
    @Test fun attachesSimilarToPrimaryPlace() {
        val place = arr(12, 9 to "[null,null,38.54,-121.73]", 11 to "\"Cuts Salon\"")
        val z = arr(15, 14 to place)            // single result at [0][1][0][14]
        val x = arr(2, 1 to "[$z]")             // [0][1] = [z]
        val geo = arr(7, 0 to "[null,null,38.5,-121.7]", 6 to "4.5")
        val entry = arr(3, 0 to "\"0xCCC:0xDDD\"", 1 to "\"Other Salon\"", 2 to geo)
        val node2 = arr(12, 11 to "[[$entry]]")
        val root = arr(3, 0 to x, 2 to node2) // [0]=focused branch, [2]=similar branch

        val result = SearchParser.parse("cuts salon", Json.parseToJsonElement(root))
        assertEquals(1, result.places.size)
        assertEquals(1, result.places[0].similarPlaces.size)
        assertEquals("Other Salon", result.places[0].similarPlaces[0].name)
    }

    /** The summary-node enrichment: PopularTimesParser lifts review count / address / rating
     *  off the FULL focused node (feature-id-matched) into PlaceDetails, so a sparse snapped
     *  place can be backfilled. Regression for "Bellagio showed 4.4 with no count". */
    @Test fun enrichmentLiftsCountAddressRatingFromFocusedNode() {
        val rating = arr(9, 7 to "4.5", 8 to "305")                 // [4][7]=rating, [4][8]=count
        val geo = arr(4, 2 to "38.58", 3 to "-121.49")             // [9][2]/[9][3]=lat/lng
        val place = arr(40, 4 to rating, 9 to geo, 10 to "\"0xAAA:0xBBB\"",
            11 to "\"Test Spa\"", 39 to "\"123 Main St, Davis, CA\"")
        val z = arr(15, 14 to place)                                // single result at [0][1][0][14]
        val x = arr(2, 1 to "[$z]")
        val body = ")]}'\n[$x]"

        val d = PopularTimesParser.parse(body, "0xAAA:0xBBB")
        assertEquals(305, d!!.reviewCount)
        assertEquals("123 Main St, Davis, CA", d.address)
        assertEquals(4.5, d.rating!!, 1e-9)
    }
}
