package app.vela.core.location

import app.vela.core.model.LatLng

/**
 * The live position guidance steers by, shared by both feeds of the one nav engine: the phone's
 * (the map's view model) and the car screen's (`VelaCarSession`, which is what runs under
 * Android Auto, Android Automotive, Gearslip and myCar). One [PositionFilter] serves both, so a
 * drive that moves from one feed to the other keeps its model, and a fix both feeds receive is
 * handed to the engine once.
 *
 * The phone's feed is the drive's when it runs: it records the trip and gates the speed. It
 * marks each fix it hands the engine ([phoneFed]); the car's feed stands down while that mark is
 * fresh ([phoneFeeding]) and steers on its own only when the phone app is closed, as on an
 * Automotive head unit or a projection with the phone locked.
 */
object GuidanceFix {
    /** How long after the phone's feed last handed the engine a fix the car's stands down. */
    const val PHONE_FRESH_MS = 2_500L

    private val filter = PositionFilter()
    private var lastT = Double.NaN
    private var lastOut: PositionFilter.Out? = null

    @Volatile private var phoneFedAtMs = 0L

    /** One fix taken by [PositionFilter.update]. [fresh] false: this fix (by its time) was taken
     *  already, by the other feed, and is not to be handed to the engine again. */
    class Take(val out: PositionFilter.Out, val fresh: Boolean)

    @Synchronized
    fun take(
        fix: LatLng, tSec: Double, accuracyM: Double?, speedMps: Double?, speedAccMps: Double?,
        bearingDeg: Double?, bearingAccDeg: Double?, still: Boolean,
    ): Take {
        lastOut?.let { if (tSec == lastT) return Take(it, fresh = false) }
        val out = filter.update(fix, tSec, accuracyM, speedMps, speedAccMps, bearingDeg, bearingAccDeg, still)
        lastT = tSec
        lastOut = out
        return Take(out, fresh = true)
    }

    /** The phone's feed handed the engine a fix at [nowMs] (elapsed realtime). */
    fun phoneFed(nowMs: Long) {
        phoneFedAtMs = nowMs
    }

    /** The phone's feed is running: the car's stands down. */
    fun phoneFeeding(nowMs: Long): Boolean = phoneFedAtMs > 0L && nowMs - phoneFedAtMs < PHONE_FRESH_MS

    /** Start over (tests). */
    @Synchronized
    fun reset() {
        filter.reset()
        lastT = Double.NaN
        lastOut = null
        phoneFedAtMs = 0L
    }
}
