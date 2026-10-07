package app.vela.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the hot paths a real session touches: cold start onto the map, map pan and fling (the
 * heaviest Compose and MapLibre interplay), and Settings (the hub, a page, a scroll). No search
 * and no navigation: the journey has to run the same on any device with any network, and those
 * paths warm up quickly in use. Startup and the first scroll are what a fresh install is judged
 * by.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect("app.vela") {
        pressHome()
        // Granted up front, so Android's own permission sheets never cover the map.
        PERMISSIONS.forEach { device.executeShellCommand("pm grant app.vela $it") }
        startActivityAndWait()
        device.waitForIdle()
        device.wait(Until.findObject(By.text("Get started")), 2000)?.click()
        dismissPrompts()
        Thread.sleep(5000) // style + first tiles settle
        dismissPrompts() // the offers that wait for a first fix or a network answer
        val w = device.displayWidth
        val h = device.displayHeight
        // Map pan + fling in both axes.
        device.swipe(w / 2, h / 2, w / 2, h / 4, 8)
        Thread.sleep(1200)
        device.swipe(w / 2, h / 2, w / 4, h / 2, 8)
        Thread.sleep(1200)
        device.swipe(w / 2, h / 3, w / 2, (h * 0.7).toInt(), 8)
        Thread.sleep(1500)
        dismissPrompts() // a late one would sit over the Settings button
        // Settings: the hub, a scroll down and back, one page, and out again.
        device.findObject(By.desc("Settings"))?.let { gear ->
            gear.click()
            device.wait(Until.hasObject(By.text("Appearance")), 3000)
            device.swipe(w / 2, (h * 0.8).toInt(), w / 2, (h * 0.3).toInt(), 12)
            Thread.sleep(800)
            device.swipe(w / 2, (h * 0.3).toInt(), w / 2, (h * 0.8).toInt(), 12)
            Thread.sleep(800)
            device.findObject(By.text("Appearance"))?.click()
            Thread.sleep(1200)
            device.pressBack()
            Thread.sleep(600)
            device.pressBack()
            Thread.sleep(600)
        }
    }

    /**
     * A fresh install opens on the welcome screen, then offers the voice download, a region
     * download and an update. Each covers the map, and a journey that stops at one records that
     * prompt and nothing behind it. This takes the "no" side of whatever is showing, and returns
     * at once when nothing is.
     */
    private fun MacrobenchmarkScope.dismissPrompts() {
        repeat(6) {
            val dismiss = device.wait(Until.findObject(By.text(DISMISS)), 1500) ?: return
            dismiss.click()
            device.waitForIdle()
        }
    }

    private companion object {
        /** The "no" side of each first-run prompt. */
        val DISMISS: java.util.regex.Pattern = java.util.regex.Pattern.compile("Not now|Skip|Maybe later|Use system voice")
        val PERMISSIONS = listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
        )
    }
}
