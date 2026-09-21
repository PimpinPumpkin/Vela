package app.vela.core

import app.vela.core.data.google.DirectionsPb
import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stops are extra top-level waypoint groups between origin and destination (verified live
 *  2026-09-21, see [DirectionsPb.withWaypoints]). No enclosing count moves. */
class DirectionsPbWaypointsTest {
    private val t = DirectionsPb.DEFAULT_TEMPLATE

    @Test fun `no stops leaves the template alone`() {
        assertEquals(t, DirectionsPb.withWaypoints(t, emptyList()))
    }

    @Test fun `a stop is one more group between origin and destination, in order`() {
        val out = DirectionsPb.withWaypoints(t, listOf(LatLng(38.6785, -121.7733)))
        assertTrue(
            out.startsWith("!1m4!3m2!3d{OLAT}!4d{OLNG}!6e2!1m4!3m2!3d38.6785!4d-121.7733!6e2!1m4!3m2!3d{DLAT}!4d{DLNG}!6e2!3m12"),
        )
        // Only the head changed: everything after the destination group is byte-identical.
        assertEquals(t.substringAfter("{DLNG}!6e2"), out.substringAfter("{DLNG}!6e2"))
        val two = DirectionsPb.withWaypoints(t, listOf(LatLng(1.0, 2.0), LatLng(3.0, 4.0)))
        assertTrue(two.contains("!3d1.0!4d2.0!6e2!1m4!3m2!3d3.0!4d4.0!6e2!1m4!3m2!3d{DLAT}"))
    }

    @Test fun `build fills every group and keeps the stop in the middle`() {
        val pb = DirectionsPb.build(LatLng(38.5449, -121.7405), LatLng(38.5816, -121.4944), TravelMode.DRIVE, waypoints = listOf(LatLng(38.6785, -121.7733)))
        assertTrue(pb.startsWith("!1m4!3m2!3d38.5449!4d-121.7405!6e2!1m4!3m2!3d38.6785!4d-121.7733!6e2!1m4!3m2!3d38.5816!4d-121.4944!6e2"))
        assertFalse(pb.contains("{"))
    }

    @Test fun `a template without the placeholder groups is left alone and reports so`() {
        val odd = "!3m12!1m3!1d1.0"
        assertFalse(DirectionsPb.waypointsSupported(odd))
        assertEquals(odd, DirectionsPb.withWaypoints(odd, listOf(LatLng(1.0, 2.0))))
        assertTrue(DirectionsPb.waypointsSupported(t))
    }
}
