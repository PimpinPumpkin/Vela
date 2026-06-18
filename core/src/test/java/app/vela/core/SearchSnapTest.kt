package app.vela.core

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
}
