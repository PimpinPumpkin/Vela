package app.vela.ui.map

import androidx.compose.runtime.mutableIntStateOf
import app.vela.core.model.BikeShareStatus
import app.vela.core.model.TransitRental
import app.vela.core.model.TransitStopTime
import java.util.Locale

/**
 * The free bikes and docks at the stations of the bike-share trips on screen (SPEC 8, Bike share
 * in directions), by provider and station name, since a plan's station has no id. Filled once per
 * search by [BikeShareTripSearch.counts]; the rows redraw when [version] moves. A new search
 * empties it first, so a count from an older search never shows beside a new trip.
 */
object BikeShareCounts {
    val version = mutableIntStateOf(0)
    private val counts = HashMap<String, BikeShareStatus>()

    /** Two bikes or docks left is few: either can be gone by the time you get there. */
    const val FEW = 2

    fun of(rental: TransitRental, stop: TransitStopTime): BikeShareStatus? =
        synchronized(counts) { counts[keyOf(rental.providerId, stop.name)] }

    fun replace(next: Map<String, BikeShareStatus>) {
        synchronized(counts) {
            if (counts.isEmpty() && next.isEmpty()) return
            counts.clear()
            counts.putAll(next)
        }
        version.intValue++
    }

    internal fun keyOf(providerId: String, stationName: String): String =
        "$providerId|${stationName.trim().lowercase(Locale.ROOT)}"
}
