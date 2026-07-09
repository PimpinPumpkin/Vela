package app.vela.core

import app.vela.core.config.Calibration
import app.vela.core.data.google.parse.SearchParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Departments come from the [118] list: name@[0], weekly hours@[3][0], status@[3][1][4][0].
 *  Shapes captured from a live Davis-area probe (HoursProbeTest), anonymized. */
class DepartmentsParseTest {

    private fun entryWith118(deps: String): JsonArray {
        val node = JsonArray(List(119) { i -> if (i == 118) Json.parseToJsonElement(deps) else JsonNull })
        return JsonArray(listOf(JsonNull, node))
    }

    @Test
    fun `parses named departments, strips the store prefix, keeps service windows`() {
        val deps = """[
          [ "Acme Market Pharmacy", null, null,
            [ [ ["Wednesday",3,[2026,7,8],[["9 AM–1:30 PM",[[9],[13,30]]],["2–9 PM",[[14],[21]]]],0,1] ],
              [ null, null, null, null, ["Closed · Opens 9 AM Thu"] ] ] ],
          [ "Delivery", null, null,
            [ [ ["Wednesday",3,[2026,7,8],[["8 AM–9 PM",[[8],[21]]]],0,1] ],
              [ null, null, null, null, ["Open · Closes 9 PM"] ] ] ],
          [ null, null, null, [] ],
          [ "Acme Market Kiosk", null, null, [ [], [] ] ]
        ]"""
        val out = SearchParser.parseDepartments(entryWith118(deps), Calibration.DEFAULT_PATHS, "Acme Market")

        assertEquals(2, out.size) // nameless + schedule-less entries skipped
        assertEquals("Pharmacy", out[0].name) // store prefix stripped
        assertEquals(listOf("Wednesday: 9 AM–1:30 PM, 2–9 PM"), out[0].hours) // split shift joined
        assertEquals("Closed · Opens 9 AM Thu", out[0].statusText)
        assertEquals(false, out[0].openNow)
        assertEquals("Delivery", out[1].name) // service window passes through
        assertEquals(true, out[1].openNow)
    }

    @Test
    fun `absent or empty 118 block means no departments`() {
        assertTrue(SearchParser.parseDepartments(entryWith118("[]"), Calibration.DEFAULT_PATHS, "X").isEmpty())
        val bare = JsonArray(listOf(JsonNull, JsonArray(List(50) { JsonNull })))
        assertTrue(SearchParser.parseDepartments(bare, Calibration.DEFAULT_PATHS, "X").isEmpty())
    }

    @Test
    fun `status-only department survives, hours-only openNow is null`() {
        val deps = """[
          [ "Bakery", null, null, [ [], [ null, null, null, null, ["Open 24 hours"] ] ] ],
          [ "Deli", null, null, [ [ ["Wednesday",3,[2026,7,8],[["7 AM–7 PM",[[7],[19]]]],0,1] ], [] ] ]
        ]"""
        val out = SearchParser.parseDepartments(entryWith118(deps), Calibration.DEFAULT_PATHS, "Acme")
        assertEquals(2, out.size)
        assertTrue(out[0].hours.isEmpty())
        assertEquals(true, out[0].openNow)
        assertNull(out[1].statusText)
        assertNull(out[1].openNow)
    }
}
