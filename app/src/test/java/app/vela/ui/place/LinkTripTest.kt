package app.vela.ui.place

import app.vela.ui.place.LinkTrip.Lookup
import app.vela.ui.place.LinkTrip.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A trip from a link whose places did not all answer ([LinkTrip]). */
class LinkTripTest {
    private fun view(origin: Row?, vararg stops: Row) =
        LinkTrip.View(origin, stops.toList(), Row("1451 W Covell Blvd", Lookup.FOUND), resolving = false)

    @Test fun `the stops that found nothing keep their place among the link's stops`() {
        val v = view(null, Row("A", Lookup.FOUND), Row("B", Lookup.NOT_FOUND), Row("C", Lookup.FOUND), Row("D", Lookup.NOT_FOUND))
        assertEquals(listOf(1 to "B", 3 to "D"), v.missingStops)
        assertNull(v.missingStart)
        assertEquals(listOf("B", "D"), v.notFound)
    }

    @Test fun `a start that found nothing is named, and is not a stop`() {
        val v = view(Row("Davis Food Co-op", Lookup.NOT_FOUND), Row("A", Lookup.FOUND))
        assertEquals("Davis Food Co-op", v.missingStart)
        assertEquals(emptyList<Pair<Int, String>>(), v.missingStops)
    }

    @Test fun `the dialog asks about the missing stops in order, then the start, each once`() {
        val v = view(Row("Home", Lookup.NOT_FOUND), Row("A", Lookup.FOUND), Row("B", Lookup.NOT_FOUND), Row("C", Lookup.FOUND), Row("D", Lookup.NOT_FOUND))
        assertEquals(1, v.nextMissing())
        assertEquals(3, v.nextMissing(after = 1))
        assertEquals(LinkTrip.START, v.nextMissing(after = 3))
        assertNull(v.nextMissing(after = LinkTrip.START))
        // Only the start is missing: it is the first and only thing asked.
        assertEquals(LinkTrip.START, view(Row("Home", Lookup.NOT_FOUND), Row("A", Lookup.FOUND)).nextMissing())
        // Nothing missing after the last stop, and no start named: the dialog closes.
        assertNull(view(null, Row("A", Lookup.NOT_FOUND)).nextMissing(after = 0))
    }

    @Test fun `start where I am answers a missing start`() {
        LinkTrip.view.value = view(Row("Home", Lookup.NOT_FOUND), Row("A", Lookup.FOUND))
        LinkTrip.asking.value = LinkTrip.START
        LinkTrip.startHere()
        assertNull(LinkTrip.asking.value)
        assertNull("nothing else is missing, so the card's line goes", LinkTrip.view.value)
        // With a stop still missing the line stays, for that stop alone.
        LinkTrip.view.value = view(Row("Home", Lookup.NOT_FOUND), Row("A", Lookup.NOT_FOUND))
        LinkTrip.startHere()
        assertEquals(listOf("A"), LinkTrip.view.value!!.notFound)
        LinkTrip.view.value = null
    }

    @Test fun `a stop found later goes back after the stops the link listed before it`() {
        // The link had A B C D; A and C were found, so the trip is [A, C] at link places [0, 2].
        assertEquals(1, LinkTrip.insertAt(listOf(0, 2), 1)) // B between A and C
        assertEquals(2, LinkTrip.insertAt(listOf(0, 2), 3)) // D after C
        assertEquals(0, LinkTrip.insertAt(listOf(1, 2), 0)) // a first stop found later goes first
        // B was put back: [A, B, C] at [0, 1, 2]; D still lands last.
        assertEquals(3, LinkTrip.insertAt(listOf(0, 1, 2), 3))
        assertEquals(0, LinkTrip.insertAt(emptyList(), 2))
    }
}
