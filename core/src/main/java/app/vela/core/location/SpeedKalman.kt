package app.vela.core.location

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 1-D Kalman filter over the nav puck's along-route SPEED, fusing the ~1 Hz GPS fixes
 * (measurement update) with the accelerometer (prediction step) — the missing piece behind the
 * puck's weird behaviour when slowing or stopping: dead reckoning at the LAST fix's speed keeps
 * gliding at full speed for up to a whole fix interval after you brake, and since the displayed
 * progress is monotonic (never backward, by design — GPS jitter protection) the overshoot can't
 * be walked back; the puck sat metres ahead of a stopping car until the car "caught up" to it.
 *
 * With the accelerometer in the predict step the modelled speed collapses the moment you brake —
 * `v ← v + a·dt` each frame, clamped at 0 — so the puck decelerates WITH the car between fixes
 * (what Google's puck does). No accelerometer (sensor missing / not yet delivering) degrades
 * gracefully: `a = 0` holds the speed constant, which is exactly the old behaviour.
 *
 * Pure math, no Android — unit-tested in `:core`. Units: seconds, m/s, m/s².
 */
class SpeedKalman {
    /** Current speed estimate (m/s, never negative). Meaningless until the first [update]. */
    var speed = 0.0
        private set

    private var p = INIT_VAR // estimate variance (m/s)²
    private var seeded = false

    /** Advance the model by [dt] seconds under measured forward acceleration [accel] (m/s²,
     *  + = speeding up along the travel bearing). Call per frame; no-op until the first fix. */
    fun predict(accel: Double, dt: Double) {
        if (!seeded || dt <= 0.0) return
        val a = accel.coerceIn(-MAX_ACCEL, MAX_ACCEL) // a spike can't teleport the model
        speed = (speed + a * dt).coerceAtLeast(0.0)
        // Uncertainty grows with time; faster when the (noisy) accelerometer is actively
        // steering the model, so the next GPS fix pulls harder after a hard brake/launch.
        p += (Q_BASE + Q_ACCEL * abs(a)) * dt
    }

    /** Fold in a GPS speed measurement (m/s). Seeds the filter on the first call. */
    fun update(gpsSpeed: Double) {
        val v = gpsSpeed.coerceAtLeast(0.0)
        if (!seeded) {
            speed = v
            p = R_GPS
            seeded = true
            return
        }
        val k = p / (p + R_GPS)
        speed = (speed + k * (v - speed)).coerceAtLeast(0.0)
        p = ((1 - k) * p).coerceAtLeast(P_FLOOR) // floor: never lock out future measurements
    }

    /** Decay the modelled speed toward 0 during a measurement OUTAGE (no fixes > the dead-reckon
     *  window). With no update() calls and accel ≈ 0 the filter otherwise holds its last speed
     *  FOREVER — a parked car whose GPS went quiet kept "moving" at the braking speed. τ = 4 s:
     *  fast enough to settle a stop, slow enough that a brief tunnel doesn't zero a real cruise
     *  before the next fix re-measures. Uncertainty grows so the next fix pulls hard. */
    fun decay(dt: Double, tau: Double = 4.0) {
        if (!seeded || dt <= 0.0) return
        speed *= kotlin.math.exp(-dt / tau)
        p += Q_BASE * dt
    }

    /** Forget everything (nav ended). The next [update] re-seeds. */
    fun reset() {
        speed = 0.0
        p = INIT_VAR
        seeded = false
    }

    private companion object {
        // Tuned so that with NO accelerometer (predict a=0) a fix still pulls ~2/3 of the way to
        // the GPS speed — the old code snapped to each fix outright, so the fallback must stay
        // nearly as reactive, just jitter-damped. Steady-state gain at 1 Hz fixes ≈ 0.69.
        const val R_GPS = 0.5     // GPS doppler speed noise (m/s)² — doppler is genuinely good
        const val Q_BASE = 0.75   // variance growth per second, coasting
        const val Q_ACCEL = 0.5   // extra growth per (m/s²) of applied accel — accel is noisy
        const val MAX_ACCEL = 8.0 // beyond ±8 m/s² is sensor junk, not driving
        const val INIT_VAR = 4.0
        const val P_FLOOR = 0.05
    }
}

/** Acceleration along a travel bearing: projects world-frame horizontal acceleration
 *  ([east], [north], m/s²) onto [bearingDeg] (0 = North, clockwise, i.e. a compass bearing).
 *  Positive = speeding up along the bearing, negative = braking. */
fun forwardAccel(east: Double, north: Double, bearingDeg: Double): Double {
    val r = Math.toRadians(bearingDeg)
    return east * sin(r) + north * cos(r)
}
