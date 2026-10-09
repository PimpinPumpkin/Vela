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
    }

    val view = mutableStateOf<View?>(null)
}
