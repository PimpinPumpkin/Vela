package app.vela.core.location

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

class PositionFilterTest {
    // Davis, the fixture origin.
    private val o = LatLng(38.5449, -121.7405)
    private fun at(east: Double, north: Double) =
        LatLng(o.lat + north / 110_540.0, o.lng + east / (111_320.0 * cos(Math.toRadians(o.lat))))
    private fun northOf(p: LatLng) = (p.lat - o.lat) * 110_540.0

    private fun PositionFilter.fix(
        p: LatLng, t: Double, speed: Double?, bearing: Double?, acc: Double = 5.0, still: Boolean = false,
    ) = update(p, t, acc, speed, null, bearing, null, still)

    @Test
    fun aStraightDriveIsSmootherThanItsFixesAndDoesNotLag() {
        val f = PositionFilter()
        val rnd = Random(7)
        var rawSq = 0.0; var fSq = 0.0; var along = 0.0; var n = 0
        for (t in 0..120) {
            val truth = at(0.0, 15.0 * t)
            val noisy = at(rnd.nextGaussian() * 3, 15.0 * t + rnd.nextGaussian() * 3)
            val out = f.fix(noisy, t.toDouble(), 15.0 + rnd.nextGaussian() * 0.2, rnd.nextGaussian() * 2)
            if (t < 10) continue
            rawSq += noisy.distanceTo(truth).let { it * it }
            fSq += out.position.distanceTo(truth).let { it * it }
            along += northOf(out.position) - 15.0 * t
            n++
        }
        assertTrue("filter ${sqrt(fSq / n)} vs raw ${sqrt(rawSq / n)}", sqrt(fSq / n) < 0.8 * sqrt(rawSq / n))
        assertTrue("lag ${along / n}", abs(along / n) < 1.0)
    }

    @Test
    fun aStoppedCarIgnoresAReflectionThatHoldsForSeconds() {
        val f = PositionFilter()
        val rnd = Random(3)
        for (t in 0..19) f.fix(at(0.0, 10.0 * t), t.toDouble(), 10.0, 0.0)
        val stop = at(0.0, 200.0)
        var t = 20
        repeat(30) { f.fix(at(rnd.nextGaussian() * 2, 200 + rnd.nextGaussian() * 2), (t++).toDouble(), 0.0, null, still = true) }
        repeat(15) {
            val out = f.fix(at(60.0, 200.0), (t++).toDouble(), 0.0, null, acc = 6.0, still = true)
            assertFalse(out.accepted)
            assertTrue(out.position.distanceTo(stop) < 5.0)
        }
        assertTrue(f.fix(at(1.0, 200.0), (t++).toDouble(), 0.0, null, still = true).accepted)
    }

    @Test
    fun aCreepTheAccelerometerCallsStillIsFollowed() {
        val f = PositionFilter()
        for (t in 0..9) f.fix(at(0.0, 0.0), t.toDouble(), 0.0, null, still = true)
        var out: PositionFilter.Out? = null
        for (k in 1..10) out = f.fix(at(0.0, 1.5 * k), 9.0 + k, 1.5, 0.0, still = true)
        assertTrue(out!!.position.distanceTo(at(0.0, 15.0)) < 2.5)
    }

    @Test
    fun aSingleLeapWhileMovingIsNotTaken() {
        val f = PositionFilter()
        for (t in 0..19) f.fix(at(0.0, 15.0 * t), t.toDouble(), 15.0, 0.0)
        val out = f.fix(at(40.0, 300.0), 20.0, 15.0, 0.0)
        assertFalse(out.accepted)
        assertTrue(out.position.distanceTo(at(0.0, 300.0)) < 3.0)
        assertTrue(f.fix(at(0.0, 315.0), 21.0, 15.0, 0.0).accepted)
    }

    @Test
    fun aShiftThatPersistsWhileMovingIsTakenOnTheThirdFix() {
        val f = PositionFilter()
        for (t in 0..19) f.fix(at(0.0, 15.0 * t), t.toDouble(), 15.0, 0.0)
        assertFalse(f.fix(at(40.0, 300.0), 20.0, 15.0, 0.0).accepted)
        assertFalse(f.fix(at(40.0, 315.0), 21.0, 15.0, 0.0).accepted)
        val third = f.fix(at(40.0, 330.0), 22.0, 15.0, 0.0)
        assertTrue(third.accepted)
        assertEquals(0.0, third.position.distanceTo(at(40.0, 330.0)), 0.01)
    }

    @Test
    fun theFirstFixAfterATunnelIsTaken() {
        val f = PositionFilter()
        for (t in 0..19) f.fix(at(0.0, 20.0 * t), t.toDouble(), 20.0, 0.0)
        // 25 s with no fix; the car slowed to 12 m/s inside, so it is 200 m behind the model.
        val out = f.fix(at(0.0, 380.0 + 300.0), 44.0, 12.0, 0.0)
        assertTrue(out.accepted)
        assertTrue(out.position.distanceTo(at(0.0, 680.0)) < 5.0)
    }

