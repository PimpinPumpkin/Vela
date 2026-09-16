package app.vela.ui.map

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the free-drive follow camera should be RIGHT NOW, between ~1 Hz GPS fixes. The old
 * ticker dead-reckoned from the latest fix (fix + speed × time since it) and RESET that anchor
 * on every new fix. Two things made that a sawtooth on surface streets (user 2026-09-16, "moves,
 * stops for a sec, then moves"): the fix it anchored to is the view model's parked-hold
 * low-passed position, which lags the truth by up to a fix at city speed, so each re-anchor
 * stepped the target BACK; and even a raw fix is noisy, so each re-anchor was a jump the 0.22 s
 * camera ease turned into a surge-and-stall. This keeps one continuously integrated estimate
 * and treats a new fix as a correction: pull a fraction of the residual now (the rest is
 * absorbed by the next fixes), keep integrating the speed along the course every frame. A fix
 * older than 2.5 s stops the integration so a dropped signal cannot run the camera away. The
 * correction itself is spread over ~0.9 s, so a fix behind the estimate slows the glide rather
 * than reversing it (unit-tested with fixes lagging 0.8 s at city speed).
 * Standing still (no course, or under walking speed) the estimate simply follows the fix.
 */
internal class FollowEstimator {
    var lat = Double.NaN
        private set
    var lng = Double.NaN
        private set
    private var speed = 0.0
    private var course = Double.NaN
    private var fixAtMs = 0L
    // A fix's correction is not applied in one frame but spread over CORR_S, so a fix that lands
    // behind the estimate (GPS noise, or a lagged position) slows the glide instead of reversing it.
    private var pendLat = 0.0
    private var pendLng = 0.0
    private var pendLeftS = 0.0

    val moving: Boolean get() = speed > 1.5 && !course.isNaN()

    fun onFix(fixLat: Double, fixLng: Double, speedMps: Double, courseDeg: Double?, nowMs: Long) {
        speed = speedMps
        course = courseDeg ?: Double.NaN
        fixAtMs = nowMs
        if (lat.isNaN()) { lat = fixLat; lng = fixLng; return }
        if (!moving) {
            lat = fixLat; lng = fixLng; pendLat = 0.0; pendLng = 0.0; pendLeftS = 0.0
            return
        }
        pendLat = (fixLat - lat) * GAIN_MOVING
        pendLng = (fixLng - lng) * GAIN_MOVING
        pendLeftS = CORR_S
    }

    fun step(dtSec: Double, nowMs: Long) {
        if (lat.isNaN() || !moving || nowMs - fixAtMs > FRESH_MS) return
        val m = speed * dtSec
        val br = Math.toRadians(course)
        lat += m * cos(br) / 111_320.0
        lng += m * sin(br) / (111_320.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        if (pendLeftS > 0.0) {
            val f = (dtSec / pendLeftS).coerceAtMost(1.0)
            lat += pendLat * f; lng += pendLng * f
            pendLat -= pendLat * f; pendLng -= pendLng * f
            pendLeftS -= dtSec
        }
    }

    fun reset() { lat = Double.NaN; lng = Double.NaN; speed = 0.0; course = Double.NaN; fixAtMs = 0L; pendLat = 0.0; pendLng = 0.0; pendLeftS = 0.0 }

    companion object {
        /** Share of a fix's residual applied at once while moving; the rest rides the next fixes. */
        const val GAIN_MOVING = 0.5
        /** Seconds over which a fix's correction is spread. */
        const val CORR_S = 0.9
        const val FRESH_MS = 2_500L
    }
}
