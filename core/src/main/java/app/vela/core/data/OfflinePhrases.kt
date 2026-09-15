package app.vela.core.data

import app.vela.core.model.ManeuverType

/**
 * Instruction text for the on-device router's maneuvers (the obf engine): maps a [ManeuverType]
 * onto the OSRM `(type, modifier)` grammar and phrases it through the localized NavStrings table,
 * so offline guidance reads and speaks like the online path in every app language. Also the
 * one bounding-box test the region indexes share.
 */
object OfflinePhrases {
    /** Whether ([lat],[lng]) lies inside the box `[s],[w],[n],[e]`. */
    fun inBox(s: Double, w: Double, n: Double, e: Double, lat: Double, lng: Double) =
        lat in s..n && lng in w..e

        fun phrase(type: ManeuverType, road: String?, rbExit: Int? = null, dest: String? = null, exitNo: String? = null): String {
        val (t, mod) = when (type) {
            ManeuverType.DEPART -> "depart" to null
            ManeuverType.ARRIVE -> "arrive" to null
            ManeuverType.CONTINUE, ManeuverType.STRAIGHT -> "continue" to "straight"
            ManeuverType.TURN_LEFT -> "turn" to "left"
            ManeuverType.TURN_RIGHT -> "turn" to "right"
            ManeuverType.SLIGHT_LEFT -> "turn" to "slight left"
            ManeuverType.SLIGHT_RIGHT -> "turn" to "slight right"
            ManeuverType.SHARP_LEFT -> "turn" to "sharp left"
            ManeuverType.SHARP_RIGHT -> "turn" to "sharp right"
            ManeuverType.UTURN -> "uturn" to null
            ManeuverType.MERGE -> "merge" to null
            ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT -> "fork" to "left"
            ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT -> "fork" to "right"
            ManeuverType.RAMP_LEFT, ManeuverType.RAMP_RIGHT -> "ramp" to null
            ManeuverType.ROUNDABOUT -> "roundabout" to null
            ManeuverType.EXIT_ROUNDABOUT -> "exit roundabout" to null
            ManeuverType.UNKNOWN -> "continue" to null
        }
        // A router marks a motorway exit as a fork/ramp carrying the junction's exit number;
        // the off-ramp phrase is the one that reads it ("Take exit 72B toward ..."), same as Google.
        val tt = if (exitNo != null && (t == "fork" || t == "ramp")) "off ramp" else t
        return app.vela.core.i18n.NavStringsRegistry.current().phrase(tt, mod, road, dest, exitNo, rbExit)
    }
}
