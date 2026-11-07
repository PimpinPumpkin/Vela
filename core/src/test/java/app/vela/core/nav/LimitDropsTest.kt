package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.LimitSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speed limit drop warning ([LimitDrops], issue #857). The cases worth pinning are the ones
 * that would make it nag or lie: a gap in the tags, a cross street read at a junction, a limit
 * stepping down in stages, and a drop the driver is already slow for.
 */
class LimitDropsTest {

    private fun span(from: Double, to: Double, kmh: Double) = LimitSpan(from, to - from, kmh)

    @Test fun `a drop at the threshold counts and one under it does not`() {
        val spans = listOf(span(0.0, 2_000.0, 80.0), span(2_000.0, 4_000.0, 50.0))
        assertEquals(1, LimitDrops.find(spans, 30.0).size)
        assertTrue(LimitDrops.find(spans, 40.0).isEmpty())
    }

    @Test fun `the drop is placed where the lower limit starts`() {
        val d = LimitDrops.find(listOf(span(0.0, 3_000.0, 100.0), span(3_000.0, 5_000.0, 50.0)), 30.0).single()
        assertEquals(3_000.0, d.atM, 0.0)
        assertEquals(100.0, d.fromKmh, 0.0)
        assertEquals(50.0, d.toKmh, 0.0)
    }

    @Test fun `a rise is never a drop`() {
        assertTrue(LimitDrops.find(listOf(span(0.0, 2_000.0, 50.0), span(2_000.0, 4_000.0, 100.0)), 20.0).isEmpty())
    }

    @Test fun `a short untagged gap keeps the limit before it`() {
        val spans = listOf(span(0.0, 2_000.0, 100.0), span(2_250.0, 4_000.0, 50.0))
        assertEquals(1, LimitDrops.find(spans, 30.0).size)
    }

    @Test fun `a long untagged gap is not a drop`() {
        val spans = listOf(span(0.0, 2_000.0, 100.0), span(2_400.0, 4_000.0, 50.0))
        assertTrue(LimitDrops.find(spans, 30.0).isEmpty())
    }

    @Test fun `a cross street read at a junction is ignored`() {
        // 100 m of 30 inside a 100 zone is a snap to the side road, not a limit to slow for.
        val spans = listOf(span(0.0, 2_000.0, 100.0), span(2_000.0, 2_100.0, 30.0), span(2_100.0, 4_000.0, 100.0))
        assertTrue(LimitDrops.find(spans, 30.0).isEmpty())
    }

    @Test fun `a limit that steps down in stages is one drop`() {
        val spans = listOf(span(0.0, 2_000.0, 100.0), span(2_000.0, 2_200.0, 70.0), span(2_200.0, 4_000.0, 50.0))
        val d = LimitDrops.find(spans, 40.0).single()
        assertEquals(2_200.0, d.atM, 0.0)
        assertEquals(100.0, d.fromKmh, 0.0)
    }

    @Test fun `the reference resets after a drop`() {
        // 100 to 70 is a drop of 30; 70 to 50 is only 20 more and is not announced again.
        val spans = listOf(span(0.0, 2_000.0, 100.0), span(2_000.0, 2_300.0, 70.0), span(2_300.0, 4_000.0, 50.0))
        val drops = LimitDrops.find(spans, 30.0)
        assertEquals(1, drops.size)
        assertEquals(2_000.0, drops[0].atM, 0.0)
    }

    @Test fun `the lead is about a kilometer on a highway and shorter in town`() {
        assertEquals(1_000.0, LimitDrops.leadM(100 / 3.6), 0.1)
        assertEquals(500.0, LimitDrops.leadM(50 / 3.6), 0.1)
        assertEquals(300.0, LimitDrops.leadM(30 / 3.6), 0.1)
    }

    @Test fun `a drop is due inside the lead, once, and never behind`() {
        val drops = listOf(LimitDrops.Drop(3_000.0, 100.0, 50.0))
        val v = 100 / 3.6
        assertNull("1.5 km out is too early", LimitDrops.due(drops, 1_500.0, v, emptySet()))
        val d = LimitDrops.due(drops, 2_100.0, v, emptySet())
        assertEquals(3_000.0, d!!.atM, 0.0)
        assertNull("announced once", LimitDrops.due(drops, 2_200.0, v, setOf(d.key)))
        assertNull("behind the car", LimitDrops.due(drops, 3_050.0, v, emptySet()))
        assertNull("stopped", LimitDrops.due(drops, 2_100.0, 0.5, emptySet()))
    }

    @Test fun `a driver already at the new limit is not told`() {
        val d = LimitDrops.Drop(3_000.0, 100.0, 50.0)
        assertTrue(LimitDrops.alreadySlow(d, 52.0))
        assertFalse(LimitDrops.alreadySlow(d, 70.0))
    }

    // A straight run east from the Davis fixture.
    private val poly = (0..4).map { LatLng(38.5449, -121.7405 + it * 0.0115) }

    @Test fun `samples merge into spans and skip the turns`() {
        val cum = RouteProjection.cumulative(poly)
        val switchAt = 2_000.0
        val spans = LimitDrops.sample(poly, cum, 0.0, 3_000.0, turnsM = listOf(1_000.0)) { p ->
            val m = RouteProjection.alongMeters(poly, cum, p)!!
            if (m < switchAt) 100.0 else 50.0
        }
        // 100 up to the turn, 100 after it, then 50: the turn splits the first stretch in two.
        assertEquals(3, spans.size)
        assertTrue("nothing sampled at the turn", spans[0].startMeters + spans[0].lengthMeters <= 1_000.0 - LimitDrops.TURN_SKIP_M + LimitDrops.SAMPLE_STEP_M)
        assertEquals(50.0, spans[2].kmh, 0.0)
        assertEquals(1, LimitDrops.find(spans, 30.0).size)
    }

    @Test fun `unknown samples leave a gap`() {
        val cum = RouteProjection.cumulative(poly)
        val spans = LimitDrops.sample(poly, cum, 0.0, 1_000.0, emptyList()) { null }
        assertTrue(spans.isEmpty())
    }
}
