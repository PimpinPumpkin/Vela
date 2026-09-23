package app.vela.ui

import app.vela.core.config.CalibrationStore

/**
 * A calibration `tuning` dial as the app reads it: an `adb shell setprop debug.vela.tune.<key> <n>`
 * override first (for testing a dial on a device without a signed calibration push), then the
 * adopted bundle, then [default]. debug.* properties can only be set from adb, so nothing in the
 * field can change them.
 */
object AppTune {
    fun value(key: String, default: Double): Double {
        val local = runCatching {
            @Suppress("PrivateApi")
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.vela.tune.$key") as? String
        }.getOrNull()?.toDoubleOrNull()
        return local ?: CalibrationStore.latest.tune(key, default)
    }

    fun on(key: String, default: Boolean): Boolean = value(key, if (default) 1.0 else 0.0) >= 0.5
}
