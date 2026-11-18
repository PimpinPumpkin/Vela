package app.vela.core.location

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The dot's FreeFused on foot: Davis, a walk east along a street at 1.4 m/s. */
class DotFixTest {
    private val start = LatLng(38.5449, -121.7405)
    private val mPerDegLng = 111_320.0 * cos(Math.toRadians(start.lat))

    private fun east(m: Double, northM: Double = 0.0) = LatLng(start.lat + northM / 110_540.0, start.lng + m / mPerDegLng)
    private fun jitter(i: Int) = sin(i * 12.9898 + 78.233)

    /** GPS once a second at 5 m with up to 4 m of error, steps every 0.5 s of 0.7 m, compass east. */
    private fun walk(d: DotFix, seconds: Int, speed: Double = 1.4, compass: Double? = 90.0, gps: Boolean = true, from: Double = 0.0, t0: Double = 0.0): List<Pair<Double, LatLng>> {
        val out = ArrayList<Pair<Double, LatLng>>()
        d.compassDeg = compass
        var k = 0
        for (half in 1..seconds * 2) {
            val t = t0 + half * 0.5
            d.step(t, 0.5)?.let { out += t to it.position }
            if (gps && half % 2 == 0) {
                val truth = from + speed * (t - t0)
                val err = 4.0 * jitter(k++)
                val o = d.gps(east(truth + err, 3.0 * jitter(k++)), t + 0.01, 5.0, speed, 0.3, 90.0, 5.0, still = false)
                out += t + 0.01 to o.position
            }
        }
        return out
    }

    private fun alongM(p: LatLng) = (p.lng - start.lng) * mPerDegLng

    @Test fun theDotMovesWithEachStepBetweenFixes() {
        val d = DotFix()
        d.gps(start, 0.0, 5.0, 0.0, 0.3, null, null, still = false)
        val trace = walk(d, 20)
        // Between two fixes, the two steps each move the dot forward.
        val steps = trace.filter { (t, _) -> t % 1.0 == 0.5 }.map { alongM(it.second) }
        var forward = 0
        for (i in 1 until steps.size) if (steps[i] > steps[i - 1]) forward++
        assertTrue("forward $forward of ${steps.size - 1}", forward >= steps.size - 2)
        // And it walks with the walker: within 3 m of the truth at the end.
        val end = trace.last()
        assertEquals(1.4 * 20, alongM(end.second), 3.0)
    }

    @Test fun theDotStopsWhenTheStepsStop() {
        val d = DotFix()
        d.gps(start, 0.0, 5.0, 0.0, 0.3, null, null, still = false)
        walk(d, 15)
        val stopAt = 1.4 * 15
        // Standing: no steps, the GPS says 0 m/s with its usual scatter around the stop.
        var last = d.position!!
        for (i in 1..10) {
            last = d.gps(east(stopAt + 3.0 * jitter(100 + i), 2.0 * jitter(200 + i)), 15.0 + i, 5.0, 0.1, 0.3, null, null, still = false).position
        }
        assertTrue("ended ${alongM(last) - stopAt} m from the stop", kotlin.math.abs(alongM(last) - stopAt) < 2.5)
    }

    @Test fun indoorsTheStepsAndWifiCarryTheDot() {
        val d = DotFix()
        d.gps(start, 0.0, 5.0, 0.0, 0.3, null, null, still = false)
        walk(d, 10)
        // GPS gone for 30 s; steps continue east; a 30 m Wi-Fi fix every 3 s near the truth.
        var lastT = 10.0
        var at: LatLng? = null
        for (half in 1..60) {
            val t = 10.0 + half * 0.5
            d.step(t, 0.5)?.let { at = it.position }
            if (half % 6 == 0) d.network(east(1.4 * t + 10.0 * jitter(half)), t + 0.02, 30.0)?.let { at = it.position }
            lastT = t
        }
        assertNotNull(at)
        assertEquals(1.4 * lastT, alongM(at!!), 8.0)
        assertTrue("accuracy ${d.accuracyM}", d.accuracyM > 3.0)
    }

    @Test fun aNetworkFixWaitsWhileTheGpsIsFreshAndBetter() {
        val d = DotFix()
        d.gps(start, 0.0, 5.0, 0.0, 0.3, null, null, still = false)
        assertNull(d.network(east(200.0), 1.0, 40.0))
        // Quiet GPS: taken, but a far Wi-Fi fix does not drag a 5 m model 200 m.
        val o = d.network(east(200.0), 8.0, 40.0)
        assertNotNull(o)
        assertTrue(o!!.position.distanceTo(start) < 60.0)
    }

    @Test fun theFirstGpsFixAfterNetworkOnlyLandsAtOnce() {
        val d = DotFix()
        d.network(east(300.0), 0.0, 1500.0)
        val o = d.gps(start, 2.0, 5.0, 0.0, 0.3, null, null, still = false)
        assertTrue(o.position.distanceTo(start) < 5.0)
    }

