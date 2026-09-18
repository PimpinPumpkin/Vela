package app.vela.ui.map

import app.vela.core.data.google.ambientProminence
import app.vela.core.model.Place

/**
 * Holds the ambient POI layer STILL while you look at it.
 *
 * The browse map's icons are ranked by [ambientProminence] (a place's review count and rating):
 * the ranking decides which places survive the on-screen cap, which one wins a shared label slot
 * when icons collide, and how big each icon and label draws. All of that is recomputed on every
 * paint, and a settled view gets painted several times: the fan-out streams its pool as category
 * terms land, the twin-dedupe re-runs a couple of seconds later, and a cold session re-runs the
 * WHOLE fan-out once because Google strips review counts for the first seconds of a session
 * (the slim-flavor heal). Each of those can arrive with a different review count for the same
 * place, so the map re-ranked under a user who had not moved: labels traded places, icons grew and
 * shrank, and a sushi counter took the label off the supermarket it sits inside, twenty seconds
 * after everything already looked right (user 2026-09-18).
 *
 * So: the first RICH paint of a view fixes each place's prominence, and later paints of the same
 * view reuse it. Refinements still ADD places (a term that lands late is new data, not a
 * re-ranking) and they still correct anything the map has not drawn yet; they just cannot reorder
 * or resize what you are already looking at. Moving the map is what forgets it - [reset] is called
 * on the same pan/zoom gate that re-queries.
 *
 * RICH matters: a cold session's first pool can come back with no review counts at all, which
 * flattens every prominence to zero. Freezing THAT would pin the flat ranking the heal exists to
 * fix, so a pool with nothing but zeros is never remembered.
 */
object AmbientStability {

    private val sticky = HashMap<String, Double>()

    /** Stable identity for a place across refetches: Google's feature id, else name + rounded point
     *  (the same identity the ambient pool's own de-duplication uses). */
    fun keyOf(p: Place): String =
        p.featureId ?: "${p.name}@${(p.location.lat * 1e4).toInt()},${(p.location.lng * 1e4).toInt()}"

    /** The prominence to rank, cap and draw [p] with: what it was first painted with in this view,
     *  or its own value when the map has not drawn it yet. */
    fun prominenceOf(p: Place): Double = sticky[keyOf(p)] ?: ambientProminence(p)

    /** Remember what is on screen now, so the next paint of the same view cannot re-rank it.
     *  First paint wins per place; a pool with no prominence at all (see RICH above) is ignored. */
    fun remember(places: List<Place>) {
        if (places.isEmpty()) return
        val rich = places.any { ambientProminence(it) > 0.0 }
        if (!rich) return
        for (p in places) sticky.getOrPut(keyOf(p)) { ambientProminence(p) }
    }

    /** New view: the next paint ranks from scratch. */
    fun reset() = sticky.clear()
}
