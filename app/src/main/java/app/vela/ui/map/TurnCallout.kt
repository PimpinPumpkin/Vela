package app.vela.ui.map

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.distanceTo
import app.vela.core.nav.RouteProjection

/**
 * The blue bubble naming the street the next turn enters (SPEC 4.9, Street callouts): where the tip
 * of its tail goes ([at]) and which way the bubble reaches from it ([side]). The tail sits on the
 * corner and the bubble reaches away from the turn, over the cross street the car does not take:
 * centered on the corner or on the new street, it covered the turn arrow's head.
 */
class TurnCallout(val at: LatLng, val label: String, val side: String) {
    companion object {
        /** A left turn: the bubble reaches right of its tail. */
        const val RIGHT = "right"
        /** A right turn: the bubble reaches left of its tail. */
        const val LEFT = "left"
        /** Neither (a roundabout's exit): the bubble stands over its tail. */
        const val CENTER = "center"
    }
}

/** Steps away from the corner, tried in order, until no drawn light, stop sign or camera is
 *  within [TURN_CALLOUT_CLEAR_M] of the tail: a centered bubble stands over its tail and must
 *  clear the icon with its body. */
private val TURN_CALLOUT_STEPS_M = doubleArrayOf(0.0, 15.0, 30.0, 45.0, 60.0)
private const val TURN_CALLOUT_CLEAR_M = 30.0
/** The same for a bubble reaching to one side, whose body is already off the corner: only its
 *  tail must clear the icon. Stepped 30 m, a left turn's bubble ran off the screen's edge. */
private val TURN_CALLOUT_SIDE_STEPS_M = doubleArrayOf(0.0, 10.0, 20.0, 30.0)
private const val TURN_CALLOUT_SIDE_CLEAR_M = 15.0

/**
 * The callout for turn [m] on route line [poly], named [label], kept clear of [furniture] (the
 * drawn lights, stop signs and cameras). A left or right turn's tail steps along the cross street
 * on the side away from the turn, the new street's direction mirrored through the corner, so the
 * bubble never lands on the arrow; any other turn steps into the new street, as before.
 */
internal fun turnCalloutFor(m: Maneuver?, poly: List<LatLng>, label: String, furniture: List<LatLng>): TurnCallout? {
    if (m == null) return null
    val side = when (m.type) {
        ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT -> TurnCallout.RIGHT
        ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT -> TurnCallout.LEFT
        else -> TurnCallout.CENTER
    }
    if (poly.size < 2) return TurnCallout(m.location, label, side)
    val cum = RouteProjection.cumulative(poly)
    val at = RouteProjection.alongMeters(poly, cum, m.location, 120.0) ?: return TurnCallout(m.location, label, side)
    val corner = RouteProjection.pointAt(poly, cum, at)
    fun spot(s: Double): LatLng {
        if (s <= 0.0) return corner
        val ahead = RouteProjection.pointAt(poly, cum, at + s)
        return if (side == TurnCallout.CENTER) ahead else LatLng(2 * corner.lat - ahead.lat, 2 * corner.lng - ahead.lng)
    }
    val centered = side == TurnCallout.CENTER
    val steps = if (centered) TURN_CALLOUT_STEPS_M else TURN_CALLOUT_SIDE_STEPS_M
    val clear = if (centered) TURN_CALLOUT_CLEAR_M else TURN_CALLOUT_SIDE_CLEAR_M
    val p = steps.asSequence().map(::spot).firstOrNull { q -> furniture.none { it.distanceTo(q) < clear } } ?: corner
    return TurnCallout(p, label, side)
}
