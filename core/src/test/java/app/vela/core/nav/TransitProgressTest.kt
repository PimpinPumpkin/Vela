package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.TransitLine
import app.vela.core.model.TransitMode
import app.vela.core.model.TransitStep
import app.vela.core.model.TransitStopTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitProgressTest {
    private val lat0 = 38.5449
    private val lng0 = -121.7405
    private fun at(northM: Double) = LatLng(lat0 + northM / 111_320.0, lng0)
    private fun stop(name: String, northM: Double) = TransitStopTime(name, location = at(northM))
    private val bus = TransitLine("42", TransitMode.BUS)

    // Board at 0 m, three stops in between every 500 m, alight at 2000 m.
    private val ride = TransitStep(
        mode = TransitMode.BUS, line = bus, numStops = 4,
        boardStop = stop("A", 0.0), alightStop = stop("E", 2000.0),
        intermediateStops = listOf(stop("B", 500.0), stop("C", 1000.0), stop("D", 1500.0)),
    )

    @Test fun `stops left count down along the leg`() {
        assertEquals(4, TransitProgress.stopsLeft(ride, at(20.0)))
        assertEquals(2, TransitProgress.stopsLeft(ride, at(1050.0)))
        assertEquals(1, TransitProgress.stopsLeft(ride, at(1480.0)))
        assertEquals(0, TransitProgress.stopsLeft(ride, at(1990.0)))
        assertEquals(4, TransitProgress.stopsLeft(ride, null))
    }

    @Test fun `the warning comes at the stop before yours`() {
        assertFalse(TransitProgress.nextIsYours(ride, at(1000.0)))
        assertTrue(TransitProgress.nextIsYours(ride, at(1500.0)))
    }

    @Test fun `with no stop list the warning comes near the stop`() {
        val bare = ride.copy(intermediateStops = emptyList())
        assertFalse(TransitProgress.nextIsYours(bare, at(1000.0)))
        assertTrue(TransitProgress.nextIsYours(bare, at(1500.0)))
    }

    @Test fun `a walk has no stops`() {
        val walk = TransitStep(mode = TransitMode.WALK, durationText = "5 min")
        assertNull(TransitProgress.stopsLeft(walk, at(0.0)))
        assertFalse(TransitProgress.nextIsYours(walk, at(0.0)))
    }
}
