package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route

/**
 * The model behind the route bar (issue #228) - a strip showing the road AHEAD of you at a glance:
 * where the traffic is, and what is coming up on it.
 *
 * TomTom's version of this also carries live hazards. Vela's cannot: every keyless live-incident
 * source has been probed and is dead (Google serves binary vector tiles, Waze is reCAPTCHA-gated).
 * So this shows only what Vela genuinely knows - the congestion spans Google gives us for the
 * route, and the static road furniture already fetched along the corridor for the map. Showing a
 * confident bar with nothing real behind it would be worse than not having one.
 *
 * Pure and unit-tested on purpose: it decides positions, which is the part that can be wrong in
 * ways a screenshot will not reveal.
 */
object RouteBar {

    /** What a mark on the bar represents. The kinds are exactly the ones Vela already draws on the
     *  map, so the bar can never claim knowledge the map does not have. */
    /** [priority]: which member of a merged cluster is drawn (higher wins); badges beat dots. */
    enum class Mark(val priority: Int) { SIGNAL(0), STOP(1), SPEED_HUMP(2), RAIL_CROSSING(3), CAMERA(4) }

    /** A congestion band, as a fraction of the REMAINING trip: 0 = where you are, 1 = destination.
     *  [level] is Google's grade (1 moderate, 2 heavy, 3+ severe). */
    data class Band(val level: Int, val from: Double, val to: Double)

    /** One mark placed on the bar, again as a fraction of the remaining trip. */
    data class Pin(val kind: Mark, val at: Double)

    data class Model(
        val bands: List<Band>,
        val pins: List<Pin>,
        /** Metres of road the bar's full height represents (the window, not the whole trip). */
        val spanM: Double,
        /** True when the window reaches the end of the route, so the top of the bar is the
         *  destination and can be capped as such rather than implying more road. */
        val reachesDestination: Boolean,
    ) {
        val isEmpty: Boolean get() = bands.isEmpty() && pins.isEmpty()
    }

    /** Marks closer together than this along the route collapse into one, so a junction with a
     *  light and a stop line does not stack two glyphs on the same pixel. */
    private const val PIN_MERGE_M = 60.0

    /** Below this the bar would be a sliver of noise; the banner already covers the last stretch. */
    private const val MIN_REMAINING_M = 400.0

    /**
     * How much road the bar shows: the next 5 km, not the whole trip.
     *
     * Scaling the strip to the entire remaining route was the first cut and it is useless on any
     * long drive - on a 700 mile trip everything within the next few miles lands inside one pixel
     * at the bottom and the bar reads as a plain grey stick (device-checked, that is exactly what
     * it looked like). TomTom's bar has the same property and solves it the same way: show the
     * near road at a scale you can actually read. When less than this is left, the bar covers the
     * rest of the trip and its top IS the destination.
     */
    const val WINDOW_M = 5_000.0

    /** Cumulative distance to each vertex; see [RouteProjection.cumulative]. Kept as a name so the
     *  bar's callers read as one unit, but the maths lives in one place. */
    fun cumulative(poly: List<LatLng>): DoubleArray = RouteProjection.cumulative(poly)

    /** How far along the route [p] sits, or null when it is off it; see [RouteProjection.alongMeters]. */
    fun alongMeters(
        poly: List<LatLng>,
        cum: DoubleArray,
        p: LatLng,
        maxOffRouteM: Double = 40.0,
    ): Double? = RouteProjection.alongMeters(poly, cum, p, maxOffRouteM)

    /**
     * Build the bar for [route] given how far along it you are.
     *
     * [markMeters] is each mark's own distance along the route (from [alongMeters]); anything
     * already behind you, or past the destination, is dropped rather than clamped to an end - a
     * pin pinned at your own position reads as "right here" and would be a lie.
     */
    fun build(
        route: Route,
        traveledM: Double,
        markMeters: List<Pair<Mark, Double>> = emptyList(),
        windowM: Double = WINDOW_M,
        totalM: Double? = null,
    ): Model {
        // The polyline's own length when the caller has it: traveledM and the marks are both
        // measured along the polyline, while Route.distanceMeters is the router's figure, and a
        // few percent between them shifted bands against pins and stood the bar down early.
        val total = totalM?.takeIf { it > 0.0 } ?: route.distanceMeters
        val done = traveledM.coerceIn(0.0, total)
        val remaining = total - done
        if (remaining < MIN_REMAINING_M) return Model(emptyList(), emptyList(), remaining, true)

        // The bar covers the next [windowM] of road, or the rest of the trip when that is nearer.
        val end = minOf(total, done + windowM)
        val span = end - done
        val reaches = end >= total - 1.0

        fun frac(m: Double) = ((m - done) / span).coerceIn(0.0, 1.0)

        val bands = route.trafficSpans
            .mapNotNull { s ->
                val sEnd = s.startMeters + s.lengthMeters
                // Keep a span overlapping the WINDOW, trimmed to it.
                if (sEnd <= done || s.startMeters >= end) null
                else Band(s.level, frac(maxOf(s.startMeters, done)), frac(minOf(sEnd, end)))
            }
            .filter { it.to > it.from }

        val pins = markMeters
            .filter { (_, m) -> m > done && m <= end }
            .sortedBy { it.second }
            .fold(mutableListOf<Pair<Mark, Double>>()) { acc, item ->
                // Collapse a cluster to ONE member: the point is "something is coming up here",
                // and two glyphs a pixel apart carry no more information than one. The kept
                // member is the one that says the most: a camera or a crossing (a badge) over a
                // light or a stop sign (a dot). ALPR cameras are mounted at signalled junctions,
                // so keeping the first member by distance hid the camera behind the light's dot.
                if (acc.isEmpty() || item.second - acc.last().second >= PIN_MERGE_M) acc += item
                else if (item.first.priority > acc.last().first.priority) acc[acc.lastIndex] = item
                acc
            }
            .map { (kind, m) -> Pin(kind, frac(m)) }

        return Model(bands, pins, span, reaches)
    }
}