    @Test
    fun aBadFirstFixGivesWayOnTheThirdFix() {
        val f = PositionFilter()
        f.fix(at(25.0, 0.0), 0.0, 0.0, null, still = true)
        assertFalse(f.fix(at(0.0, 0.0), 1.0, 0.0, null, still = true).accepted)
        assertFalse(f.fix(at(0.0, 0.0), 2.0, 0.0, null, still = true).accepted)
        val out = f.fix(at(0.0, 0.0), 3.0, 0.0, null, still = true)
        assertTrue(out.accepted)
        assertTrue(out.position.distanceTo(at(0.0, 0.0)) < 1.0)
    }

    @Test
    fun aParkedPhoneAtRestNeverTakesAReflectionHoweverLong() {
        val f = PositionFilter()
        var t = 0.0
        repeat(40) { f.fix(at(0.0, 0.0), t++, 0.0, null, still = true) }
        repeat(120) { assertFalse(f.fix(at(70.0, 0.0), t++, 0.0, null, acc = 8.0, still = true).accepted) }
        assertTrue(f.position!!.distanceTo(at(0.0, 0.0)) < 1.0)
    }

    @Test
    fun aDopplerOnlyStopGivesWayAfterTwentyFixes() {
        val f = PositionFilter()
        var t = 0.0
        repeat(40) { f.fix(at(0.0, 0.0), t++, 0.0, null) }
        repeat(PositionFilter.MAX_REJECTED_STOPPED) { assertFalse(f.fix(at(70.0, 0.0), t++, 0.0, null, acc = 8.0).accepted) }
        val out = f.fix(at(70.0, 0.0), t, 0.0, null, acc = 8.0)
        assertTrue(out.accepted)
        assertTrue(out.position.distanceTo(at(70.0, 0.0)) < 1.0)
    }

    @Test
    fun aWideFirstFixGivesWayAtOnce() {
        val f = PositionFilter()
        f.fix(at(25.0, 0.0), 0.0, 0.0, null, acc = 30.0)
        var out: PositionFilter.Out? = null
        for (t in 1..3) out = f.fix(at(0.0, 0.0), t.toDouble(), 0.0, null)
        assertTrue(out!!.accepted)
        assertTrue(out.position.distanceTo(at(0.0, 0.0)) < 3.0)
    }

    @Test
    fun aDopplerGlitchToZeroAtSpeedIsNotTaken() {
        val f = PositionFilter()
        for (t in 0..19) f.fix(at(0.0, 30.0 * t), t.toDouble(), 30.0, 0.0)
        f.fix(at(0.0, 600.0), 20.0, 0.0, null)
        val out = f.fix(at(0.0, 630.0), 21.0, 30.0, 0.0)
        assertTrue(out.accepted)
        assertTrue(out.position.distanceTo(at(0.0, 630.0)) < 3.0)
    }

    @Test
    fun aTurnIsFollowed() {
        val f = PositionFilter()
        // East at 10 m/s, then a quarter circle of radius 25 m to the left, then north.
        val pts = ArrayList<Triple<LatLng, Double, Double>>() // position, speed, course
        for (t in 0..10) pts += Triple(at(10.0 * t, 0.0), 10.0, 90.0)
        val r = 25.0
        for (k in 1..4) {
            val a = Math.toRadians(90.0 * k / 4)
            pts += Triple(at(100 + r * kotlin.math.sin(a), r - r * cos(a)), 10.0, 90.0 - 90.0 * k / 4)
        }
        for (k in 1..8) pts += Triple(at(125.0, 25.0 + 10.0 * k), 10.0, 0.0)
        for ((i, p) in pts.withIndex()) {
            val out = f.fix(p.first, i.toDouble(), p.second, p.third)
            assertTrue("fix $i off by ${out.position.distanceTo(p.first)}", out.position.distanceTo(p.first) < 4.0)
        }
    }

    @Test
    fun aHardStopIsFollowed() {
        val f = PositionFilter()
        for (t in 0..10) f.fix(at(0.0, 15.0 * t), t.toDouble(), 15.0, 0.0)
        // 5 m/s^2 to a stop from 150 m: 15, 10, 5, 0.
        val norths = doubleArrayOf(150.0 + 12.5, 150.0 + 12.5 + 7.5, 150.0 + 12.5 + 7.5 + 2.5)
        val speeds = doubleArrayOf(10.0, 5.0, 0.0)
        for (k in 0..2) {
            val out = f.fix(at(0.0, norths[k]), 11.0 + k, speeds[k], if (speeds[k] > 0) 0.0 else null)
            assertTrue("step $k", out.accepted && out.position.distanceTo(at(0.0, norths[k])) < 3.0)
        }
        var t = 14.0
        var last = 0.0
        repeat(10) {
            last = f.fix(at(0.0, 172.5), t++, 0.0, null, still = true).position.distanceTo(at(0.0, 172.5))
            assertTrue("at rest $last", last < 1.0)
        }
    }
}
