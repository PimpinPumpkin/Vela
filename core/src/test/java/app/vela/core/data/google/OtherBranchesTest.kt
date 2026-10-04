package app.vela.core.data.google

import app.vela.core.data.google.parse.SearchParser
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherBranchesTest {
    private val focus = Place(id = "g:1", name = "Acme Curry", location = LatLng(38.5449, -121.7405), featureId = "0x1:0x1")
    private fun p(name: String, lat: Double, lng: Double, fid: String) = Place(id = "g:$fid", name = name, location = LatLng(lat, lng), featureId = fid)

    @Test fun `same-name entries in People also search for become results`() {
        val also = listOf(
            p("Acme Curry", 38.5600, -121.7600, "0x2:0x2"),
            p("Acme Curry Restaurant & Banquet Hall", 38.5300, -121.7000, "0x3:0x3"),
            p("Some Other Indian Place", 38.5500, -121.7500, "0x4:0x4"),
            p("Acme Curry", 38.5449, -121.7405, "0x1:0x1"), // the focused one itself
        )
        val got = SearchParser.otherBranches("acme curry", also, focus)
        assertEquals(listOf("Acme Curry", "Acme Curry Restaurant & Banquet Hall"), got.map { it.name })
    }

    @Test fun `a short or unrelated query adds nothing`() {
        val also = listOf(p("Acme Curry", 38.56, -121.76, "0x2:0x2"))
        assertEquals(0, SearchParser.otherBranches("ac", also, focus).size)
        assertEquals(0, SearchParser.otherBranches("pizza", listOf(p("Other Grill", 38.56, -121.76, "0x4:0x4")), focus).size)
    }

    private fun node(name: String, lat: Double, lng: Double, extra: String = ""): String {
        val cells = MutableList(100) { "null" }
        cells[9] = "[null,null,$lat,$lng]"; cells[11] = "\"$name\""
        if (extra.isNotEmpty()) cells[99] = extra
        return cells.joinToString(",", "[", "]")
    }
    private fun root(focus: String) = Json.parseToJsonElement("[[null,[[" + List(14) { "null" }.joinToString(",") + ",$focus]]]]")

    @Test fun `a focused reply reads the branches out of its related block`() {
        val related = "[[[\"People also search for\",[[\"0x2:0x2\",${node("Acme Curry", 38.56, -121.76)}],[\"0x3:0x3\",${node("Other Place", 38.55, -121.75)}]]]]]"
        val r = SearchParser.parse("acme curry", root(node("Acme Curry", 38.5449, -121.7405, related)))
        assertEquals(2, r.places.size)
    }

    @Test fun `a focused reply says so and names its focus`() {
        val r = SearchParser.parse("acme curry", root(node("Acme Curry", 38.5449, -121.7405)))
        assertTrue(r.focusedSingle)
        assertEquals("Acme Curry", r.focus?.name)
    }

    @Test fun `branch names are matched both ways and unrelated places are not`() {
        val focus = Place(id = "g:1", name = "Mikuni", location = LatLng(38.5449, -121.7405))
        fun named(n: String) = Place(id = "x", name = n, location = LatLng(38.6, -121.5))
        assertTrue(SearchParser.isBranch("mikuni japanese restaurant", focus, named("Mikuni")))
        assertTrue(SearchParser.isBranch("acme curry", focus, named("Acme Curry Restaurant & Banquet Hall")))
        assertFalse(SearchParser.isBranch("mikuni japanese restaurant", focus, named("Yuchan Shokudo")))
        assertFalse(SearchParser.isBranch("mikuni japanese restaurant", focus, named("Sushi")))
    }

    @Test fun `the same branch from two sources is one row and keeps its address`() {
        val bare = Place(id = "a", name = "Acme Curry", location = LatLng(38.5600, -121.7600), featureId = "0x0:0x9")
        val full = Place(id = "b", name = "Acme Curry", location = LatLng(38.56002, -121.76001), featureId = "0x5:0x9", address = "1 Main St")
        val other = Place(id = "c", name = "Acme Curry", location = LatLng(38.50, -121.70), address = "9 Elm St")
        val got = SearchParser.mergeBranches(listOf(focus, bare, full, other))
        assertEquals(listOf("g:1", "b", "c"), got.map { it.id })
    }
}
