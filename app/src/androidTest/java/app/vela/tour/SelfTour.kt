package app.vela.tour

import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.screenshot.Screenshot
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FileOutputStream

/**
 * Helpers for the in-process self-coverage tour (SelfTourTest). Accuracy contract (AGENTS.md
 * "self-coverage"): the suite reads the SAME sources of truth as the external harness always did -
 * the accessibility tree (what uiautomator dumps exposed, now queried in-process in milliseconds
 * instead of ~2.6s XML round-trips) and REAL framebuffer screenshots (androidx.test [Screenshot],
 * which includes the MapLibre GL surface) - and injects input through the REAL system
 * InputDispatcher ([UiDevice.pressKeyCode], the same path as `adb shell input keyevent`). It runs
 * against the R8-minified debug build, the same binary class the external harness tested. On top
 * it ADDS ground truth the old harness could only infer: [UiObject2.isFocused] is the actual focus
 * state. Any tree-vs-pixels disagreement is a failure, never a silent pass.
 */
object SelfTour {
    private const val TAG = "SELFTOUR"
    private const val WAIT = 8_000L

    val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** The restricted-flavor flag, read REFLECTIVELY so this suite compiles on main (no flavors)
     *  and on branches that add BuildConfig.RESTRICTED - absent field = standard behavior. */
    val restricted: Boolean = runCatching {
        Class.forName("app.vela.BuildConfig").getField("RESTRICTED").getBoolean(null)
    }.getOrDefault(false)

    /** Real-dispatcher key press + a short settle. */
    fun dpad(keyCode: Int, settleMs: Long = 300) {
        device.pressKeyCode(keyCode)
        Thread.sleep(settleMs)
    }

    fun byText(text: String): UiObject2? = device.wait(Until.findObject(By.text(text)), WAIT)
    fun byDesc(desc: String): UiObject2? = device.wait(Until.findObject(By.desc(desc)), WAIT)
    fun gone(text: String): Boolean = device.findObject(By.text(text)) == null

    /** Marks a step in logcat with an epoch timestamp so the wrapper script can pull the exact
     *  scrcpy video frames for this moment (the continuous pixel record). */
    fun mark(step: String) = Log.i(TAG, "STEP|$step|" + System.currentTimeMillis())

    /** Real-framebuffer still into the app external files dir (the wrapper pulls these). The
     *  per-surface eyeball record - same artifact class as the external harness, rule unchanged. */
    fun shot(name: String) {
        mark(name)
        val bmp = Screenshot.capture().bitmap
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "selftour").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    /** Assert [o]'s bounds sit fully on the display - the exact small-screen clip check. */
    fun assertOnScreen(label: String, o: UiObject2) {
        val b = o.visibleBounds
        val w = device.displayWidth
        val h = device.displayHeight
        check(b.left >= 0 && b.top >= 0 && b.right <= w && b.bottom <= h) {
            "$label clipped: $b vs ${w}x$h"
        }
    }
}
