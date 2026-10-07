package app.vela.ui.map

import androidx.compose.runtime.mutableIntStateOf
import app.vela.core.model.LatLng
import app.vela.core.model.TransitStep
import java.util.IdentityHashMap

/**
 * Real paths for the ride legs of the transit trip being previewed, looked up after the trip is on
 * screen (issue #677). Google's itineraries carry stops and no track, so the map joined the stops
 * with straight lines that fly over rivers and blocks; the view model asks the open transit
 * planner for each ride's own path and puts it here, and the map redraws when [version] moves.
 *
 * A holder keyed by the step object, not a field on the itinerary: the chooser's rows and the
 * preview are tied to the itinerary's identity, and swapping in a copy would collapse the row.
 */
object TransitShapes {
    val version = mutableIntStateOf(0)
    private val paths = IdentityHashMap<TransitStep, List<LatLng>>()

    fun of(step: TransitStep): List<LatLng>? = step.path ?: synchronized(paths) { paths[step] }

    fun put(step: TransitStep, path: List<LatLng>) {
        synchronized(paths) { paths[step] = path }
        version.intValue++
    }

    fun clear() {
        synchronized(paths) { if (paths.isEmpty()) return; paths.clear() }
        version.intValue++
    }
}
