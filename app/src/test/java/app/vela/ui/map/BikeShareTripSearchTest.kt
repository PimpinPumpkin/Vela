package app.vela.ui.map

import app.vela.core.model.LatLng
import app.vela.core.model.TransitItinerary
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the bike-share trip search asks: the systems first, kept for the same ends (Davis and
 *  Sacramento fixture). */
class BikeShareTripSearchTest {
    private val davis = LatLng(38.5449, -121.7405)
    private val sacramento = LatLng(38.5816, -121.4944)
    private val trip = TransitItinerary(durationText = "20 min")

    private class Calls {
        var groups = 0
        var plans = 0
        var planGroups: List<String>? = null
    }

    private fun search(calls: Calls, now: LongArray, groups: List<String>?) = BikeShareTripSearch(
        OkHttpClient(),
        groupsOf = { _, _ -> calls.groups++; groups },
        planOf = { _, _, g -> calls.plans++; calls.planGroups = g; listOf(trip) },
        clock = { now[0] },
    )

    @Test fun `with no docked system around the trip the plan is never asked for`() = runBlocking {
        val calls = Calls()
        val s = search(calls, longArrayOf(0L), emptyList())
        assertEquals(emptyList<TransitItinerary>(), s.search(davis, sacramento))
        assertEquals(1, calls.groups)
        assertEquals(0, calls.plans)
    }

    @Test fun `the plan is asked for on the systems found`() = runBlocking {
        val calls = Calls()
        val s = search(calls, longArrayOf(0L), listOf("test-bikes"))
        assertEquals(listOf(trip), s.search(davis, sacramento))
        assertEquals(listOf("test-bikes"), calls.planGroups)
    }

    @Test fun `the systems are kept for the same ends, either way, until they are old`() = runBlocking {
        val calls = Calls()
        val now = longArrayOf(0L)
        val s = search(calls, now, listOf("test-bikes"))
        s.search(davis, sacramento)
        now[0] = BikeShareTripSearch.GROUPS_TTL_MS - 1
        s.search(sacramento, davis)
        assertEquals(1, calls.groups)
        assertEquals(2, calls.plans)
        now[0] = BikeShareTripSearch.GROUPS_TTL_MS + 1
        s.search(davis, sacramento)
        assertEquals(2, calls.groups)
    }

    @Test fun `a failed lookup is never kept as no system`() = runBlocking {
        val calls = Calls()
        val s = search(calls, longArrayOf(0L), null)
        assertEquals(emptyList<TransitItinerary>(), s.search(davis, sacramento))
        s.search(davis, sacramento)
        assertEquals(2, calls.groups)
        assertEquals(0, calls.plans)
    }

    // A ride from a station on Covell Boulevard to one downtown, in Davis.
    private val covell = LatLng(38.5617, -121.7604)
    private val downtown = LatLng(38.5440, -121.7400)
    private val ride = app.vela.core.model.TransitStep(
        mode = app.vela.core.model.TransitMode.BIKE_SHARE,
        boardStop = app.vela.core.model.TransitStopTime(name = "Covell Park", location = covell),
        alightStop = app.vela.core.model.TransitStopTime(name = "Downtown", location = downtown),
        rental = app.vela.core.model.TransitRental(providerId = "test-bikes", groupId = "test", system = "Test Bikes"),
    )
    private val bikeTrip = TransitItinerary(steps = listOf(ride))

    private fun station(id: String, name: String, at: LatLng, bikes: Int, docks: Int?) = app.vela.core.data.transit.Transitous.BikeStation(
        key = "test-bikes/$id", name = name, lat = at.lat, lon = at.lng, address = null,
        bikes = bikes, docks = docks, renting = true, returning = true,
    )

    @Test fun `the counts are found for both stations of a ride`() = runBlocking {
        val asked = ArrayList<List<String>>()
        val s = BikeShareTripSearch(
            OkHttpClient(),
            stationsOf = { _, g -> asked += g; listOf(station("1", "COVELL PARK", covell, 1, null), station("2", "Downtown", downtown, 9, 4)) },
            clock = { 0L }, wallClock = { 42L },
        )
        val counts = s.counts(listOf(bikeTrip))!!
        assertEquals(listOf("test"), asked.first())
        val pickup = counts[BikeShareCounts.keyOf("test-bikes", "Covell Park")]!!
        assertEquals(1, pickup.bikes)
        assertEquals(42L, pickup.atMs)
        assertEquals(4, counts[BikeShareCounts.keyOf("test-bikes", "Downtown")]!!.docks)
    }

    @Test fun `with no reply there are no counts, not zero bikes`() = runBlocking {
        val s = BikeShareTripSearch(OkHttpClient(), stationsOf = { _, _ -> null }, clock = { 0L })
        assertEquals(null, s.counts(listOf(bikeTrip)))
        assertEquals(null, s.counts(listOf(trip)))
    }

    @Test fun `stations near each other share a box, far ones get their own`() {
        // Covell to downtown is about 2.7 km: two boxes. Two points 300 m apart: one.
        assertEquals(2, BikeShareTripSearch.boxes(listOf(covell, downtown)).size)
        val near = LatLng(covell.lat + 0.0027, covell.lng)
        val one = BikeShareTripSearch.boxes(listOf(covell, near)).single()
        assertTrue(one[0] < covell.lat && one[2] > near.lat)
        assertEquals("at most four boxes", 4, BikeShareTripSearch.boxes((0 until 6).map { LatLng(38.0 + it * 0.1, -121.7) }).size)
    }

    @Test fun `ends a kilometer apart share an area, ends farther apart do not`() {
        assertEquals(
            BikeShareTripSearch.areaKey(davis, sacramento),
            BikeShareTripSearch.areaKey(LatLng(38.5441, -121.7409), LatLng(38.5812, -121.4948)),
        )
        assertNotEquals(
            BikeShareTripSearch.areaKey(davis, sacramento),
            BikeShareTripSearch.areaKey(LatLng(38.5560, -121.7405), sacramento),
        )
    }
}
