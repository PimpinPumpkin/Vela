package app.carto.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.carto.core.data.CalibrationNeededException
import app.carto.core.data.MapDataSource
import app.carto.core.data.tiles.MapStyle
import app.carto.core.location.LocationProvider
import app.carto.core.model.LatLng
import app.carto.core.model.Place
import app.carto.core.model.Route
import app.carto.core.nav.NavEngine
import app.carto.core.nav.NavEvent
import app.carto.core.nav.NavState
import app.carto.core.voice.VoiceEngine
import app.carto.core.voice.VoiceGuide
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val query: String = "",
    val results: List<Place> = emptyList(),
    val selected: Place? = null,
    val routes: List<Route> = emptyList(),
    val activeRoute: Route? = null,
    val navigating: Boolean = false,
    val nav: NavState = NavState(),
    val maneuverText: String = "",
    val status: String? = null,
    val showPsdsTip: Boolean = false,
    val styleUri: String = MapStyle.DEFAULT.uri,
    val styleName: String = MapStyle.DEFAULT.label,
    val selectedEngine: VoiceEngine? = null,
    val searching: Boolean = false,
)

/**
 * The single state holder for the whole map experience: location stream,
 * search, route preview and the live turn-by-turn loop. Search/route failures
 * are caught and surfaced as [MapUiState.status] (especially the routine
 * [CalibrationNeededException]) rather than crashing.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    private val dataSource: MapDataSource,
    private val locationProvider: LocationProvider,
    private val voice: VoiceGuide,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var destination: LatLng? = null
    private var locationJob: Job? = null

    init {
        // Instant map: seed the camera from the cached last-known fix.
        val seed = locationProvider.lastKnown()
        _state.update { it.copy(center = seed, myLocation = it.myLocation ?: seed) }
        // Warm up TTS early so the engine list is ready in Settings.
        voice.init()
    }

    /** Call once location permission is granted. Idempotent. */
    fun startLocation() {
        if (locationJob != null) return
        locationJob = viewModelScope.launch {
            launch {
                delay(8_000)
                if (_state.value.myLocation == null) _state.update { it.copy(showPsdsTip = true) }
            }
            locationProvider.updates().collect { loc ->
                val here = LatLng(loc.latitude, loc.longitude)
                _state.update {
                    it.copy(myLocation = here, showPsdsTip = false, center = it.center ?: here)
                }
                if (_state.value.navigating) advanceNav(here)
            }
        }
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
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
                _state.update {
                    it.copy(routes = routes, activeRoute = routes.firstOrNull(), status = null)
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
        voice.init(_state.value.selectedEngine?.packageName)
        val first = route.maneuvers.firstOrNull()?.instruction.orEmpty()
        _state.update { it.copy(navigating = true, nav = NavState(), maneuverText = first) }
        voice.speak("Starting navigation. $first")
    }

    fun stopNav() {
        voice.stop()
        _state.update { it.copy(navigating = false, nav = NavState(), maneuverText = "") }
    }

    private fun advanceNav(here: LatLng) {
        val route = _state.value.activeRoute ?: return
        val (next, events) = NavEngine.update(route, _state.value.nav, here)
        val maneuver = route.maneuvers.getOrNull(next.stepIndex)
        _state.update { it.copy(nav = next, maneuverText = maneuver?.instruction.orEmpty()) }
        events.forEach { ev ->
            when (ev) {
                is NavEvent.Speak -> voice.speak(ev.text, ev.interrupt)
                NavEvent.Arrived -> _state.update { it.copy(navigating = false) }
                NavEvent.RerouteNeeded -> reroute(here)
            }
        }
    }

    private fun reroute(from: LatLng) {
        val dest = destination ?: return
        viewModelScope.launch {
            voice.speak("Rerouting", interrupt = true)
            runCatching { dataSource.directions(from, dest) }.onSuccess { routes ->
                _state.update {
                    it.copy(routes = routes, activeRoute = routes.firstOrNull(), nav = NavState())
                }
            }
        }
    }

    // --- settings ----------------------------------------------------------

    fun setStyle(style: MapStyle) =
        _state.update { it.copy(styleUri = style.uri, styleName = style.label) }

    fun voiceEngines(): List<VoiceEngine> = voice.availableEngines()

    fun setVoiceEngine(e: VoiceEngine) = _state.update { it.copy(selectedEngine = e) }

    fun dismissPsdsTip() = _state.update { it.copy(showPsdsTip = false) }

    fun recenter() = _state.update { it.copy(center = it.myLocation) }

    fun clearStatus() = _state.update { it.copy(status = null) }

    override fun onCleared() {
        voice.shutdown()
    }
}
