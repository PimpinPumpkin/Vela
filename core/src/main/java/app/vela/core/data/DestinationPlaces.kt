package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo

/**
 * The Google place a route's destination point stands for, when it is one. Google's own page asks
 * for a place by its text and feature id, and the route then ends at the place's own entrance point.
 * Asked for the bare point, it ends on the road nearest the pin, which for a large store can be the
 * road behind the building. Kept by point, so a reroute or a recheck to the same point (NavSession
 * holds only the point) asks for the place too.
 */
object DestinationPlaces {
    data class Ref(val text: String, val featureId: String)

    /** A point within this of a remembered one is that place. */
    private const val SAME_POINT_M = 2.0
    private const val MAX_KEPT = 16

    private val refs = ArrayList<Pair<LatLng, Ref>>()

    @Synchronized fun remember(at: LatLng, text: String, featureId: String) {
        if (text.isBlank() || featureId.isBlank()) return
        refs.removeAll { it.first.distanceTo(at) <= SAME_POINT_M }
        refs += at to Ref(text, featureId)
        while (refs.size > MAX_KEPT) refs.removeAt(0)
    }

    @Synchronized fun at(p: LatLng): Ref? = refs.lastOrNull { it.first.distanceTo(p) <= SAME_POINT_M }?.second

    @Synchronized fun clear() = refs.clear()

    /** Google's end of a place route this far from the pin or less is the place's entrance. */
    const val END_MAX_M = 400.0

    /**
     * Where an open-router route that follows Google's [line] to [destination] must end: Google's
     * own last point when [destination] was asked for as a place, else [destination]. The snap
     * that names Google's course ran its last leg to the pin, so a route Google ended at the front
     * of a large store ended on the road behind it.
     */
    fun routeEnd(line: List<LatLng>?, destination: LatLng): LatLng {
        if (at(destination) == null) return destination
        val end = line?.lastOrNull() ?: return destination
        return if (end.distanceTo(destination) <= END_MAX_M) end else destination
    }
}
