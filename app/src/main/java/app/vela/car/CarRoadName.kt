package app.vela.car

import app.vela.core.nav.NavSession

/**
 * The road the car map's pill names during a drive: the phone pill's rule (the road you are on,
 * following its silent renames, else on an unnamed ramp the road it leads onto), shortened as the
 * car's turn card is. Null when there is nothing to name.
 */
internal object CarRoadName {
    fun of(s: NavSession.State): String? {
        val mans = s.route?.maneuvers ?: return null
        return app.vela.core.nav.roadLabelAt(mans, s.nav.stepIndex, s.nav.distanceToNextManeuver, orNext = true)
            ?.let(app.vela.core.util.RoadNameShort::shorten)
            ?.takeIf { it.isNotBlank() }
    }
}
