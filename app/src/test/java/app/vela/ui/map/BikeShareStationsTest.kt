package app.vela.ui.map

import android.content.ContextWrapper
import app.vela.core.data.transit.Transitous
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.ui.BikeShareLayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The box rule and the open sheet's refresh of the bike-share layer (Davis fixture). */
class BikeShareStationsTest {
    // A view over Davis: south, west, north, east.
    private val s = 38.540; private val w = -121.750; private val n = 38.550; private val e = -121.740

    private fun station(id: String, bikes: Int) = Transitous.BikeStation(
        key = "test-bikes/$id", name = "Station $id", lat = 38.545, lon = -121.745, address = null,
        bikes = bikes, docks = 3, renting = true, returning = true,
    )

    @Test fun `the box is the view padded by half on each side`() {
        val b = BikeShareStations.padded(s, w, n, e)
        assertEquals(38.535, b[0], 1e-9)
        assertEquals(-121.755, b[1], 1e-9)
        assertEquals(38.555, b[2], 1e-9)
        assertEquals(-121.735, b[3], 1e-9)
    }

    @Test fun `a small pan is served by the same box, a pan out of its middle is not`() {
        val b = BikeShareStations.padded(s, w, n, e)
        assertTrue(BikeShareStations.covers(b, s, w, n, e))
        assertTrue("a quarter of a view east", BikeShareStations.covers(b, s, w + 0.0025, n, e + 0.0025))
        assertFalse("a whole view east", BikeShareStations.covers(b, s, w + 0.010, n, e + 0.010))
    }

    @Test fun `a zoom out past the box asks again even with the center in place`() {
        val b = BikeShareStations.padded(s, w, n, e)
        assertFalse(BikeShareStations.covers(b, s - 0.010, w - 0.010, n + 0.010, e + 0.010))
    }

    @Test fun `a request on the wire lands while the view keeps moving, and the new view is asked next`() {
        // While the map follows a moving fix the view comes once a second, sooner than a slow reply.
        val asked = mutableListOf<DoubleArray>()
        val replies = mutableListOf<CompletableDeferred<List<Transitous.BikeStation>?>>()
        val state = MutableStateFlow(MapUiState())
        val shares = BikeShareStations(ContextWrapper(null), CoroutineScope(Dispatchers.Unconfined), state, OkHttpClient(), settleMs = 0) { b ->
            asked += b
            CompletableDeferred<List<Transitous.BikeStation>?>().also { replies += it }.await()
        }
        BikeShareLayer.on.value = true
        try {
            shares.onViewport(s, w, n, e, 16.0)
            shares.onViewport(s, w + 0.02, n, e + 0.02, 16.0) // a view east, out of the first box
            assertEquals("the first request is still out", 1, asked.size)
            replies[0].complete(listOf(station("7", 9)))
            assertEquals(listOf(station("7", 9)), state.value.bikeStations)
            assertEquals("the moved view is asked for once the first lands", 2, asked.size)
            assertTrue(asked[1][1] > asked[0][1])
        } finally {
            BikeShareLayer.on.value = false
        }
    }

    @Test fun `an open station takes its new counts`() {
        val open = Place(id = BikeShareStations.ID_PREFIX + "test-bikes/7", name = "Station 7", location = LatLng(38.545, -121.745))
        val got = BikeShareStations.refreshed(open, listOf(station("6", 1), station("7", 9)), atMs = 1_000L)!!
        assertEquals(9, got.bikeShare!!.bikes)
        assertEquals(3, got.bikeShare!!.docks)
        assertEquals(1_000L, got.bikeShare!!.atMs)
    }

    @Test fun `a bike-share station's pin is a bike, not a bus stop`() {
        assertEquals("bike", PoiIcons.groupForCategory("Bike-share station"))
        assertEquals("bike", PoiIcons.groupForCategory("Bike sharing station"))
        assertEquals("transit", PoiIcons.groupForCategory("Bus station"))
        assertEquals("civic", PoiIcons.groupForCategory("Fire station"))
        // The app's own station is drawn from this, not from its translated category.
        assertEquals("bike", PoiIcons.groupForCategory(BikeShareStations.PIN_CATEGORY))
    }

    @Test fun `a bike-share category is never a transit stop`() {
        for (c in listOf("Bike sharing station", "Bicycle rental service", "Estación de bicicletas compartidas",
            "Estación de alquiler de bicicletas", "Estación de bici pública", "Station de vélos en libre-service",
            "Velostation", "Fahrradverleih")) {
            assertTrue(c, BikeShareStations.isBikeCategory(c))
        }
        for (c in listOf("Bus station", "Estación de tren", "Gare routière", "Development office", "Mobile home park")) {
            assertFalse(c, BikeShareStations.isBikeCategory(c))
        }
    }

    @Test fun `other places and a station out of the list are left as they were`() {
        val cafe = Place(id = "cafe", name = "Cafe", location = LatLng(38.545, -121.745))
        assertSame(cafe, BikeShareStations.refreshed(cafe, listOf(station("7", 9)), atMs = 1L))
        val gone = Place(id = BikeShareStations.ID_PREFIX + "test-bikes/8", name = "Station 8", location = LatLng(38.545, -121.745))
        assertSame(gone, BikeShareStations.refreshed(gone, listOf(station("7", 9)), atMs = 1L))
        assertNull(BikeShareStations.refreshed(null, listOf(station("7", 9)), atMs = 1L))
    }
}
