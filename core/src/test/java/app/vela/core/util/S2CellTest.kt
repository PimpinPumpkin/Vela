package app.vela.core.util

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

class S2CellTest {
    private fun km(a: LatLng, b: LatLng): Double {
        val dy = (a.lat - b.lat) * 111.0
        val dx = (a.lng - b.lng) * 111.0 * cos(Math.toRadians(a.lat))
        return hypot(dx, dy)
    }

    @Test fun `a feature id's cell lands by its place`() {
        // The Uffizi in Florence: the cell from its Google link against the museum's door.
        val uffizi = S2Cell.center(java.lang.Long.parseUnsignedLong("132a54008dc59081", 16))!!
        assertTrue(km(uffizi, LatLng(43.7678, 11.2553)) < 0.5)
        // A fixture id in the test files: the cell is in the same town as the fixture pin.
        val coop = S2Cell.center(java.lang.Long.parseUnsignedLong("8085299a7b6c6f0b", 16))!!
        assertTrue(km(coop, LatLng(38.5449, -121.7405)) < 5.0)
        assertEquals(30, S2Cell.level(java.lang.Long.parseUnsignedLong("132a54008dc59081", 16)))
    }

    @Test fun `a face cell and a bad id`() {
        // Face 0's whole cell is centered on the equator at longitude 0.
        val face0 = S2Cell.center(1L shl 60)!!
        assertEquals(0.0, face0.lat, 1e-9)
        assertEquals(0.0, face0.lng, 1e-9)
        assertNull(S2Cell.center(0L))
    }
}
