package app.vela.core.data

import app.vela.core.model.LabelPlace
import app.vela.core.model.LatLng
import app.vela.core.model.ListPlace
import app.vela.core.model.Place
import app.vela.core.model.PlaceList
import app.vela.core.model.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A place starred or listed from a map label before its listing was known, and the listing
 * written onto it later. Fixture: downtown Davis. 0.0001 degrees of latitude is about 11 m.
 */
class KeptLabelListingTest {
    private val at = LatLng(38.5449, -121.7405)
    private fun north(m: Double) = LatLng(at.lat + m / 111_195.0, at.lng)

    private fun listing(name: String, where: LatLng = north(15.0), fid: String? = "0x1:0x2", address: String? = "620 G St, Davis, CA 95616", closed: Boolean = false) =
        Place(id = "g:" + name.hashCode(), name = name, location = where, category = "Pet store", address = address, featureId = fid, permanentlyClosed = closed)

    private fun star(name: String = "Petco", id: String = LabelPlace.basemapId("Petco"), where: LatLng = at, bare: Boolean = true) =
        SavedPlace(id, name, where.lat, where.lng, bare = bare, icon = "emoji:P", pinned = true)

    // ---- which search hit is the kept label's listing ----

    @Test fun `a listing that agrees by name near the label is its listing`() {
        assertEquals("Petco Grooming", keptLabelListing("Petco", at, null, listOf(listing("Petco Grooming")))?.name)
    }

    @Test fun `the listing named exactly as kept leads a nearer one that only agrees`() {
        val hits = listOf(listing("Petco Grooming", north(10.0), "0x1:0xa"), listing("Petco", north(60.0), "0x1:0xb"))
        assertEquals("0x1:0xb", keptLabelListing("Petco", at, null, hits)?.featureId)
    }

    @Test fun `among listings that agree alike the nearest is taken`() {
        val hits = listOf(listing("Petco", north(120.0), "0x1:0xa"), listing("Petco", north(20.0), "0x1:0xb"))
        assertEquals("0x1:0xb", keptLabelListing("Petco", at, null, hits)?.featureId)
    }

    @Test fun `a neighbor under another name is never the listing, however near`() {
        assertNull(keptLabelListing("Fast & Easy Mart", at, null, listOf(listing("Chevron", north(5.0)))))
    }

    @Test fun `a branch out of reach, a closed listing and one with no feature id are not taken`() {
        assertNull(keptLabelListing("Petco", at, null, listOf(listing("Petco", north(KEPT_LABEL_MAX_M + 50.0)))))
        assertNull(keptLabelListing("Petco", at, null, listOf(listing("Petco", closed = true))))
        assertNull(keptLabelListing("Petco", at, null, listOf(listing("Petco", fid = null))))
        assertNull(keptLabelListing("Petco", at, null, listOf(listing("Petco", fid = ""))))
    }

    @Test fun `another house number than the kept address rules a listing out`() {
        val kept = "1451 W Covell Blvd, Davis, CA 95616"
        assertNull(keptLabelListing("Petco", at, kept, listOf(listing("Petco"))))
        assertEquals("Petco", keptLabelListing("Petco", at, kept, listOf(listing("Petco", address = "1451 West Covell Boulevard, Davis, CA 95616")))?.name)
        // No number on one side decides nothing.
        assertEquals("Petco", keptLabelListing("Petco", at, kept, listOf(listing("Petco", address = null)))?.name)
        assertEquals("Petco", keptLabelListing("Petco", at, "Davis, CA", listOf(listing("Petco")))?.name)
    }

    // ---- the star ----

    @Test fun `a star kept from a basemap label under the label's name awaits its listing`() {
        assertTrue(star().awaitsListing)
        assertFalse("not marked a point: an ordinary saved place", star(bare = false).awaitsListing)
        assertFalse("renamed: a label of the user's own", star(name = "The pet shop").awaitsListing)
        assertFalse("a dropped pin", SavedPlace("pin:38.5449,-121.7405", "Dropped pin", at.lat, at.lng, bare = true).awaitsListing)
        assertFalse("a contact's label on a geocoded address", SavedPlace("g:1", "Petco", at.lat, at.lng, bare = true).awaitsListing)
    }

    @Test fun `the listing goes onto the star and the name, icon and pin stay`() {
        val l = listing("Petco Grooming")
        val out = linkSaved(listOf(star()), LabelPlace.basemapId("Petco"), at, l).single()
        assertFalse(out.bare)
        assertFalse(out.isPoint)
        assertEquals(l.location, out.location)
        assertEquals("620 G St, Davis, CA 95616", out.address)
        assertEquals("Petco", out.name)
        assertEquals(LabelPlace.basemapId("Petco"), out.id)
        assertEquals("emoji:P", out.icon)
        assertTrue(out.pinned)
    }

