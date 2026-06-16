package app.vela.ui.map

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.MapDataSource
import app.vela.core.data.RecentSearchStore
import app.vela.core.data.SavedPlaceStore
import app.vela.core.data.tiles.MapStyle
import app.vela.core.location.LocationProvider
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Route
import app.vela.core.model.SavedPlace
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavSession
import app.vela.core.nav.NavState
import app.vela.core.voice.VoiceEngine
import app.vela.core.voice.VoiceGuide
import app.vela.service.NavigationService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val selected: Place? = null,
    val routes: List<Route> = emptyList(),
    val activeRoute: Route? = null,
    val travelMode: TravelMode = TravelMode.DRIVE,
    val navigating: Boolean = false,
    val nav: NavState = NavState(),
    val maneuverText: String = "",
    val fasterRoute: Route? = null,
    val fasterSavingSeconds: Double = 0.0,
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

        viewModelScope.launch {
            navSession.state.collect { ns ->
                _state.update {
                    it.copy(
                        navigating = ns.navigating,
                        nav = ns.nav,
                        maneuverText = ns.maneuverText,
                        activeRoute = if (ns.navigating && ns.route != null) ns.route else it.activeRoute,
                        fasterRoute = ns.fasterRoute,
                        fasterSavingSeconds = ns.fasterSavingSeconds,
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

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    /** The X in the search bar: wipe the query, results and selection. */
    fun clearSearch() = _state.update {
        it.copy(
            query = "", results = emptyList(), selected = null,
            resultsCollapsed = false, showSearchThisArea = false,
        )
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

    fun selectSaved(sp: SavedPlace) =
        selectPlace(Place(id = sp.id, name = sp.name, location = sp.location))

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
        recentStore.add(q)
        _state.update { it.copy(recents = recentStore.recent()) }
        viewModelScope.launch {
            _state.update { it.copy(searching = true, showSearchThisArea = false, resultsCollapsed = false) }
            try {
                val res = dataSource.search(q, near)
                _state.update {
                    it.copy(results = res.places, selected = null, status = null, searching = false)
                }
            } catch (e: CalibrationNeededException) {
                _state.update { it.copy(status = "Search needs recalibration: ${e.message}", searching = false) }
            } catch (e: Exception) {
                _state.update { it.copy(status = "Search failed: ${e.message}", searching = false) }
            }
        }
    }

    fun selectPlace(p: Place) =
        _state.update { it.copy(selected = p, center = p.location) }

    fun clearSelection() =
        _state.update {
            it.copy(
                selected = null, routes = emptyList(), activeRoute = null,
                showSteps = false, previewStepIndex = null,
            )
        }

    fun openSteps() = _state.update { it.copy(showSteps = true) }

    fun closeSteps() = _state.update { it.copy(showSteps = false, previewStepIndex = null) }

    /** Tapped a step in the list → preview that maneuver's spot on the map. */
    fun previewStep(index: Int) = _state.update { it.copy(previewStepIndex = index) }

    /** Tapped a POI on the map: show it immediately, then enrich with full
     *  details (hours, rating, …) from a search for that name nearby. */
    fun onPoiTap(name: String, location: LatLng) {
        _state.update {
            it.copy(
                selected = Place(id = "poi:" + name.hashCode(), name = name, location = location),
                results = emptyList(),
                center = location,
            )
        }
        viewModelScope.launch {
            val full = runCatching {
                dataSource.search(name, location).places.minByOrNull { p -> p.location.distanceTo(location) }
            }.getOrNull()
            if (full != null && _state.value.selected?.name == name) {
                _state.update { it.copy(selected = full) }
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
        route(dest, _state.value.travelMode)
    }

    fun setTravelMode(mode: TravelMode) {
        if (_state.value.travelMode == mode) return
        _state.update { it.copy(travelMode = mode) }
        destination?.let { route(it, mode) }
    }

    private fun route(dest: LatLng, mode: TravelMode) {
        val origin = _state.value.myLocation ?: return
        viewModelScope.launch {
            try {
                val routes = dataSource.directions(origin, dest, mode)
                _state.update {
                    it.copy(
                        routes = routes,
                        activeRoute = routes.firstOrNull(),
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

    fun startNav() {
        val route = _state.value.activeRoute ?: return
        val dest = destination ?: route.polyline.lastOrNull() ?: return
        navSession.start(route, dest, _state.value.selectedEngine?.packageName)
        NavigationService.start(appContext)
    }

    fun stopNav() {
        NavigationService.stop(appContext)
        navSession.stop()
        _state.update { it.copy(showSteps = false, previewStepIndex = null) }
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
}
