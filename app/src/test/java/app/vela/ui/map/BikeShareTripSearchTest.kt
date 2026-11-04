package app.vela.ui.map

import app.vela.core.model.LatLng
import app.vela.core.model.TransitItinerary
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
