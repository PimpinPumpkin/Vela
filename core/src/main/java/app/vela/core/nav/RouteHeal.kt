package app.vela.core.nav

import app.vela.core.model.Route

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

    /**
     * What [candidate] would put right in [current], as words for the trip note ("steps",
     * "names", "traffic", joined by "+"), or null when it brings nothing or would take any of
     * the three away. The caller has already found the two on the same course.
     */
    fun gains(current: Route, candidate: Route): String? {
        val steps = !current.hasRealSteps && candidate.hasRealSteps
        val names = current.namesShort && !candidate.namesShort && candidate.hasRealSteps
        val traffic = !current.hasLiveTraffic && candidate.hasLiveTraffic
        // Abbreviated steps are a fraction of the turns: every turn with some of them bare is
        // still the better list, so names only count as lost against real steps.
        val loses = (current.hasRealSteps && !candidate.hasRealSteps) ||
            (current.hasLiveTraffic && !candidate.hasLiveTraffic) ||
            (current.hasRealSteps && !current.namesShort && candidate.namesShort)
        if (loses || !(steps || names || traffic)) return null
        return listOfNotNull("steps".takeIf { steps }, "names".takeIf { names }, "traffic".takeIf { traffic }).joinToString("+")
    }
}
