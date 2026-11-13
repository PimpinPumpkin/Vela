package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TollNote

/**
 * Showing a toll road as a choice. Google's keyless reply says nothing about tolls, but its avoid
 * flag does: asked to avoid tolls, it gives a different route only when the fastest one uses a
 * toll road. So the chooser asks twice, once as usual and once with tolls avoided, and compares.
 *
 * - The avoiding route is the same way as the fastest: the fastest has no toll. Nothing is shown.
 * - It differs: the fastest probably has a toll ([TollNote.MAY_HAVE_TOLLS]) and the avoiding route
 *   has none ([TollNote.NO_TOLLS]). It joins the list if it is not already one of the routes.
 *
 * The rows already carry each route's time and distance, so the comparison needs no figures of its
 * own. Pure, so the cases are unit-tested.
 */
object TollCompare {
    /** [tolledIdx] is the fastest route, which probably has tolls. [avoidingIdx] is the row that is
     *  the avoiding route, or null when it is not on offer yet and has to be added. */
    data class Plan(val tolledIdx: Int, val avoidingIdx: Int?)

    /**
     * What to say about [routes], given [avoiding], the first route Google gave with tolls avoided.
     * [sameWay] says whether the first line runs along the second (SavedRoutes.sameWay). Null when
     * there is nothing to say: no routes, an avoiding route that could not honor the avoid, or a
     * fastest route that already avoids tolls.
     */
    fun plan(routes: List<Route>, avoiding: Route, sameWay: (List<LatLng>, List<LatLng>) -> Boolean): Plan? {
        if (routes.isEmpty() || avoiding.avoidNotHonored || avoiding.polyline.size < 2) return null
        fun same(a: Route) = sameWay(a.polyline, avoiding.polyline) && sameWay(avoiding.polyline, a.polyline)
        val fastest = routes.indices.minByOrNull { routes[it].let { r -> r.durationInTrafficSeconds ?: r.durationSeconds } } ?: return null
        if (same(routes[fastest])) return null
        return Plan(fastest, routes.indices.firstOrNull { same(routes[it]) })
    }

    /** [routes] with the tolls noted by [plan]. The avoiding route is added at the END when it was
     *  not on offer: other lists index into this one by position. */
    fun apply(routes: List<Route>, plan: Plan, avoiding: Route): List<Route> {
        val tagged = routes.mapIndexed { i, r ->
            when (i) {
                plan.avoidingIdx -> r.copy(tolls = TollNote.NO_TOLLS)
                plan.tolledIdx -> r.copy(tolls = TollNote.MAY_HAVE_TOLLS)
                else -> r
            }
        }
        return if (plan.avoidingIdx == null) tagged + avoiding.copy(tolls = TollNote.NO_TOLLS) else tagged
    }
}
