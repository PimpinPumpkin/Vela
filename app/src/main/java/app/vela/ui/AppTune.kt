package app.vela.ui

import app.vela.core.config.CalibrationStore

/**
 * A calibration `tuning` dial as the app reads it: an `adb shell setprop debug.vela.tune.<key> <n>`
 * override first (for testing a dial on a device without a signed calibration push), then the
 * adopted bundle, then [default]. debug.* properties can only be set from adb, so nothing in the
 * field can change them.
 */
object AppTune {
    fun value(key: String, default: Double): Double = local(key) ?: CalibrationStore.latest.tune(key, default)

    /** The adb override alone, never the bundle: for a dial that must stay a device-only test hook.
     *  Cheap enough for composition (the screenshot clock reads it per result row). */
    fun local(key: String): Double? = runCatching { sysGet?.invoke(null, "debug.vela.tune.$key") as? String }
        .getOrNull()?.toDoubleOrNull()

    @Suppress("PrivateApi")
    private val sysGet by lazy {
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
    }

    fun on(key: String, default: Boolean): Boolean = value(key, if (default) 1.0 else 0.0) >= 0.5
}
