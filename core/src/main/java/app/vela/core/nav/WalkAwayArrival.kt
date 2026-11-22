package app.vela.core.nav

/**
 * Arriving by getting out and walking: a drive parked short of its destination (the back of a big
 * lot, a spot down the block) never came within the engine's arrival distances, and with the car
 * disconnect no longer ending the drive (the car's stop request after it is gone is ignored) the
 * phone kept guiding to the door. Google treats parking near the destination and walking away as
 * arriving. Here: once the car has stood still for [PARKED_S] within [RADIUS_M] of the final
 * destination, the step sensor is watched ([watching]); [STEPS] steps within [WINDOW_S] end the
 * drive as arrived. Moving at driving speed again, or leaving the radius, starts it over.
 *
 * Steps, not GPS speed: a crawl in a queue near the destination moves at walking pace too, and the
 * accelerometer's step rhythm is what a car never makes. Not thread-safe; [NavSession] calls it
 * from its location and step paths under its own lock.
 */
class WalkAwayArrival {
    private var stillSinceS = Double.NaN
    private val steps = ArrayDeque<Double>()

    /** Whether the step sensor should run: parked long enough near the destination. */
    var watching = false
        private set

    /** One fix: its time on the boot clock, its speed (null when unknown) and the straight-line
     *  distance to the final destination (null when a stop is still ahead, or no destination). */
    fun onFix(tSec: Double, speedMps: Double?, toDestM: Double?) {
        if (toDestM == null || toDestM > RADIUS_M || (speedMps ?: 0.0) >= DRIVING_MPS) {
            reset()
            return
        }
        if ((speedMps ?: 0.0) <= STILL_MPS) {
            if (stillSinceS.isNaN()) stillSinceS = tSec
            if (tSec - stillSinceS >= PARKED_S) watching = true
        }
        // Walking away keeps the watch: speed at walking pace is between still and driving.
    }

    /** One step at [tSec] on the boot clock; true when the steps say the driver has walked off. */
    fun onStep(tSec: Double): Boolean {
        if (!watching) return false
        steps.addLast(tSec)
        while (steps.isNotEmpty() && tSec - steps.first() > WINDOW_S) steps.removeFirst()
        return steps.size >= STEPS
    }

    fun reset() {
        stillSinceS = Double.NaN
        watching = false
        steps.clear()
    }

    companion object {
        /** Parked within this straight-line distance of the destination counts as there. */
        const val RADIUS_M = 250.0
        /** Standing still this long first: a stop at a light is not parking. */
        const val PARKED_S = 15.0
        /** Steps within [WINDOW_S] that mean walking, not shifting in a seat or a bump. */
        const val STEPS = 16
        const val WINDOW_S = 15.0
        /** At or under this the car is still. */
        const val STILL_MPS = 0.5
        /** At or over this the car is driving again, and the watch starts over. */
        const val DRIVING_MPS = 4.0
    }
}
