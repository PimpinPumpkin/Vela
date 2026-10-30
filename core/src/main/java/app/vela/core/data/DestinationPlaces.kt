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
}
