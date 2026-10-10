package app.vela.ui.place

import androidx.compose.runtime.mutableStateOf

/**
 * A trip opened from a directions link with a start or stops (`MapViewModel.openTripLink`), as the
 * endpoints card shows it. While the places are looked up the card lists every one by the name or
 * address the link carries, each with its own progress, instead of the destination alone; after,
 * it keeps a note naming any place that found nothing, so a stop never drops out of the trip
 * without a word.
 *
 * A holder rather than state passed down: the card is called from MapScreen, which is at its size
 * limits and takes no new parameter. The view model is the only writer.
 */
internal object LinkTrip {
    enum class Lookup { LOOKING, FOUND, NOT_FOUND }

    data class Row(val label: String, val lookup: Lookup = Lookup.LOOKING)

    data class View(
        /** Null when the trip starts where you are. */
        val origin: Row?,
        val stops: List<Row>,
        val destination: Row,
        /** True until every place has answered. */
        val resolving: Boolean = true,
    ) {
        /** The places that found nothing, in trip order. */
        val notFound: List<String>
            get() = (listOfNotNull(origin) + stops + destination).filter { it.lookup == Lookup.NOT_FOUND }.map { it.label }

        /** The stops that found nothing, each with its place among the link's stops. */
        val missingStops: List<Pair<Int, String>>
            get() = stops.withIndex().filter { it.value.lookup == Lookup.NOT_FOUND }.map { it.index to it.value.label }

        /** True when the start the link named found nothing, so the trip starts where you are. */
        val missingStart: String? get() = origin?.takeIf { it.lookup == Lookup.NOT_FOUND }?.label

        /**
         * What the not-found dialog asks about after [after] (null = from the top): the missing
         * stops in trip order, then the start ([START]), then nothing. One place at a time, and a
         * place already asked about is not asked again.
         */
        fun nextMissing(after: Int? = null): Int? {
            if (after == START) return null
            val from = after ?: -1
            return missingStops.firstOrNull { it.first > from }?.first ?: START.takeIf { missingStart != null }
        }
    }

    /** [asking]'s value for the trip's start. */
    const val START = -1

    /** The place the not-found dialog is asking about: a place among the link's stops, [START],
     *  or null when the dialog is closed. */
    val asking = mutableStateOf<Int?>(null)

    /** Choose the trip's start, set by the view model (the dialog's button for a missing start). */
    var pickStart: () -> Unit = {}

    /** "Start where I am" on a start that found nothing: the trip starts here by choice now, so
     *  the start is no longer a missing place. */
    fun startHere() {
        val v = view.value ?: return
        val now = v.copy(origin = null)
        view.value = now.takeIf { it.notFound.isNotEmpty() }
        asking.value = null
    }

    val view = mutableStateOf<View?>(null)

    /** Search for the stop at this place among the link's stops, set by the view model: the
     *  card's "Search for it" calls it, and the pick goes back into the trip where the link had it. */
    var find: (Int) -> Unit = {}

    /** Where a stop found later goes among the trip's stops: after every stop the link listed
     *  before it. [slots] are the link positions of the stops already in the trip, in trip order. */
    fun insertAt(slots: List<Int>, slot: Int): Int = slots.count { it < slot }
}