    @Test fun theStepLengthIsLearnedFromTheGps() {
        val d = DotFix()
        d.gps(start, 0.0, 5.0, 0.0, 0.3, null, null, still = false)
        // A long stride: 1.6 m/s at two steps a second is 0.8 m a step.
        walk(d, 40, speed = 1.6)
        assertEquals(0.8, d.stepLengthM, 0.05)
    }

    @Test fun aCrookedPhoneStillStepsTheWayTheWalkGoes() {
        val d = DotFix()
        d.gps(start, 0.0, 5.0, 0.0, 0.3, null, null, still = false)
        // Held 40 degrees off the walk: the compass reads 50 while the walk goes east (90).
        walk(d, 20, compass = 50.0)
        // GPS gone: ten more seconds on steps alone must keep going east, not north-east.
        val before = d.position!!
        var at = before
        d.compassDeg = 50.0
        for (half in 1..20) d.step(20.0 + half * 0.5, 0.5)?.let { at = it.position }
        val de = (at.lng - before.lng) * mPerDegLng
        val dn = (at.lat - before.lat) * 110_540.0
        assertTrue("east $de north $dn", de > 8.0 && kotlin.math.abs(dn) < 4.0)
    }

    @Test fun indoorsAConfidentWrongFirstFixDoesNotPinTheDot() {
        val d = DotFix()
        // Standing indoors: the first fix claims 8 m and is 40 m out (the GPS through a roof); the
        // ones after scatter 8 m around the phone at 12 m, every one reading 0 m/s.
        // It has sat there a minute, past the filter's first 30 s, when it gave way easily.
        for (i in 0..60) d.gps(east(40.0 + 2.0 * jitter(300 + i)), i.toDouble(), 8.0, 0.0, 0.3, null, null, still = false)
        var at = d.position!!
        for (i in 61..70) at = d.gps(east(8.0 * jitter(i), 6.0 * jitter(50 + i)), i.toDouble(), 12.0, 0.0, 0.3, null, null, still = false).position
        assertTrue("after 10 s still ${at.distanceTo(start)} m from the phone", at.distanceTo(start) < 15.0)
    }

    @Test fun indoorsADotOffByLessThanTheGateStillComesBack() {
        val d = DotFix()
        // 25 m out but inside the gate: the old rule took each new fix at a hundredth.
        for (i in 0..60) d.gps(east(25.0 + 2.0 * jitter(400 + i)), i.toDouble(), 8.0, 0.0, 0.3, null, null, still = false)
        var at = d.position!!
        for (i in 61..70) at = d.gps(east(4.0 * jitter(i), 4.0 * jitter(70 + i)), i.toDouble(), 10.0, 0.0, 0.3, null, null, still = false).position
        assertTrue("after 10 s still ${at.distanceTo(start)} m from the phone", at.distanceTo(start) < 8.0)
    }

    @Test fun indoorsJunkSpeedNeverWalksTheDotAway() {
        val d = DotFix()
        d.gps(start, 0.0, 30.0, 0.0, 1.0, null, null, still = false)
        var worst = 0.0
        for (i in 1..60) {
            val o = d.gps(east(15.0 * jitter(i), 15.0 * jitter(100 + i)), i.toDouble(), 30.0, 2.0, 1.5, (i * 83.0) % 360, 60.0, still = false)
            worst = maxOf(worst, o.position.distanceTo(start))
        }
        assertTrue("worst $worst m", worst < 20.0)
    }

    @Test fun aWalkWithNoStepsHeardStillFollowsTheGps() {
        val d = DotFix()
        d.gps(start, 0.0, 8.0, 0.0, 0.3, null, null, still = false)
        // 1 m/s east for 30 s, phone in a bag (no steps), fixes at 8 m with 4 m of scatter.
        var at = start
        for (i in 1..30) at = d.gps(east(1.0 * i + 4.0 * jitter(i), 3.0 * jitter(200 + i)), i.toDouble(), 8.0, 1.0, 0.3, 90.0, 10.0, still = false).position
        assertEquals(30.0, alongM(at), 5.0)
    }

    @Test fun standingStillTheDotKeepsFollowingNewFixes() {
        val d = DotFix()
        d.gps(start, 0.0, 6.0, 0.0, 0.3, null, null, still = false)
        for (i in 1..10) d.gps(start, i.toDouble(), 6.0, 0.0, 0.3, null, null, still = false)
        // The fixes settle 12 m north (a better lock); the dot follows within a few seconds.
        var at = start
        for (i in 11..20) at = d.gps(east(0.0, 12.0), i.toDouble(), 5.0, 0.0, 0.3, null, null, still = false).position
        assertTrue("north ${(at.lat - start.lat) * 110_540.0} m", (at.lat - start.lat) * 110_540.0 > 9.0)
    }

    @Test fun aCarsVibrationNeverWalksTheDot() {
        val d = DotFix()
        d.gps(start, 0.0, 5.0, 15.0, 0.3, 90.0, 2.0, still = false)
        assertNull(d.step(0.5, 0.5))
    }
}
