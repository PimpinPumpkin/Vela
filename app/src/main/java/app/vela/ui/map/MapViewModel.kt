package app.vela.ui.map

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vela.core.config.CalibrationStore
import app.vela.core.config.Notice
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.MapDataSource
import app.vela.core.data.MapLink
import app.vela.core.data.OfflinePoiStore
import app.vela.core.data.RouteCorridor
import app.vela.core.data.OverpassPois
import app.vela.core.data.PlaceShortcutStore
import app.vela.core.data.RecentPlaceStore
import app.vela.core.data.RecentSearchStore
import app.vela.core.data.SavedPlaceStore
import app.vela.core.data.tiles.MapStyle
import app.vela.core.location.LocationProvider
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Review
import app.vela.core.model.Route
import app.vela.core.model.SavedPlace
import app.vela.core.model.ShortcutKind
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavSession
import app.vela.core.nav.NavState
import app.vela.core.voice.VoiceEngine
import app.vela.core.voice.VoiceGuide
import app.vela.voice.VoiceInstaller
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
    val mySpeed: Float? = null, // metres/second, from GPS
    val myLocationStale: Boolean = true, // grey the dot until/unless a live fix is recent
    val query: String = "",
    val results: List<Place> = emptyList(),
    val suggestions: List<Place> = emptyList(),
    val selected: Place? = null,
    val placesHere: List<Place> = emptyList(), // other Google listings at the selected spot
    val reviews: List<Review> = emptyList(),
    val reviewsLoading: Boolean = false,
    val loadingDetails: Boolean = false, // the lazy WebView detail fetch (popular times etc.) is in flight
    val routes: List<Route> = emptyList(),
    val activeRoute: Route? = null,
    val directionsOpen: Boolean = false,
    val directionsReversed: Boolean = false, // route from the place back to you
    val directionsOrigin: Place? = null,     // custom "From" (null = your live location)
    val pickingOrigin: Boolean = false,      // the next search pick sets the origin, not a destination
    val travelMode: TravelMode = TravelMode.DRIVE,
    val transit: List<TransitItinerary> = emptyList(),
    val transitLoading: Boolean = false,
    val navigating: Boolean = false,
    val navCameraDetached: Boolean = false,
    val voiceMuted: Boolean = false,
    val diagnosticsEnabled: Boolean = false,
    val tripRecordingEnabled: Boolean = false, // record nav GPS traces for replay (more invasive)
    val replaying: Boolean = false,            // a recorded trip is currently being replayed
    val arrived: Boolean = false,
    val nav: NavState = NavState(),
    val maneuverText: String = "",
    val fasterRoute: Route? = null,
    val fasterSavingSeconds: Double = 0.0,
    val arrivedLabel: String = "",
    val arrivedDistanceMeters: Double = 0.0,
    val arrivedSeconds: Double = 0.0,
    val status: String? = null,
    val installingEngine: String? = null, // pkg of the voice engine currently downloading
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
    val recentPlaces: List<SavedPlace> = emptyList(),
    val saved: List<SavedPlace> = emptyList(),
    val home: SavedPlace? = null,
    val work: SavedPlace? = null,
    val assigningShortcut: ShortcutKind? = null, // picking a place to pin as Home/Work
    val notices: List<Notice> = emptyList(), // pushed via the signed calibration channel
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
    private val voiceInstaller: VoiceInstaller,
    private val navSession: NavSession,
    private val recentStore: RecentSearchStore,
    private val recentPlaceStore: RecentPlaceStore,
    private val savedStore: SavedPlaceStore,
    private val shortcutStore: PlaceShortcutStore,
    private val calibration: CalibrationStore,
    private val offlinePoiStore: OfflinePoiStore,
    private val webPhotos: WebPhotoFetcher,
    private val webDirections: WebDirectionsFetcher,
    private val diag: app.vela.core.diag.DiagLog,
    private val diagExporter: app.vela.diag.DiagExporter,
    private val webPopularTimes: app.vela.web.WebPopularTimesFetcher,
    private val tripStore: app.vela.replay.TripStore,
    private val http: okhttp3.OkHttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var destination: LatLng? = null
    private var mapCenter: LatLng? = null
    private var locationJob: Job? = null
    private var staleTimerJob: Job? = null
    private var replayJob: Job? = null
    private var replayOwnsNav = false // a replay auto-started the nav session → tear it down on end/supersede
    private val noticePrefs = appContext.getSharedPreferences("vela_notices", Context.MODE_PRIVATE)

    init {
        val seed = locationProvider.lastKnown()
        _state.update { it.copy(center = seed, myLocation = it.myLocation ?: seed) }
        voice.init() // warm TTS so the engine list is ready in Settings
        _state.update {
            it.copy(
                recents = recentStore.recent(), saved = savedStore.saved(),
                recentPlaces = recentPlaceStore.recent(),
                home = shortcutStore.get(ShortcutKind.HOME), work = shortcutStore.get(ShortcutKind.WORK),
            )
        }
        refreshNotices() // any cached notices, shown immediately
        // Pull the latest scraper calibration from the repo (non-blocking, once),
        // then surface any freshly-pushed notices.
        viewModelScope.launch {
            runCatching { calibration.refresh() }
            refreshNotices()
        }

        viewModelScope.launch {
            navSession.state.collect { ns ->
                // Persist the recorded trip the instant we arrive, so it survives even if
                // the user never taps "Done" on the arrival card. finishTrip is idempotent,
                // so the later Done → stopNav → finishTrip is a harmless no-op.
                val justArrived = ns.arrived && !_state.value.arrived
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
                if (justArrived) tripStore.finishTrip()
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
            var lastFixTime = 0L
            locationProvider.updates().collect { loc ->
                val here = LatLng(loc.latitude, loc.longitude)
                val prev = _state.value.myLocation
                val movedM = prev?.distanceTo(here) ?: 0.0
                val dt = if (lastFixTime > 0L) (loc.time - lastFixTime) / 1000.0 else -1.0
                // Prefer the fix's own bearing/speed; otherwise DERIVE them from movement.
                // Some fixes omit bearing/speed (cold start, just-started-moving, certain
                // chipsets/ROMs/mock providers) — without a heading the nav puck can't point
                // and dead-reckoning can't run. Only derive on real movement, so a standstill's
                // GPS jitter doesn't spin the marker; and require a sane inter-fix gap (>=0.3 s)
                // so a GPS+NETWORK burst arriving ~together can't divide by a near-zero dt into
                // an absurd speed. Keep the last good value otherwise.
                val bearing = when {
                    loc.hasBearing() && loc.speed > 0.5f -> loc.bearing
                    prev != null && movedM > 3.0 && dt >= 0.3 -> bearingBetween(prev, here)
                    else -> _state.value.myBearing
                }
                val speed = when {
                    loc.hasSpeed() -> loc.speed
                    prev != null && movedM > 1.0 && dt in 0.3..10.0 -> (movedM / dt).toFloat().coerceIn(0f, 70f)
                    else -> _state.value.mySpeed
                }
                lastFixTime = loc.time
                _state.update {
                    it.copy(myLocation = here, myBearing = bearing, mySpeed = speed, showPsdsTip = false, center = it.center ?: here, myLocationStale = false)
                }
                restartStaleTimer()
                // Save the fix to the active trip (no-op unless one is recording).
                tripStore.record(loc)
                // Drive turn-by-turn from here so navigation works even if the
                // foreground NavigationService can't start (Android-14 FGS-location
                // restrictions / GrapheneOS). No-op unless a session is active.
                navSession.onLocation(here)
            }
        }
    }

    /** Great-circle bearing (deg, 0 = N) from [a] to [b] — used to synthesise a heading
     *  when a GPS fix doesn't carry one. */
    private fun bearingBetween(a: LatLng, b: LatLng): Float {
        val dLng = Math.toRadians(b.lng - a.lng)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val y = Math.sin(dLng) * Math.cos(la2)
        val x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dLng)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** Grey the location dot if no live fix arrives for a while (Google-style) — the
     *  seeded last-known position starts stale and turns blue on the first real fix. */
    private fun restartStaleTimer() {
        staleTimerJob?.cancel()
        staleTimerJob = viewModelScope.launch {
            delay(STALE_LOCATION_MS)
            _state.update { it.copy(myLocationStale = true) }
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
        recentPlaceStore.clear()
        _state.update { it.copy(recents = emptyList(), recentPlaces = emptyList()) }
    }

    /** Show notices pushed via the signed calibration channel, minus dismissed ones. */
    private fun refreshNotices() {
        val dismissed = noticePrefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty()
        _state.update { st -> st.copy(notices = calibration.current().notices.filterNot { it.id in dismissed }) }
    }

    fun dismissNotice(id: String) {
        val dismissed = noticePrefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty() + id
        noticePrefs.edit().putStringSet(KEY_DISMISSED, dismissed).apply()
        _state.update { st -> st.copy(notices = st.notices.filterNot { it.id == id }) }
    }

    /** Record an opened place so the search page can offer one-tap return to it. */
    private fun rememberRecentPlace(sp: SavedPlace) {
        recentPlaceStore.add(sp)
        _state.update { it.copy(recentPlaces = recentPlaceStore.recent()) }
    }

    // --- Home / Work shortcuts -------------------------------------------------

    /** Arm "pick a place to pin as Home/Work"; the next selected place is consumed
     *  by [consumeAssign] instead of opening its sheet. */
    fun beginAssignShortcut(kind: ShortcutKind) =
        _state.update { it.copy(assigningShortcut = kind, selected = null) }

    fun cancelAssign() = _state.update { it.copy(assigningShortcut = null) }

    /** If a shortcut is being assigned, store [sp] in it and return true (handled). */
    private fun consumeAssign(sp: SavedPlace): Boolean {
        val kind = _state.value.assigningShortcut ?: return false
        shortcutStore.set(kind, sp)
        _state.update {
            it.copy(
                assigningShortcut = null, selected = null, suggestions = emptyList(),
                results = emptyList(), query = "",
                home = shortcutStore.get(ShortcutKind.HOME), work = shortcutStore.get(ShortcutKind.WORK),
                status = "${kind.label} set to ${sp.name}",
            )
        }
        return true
    }

    /** Open the place pinned to [kind] (like tapping a saved place). */
    fun openShortcut(kind: ShortcutKind) {
        val sp = _state.value.let { if (kind == ShortcutKind.HOME) it.home else it.work } ?: return
        selectSaved(sp)
    }

    fun clearShortcut(kind: ShortcutKind) {
        shortcutStore.set(kind, null)
        _state.update {
            it.copy(home = shortcutStore.get(ShortcutKind.HOME), work = shortcutStore.get(ShortcutKind.WORK))
        }
    }

    /** Pin the currently-open place straight to Home/Work from its sheet. */
    fun setSelectedAsShortcut(kind: ShortcutKind) {
        val p = _state.value.selected ?: return
        pinSavedAs(SavedPlace.of(p), kind)
    }

    /** Pin an already-saved place straight to Home/Work (no assign hop needed). */
    fun pinSavedAs(sp: SavedPlace, kind: ShortcutKind) {
        shortcutStore.set(kind, sp)
        _state.update {
            it.copy(
                home = shortcutStore.get(ShortcutKind.HOME), work = shortcutStore.get(ShortcutKind.WORK),
                status = "${kind.label} set to ${sp.name}",
            )
        }
    }

    /** Remove a place from the saved list (toggle removes an existing entry). */
    fun removeSaved(sp: SavedPlace) {
        savedStore.toggle(sp)
        _state.update { it.copy(saved = savedStore.saved()) }
    }

    fun toggleSave() {
        val p = _state.value.selected ?: return
        savedStore.toggle(SavedPlace.of(p))
        _state.update { it.copy(saved = savedStore.saved()) }
    }

    fun selectSaved(sp: SavedPlace) {
        if (consumeAssign(sp)) return
        val base = Place(id = sp.id, name = sp.name, location = sp.location)
        if (_state.value.pickingOrigin) { setDirectionsOrigin(base); return }
        _state.update { it.copy(selected = base, center = base.location, placesHere = emptyList(), reviews = emptyList(), reviewsLoading = false, loadingDetails = false) }
        rememberRecentPlace(sp)
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
                // The enriched place now has an address, so the WebView detail fetch can
                // do its specific name+address query — without this, popular times +
                // editorial/owner never loaded for saved/recent places (only via search).
                fetchPlaceDetails(enriched)
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
        // A search strongly predicts opening a place — warm the detail WebView now so
        // popular times etc. land faster when the user taps a result (idempotent).
        viewModelScope.launch { runCatching { webPopularTimes.prewarm() } }
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

    /** Handle an external `geo:` / Google-Maps link (Vela as the system maps
     *  handler): a query runs a search biased to any coordinates in the link; a
     *  bare point drops a reverse-geocoded pin there. */
    fun openDeepLink(link: MapLink) {
        val near = link.lat?.let { la -> link.lng?.let { ln -> LatLng(la, ln) } }
        val q = link.query
        when {
            !q.isNullOrBlank() -> {
                _state.update { it.copy(query = q, center = near ?: it.center) }
                runSearch(q, near ?: _state.value.myLocation ?: _state.value.center)
            }
            near != null -> onMapLongPress(near)
        }
    }

    fun selectPlace(p: Place) {
        if (consumeAssign(SavedPlace.of(p))) return
        if (_state.value.pickingOrigin) { setDirectionsOrigin(p); return }
        suggestJob?.cancel()
        _state.update {
            it.copy(
                selected = p, center = p.location, reviews = emptyList(), suggestions = emptyList(),
                placesHere = othersAt(p, it.results), loadingDetails = false,
            )
        }
        fetchReviews(p)
        fetchPhotos(p)
        fetchPlaceDetails(p)
        rememberRecentPlace(SavedPlace.of(p))
    }

    /** Open a "People also search for" card: build a minimal Place from it and select it —
     *  reviews / photos / the full detail re-fetch then fill the rest in (we have its
     *  feature id + location, so the same enrichment that backfills any place applies). */
    fun openSimilar(s: app.vela.core.model.SimilarPlace) {
        selectPlace(
            Place(
                id = "g:" + s.name.hashCode() + ":" + (s.location.lat * 1e4).toInt(),
                name = s.name,
                location = s.location,
                rating = s.rating,
                featureId = s.featureId,
            ),
        )
    }

    /** Pull the rich details the keyless/list search trims — popular times, the
     *  editorial one-liner, and the owner's "From the owner" blurb — via a hidden
     *  WebView (the keyless OkHttp search is bot-degraded and strips them; a real
     *  browser engine isn't — see [WebPopularTimesFetcher]). Best-effort, applied
     *  only to fields we don't already have and only if it's still selected. */
    private fun fetchPlaceDetails(p: Place) {
        if (p.name.isBlank()) return
        // Fetch unless the place already looks complete. Beyond the three rich fields, a
        // missing review count / full weekly hours / address means this is a sparse summary
        // node (a suite/multi-tenant address snap) worth enriching from the focused re-fetch.
        val complete = p.popularTimes != null && p.editorialSummary != null && p.ownerDescription != null &&
            p.reviewCount != null && !p.address.isNullOrBlank() && p.hours.size >= 2
        if (complete) return
        _state.update { if (it.selected?.id == p.id) it.copy(loadingDetails = true) else it }
        viewModelScope.launch {
            val d = runCatching { webPopularTimes.fetch(p) }.getOrNull()
            _state.update { st ->
                val sel = st.selected
                if (sel?.id != p.id) st else st.copy(
                    loadingDetails = false,
                    selected = if (d == null) sel else sel.copy(
                        popularTimes = sel.popularTimes ?: d.popularTimes,
                        editorialSummary = sel.editorialSummary ?: d.editorialSummary,
                        ownerDescription = sel.ownerDescription ?: d.ownerDescription,
                        // Backfill only what the summary left blank; take the fuller hours list.
                        rating = sel.rating ?: d.rating,
                        reviewCount = sel.reviewCount ?: d.reviewCount,
                        hours = if (d.hours.size > sel.hours.size) d.hours else sel.hours,
                        address = sel.address?.ifBlank { null } ?: d.address,
                        phone = sel.phone ?: d.phone,
                        website = sel.website ?: d.website,
                        statusText = sel.statusText ?: d.statusText,
                        openNow = sel.openNow ?: d.openNow,
                        priceText = sel.priceText ?: d.priceText,
                        priceLevel = sel.priceLevel ?: d.priceLevel,
                        about = sel.about.ifEmpty { d.about },
                        featuredReview = sel.featuredReview ?: d.featuredReview,
                    ),
                )
            }
        }
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
                    if (sel?.featureId == fid) st.copy(
                        selected = sel.copy(photoUrls = full.map { it.url }, photoDates = full.map { it.postedText }),
                    ) else st
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
            // The reviews RPC intermittently comes back empty (a bot-degraded reply / rate
            // blip), which used to show "no reviews" permanently until you reopened the place.
            // When the place's OWN count says it HAS reviews but the fetch returned none, treat
            // that mismatch as a transient miss and retry a couple times with backoff. A place
            // that genuinely has no reviews (count 0/unknown) stops after the first try, so we
            // never hammer the endpoint for places with nothing to fetch.
            val expected = p.reviewCount ?: 0
            var revs = runCatching { dataSource.reviews(fid) }.getOrDefault(emptyList())
            var attempt = 1
            while (revs.isEmpty() && expected > 0 && attempt < 3) {
                delay(400L * attempt) // 400ms, then 800ms
                if (_state.value.selected?.featureId != fid) return@launch // user moved on
                revs = runCatching { dataSource.reviews(fid) }.getOrDefault(emptyList())
                attempt++
            }
            if (_state.value.selected?.featureId == fid) {
                _state.update { it.copy(reviews = revs, reviewsLoading = false) }
            }
        }
    }

    fun clearSelection() =
        _state.update {
            it.copy(
                selected = null, placesHere = emptyList(), reviews = emptyList(), reviewsLoading = false, loadingDetails = false,
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
                directionsOrigin = null, pickingOrigin = false, directionsReversed = false,
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
        if (consumeAssign(SavedPlace(id = "poi:" + name.hashCode(), name = name, lat = location.lat, lng = location.lng))) return
        _state.update {
            it.copy(
                selected = Place(id = "poi:" + name.hashCode(), name = name, location = location),
                results = emptyList(),
                center = location,
                placesHere = emptyList(),
                reviews = emptyList(),
                loadingDetails = false,
                pickingOrigin = false,
            )
        }
        viewModelScope.launch {
            val resolved = runCatching {
                val results = dataSource.search(name, location).places
                val nearest = results.minByOrNull { p -> p.location.distanceTo(location) }
                // A tapped POI can map to several Google listings at the same spot —
                // e.g. a co-branded "SpeeDee Midas" has a rich "SpeeDee" profile (543
                // reviews) AND a sparse "Midas" one (2 reviews), both at 2000 F St.
                // Among listings essentially AT the tap (~35 m) the most-reviewed is
                // the maintained, canonical one. But two *genuinely distinct* shops
                // can also share a spot (a strip mall), so only override the nearest
                // result when the canonical listing CLEARLY dominates by review count
                // (a true duplicate, not a close call). Results are already filtered
                // by the POI's own name, which keeps this from wandering off-place.
                val canonical = results
                    .filter { it.location.distanceTo(location) < 35.0 }
                    .maxByOrNull { it.reviewCount ?: 0 }
                val pick = if (canonical != null && nearest != null &&
                    (canonical.reviewCount ?: 0) >= 2 * (nearest.reviewCount ?: 0) + 5
                ) {
                    canonical
                } else {
                    nearest
                }
                pick to results
            }.getOrNull()
            val full = resolved?.first
            if (full != null && _state.value.selected?.name == name) {
                _state.update { it.copy(selected = full, placesHere = othersAt(full, resolved.second)) }
                fetchReviews(full)
                fetchPhotos(full)
                fetchPlaceDetails(full) // popular times + editorial/owner, like a search-result tap
                rememberRecentPlace(SavedPlace.of(full))
            }
        }
    }

    /** Other Google listings essentially at the same spot as [place] (within ~40 m) —
     *  e.g. a co-branded shop's duplicate profile, or a different unit at the address.
     *  Drawn from search results we already have, so it's free; empty for a place
     *  with nothing co-located. Powers the "Also here" section of the place sheet. */
    /** The street line of an address ("239 G St" out of "239 G St, Davis, CA 95616"),
     *  normalised and with any suite/unit/floor dropped, so two listings in the same
     *  building match even if one carries "Ste A". Null when there's no usable line. */
    private fun streetKey(addr: String?): String? {
        val line = addr?.substringBefore(",")?.lowercase()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return line
            .replace(Regex("\\s+(ste|suite|unit|apt|apartment|bldg|building|fl|floor|#).*$"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /** Other Google listings genuinely AT [place]'s address — same street line (the
     *  common case), or, when an address is missing, the same building footprint
     *  (tight radius). Pure proximity was too loose: a shop across the street is well
     *  within 40 m but is NOT "also at this location". */
    private fun othersAt(place: Place, candidates: List<Place>): List<Place> {
        val key = streetKey(place.address)
        return candidates.filter { c ->
            val notSelf =
                if (c.featureId != null && place.featureId != null) c.featureId != place.featureId
                else c.name != place.name
            if (!notSelf) return@filter false
            val ck = streetKey(c.address)
            val dist = c.location.distanceTo(place.location)
            if (key != null && ck != null) ck == key && dist < 60.0 // same address + sanity radius
            else dist < 15.0 // no address to compare → same footprint only
        }.take(6)
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
                placesHere = emptyList(),
                reviews = emptyList(),
                pickingOrigin = false,
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
        if (_state.value.selected == null) return
        // Start each directions session clean — don't inherit a custom origin or
        // pick-mode left over from a previous place's directions.
        _state.update { it.copy(directionsOpen = true, directionsReversed = false, directionsOrigin = null, pickingOrigin = false) }
        route(_state.value.travelMode)
    }

    /** Swap origin and destination — route the other way (you ⇄ the place). */
    fun swapDirections() {
        _state.update { it.copy(directionsReversed = !it.directionsReversed) }
        route(_state.value.travelMode)
    }

    /** Tapped the directions "From" row → the next search pick becomes the origin
     *  (not a destination). The UI opens the search overlay; [setDirectionsOrigin] or
     *  [cancelPickOrigin] ends the mode. */
    fun beginPickOrigin() = _state.update { it.copy(pickingOrigin = true, query = "", suggestions = emptyList()) }

    fun cancelPickOrigin() = _state.update { it.copy(pickingOrigin = false) }

    /** Set a custom directions origin (a place other than your live location) and
     *  re-route. Clears with [clearRoute]. */
    fun setDirectionsOrigin(p: Place) {
        _state.update { it.copy(directionsOrigin = p, pickingOrigin = false) }
        route(_state.value.travelMode)
    }

    /** Drop a custom origin → route from your live location again. Also exits
     *  pick-mode (it's offered as the top row of the origin picker). */
    fun useMyLocationAsOrigin() {
        _state.update { it.copy(directionsOrigin = null, pickingOrigin = false) }
        route(_state.value.travelMode)
    }

    /** Pick one of the alternate routes (drawn greyed on the map / listed in the
     *  directions panel) as the active one. */
    fun selectRoute(index: Int) = _state.update {
        it.copy(activeRoute = it.routes.getOrNull(index) ?: it.activeRoute)
    }

    fun setTravelMode(mode: TravelMode) {
        if (_state.value.travelMode == mode) return
        _state.update { it.copy(travelMode = mode) }
        route(mode)
    }

    private fun route(mode: TravelMode) {
        val s = _state.value
        val place = s.selected?.location ?: return
        // The "from" endpoint: a custom origin if set, else your live location.
        val fromPoint = s.directionsOrigin?.location ?: s.myLocation
        // reversed → from the place back to the from-point; else → from-point to the place.
        val origin = (if (s.directionsReversed) place else fromPoint) ?: return
        val dest = (if (s.directionsReversed) fromPoint else place) ?: return
        destination = dest
        if (mode == TravelMode.TRANSIT) { routeTransit(origin, dest); return }
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
    private fun routeTransit(origin: LatLng, dest: LatLng) {
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
        startLocation() // make sure live fixes are flowing — they drive the nav loop
        navSession.start(route, dest, _state.value.selected?.name.orEmpty(), _state.value.selectedEngine?.packageName)
        NavigationService.start(appContext)
        // Record this trip's GPS trace for later replay, if the user opted in. Read
        // the pref directly so it works even before Settings has been opened.
        if (settingsPrefs.getBoolean("trip_recording_on", false)) {
            tripStore.startTrip(_state.value.selected?.name ?: "Trip", dest, System.currentTimeMillis())
        }
        // If the phone has no voice engine, say so once instead of going silent.
        if (voice.availableEngines().isEmpty()) {
            showStatus("No voice engine installed — add one in Settings → Voice for spoken directions")
        }
    }

    fun stopNav() {
        NavigationService.stop(appContext)
        navSession.stop()
        tripStore.finishTrip() // close + persist the recorded trip (drops too-short ones)
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
    /** Re-attach the follow-camera AND snap the maneuver banner back to the current
     *  step — so recenter undoes both a manual pan and a swipe-ahead step preview. */
    fun recenterNav() = _state.update { it.copy(navCameraDetached = false, previewStepIndex = null) }

    /** Mute / unmute spoken guidance (the in-nav speaker button). */
    fun toggleVoice() {
        val muted = !voice.muted
        voice.muted = muted
        _state.update { it.copy(voiceMuted = muted) }
    }

    /** Reflect the persisted opt-in diagnostics flag into UI state (Settings reads it). */
    fun refreshDiagnostics() = _state.update { it.copy(diagnosticsEnabled = diag.isEnabled()) }

    /** Opt in/out of the local diagnostics log. Off clears anything collected. */
    fun setDiagnostics(on: Boolean) {
        diag.setEnabled(on)
        _state.update { it.copy(diagnosticsEnabled = on) }
    }

    private val settingsPrefs = appContext.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    /** Reflect the persisted "save my trips" flag into UI state. */
    fun refreshTripRecording() =
        _state.update { it.copy(tripRecordingEnabled = settingsPrefs.getBoolean("trip_recording_on", false)) }

    /** Opt in/out of recording nav trips (GPS traces) for replay — strictly local,
     *  more invasive than diagnostics, so it's its own toggle. */
    fun setTripRecording(on: Boolean) {
        settingsPrefs.edit().putBoolean("trip_recording_on", on).apply()
        _state.update { it.copy(tripRecordingEnabled = on) }
    }

    fun recordedTrips(): List<app.vela.replay.TripMeta> = tripStore.list()
    fun deleteTrip(id: String) = tripStore.delete(id)

    /** Replay a recorded trip's GPS trace through the live pipeline (camera + dot +
     *  nav loop), at 3× so it's quick. Auto-routes to the trip's destination and starts
     *  turn-by-turn so the drive replays exactly as it did (best-effort; the trace still
     *  plays if routing fails), tearing that nav back down when the replay ends. */
    fun replayTrip(meta: app.vela.replay.TripMeta) {
        val fixes = tripStore.load(meta.id)
        if (fixes.size < 2) { flashStatus("That trip has no track to replay"); return }
        replayJob?.cancel()
        // A superseded replay's stale finally no-ops (the job guard fails below), so tear
        // down any nav IT auto-started here, before this new replay starts its own.
        if (replayOwnsNav) { navSession.stop(); replayOwnsNav = false; destination = null }
        locationJob?.cancel(); locationJob = null // pause live GPS while the trace plays
        _state.update { it.copy(replaying = true, navCameraDetached = false) }
        flashStatus("Replaying ${meta.label} (3×)…", 3000L)
        val job = viewModelScope.launch {
            try {
                // Auto-route to the trip's destination so turn-by-turn runs during the
                // replay without manually starting nav first — best-effort (the replay
                // still plays if routing fails), and skipped if nav is already active.
                val dest = meta.dest
                if (dest != null && !navSession.state.value.navigating) {
                    val from = LatLng(fixes.first().lat, fixes.first().lng)
                    val route = runCatching { dataSource.directions(from, dest, TravelMode.DRIVE) }
                        .getOrNull()?.firstOrNull()
                    if (route != null) {
                        destination = dest
                        navSession.start(route, dest, meta.label, null)
                        replayOwnsNav = true
                    }
                }
                val pts = fixes.map { app.vela.core.location.ReplayFix(it.lat, it.lng, it.t, it.bearing, it.speed) }
                locationProvider.replay(pts, speedup = 3f).collect { loc ->
                    val here = LatLng(loc.latitude, loc.longitude)
                    val bearing = if (loc.hasBearing() && loc.speed > 0.5f) loc.bearing else _state.value.myBearing
                    val speed = if (loc.hasSpeed()) loc.speed else _state.value.mySpeed
                    _state.update { it.copy(myLocation = here, myBearing = bearing, mySpeed = speed, center = here, myLocationStale = false) }
                    navSession.onLocation(here)
                }
            } finally {
                // Only the current replay tears down: a superseded one was already stopped
                // above, so this stale finally (job guard false) no-ops.
                if (replayJob === coroutineContext[Job]) {
                    replayJob = null
                    if (replayOwnsNav) { navSession.stop(); replayOwnsNav = false; destination = null }
                    _state.update { it.copy(replaying = false) }
                    startLocation() // resume live GPS
                }
            }
        }
        replayJob = job
    }

    /** Stop a running replay; its finally clears the flag and resumes live GPS. */
    fun stopReplay() {
        if (!_state.value.replaying) return
        replayJob?.cancel()
    }

    /** A share intent for the recorded debug session, or null if nothing's logged
     *  yet (Settings then shows a "nothing recorded" hint). */
    fun diagShareIntent(): android.content.Intent? = diagExporter.buildShareIntent()

    /** A share/save intent for the saved-places list as a portable JSON file (via the
     *  same FileProvider as the diag export), or null when nothing is saved. */
    fun exportSavedIntent(): android.content.Intent? {
        val places = savedStore.saved()
        if (places.isEmpty()) return null
        return runCatching {
            val dir = java.io.File(appContext.cacheDir, "export").apply { mkdirs() }
            val file = java.io.File(dir, "vela-saved-places.json")
            file.writeText(savedStore.exportJson())
            val uri = androidx.core.content.FileProvider.getUriForFile(
                appContext, "${appContext.packageName}.fileprovider", file,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Vela saved places (${places.size})")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            android.content.Intent.createChooser(send, "Export saved places")
                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        }.getOrNull()
    }

    /** Import saved places from a picked file [uri]; returns how many were newly added
     *  (refreshes the saved list in state). 0 on a read/parse failure or nothing new. */
    fun importSavedFromUri(uri: android.net.Uri): Int {
        val json = runCatching {
            appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return 0
        val added = savedStore.importMerge(json)
        if (added > 0) _state.update { it.copy(saved = savedStore.saved()) }
        return added
    }

    /** A share intent for a recorded trip's raw CSV trace (via the same FileProvider),
     *  so a drive can be pulled off a *release* build — handed to a dev for replay/debug,
     *  or kept as a backup. Null if the trip file is gone. User-initiated, user-routed. */
    fun exportTripIntent(meta: app.vela.replay.TripMeta): android.content.Intent? {
        val csv = tripStore.rawCsv(meta.id) ?: return null
        return runCatching {
            val dir = java.io.File(appContext.cacheDir, "export").apply { mkdirs() }
            val file = java.io.File(dir, "vela-trip-${meta.id}.csv")
            file.writeText(csv)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                appContext, "${appContext.packageName}.fileprovider", file,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Vela trip: ${meta.label} (${meta.fixCount} points)")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            android.content.Intent.createChooser(send, "Share trip")
                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        }.getOrNull()
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

    fun setVoiceEngine(e: VoiceEngine) {
        voice.init(e.packageName) // re-init now so the pick applies + a test plays through it
        _state.update { it.copy(selectedEngine = e) }
    }

    fun testVoice() = voice.test()

    /** null = still initialising, true = a voice is ready, false = no usable voice. */
    fun voiceWorking(): Boolean? = voice.working

    /** Open-source engines a phone with none can install in one tap (off F-Droid). */
    fun installableEngines(): List<VoiceInstaller.Engine> =
        voiceInstaller.engines.filterNot { voiceInstaller.isInstalled(it.pkg) }

    fun installVoiceEngine(engine: VoiceInstaller.Engine) {
        if (_state.value.installingEngine != null) return // one at a time
        _state.update { it.copy(installingEngine = engine.pkg) }
        viewModelScope.launch {
            val result = voiceInstaller.installFromFDroid(engine.pkg)
            _state.update { it.copy(installingEngine = null) }
            // result == null → the system installer launched; else a status/error line.
            flashStatus(result ?: "Opening installer for ${engine.label}…")
        }
    }

    private var statusJob: Job? = null

    /** A status banner that **auto-clears** after a few seconds (unlike [showStatus],
     *  which stays until dismissed) — for transient feedback like a finished download. */
    fun flashStatus(msg: String, millis: Long = 4500L) {
        statusJob?.cancel()
        _state.update { it.copy(status = msg) }
        statusJob = viewModelScope.launch {
            delay(millis)
            _state.update { if (it.status == msg) it.copy(status = null) else it }
        }
    }

    fun dismissPsdsTip() = _state.update { it.copy(showPsdsTip = false) }

    fun recenter() = _state.update { it.copy(center = it.myLocation) }

    fun clearStatus() = _state.update { it.copy(status = null) }

    fun showStatus(msg: String) = _state.update { it.copy(status = msg) }

    // --- offline download (triggered from Settings, not a map FAB) -------------

    // [south, west, north, east, zoom] of the last settled map view; the offline
    // download uses this so the control can live in Settings, off the map.
    @Volatile
    private var viewport: DoubleArray? = null

    fun onViewport(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        viewport = doubleArrayOf(south, west, north, east, zoom)
    }

    fun hasViewport(): Boolean = viewport != null

    /** Download tiles + POIs for the area the map was last showing (Google-style
     *  "download this area", but invoked from Settings → Offline maps). */
    fun downloadViewport() {
        val v = viewport ?: run { showStatus("Open the map and pan to an area first"); return }
        val (s, w, n, e, zoom) = listOf(v[0], v[1], v[2], v[3], v[4])
        val minZ = (zoom - 1).coerceIn(0.0, 15.0)
        val maxZ = (zoom + 3).coerceIn(minZ, 16.0)
        val bounds = org.maplibre.android.geometry.LatLngBounds.from(n, e, s, w)
        val name = "Area near %.2f, %.2f".format((s + n) / 2, (w + e) / 2)
        app.vela.offline.OfflineMaps.download(appContext, _state.value.styleUri, bounds, minZ, maxZ, name, ::showStatus)
        downloadOfflinePois(s, w, n, e)
    }

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

    private companion object {
        const val KEY_DISMISSED = "dismissed"
        const val STALE_LOCATION_MS = 12_000L // grey the dot after this long with no fix
    }
}
