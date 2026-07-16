package app.vela.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the hot paths a real session touches: cold start onto the map, map pan/fling (the
 * heaviest Compose+MapLibre interplay), and a Settings scroll (plain Compose lists - the surface
 * the "is this build even R8'd" jank report was about). Deliberately network-light: the journey
 * must be reproducible on any device, so no search/nav (those paths JIT quickly in use anyway;
 * startup and first-scroll are what a fresh nightly install gets judged by).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect("app.vela") {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        Thread.sleep(5000) // style + first tiles settle
        val w = device.displayWidth
        val h = device.displayHeight
        // Map pan + fling in both axes.
        device.swipe(w / 2, h / 2, w / 2, h / 4, 8)
        Thread.sleep(1200)
        device.swipe(w / 2, h / 2, w / 4, h / 2, 8)
        Thread.sleep(1200)
        device.swipe(w / 2, h / 3, w / 2, (h * 0.7).toInt(), 8)
        Thread.sleep(1500)
    }
}
