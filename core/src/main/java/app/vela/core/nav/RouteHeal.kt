package app.vela.core.nav

import app.vela.core.model.Route
import app.vela.core.model.RouteSource

/**
 * When a drive's route is short of something a later answer can supply, and when a re-checked
 * route on the same course may quietly take its place. Three things can be short: the steps
 * (Google's abbreviated ones, adopted while the open router was down), the street names (a
 * stretch named from its bends alone because the naming services did not answer in time,
 * [Route.namesShort]) and live traffic.
 */
object RouteHeal {
    /** [r] is short of steps, names or traffic: the drive re-checks it on the fast interval. */
    fun degraded(r: Route): Boolean = !r.hasRealSteps || !r.hasLiveTraffic || r.namesShort

    /** [r] is not Google's line: the open router's own route or an on-device one. A drive takes
     *  one only when every Google route turned the car round or Google was out, and goes back to
     *  Google's line once a re-check brings it (the "line" gain). */
    fun offGoogleLine(r: Route): Boolean =
        r.source == RouteSource.OSRM || r.source == RouteSource.OBF || r.source == RouteSource.GRAPHHOPPER

    /**
     * What [candidate] would put right in [current], as words for the trip note ("steps",
     * "names", "traffic", "line", joined by "+"), or null when it brings nothing or would take
     * any of the three away. The caller has already found the two on the same course. [lineOk]:
     * the caller drives a car and found that [candidate] does not turn it round, so Google's line
     * may replace a route that is not (a reroute that took the open router's route, because every
     * Google route turned the car round, kept the open router's way into the destination, a
     * street off, to the end of a real drive on 2026-11-17).
     */
    fun gains(current: Route, candidate: Route, lineOk: Boolean = false): String? {
        val steps = !current.hasRealSteps && candidate.hasRealSteps
        // Names only from a route that is Google's line: a re-check that could not build the
        // hybrid answers with the open router's own route, which runs through whatever Google
        // went around, and the same-course test samples too few points to see a short detour.
        val googleLine = candidate.source == RouteSource.GOOGLE_HYBRID || candidate.source == RouteSource.GOOGLE_LINE_NAMED
        val names = current.namesShort && !candidate.namesShort && candidate.hasRealSteps && googleLine
        val traffic = !current.hasLiveTraffic && candidate.hasLiveTraffic
        val line = lineOk && googleLine && offGoogleLine(current) && candidate.hasRealSteps && !candidate.namesShort
        // Abbreviated steps are a fraction of the turns: every turn with some of them bare is
        // still the better list, so names only count as lost against real steps.
        val loses = (current.hasRealSteps && !candidate.hasRealSteps) ||
            (current.hasLiveTraffic && !candidate.hasLiveTraffic) ||
            (current.hasRealSteps && !current.namesShort && candidate.namesShort)
        if (loses || !(steps || names || traffic || line)) return null
        return listOfNotNull("steps".takeIf { steps }, "names".takeIf { names }, "traffic".takeIf { traffic }, "line".takeIf { line }).joinToString("+")
    }
}
