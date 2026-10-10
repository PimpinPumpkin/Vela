package app.vela.ui.nav

import app.vela.core.model.ManeuverType

/**
 * The picture a maneuver type gets, shared by every surface that draws one: the turn card, the
 * step list and picture-in-picture through [maneuverIcon], and the notification and Android Auto
 * through [app.vela.service.NavGlyphs]. One table, so the surfaces cannot drift apart.
 *
 * Ramps, forks and keeps get the slight-left or slight-right arrow, which points only where the
 * driver goes. A glyph that also drew the branch not taken read as a road sign allowing either
 * way. Merges and roundabouts keep their own pictures (a roundabout is drawn from its geometry
 * where the maneuver is in hand, see [maneuverIconFor]).
 */
enum class ManeuverGlyph {
    ORIGIN, FLAG, STRAIGHT, UNKNOWN,
    LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN,
    MERGE, ROUNDABOUT,
}

fun maneuverGlyph(type: ManeuverType): ManeuverGlyph = when (type) {
    ManeuverType.DEPART -> ManeuverGlyph.ORIGIN
    ManeuverType.ARRIVE -> ManeuverGlyph.FLAG
    ManeuverType.CONTINUE, ManeuverType.STRAIGHT -> ManeuverGlyph.STRAIGHT
    ManeuverType.UNKNOWN -> ManeuverGlyph.UNKNOWN
    ManeuverType.TURN_LEFT -> ManeuverGlyph.LEFT
    ManeuverType.TURN_RIGHT -> ManeuverGlyph.RIGHT
    ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT,
    ManeuverType.FORK_LEFT, ManeuverType.RAMP_LEFT -> ManeuverGlyph.SLIGHT_LEFT
    ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT,
    ManeuverType.FORK_RIGHT, ManeuverType.RAMP_RIGHT -> ManeuverGlyph.SLIGHT_RIGHT
    ManeuverType.SHARP_LEFT -> ManeuverGlyph.SHARP_LEFT
    ManeuverType.SHARP_RIGHT -> ManeuverGlyph.SHARP_RIGHT
    ManeuverType.UTURN -> ManeuverGlyph.UTURN
    ManeuverType.MERGE -> ManeuverGlyph.MERGE
    ManeuverType.ROUNDABOUT, ManeuverType.EXIT_ROUNDABOUT -> ManeuverGlyph.ROUNDABOUT
}
