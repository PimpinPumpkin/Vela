package app.vela.core.location

import app.vela.core.model.LatLng
import app.vela.core.model.destinationPoint
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkWatchTest {
    private val start = LatLng(38.5449, -121.7405) // Davis fixture

    /** Drives east at 12 m/s for [driveS] s, one fix a second; returns the end and its time. */
    private fun drive(w: ParkWatch, driveS: Int): Pair<LatLng, Double> {
        var at = start
        for (t in 0 until driveS) {
            at = start.destinationPoint(12.0 * t, 90.0)
            w.onFix(t.toDouble(), at, 12.0)
        }
        return at to (driveS - 1).toDouble()
    }

    /** Two steps a second from [t0] for [s] seconds; the first spot handed back. */
    private fun walk(w: ParkWatch, t0: Double, s: Int): LatLng? {
        for (i in 1..2 * s) w.onStep(t0 + i * 0.5)?.let { return it }
        return null
    }

    @Test fun `parked and walked off saves where the car stood`() {
        val w = ParkWatch()
        val (end, t) = drive(w, 60)
        for (i in 1..12) w.onFix(t + i, end, 0.0)
        assertTrue(w.parked)
        val spot = walk(w, t + 12, 10)
        assertEquals(0.0, spot!!.distanceTo(end), 0.5)
        assertFalse(w.parked) // one spot per drive
    }

    @Test fun `a crawl in a queue is not walking off`() {
        val w = ParkWatch()
        val (end, t) = drive(w, 60)
        for (i in 1..20) w.onFix(t + i, end, 0.0)
        // Creeping at walking pace for 40 s: no steps, no spot.
        for (i in 1..40) w.onFix(t + 20 + i, end.destinationPoint(1.4 * i, 90.0), 1.4)
        assertNull(walk(w, t + 60, 0))
        assertNull(w.onLeave(t + 61)) // 56 m on from the stop: not parked there
    }

    @Test fun `a red light is not parking`() {
        val w = ParkWatch()
        val (end, t) = drive(w, 60)
        for (i in 1..40) w.onFix(t + i, end, 0.0)
        for (i in 1..10) w.onFix(t + 40 + i, end.destinationPoint(12.0 * i, 90.0), 12.0)
        assertFalse(w.parked)
        assertNull(w.onLeave(t + 51))
        assertNull(walk(w, t + 51, 10))
    }

    @Test fun `no drive, no spot`() {
        val w = ParkWatch()
        for (i in 0..30) w.onFix(i.toDouble(), start, 0.0)
        assertFalse(w.parked)
        assertNull(walk(w, 30.0, 20))
        assertNull(w.onLeave(60.0))
    }

    @Test fun `steps right after stopping do not count`() {
        val w = ParkWatch()
        val (end, t) = drive(w, 60)
        w.onFix(t + 1, end, 0.0)
        // A passenger shifting about in the first seconds: under PARKED_S, ignored.
        for (i in 1..16) assertNull(w.onStep(t + 1 + i * 0.25))
    }

    @Test fun `the car's screen disconnecting at a stop saves the spot`() {
        val w = ParkWatch()
        val (end, t) = drive(w, 60)
        for (i in 1..5) w.onFix(t + i, end, 0.0)
        assertEquals(0.0, w.onLeave(t + 6)!!.distanceTo(end), 0.5)
        assertNull(w.onLeave(t + 7))
    }

    @Test fun `a disconnect while moving saves nothing`() {
        val w = ParkWatch()
        val (_, t) = drive(w, 60)
        assertNull(w.onLeave(t + 0.5))
    }

    @Test fun `a leave long after the last fix saves nothing`() {
        val w = ParkWatch()
        val (end, t) = drive(w, 60)
        for (i in 1..5) w.onFix(t + i, end, 0.0)
        assertNull(w.onLeave(t + 5 + ParkWatch.LEAVE_MAX_AGE_S + 1))
    }

    @Test fun `settling into the space moves the spot`() {
        val w = ParkWatch()
        val (end, t) = drive(w, 60)
        w.onFix(t + 1, end, 0.0)
        val backed = end.destinationPoint(4.0, 180.0)
        w.onFix(t + 3, end.destinationPoint(2.0, 180.0), 1.0) // reversing slowly
        for (i in 4..20) w.onFix(t + i, backed, 0.0)
        assertEquals(0.0, walk(w, t + 20, 10)!!.distanceTo(backed), 0.5)
    }
}
