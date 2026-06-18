package app.vela.ui.map

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vela.core.config.CalibrationStore
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.MapDataSource
import app.vela.core.data.OfflinePoiStore
import app.vela.core.data.RouteCorridor
import app.vela.core.data.OverpassPois
import app.vela.core.data.RecentSearchStore
import app.vela.core.data.SavedPlaceStore
import app.vela.core.data.tiles.MapStyle
import app.vela.core.location.LocationProvider
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Review
import app.vela.core.model.Route
import app.vela.core.model.SavedPlace
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavSession
import app.vela.core.nav.NavState
import app.vela.core.voice.VoiceEngine
import app.vela.core.voice.VoiceGuide
import app.vela.service.NavigationService
import app.vela.core.model.TransitItinerary
import app.vela.web.WebDirectionsFetcher
import app.vela.web.WebPhotoFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val center: LatLng? = null,
    val myLocation: LatLng? = null,
    val myBearing: Float? = null,
    val query: String = "",
    val results: List<Place> = emptyList(),
    val suggestions: List<Place> = emptyList(),
    val selected: Place? = null,
    val reviews: List<Review> = emptyList(),
    val reviewsLoading: Boolean = false,
    val routes: List<Route> = emptyList(),
    val activeRoute: Route? = null,
    val directionsOpen: Boolean = false,
    val travelMode: TravelMode = TravelMode.DRIVE,
    val transit: List<TransitItinerary> = emptyList(),
    val transitLoading: Boolean = false,
    val navigating: Boolean = false,
    val navCameraDetached: Boolean = false,
    val voiceMuted: Boolean = false,
    val arrived: Boolean = false,
    val nav: NavState = NavState(),
    val maneuverText: String = "",
    val fasterRoute: Route? = null,
    val fasterSavingSeconds: Double = 0.0,
    val arrivedLabel: String = "",
    val arrivedDistanceMeters: Double = 0.0,
    val arrivedSeconds: Double = 0.0,
    val status: String? = null,
    val showPsdsTip: Boolean = false,
    val showSearchThisArea: Boolean = false,
    val showSteps: Boolean = false,
    val previewStepIndex: Int? = null,
    val styleUri: String = MapStyle.DEFAULT.uri,
    val styleName: String = MapStyle.DEFAULT.label,
    val selectedEngine: VoiceEngine? = null,
    val searching: Boolean = false,
    val resultsCollapsed: Boolean = false,
    val recents: List<String> = emptyList(),
    val saved: List<SavedPlace> = emptyList(),
)

