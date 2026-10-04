package app.vela.core.search

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class AreaNarrowTest {
    private fun p(n: String, lat: Double, lng: Double) = Place(id = n, name = n, location = LatLng(lat, lng))
    private val downtown = doubleArrayOf(38.540, -121.745, 38.548, -121.735) // a few Davis blocks
    private val all = listOf(p("in", 38.544, -121.740), p("edge", 38.5486, -121.740), p("sacramento", 38.58, -121.49))

    @Test fun `only what is in the view, with a margin, is kept`() {
        assertEquals(listOf("in", "edge"), AreaNarrow.inView(all, downtown).map { it.name })
    }

    @Test fun `a view with nothing in it keeps the whole answer, and no view changes nothing`() {
        assertEquals(1, AreaNarrow.inView(all.takeLast(1), downtown).size)
        assertEquals(3, AreaNarrow.inView(all, null).size)
    }
}
