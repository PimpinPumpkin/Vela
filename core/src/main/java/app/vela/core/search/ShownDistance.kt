package app.vela.core.search

import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo

/**
 * The distance a result card and a place sheet show (issue #916). Google measures it from you
 * wherever the map has been moved to. A search measures from its own ranking point (`rankBias`,
 * or the view's center), so while the phone has a live fix every place that carries a distance is
 * measured again from the fix. Without one (no fix yet, location off, or the dot gone gray) the
 * place keeps the distance it came with. Only the number changes: the order a search ranked its
 * results in is left as it is.
 */
object ShownDistance {
    /** The point to measure from: [myLocation] while it is live, else null, which keeps each
     *  place's own distance. A point within half a degree of 0,0 is the map's untouched camera or
     *  a bogus fix, never the user. */
    fun from(myLocation: LatLng?, stale: Boolean): LatLng? =
        myLocation?.takeUnless { stale || (kotlin.math.abs(it.lat) < 0.5 && kotlin.math.abs(it.lng) < 0.5) }

    /** [p] measured from [from]. A place with no distance (a list member, a planned route's row)
     *  is left without one. */
    fun of(p: Place, from: LatLng?): Place =
        if (from == null || p.distanceMeters == null) p else p.copy(distanceMeters = from.distanceTo(p.location))

    fun of(places: List<Place>, from: LatLng?): List<Place> =
        if (from == null) places else places.map { of(it, from) }
}