/**
 * State holder for the map experience. Nav itself lives in the shared
 * [NavSession] (driven by the foreground service so it survives backgrounding);
 * this VM just starts/stops it and mirrors its state for the UI.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dataSource: MapDataSource,
    private val locationProvider: LocationProvider,
    private val voice: VoiceGuide,
    private val navSession: NavSession,
    private val recentStore: RecentSearchStore,
    private val savedStore: SavedPlaceStore,
    private val calibration: CalibrationStore,
    private val offlinePoiStore: OfflinePoiStore,
    private val webPhotos: WebPhotoFetcher,
    private val webDirections: WebDirectionsFetcher,
    private val http: okhttp3.OkHttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var destination: LatLng? = null
    private var mapCenter: LatLng? = null
    private var locationJob: Job? = null

    init {
        val seed = locationProvider.lastKnown()
        _state.update { it.copy(center = seed, myLocation = it.myLocation ?: seed) }
        voice.init() // warm TTS so the engine list is ready in Settings
        _state.update { it.copy(recents = recentStore.recent(), saved = savedStore.saved()) }
        // Pull the latest scraper calibration from the repo (non-blocking, once).
        viewModelScope.launch { runCatching { calibration.refresh() } }

        viewModelScope.launch {
            navSession.state.collect { ns ->
                _state.update {
                    it.copy(
                        navigating = ns.navigating,
                        arrived = ns.arrived,
                        nav = ns.nav,
                        maneuverText = ns.maneuverText,
                        activeRoute = if (ns.navigating && ns.route != null) ns.route else it.activeRoute,
                        fasterRoute = ns.fasterRoute,
                        fasterSavingSeconds = ns.fasterSavingSeconds,
                        arrivedLabel = ns.destinationLabel,
                        arrivedDistanceMeters = ns.tripDistanceMeters,
                        arrivedSeconds = ns.tripElapsedSeconds,
                    )
                }
            }
        }
    }

    fun startLocation() {
        if (locationJob != null) return
        locationJob = viewModelScope.launch {
            launch {
                delay(8_000)
                if (_state.value.myLocation == null) _state.update { it.copy(showPsdsTip = true) }
            }
            locationProvider.updates().collect { loc ->
                val here = LatLng(loc.latitude, loc.longitude)
                // Keep the last good bearing while stopped (GPS bearing is noise at rest).
                val bearing = if (loc.hasBearing() && loc.speed > 0.5f) loc.bearing else _state.value.myBearing
                _state.update {
                    it.copy(myLocation = here, myBearing = bearing, showPsdsTip = false, center = it.center ?: here)
                }
            }
        }
    }

    private var suggestJob: Job? = null

    /** As the user types, fetch live place suggestions (debounced) so the search
     *  page shows real matches — name + address — to tap, like Google's
     *  autocomplete. Reuses the calibrated search endpoint (no separate suggest
     *  RPC); best-effort, and a stale response is dropped if the query moved on. */
    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        suggestJob?.cancel()
        val term = q.trim()
        if (term.length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(320) // only fire once typing pauses
            val near = _state.value.myLocation ?: mapCenter
            val res = runCatching { dataSource.search(term, near).places }.getOrDefault(emptyList())
            if (_state.value.query.trim() == term) { // ignore if the query changed meanwhile
                _state.update { it.copy(suggestions = res.take(6)) }
            }
        }
    }

    /** The X in the search bar: wipe the query, results and selection. */
    fun clearSearch() {
        suggestJob?.cancel()
        _state.update {
            it.copy(
                query = "", results = emptyList(), suggestions = emptyList(), selected = null,
                resultsCollapsed = false, showSearchThisArea = false,
            )
        }
    }

    /** Hide the results list (swipe-up / back) to browse the map; pins stay. */
    fun collapseResults() = _state.update { it.copy(resultsCollapsed = true) }

    fun expandResults() = _state.update { it.copy(resultsCollapsed = false) }

    fun searchRecent(q: String) {
        onQueryChange(q)
        search()
    }

    fun clearRecents() {
        recentStore.clear()
        _state.update { it.copy(recents = emptyList()) }
    }

    fun toggleSave() {
        val p = _state.value.selected ?: return
        savedStore.toggle(SavedPlace.of(p))
        _state.update { it.copy(saved = savedStore.saved()) }
    }

    fun selectSaved(sp: SavedPlace) {
        val base = Place(id = sp.id, name = sp.name, location = sp.location)
        _state.update { it.copy(selected = base, center = base.location, reviews = emptyList(), reviewsLoading = false) }
        // A saved place has no feature id, so it used to open with no photos/reviews.
        // Enrich it via a search (like a POI tap) to pull them; keep the saved id so
        // the star stays filled.
        viewModelScope.launch {
            val full = runCatching {
                dataSource.search(sp.name, sp.location).places.minByOrNull { it.location.distanceTo(sp.location) }
            }.getOrNull()
            if (full != null && _state.value.selected?.id == sp.id) {
                val enriched = full.copy(id = sp.id)
                _state.update { it.copy(selected = enriched) }
                fetchReviews(enriched)
                fetchPhotos(enriched)
            }
        }
    }

    fun search() = runSearch(_state.value.query.trim(), _state.value.myLocation)

    /** Re-run the current query biased to the area the user has panned to. */
    fun searchThisArea() = runSearch(_state.value.query.trim(), mapCenter)

    /** Map settled after a user pan: offer "Search this area" while results show. */
    fun onCameraIdle(center: LatLng) {
        mapCenter = center
        if (_state.value.results.isNotEmpty() && _state.value.selected == null) {
            _state.update { it.copy(showSearchThisArea = true) }
        }
    }

    private fun runSearch(q: String, near: LatLng?) {
        if (q.isEmpty()) return
        suggestJob?.cancel()
        recentStore.add(q)
        _state.update { it.copy(recents = recentStore.recent()) }
        viewModelScope.launch {
            _state.update { it.copy(searching = true, suggestions = emptyList(), showSearchThisArea = false, resultsCollapsed = false) }
            try {
                val res = dataSource.search(q, near)
                _state.update {
                    it.copy(results = res.places, selected = null, status = null, searching = false)
                }
            } catch (e: CalibrationNeededException) {
                _state.update { it.copy(status = "Search needs recalibration: ${e.message}", searching = false) }
            } catch (e: Exception) {
                // Network/Google failure → fall back to the offline OSM index.
                val offline = withContext(Dispatchers.IO) {
                    runCatching { offlinePoiStore.search(q, near) }.getOrDefault(emptyList())
                }
                if (offline.isNotEmpty()) {
                    _state.update { it.copy(results = offline, selected = null, status = "Offline results (no connection)", searching = false) }
                } else {
                    _state.update { it.copy(status = "Search failed: ${e.message}", searching = false) }
                }
            }
        }
    }

    /** "Search along route": search [query] biased to the route's midpoint, then
     *  keep only results near the route line (ordered start→destination). Closes
     *  the directions panel to reveal the pins, but keeps the route drawn. */
    fun searchAlongRoute(query: String) {
        val route = _state.value.activeRoute?.polyline
        if (route == null || route.size < 2) { runSearch(query, _state.value.myLocation); return }
        suggestJob?.cancel()
        recentStore.add(query)
        viewModelScope.launch {
            _state.update {
                it.copy(query = query, searching = true, directionsOpen = false, suggestions = emptyList(), resultsCollapsed = false, recents = recentStore.recent())
            }
            try {
                val res = dataSource.search(query, route[route.size / 2])
                val along = RouteCorridor.alongRoute(res.places, route)
                _state.update {
                    it.copy(
                        results = along,
                        selected = null,
                        searching = false,
                        status = if (along.isEmpty()) "No \"$query\" found along your route" else null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, status = "Search failed") }
            }
        }
    }

    fun selectPlace(p: Place) {
        suggestJob?.cancel()
        _state.update { it.copy(selected = p, center = p.location, reviews = emptyList(), suggestions = emptyList()) }
        fetchReviews(p)
        fetchPhotos(p)
    }

    /** Pull the full photo gallery (~40+) by feature id and swap it in for the
     *  search response's ~10-photo preview. Goes through [WebPhotoFetcher] — a real
     *  browser session is the only thing Google serves the gallery to (the OkHttp
     *  path just gets Street View). Best-effort: an empty/failed fetch leaves the
     *  preview untouched (no regression). */
    private fun fetchPhotos(p: Place) {
        val fid = p.featureId
        if (fid.isNullOrBlank() || !fid.contains(":")) return
        viewModelScope.launch {
            val full = runCatching { webPhotos.fetch(fid) }.getOrDefault(emptyList())
            if (full.isNotEmpty()) {
                _state.update { st ->
                    val sel = st.selected
                    if (sel?.featureId == fid) st.copy(selected = sel.copy(photoUrls = full)) else st
                }
            }
        }
    }

    /** Pull full reviews for a place by its Google feature id (best-effort,
     *  applied only if it's still the selected place when they arrive). */
    private fun fetchReviews(p: Place) {
        val fid = p.featureId
        if (fid.isNullOrBlank()) {
            _state.update { it.copy(reviews = emptyList(), reviewsLoading = false) }
            return
        }
        _state.update { it.copy(reviewsLoading = true) }
        viewModelScope.launch {
            val revs = runCatching { dataSource.reviews(fid) }.getOrDefault(emptyList())
            if (_state.value.selected?.featureId == fid) {
                _state.update { it.copy(reviews = revs, reviewsLoading = false) }
            }
        }
    }

    fun clearSelection() =
        _state.update {
            it.copy(
                selected = null, reviews = emptyList(), reviewsLoading = false,
                routes = emptyList(), activeRoute = null, directionsOpen = false,
                transit = emptyList(), transitLoading = false,
                showSteps = false, previewStepIndex = null,
            )
        }

    /** Back out of the directions preview to the place sheet: drop the route,
     *  keep the place selected (so back peels one layer at a time). */
    fun clearRoute() {
        destination = null
        _state.update {
            it.copy(
                routes = emptyList(), activeRoute = null, directionsOpen = false,
                transit = emptyList(), transitLoading = false,
                showSteps = false, previewStepIndex = null,
            )
        }
    }

    fun openSteps() = _state.update { it.copy(showSteps = true) }

    fun closeSteps() = _state.update { it.copy(showSteps = false, previewStepIndex = null) }

    /** Tapped a step in the list → preview that maneuver's spot on the map. */
    fun previewStep(index: Int) = _state.update { it.copy(previewStepIndex = index) }

    /** Leave step-preview (the banner swipe / steps list) and return to live nav. */
    fun clearPreview() = _state.update { it.copy(previewStepIndex = null) }

    /** Tapped a POI on the map: show it immediately, then enrich with full
     *  details (hours, rating, …) from a search for that name nearby. */
    fun onPoiTap(name: String, location: LatLng) {
        _state.update {
            it.copy(
                selected = Place(id = "poi:" + name.hashCode(), name = name, location = location),
                results = emptyList(),
                center = location,
                reviews = emptyList(),
            )
        }
        viewModelScope.launch {
            val full = runCatching {
                dataSource.search(name, location).places.minByOrNull { p -> p.location.distanceTo(location) }
            }.getOrNull()
            if (full != null && _state.value.selected?.name == name) {
                _state.update { it.copy(selected = full) }
                fetchReviews(full)
                fetchPhotos(full)
            }
        }
    }

    /** Long-press the map (or a building) → drop a pin and reverse-geocode it
     *  to an address, like Google's press-and-hold. */
    fun onMapLongPress(location: LatLng) {
        _state.update {
            it.copy(
                selected = Place(id = "pin:${location.lat},${location.lng}", name = "Dropped pin", location = location),
                results = emptyList(),
                resultsCollapsed = false,
                showSearchThisArea = false,
                reviews = emptyList(),
            )
        }
        viewModelScope.launch {
            val place = runCatching { dataSource.reverseGeocode(location) }.getOrNull()
            if (place != null && _state.value.selected?.location == location) {
                _state.update { it.copy(selected = place) }
            }
        }
    }

    fun quickSearch(category: String) {
        _state.update { it.copy(query = category) }
        search()
    }

    fun routeToSelected() {
        val dest = _state.value.selected?.location ?: return
        destination = dest
        _state.update { it.copy(directionsOpen = true) }
        route(dest, _state.value.travelMode)
    }

    /** Pick one of the alternate routes (drawn greyed on the map / listed in the
     *  directions panel) as the active one. */
    fun selectRoute(index: Int) = _state.update {
        it.copy(activeRoute = it.routes.getOrNull(index) ?: it.activeRoute)
    }

    fun setTravelMode(mode: TravelMode) {
        if (_state.value.travelMode == mode) return
        _state.update { it.copy(travelMode = mode) }
        destination?.let { route(it, mode) }
    }

    private fun route(dest: LatLng, mode: TravelMode) {
        if (mode == TravelMode.TRANSIT) { routeTransit(dest); return }
        val origin = _state.value.myLocation ?: return
        viewModelScope.launch {
            try {
                val routes = dataSource.directions(origin, dest, mode)
                _state.update {
                    it.copy(
                        routes = routes,
                        activeRoute = routes.firstOrNull(),
                        transit = emptyList(), transitLoading = false,
                        status = if (routes.isEmpty()) "No ${mode.name.lowercase()} route found" else null,
                    )
                }
            } catch (e: CalibrationNeededException) {
                _state.update { it.copy(status = "Directions need recalibration: ${e.message}") }
            } catch (e: Exception) {
                _state.update { it.copy(status = "Routing failed: ${e.message}") }
            }
        }
    }

    /** Transit can't self-route (no traffic-free open transit graph) and Google
     *  only serves it to a real browser engine, so it goes through the hidden
     *  WebView ([WebDirectionsFetcher]) rather than the OkHttp data source. We
     *  clear the driving route line while it loads — transit shows a results
     *  board, not a single drawn path. */
    private fun routeTransit(dest: LatLng) {
        val origin = _state.value.myLocation ?: return
        _state.update { it.copy(routes = emptyList(), activeRoute = null, transit = emptyList(), transitLoading = true, status = null) }
        viewModelScope.launch {
            val trips = runCatching { webDirections.transit(origin, dest) }.getOrDefault(emptyList())
            _state.update {
                if (it.travelMode != TravelMode.TRANSIT) it // user switched away mid-load
                else it.copy(
                    transit = trips,
                    transitLoading = false,
                    status = if (trips.isEmpty()) "No transit routes found" else null,
                )
            }
        }
    }

    fun startNav() {
        val route = _state.value.activeRoute ?: return
        val dest = destination ?: route.polyline.lastOrNull() ?: return
        navSession.start(route, dest, _state.value.selected?.name.orEmpty(), _state.value.selectedEngine?.packageName)
        NavigationService.start(appContext)
    }

    fun stopNav() {
        NavigationService.stop(appContext)
        navSession.stop()
        _state.update { it.copy(showSteps = false, previewStepIndex = null, navCameraDetached = false) }
    }

    /** User panned the map during navigation → detach the follow-camera so they
     *  can look around (a "Re-center" button reattaches it). Ignored mid step-
     *  preview, where the banner swipe already drives the camera. */
    fun onNavPanned() {
        val s = _state.value
        if (s.navigating && s.previewStepIndex == null && !s.navCameraDetached) {
            _state.update { it.copy(navCameraDetached = true) }
        }
    }

    /** Re-center on the vehicle and resume follow (the in-nav Re-center button). */
    fun recenterNav() = _state.update { it.copy(navCameraDetached = false) }

    /** Mute / unmute spoken guidance (the in-nav speaker button). */
    fun toggleVoice() {
        val muted = !voice.muted
        voice.muted = muted
        _state.update { it.copy(voiceMuted = muted) }
    }

    /** Dismiss the arrival summary and return to a clean map (drops the finished
     *  route + selection). */
    fun finishNav() {
        stopNav()
        clearSelection()
    }

    fun acceptFasterRoute() = navSession.acceptFasterRoute()

    fun dismissFasterRoute() = navSession.dismissFasterRoute()

    fun setStyle(style: MapStyle) =
        _state.update { it.copy(styleUri = style.uri, styleName = style.label) }

    fun voiceEngines(): List<VoiceEngine> = voice.availableEngines()

    fun setVoiceEngine(e: VoiceEngine) = _state.update { it.copy(selectedEngine = e) }

    fun dismissPsdsTip() = _state.update { it.copy(showPsdsTip = false) }

    fun recenter() = _state.update { it.copy(center = it.myLocation) }

    fun clearStatus() = _state.update { it.copy(status = null) }

    fun showStatus(msg: String) = _state.update { it.copy(status = msg) }

    /** When a map region is downloaded for offline use, also pull its POIs from
     *  OSM/Overpass into the on-device index so search works there with no signal. */
    fun downloadOfflinePois(south: Double, west: Double, north: Double, east: Double) {
        viewModelScope.launch {
            val pois = withContext(Dispatchers.IO) { OverpassPois.fetch(http, south, west, north, east) }
            if (pois.isNotEmpty()) {
                withContext(Dispatchers.IO) { offlinePoiStore.add(pois) }
                showStatus("Saved ${pois.size} places for offline search")
            }
        }
    }
}
