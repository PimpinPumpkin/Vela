package app.vela.core.vehicle

import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType

/**
 * Turn arrows and distance on the instrument cluster of head units built on the FCE/Coagent
 * vehicle HAL (SPEC 10.9). The HAL takes integer vehicle properties; the transport that writes them
 * lives in `:app` (`FceCarPropertyTransport`). This file holds the property ids, the cluster's own
 * glyph codes and the order of writes, so all of it is testable without the HAL.
 *
 * These glyph codes belong to this HAL only. The SOME/IP HUD has its own ids ([SomeIpHudArrows]).
 */
object FceProperties {
    /** Navigation view state: [STATUS_NAVIGATING], [STATUS_CRUISE] or [STATUS_CLOSED]. */
    const val NAVI_STATUS = 0x214000a5
    /** The maneuver glyph, one of [FceIcons]. */
    const val ICON = 0x21400099
    /** A second glyph set the cluster reads only with icon 255. A leftover nonzero value blanks
     *  U-turns and roundabout exits, so it is written 0 before every glyph. */
    const val ICON_SET_2 = 0x2140009a
    /** Meters to the next maneuver. The cluster formats kilometers itself. */
    const val DISTANCE = 0x21400098

    const val STATUS_NAVIGATING = 2
    const val STATUS_CRUISE = 1
    const val STATUS_CLOSED = 0

    /** The MCU carries the distance in 17 bits. */
    const val MAX_DISTANCE_M = 131_071
}

/** The cluster's glyph codes (the AutoNavi turn table, right-hand-traffic roundabouts). */
object FceIcons {
    const val NONE = 0
    const val DESTINATION = 3
    const val STRAIGHT = 16
    const val SLIGHT_RIGHT = 17
    const val SLIGHT_LEFT = 18
    const val LEFT = 19
    const val RIGHT = 20
    const val SHARP_LEFT = 21
    const val SHARP_RIGHT = 22
    const val U_TURN_LEFT = 23
    const val U_TURN_RIGHT = 24
    /** Roundabout showing exit 1. Exits 1 to [ROUNDABOUT_EXIT_MAX] are consecutive codes. */
    const val ROUNDABOUT_EXIT_1 = 42
    const val ROUNDABOUT_EXIT_MAX = 10
    const val ROUNDABOUT_EXIT = 52
    const val ROUNDABOUT_ENTER = 53

    /**
     * The glyph for [m]. A maneuver with no glyph of its own gets the nearest one (a keep, fork or
     * ramp is a slight turn), and one with no meaning gets none: a wrong arrow at a real turn is
     * worse than no arrow, and the distance still shows. Vela's U-turn has no side and is drawn
     * left, as on the phone. A clockwise roundabout shows the counterclockwise glyph with the right
     * exit number, because no clockwise glyph is known.
     */
    fun of(m: Maneuver?): Int = when (m?.type) {
        null, ManeuverType.UNKNOWN -> NONE
        ManeuverType.TURN_LEFT -> LEFT
        ManeuverType.TURN_RIGHT -> RIGHT
        ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.FORK_LEFT, ManeuverType.RAMP_LEFT -> SLIGHT_LEFT
        ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.RAMP_RIGHT -> SLIGHT_RIGHT
        ManeuverType.SHARP_LEFT -> SHARP_LEFT
        ManeuverType.SHARP_RIGHT -> SHARP_RIGHT
        ManeuverType.UTURN -> U_TURN_LEFT
        ManeuverType.DEPART, ManeuverType.CONTINUE, ManeuverType.STRAIGHT, ManeuverType.MERGE -> STRAIGHT
        ManeuverType.ARRIVE -> DESTINATION
        ManeuverType.EXIT_ROUNDABOUT -> ROUNDABOUT_EXIT
        ManeuverType.ROUNDABOUT -> {
            val exit = m.roundaboutExit ?: 0
            if (exit in 1..ROUNDABOUT_EXIT_MAX) ROUNDABOUT_EXIT_1 + exit - 1 else ROUNDABOUT_ENTER
        }
    }

