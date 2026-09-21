package app.vela.core

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guard that Google's reply really went through the stops, and the per-leg via sampling the
 *  multi-stop snap uses (issue #600, 2026-09-21). Davis-fixture grid, ~111 m per 0.001 degree. */
class StopsOnLineTest {
    // A straight north-south line, 101 vertices, ~11 km.
    private val line = (0..100).map { LatLng(38.5 + it * 0.001, -121.7) }

    @Test fun `stops on the line are found in order, a far stop is not`() {
        val on = listOf(LatLng(38.53, -121.7), LatLng(38.57, -121.7001))
        assertEquals(listOf(30, 70), RouteGeometry.stopIndicesOn(line, on))
        assertTrue(RouteGeometry.stopsOnLine(line, on))
        // 0.01 degrees of longitude east is ~870 m: the direct trip never came near this stop.
        assertFalse(RouteGeometry.stopsOnLine(line, listOf(LatLng(38.53, -121.69))))
        assertTrue(RouteGeometry.stopsOnLine(line, emptyList()))
    }

    @Test fun `a stop behind the previous one is not on the trip`() {
        // Trip order matters: the second stop lies before the first along the line.
        assertNull(RouteGeometry.stopIndicesOn(line, listOf(LatLng(38.57, -121.7), LatLng(38.53, -121.7))))
    }

    @Test fun `the via list samples each leg and puts the real stop between them`() {
        val stop = LatLng(38.55, -121.7)
        val vias = RouteGeometry.sampleViasThrough(line, listOf(stop), perLeg = 4)!!
        val at = vias.indexOf(stop)
        assertTrue("the stop is a via", at > 0)
        assertEquals(4, at) // four samples of the first leg before it
        assertEquals(4, vias.size - at - 1) // four of the second after it
        // Every via advances along the line: samples before the stop are south of it, after are north.
        assertTrue(vias.take(at).all { it.lat < stop.lat })
        assertTrue(vias.drop(at + 1).all { it.lat > stop.lat })
        assertNull(RouteGeometry.sampleViasThrough(line, listOf(LatLng(38.55, -121.65))))
    }
}
