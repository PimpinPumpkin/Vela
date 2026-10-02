package app.vela.core.data.naming

import app.vela.core.data.RouteGeometry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.RouteSource
import app.vela.core.model.distanceTo

/**
 * Google's line with the best steps available for each part of it (2026-10-02).
 *
 * Google's route knows about closures and traffic; the open router's steps carry lanes, exit
 * numbers and sign text. Where the two routes run along the same road the open router's maneuvers
 * are kept as they are. Where Google's line leaves the open route (a [Stretch]) the turns come
 * from that piece of Google's line, named from the map tiles ([LineNamer]). Nothing is routed
 * through points, so the result cannot loop or double back, and it is Google's line exactly.
 */
object HybridRoute {
    /** A sample of Google's line this far from the open route is off it. */
    const val OFF_M = 45.0
    /** A shorter run off the open route is drawing noise (a ramp traced differently, the other
     *  side of a wide junction), not a different way. */
    const val MIN_RUN_M = 120.0
    /** Each stretch reaches this far into the shared road at both ends, so the turn off the
     *  shared road and the turn back onto it belong to the stretch. */
    const val PAD_M = 90.0
    private const val STEP_M = 20.0
    /** An open-router maneuver has to sit this close to Google's line to be carried over. */
    private const val SNAP_M = 40.0
    /** Two maneuvers this close together are the same junction; the open router's is kept. */
    private const val MERGE_M = 30.0

    /** Meters along Google's line, padded. */
    data class Stretch(val fromM: Double, val toM: Double)

    /** Where [google] leaves [open], as padded and merged stretches along [google]. Empty when
     *  the two are the same way. */
    fun stretches(google: List<LatLng>, open: List<LatLng>): List<Stretch> {
        if (google.size < 2 || open.size < 2) return emptyList()
        val grid = RouteGeometry.SegmentGrid(open, cumulative(open))
        val cum = cumulative(google)
        val total = cum.last()
        val runs = ArrayList<Stretch>()
        var start = -1.0
        var m = 0.0
        var i = 1
        while (m <= total) {
            while (i < cum.size - 1 && cum[i] < m) i++
            val seg = cum[i] - cum[i - 1]
            val f = if (seg <= 0.0) 0.0 else ((m - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
            val p = LatLng(google[i - 1].lat + (google[i].lat - google[i - 1].lat) * f, google[i - 1].lng + (google[i].lng - google[i - 1].lng) * f)
            val off = grid.along(p, OFF_M) == null
            if (off && start < 0) start = m
            if (!off && start >= 0) { if (m - start >= MIN_RUN_M) runs += Stretch(start, m); start = -1.0 }
            m += STEP_M
        }
        if (start >= 0 && total - start >= MIN_RUN_M) runs += Stretch(start, total)
        // Pad, clamp, merge what now touches.
        val out = ArrayList<Stretch>()
        for (r in runs) {
            val a = (r.fromM - PAD_M).coerceAtLeast(0.0); val b = (r.toM + PAD_M).coerceAtMost(total)
            val last = out.lastOrNull()
            if (last != null && a <= last.toM) out[out.lastIndex] = Stretch(last.fromM, maxOf(last.toM, b)) else out += Stretch(a, b)
        }
        return out
    }

    /** The piece of [line] between [fromM] and [toM] along it. */
    fun slice(line: List<LatLng>, fromM: Double, toM: Double): List<LatLng> {
        val cum = cumulative(line)
        val out = ArrayList<LatLng>()
        out += pointAt(line, cum, fromM)
        for (k in line.indices) if (cum[k] > fromM && cum[k] < toM) out += line[k]
        out += pointAt(line, cum, toM)
        return out
    }

    /**
     * [google] with maneuvers stitched from [open] (outside the stretches) and from [named]
     * (inside them; each list is LineNamer's maneuvers for that stretch's slice, DEPART and ARRIVE
     * included, in order). Null when the result would not start with a departure and end with an
     * arrival, which means a piece could not be placed.
     */
    fun stitch(google: Route, open: Route, named: List<Pair<Stretch, List<Maneuver>>>): Route? {
        val g = google.polyline
        if (g.size < 2 || named.isEmpty()) return null
        val cum = cumulative(g)
        val total = cum.last()
        val grid = RouteGeometry.SegmentGrid(g, cum)
        fun inStretch(a: Double) = named.any { (s, _) -> a >= s.fromM && a <= s.toM }

        data class At(val m: Double, val man: Maneuver, val fromOpen: Boolean)
        val all = ArrayList<At>()
        val openMs = open.maneuvers
        for ((k, man) in openMs.withIndex()) {
            val a = when {
                k == 0 && man.type == ManeuverType.DEPART -> 0.0
                k == openMs.lastIndex && man.type == ManeuverType.ARRIVE -> total
                else -> grid.along(man.location, SNAP_M) ?: continue
            }
            if (!inStretch(a)) all += At(a, man, true)
        }
        for ((s, ms) in named) {
            var pos = 0.0
            for (man in ms) {
                val a = s.fromM + pos
                pos += man.distanceMeters
                // The slice's own ends are real only where the slice starts or ends the trip.
                if (man.type == ManeuverType.DEPART && s.fromM > 0.0) continue
                if (man.type == ManeuverType.ARRIVE && s.toM < total) continue
                all += At(if (man.type == ManeuverType.ARRIVE) total else a, man, false)
            }
        }
        all.sortBy { it.m }
        val kept = ArrayList<At>()
        for (x in all) {
            val last = kept.lastOrNull()
            if (last != null && x.m - last.m < MERGE_M && x.man.type != ManeuverType.ARRIVE && last.man.type != ManeuverType.DEPART) {
                if (x.fromOpen && !last.fromOpen) kept[kept.lastIndex] = x
                continue
            }
            kept += x
        }
        if (kept.size < 2 || kept.first().man.type != ManeuverType.DEPART || kept.last().man.type != ManeuverType.ARRIVE) return null
        val dur = google.durationSeconds
        val out = kept.mapIndexed { k, x ->
            val len = if (k == kept.lastIndex) 0.0 else (kept[k + 1].m - x.m).coerceAtLeast(0.0)
            x.man.copy(
                location = if (x.man.type == ManeuverType.ARRIVE) g.last() else if (k == 0) g.first() else x.man.location,
                distanceMeters = len,
                durationSeconds = if (total > 0.0) dur * len / total else 0.0,
                // A name change the open router placed farther along its own leg than this leg now runs.
                renames = x.man.renames.filter { it.atMeters < len },
            )
        }
        return google.copy(
            legs = listOf(RouteLeg(google.distanceMeters, google.durationSeconds, google.durationInTrafficSeconds, out)),
            provisional = false,
            abbreviatedSteps = false,
            source = RouteSource.GOOGLE_HYBRID,
        )
    }

    private fun cumulative(line: List<LatLng>): DoubleArray {
        val cum = DoubleArray(line.size)
        for (i in 1 until line.size) cum[i] = cum[i - 1] + line[i - 1].distanceTo(line[i])
        return cum
    }

    private fun pointAt(line: List<LatLng>, cum: DoubleArray, m: Double): LatLng {
        if (m <= 0.0) return line.first()
        if (m >= cum.last()) return line.last()
        var i = 1
        while (i < cum.size - 1 && cum[i] < m) i++
        val seg = cum[i] - cum[i - 1]
        val f = if (seg <= 0.0) 0.0 else (m - cum[i - 1]) / seg
        return LatLng(line[i - 1].lat + (line[i].lat - line[i - 1].lat) * f, line[i - 1].lng + (line[i].lng - line[i - 1].lng) * f)
    }
}
