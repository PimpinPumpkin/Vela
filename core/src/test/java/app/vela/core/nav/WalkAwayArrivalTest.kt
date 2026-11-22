package app.vela.core.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkAwayArrivalTest {
    /** Parked [secs] seconds, one fix a second, [toDest] from the destination. */
    private fun parked(w: WalkAwayArrival, secs: Int, toDest: Double = 120.0, from: Double = 0.0) {
        for (i in 0..secs) w.onFix(from + i, 0.0, toDest)
    }

    private fun walk(w: WalkAwayArrival, from: Double, steps: Int, gapS: Double = 0.55): Boolean =
        (0 until steps).map { w.onStep(from + it * gapS) }.last()

    @Test fun `parked near the destination and walking off arrives`() {
        val w = WalkAwayArrival()
        parked(w, 20)
        assertTrue(w.watching)
        assertTrue(walk(w, 21.0, WalkAwayArrival.STEPS))
    }

    @Test fun `a short stop does not watch, and steps before it count for nothing`() {
        val w = WalkAwayArrival()
        parked(w, 10)
        assertFalse(w.watching)
        assertFalse(walk(w, 11.0, 40))
    }

    @Test fun `too far from the destination never watches`() {
        val w = WalkAwayArrival()
        parked(w, 60, toDest = WalkAwayArrival.RADIUS_M + 50)
        assertFalse(w.watching)
    }

    @Test fun `a stop still ahead never watches`() {
        val w = WalkAwayArrival()
        for (i in 0..60) w.onFix(i.toDouble(), 0.0, null)
        assertFalse(w.watching)
    }

    @Test fun `driving off again starts over`() {
        val w = WalkAwayArrival()
        parked(w, 20)
        w.onFix(21.0, 8.0, 100.0)
        assertFalse(w.watching)
        assertFalse(walk(w, 22.0, 40))
    }

    @Test fun `a few steps, or steps spread thin, are not walking off`() {
        val w = WalkAwayArrival()
        parked(w, 20)
        assertFalse(walk(w, 21.0, WalkAwayArrival.STEPS - 1))
        val v = WalkAwayArrival()
        parked(v, 20)
        assertFalse(walk(v, 21.0, 40, gapS = 2.0)) // one step every two seconds
    }

    @Test fun `walking pace keeps the watch`() {
        val w = WalkAwayArrival()
        parked(w, 20)
        w.onFix(21.0, 1.4, 110.0)
        assertTrue(w.watching)
    }
}
