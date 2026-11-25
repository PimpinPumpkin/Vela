package app.vela.core.location

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo

/**
 * Where the car was left, for "Save where I park" (`AutoParking`). Google saves the spot when the
 * phone leaves the car: Android Auto or the car's Bluetooth disconnecting, or Play services seeing
 * driving turn into walking. Vela has the fixes it gets while it runs, the step detector, and a
 * few leave signals.
 *
 * A spot is where the car stood still ([STILL_MPS] or slower) after a drive (a fix at
 * [DRIVING_MPS] or faster). It is handed back when the driver walks off: [STEPS] steps within
 * [WINDOW_S] once the car has stood [PARKED_S] ([onStep]). Steps, not speed: a crawl in a queue
 * moves at walking pace too, and a car makes no step rhythm. Or when [onLeave] is told the phone
 * left the car (the car's screen disconnecting, a drive ending on foot, Vela leaving the screen at
 * the destination) while the car stands still. A fix faster than a walk means the car is moving,
 * and the spot starts over. Until the first step, still fixes within [SETTLE_M] move the spot (a
 * slow reverse into the space). A drive more than [DROVE_FORGET_S] ago with no stop since is
 * forgotten. Each drive gives at most one spot.
 *
 * GPS fixes only. Not thread-safe; the caller holds a lock.
 */
class ParkWatch {
    private var drove = false
    private var lastDriveS = Double.NaN
    private var stillSinceS = Double.NaN
    private var stillAt: LatLng? = null
    private val steps = ArrayDeque<Double>()
    private var lastT = Double.NaN
    private var lastAt: LatLng? = null
    private var lastSpeed = Double.NaN

    /** A fix at [tSec] (boot clock) and its speed. A fix with no speed is skipped. */
    fun onFix(tSec: Double, at: LatLng, speedMps: Double?) {
        if (!lastT.isNaN() && tSec <= lastT) return
        lastT = tSec
        lastAt = at
        val sp = speedMps ?: return
        lastSpeed = sp
        if (drove && stillAt == null && tSec - lastDriveS > DROVE_FORGET_S) drove = false
        when {
            sp >= WALK_MAX_MPS -> {
                // Faster than anyone walks: the car is moving (or creeping round a lot).
                if (sp >= DRIVING_MPS) { drove = true; lastDriveS = tSec }
                stillSinceS = Double.NaN
                stillAt = null
                steps.clear()
            }
            !drove -> {}
            sp <= STILL_MPS -> {
                val spot = stillAt
                if (spot == null) { stillSinceS = tSec; stillAt = at }
                // Settling into the space moves the spot; once the driver is walking it does not.
                else if (steps.isEmpty() && spot.distanceTo(at) <= SETTLE_M) stillAt = at
            }
        }
    }

    /** Whether the last fix, under [LEAVE_MAX_AGE_S] old at [tSec], was faster than a walk: a leave
     *  signal now (a Bluetooth glitch mid-drive) is not parking. */
    fun movingAt(tSec: Double): Boolean =
        !lastT.isNaN() && tSec - lastT <= LEAVE_MAX_AGE_S && lastSpeed >= WALK_MAX_MPS

    /** Whether steps are wanted: a drive is on record and the car stands still. */
    val parked: Boolean get() = drove && stillAt != null

    /** A step at [tSec] (boot clock); the spot once the steps say the driver walked off. */
    fun onStep(tSec: Double): LatLng? {
        val spot = stillAt ?: return null
        if (!drove || tSec - stillSinceS < PARKED_S) return null
        steps.addLast(tSec)
        while (steps.isNotEmpty() && tSec - steps.first() > WINDOW_S) steps.removeFirst()
        return if (steps.size >= STEPS) take(spot) else null
    }

    /** The phone left the car at [tSec]: the spot, when the car had stood still for
     *  [LEAVE_STILL_S] after a drive, and the last fix is under [LEAVE_MAX_AGE_S] old and within
     *  [LEAVE_NEAR_M] of it (a car that crept on from its stop is not parked there). */
    fun onLeave(tSec: Double): LatLng? {
        val spot = stillAt ?: return null
        if (!drove || lastT.isNaN() || tSec - lastT > LEAVE_MAX_AGE_S) return null
        if ((lastAt?.distanceTo(spot) ?: Double.MAX_VALUE) > LEAVE_NEAR_M) return null
        if (tSec - stillSinceS < LEAVE_STILL_S) return null
        return take(spot)
    }

    fun reset() {
        drove = false
        lastDriveS = Double.NaN
        stillSinceS = Double.NaN
        stillAt = null
        steps.clear()
    }

    private fun take(spot: LatLng): LatLng {
        reset()
        return spot
    }

    companion object {
        /** A fix this fast was driving. */
        const val DRIVING_MPS = 4.0
        /** Faster than a brisk walk: a fix this fast is the car moving, not the driver. */
        const val WALK_MAX_MPS = 2.5
        /** At or under this the car stands still. */
        const val STILL_MPS = 0.5
        /** Still this long before steps count: getting out of a car takes longer. */
        const val PARKED_S = 10.0
        /** Steps within [WINDOW_S] that mean walking, not shifting in a seat (as [app.vela.core.nav.WalkAwayArrival]). */
        const val STEPS = 16
        const val WINDOW_S = 15.0
        /** Within this of where the car first stood, it is still settling into the space. */
        const val SETTLE_M = 10.0
        /** Still at least this long when a leave signal comes. */
        const val LEAVE_STILL_S = 3.0
        /** The last fix this near the spot for a leave signal to save it. */
        const val LEAVE_NEAR_M = 25.0
        /** A leave signal more than this after the last fix knows nothing about where the car is. */
        const val LEAVE_MAX_AGE_S = 120.0
        /** A drive this long ago with no stop since is forgotten. */
        const val DROVE_FORGET_S = 1800.0
    }
}
