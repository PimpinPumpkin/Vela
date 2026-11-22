package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.distanceTo

/**
 * Leaving Google's route with no signal. The phone's router plans only the way back onto it, to a
 * step about [REJOIN_AHEAD_M] further along, and the rest of Google's route is kept as it was: its
 * roads, street names, lanes, signs and exits. OsmAnd's recalculation does the same with its own
 * previous route (`recalculateDistanceHelp`, 10 km). A plain reroute to the destination would
 * trade all of that for the phone's own choice of roads.
 *
 * The joined route is offline ([Route.offline]) like the way back, so it is a stand-in
 * ([RouteHeal.standIn]): once the signal is back, Google's route takes its place.
 */
object RouteRejoin {
    /** Where the way back ends: step [step] of the old route, [point], [alongM] meters along it. */
    data class Target(val step: Int, val point: LatLng, val alongM: Double)

    /**
     * The first step of [old] at least [aheadM] past [alongM] (where the car left it), placed
     * where the engine places it (the sum of the step lengths before it). Null when no step but
     * the arrival is that far on: the plain reroute is then the same thing.
     */
    fun target(old: Route, alongM: Double, aheadM: Double = REJOIN_AHEAD_M): Target? {
        if (old.polyline.size < 2) return null
        val steps = old.maneuvers
        var at = 0.0
        for (i in steps.indices) {
            if (i > 0 && steps[i].type == ManeuverType.ARRIVE) return null
            if (i > 0 && at >= alongM + aheadM) {
                val cum = RouteProjection.cumulative(old.polyline)
                if (at >= cum.last()) return null
                return Target(i, RouteProjection.pointAt(old.polyline, cum, at), at)
            }
            at += steps[i].distanceMeters
        }
        return null
    }

    /**
     * [head], the way from the car to [t], followed by [old] from [t] on. Null when [head] does
     * not end at [t] (more than [JOIN_GAP_M] off), or comes in along another road: its last
     * [APPROACH_M] more than [APPROACH_DEG] off the old route's course into the same point, so
     * the old route's step there ("turn right") would be wrong.
     */
    fun splice(head: Route, old: Route, t: Target): Route? {
        val headSteps = head.maneuvers
        if (headSteps.size < 2 || headSteps.last().type != ManeuverType.ARRIVE) return null
        if (head.polyline.size < 2 || old.polyline.size < 2) return null
        if (head.polyline.last().distanceTo(t.point) > JOIN_GAP_M) return null
        val headCum = RouteProjection.cumulative(head.polyline)
        val oldCum = RouteProjection.cumulative(old.polyline)
        if (headCum.last() < APPROACH_M || t.alongM < APPROACH_M) return null
        val into = courseInto(head.polyline, headCum, headCum.last())
        val oldInto = courseInto(old.polyline, oldCum, t.alongM)
        if (angleBetween(into, oldInto) > APPROACH_DEG) return null

        val tailSteps = old.maneuvers.drop(t.step)
        val from = RouteProjection.segmentEnd(oldCum, t.alongM)
        val tailLine = old.polyline.drop(from).dropWhile { it.distanceTo(t.point) < 1.0 }
        val steps = headSteps.dropLast(1) + tailSteps
        val distance = headSteps.dropLast(1).sumOf { it.distanceMeters } + tailSteps.sumOf { it.distanceMeters }
        val duration = headSteps.dropLast(1).sumOf { it.durationSeconds } + tailSteps.sumOf { it.durationSeconds }
        return Route(
            polyline = head.polyline.dropLast(1) + listOf(t.point) + tailLine,
            legs = listOf(RouteLeg(distance, duration, null, steps)),
            distanceMeters = distance,
            durationSeconds = duration,
            durationInTrafficSeconds = null,
            offline = true,
            source = head.source,
            roadNamesLatin = old.roadNamesLatin + head.roadNamesLatin,
            exitSigns = head.exitSigns + old.exitSigns,
            stepNames = head.stepNames + old.stepNames,
            made = "$MADE${headSteps.size - 1}",
        )
    }

    /** Whether [r] keeps Google's steps past the way back ([splice]). */
    fun joined(r: Route): Boolean = r.made.startsWith(MADE)

    /** The course over the last [APPROACH_M] before [m] meters along [poly]. */
    private fun courseInto(poly: List<LatLng>, cum: DoubleArray, m: Double): Double {
        val a = RouteProjection.pointAt(poly, cum, m - APPROACH_M)
        val b = RouteProjection.pointAt(poly, cum, m)
        val dy = b.lat - a.lat
        val dx = (b.lng - a.lng) * kotlin.math.cos(Math.toRadians((a.lat + b.lat) / 2))
        return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0
    }

    private fun angleBetween(a: Double, b: Double): Double {
        val d = kotlin.math.abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** How far along the old route the way back aims. */
    const val REJOIN_AHEAD_M = 3_000.0
    /** The way back has to end this near the point it was asked for. */
    const val JOIN_GAP_M = 40.0
    /** The way back's course over this last stretch... */
    const val APPROACH_M = 40.0
    /** ...is within this of the old route's into the same point. */
    const val APPROACH_DEG = 45.0
    /** The joined route is kept when no longer than the plain reroute times this plus [REJOIN_SLACK_M]. */
    const val REJOIN_MAX_LONGER = 1.15
    const val REJOIN_SLACK_M = 800.0
    /** [Route.made] of a joined route: the way back's step count follows. */
    const val MADE = "rejoin="
}
