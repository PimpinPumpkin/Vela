package app.vela.core.search

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShownDistanceTest {
    private val davis = LatLng(38.5449, -121.7405)
    private val sacramento = LatLng(38.5816, -121.4944)
    private val sanFrancisco = LatLng(37.7749, -122.4194)

    // A San Francisco result as a search over San Francisco measured it: from the view's center.
    private val cafe = Place(id = "cafe", name = "Cafe", location = LatLng(37.7790, -122.4170), distanceMeters = 500.0)

    @Test fun `a live fix is the point, a stale or missing one is not`() {
        assertEquals(davis, ShownDistance.from(davis, stale = false))
        assertNull(ShownDistance.from(davis, stale = true))
        assertNull(ShownDistance.from(null, stale = false))
    }

    @Test fun `the untouched camera at 0,0 is never the user`() {
        assertNull(ShownDistance.from(LatLng(0.1, -0.2), stale = false))
    }

    @Test fun `with a live fix the distance is from the user wherever the search was`() {
        val shown = ShownDistance.of(cafe, ShownDistance.from(davis, stale = false))
        assertEquals(davis.distanceTo(cafe.location), shown.distanceMeters!!, 0.5)
        // Davis to San Francisco is over 100 km, not the 500 m the view's center gave.
        assertTrue(shown.distanceMeters!! > 100_000.0)
    }

    @Test fun `without a live fix the search's own distance stays`() {
        assertSame(cafe, ShownDistance.of(cafe, ShownDistance.from(davis, stale = true)))
        assertSame(cafe, ShownDistance.of(cafe, null))
    }

    @Test fun `a place that carried no distance gets none`() {
        val listed = Place(id = "saved", name = "Saved", location = sacramento)
        assertNull(ShownDistance.of(listed, davis).distanceMeters)
    }

    @Test fun `a list keeps its order and each place is measured`() {
        val near = Place(id = "near", name = "Near", location = sanFrancisco, distanceMeters = 10.0)
        val far = Place(id = "far", name = "Far", location = sacramento, distanceMeters = 20.0)
        val shown = ShownDistance.of(listOf(near, far), davis)
        assertEquals(listOf("near", "far"), shown.map { it.id })
        assertEquals(davis.distanceTo(sacramento), shown[1].distanceMeters!!, 0.5)
        assertEquals(davis.distanceTo(sanFrancisco), shown[0].distanceMeters!!, 0.5)
    }
}
