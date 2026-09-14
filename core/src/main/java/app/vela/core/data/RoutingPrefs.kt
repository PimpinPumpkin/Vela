package app.vela.core.data

/**
 * Routing preferences the app sets and the engine reads. Kept as a plain holder rather than
 * threaded through every directions() caller (the chooser, the nav session's reroutes and
 * rechecks, the car app) because a preference is a fact about the user, not about one request.
 */
object RoutingPrefs {
    /** Bike mode routes for safety over speed (issue #401): the offline engine's bicycle profile
     *  where a region is downloaded, else the open Valhalla router told to stay off busy roads.
     *  Off = the plain fastest bike route from OSRM. Default on, like Google's bike routing. */
    @Volatile var bikeSafe: Boolean = true
}
