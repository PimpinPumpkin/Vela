package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TollNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TollCompareTest {
    private fun line(lng: Double) = listOf(LatLng(38.0, lng), LatLng(38.1, lng))

    private fun route(lng: Double, seconds: Double, traffic: Double? = null) =
        Route(polyline = line(lng), legs = emptyList(), distanceMeters = 10_000.0, durationSeconds = seconds, durationInTrafficSeconds = traffic)

    // Two lines are the same way when they sit on the same meridian.
    private val sameWay: (List<LatLng>, List<LatLng>) -> Boolean = { a, b -> a.first().lng == b.first().lng }

    @Test
    fun aFastestRouteThatAlreadyAvoidsTollsSaysNothing() {
        val routes = listOf(route(-121.0, 600.0), route(-121.1, 700.0))
        assertNull(TollCompare.plan(routes, route(-121.0, 600.0), sameWay))
    }

    @Test
    fun anAvoidingRouteThatIsAnotherRowTagsTheFastestAndThatRow() {
        val routes = listOf(route(-121.0, 600.0), route(-121.1, 700.0), route(-121.2, 800.0))
        val avoiding = route(-121.1, 705.0)
        val plan = TollCompare.plan(routes, avoiding, sameWay)!!
        assertEquals(0, plan.tolledIdx)
        assertEquals(1, plan.avoidingIdx)
        val out = TollCompare.apply(routes, plan, avoiding)
        assertEquals(listOf(TollNote.MAY_HAVE_TOLLS, TollNote.NO_TOLLS, null), out.map { it.tolls })
        assertEquals(3, out.size)
    }

    @Test
    fun anAvoidingRouteThatIsNotOnOfferIsAddedAtTheEnd() {
        val routes = listOf(route(-121.0, 600.0), route(-121.1, 700.0))
        val avoiding = route(-121.5, 900.0)
        val plan = TollCompare.plan(routes, avoiding, sameWay)!!
        assertNull(plan.avoidingIdx)
        val out = TollCompare.apply(routes, plan, avoiding)
        assertEquals(3, out.size)
        assertEquals(listOf(TollNote.MAY_HAVE_TOLLS, null, TollNote.NO_TOLLS), out.map { it.tolls })
        assertEquals(avoiding.polyline, out.last().polyline)
        // The routes already on offer keep their places.
        assertEquals(routes[1].polyline, out[1].polyline)
    }

    @Test
    fun theFastestIsFoundByTheTrafficTimeWhenThereIsOne() {
        // The first row is the fastest by typical time, the second by time in traffic.
        val routes = listOf(route(-121.0, 600.0, traffic = 900.0), route(-121.1, 700.0, traffic = 650.0))
        val plan = TollCompare.plan(routes, route(-121.5, 900.0), sameWay)!!
        assertEquals(1, plan.tolledIdx)
    }

    @Test
    fun anAvoidThatWasNotHonoredSaysNothing() {
        val routes = listOf(route(-121.0, 600.0))
        assertNull(TollCompare.plan(routes, route(-121.5, 900.0).copy(avoidNotHonored = true), sameWay))
    }

    @Test
    fun noRoutesOrNoLineSaysNothing() {
        assertNull(TollCompare.plan(emptyList(), route(-121.5, 900.0), sameWay))
        assertNull(TollCompare.plan(listOf(route(-121.0, 600.0)), route(-121.5, 900.0).copy(polyline = emptyList()), sameWay))
    }

    @Test
    fun theSameWayTestIsAskedBothWays() {
        // A line that runs along the other in one direction only (a detour that contains the route)
        // is not the same way.
        val oneWay: (List<LatLng>, List<LatLng>) -> Boolean = { a, b -> a.size < b.size }
        val routes = listOf(route(-121.0, 600.0))
        val avoiding = route(-121.0, 900.0).copy(polyline = line(-121.0) + LatLng(38.2, -121.0))
        assertEquals(0, TollCompare.plan(routes, avoiding, oneWay)!!.tolledIdx)
    }
}
