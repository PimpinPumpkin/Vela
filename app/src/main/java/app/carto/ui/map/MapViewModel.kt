package app.carto.ui.map

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.carto.core.data.CalibrationNeededException
import app.carto.core.data.MapDataSource
import app.carto.core.data.RecentSearchStore
import app.carto.core.data.tiles.MapStyle
import app.carto.core.location.LocationProvider
import app.carto.core.model.LatLng
import app.carto.core.model.Place
import app.carto.core.model.Route
import app.carto.core.nav.NavSession
import app.carto.core.nav.NavState
import app.carto.core.voice.VoiceEngine
import app.carto.core.voice.VoiceGuide
import app.carto.service.NavigationService
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
    val navigating: Boolean = false,
    val nav: NavState = NavState(),
    val maneuverText: String = "",
    val fasterRoute: Route? = null,
    val fasterSavingSeconds: Double = 0.0,
    val status: String? = null,
    val showPsdsTip: Boolean = false,
    val styleUri: String = MapStyle.DEFAULT.uri,
    val styleName: String = MapStyle.DEFAULT.label,
    val selectedEngine: VoiceEngine? = null,
    val searching: Boolean = false,
    val recents: List<String> = emptyList(),
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
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var destination: LatLng? = null
    private var locationJob: Job? = null

    init {
        val seed = locationProvider.lastKnown()
        _state.update { it.copy(center = seed, myLocation = it.myLocation ?: seed) }
        voice.init() // warm TTS so the engine list is ready in Settings
        _state.update { it.copy(recents = recentStore.recent()) }

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

    fun searchRecent(q: String) {
        onQueryChange(q)
        search()
    }

    fun clearRecents() {
        recentStore.clear()
        _state.update { it.copy(recents = emptyList()) }
    }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        recentStore.add(q)
        _state.update { it.copy(recents = recentStore.recent()) }
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            try {
                val res = dataSource.search(q, _state.value.myLocation)
                _state.update { it.copy(results = res.places, status = null, searching = false) }
            } catch (e: CalibrationNeededException) {
                _state.update { it.copy(status = "Search needs recalibration: ${e.message}", searching = false) }
            } catch (e: Exception) {
                _state.update { it.copy(status = "Search failed: ${e.message}", searching = false) }
            }
        }
    }

    fun selectPlace(p: Place) =
        _state.update { it.copy(selected = p, results = emptyList(), center = p.location) }

    fun clearSelection() =
        _state.update { it.copy(selected = null, routes = emptyList(), activeRoute = null) }

    fun routeToSelected() {
        val dest = _state.value.selected?.location ?: return
        val origin = _state.value.myLocation ?: return
        destination = dest
        viewModelScope.launch {
            try {
                val routes = dataSource.directions(origin, dest)
                _state.update { it.copy(routes = routes, activeRoute = routes.firstOrNull(), status = null) }
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
