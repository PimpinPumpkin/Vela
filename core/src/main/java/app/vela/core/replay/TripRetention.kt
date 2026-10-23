package app.vela.core.replay

/**
 * How long a saved drive is kept when "Delete drives after 30 days" is on (the first-run card and
 * Settings > Diagnostics). A drive's age is from when its file was last written, the end of the
 * drive. Off keeps every drive, which is what installs from before the setting do until they
 * turn it on.
 */
object TripRetention {
    const val KEEP_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Whether a drive last written at [modifiedMs] is past [KEEP_DAYS] at [nowMs]. */
    fun expired(modifiedMs: Long, nowMs: Long): Boolean = nowMs - modifiedMs > KEEP_DAYS * DAY_MS
}
