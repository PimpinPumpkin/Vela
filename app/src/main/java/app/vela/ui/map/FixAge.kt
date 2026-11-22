package app.vela.ui.map

import app.vela.core.model.LatLng

/**
 * How old the live fix on the map is: a GPS fix reaches the app about 150 ms after the receiver
 * measured it (a Pixel 9 drive, 580 fixes: 139 ms to 166 ms, p10 to p90), and the puck that takes
 * it as "now" draws the car that far back. The location handler sets the fix it publishes with its
 * own clock (`Location.elapsedRealtimeNanos`); the map reads the age when it takes that same fix.
 * A holder, not a map parameter: `MapScreen` cannot take another argument (CLAUDE.md).
 */
internal object FixAge {
    @Volatile private var fix: LatLng? = null
    @Volatile private var rtNanos = 0L

    /** [loc] is the position published for a fix measured at [elapsedRealtimeNanos] (0 = unknown). */
    fun set(loc: LatLng, elapsedRealtimeNanos: Long) {
        rtNanos = elapsedRealtimeNanos
        fix = loc
    }

    fun clear() { fix = null; rtNanos = 0L }

    /** Milliseconds since [loc] was measured, or 0 when it is not the fix last set, has no clock, or
     *  is older than [MAX_MS] (a stale fix, not a delivery delay). */
    fun ageMs(loc: LatLng?, nowNanos: Long = android.os.SystemClock.elapsedRealtimeNanos()): Long {
        if (loc == null || loc !== fix) return 0L
        val t = rtNanos
        if (t <= 0L) return 0L
        val age = (nowNanos - t) / 1_000_000L
        return if (age in 0L..MAX_MS) age else 0L
    }

    /** Ages past this are not delivery delay, and moving such a fix forward would guess. */
    const val MAX_MS = 1_000L
}