    /**
     * The distance as the cluster should show it: whole 10 m under 1 km, whole 100 m above, so its
     * own formatting reads "1.5 km" and not the exact 1,530 m. Clamped to the 17-bit signal.
     */
    fun distance(meters: Double): Int {
        val m = if (meters.isNaN()) 0.0 else meters.coerceIn(0.0, FceProperties.MAX_DISTANCE_M.toDouble())
        val step = if (m < 1000.0) 10 else 100
        return (Math.round(m / step) * step).toInt().coerceAtMost(FceProperties.MAX_DISTANCE_M)
    }
}

/** Writes one integer property; false when the value did not reach the HAL. */
fun interface FcePropertyWriter {
    fun setInt(propertyId: Int, value: Int): Boolean
}

/** What the cluster should show for a navigation state. */
sealed interface ClusterNav {
    /** A maneuver to draw: its glyph and the rounded distance. */
    data class Guide(val icon: Int, val distanceM: Int) : ClusterNav
    /** Navigating with nothing to show: keep the view open, blank the arrow. */
    data object Standby : ClusterNav
    /** Not navigating, or the feature is off: close the view. */
    data object Stop : ClusterNav
}

/**
 * The cluster output for one navigation state (SPEC 10.9). Off or not navigating or arrived closes
 * the view; paused or no maneuver yet (a reroute) keeps it open and blank; otherwise the arrow and
 * distance. Pure, so the state transitions are tested without the HAL.
 */
fun clusterNavOutput(
    enabled: Boolean,
    navigating: Boolean,
    paused: Boolean,
    arrived: Boolean,
    maneuver: Maneuver?,
    distanceToNextM: Double,
): ClusterNav = when {
    !enabled || !navigating || arrived -> ClusterNav.Stop
    paused || maneuver == null -> ClusterNav.Standby
    else -> ClusterNav.Guide(FceIcons.of(maneuver), FceIcons.distance(distanceToNextM))
}

/**
 * The order of writes the cluster expects, writing only what changed. Status 2 opens the
 * navigation view, and only after it took do the glyph and distance go out. A failed write leaves
 * the last value unknown, so the next call writes it again. Not thread-safe: one caller thread.
 */
class FceClusterOutput(private val writer: FcePropertyWriter) {
    var active = false
        private set
    private var lastIcon: Int? = null
    private var lastDistance: Int? = null

    /** Guidance: [icon] from [FceIcons.of], [distanceM] from [FceIcons.distance]. */
    fun update(icon: Int, distanceM: Int) {
        if (!open()) return
        if (icon != lastIcon) {
            lastIcon = null
            if (writer.setInt(FceProperties.ICON_SET_2, 0) && writer.setInt(FceProperties.ICON, icon)) lastIcon = icon
        }
        val d = distanceM.coerceIn(0, FceProperties.MAX_DISTANCE_M)
        if (d != lastDistance) {
            lastDistance = if (writer.setInt(FceProperties.DISTANCE, d)) d else null
        }
    }

    /** Navigating with nothing to show (no route yet, a reroute, paused): the view stays open and
     *  any glyph or distance left over is blanked. */
    fun standby() {
        if (!open()) return
        if (lastIcon != FceIcons.NONE) lastIcon = if (writer.setInt(FceProperties.ICON, FceIcons.NONE)) FceIcons.NONE else null
        if (lastDistance != 0) lastDistance = if (writer.setInt(FceProperties.DISTANCE, 0)) 0 else null
    }

    /** The stock end of navigation. Without it the cluster keeps showing the last arrow. */
    fun stop() {
        if (!active) return
        writer.setInt(FceProperties.ICON, FceIcons.NONE)
        writer.setInt(FceProperties.ICON_SET_2, 0)
        writer.setInt(FceProperties.DISTANCE, 0)
        writer.setInt(FceProperties.NAVI_STATUS, FceProperties.STATUS_CRUISE)
        writer.setInt(FceProperties.NAVI_STATUS, FceProperties.STATUS_CLOSED)
        active = false
        lastIcon = null
        lastDistance = null
    }

    /** Forgets what the cluster shows, for a HAL that died and came back with its own state. */
    fun reset() {
        active = false
        lastIcon = null
        lastDistance = null
    }

    private fun open(): Boolean {
        if (active) return true
        if (!writer.setInt(FceProperties.NAVI_STATUS, FceProperties.STATUS_NAVIGATING)) return false
        active = true
        lastIcon = null
        lastDistance = null
        return true
    }
}
