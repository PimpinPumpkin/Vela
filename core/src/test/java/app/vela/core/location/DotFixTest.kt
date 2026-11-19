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
        assertNull(d.network(east(200.0), 4.0, 4.0))
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

    @Test fun sittingStillTheDotHoldsThroughJumpyIndoorFixes() {
        val d = DotFix()
        d.gps(start, 0.0, 25.0, 0.0, 1.0, null, null, still = false)
        var prev = d.position!!
        var worstStep = 0.0
        var worst = 0.0
        // Two minutes in an office: GPS at 20 to 35 m jumping 25 m any way with a junk speed, and
        // a 15 m Wi-Fi fix every 5 s scattered 12 m.
        for (i in 1..120) {
            var o = d.gps(
                east(25.0 * jitter(i), 25.0 * jitter(500 + i)), i.toDouble(), 20.0 + 15.0 * kotlin.math.abs(jitter(900 + i)),
                1.5 * kotlin.math.abs(jitter(700 + i)), 1.0, (i * 61.0) % 360, 50.0, still = false,
            )
            if (i % 5 == 0) d.network(east(12.0 * jitter(300 + i), 12.0 * jitter(800 + i)), i + 0.5, 15.0)?.let { o = it }
            // The first half minute averages in from a 25 m first fix; after that the dot sits still.
            if (i > 30) {
                worstStep = maxOf(worstStep, o.position.distanceTo(prev))
                worst = maxOf(worst, o.position.distanceTo(start))
            }
            prev = o.position
        }
        assertTrue("largest move between fixes $worstStep m", worstStep < 1.5)
        assertTrue("farthest from the phone $worst m", worst < 6.0)
    }

    @Test fun aWifiFixThatDisagreesWithTheGpsDoesNotPullTheDot() {
        val d = DotFix()
        // Indoors: GPS at 12 m scattered 8 m around the phone, Wi-Fi at 12 m sitting 30 m east of
        // it every 2 s. The dot stays with the GPS and does not swing between the two.
        var prev: LatLng? = null
        var worstStep = 0.0
        var at = start
        for (i in 0..90) {
            at = d.gps(east(8.0 * jitter(i), 8.0 * jitter(600 + i)), i.toDouble(), 12.0, 0.1, 0.3, null, null, still = false).position
            if (i % 2 == 0) d.network(east(30.0 + 3.0 * jitter(40 + i)), i + 0.5, 12.0)?.let { at = it.position }
            if (i > 20) prev?.let { worstStep = maxOf(worstStep, at.distanceTo(it)) }
            prev = at
        }
        assertTrue("ended ${at.distanceTo(start)} m from the phone", at.distanceTo(start) < 8.0)
        assertTrue("largest move $worstStep m", worstStep < 1.5)
    }

    /** A minute sitting still on a GPS that claims half a meter, as a phone indoors does. */
    private fun settled(): DotFix {
        val d = DotFix()
        for (i in 0..60) d.gps(start, i.toDouble(), 0.5, 0.0, 0.02, null, null, still = true)
        return d
    }

    @Test fun afterAGapAWifiFixThatAgreesKeepsTheDot() {
        // Eight minutes with the app away, the phone where it was. The first fix back is Wi-Fi,
        // 11 m off at 9 m, then the GPS returns at 32 m, 46 m off, then good again.
        val d = settled()
        val wifi = d.network(east(3.0, -11.0), 540.0, 9.0)!!
        assertTrue("moved ${wifi.position.distanceTo(start)} m", wifi.position.distanceTo(start) < 1.5)
        assertTrue("circle ${d.accuracyM} m", d.accuracyM >= 9.0)
        d.gps(east(-30.0, -35.0), 541.0, 32.0, 0.2, 0.5, null, null, still = true)
        assertTrue("a wide fix keeps the circle", d.accuracyM >= 9.0)
        var at = d.position!!
        for (i in 2..12) at = d.gps(east(-2.5, -1.5), 540.0 + i, 3.0, 0.0, 0.05, null, null, still = true).position
        assertTrue("ended ${at.distanceTo(east(-2.5, -1.5))} m from the GPS", at.distanceTo(east(-2.5, -1.5)) < 2.0)
        assertTrue("circle ${d.accuracyM} m once the GPS is back", d.accuracyM <= 3.5)
    }

    @Test fun afterAGapAFixFarFromTheDotStartsItOver() {
        // The phone went somewhere while the app was away.
        val d = settled()
        val o = d.network(east(400.0), 600.0, 20.0)!!
        assertTrue("left ${o.position.distanceTo(east(400.0))} m from the fix", o.position.distanceTo(east(400.0)) < 1.0)
    }

    @Test fun aShortPauseIsNoGap() {
        // Under the filter's restart time the Wi-Fi fix waits for the GPS as at any other time.
        val d = settled()
        assertNull(d.network(east(3.0, -11.0), 63.0, 9.0))
        assertTrue(d.accuracyM < 3.0)
    }

    @Test fun aCarsVibrationNeverWalksTheDot() {
        val d = DotFix()
        d.gps(start, 0.0, 5.0, 15.0, 0.3, 90.0, 2.0, still = false)
        assertNull(d.step(0.5, 0.5))
    }
}
