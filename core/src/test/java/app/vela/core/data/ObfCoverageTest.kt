package app.vela.core.data

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test

/** The trip-box test the phone-first reroute relies on (Delaware fixture boxes). */
class ObfCoverageTest {
    private val delaware = doubleArrayOf(38.45, -75.79, 39.84, -75.05)
    private val kentucky = doubleArrayOf(36.49, -89.57, 39.15, -81.96)

    @Test fun `a trip inside one region keeps that region and drops the far one`() {
        val keep = ObfRouteEngine.tripCandidates(listOf(delaware, kentucky), LatLng(39.16, -75.52), LatLng(39.74, -75.55))
        assertEquals(setOf(0), keep)
    }

    @Test fun `the pad reaches a region the endpoints do not touch`() {
        // Ends just north of Delaware's box: the ~30 km pad still pulls the region in.
        val keep = ObfRouteEngine.tripCandidates(listOf(delaware), LatLng(39.90, -75.5), LatLng(40.0, -75.6))
        assertEquals(setOf(0), keep)
        // A trip a whole state away gets nothing.
        assertEquals(emptySet<Int>(), ObfRouteEngine.tripCandidates(listOf(delaware), LatLng(37.0, -85.0), LatLng(37.2, -85.3)))
    }
}
