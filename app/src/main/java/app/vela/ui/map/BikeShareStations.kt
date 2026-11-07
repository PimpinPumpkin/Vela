package app.vela.ui.map

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.vela.R
import app.vela.core.data.transit.Transitous
import app.vela.core.model.BikeShareStatus
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.ui.AppVisibility
import app.vela.ui.BikeShareLayer
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Bike-share stations for the map (SPEC 8, Bike-share stations): while [BikeShareLayer] is on and
 * the map is at street zoom, the stations around the view come from Transitous and land in
 * [MapUiState.bikeStations]. The box around the view is asked again every [REFRESH_MS] while the
 * app is on screen, and an open station's sheet takes the new counts. Nothing is asked during navigation,
 * with "Use less data", offline, or with the app in the background.
 *
 * Only docked systems' stations are asked for: the docked provider groups around the box come
 * first ([Transitous.bikeShareGroupsInBox]), kept for [GROUPS_TTL_MS] over a box twice as wide
 * ([groupsBox]). With none, nothing more is asked and nothing is drawn. A failed lookup is never
 * kept as "none": that box is asked for with every operator's stations, and `isZone` filters them.
 *
 * Construct it ABOVE the view model's `init` and call [bind] from there: the collectors' first
 * pass runs inline.
 */
internal class BikeShareStations(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<MapUiState>,
    private val http: OkHttpClient,
    /** The wait after the view settles. A test passes 0. */
    private val settleMs: Long = SETTLE_MS,
    /** The docked systems in a box `[south, west, north, east]`; null on any failure. A test passes a fake. */
    private val groupsOf: suspend (DoubleArray) -> List<String>? = { b ->
        withContext(Dispatchers.IO) { runCatching { Transitous.bikeShareGroupsInBox(http, b[0], b[1], b[2], b[3]) }.getOrNull() }
    },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Asks for the stations in a box, of those systems only when given; null on any failure. A
     *  test passes a fake. */
    private val fetchBox: suspend (DoubleArray, List<String>?) -> List<Transitous.BikeStation>? = { b, g ->
        withContext(Dispatchers.IO) { runCatching { Transitous.bikeStationsInBox(http, b[0], b[1], b[2], b[3], g) }.getOrNull() }
    },
) {
    /** The last settled view, `[south, west, north, east, zoom]`. */
    private var view: DoubleArray? = null
    /** The padded box the shown stations came from, `[south, west, north, east]`. */
    private var box: DoubleArray? = null
    private val requests = ViewFetch(scope)
    /** When the shown stations were fetched: the wall clock for the sheet's age, the elapsed-time
     *  clock for the minute refresh, which a change of the phone's clock must not stall. */
    private var fetchedAt = 0L
    private var fetchedElapsed = -REFRESH_MS
    /** The docked systems last found, the box they were asked for and when. */
    private var keptGroups: List<String> = emptyList()
    private var keptGroupsBox: DoubleArray? = null
    private var keptGroupsAt = 0L

    fun bind() {
        // Turning the layer on (or off) acts at once, not at the next pan.
        scope.launch {
            snapshotFlow { BikeShareLayer.on.value }.collect {
                view?.let { v -> onViewport(v[0], v[1], v[2], v[3], v[4]) }
            }
        }
        // The minute refresh, only while it can be seen: layer on, no drive, app on screen.
        scope.launch {
            combine(
                snapshotFlow { BikeShareLayer.on.value },
                state.map { it.navigating }.distinctUntilChanged(),
                AppVisibility.foreground,
            ) { on, nav, fg -> on && !nav && fg }
                .distinctUntilChanged()
                .collectLatest { live ->
                    if (!live) return@collectLatest
                    while (true) {
                        val age = SystemClock.elapsedRealtime() - fetchedElapsed
                        if (age < REFRESH_MS) { delay(REFRESH_MS - age); continue }
                        if (box != null) fetch(settle = 0) // a request already out is left to land
                        delay(REFRESH_MS)
                    }
                }
        }
    }

    fun onViewport(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        view = doubleArrayOf(south, west, north, east, zoom)
        if (!BikeShareLayer.on.value || zoom < MIN_ZOOM) {
            box = null
            requests.cancel()
            if (state.value.bikeStations.isNotEmpty()) state.update { it.copy(bikeStations = emptyList()) }
            return
        }
        if (state.value.navigating) return // the map hides the stations during a drive
        box?.let { b -> if (covers(b, south, west, north, east)) return }
        fetch(settleMs)
    }

    /** The light place a tapped station opens: its counts, no Google lookup. */
    fun placeOf(station: Transitous.BikeStation): Place = Place(
        id = ID_PREFIX + station.key,
        name = station.name,
        location = LatLng(station.lat, station.lon),
        category = appContext.getString(R.string.place_bike_share_station),
        address = station.address,
        bikeShare = statusOf(station, fetchedAt),
    )

    /**
     * Asks for the box around the last view after [settle] ms. A request already out is never
     * canceled for a newer view ([ViewFetch]): central Paris took 732 ms after the 350 ms settle,
     * longer than the second between views while the map follows a moving fix. The view it no
     * longer covers is asked for when it lands.
     */
    private fun fetch(settle: Long) {
        // "Use less data" and offline keep what is drawn and ask for nothing.
        if (app.vela.core.data.LowDataMode.enabled || state.value.offline) return
        requests.ask(settle, send = send@{
            val v = view ?: return@send false
            val b = padded(v[0], v[1], v[2], v[3])
            val t0 = SystemClock.elapsedRealtime()
            val groups = groups(b)
            if (groups != null && groups.isEmpty()) {
                // No docked system here: nothing to draw and no stations to ask for.
                if (!BikeShareLayer.on.value || state.value.navigating) return@send false
                box = b
                fetchedAt = System.currentTimeMillis()
                fetchedElapsed = SystemClock.elapsedRealtime()
                Log.i(TAG, "bike share: no docked system in the box")
                state.update { s -> if (s.bikeStations.isEmpty()) s else s.copy(bikeStations = emptyList()) }
                return@send true
            }
            val got = fetchBox(b, groups)
            if (got == null) {
                // A failure is never kept as "no stations": the next pan or minute asks again.
                Log.i(TAG, "bike share: fetch failed after ${SystemClock.elapsedRealtime() - t0} ms")
                return@send false
            }
            if (!BikeShareLayer.on.value || state.value.navigating) return@send false
            val cLat = (b[0] + b[2]) / 2
            val cLng = (b[1] + b[3]) / 2
            val list = if (got.size <= MAX_STATIONS) got
                else got.sortedBy { (it.lat - cLat) * (it.lat - cLat) + (it.lon - cLng) * (it.lon - cLng) }.take(MAX_STATIONS)
            box = b
            fetchedAt = System.currentTimeMillis()
            fetchedElapsed = SystemClock.elapsedRealtime()
            Log.i(TAG, "bike share: ${list.size} stations (${got.size} in the box, ${groups?.size ?: "all"} systems) in ${SystemClock.elapsedRealtime() - t0} ms")
            val at = fetchedAt
            state.update { s ->
                val selected = refreshed(s.selected, list, at)
                if (list == s.bikeStations && selected === s.selected) s else s.copy(bikeStations = list, selected = selected)
            }
            true
        }, landed = {
            // The map moved on while the request was out: ask for where it is now.
            val b = box
            val nv = view
            if (b != null && nv != null && !covers(b, nv[0], nv[1], nv[2], nv[3])) onViewport(nv[0], nv[1], nv[2], nv[3], nv[4])
        })
    }

    /** The docked systems for box [b]: the kept ones while their box holds [b] and they are under
     *  [GROUPS_TTL_MS] old, else a new lookup over [groupsBox]. Null when the lookup failed. */
    private suspend fun groups(b: DoubleArray): List<String>? {
        val kb = keptGroupsBox
        if (kb != null && clock() - keptGroupsAt < GROUPS_TTL_MS && contains(kb, b)) return keptGroups
        val gb = groupsBox(b)
        val got = groupsOf(gb)
        if (got == null) {
            Log.i(TAG, "bike share: the systems lookup failed, asking for every operator's stations")
            return null
        }
        keptGroups = got
        keptGroupsBox = gb
        keptGroupsAt = clock()
        return got
    }

    companion object {
        /** How long the docked systems found around the view are kept. */
        const val GROUPS_TTL_MS = 10 * 60_000L

        /** The id prefix of a tapped station's place, `bikeshare:<providerId>/<station id>`. */
        const val ID_PREFIX = "bikeshare:"
        /** Stations are asked for and drawn from this zoom, the transit stops' fetch zoom. */
        const val MIN_ZOOM = 15.0
        /** How often the shown box is asked again while the map is on screen. */
        const val REFRESH_MS = 60_000L
        /** The wait after the view settles, so a fling asks once. */
        const val SETTLE_MS = 350L
        /** The most stations drawn, nearest the box's center first. */
        const val MAX_STATIONS = 500
        /** What a station's pin is drawn from. The place's own category is the translated label,
         *  and [PoiIcons.groupForCategory] reads English words: a translation that says "station"
         *  drew the bus pin. */
        const val PIN_CATEGORY = "Bike-share station"
        private const val TAG = "VelaTransit"

        // Bike words in the languages Transitous covers most. A category with one is never a
        // transit stop, though Google's "Bike sharing station" and "Estación de alquiler de
        // bicicletas" say "station". Compiled in: a published `transitExcludeWords` replaces the
        // compiled exclusions, and these must hold whatever it says.
        private val BIKE_CATEGORY = Regex("""bike|bicycl|\bbici|\bv[ée]lo|fahrrad|fiets|cykel|sykkel""", RegexOption.IGNORE_CASE)

        /** Whether a place category names bike share or bike rental. */
        internal fun isBikeCategory(category: String): Boolean = BIKE_CATEGORY.containsMatchIn(category)

        /** The view padded by half its size on each side. */
        internal fun padded(south: Double, west: Double, north: Double, east: Double): DoubleArray {
            val padLat = (north - south) * 0.5
            val padLng = (east - west) * 0.5
            return doubleArrayOf(south - padLat, west - padLng, north + padLat, east + padLng)
        }

        /** The box the docked systems are asked for: the stations' box padded by half on each side,
         *  so a pan within a box's width keeps them. */
        internal fun groupsBox(b: DoubleArray): DoubleArray = padded(b[0], b[1], b[2], b[3])

        /** Whether box [outer] holds box [inner], both `[south, west, north, east]`. */
        internal fun contains(outer: DoubleArray, inner: DoubleArray): Boolean =
            inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] <= outer[2] && inner[3] <= outer[3]

        /** Whether box [b] still serves the view: the view fits inside it and its center sits in the
         *  box's middle half. A zoom out past the box, or a pan out of the middle, asks again. */
        internal fun covers(b: DoubleArray, south: Double, west: Double, north: Double, east: Double): Boolean {
            if (south < b[0] || west < b[1] || north > b[2] || east > b[3]) return false
            val insLat = (b[2] - b[0]) * 0.25
            val insLng = (b[3] - b[1]) * 0.25
            val cLat = (south + north) / 2
            val cLng = (west + east) / 2
            return cLat in (b[0] + insLat)..(b[2] - insLat) && cLng in (b[1] + insLng)..(b[3] - insLng)
        }

        internal fun statusOf(st: Transitous.BikeStation, atMs: Long) =
            BikeShareStatus(bikes = st.bikes, docks = st.docks, renting = st.renting, returning = st.returning, atMs = atMs)

        /** The open place with the new counts when it is one of [stations]; else the place as it was. */
        internal fun refreshed(selected: Place?, stations: List<Transitous.BikeStation>, atMs: Long): Place? {
            if (selected == null || !selected.id.startsWith(ID_PREFIX)) return selected
            val key = selected.id.removePrefix(ID_PREFIX)
            val st = stations.firstOrNull { it.key == key } ?: return selected
            return selected.copy(bikeShare = statusOf(st, atMs))
        }
    }
}
