package app.carto.core.data.google.parse

import app.carto.core.data.CalibrationNeededException
import app.carto.core.data.google.PolylineCodec
import app.carto.core.data.google.findString
import app.carto.core.model.LatLng
import app.carto.core.model.Maneuver
import app.carto.core.model.ManeuverType
import app.carto.core.model.Route
import app.carto.core.model.RouteLeg
import app.carto.core.model.distanceTo
import kotlinx.serialization.json.JsonElement

/**
 * Parses the directions response. The geometry is a standard encoded polyline,
 * which we can find structurally and decode with zero calibration. The
 * durations — crucially `duration_in_traffic` — and the per-step maneuver list
 * are positional and DO need calibration; until then we render a single-leg
 * route with geometric distance and a nominal speed so the map/preview works,
 * and leave the traffic ETA null (honestly "unknown") rather than faking it.
 */
object DirectionsParser {

    fun parse(root: JsonElement): List<Route> {
        val encoded = root.findString { looksLikePolyline(it) }
            ?: throw CalibrationNeededException("route polyline")
        val poly = PolylineCodec.decode(encoded)
        if (poly.size < 2) throw CalibrationNeededException("route polyline decoded empty")

        // CALIBRATE: real per-leg distance/duration and duration_in_traffic.
        val dist = pathLength(poly)
        val dur = dist / 13.4 // ~30 mph placeholder until durations are pinned
        val maneuvers = listOf(
            Maneuver(ManeuverType.DEPART, "Start", poly.first(), dist, dur),
            Maneuver(ManeuverType.ARRIVE, "Arrive at your destination", poly.last(), 0.0, 0.0),
        )
        return listOf(
            Route(
                polyline = poly,
                legs = listOf(RouteLeg(dist, dur, durationInTrafficSeconds = null, maneuvers = maneuvers)),
                distanceMeters = dist,
                durationSeconds = dur,
                durationInTrafficSeconds = null,
            ),
        )
    }

    /** Encoded-polyline chars are printable ASCII in the 63..126 band. */
    private fun looksLikePolyline(s: String): Boolean =
        s.length > 20 && s.all { it.code in 63..126 } && s.any { it.code > 92 }

    private fun pathLength(p: List<LatLng>): Double {
        var d = 0.0
        for (i in 1 until p.size) d += p[i - 1].distanceTo(p[i])
        return d
    }
}
