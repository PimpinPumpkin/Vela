package app.vela.core.data

import app.vela.core.model.PlaceList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportTargetTest {
    private fun titleId(title: String) = "list:import:" + title.hashCode().toString(16)

    @Test fun `a first import gets the id its source gives`() {
        val (existing, id) = importTarget(emptyList(), "Untitled map", "mymap:abc")
        assertNull(existing)
        assertEquals("list:import:" + "mymap:abc".hashCode().toString(16), id)
    }

    @Test fun `the same import saved again finds its list, whatever it is called now`() {
        val (_, id) = importTarget(emptyList(), "Untitled map", "mymap:abc")
        val saved = listOf(PlaceList(id = id, name = "Trails I like"))
        val (existing, again) = importTarget(saved, "Untitled map", "mymap:abc")
        assertEquals(saved.single(), existing)
        assertEquals(id, again)
    }

    @Test fun `another map with the same title is a new list`() {
        val (_, first) = importTarget(emptyList(), "Untitled map", "mymap:abc")
        val saved = listOf(PlaceList(id = first, name = "Untitled map"))
        val (existing, second) = importTarget(saved, "Untitled map", "mymap:xyz")
        assertNull(existing)
        assertNotEquals(first, second)
    }

    @Test fun `a list of the user's own with the same name is never the target`() {
        val own = listOf(PlaceList(id = "list:1700000000000", name = "Coffee"))
        val (existing, id) = importTarget(own, "Coffee", "list:https://maps.app.goo.gl/x")
        assertNull(existing)
        assertNotEquals(own.single().id, id)
    }

    @Test fun `a list saved before imports carried a source is found by its title`() {
        val old = listOf(PlaceList(id = titleId("Weekend"), name = "Weekend"))
        assertEquals(old.single(), importTarget(old, "Weekend", "list:https://maps.app.goo.gl/x").first)
    }

    @Test fun `an old import that was renamed is left alone, and the new list gets a free id`() {
        val old = listOf(PlaceList(id = titleId("Weekend"), name = "Renamed"))
        val (existing, id) = importTarget(old, "Weekend", null)
        assertNull(existing)
        assertEquals(titleId("Weekend") + ":2", id)
    }
}
