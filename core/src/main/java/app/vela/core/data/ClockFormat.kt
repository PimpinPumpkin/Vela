package app.vela.core.data

/** The device's 12/24-hour clock setting, pushed in by the app (a plain holder, like
 *  [LowDataMode]): :core has no Context, and the transit boards format times. */
object ClockFormat {
    @Volatile var use24h: Boolean = false
}
