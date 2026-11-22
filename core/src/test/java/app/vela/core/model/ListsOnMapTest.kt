package app.vela.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ListsOnMapTest {
    // Davis fixtures.
    private val cafe = ListPlace("p:cafe", "Cafe", 38.5449, -121.7405)
    private val park = ListPlace("p:park", "Park", 38.5600, -121.7600)
    private val library = ListPlace("p:library", "Library", 38.5380, -121.7500)

    private fun list(id: String, vararg places: ListPlace, onMap: Boolean = true) =
        PlaceList(id = id, name = id, places = places.toList(), onMap = onMap)

    /** The ids that draw, each with the list it draws for ("saved" for a quick-save). */
    private fun drawn(lists: List<PlaceList>, saved: List<SavedPlace> = emptyList(), open: String? = null) =
        ListsOnMap.pins(lists, saved, open).map {
            when (it) {
                is ListsOnMap.Pin.Listed -> it.place.id to it.list.id
                is ListsOnMap.Pin.Saved -> it.place.id to "saved"
            }
        }

    @Test fun `every list on draws every place and returns the same lists`() {
        val lists = listOf(list("food", cafe), list("outside", park))
        assertSame(lists, ListsOnMap.shown(lists, null))
        assertEquals(listOf("p:cafe" to "food", "p:park" to "outside"), drawn(lists))
    }

    @Test fun `a list switched off draws nothing`() {
        val lists = listOf(list("food", cafe), list("outside", park, onMap = false))
        assertEquals(listOf("food"), ListsOnMap.shown(lists, null).map { it.id })
        assertEquals(listOf("p:cafe" to "food"), drawn(lists))
    }

    @Test fun `a place in a list that is off still draws for a list that is on`() {
        val lists = listOf(list("hidden", cafe, park, onMap = false), list("food", cafe))
        assertEquals(listOf("p:cafe" to "food"), drawn(lists))
    }

    @Test fun `the open list draws whatever its switch`() {
        val lists = listOf(list("food", cafe), list("outside", park, library, onMap = false))
        assertEquals(listOf("food", "outside"), ListsOnMap.shown(lists, "outside").map { it.id })
        assertEquals(listOf("p:cafe" to "food", "p:park" to "outside", "p:library" to "outside"), drawn(lists, open = "outside"))
        // Opening a list that is on changes nothing.
        assertEquals(listOf("p:cafe" to "food"), drawn(lists, open = "food"))
    }

    @Test fun `a quick-save draws even when only a list that is off holds it`() {
        val lists = listOf(list("hidden", cafe, onMap = false))
        val saved = listOf(SavedPlace("p:cafe", "Cafe", 38.5449, -121.7405), SavedPlace("p:other", "Other", 38.55, -121.73))
        assertEquals(listOf("p:cafe" to "saved", "p:other" to "saved"), drawn(lists, saved))
        // A list that is on draws it once, in its own icon and color.
        assertEquals(listOf("p:cafe" to "food", "p:other" to "saved"), drawn(lists + list("food", cafe), saved))
    }

    @Test fun `a place in two lists that are on draws once, for the first`() {
        val lists = listOf(list("first", cafe), list("second", cafe, park))
        assertEquals(listOf("p:cafe" to "first", "p:park" to "second"), drawn(lists))
    }

    @Test fun `a custom map's hidden layer stays hidden`() {
        val layered = PlaceList(
            id = "map", name = "map",
            places = listOf(cafe.copy(layer = "Food"), park.copy(layer = "Parks")),
            hiddenLayers = listOf("Parks"),
        )
        assertEquals(listOf("p:cafe" to "map"), drawn(listOf(layered)))
    }

    @Test fun `the switch is written only when off and an old payload decodes as on`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = """[{"id":"food","name":"Food","places":[]}]"""
        assertTrue(json.decodeFromString<List<PlaceList>>(old).single().onMap)
        assertFalse(json.encodeToString(list("food")).contains("onMap"))
        val hidden = json.encodeToString(listOf(list("food", onMap = false)))
        assertTrue(hidden.contains("\"onMap\":false"))
        assertFalse(json.decodeFromString<List<PlaceList>>(hidden).single().onMap)
    }
}
