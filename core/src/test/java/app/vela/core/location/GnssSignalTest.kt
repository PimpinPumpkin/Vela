package app.vela.core.location

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GnssSignalTest {
    @After fun clear() = GnssSignal.reset()

    @Test fun theFourStrongestUsedSatellitesAreAveraged() {
        GnssSignal.update(listOf(18.0, 42.0, 39.0, 25.0, 44.0, 41.0), 1_000L)
        val (top4, used) = GnssSignal.latest(1_500L)!!
        assertEquals((44.0 + 42.0 + 41.0 + 39.0) / 4, top4, 1e-9)
        assertEquals(6, used)
    }

    @Test fun fewerThanFourAreAveragedAsTheyAre() {
        assertEquals(30.0, GnssSignal.top4Of(listOf(28.0, 32.0)), 1e-9)
    }

    @Test fun aStaleOrEmptyStatusSaysNothing() {
        GnssSignal.update(listOf(40.0, 40.0, 40.0, 40.0), 1_000L)
        assertNull(GnssSignal.latest(1_000L + GnssSignal.FRESH_MS + 1))
        GnssSignal.update(emptyList(), 5_000L)
        assertNull("no satellite used", GnssSignal.latest(5_000L))
    }
}
