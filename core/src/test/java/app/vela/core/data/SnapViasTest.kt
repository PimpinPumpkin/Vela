package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The vias a reroute's naming snap goes through. */
class SnapViasTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * cos(Math.toRadians(lat0))
    private fun pt(e: Double, n: Double) = LatLng(lat0 + n / 111_320.0, lng0 + e / mPerDegLng)

    // A Google line that leaves the car at 315 degrees for 100 m, then runs at 45 degrees.
    private val line = run {
        val out = ArrayList<LatLng>()
        var e = 0.0; var n = 0.0
        fun go(brg: Double, len: Double) {
            repeat((len / 10).toInt()) { e += 10 * sin(Math.toRadians(brg)); n += 10 * cos(Math.toRadians(brg)); out += pt(e, n) }
        }
        out += pt(0.0, 0.0); go(315.0, 100.0); go(45.0, 2000.0)
        out
    }

    @Test fun `with a heading no via sits on the line's own start`() {
        val origin = pt(0.0, 0.0)
        val vias = RouteGeometry.snapVias(line, origin, departBearingDeg = 40.0)
        assertTrue(vias.isNotEmpty())
        assertTrue(vias.all { it.distanceTo(origin) >= RouteGeometry.VIA_ORIGIN_CLEAR_M })
    }

    @Test fun `without a heading the samples are unchanged`() {
        assertEquals(RouteGeometry.sampleVias(line), RouteGeometry.snapVias(line, pt(0.0, 0.0), departBearingDeg = null))
    }
}
