package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TrafficSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A same-course recheck's traffic replaces the traffic ahead of the car on the route being
 * driven (issue #973): a jam that formed after the route was fetched shows, one that cleared
 * goes, and the driven part keeps what it had.
 */
class NavSessionLiveSpansTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLat = 111_320.0

    /** [north] meters north of the start, on one straight road. */
    private fun at(north: Double) = LatLng(lat0 + north / mPerDegLat, lng0)

    private fun line(from: Double, to: Double) = (0..((to - from) / 10).toInt()).map { at(from + it * 10.0) }

    private fun route(from: Double, to: Double, spans: List<TrafficSpan>) =
        Route(line(from, to), emptyList(), to - from, 100.0, 120.0, trafficSpans = spans)

    // 2 km north; the car is 600 m along.
    private val current = route(
        0.0, 2000.0,
        listOf(TrafficSpan(2, 300.0, 200.0), TrafficSpan(1, 1200.0, 200.0)),
    )

    @Test
    fun aJamThatFormedAheadShowsAndOneThatClearedGoes() {
        // Fetched from the car: a severe jam 200 to 500 m ahead of it, nothing where the old one was.
        val fresh = route(600.0, 2000.0, listOf(TrafficSpan(3, 200.0, 300.0)))
        val spans = NavSession.liveSpans(current, fresh, 600.0)!!
        assertEquals(TrafficSpan(2, 300.0, 200.0), spans[0]) // driven part kept
        assertEquals(2, spans.size)
        val jam = spans[1]
        assertEquals(3, jam.level)
        assertTrue("starts at ${jam.startMeters}", jam.startMeters in 780.0..820.0)
        assertTrue("ends at ${jam.startMeters + jam.lengthMeters}", (jam.startMeters + jam.lengthMeters) in 1080.0..1130.0)
    }

    @Test
    fun aSpanTheCarIsInIsCutAtTheCar() {
        val now = route(0.0, 2000.0, listOf(TrafficSpan(2, 500.0, 300.0)))
        val fresh = route(600.0, 2000.0, emptyList())
        val spans = NavSession.liveSpans(now, fresh, 600.0)!!
        assertEquals(listOf(TrafficSpan(2, 500.0, 100.0)), spans)
    }

    @Test
    fun theSameTrafficIsNoChange() {
        val fresh = route(600.0, 2000.0, listOf(TrafficSpan(1, 600.0, 200.0)))
        assertNull(NavSession.liveSpans(current, fresh, 600.0))
    }

    @Test
    fun noTrafficEitherWayIsNoChange() {
        val clear = route(0.0, 2000.0, emptyList())
        assertNull(NavSession.liveSpans(clear, route(600.0, 2000.0, emptyList()), 600.0))
    }
}
