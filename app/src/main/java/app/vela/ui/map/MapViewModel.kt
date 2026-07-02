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
import app.vela.web.WebReviewsFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val center: LatLng? = null,
    val recenterTick: Int = 0, // bumped per recenter tap so the map force-moves even if "centered"
    val myLocation: LatLng? = null,
    val myBearing: Float? = null,
    val mySpeed: Float? = null, // metres/second, from GPS
    val compassHeading: Float? = null, // device facing (rotation-vector sensor) — browse cone when stopped
    val myLocationStale: Boolean = true, // grey the dot until/unless a live fix is recent
    val query: String = "",
    val results: List<Place> = emptyList(),
    val ambientPois: List<Place> = emptyList(), // Google places for the visible area, shown on the bare browse map
    val suggestions: List<Place> = emptyList(),
    val selected: Place? = null,
    val placesHere: List<Place> = emptyList(), // other Google listings at the selected spot
    val reviews: List<Review> = emptyList(),
    val reviewsLoading: Boolean = false,
    val reviewsFound: Int = 0, // live count streamed by the scrape while reviewsLoading (progress, not final)
    val photosLoading: Boolean = false, // the lazy WebView gallery scrape is in flight (more photos coming)
    val loadingDetails: Boolean = false, // the lazy WebView detail fetch (popular times etc.) is in flight
    val routes: List<Route> = emptyList(),
    val activeRoute: Route? = null,
    val directionsOpen: Boolean = false,
    val directionsReversed: Boolean = false, // route from the place back to you
    val directionsOrigin: Place? = null,     // custom "From" (null = your live location)
    val pickingOrigin: Boolean = false,      // the next search pick sets the origin, not a destination
    val directionsWaypoints: List<Place> = emptyList(), // intermediate stops, in order (multi-stop)
    val pickingStop: Boolean = false,        // the next search pick is added as a stop
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
    // Offline routing (downloadable per-region CH graphs — Settings → Offline routing)
    val routingRegions: List<app.vela.offline.RoutingRegion> = emptyList(),
    val routingInstalledIds: Set<String> = emptySet(), // region ids whose graphs are on disk
    val routingDownloadingId: String? = null,          // region id currently downloading, else null
    val routingDownloadPct: Int = 0,
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
    private val headingProvider: app.vela.core.location.HeadingProvider,
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
    private val webReviews: WebReviewsFetcher,
    private val webDirections: WebDirectionsFetcher,
    private val diag: app.vela.core.diag.DiagLog,
    private val diagExporter: app.vela.diag.DiagExporter,
    private val webPopularTimes: app.vela.web.WebPopularTimesFetcher,
    private val tripStore: app.vela.replay.TripStore,
    private val routingGraphStore: app.vela.offline.RoutingGraphStore,
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
                val navStarted = ns.navigating && !_state.value.navigating
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
                // Local-only nav breadcrumbs (no-op unless Diagnostics is opted in): a
                // start/arrival trail + per-drive distance & time, so an exported session shows
                // what the nav engine did — the tuning signal that pairs with the raw GPS trip
                // trace. Rides the existing opt-in; never uploaded.
                if (navStarted) diag.record("nav", "start → ${ns.destinationLabel.ifBlank { "destination" }}")
                if (justArrived) {
                    tripStore.finishTrip()
                    diag.record(
                        "nav",
                        "arrived → ${ns.destinationLabel.ifBlank { "destination" }}",
                        String.format(
                            java.util.Locale.US, "drove %.2f mi in %.0f min",
                            ns.tripDistanceMeters / 1609.34, ns.tripElapsedSeconds / 60.0,
                        ),
                    )
                }
            }
        }
    }

    /** Decide the displayed position from a new fix. Rejects GPS OUTLIERS — a coarse NETWORK /
     *  multipath fix that leaps hundreds of metres (the "every ~8 s the dot + distance + mph jump
     *  to a crazy number" jitter) — by capping the move to what's physically plausible for the
     *  elapsed time, and HOLDS the dot at a standstill so a parked car's GPS noise doesn't make it
     *  hop (Google keeps it still). Reused by the live collector and the replay collector. */
    private fun sanePosition(here: LatLng, prev: LatLng?, lastSpeed: Float?, dt: Double, outlierStreak: IntArray): LatLng {
        // No baseline, or the FIRST fix of a session (dt < 0) → anchor here. Without this, a
        // replay that starts away from your live position rejected EVERY fix as an "outlier" vs
        // the stale start point, so the dot never moved ("replay thinks I'm stationary").
        if (prev == null || dt < 0.0) { outlierStreak[0] = 0; return here }
        val moved = prev.distanceTo(here)
        val sp = lastSpeed ?: 0f
        // Outlier: farther than (last speed + accel headroom) × elapsed + GPS slack is implausible
        // for one step → a NETWORK/multipath leap; keep the prior position. BUT if the leap
        // PERSISTS a couple of fixes it's the new reality (a real teleport), so accept + re-anchor
        // instead of getting stuck rejecting forever against a stale point.
        val plausible = (sp + 12f) * dt + 35.0
        if (moved > plausible) {
            if (outlierStreak[0] >= 2) { outlierStreak[0] = 0; return here }
            outlierStreak[0]++
            return prev
        }
        outlierStreak[0] = 0
        // Speed-adaptive LOW-PASS on the position: heavy smoothing at low speed (so parked/idle
        // GPS jitter barely nudges the dot — Google smooths this, OsmAnd doesn't), easing to a 1:1
        // follow by ~10 m/s where real movement dominates the noise. Replaces a binary standstill
        // hold whose hard speed cliff the GPS speed-noise kept tripping (the "still jumps at idle").
        val k = (sp / 10f).coerceIn(0.12f, 1f).toDouble()
        return LatLng(prev.lat + (here.lat - prev.lat) * k, prev.lng + (here.lng - prev.lng) * k)
    }

    fun startLocation() {
        if (locationJob != null) return
        locationJob = viewModelScope.launch {
            launch {
                delay(8_000)
                if (_state.value.myLocation == null) _state.update { it.copy(showPsdsTip = true) }
            }
            // Device-facing compass for the browse-mode heading cone (GPS bearing is junk at a
            // standstill). Pushed to state ONLY in browse and ONLY on a real change (>=2°), so it
            // can't spam recomposition during nav — there the heading comes from the matched road.
            launch {
                var last = Float.NaN
                headingProvider.headings().collect { az ->
                    if (_state.value.navigating) return@collect
                    val moved = if (last.isNaN()) 999f else kotlin.math.abs(((az - last + 540f) % 360f) - 180f)
                    if (moved >= 2f) {
                        last = az
                        _state.update { it.copy(compassHeading = az) }
                    }
                }
            }
            var lastFixTime = 0L
            val posOutlierStreak = intArrayOf(0)
            locationProvider.updates().collect { loc ->
                val rawHere = LatLng(loc.latitude, loc.longitude)
                val prev = _state.value.myLocation
                val dt = if (lastFixTime > 0L) (loc.time - lastFixTime) / 1000.0 else -1.0
                // Drop outlier leaps + hold the dot when parked (see sanePosition).
                val here = sanePosition(rawHere, prev, _state.value.mySpeed, dt, posOutlierStreak)
                val movedM = prev?.distanceTo(here) ?: 0.0
                // A long inter-fix gap while navigating is where the dead-reckon (capped 2 s)
                // carries the puck — log it (opt-in, no-op otherwise) so a tuning trace shows
                // where GPS dropped and for how long.
                if (dt > 3.0 && _state.value.navigating) {
                    diag.record(
                        "gps",
                        String.format(java.util.Locale.US, "fix gap %.1fs while navigating", dt),
                        String.format(java.util.Locale.US, "puck dead-reckons (<=2s) at %.0f m/s", _state.value.mySpeed ?: 0f),
                    )
                }
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
                val rawSpeed = when {
                    loc.hasSpeed() -> loc.speed
                    prev != null && movedM > 1.0 && dt in 0.3..10.0 -> (movedM / dt).toFloat().coerceIn(0f, 70f)
                    else -> _state.value.mySpeed
                }
                // Reject a single-fix speed SPIKE (a GPS glitch — "going 35, hops to 157"): no
                // car gains >15 m/s (~33 mph) between fixes, so keep the prior speed on a jump.
                val lastSp = _state.value.mySpeed
                val speed = if (rawSpeed != null && lastSp != null && rawSpeed > lastSp + 15f) lastSp else rawSpeed
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
                navSession.onLocation(here, app.vela.ui.Units.imperial.value)
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
        if (_state.value.pickingStop) { addStop(base); return }
        if (_state.value.pickingOrigin) { setDirectionsOrigin(base); return }
        _state.update { it.copy(selected = base, center = base.location, placesHere = emptyList(), reviews = emptyList(), reviewsLoading = false, reviewsFound = 0, photosLoading = false, loadingDetails = false) }
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
        if (_state.value.pickingStop) { addStop(p); return }
        if (_state.value.pickingOrigin) { setDirectionsOrigin(p); return }
        suggestJob?.cancel()
        _state.update {
            it.copy(
                selected = p, center = p.location, reviews = emptyList(), suggestions = emptyList(),
                placesHere = othersAt(p, it.results), loadingDetails = false, photosLoading = false,
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

    /** Pull the full photo gallery by scraping the place's own Google Maps page
     *  ([WebPhotoFetcher]) and swap it in for the search response's ~1-photo preview.
     *  Sets [MapState.photosLoading] while in flight so the sheet can show "more coming".
     *  Best-effort: an empty/failed scrape leaves the preview untouched (no regression). */
    private fun fetchPhotos(p: Place) {
        val fid = p.featureId
        if (fid.isNullOrBlank() || !fid.contains(":")) return
        // Only flash the loading shimmer for places LIKELY to have photos — a rated/reviewed
        // business or one with a preview already. A residential address (no rating, reviews, or
        // preview) shouldn't show a photo placeholder for a gallery it'll never have. We still
        // run the scrape silently in case it surprises us; we just don't promise photos.
        val photoWorthy = p.rating != null || p.reviewCount != null || p.photoUrls.isNotEmpty()
        if (photoWorthy) _state.update { if (it.selected?.featureId == fid) it.copy(photosLoading = true) else it }
        viewModelScope.launch {
            val full = runCatching { webPhotos.fetch(fid) }.getOrDefault(emptyList())
            _state.update { st ->
                val sel = st.selected
                if (sel?.featureId == fid) st.copy(
                    selected = if (full.isNotEmpty()) sel.copy(photoUrls = full.map { it.url }, photoDates = full.map { it.postedText }, photoCategories = full.map { it.category }) else sel,
                    photosLoading = false,
                ) else st
            }
        }
    }

    /** Pull full reviews for a place by its Google feature id (best-effort,
     *  applied only if it's still the selected place when they arrive). */
    private var reviewsJob: Job? = null

    private fun fetchReviews(p: Place, force: Boolean = false) {
        // Supersede any in-flight scrape: the fetcher serializes on a Mutex, so an abandoned
        // 40 s Taco Bell grind would otherwise make the NEXT place's reviews queue behind it
        // (~90 s worst case to first review). Cancelling frees the mutex immediately, and this
        // fetch's page navigation kills the old page's scraper script.
        reviewsJob?.cancel()
        // The live Google panel renders reviews itself — don't burn a 20 s background scrape
        // the tab won't show. [force] = the panel failed to carve and the tab fell back to the
        // native list (retryReviews), which DOES need the scrape.
        if (app.vela.ui.LiveReviews.on.value && !force) {
            _state.update { it.copy(reviews = emptyList(), reviewsLoading = false, reviewsFound = 0) }
            return
        }
        val fid = p.featureId
        if (fid.isNullOrBlank()) {
            _state.update { it.copy(reviews = emptyList(), reviewsLoading = false, reviewsFound = 0) }
            return
        }
        _state.update { it.copy(reviewsLoading = true, reviewsFound = 0) }
        // Live progress off the scrape (arrives on a WebView thread — StateFlow.update is
        // thread-safe). Feature-id-gated so a slow scrape can't tick a different place's counter.
        val onProgress: (Int) -> Unit = { n ->
            _state.update { if (it.selected?.featureId == fid) it.copy(reviewsFound = n) else it }
        }
        // Stream the accumulated reviews into the list AS THEY'RE SCRAPED, under the progress bar
        // — 30 s of bar-only was a dead wait. Also gated on reviewsLoading inside the atomic
        // update: the final result clears that flag in the same copy, so a straggler partial
        // racing past the finish line can't overwrite the complete list with a prefix.
        var streamed: List<Review> = emptyList()
        val onPartial: (List<Review>) -> Unit = { list ->
            streamed = list
            _state.update {
                if (it.selected?.featureId == fid && it.reviewsLoading) it.copy(reviews = list) else it
            }
        }
        reviewsJob = viewModelScope.launch {
            // The reviews RPC intermittently comes back empty (a bot-degraded reply / rate
            // blip), which used to show "no reviews" permanently until you reopened the place.
            // When the place's OWN count says it HAS reviews but the fetch returned none, treat
            // that mismatch as a transient miss and retry a couple times with backoff. A place
            // that genuinely has no reviews (count 0/unknown) stops after the first try, so we
            // never hammer the endpoint for places with nothing to fetch.
            val expected = p.reviewCount ?: 0
            // A Kotlin-side timeout returns EMPTY even after partials streamed — keep the streamed
            // set rather than wiping the list the user is already reading (empty < partial < full).
            fun settle(r: List<Review>) = if (r.isEmpty()) streamed else r
            // Retry when the attempt produced nothing OR only a suspicious sliver of a place that
            // clearly has more (the wedged-scrape signature: a couple of overview cards streamed,
            // then the timeout). Without the sliver test, settle() would present 3-of-612 as the
            // final list AND disable both recovery paths at once (this loop, and the tap-to-retry
            // row, which only shows for an EMPTY list).
            fun tooFew(r: List<Review>) = r.size < minOf(4, expected)
            var revs = settle(runCatching { webReviews.fetch(fid, onProgress, onPartial) }.getOrDefault(emptyList()))
            coroutineContext.ensureActive() // superseded by a newer fetch — don't touch state below
            var attempt = 1
            // A fresh fetch clears the flake within a few seconds (confirmed: a manual tap-to-
            // retry succeeds), so auto-retry across a ~3 s window before falling back to the
            // manual retry — most flakes self-heal without the user touching anything.
            while (tooFew(revs) && expected > 0 && attempt <= 2) {
                delay(500L * attempt) // the WebView fetch is thorough (internal polling) — one retry covers a page-load miss
                if (_state.value.selected?.featureId != fid) return@launch // user moved on
                // The dead attempt's last count would otherwise sit frozen on the bar through the
                // retry's page-load window, then visibly snap backward when its first tick lands.
                _state.update { it.copy(reviewsFound = 0) }
                revs = settle(runCatching { webReviews.fetch(fid, onProgress, onPartial) }.getOrDefault(emptyList()))
                coroutineContext.ensureActive()
                attempt++
            }
            if (_state.value.selected?.featureId == fid) {
                _state.update { it.copy(reviews = revs, reviewsLoading = false, reviewsFound = 0) }
            }
        }
    }

    /** User tapped "retry" on the reviews tab after a transient empty fetch — re-run it for
     *  the open place. (The reviews RPC flakes intermittently; the auto-retry covers a quick
     *  blip, this covers one that's stuck for longer than the place sheet's first try.) */
    fun retryReviews() {
        val p = _state.value.selected ?: return
        fetchReviews(p, force = true)
    }

    fun clearSelection() {
        reviewsJob?.cancel() // free the scrape WebView/mutex — nothing is reading its result now
        _state.update {
            it.copy(
                selected = null, placesHere = emptyList(), reviews = emptyList(), reviewsLoading = false, reviewsFound = 0, loadingDetails = false,
                routes = emptyList(), activeRoute = null, directionsOpen = false,
                transit = emptyList(), transitLoading = false,
                showSteps = false, previewStepIndex = null,
                directionsWaypoints = emptyList(), pickingStop = false,
            )
        }
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
                directionsWaypoints = emptyList(), pickingStop = false,
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
        // Picking the route origin (or a stop) by tapping the map → adopt this POI, don't open it.
        if (_state.value.pickingStop) {
            addStop(Place(id = "poi:" + name.hashCode(), name = name, location = location))
            return
        }
        if (_state.value.pickingOrigin) {
            setDirectionsOrigin(Place(id = "poi:" + name.hashCode(), name = name, location = location))
            return
        }
        // Tapping a POI brings it to the FRONT — close the directions chooser so the place sheet
        // isn't loaded invisibly underneath it (it's gated on !directionsOpen). Google does the same.
        reviewsJob?.cancel() // the old place's scrape holds the WebView/mutex — free it for this one
        _state.update {
            it.copy(
                selected = Place(id = "poi:" + name.hashCode(), name = name, location = location),
                results = emptyList(),
                center = location,
                placesHere = emptyList(),
                reviews = emptyList(),
                // Also clear the loading flag + live counter: a still-in-flight scrape for the
                // PREVIOUS place would otherwise leave its count showing under THIS one (its
                // completion update is feature-id-gated, so the stale flag never self-heals).
                reviewsLoading = false,
                reviewsFound = 0,
                loadingDetails = false,
                photosLoading = false,
                pickingOrigin = false,
                pickingStop = false,
                directionsOpen = false,
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
        reviewsJob?.cancel() // a pin never fetches reviews — free the old scrape's WebView/mutex
        _state.update {
            it.copy(
                selected = Place(id = "pin:${location.lat},${location.lng}", name = "Dropped pin", location = location),
                results = emptyList(),
                resultsCollapsed = false,
                showSearchThisArea = false,
                placesHere = emptyList(),
                reviews = emptyList(),
                // A dropped pin never fetches reviews OR photos, and the previous place's in-flight
                // fetches complete behind feature-id gates that no longer match — so any stale
                // loading flag would show (shimmer tiles on a bare road / a spinning review row)
                // FOREVER. Clear them all, like the POI-tap block does.
                reviewsLoading = false,
                reviewsFound = 0,
                photosLoading = false,
                loadingDetails = false,
                pickingOrigin = false,
                pickingStop = false,
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
        // Start each directions session clean — don't inherit a custom origin, stops, or
        // pick-mode left over from a previous place's directions.
        _state.update { it.copy(directionsOpen = true, directionsReversed = false, directionsOrigin = null, pickingOrigin = false, directionsWaypoints = emptyList(), pickingStop = false) }
        route(_state.value.travelMode)
    }

    /** Swap origin and destination — route the other way (you ⇄ the place). The stop list is
     *  physically reversed too, so STORED order always == DISPLAYED order == TRAVEL order — otherwise
     *  the panel would list stops opposite to how they're driven and the reorder arrows would act
     *  inverted on a reversed trip. */
    fun swapDirections() {
        _state.update {
            it.copy(
                directionsReversed = !it.directionsReversed,
                directionsWaypoints = it.directionsWaypoints.reversed(),
            )
        }
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

    /** Tapped "Add stop" → the next search pick becomes an intermediate stop (multi-stop routing).
     *  [addStop]/[cancelPickStop] ends the mode. */
    fun beginPickStop() = _state.update { it.copy(pickingStop = true, query = "", suggestions = emptyList()) }

    fun cancelPickStop() = _state.update { it.copy(pickingStop = false) }

    /** Append an intermediate stop and re-route through it. */
    fun addStop(p: Place) {
        _state.update { it.copy(directionsWaypoints = it.directionsWaypoints + p, pickingStop = false) }
        route(_state.value.travelMode)
    }

    /** Remove the stop at [index] and re-route. */
    fun removeStop(index: Int) {
        _state.update { it.copy(directionsWaypoints = it.directionsWaypoints.filterIndexed { i, _ -> i != index }) }
        route(_state.value.travelMode)
    }

    /** Move the stop at [index] by [delta] (−1 up / +1 down) and re-route through the new order. */
    fun moveStop(index: Int, delta: Int) {
        _state.update { s ->
            val list = s.directionsWaypoints.toMutableList()
            val to = index + delta
            if (index in list.indices && to in list.indices) list.add(to, list.removeAt(index))
            s.copy(directionsWaypoints = list)
        }
        route(_state.value.travelMode)
    }

    /** Pick one of the alternate routes (drawn greyed on the map / listed in the
     *  directions panel) as the active one. A provisional Google alternate (polyline + ETA only) is
     *  NAMED here — the moment you pick it — so its turn-by-turn is ready by the time you hit Start. */
    fun selectRoute(index: Int) {
        val picked = _state.value.routes.getOrNull(index) ?: return
        _state.update { it.copy(activeRoute = picked) }
        if (!picked.provisional) return
        namingJob?.cancel()
        namingJob = viewModelScope.launch {
            val named = nameIfNeeded(picked)
            _state.update { st ->
                val routes = st.routes.toMutableList()
                if (index in routes.indices && routes[index] === picked) routes[index] = named
                st.copy(routes = routes, activeRoute = if (st.activeRoute === picked) named else st.activeRoute)
            }
        }
    }

    private var namingJob: kotlinx.coroutines.Job? = null

    /** Turn a provisional route's placeholder steps into real named turn-by-turn (map-matched / snapped
     *  on-device). Its own polyline endpoints are the origin + destination. Best-effort. */
    private suspend fun nameIfNeeded(route: app.vela.core.model.Route): app.vela.core.model.Route {
        if (!route.provisional) return route
        val o = route.polyline.firstOrNull() ?: return route.copy(provisional = false)
        val d = route.polyline.lastOrNull() ?: return route.copy(provisional = false)
        return runCatching { dataSource.nameRoute(route, o, d, _state.value.travelMode) }
            .getOrNull() ?: route.copy(provisional = false)
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
        // Stops are ALWAYS stored in travel order (swapDirections physically reverses the list), so no
        // per-call reversal here — display, reorder arrows and routing all agree on one order.
        val stops = s.directionsWaypoints.map { it.location }
        viewModelScope.launch {
            try {
                val routes = dataSource.directions(origin, dest, mode, stops)
                _state.update {
                    it.copy(
                        routes = routes,
                        activeRoute = routes.firstOrNull(),
                        transit = emptyList(), transitLoading = false,
                        status = if (routes.isEmpty()) "No ${mode.name.lowercase()} route found" else null,
                    )
                }
                // The default active route can be a PROVISIONAL Google alternate (it sorts to the
                // top when it has the fastest live ETA). A provisional route carries Google's
                // ABBREVIATED steps + an ETA over un-snapped geometry — so the pre-nav preview showed
                // wrong turns/ETA that only "corrected" when Start named it. Name it NOW (OSRM snap +
                // re-applied traffic), exactly as picking an alternate does, so preview == nav.
                if (routes.firstOrNull()?.provisional == true) selectRoute(0)
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
        // If they hit Start before a picked alternate finished naming, name it first, then launch.
        if (route.provisional) {
            viewModelScope.launch {
                val named = nameIfNeeded(route)
                _state.update { it.copy(activeRoute = named) }
                launchNav(named)
            }
        } else {
            launchNav(route)
        }
    }

    private fun launchNav(route: app.vela.core.model.Route) {
        val dest = destination ?: route.polyline.lastOrNull() ?: return
        startLocation() // make sure live fixes are flowing — they drive the nav loop
        // Stops are stored in travel order (swapDirections reverses the list itself) → per-stop arrival
        // cues + reroute-through-remaining.
        val s = _state.value
        val stops = s.directionsWaypoints.map { NavSession.NavStop(it.location, it.name) }
        navSession.start(route, dest, s.selected?.name.orEmpty(), s.selectedEngine?.packageName, stops, s.travelMode)
        NavigationService.start(appContext)
        // Record this trip's GPS trace for later replay, if the user opted in. Read
        // the pref directly so it works even before Settings has been opened.
        if (settingsPrefs.getBoolean("trip_recording_on", false)) {
            tripStore.startTrip(_state.value.selected?.name ?: "Trip", dest, System.currentTimeMillis())
            tripStore.saveRoute(route) // save the blue line + maneuvers so a replay drives THIS route
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
                // Drive turn-by-turn during the replay without manually starting nav first.
                // Prefer the route SAVED with the trip (the exact blue line the user drove) so the
                // cards/voice replay identically and any divergence is real, not a re-route
                // artifact; fall back to a fresh route for older trips that predate route-saving.
                // Best-effort (the replay still plays if both fail), skipped if nav's already active.
                if (!navSession.state.value.navigating) {
                    val saved = tripStore.loadRoute(meta.id)
                    val route = saved ?: meta.dest?.let { d ->
                        val from = LatLng(fixes.first().lat, fixes.first().lng)
                        runCatching { dataSource.directions(from, d, TravelMode.DRIVE) }.getOrNull()?.firstOrNull()
                    }
                    val dest = meta.dest ?: route?.polyline?.lastOrNull()
                    if (route != null && dest != null) {
                        destination = dest
                        navSession.start(route, dest, meta.label, null)
                        replayOwnsNav = true
                    }
                }
                val pts = fixes.map { app.vela.core.location.ReplayFix(it.lat, it.lng, it.t, it.bearing, it.speed) }
                var lastReplayT = 0L
                val posOutlierStreak = intArrayOf(0)
                locationProvider.replay(pts, speedup = 3f).collect { loc ->
                    val rawHere = LatLng(loc.latitude, loc.longitude)
                    val prev = _state.value.myLocation
                    val dt = if (lastReplayT > 0L) (loc.time - lastReplayT) / 1000.0 else -1.0
                    lastReplayT = loc.time
                    // Same outlier-reject + standstill-hold as live, so a recorded NETWORK leap
                    // doesn't jump the dot / distance / mph on replay either.
                    val here = sanePosition(rawHere, prev, _state.value.mySpeed, dt, posOutlierStreak)
                    val bearing = if (loc.hasBearing() && loc.speed > 0.5f) loc.bearing else _state.value.myBearing
                    // Same single-fix spike reject as live GPS — recorded traces carry the raw
                    // glitches, so a 35→157 mph hop in the trace doesn't show on replay either.
                    val rawSp = if (loc.hasSpeed()) loc.speed else _state.value.mySpeed
                    val lastSp = _state.value.mySpeed
                    val speed = if (rawSp != null && lastSp != null && rawSp > lastSp + 15f) lastSp else rawSp
                    _state.update { it.copy(myLocation = here, myBearing = bearing, mySpeed = speed, center = here, myLocationStale = false) }
                    navSession.onLocation(here, app.vela.ui.Units.imperial.value)
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

    fun recenter() = _state.update { it.copy(center = it.myLocation, recenterTick = it.recenterTick + 1) }

    fun clearStatus() = _state.update { it.copy(status = null) }

    fun showStatus(msg: String) = _state.update { it.copy(status = msg) }

    // --- offline download (triggered from Settings, not a map FAB) -------------

    // [south, west, north, east, zoom] of the last settled map view; the offline
    // download uses this so the control can live in Settings, off the map.
    @Volatile
    private var viewport: DoubleArray? = null

    fun onViewport(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        viewport = doubleArrayOf(south, west, north, east, zoom)
        maybeLoadAmbientPois(LatLng((south + north) / 2, (west + east) / 2), zoom)
    }

    private var ambientJob: Job? = null
    private var lastAmbientCenter: LatLng? = null
    private var lastAmbientZoom = 0.0

    /**
     * Ambient Google POIs: on a bare, zoomed-in browse map, fetch the prominent Google places for
     * the visible area and show them as category dots — so Google-only spots (not in the OSM
     * basemap) appear without searching. The query viewport TRACKS the map zoom (zoom in → tighter
     * box → denser, more local results, like Google), and the dots are CLEARED when you zoom out
     * past neighbourhood level (they'd be sparse + cluttered over a huge area). Tightly gated:
     * bare map only (no results / open place / nav / replay), debounced, re-queried on a real pan
     * OR zoom change.
     */
    private fun maybeLoadAmbientPois(center: LatLng, zoom: Double) {
        val s = _state.value
        if (s.navigating || s.replaying || s.results.isNotEmpty() || s.selected != null) return
        // Zoomed out past neighbourhood level → drop the dots (and let the OSM POIs come back).
        if (zoom < 14.0) {
            ambientJob?.cancel()
            lastAmbientCenter = null
            if (s.ambientPois.isNotEmpty()) _state.update { it.copy(ambientPois = emptyList()) }
            return
        }
        // Re-query only on a real pan or a real zoom change (not every settle).
        val moved = lastAmbientCenter?.let { it.distanceTo(center) >= 180.0 } ?: true
        val zoomed = abs(zoom - lastAmbientZoom) >= 0.8
        if (!moved && !zoomed && s.ambientPois.isNotEmpty()) return
        ambientJob?.cancel()
        // Span ≈ viewport height: ~9 km at z14 down to ~3.5 km zoomed in (kept ≥3.5 km — tighter
        // than that returns FEWER local hits, per the live calibration).
        val span = (9000.0 / 2.0.pow(zoom - 14.0)).coerceIn(3500.0, 9000.0)
        ambientJob = viewModelScope.launch {
            delay(500) // let the map settle before scraping
            val res = runCatching { dataSource.nearbyPlaces(center, span) }.getOrNull() ?: return@launch
            lastAmbientCenter = center
            lastAmbientZoom = zoom
            // Re-check we're still on the bare map — the user may have searched/opened a place while we fetched.
            val cur = _state.value
            if (cur.navigating || cur.replaying || cur.results.isNotEmpty() || cur.selected != null) return@launch
            _state.update { it.copy(ambientPois = res.filterNot { p -> p.permanentlyClosed }.take(80)) }
        }
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
        downloadRoutingForArea((s + n) / 2, (w + e) / 2)
    }

    /** Saving an area offline also pulls the routing graph for the region that CONTAINS it (if one is
     *  catalogued + not already installed) — so "offline for this area" means map AND navigation, one tap. */
    private fun downloadRoutingForArea(lat: Double, lng: Double) {
        viewModelScope.launch {
            val regions = _state.value.routingRegions.ifEmpty {
                routingGraphStore.manifest(app.vela.BuildConfig.ROUTING_MANIFEST_URL)
                    .also { rs -> _state.update { it.copy(routingRegions = rs) } }
            }
            // smallest covering box = the specific region for this area (boxes overlap at borders; a big
            // neighbour like British Columbia shouldn't be grabbed for a Sacramento download)
            val region = regions.filter { lat in it.s..it.n && lng in it.w..it.e }
                .minByOrNull { (it.n - it.s) * (it.e - it.w) } ?: return@launch
            if (region.id in routingGraphStore.installedIds() || _state.value.routingDownloadingId != null) return@launch
            downloadRoutingGraph(region) // shows its own progress + status
        }
    }

    // --- Offline ROUTING graphs (Settings → Offline routing) ---------------------------------

    /** Reflect what's installed + fetch the manifest of downloadable region graphs. */
    fun refreshRoutingRegions() {
        _state.update { it.copy(routingInstalledIds = routingGraphStore.installedIds()) }
        viewModelScope.launch {
            val regions = routingGraphStore.manifest(app.vela.BuildConfig.ROUTING_MANIFEST_URL)
            _state.update { it.copy(routingRegions = regions) }
        }
    }

    /** Download + install [region]'s CH graph for fully-offline routing in that area. */
    fun downloadRoutingGraph(region: app.vela.offline.RoutingRegion) {
        if (_state.value.routingDownloadingId != null) return
        _state.update { it.copy(routingDownloadingId = region.id, routingDownloadPct = 0) }
        viewModelScope.launch {
            val ok = routingGraphStore.download(region) { pct ->
                _state.update { it.copy(routingDownloadPct = pct) }
            }
            _state.update {
                it.copy(routingDownloadingId = null, routingInstalledIds = routingGraphStore.installedIds())
            }
            showStatus(if (ok) "Offline routing ready: ${region.name}" else "Offline routing download failed")
        }
    }

    fun deleteRoutingGraph(id: String) {
        routingGraphStore.delete(id)
        _state.update { it.copy(routingInstalledIds = routingGraphStore.installedIds()) }
        showStatus("Offline routing removed")
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
