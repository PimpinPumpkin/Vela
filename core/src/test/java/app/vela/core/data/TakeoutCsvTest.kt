package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoutCsvTest {
    private val file = "﻿Title,Note,URL,Comment\n" +
        "\"Davis Food Co-op\",\"good bread, open late\",\"https://www.google.com/maps/place/Davis+Food+Co-op/data=!4m2!3m1!1s0x8085299a7b6c6f0b:0x9ab2c3d4e5f60718\",\n" +
        "\"\",,\"https://www.google.com/maps/search/38.5449,-121.7405\",\n" +
        "\"Old pin\",\"line one\nline two\",\"http://maps.google.com/?cid=10281119596374313554\",\"a \"\"quoted\"\" word\"\n" +
        ",,,\n"

    @Test fun `a takeout list is recognized by its header and read with quotes and line breaks`() {
        assertTrue(TakeoutCsv.isTakeoutCsv(file))
        assertFalse(TakeoutCsv.isTakeoutCsv("[{\"id\":\"x\"}]"))
        assertFalse(TakeoutCsv.isTakeoutCsv("name,lat,lng\nA,1,2\n"))
        val rows = TakeoutCsv.parse(file)
        assertEquals(3, rows.size)
        assertEquals("Davis Food Co-op", rows[0].title)
        assertEquals("good bread, open late", rows[0].note)
        assertEquals("", rows[1].title)
        assertEquals("line one\nline two", rows[2].note)
        assertEquals("a \"quoted\" word", rows[2].comment)
    }

    @Test fun `a header in the account's language is read by position around the URL column`() {
        val french = "Titre,Note,URL,Tags,Commentaire\n" +
            "Davis Ace Hardware,,https://www.google.com/maps/place/Davis+Ace+Hardware/data=!4m2!3m1!1s0x80852900aaaaaaaa:0x1234abcd,,a note at the end\n" +
            "Pinned,,\"https://www.google.com/maps/search/38.5449,-121.7405\",,\n" // the export quotes a link with a comma
        assertTrue(TakeoutCsv.isTakeoutCsv(french))
        val rows = TakeoutCsv.parse(french)
        assertEquals(2, rows.size)
        assertEquals("Davis Ace Hardware", rows[0].title)
        assertEquals("a note at the end", rows[0].comment)
        assertEquals(null, rows[0].note)
        assertTrue(rows[1].url.contains("/search/38.5449,-121.7405"))
        // A sheet of coordinates is not a Takeout list, whatever its first column says.
        assertFalse(TakeoutCsv.isTakeoutCsv("name,lat,lng\nA,1,2\n"))
        assertEquals(listOf(0, 2, 1, 4), TakeoutCsv.columns(listOf("Titre", "Note", "URL", "Tags", "Commentaire"))!!.toList())
        assertEquals(listOf(0, 2, 1, 3), TakeoutCsv.columns(listOf("Title", "Note", "URL", "Comment"))!!.toList())
    }

    @Test fun `a listing link carries the feature id and no point`() {
        val l = TakeoutCsv.readLink(TakeoutCsv.parse(file)[0].url)!!
        assertEquals("Davis Food Co-op", l.name)
        assertFalse(l.hasPoint)
        assertEquals("0x8085299a7b6c6f0b:0x9ab2c3d4e5f60718", l.featureId)
        assertEquals("9ab2c3d4e5f60718", l.featureKey)
        assertEquals("9ab2c3d4e5f60718", TakeoutCsv.featureKey("0x8085299A7B6C6F0B:0x9AB2C3D4E5F60718"))
        // The feature id's first half is an S2 cell: the listing says roughly where it is.
        val cell = l.cellCenter()!!
        assertEquals(38.55, cell.lat, 0.05)
        assertEquals(-121.73, cell.lng, 0.05)
        assertNull(TakeoutCsv.featureKey(null))
    }

    @Test fun `a dropped pin link is a point and a cid link is a key`() {
        val pin = TakeoutCsv.readLink(TakeoutCsv.parse(file)[1].url)!!
        assertTrue(pin.hasPoint)
        assertEquals(38.5449, pin.lat!!, 1e-9)
        assertEquals(-121.7405, pin.lng!!, 1e-9)
        assertNull(pin.name)
        val cid = TakeoutCsv.readLink(TakeoutCsv.parse(file)[2].url)!!
        assertFalse(cid.hasPoint)
        assertEquals("8eaddfcd1b32ca52", cid.featureKey)
        assertNull(cid.featureId)
        assertNull(TakeoutCsv.readLink("https://example.com/not-maps"))
    }

    @Test fun `a shared place link with its own pin reads the pin`() {
        val l = TakeoutCsv.readLink("https://www.google.com/maps/place/Mikuni/@38.54,-121.74,17z/data=!3m1!4b1!4m6!3m5!1s0x1:0x2!8m2!3d38.5451!4d-121.7398")!!
        assertEquals(38.5451, l.lat!!, 1e-9)
        assertEquals(-121.7398, l.lng!!, 1e-9)
        assertEquals("2", l.featureKey)
    }

    private fun place(name: String, key: String) =
        Place(id = "$name:$key", name = name, location = LatLng(38.5, -121.7), featureId = "0x1:0x$key")

    @Test fun `a listing row takes the hit by feature id, else the one hit by name`() {
        val coop = place("Davis Food Co-op", "aa")
        val mikuni = place("Mikuni", "bb")
        val mikuni2 = place("Mikuni", "cc")
        val near = listOf(coop, mikuni, mikuni2)
        assertEquals(mikuni, TakeoutCsv.pick(near, emptyList(), "bb", "Davis Food Co-op"))
        assertEquals(coop, TakeoutCsv.pick(near, emptyList(), null, "davis food co-op"))
        assertNull(TakeoutCsv.pick(near, emptyList(), null, "Mikuni"))
        assertEquals(mikuni2, TakeoutCsv.pick(emptyList(), near, "cc", "Mikuni"))
        assertNull(TakeoutCsv.pick(emptyList(), listOf(coop), "zz", "Davis Food Co-op"))
        // A feature id that matches nothing vetoes the name: a same-name hit is another place.
        assertNull(TakeoutCsv.pick(listOf(coop), emptyList(), "zz", "Davis Food Co-op"))
    }

    private fun lp(id: String, fid: String? = null) = app.vela.core.model.ListPlace(id = id, name = id, lat = 38.54, lng = -121.74, featureId = fid)

    @Test fun `a running import keeps the earlier places it has not found again`() {
        val before = listOf(lp("a", "0x1:0xaa"), lp("b", "0x1:0xbb"), lp("imp:pin"), lp("c", "0x1:0xcc"))
        val found = listOf(lp("b2", "0x2:0xBB"), lp("imp:pin"))
        val saved = TakeoutCsv.partial(found, before)
        assertEquals(listOf("b2", "imp:pin", "a", "c"), saved.map { it.id })
        assertEquals(found, TakeoutCsv.partial(found, emptyList()))
    }

    @Test fun `earlier places are keyed by the stable half of their feature id`() {
        val known = TakeoutCsv.keyed(listOf(lp("a", "0x1:0xAA"), lp("imp:pin")))
        assertEquals(setOf("aa"), known.keys)
        assertEquals("a", known.getValue(TakeoutCsv.featureKey("0x9:0xaa")!!).id)
    }
}