    @Test fun `a star that awaits nothing is left alone`() {
        val id = LabelPlace.basemapId("Petco")
        val renamed = listOf(star(name = "The pet shop"))
        assertSame(renamed, linkSaved(renamed, id, at, listing("Petco")))
        val plain = listOf(star(bare = false))
        assertSame(plain, linkSaved(plain, id, at, listing("Petco")))
        // The id is the name's hash: the same chain's other branch across town shares it.
        val elsewhere = listOf(star(where = north(900.0)))
        assertSame(elsewhere, linkSaved(elsewhere, id, at, listing("Petco")))
    }

    @Test fun `a star on a sheet that turned out a listing is told from a contact's label`() {
        val sheet = Place(id = "0x1:0x2", name = "Petco", location = at, rating = 4.4, featureId = "0x1:0x2")
        assertTrue(SavedPlace("0x1:0x2", "Petco", at.lat, at.lng, bare = true).isStarOf(sheet))
        assertFalse(SavedPlace("0x1:0x2", "Sam Lee", at.lat, at.lng, bare = true).isStarOf(sheet))
        assertFalse(SavedPlace("0x1:0x2", "Petco", at.lat, at.lng).isStarOf(sheet))
        assertFalse(SavedPlace("0x1:0x9", "Petco", at.lat, at.lng, bare = true).isStarOf(sheet))
    }

    // ---- list entries ----

    private fun entry(id: String, where: LatLng = at, fid: String? = null) =
        ListPlace(id = id, name = "Petco", lat = where.lat, lng = where.lng, note = "cat food", featureId = fid, icon = "emoji:P")

    @Test fun `the listing goes onto every list entry kept from the label and what the user set stays`() {
        val id = LabelPlace.basemapId("Petco")
        val other = entry("g:99", fid = "0x9:0x9")
        val lists = listOf(PlaceList("a", "Errands", places = listOf(other, entry(id))), PlaceList("b", "Pets", places = listOf(entry(id))), PlaceList("c", "Empty"))
        val out = linkListing(lists, id, at, listing("Petco Grooming"))
        for (l in out.take(2)) {
            val e = l.places.last()
            assertEquals("0x1:0x2", e.featureId)
            assertEquals("620 G St, Davis, CA 95616", e.address)
            assertEquals("Petco", e.name); assertEquals("cat food", e.note); assertEquals("emoji:P", e.icon)
            assertEquals(id, e.id); assertEquals(at, e.location)
            assertFalse(e.awaitsListing)
        }
        assertSame(other, out[0].places[0])
        assertSame(lists[2], out[2])
    }

    @Test fun `an open-places entry is found by its own id wherever the feature sits now`() {
        val lists = listOf(PlaceList("a", "Pets", places = listOf(entry("overture:abc", north(140.0)))))
        assertEquals("0x1:0x2", linkListing(lists, "overture:abc", at, listing("Petco"))[0].places[0].featureId)
        // A basemap label's id is shared by every place of that name: only the one at the label.
        val far = listOf(PlaceList("a", "Pets", places = listOf(entry(LabelPlace.basemapId("Petco"), north(140.0)))))
        assertSame(far, linkListing(far, LabelPlace.basemapId("Petco"), at, listing("Petco")))
    }

    @Test fun `nothing is rewritten when no entry awaits a listing`() {
        val id = LabelPlace.basemapId("Petco")
        val linked = listOf(PlaceList("a", "Pets", places = listOf(entry(id, fid = "0x7:0x7"))))
        assertSame(linked, linkListing(linked, id, at, listing("Petco")))
        val imported = listOf(PlaceList("a", "Pets", places = listOf(entry("mymap:1f"), entry("imp:38.54490,-121.74050"))))
        assertSame(imported, linkListing(imported, "mymap:1f", at, listing("Petco")))
        val awaiting = listOf(PlaceList("a", "Pets", places = listOf(entry(id))))
        assertSame("a listing with no feature id links nothing", awaiting, linkListing(awaiting, id, at, listing("Petco", fid = null)))
    }

    @Test fun `a list that already holds the listing does not get it twice`() {
        val id = LabelPlace.basemapId("Petco")
        val both = listOf(PlaceList("a", "Pets", places = listOf(entry(id), entry("g:77", fid = "0x1:0x2"))))
        assertSame(both, linkListing(both, id, at, listing("Petco")))
    }

    @Test fun `only a label's own id is the label`() {
        assertTrue(LabelPlace.isLabel("poi:123")); assertTrue(LabelPlace.isLabel("overture:osm:n1"))
        for (id in listOf("g:1:385449", "pin:38.5,-121.7", "addr:12@38.5,-121.7", "mymap:1f", "import:0x1:0x2", "gtfs:stop")) assertFalse(id, LabelPlace.isLabel(id))
        assertTrue(LabelPlace.same("poi:1", north(10.0), "poi:1", at))
        assertFalse(LabelPlace.same("poi:1", north(LabelPlace.SAME_LABEL_M + 20.0), "poi:1", at))
        assertFalse(LabelPlace.same("poi:2", at, "poi:1", at))
    }
}
