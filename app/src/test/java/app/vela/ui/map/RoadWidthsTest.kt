package app.vela.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The road classes keep their order at every zoom, and minor streets stay well under the main roads
 *  ([RoadWidths]). Two-way widths at `roadWidthScale` 1, edges on and off. */
class RoadWidthsTest {
    private val zooms = (25..40).map { it / 2.0 } // z12.5 .. z20
    private fun m(cls: String) = RoadWidths.WIDTH_M.getValue(cls)
    private fun fill(cls: String, z: Double) = RoadWidths.fill(cls, z, m(cls))
    private fun casing(cls: String, z: Double, edges: Boolean) = RoadWidths.casing(cls, z, m(cls), edges)

    @Test fun `classes keep their order wherever both are drawn`() {
        val order = listOf("service_track", "minor", "secondary_tertiary", "trunk_primary", "motorway")
        for (edges in listOf(true, false)) for (z in zooms) {
            for ((lo, hi) in order.zipWithNext()) {
                if (fill(lo, z) <= 0.0 || fill(hi, z) <= 0.0) continue
                // Trunk and motorway share a base curve; service can meet minor at its cap.
                val strict = lo == "minor" || lo == "secondary_tertiary"
                for ((what, a, b) in listOf(
                    Triple("fill", fill(lo, z), fill(hi, z)),
                    Triple("casing", casing(lo, z, edges), casing(hi, z, edges)),
                )) {
                    val ok = if (strict) a < b else a <= b
                    assertTrue("$what $lo $a vs $hi $b at z$z edges=$edges", ok)
                }
            }
        }
    }

    @Test fun `minor streets stay under two thirds of a secondary road`() {
        for (edges in listOf(true, false)) for (z in zooms.filter { it >= 13.5 }) {
            val ratio = casing("minor", z, edges) / casing("secondary_tertiary", z, edges)
            assertTrue("minor/secondary casing $ratio at z$z edges=$edges", ratio <= 0.65)
        }
    }

    @Test fun `widths never shrink as the map zooms in`() {
        val all = (10..40).map { it / 2.0 }
        for (cls in RoadWidths.BASE.keys) for (edges in listOf(true, false)) {
            for ((z0, z1) in all.zipWithNext()) {
                assertTrue("$cls fill z$z0 -> z$z1", fill(cls, z1) >= fill(cls, z0) - 1e-9)
                assertTrue("$cls casing z$z0 -> z$z1 edges=$edges", casing(cls, z1, edges) >= casing(cls, z0, edges) - 1e-9)
            }
        }
    }

    @Test fun `scale 0 leaves the base curves`() {
        for (cls in RoadWidths.BASE.keys) for (z in zooms) {
            assertEquals(RoadWidths.expInterp(RoadWidths.BASE.getValue(cls).first, z), RoadWidths.fill(cls, z, 0.0), 1e-9)
        }
    }
}
