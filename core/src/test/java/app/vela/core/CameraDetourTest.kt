package app.vela.core

import app.vela.core.model.LatLng
import app.vela.core.nav.CameraDetour
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Side-street candidates around camera clusters (issue #600). Davis-fixture grid. */
class CameraDetourTest {
    // A road running due north, 101 vertices, ~11 km.
    private val north = (0..100).map { LatLng(38.5 + it * 0.001, -121.7) }

    @Test fun `a candidate sits 150 m to either side of the road at the cluster`() {
        val c = CameraDetour.candidates(north, listOf(3000.0)).single()
        assertEquals(3000.0, c.atM, 1.0)
        assertEquals(1, c.count)
        // Driving north: left is west (smaller longitude), right is east; both level with the road point.
        assertTrue(c.left.lng < -121.7 && c.right.lng > -121.7)
        assertEquals(c.left.lat, c.right.lat, 1e-6)
        val p = LatLng(38.5 + 3000.0 / 111_320.0, -121.7)
        assertEquals(150.0, p.distanceTo(c.left), 2.0)
        assertEquals(150.0, p.distanceTo(c.right), 2.0)
    }

    @Test fun `heads on one corner are one cluster, clusters are capped nearest first`() {
        // Three heads within 40 m at 1 km, then singles at 4, 6 and 8 km: four clusters, three kept.
        val cams = listOf(1010.0, 1000.0, 1030.0, 8000.0, 4000.0, 6000.0)
        val out = CameraDetour.candidates(north, cams)
        assertEquals(listOf(1000.0, 4000.0, 6000.0), out.map { it.atM })
        assertEquals(3, out.first().count)
    }

    @Test fun `the plan keeps stops and detour points in travel order`() {
        val a = LatLng(1.0, 1.0); val b = LatLng(2.0, 2.0); val v = LatLng(9.0, 9.0)
        // Stop a at 500 m, stop b with no position on the line, a via at 2 km: a, via, b? No: b's
        // position is unknown and it comes after a, so it fills in past a and the via sorts by its own.
        val plan = CameraDetour.mergePlan(listOf(500.0 to a, null to b), listOf(2000.0 to v))
        assertEquals(listOf(a, b, v), plan)
        val plan2 = CameraDetour.mergePlan(listOf(500.0 to a, 3000.0 to b), listOf(2000.0 to v))
        assertEquals(listOf(a, v, b), plan2)
        assertEquals(listOf(v), CameraDetour.mergePlan(emptyList(), listOf(2000.0 to v)))
    }
}
