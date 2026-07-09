package app.vela.car

import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import app.vela.core.model.ManeuverType
import app.vela.core.model.Maneuver as VelaManeuver

/**
 * Maps Vela's routing model ([VelaManeuver]/[ManeuverType], metres, seconds) onto the
 * `androidx.car.app.navigation` types a [androidx.car.app.navigation.model.NavigationTemplate]
 * consumes. Pure + unit-testable — no Android Auto host needed.
 *
 * The one sharp edge the car API has: roundabout maneuvers built with an ENTER_AND_EXIT type
 * REQUIRE a roundabout exit number or `Maneuver.Builder.build()` throws. [carManeuver] always
 * supplies a concrete direction/side and a default exit number so no input ever throws.
 */
object ManeuverMapper {

    /** Right-hand traffic (US, Israel, most of the world) → counter-clockwise roundabouts; the
     *  car API needs a concrete CW/CCW type. A future locale hook can flip this for the UK etc. */
    private const val ROUNDABOUT_CCW = true

    fun carManeuver(m: VelaManeuver): Maneuver {
        val type = when (m.type) {
            ManeuverType.DEPART -> Maneuver.TYPE_DEPART
            ManeuverType.ARRIVE -> Maneuver.TYPE_DESTINATION
            ManeuverType.CONTINUE, ManeuverType.STRAIGHT, ManeuverType.UNKNOWN -> Maneuver.TYPE_STRAIGHT
            ManeuverType.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            ManeuverType.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            ManeuverType.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            ManeuverType.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            ManeuverType.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            ManeuverType.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            ManeuverType.UTURN -> Maneuver.TYPE_U_TURN_LEFT
            ManeuverType.MERGE -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
            ManeuverType.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
            ManeuverType.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
            ManeuverType.RAMP_LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
            ManeuverType.RAMP_RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
            ManeuverType.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
            ManeuverType.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
            ManeuverType.ROUNDABOUT, ManeuverType.EXIT_ROUNDABOUT ->
                if (ROUNDABOUT_CCW) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
                else Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        }
        val b = Maneuver.Builder(type)
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW ||
            type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
        ) {
            b.setRoundaboutExitNumber(1) // exit count unknown from OSRM here; 1 keeps the builder valid
        }
        return b.build()
    }

    /** A car [Step] for the current maneuver: the spoken cue + the road name being entered. */
    fun carStep(m: VelaManeuver): Step {
        val cue: CharSequence = m.instruction.ifBlank { m.road?.takeIf { it.isNotBlank() } ?: m.ref?.takeIf { it.isNotBlank() } ?: " " }
        return Step.Builder(cue)
            .setManeuver(carManeuver(m))
            .apply { (m.road ?: m.ref)?.takeIf { it.isNotBlank() }?.let { setRoad(it as CharSequence) } }
            // Lane guidance (Google's arrow diagram) — both the Lane metadata and a rendered image.
            .apply {
                runCatching {
                    LaneImage.build(m.lanes)?.let { (lanes, image) ->
                        lanes.forEach { addLane(it) }
                        setLanesImage(image)
                    }
                }
            }
            .build()
    }

    /** Metres → a car [Distance], rounded Google-style, in the user's unit system. */
    fun carDistance(meters: Double, imperial: Boolean): Distance {
        return if (imperial) {
            val feet = meters * 3.28084
            if (feet < 1000) Distance.create(roundStep(feet, if (feet >= 100) 50.0 else 10.0), Distance.UNIT_FEET)
            else Distance.create(meters / 1609.344, Distance.UNIT_MILES)
        } else {
            if (meters < 1000) Distance.create(roundStep(meters, if (meters >= 100) 50.0 else 10.0), Distance.UNIT_METERS)
            else Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS)
        }
    }

    // Round to the nearest step, but allow 0 (at the turn / arrival the host renders "Now"). The old
    // coerceAtLeast(step) floored every distance to 10 ft/10 m so it never showed 0.
    private fun roundStep(v: Double, step: Double): Double = (Math.round(v / step) * step).coerceAtLeast(0.0)

    /** The next-turn card for the [NavigationTemplate] — current step + distance to it, plus the
     *  following maneuver as the "then" step (Google's "then turn left" junction preview). */
    fun routingInfo(next: VelaManeuver?, then: VelaManeuver?, distanceToNext: Double, imperial: Boolean): RoutingInfo {
        val b = RoutingInfo.Builder()
        if (next == null) return b.setLoading(true).build()
        b.setCurrentStep(carStep(next), carDistance(distanceToNext, imperial))
        if (then != null) runCatching { b.setNextStep(carStep(then)) } // "then …" preview
        return b.build()
    }

    /** A per-STEP [TravelEstimate] (distance + time to the NEXT maneuver) for Trip.addStep — the host's
     *  navigation data channel (cluster/rail). Time is a rough proportional estimate; the host mainly
     *  uses the distance. */
    fun stepEstimate(distanceToStepMeters: Double, secondsToStep: Double, nowEpochMillis: Long, imperial: Boolean): TravelEstimate {
        val secs = secondsToStep.coerceAtLeast(0.0)
        val arrivalMs = nowEpochMillis + (secs * 1000).toLong()
        val arrival = androidx.car.app.model.DateTimeWithZone.create(arrivalMs, java.util.TimeZone.getDefault())
        return TravelEstimate.Builder(carDistance(distanceToStepMeters, imperial), arrival)
            .setRemainingTimeSeconds(secs.toLong())
            .build()
    }

    /** Destination ETA estimate: remaining distance, remaining time, and arrival wall-clock. */
    fun destinationEstimate(remainingMeters: Double, remainingSeconds: Double, nowEpochMillis: Long, imperial: Boolean): TravelEstimate {
        // Clamp remaining ≥ 0 so a momentary NavEngine overshoot can't show a past arrival time.
        val remaining = remainingSeconds.coerceAtLeast(0.0)
        val arrivalMs = nowEpochMillis + (remaining * 1000).toLong()
        val zone = java.util.TimeZone.getDefault()
        val arrival = androidx.car.app.model.DateTimeWithZone.create(arrivalMs, zone)
        return TravelEstimate.Builder(carDistance(remainingMeters, imperial), arrival)
            .setRemainingTimeSeconds(remaining.toLong())
            .build()
    }

}
