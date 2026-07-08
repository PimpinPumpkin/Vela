package app.vela.ui.map

import android.content.Context
import app.vela.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vela.core.config.CalibrationStore
import app.vela.core.config.Notice
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.MapDataSource
import app.vela.core.data.google.ambientProminence
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
import app.vela.core.voice.PiperCatalog
import app.vela.core.voice.PiperVoice
import app.vela.core.voice.VelaPiper
import app.vela.core.voice.VoiceEngine
import app.vela.core.voice.VoiceGuide
import app.vela.voice.KokoroInstaller
import app.vela.voice.PiperSynth
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

/** Which directions endpoint the "Choose on map" crosshair is currently setting. */
enum class MapPick { ORIGIN, STOP }

data class MapUiState(
    val center: LatLng? = null,
    val recenterTick: Int = 0, // bumped per recenter tap so the map force-moves even if "centered"
    val myLocation: LatLng? = null,
    val myBearing: Float? = null,
    val mySpeed: Float? = null, // metres/second, from GPS (spike-filtered, held briefly on speedless fixes)
    val mySpeedRaw: Float? = null, // THIS fix's own measured speed (doppler or derived) — null when the
                                   // fix carried none. The puck's Kalman measures ONLY from this: feeding
                                   // it the held mySpeed re-injected a stale braking speed at high gain
                                   // every fix, which is what kept the puck "moving" at a red light.
    val speedLimitKmh: Double? = null, // posted limit of the current road (OSM maxspeed via GraphHopper),
                                       // km/h; null = unknown/untagged/no offline graph → badge hidden.
                                       // Converted to the display unit at the badge.
    val navStarved: Boolean = false, // navigating but guidance hasn't received a usable (GPS, ≤50 m
                                     // accuracy) fix in a while — drives the "Searching for GPS" chip
                                     // when coarse fixes keep the ordinary stale timer from firing
    val compassHeading: Float? = null, // device facing (rotation-vector sensor) — browse cone when stopped
    val myLocationStale: Boolean = true, // grey the dot until/unless a live fix is recent
    val offline: Boolean = false, // no usable internet — drives the subtle offline indicator
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
    val buildingOverlays: List<String> = emptyList(), // full pmtiles:// URIs (file:// downloaded / https:// streamed for the view)
    val addressOverlays: List<String> = emptyList(), // pmtiles:// URIs streamed for house-number labels (OpenAddresses)
                                                      // .pmtiles — rendered beneath OSM to fill gaps
    val trafficControls: List<app.vela.core.data.TrafficControl> = emptyList(), // OSM lights+stop signs drawn at high zoom
    val directionsOpen: Boolean = false,
    val directionsReversed: Boolean = false, // route from the place back to you
    val directionsOrigin: Place? = null,     // custom "From" (null = your live location)
    val pickingOrigin: Boolean = false,      // the next search pick sets the origin, not a destination
    val directionsWaypoints: List<Place> = emptyList(), // intermediate stops, in order (multi-stop)
    val pickingStop: Boolean = false,        // the next search pick is added as a stop
    val pickOnMap: MapPick? = null,          // "Choose on map" crosshair mode is active for this endpoint
    val travelMode: TravelMode = TravelMode.DRIVE,
    val transit: List<TransitItinerary> = emptyList(),
    val transitLoading: Boolean = false,
    val navigating: Boolean = false,
    val resumeNavLabel: String? = null, // a nav session was interrupted (process killed mid-drive) and can
                                        // be resumed — drives the "Resume navigation to <label>?" prompt
    val navCameraDetached: Boolean = false,
    val voiceMuted: Boolean = false,
    val diagnosticsEnabled: Boolean = false,
    val tripRecordingEnabled: Boolean = false, // record nav GPS traces for replay (more invasive)
    val replaying: Boolean = false,            // a recorded trip OR a demo drive is playing (drives the puck)
    val demoDriving: Boolean = false,          // replaying is a Settings→demo synthetic drive (not a recorded trip) — nav chrome only, no "Stop replay" pill
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
    val kokoroDownloadPct: Float? = null, // 0f..1f while the neural-voice model downloads; null = idle
    val installedVoiceIds: Set<String> = emptySet(), // Piper voices present on disk (the voice browser)
    val selectedVoiceId: String? = null, // the active Piper voice id (null = none installed)
    val voiceDownloadingId: String? = null, // the ONE voice currently downloading (one-at-a-time), else null
    val voiceInstalling: Boolean = false,   // download done, unpacking the archive (the map card shows "Installing…")
    val voiceSpeaker: Int = 0, // chosen speaker # for the multi-speaker Vela voice (playground stepper)
    val voiceSpeed: Float = 1.0f, // spoken-directions speed multiplier (1.0 = normal, >1 = faster)
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
    private val kokoroInstaller: KokoroInstaller,
    private val piperSynth: PiperSynth,
    private val navSession: NavSession,
    private val recentStore: RecentSearchStore,
    private val recentPlaceStore: RecentPlaceStore,
    private val savedStore: SavedPlaceStore,
    private val shortcutStore: PlaceShortcutStore,
    private val calibration: CalibrationStore,
    private val offlinePoiStore: OfflinePoiStore,
    private val addressStore: app.vela.core.data.OfflineAddressStore,
    private val webPhotos: WebPhotoFetcher,
    private val webReviews: WebReviewsFetcher,
    private val webDirections: WebDirectionsFetcher,
    private val diag: app.vela.core.diag.DiagLog,
    private val diagExporter: app.vela.diag.DiagExporter,
    private val webPopularTimes: app.vela.web.WebPopularTimesFetcher,
    private val tripStore: app.vela.replay.TripStore,
    private val routingGraphStore: app.vela.offline.RoutingGraphStore,
    private val overlayStore: app.vela.offline.OverlayTileStore,
    private val routeEngine: app.vela.core.data.RouteEngine,
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
    private var lastRecordedRoute: app.vela.core.model.Route? = null // last route block written to the
                                                                     // active trip (route swaps append)
    // Nav resume across process death: persist just the DESTINATION (+ label/mode) when nav starts, so if
    // the OS reaps the backgrounded process mid-drive (Android-14 FGS-location limits on GrapheneOS), the
    // next launch can offer to resume — re-fetching a FRESH route from wherever you are now. Route isn't
    // serialized; re-routing from the current fix is simpler + handles the distance you covered while away.
    private val navResumePrefs = appContext.getSharedPreferences("vela_nav_resume", Context.MODE_PRIVATE)
    private var resumeDest: LatLng? = null   // stashed target for resumeNav() after maybeOfferResume()
    private var resumeMode: TravelMode = TravelMode.DRIVE
    private var lastNavHeartbeatMs = 0L       // last time we refreshed the persisted-nav "at" timestamp (see NAV_HEARTBEAT_MS)
    @Volatile private var lastVoiceLangHinted: String? = null // last language we told the user they lack a
                                                              // voice for — so the hint shows once, not per prompt
    @Volatile private var lastLimitLoc: LatLng? = null // last fix the road speed-limit was computed at —
                                                       // the snap is only re-run after moving ~a road-segment
    @Volatile private var lastLimitHitLoc: LatLng? = null // last fix that RESOLVED a limit — drives the
                                                          // "forget a stale limit after driving far off it" clear
    private var limitJob: Job? = null // single-flight the off-thread maxspeed snap
    private val noticePrefs = appContext.getSharedPreferences("vela_notices", Context.MODE_PRIVATE)

    init {
        val seed = locationProvider.lastKnown()
        _state.update { it.copy(center = seed, myLocation = it.myLocation ?: seed) }
        maybeOfferResume() // a drive that was cut off by a process-kill → offer to pick it back up
        refreshBuildingOverlays() // surface any installed open building overlays for the map to render
        observeConnectivity() // drive the subtle offline indicator (globe-slash + "Offline" in the bar)
        // Reclaim disk from the removed Kokoro/Matcha voices (up to ~500 MB of dead model files after
        // the Piper-only switch). Off the main thread; a no-op once the dirs are gone.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                java.io.File(appContext.filesDir, "kokoro").deleteRecursively()
                java.io.File(appContext.filesDir, "matcha").deleteRecursively()
            }
        }
        // Relocate any pre-browser flat Piper install (filesDir/piper/*.onnx) into the per-voice subdir
        // layout the voice browser expects — synchronous, rename-only (no re-download), crash-safe.
        VelaPiper.migrateFlatLayoutIfNeeded(appContext)
        // Restore the saved voice; default to the downloaded Piper voice.
        val savedRaw = appContext.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .getString("voice_engine", null)
        val savedEngine = when {
            // A stale neural id from a removed voice (vela.kokoro / vela.matcha) → our Piper voice.
            savedRaw == null || savedRaw.startsWith("vela.") ->
                if (VelaPiper.isReady(appContext)) VelaPiper.ENGINE_ID else null
            else -> savedRaw // a system TTS engine the user picked
        }
        neuralSynthFor(savedEngine)?.let { voice.neural = it }
        // When guidance can't speak the app/system language (the neural voice is a different
        // language AND no system TTS voice exists for it) — e.g. the user set the app language to
        // Russian but only has the English voice — VoiceGuide stays silent (never mangles it) and
        // tells us the language so we can nudge, once per language.
        voice.langUnavailable = { lang ->
            if (lang != lastVoiceLangHinted) {
                lastVoiceLangHinted = lang
                val endonym = app.vela.ui.AppLocale.endonym(lang)
                viewModelScope.launch(Dispatchers.Main) {
                    flashStatus(appContext.getString(R.string.mapvm_voice_lang_missing, endonym), 6000L)
                }
            }
        }
        voice.init(savedEngine) // null → default system TTS; also warms the engine list for Settings
        if (savedEngine != null) {
            val label = velaLabel(savedEngine)
                ?: voice.availableEngines().firstOrNull { it.packageName == savedEngine }?.label ?: savedEngine
            _state.update { it.copy(selectedEngine = VoiceEngine(savedEngine, label)) }
        }
        neuralSynthFor(savedEngine)?.warmUp()
        val voicePrefs = appContext.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
        val savedSpeed = voicePrefs.getFloat("voice_speed", calibration.current().defaultVoiceSpeed)
        voice.setRate(savedSpeed) // relay the saved rate to the AOSP TTS engine at startup
        val installedVoices = VelaPiper.installedVoiceIds(appContext)
        val activeVoice = VelaPiper.effectiveVoiceId(appContext)
        _state.update {
            it.copy(
                installedVoiceIds = installedVoices.toSet(),
                selectedVoiceId = activeVoice,
                voiceSpeaker = savedSpeakerFor(activeVoice),
                voiceSpeed = savedSpeed,
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
                // Record LIVE route swaps (reroute / accepted faster route) into the active trip
                // as a new RP/RD/M block at the current fix position — without this the saved trip
                // held only the start route while the drive continued on another, and a replay/
                // audit diffed the trace against a route the driver wasn't on ("arrow on another
                // street"). TripLog parses the blocks as segments; replay swaps at the same spot.
                val nsRoute = ns.route
                if (ns.navigating && nsRoute != null && nsRoute !== lastRecordedRoute) {
                    if (lastRecordedRoute != null) tripStore.saveRoute(nsRoute)
                    lastRecordedRoute = nsRoute
                }
                if (!ns.navigating) lastRecordedRoute = null
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
                // Heartbeat the resume timestamp while a REAL drive is under way (skip replay/demo, which
                // don't persist) so the resume window measures time since the INTERRUPTION, not since nav
                // start — else a drive longer than RESUME_MAX_AGE_MS could never be resumed (audit 2026-07-06).
                if (ns.navigating && !_state.value.replaying && navResumePrefs.contains("lat")) {
                    val now = System.currentTimeMillis()
                    if (now - lastNavHeartbeatMs > NAV_HEARTBEAT_MS) {
                        lastNavHeartbeatMs = now
                        navResumePrefs.edit().putLong("at", now).apply()
                    }
                }
                if (justArrived) {
                    tripStore.finishTrip()
                    // Don't touch the resume pref on a REPLAY/DEMO arrival — those never persisted one, and a
                    // real drive could be paused underneath (a replay riding an active nav); only a genuine
                    // live arrival should clear it (audit 2026-07-07).
                    if (!_state.value.replaying) clearPersistedNav()
                    // NavEvent.Arrived fires only at the FINAL destination, so this is safe for multi-stop trips.
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
        // Don't resurrect the live GPS collector mid-replay: replayTrip cancels+nulls locationJob so the
        // trace owns the puck, but a permission callback / MapScreen effect can re-call startLocation while
        // replaying — and a real fix would then overwrite myLocation+center, snapping the puck back to the
        // user's actual position. Replay's own `finally` calls startLocation() again once replaying=false.
        if (_state.value.replaying) return
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
            var lastFixRtNanos = 0L
            var lastGpsMs = 0L
            var lastSpeedEvidenceMs = 0L
            var lastNavFedMs = android.os.SystemClock.elapsedRealtime()
            var prevWasGps = false
            val posOutlierStreak = intArrayOf(0)
            locationProvider.updates().collect { loc ->
                // Belt-and-suspenders with the startLocation guard: if this collector was already in
                // flight when a replay began (cancel hadn't landed yet), drop every real fix while the
                // trace is playing so it can't snap the puck back to the user's actual location.
                if (_state.value.replaying) return@collect
                val nowMs = android.os.SystemClock.elapsedRealtime()
                val isGps = loc.provider == android.location.LocationManager.GPS_PROVIDER
                // Provider gating, OsmAnd-style (useOnlyGPS): a NETWORK (BeaconDB wifi/cell) fix
                // is routinely 100-1000 m off — trusted blindly it teleported the dot onto a
                // parallel street, fired a spurious reroute, then teleported back when GPS
                // recovered ("GPS thinking I am somewhere else"). A network fix may paint the
                // DOT only when GPS has been quiet a while (cold start / garage / dead antenna —
                // a GPS-less phone still deserves a coarse position), and it NEVER steers
                // guidance: the navSession feed below is GPS-only.
                if (!isGps) {
                    if (nowMs - lastGpsMs < NETWORK_FIX_QUIET_MS && lastGpsMs > 0L) return@collect
                } else {
                    lastGpsMs = nowMs
                }
                val rawHere = LatLng(loc.latitude, loc.longitude)
                val prev = _state.value.myLocation
                // Inter-fix dt from the MONOTONIC boot clock: loc.time mixes GNSS UTC (GPS fixes)
                // with the system clock (NETWORK fixes), and an out-of-order timestamp made
                // dt<0 — which sanePosition treated as "first fix" and re-anchored to a raw
                // outlier with no gating at all (a one-fix mid-drive teleport). Mock providers
                // on old APIs can leave elapsedRealtimeNanos at 0 — fall back to loc.time then.
                val fixRtNanos = if (loc.elapsedRealtimeNanos != 0L) loc.elapsedRealtimeNanos else loc.time * 1_000_000L
                val dt = if (lastFixRtNanos > 0L) (fixRtNanos - lastFixRtNanos) / 1e9 else -1.0
                if (lastFixRtNanos > 0L && dt <= 0.0) return@collect // duplicate/reordered delivery — drop it
                // Drop outlier leaps + hold the dot when parked (see sanePosition).
                val here = sanePosition(rawHere, prev, _state.value.mySpeed, dt, posOutlierStreak)
                val movedM = prev?.distanceTo(here) ?: 0.0
                // A long inter-fix gap while navigating is where the dead-reckon carries the
                // puck — log it (opt-in, no-op otherwise) so a tuning trace shows where GPS
                // dropped and for how long.
                if (dt > 3.0 && _state.value.navigating) {
                    diag.record(
                        "gps",
                        String.format(java.util.Locale.US, "fix gap %.1fs while navigating", dt),
                        String.format(java.util.Locale.US, "puck dead-reckons at %.0f m/s", _state.value.mySpeed ?: 0f),
                    )
                }
                // Prefer the fix's own bearing/speed; otherwise DERIVE them from movement.
                // Derivation needs two GPS fixes and real movement past an ACCURACY-scaled noise
                // floor — deriving across a GPS→NETWORK pair minted phantom 16 mph readouts at a
                // red light from a 30 m BeaconDB hop (and re-armed the puck's creep).
                val accFloor = maxOf(3.0, (if (loc.hasAccuracy()) loc.accuracy else 10f) * 0.7).toFloat()
                val canDerive = prev != null && prevWasGps && isGps && movedM > accFloor && dt in 0.3..10.0
                val bearing = when {
                    loc.hasBearing() && loc.speed > 0.5f -> loc.bearing
                    canDerive && movedM > 3.0 -> bearingBetween(prev!!, here)
                    else -> _state.value.myBearing
                }
                // Speed EVIDENCE = this fix measured it (doppler) or real GPS movement derived it.
                // A speedless fix used to hold the previous speed FOREVER — each one re-froze a
                // stale nonzero mph through a whole stop. Hold at most SPEED_HOLD_MS; past that,
                // no evidence of motion = not moving, show 0.
                val hasEvidence = loc.hasSpeed() || canDerive
                if (hasEvidence) lastSpeedEvidenceMs = nowMs
                val rawSpeed = when {
                    loc.hasSpeed() -> loc.speed
                    canDerive -> (movedM / dt).toFloat().coerceIn(0f, 70f)
                    nowMs - lastSpeedEvidenceMs > SPEED_HOLD_MS -> 0f
                    else -> _state.value.mySpeed
                }
                // Plausibility-gate the measured speed (shared with replay): symmetric and
                // accel-bounded — the old one-sided +15 m/s check let a single doppler down-glitch
                // to 0 through at 67 mph, then REJECTED every real 30 m/s fix against the held 0
                // (the speedo-latched-at-0 lockout). The gate compares against the last ACCEPTED
                // measurement and yields to a persistent change on the 2nd consecutive fix.
                val measured = if (hasEvidence && rawSpeed != null) gateMeasuredSpeed(rawSpeed, dt) else null
                val speed = when {
                    measured != null -> measured
                    hasEvidence -> _state.value.mySpeed // one-off glitch rejected: hold the shown value
                    else -> rawSpeed                    // held / timed-out-to-0 path from above
                }
                lastFixRtNanos = fixRtNanos
                prevWasGps = isGps
                _state.update {
                    it.copy(
                        myLocation = here, myBearing = bearing, mySpeed = speed,
                        // The fix's OWN accepted measurement, null when it had none (or the gate
                        // rejected it) — the puck Kalman's measurement stream must never see a
                        // held display value or a rejected glitch.
                        mySpeedRaw = measured,
                        showPsdsTip = false, center = it.center ?: here, myLocationStale = false,
                    )
                }
                restartStaleTimer()
                // Save the fix to the active trip (no-op unless one is recording).
                tripStore.record(loc)
                // Drive turn-by-turn from here so navigation works even if the
                // foreground NavigationService can't start (Android-14 FGS-location
                // restrictions / GrapheneOS). No-op unless a session is active. GUIDANCE IS
                // GPS-ONLY, and a coarse fix (accuracy worse than ~50 m) updates the dot but
                // must not steer it — OsmAnd's ACCURACY_FOR_ROUTING does the same. When
                // guidance is starved of usable fixes for a while (urban canyon at 60-80 m
                // accuracy for minutes), SAY so — the frozen banner used to be indistinguishable
                // from working nav (the stale timer never fires while coarse fixes keep coming).
                if (isGps && (!loc.hasAccuracy() || loc.accuracy <= 50f)) {
                    navSession.onLocation(here, app.vela.ui.Units.imperial.value, speed?.toDouble())
                    lastNavFedMs = nowMs
                    updateSpeedLimit(here) // posted-limit badge for the road under the puck (off-thread)
                    if (_state.value.navStarved) _state.update { it.copy(navStarved = false) }
                } else if (_state.value.navigating && nowMs - lastNavFedMs > NAV_STARVED_MS && !_state.value.navStarved) {
                    _state.update { it.copy(navStarved = true) }
                }
            }
        }
    }

    // Speed plausibility-gate state: the baseline is the last ACCEPTED measurement — never a
    // held/zeroed display value (comparing against state.mySpeed is what created the
    // speedo-latched-at-0 lockout: the zeroer wrote 0 as the baseline and every real 30 m/s
    // doppler was then "a spike" forever).
    private var speedGateBase: Float? = null
    private var speedGateStreak = 0

    /** Gate a MEASURED speed (doppler or derived): symmetric (up AND down — a one-fix doppler
     *  glitch to 0 at 67 mph is as bogus as a hop to 157), accel-bounded (|Δv| ≤ 8 m/s² × dt +
     *  slack, matching SpeedKalman.MAX_ACCEL), and self-healing — the 2nd consecutive
     *  out-of-band fix is the new reality (hard brake, replay jump) and is accepted. Returns the
     *  accepted measurement, or null when this fix's value is rejected (hold the display,
     *  don't feed the Kalman). Shared by the live and replay collectors. */
    private fun gateMeasuredSpeed(raw: Float, dt: Double): Float? {
        val base = speedGateBase
        val bound = (8.0 * dt.coerceIn(0.5, 3.0) + 5.0).toFloat()
        return if (base != null && dt > 0.0 && dt <= 3.0 && kotlin.math.abs(raw - base) > bound && speedGateStreak == 0) {
            speedGateStreak = 1
            null
        } else {
            speedGateStreak = 0
            speedGateBase = raw
            raw
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
            // With a 0 m distance filter, "no fixes at all for a few seconds" means the GPS went
            // quiet (engine throttled / signal lost while parked) — not that we're moving. Zero
            // the speedometer instead of freezing it at the last (braking) speed; the puck's
            // dead-reckoning already stops at 2 s, so this keeps the readout consistent with it.
            delay(SPEED_ZERO_MS)
            _state.update { if ((it.mySpeed ?: 0f) != 0f) it.copy(mySpeed = 0f, mySpeedRaw = null) else it }
            delay(STALE_LOCATION_MS - SPEED_ZERO_MS)
            _state.update { it.copy(myLocationStale = true) }
        }
    }

    /**
     * Update the posted speed-limit badge for the road under the puck (OSM `maxspeed` from the on-device
     * GraphHopper graph). Cheap-gated: the snap is only re-run once you've moved ~a road segment ([here] >
     * ~18 m from the last computed fix), single-flighted ([limitJob]), and off the main thread. `null`
     * (untagged road / no offline graph / pre-`max_speed` graph) hides the badge; a stale non-null is kept
     * until a new road resolves so it doesn't flicker off between snaps.
     */
    private fun updateSpeedLimit(here: LatLng) {
        val last = lastLimitLoc
        if (last != null && last.distanceTo(here) < 18.0) return
        if (limitJob?.isActive == true) return
        lastLimitLoc = here
        limitJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val kmh = runCatching { routeEngine.currentRoadLimit(here.lat, here.lng) }.getOrNull()
            coroutineContext.ensureActive() // cancelled mid-snap by clearSpeedLimit (stopNav/replay teardown)?
                                            // throw rather than resurrect the badge the teardown just cleared (audit 2026-07-06)
            if (kmh != null) {
                lastLimitHitLoc = here
                if (kmh != _state.value.speedLimitKmh) _state.update { it.copy(speedLimitKmh = kmh) }
            } else if (_state.value.speedLimitKmh != null) {
                // Untagged snap. Keep the last limit across a brief gap between tagged segments, but
                // CLEAR it once we've driven far past where it was last resolved — else turning off a
                // tagged 45 onto an untagged residential street would show a stale 45 forever (worse
                // than blank, since it actively misinforms).
                val hit = lastLimitHitLoc
                if (hit == null || hit.distanceTo(here) > SPEED_LIMIT_FORGET_M) {
                    _state.update { it.copy(speedLimitKmh = null) }
                }
            }
        }
    }

    private var suggestJob: Job? = null
    // Single-flight the search so a slow earlier query can't land AFTER (and overwrite) a newer query's
    // results. Shared by runSearch + searchAlongRoute so a plain and an along-route search cancel each
    // other (audit 2026-07-06). Both cancel it and rethrow CancellationException before their generic catch,
    // else the cancelled coroutine would run the offline-fallback/error state update.
    private var searchJob: Job? = null
    // Single-flight directions so a late reply can't overwrite newer state / resurrect a route the user
    // backed out of. Each route() supersedes the previous; a directionsOpen/mode guard is the belt-and-
    // suspenders for the back-out (audit 2026-07-06). Cancelled by clearRoute/clearSelection.
    private var routeJob: Job? = null

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
            val near = mapCenter ?: _state.value.myLocation // suggestions near the viewport, like search
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
                status = appContext.getString(R.string.mapvm_shortcut_set, kind.label, sp.name),
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
                status = appContext.getString(R.string.mapvm_shortcut_set, kind.label, sp.name),
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

    // Bias to what the user is LOOKING at (the panned viewport), Google-style — so searching after
    // panning to another area returns results THERE, not back at your GPS location. Falls back to GPS
    // before the map has settled a centre.
    fun search() = runSearch(_state.value.query.trim(), mapCenter ?: _state.value.myLocation)

    /** Re-run the current query biased to the area the user has panned to. */
    fun searchThisArea() = runSearch(_state.value.query.trim(), mapCenter)

    /** Map settled after a user pan: offer "Search this area" while results show. */
    fun onCameraIdle(center: LatLng) {
        mapCenter = center
        if (_state.value.results.isNotEmpty() && _state.value.selected == null) {
            _state.update { it.copy(showSearchThisArea = true) }
        }
    }

    /** Is there a usable internet connection right now? Used to skip the Google scrape when offline (it
     *  would only hang to the socket timeout). Fails OPEN — if the check itself errors, assume online so a
     *  quirk can never block search. */
    /** Track connectivity so the UI can show a quiet offline indicator (no more banner). Seeds now and
     *  updates on every network change; fails safe to "online" so a quirk never falsely greys the app. */
    private fun observeConnectivity() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        fun refresh() {
            val off = !isOnline()
            _state.update { if (it.offline != off) it.copy(offline = off) else it }
        }
        refresh()
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = refresh()
                override fun onLost(network: android.net.Network) = refresh()
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) = refresh()
            })
        }
    }

    private fun isOnline(): Boolean = runCatching {
        val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    /** A dropped/absent connection (DNS, no route, timeout) as opposed to a real Google/parse failure —
     *  so search can show the friendly "download an area" offline guidance instead of a raw host error. */
    private fun isConnectivityError(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            if (t is java.net.UnknownHostException || t is java.net.ConnectException ||
                t is java.net.SocketTimeoutException || t is java.net.NoRouteToHostException ||
                t is javax.net.ssl.SSLException
            ) return true
            t = t.cause
        }
        return false
    }

    private fun runSearch(q: String, near: LatLng?) {
        if (q.isEmpty()) return
        suggestJob?.cancel()
        recentStore.add(q)
        _state.update { it.copy(recents = recentStore.recent()) }
        // A search strongly predicts opening a place — warm the detail WebViews now so
        // popular times AND the photo gallery land faster when the user taps a result
        // (both idempotent; the photo warm primes the renderer + HTTP/2 sockets + cache
        // so the first place page skips the cold start).
        viewModelScope.launch { runCatching { webPopularTimes.prewarm() } }
        runCatching { webPhotos.warm() }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true, suggestions = emptyList(), showSearchThisArea = false, resultsCollapsed = false) }
            // No connection → skip the Google scrape entirely (it would just hang to the socket timeout,
            // the "search does nothing offline" report) and search the on-device OSM index straight away.
            // Empty index = no area downloaded yet, so point the user at the download (issue #3).
            if (!isOnline()) {
                val (offline, haveArea) = withContext(Dispatchers.IO) {
                    val pois = runCatching { offlinePoiStore.search(q, near) }.getOrDefault(emptyList())
                    // If it looks like a street address, geocode it too and lead with the address matches.
                    val addrs = if (app.vela.core.data.OfflineAddressStore.looksLikeAddress(q))
                        runCatching { addressStore.geocode(q, near) }.getOrDefault(emptyList()) else emptyList()
                    val merged = (if (addrs.isNotEmpty()) addrs + pois else pois + addrs).distinctBy { it.id }
                    val have = merged.isNotEmpty() ||
                        runCatching { offlinePoiStore.count() > 0 || addressStore.count() > 0 || addressStore.streetCount() > 0 }.getOrDefault(false)
                    merged to have
                }
                _state.update {
                    when {
                        // No "Offline results" banner — the quiet offline indicator (globe-slash + the
                        // greyed "Offline" in the search bar) already says we're offline.
                        offline.isNotEmpty() ->
                            it.copy(results = offline, selected = if (it.pickingOrigin || it.pickingStop) it.selected else null, status = null, searching = false)
                        // Has a downloaded area but nothing matched — don't tell them to download again.
                        haveArea ->
                            it.copy(results = emptyList(), status = appContext.getString(R.string.mapvm_offline_no_match, q), searching = false)
                        else ->
                            it.copy(results = emptyList(), status = appContext.getString(R.string.mapvm_offline_no_data), searching = false)
                    }
                }
                return@launch
            }
            try {
                val res = dataSource.search(q, near)
                _state.update {
                    // Keep the directions DESTINATION (held in `selected`) while picking an origin/stop —
                    // else typing the origin query wiped the "To" and the panel showed an empty
                    // "Destination" with stale routes (the from-here edit cleared where you were going).
                    it.copy(results = res.places, selected = if (it.pickingOrigin || it.pickingStop) it.selected else null, status = null, searching = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // superseded by a newer search — don't run the fallback/error update on a dead job
            } catch (e: CalibrationNeededException) {
                _state.update { it.copy(status = appContext.getString(R.string.mapvm_search_needs_recalibration, e.message), searching = false) }
            } catch (e: Exception) {
                // Network/Google failure → fall back to the offline OSM index (POIs + address geocode, same
                // as the straight-offline branch so an address still resolves when the scrape times out).
                val offline = withContext(Dispatchers.IO) {
                    val pois = runCatching { offlinePoiStore.search(q, near) }.getOrDefault(emptyList())
                    val addrs = if (app.vela.core.data.OfflineAddressStore.looksLikeAddress(q))
                        runCatching { addressStore.geocode(q, near) }.getOrDefault(emptyList()) else emptyList()
                    (if (addrs.isNotEmpty()) addrs + pois else pois + addrs).distinctBy { it.id }
                }
                if (offline.isNotEmpty()) {
                    _state.update { it.copy(results = offline, selected = if (it.pickingOrigin || it.pickingStop) it.selected else null, status = null, searching = false) }
                } else if (isConnectivityError(e)) {
                    // A dead connection: if there's a downloaded area the query just didn't match it, else
                    // point the user at the offline download instead of a raw "Unable to resolve host".
                    val haveArea = withContext(Dispatchers.IO) {
                        runCatching { offlinePoiStore.count() > 0 || addressStore.count() > 0 || addressStore.streetCount() > 0 }.getOrDefault(false)
                    }
                    val msg = if (haveArea) appContext.getString(R.string.mapvm_offline_no_match, q) else appContext.getString(R.string.mapvm_offline_no_data)
                    _state.update { it.copy(status = msg, searching = false) }
                } else {
                    _state.update { it.copy(status = appContext.getString(R.string.mapvm_search_failed_reason, e.message), searching = false) }
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
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
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
                        status = if (along.isEmpty()) appContext.getString(R.string.mapvm_none_found_along_route, query) else null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // superseded — don't run the error update on a dead job
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, status = appContext.getString(R.string.mapvm_search_failed)) }
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
            // Photos STREAM in: the scraper reports the accumulated set whenever it grows, so the
            // strip fills progressively (first partial = the page's hero photos, ~1s after load)
            // instead of waiting ~20s for the full category walk. Monotonic (a partial never
            // shrinks the strip below the search preview) + feature-id/loading gated (a stale
            // partial can't touch the next place; the final result clears the flag in the same
            // atomic copy, so a straggler can't overwrite it — same pattern as review streaming).
            val full = runCatching {
                webPhotos.fetch(fid, onPartial = { part ->
                    if (part.isNotEmpty()) _state.update { st ->
                        val sel = st.selected
                        if (sel?.featureId == fid && st.photosLoading && part.size > sel.photoUrls.size) st.copy(
                            selected = sel.copy(photoUrls = part.map { it.url }, photoDates = part.map { it.postedText }, photoCategories = part.map { it.category }),
                        ) else st
                    }
                })
            }.getOrDefault(emptyList())
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
        // The INLINE reviews are now the native scraped list (smooth, no nested WebView) — always
        // run the scrape. The live Google panel is a separate FULL-SCREEN "read all" view that
        // loads its own reviews on demand, so it no longer suppresses this. ([force] is now moot
        // but kept for the retry path's call sites.)
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
        routeJob?.cancel() // a directions fetch in flight must not resurrect routes after we clear them
        _state.update {
            it.copy(
                selected = null, placesHere = emptyList(), reviews = emptyList(), reviewsLoading = false, reviewsFound = 0, loadingDetails = false,
                routes = emptyList(), activeRoute = null, directionsOpen = false,
                transit = emptyList(), transitLoading = false,
                showSteps = false, previewStepIndex = null,
                directionsWaypoints = emptyList(), pickingStop = false,
            )
        }
        // Opening a place pans the camera to centre it, so the ambient POIs (loaded for the previous
        // centre) can be off-screen once we're back on the bare map. Closing no longer moves the camera
        // (that was the "camera spazz"), so nothing fires a camera-idle to reload them. Do it here.
        refreshAmbientForCurrentView()
    }

    /** Re-evaluate the ambient POIs for whatever the map is currently showing. Used when returning to
     *  the bare map without a camera move (e.g. closing a place). No-op if there's no viewport yet, and
     *  [maybeLoadAmbientPois] keeps its own gates (skips while results/nav/a place are up, only refetches
     *  on a real pan/zoom). */
    private fun refreshAmbientForCurrentView() {
        val vp = viewport ?: return
        val c = mapCenter ?: LatLng((vp[0] + vp[2]) / 2, (vp[1] + vp[3]) / 2)
        val radius = c.distanceTo(LatLng(vp[2], vp[3]))
        maybeLoadAmbientPois(c, vp[4], radius)
    }

    /** Back out of the directions preview to the place sheet: drop the route,
     *  keep the place selected (so back peels one layer at a time). */
    fun clearRoute() {
        destination = null
        routeJob?.cancel() // an in-flight directions fetch must not repopulate the route we're backing out of
        _state.update {
            it.copy(
                routes = emptyList(), activeRoute = null, directionsOpen = false,
                transit = emptyList(), transitLoading = false,
                showSteps = false, previewStepIndex = null,
                directionsOrigin = null, pickingOrigin = false, directionsReversed = false,
                directionsWaypoints = emptyList(), pickingStop = false, pickOnMap = null,
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
        // Capture the placeholder so the async resolve can gate on FULL equality (name AND location) — two
        // same-named POIs tapped in quick succession (a chain's two branches) otherwise let the slower
        // resolve for the first hijack the second's sheet, since the old gate matched name only (audit 2026-07-06).
        val placeholder = Place(id = "poi:" + name.hashCode(), name = name, location = location)
        _state.update {
            it.copy(
                selected = placeholder,
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
            if (full != null && _state.value.selected == placeholder) {
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
        // "Choose on map" is active → a long-press sets that endpoint directly (the quick half of the
        // crosshair flow) instead of dropping a destination pin.
        val pick = _state.value.pickOnMap
        if (pick != null) {
            viewModelScope.launch {
                val place = runCatching { dataSource.reverseGeocode(location) }.getOrNull()
                    ?: Place(id = "pin:${location.lat},${location.lng}", name = appContext.getString(R.string.mapvm_dropped_pin), location = location)
                when (pick) {
                    MapPick.ORIGIN -> setDirectionsOrigin(place)
                    MapPick.STOP -> addStop(place)
                }
            }
            return
        }
        reviewsJob?.cancel() // a pin never fetches reviews — free the old scrape's WebView/mutex
        _state.update {
            it.copy(
                selected = Place(id = "pin:${location.lat},${location.lng}", name = appContext.getString(R.string.mapvm_dropped_pin), location = location),
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
        _state.update { it.copy(directionsOrigin = p, pickingOrigin = false, pickOnMap = null) }
        route(_state.value.travelMode)
    }

    /** "Choose on map" for an endpoint — leave the search overlay, show a center crosshair over the
     *  live map, and set that endpoint from wherever the map is centred (or a long-press) on confirm. */
    fun chooseOriginOnMap() = _state.update { it.copy(pickingOrigin = false, pickOnMap = MapPick.ORIGIN) }
    fun chooseStopOnMap() = _state.update { it.copy(pickingStop = false, pickOnMap = MapPick.STOP) }
    fun cancelChooseOnMap() = _state.update { it.copy(pickOnMap = null) }

    /** Confirm the crosshair pick: reverse-geocode the map's current centre and set it as the
     *  origin/stop (falls back to a bare pin if the geocode misses so the endpoint is still set). */
    fun confirmMapPick() {
        val target = _state.value.pickOnMap ?: return
        val at = mapCenter ?: return
        viewModelScope.launch {
            val place = runCatching { dataSource.reverseGeocode(at) }.getOrNull()
                ?: Place(id = "pin:${at.lat},${at.lng}", name = appContext.getString(R.string.mapvm_dropped_pin), location = at)
            when (target) {
                MapPick.ORIGIN -> setDirectionsOrigin(place)
                MapPick.STOP -> addStop(place)
            }
        }
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
        _state.update { it.copy(directionsWaypoints = it.directionsWaypoints + p, pickingStop = false, pickOnMap = null) }
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
        // Guard: this reply is only applied if directions is still open for the SAME mode (the user hasn't
        // backed out or switched away while it was fetching). Mirrors routeTransit's stale-load guard.
        fun stillWanted() = _state.value.directionsOpen && _state.value.travelMode == mode
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            try {
                val routes = dataSource.directions(origin, dest, mode, stops)
                if (!stillWanted()) return@launch // backed out / switched mode mid-fetch — don't resurrect it
                _state.update {
                    it.copy(
                        routes = routes,
                        activeRoute = routes.firstOrNull(),
                        transit = emptyList(), transitLoading = false,
                        status = if (routes.isEmpty()) appContext.getString(R.string.mapvm_no_mode_route_found, mode.name.lowercase()) else null,
                    )
                }
                // The default active route can be a PROVISIONAL Google alternate (it sorts to the
                // top when it has the fastest live ETA). A provisional route carries Google's
                // ABBREVIATED steps + an ETA over un-snapped geometry — so the pre-nav preview showed
                // wrong turns/ETA that only "corrected" when Start named it. Name it NOW (OSRM snap +
                // re-applied traffic), exactly as picking an alternate does, so preview == nav.
                if (routes.firstOrNull()?.provisional == true) selectRoute(0)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // superseded by a newer route()/cleared — don't touch state on a dead job
            } catch (e: CalibrationNeededException) {
                if (stillWanted()) _state.update { it.copy(status = appContext.getString(R.string.mapvm_directions_need_recalibration, e.message)) }
            } catch (e: Exception) {
                if (stillWanted()) _state.update { it.copy(status = appContext.getString(R.string.mapvm_routing_failed_reason, e.message)) }
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
                    status = if (trips.isEmpty()) appContext.getString(R.string.mapvm_no_transit_routes) else null,
                )
            }
        }
    }

    fun startNav() {
        val route = _state.value.activeRoute ?: return
        viewModelScope.launch {
            // If they hit Start before a picked alternate finished naming, name it first.
            val named = if (route.provisional) nameIfNeeded(route).also { _state.update { s -> s.copy(activeRoute = it) } } else route
            // Optional Google-style "pass the light, then turn" landmark clauses (off by default) — fetch the
            // route's traffic signals once + fold the clauses into its turns before the session starts.
            val enriched = enrichLightsIfEnabled(named)
            if (enriched !== named) _state.update { it.copy(activeRoute = enriched) }
            // Demo / screenshot / test mode (Settings → Navigation): drive the route as a SYNTHETIC GPS
            // trace instead of using the real fix, so nav can be shown/tested anywhere (a Davis route
            // while the phone is elsewhere). Same replay pipeline as a recorded trip.
            if (settingsPrefs.getBoolean("demo_drive", false)) startDemoDrive(enriched) else launchNav(enriched)
        }
    }

    /** Drive [route] as a synthetic GPS trace ([DemoTrace] → the recorded-trip [LocationProvider.replay]
     *  path), so navigation runs with NO real fix — for demos, screenshots and testing nav anywhere.
     *  Reuses the replay machinery wholesale (hermetic nav, puck physics, camera, voice); the synthetic
     *  fixes are clean (monotonic time, real speed/bearing) so they skip the outlier/standstill gating a
     *  recorded trace needs. Ends like a replay: live GPS resumes, the route/dot reset. */
    private fun startDemoDrive(route: app.vela.core.model.Route) {
        val dest = destination ?: route.polyline.lastOrNull() ?: return
        val fixes = app.vela.core.location.DemoTrace.fromRoute(route.polyline)
        if (fixes.size < 2) { flashStatus(appContext.getString(R.string.mapvm_no_track_to_replay)); return }
        replayJob?.cancel()
        if (replayOwnsNav) { navSession.stop(); replayOwnsNav = false; destination = null }
        locationJob?.cancel(); locationJob = null // synthetic trace owns the puck — no live fixes
        staleTimerJob?.cancel(); staleTimerJob = null
        val resumeLoc = _state.value.myLocation
        _state.update { it.copy(replaying = true, demoDriving = true, navCameraDetached = false) }
        val label = _state.value.selected?.name.orEmpty()
        val job = viewModelScope.launch {
            try {
                destination = dest
                val engine = _state.value.selectedEngine?.packageName
                neuralSynthFor(engine)?.let { voice.neural = it }
                navSession.replayMode = true
                navSession.start(route, dest, label, engine)
                replayOwnsNav = true
                locationProvider.replay(fixes, speedup = 1f).collect { loc ->
                    if (replayJob !== coroutineContext[Job]) return@collect // superseded
                    val here = LatLng(loc.latitude, loc.longitude)
                    _state.update {
                        it.copy(
                            myLocation = here, myBearing = loc.bearing, mySpeed = loc.speed,
                            mySpeedRaw = loc.speed, center = here, myLocationStale = false,
                        )
                    }
                    navSession.onLocation(here, app.vela.ui.Units.imperial.value, loc.speed.toDouble())
                    updateSpeedLimit(here)
                }
            } finally {
                if (replayJob === coroutineContext[Job]) {
                    replayJob = null
                    navSession.replayMode = false
                    if (replayOwnsNav) { navSession.stop(); replayOwnsNav = false; destination = null }
                    clearSpeedLimit()
                    _state.update {
                        it.copy(
                            replaying = false, demoDriving = false, speedLimitKmh = null,
                            routes = emptyList(), activeRoute = null, directionsOpen = false,
                            showSteps = false, previewStepIndex = null,
                            myLocation = resumeLoc ?: it.myLocation,
                        )
                    }
                    startLocation()
                }
            }
        }
        replayJob = job
    }

    /** Fold traffic-light landmark clauses into [route]'s turns if Settings → Navigation has it on (else no-op,
     *  no network). Best-effort + IO; a fetch miss just leaves the route unchanged. */
    private suspend fun enrichLightsIfEnabled(route: app.vela.core.model.Route): app.vela.core.model.Route {
        if (!settingsPrefs.getBoolean("nav_traffic_lights", false)) return route
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val signals = app.vela.core.data.OverpassTrafficSignals.fetchAlong(http, route.polyline)
            app.vela.core.data.RouteGeometry.enrichWithLights(route, signals)
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
        persistNav(dest, s.selected?.name.orEmpty(), s.travelMode) // so a process-kill mid-drive can resume
        if (_state.value.resumeNavLabel != null) _state.update { it.copy(resumeNavLabel = null) } // starting fresh clears any stale offer
        // Record this trip's GPS trace for later replay, if the user opted in. Read
        // the pref directly so it works even before Settings has been opened.
        if (settingsPrefs.getBoolean("trip_recording_on", false)) {
            tripStore.startTrip(_state.value.selected?.name ?: appContext.getString(R.string.mapvm_trip_default_name), dest, System.currentTimeMillis())
            tripStore.saveRoute(route) // save the blue line + maneuvers so a replay drives THIS route
        }
        // If the phone has no voice engine, say so once instead of going silent.
        if (voice.availableEngines().isEmpty()) {
            showStatus(appContext.getString(R.string.mapvm_no_voice_engine))
        }
    }

    fun stopNav() {
        // A replay OR a demo drive owns nav through the replay job — "End" (and the back gesture, which
        // also routes here) must end the REPLAY, not run live-nav teardown: stopReplay cancels replayJob
        // whose finally does the full owned-nav teardown (replayMode off, navSession.stop, route/dot/camera
        // restore, live-GPS resume) and never clears a real drive's persisted resume prefs. Covers demo
        // (demoDriving ⟹ replaying && replayOwnsNav). Was demoDriving-only, so a recorded-trip replay's End
        // ran live teardown and left the replay job running (audit 2026-07-06).
        if (_state.value.replaying && replayOwnsNav) { stopReplay(); return }
        NavigationService.stop(appContext)
        navSession.stop()
        tripStore.finishTrip() // close + persist the recorded trip (drops too-short ones)
        clearSpeedLimit() // clear the speed-limit badge for the next drive
        clearPersistedNav() // this drive is over → don't offer to resume it next launch
        _state.update { it.copy(showSteps = false, previewStepIndex = null, navCameraDetached = false, speedLimitKmh = null) }
    }

    /** Reset the speed-limit badge + its throttle state (shared by nav-stop and replay-teardown so the
     *  next drive/replay starts clean — else a stale limit could flash near the last drive's end point). */
    private fun clearSpeedLimit() {
        limitJob?.cancel()
        lastLimitLoc = null
        lastLimitHitLoc = null
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
        if (fixes.size < 2) { flashStatus(appContext.getString(R.string.mapvm_no_track_to_replay)); return }
        replayJob?.cancel()
        // A superseded replay's stale finally no-ops (the job guard fails below), so tear
        // down any nav IT auto-started here, before this new replay starts its own.
        if (replayOwnsNav) { navSession.stop(); replayOwnsNav = false; destination = null }
        locationJob?.cancel(); locationJob = null // pause live GPS while the trace plays
        // Also kill any pending stale-location timer armed by the last live fix — otherwise it can fire
        // ~seconds into the replay and flip myLocationStale=true, briefly greying the replay puck / hiding
        // its arrow until the next trace fix clears it. The replay collector sets stale=false per fix.
        staleTimerJob?.cancel(); staleTimerJob = null
        // The user's real position BEFORE the trace took over — restored on teardown so exiting the replay
        // snaps the dot back off the trace's end point to (approximately) where they are; the resumed live
        // GPS refines it on the next fix.
        val resumeLoc = _state.value.myLocation
        _state.update { it.copy(replaying = true, navCameraDetached = false) }
        flashStatus(appContext.getString(R.string.mapvm_replaying, meta.label), 3000L)
        val job = viewModelScope.launch {
            try {
                // Drive turn-by-turn during the replay without manually starting nav first.
                // Prefer the route SAVED with the trip (the exact blue line the user drove) so the
                // cards/voice replay identically and any divergence is real, not a re-route
                // artifact; fall back to a fresh route for older trips that predate route-saving.
                // Best-effort (the replay still plays if both fail), skipped if nav's already active.
                // Segment-aware: the trip records every route the drive actually used (start +
                // each reroute/faster-route swap as its own RP/RD/M block). The replay starts on
                // the FIRST route and swaps at the recorded fix positions — HERMETICALLY: no live
                // fetches (replayMode suppresses reroute + the faster-route recheck; a live fetch
                // used to swap the route mid-replay and match the trace against a route the
                // driver never drove — arrow on another street, faster-route sheet over a replay).
                val segments = tripStore.rawCsv(meta.id)
                    ?.let { app.vela.core.replay.TripLog.parse(it).segments }
                    .orEmpty()
                if (!navSession.state.value.navigating) {
                    val saved = segments.firstOrNull()?.route
                    val route = saved ?: meta.dest?.let { d ->
                        val from = LatLng(fixes.first().lat, fixes.first().lng)
                        runCatching { dataSource.directions(from, d, TravelMode.DRIVE) }.getOrNull()?.firstOrNull()
                    }
                    val dest = meta.dest ?: route?.polyline?.lastOrNull()
                    if (route != null && dest != null) {
                        destination = dest
                        // Replay must speak through the SAME engine as live nav — the user's selected
                        // voice (e.g. the Vela neural voice), not null → which fell back to the system
                        // TTS while still applying the voice-speed pref (the "GrapheneOS voice at 0.8×"
                        // bug). Wire the neural synth too, in case the pick changed since launch.
                        val engine = _state.value.selectedEngine?.packageName
                        neuralSynthFor(engine)?.let { voice.neural = it }
                        navSession.replayMode = true
                        navSession.start(route, dest, meta.label, engine)
                        replayOwnsNav = true
                    }
                }
                val swapAt = segments.drop(1).associateBy({ it.fromPoint }, { it.route })
                val pts = fixes.map { app.vela.core.location.ReplayFix(it.lat, it.lng, it.t, it.bearing, it.speed) }
                var lastReplayT = 0L
                var fixIdx = 0
                val posOutlierStreak = intArrayOf(0)
                locationProvider.replay(pts, speedup = REPLAY_SPEEDUP).collect { loc ->
                    // Play back the drive's own route swaps at the fix where they happened.
                    swapAt[fixIdx]?.let { if (replayOwnsNav) navSession.replaySetRoute(it) }
                    fixIdx += 1
                    val rawHere = LatLng(loc.latitude, loc.longitude)
                    val prev = _state.value.myLocation
                    val dt = if (lastReplayT > 0L) (loc.time - lastReplayT) / 1000.0 else -1.0
                    lastReplayT = loc.time
                    // Same outlier-reject + standstill-hold as live, so a recorded NETWORK leap
                    // doesn't jump the dot / distance / mph on replay either.
                    val here = sanePosition(rawHere, prev, _state.value.mySpeed, dt, posOutlierStreak)
                    val bearing = if (loc.hasBearing() && loc.speed > 0.5f) loc.bearing else _state.value.myBearing
                    // Same symmetric plausibility gate as live GPS — recorded traces carry the raw
                    // glitches (35→157 hops AND one-fix dropouts to 0), and the old one-sided
                    // filter here had no escape at all: one recorded down-glitch latched the
                    // whole rest of the replay at 0 (dead Kalman, camera pinned zoomed-in).
                    val measured = if (loc.hasSpeed()) gateMeasuredSpeed(loc.speed, dt.coerceAtLeast(0.0)) else null
                    val speed = measured ?: _state.value.mySpeed
                    _state.update {
                        it.copy(
                            myLocation = here, myBearing = bearing, mySpeed = speed,
                            // Replay fixes carry the recorded doppler — feed the puck Kalman the
                            // same way live does, or the replay puck never seeds (no gliding,
                            // no speed-scaled zoom/gates: replays looked worse than real drives).
                            mySpeedRaw = measured,
                            center = here, myLocationStale = false,
                        )
                    }
                    navSession.onLocation(here, app.vela.ui.Units.imperial.value, speed?.toDouble())
                    updateSpeedLimit(here) // posted-limit badge during replay too (local graph read)
                }
            } finally {
                // Only the current replay tears down: a superseded one was already stopped
                // above, so this stale finally (job guard false) no-ops.
                if (replayJob === coroutineContext[Job]) {
                    replayJob = null
                    navSession.replayMode = false
                    val ownedNav = replayOwnsNav
                    if (replayOwnsNav) { navSession.stop(); replayOwnsNav = false; destination = null }
                    clearSpeedLimit() // mirror stopNav — don't leak the replay's last limit into the next drive
                    _state.update {
                        if (ownedNav) {
                            // The replay owned the route + drove the dot. Tear BOTH down: drop the replayed
                            // blue line (the navSession→state observer keeps activeRoute once nav stops, so it
                            // must be nulled here or the line stayed drawn), clear the step preview, and snap
                            // the dot/camera back to the user's real pre-replay location off the trace's end.
                            it.copy(
                                replaying = false, speedLimitKmh = null,
                                routes = emptyList(), activeRoute = null, directionsOpen = false,
                                showSteps = false, previewStepIndex = null,
                                myLocation = resumeLoc ?: it.myLocation,
                                center = resumeLoc ?: it.center,
                            )
                        } else {
                            // Replay rode an already-active nav session — leave its route/location alone.
                            it.copy(replaying = false, speedLimitKmh = null)
                        }
                    }
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
                putExtra(android.content.Intent.EXTRA_SUBJECT, appContext.getString(R.string.mapvm_export_saved_subject, places.size))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            android.content.Intent.createChooser(send, appContext.getString(R.string.mapvm_export_saved_chooser))
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
                putExtra(android.content.Intent.EXTRA_SUBJECT, appContext.getString(R.string.mapvm_export_trip_subject, meta.label, meta.fixCount))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            android.content.Intent.createChooser(send, appContext.getString(R.string.mapvm_share_trip_chooser))
                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        }.getOrNull()
    }

    /** Dismiss the arrival summary and return to a clean map (drops the finished
     *  route + selection). */
    fun finishNav() {
        stopNav()
        clearSelection()
    }

    // --- nav resume across process death -----------------------------------------------------------
    /** Persist the active drive's DESTINATION so the next launch can offer to resume if the process was
     *  reaped mid-drive. Called on start + kept fresh through a resumed session. */
    private fun persistNav(dest: LatLng, label: String, mode: TravelMode) {
        navResumePrefs.edit()
            .putFloat("lat", dest.lat.toFloat()).putFloat("lng", dest.lng.toFloat())
            .putString("label", label).putString("mode", mode.name)
            .putLong("at", System.currentTimeMillis())
            .apply()
    }

    /** Nav ended (stopped/arrived/dismissed) → forget the resume target so it isn't offered next launch. */
    private fun clearPersistedNav() {
        resumeDest = null
        lastNavHeartbeatMs = 0L // next drive's heartbeat starts fresh
        navResumePrefs.edit().clear().apply()
        if (_state.value.resumeNavLabel != null) _state.update { it.copy(resumeNavLabel = null) }
    }

    /** On launch: a nav session persisted recently (process reaped mid-drive) → stash it + raise the
     *  "Resume navigation?" prompt. Stale (older than [RESUME_MAX_AGE_MS], i.e. that drive is long over) →
     *  clear it silently. Called from init. */
    private fun maybeOfferResume() {
        val at = navResumePrefs.getLong("at", 0L)
        if (at == 0L) return
        if (System.currentTimeMillis() - at > RESUME_MAX_AGE_MS) { clearPersistedNav(); return }
        val lat = navResumePrefs.getFloat("lat", Float.NaN); val lng = navResumePrefs.getFloat("lng", Float.NaN)
        if (lat.isNaN() || lng.isNaN()) { clearPersistedNav(); return }
        resumeDest = LatLng(lat.toDouble(), lng.toDouble())
        resumeMode = runCatching { TravelMode.valueOf(navResumePrefs.getString("mode", null) ?: "DRIVE") }
            .getOrDefault(TravelMode.DRIVE)
        _state.update { it.copy(resumeNavLabel = navResumePrefs.getString("label", "") ?: "") }
    }

    /** User tapped "Resume": re-route from the CURRENT fix to the saved destination + start nav afresh
     *  (a fresh route handles however far you drove while the app was gone, and any traffic since). */
    fun resumeNav() {
        val dest = resumeDest ?: return
        val label = _state.value.resumeNavLabel.orEmpty()
        val mode = resumeMode
        val origin = _state.value.myLocation
        if (origin == null) { showStatus(appContext.getString(R.string.mapvm_resume_waiting_gps)); return }
        _state.update { it.copy(resumeNavLabel = null) }
        viewModelScope.launch {
            val routes = runCatching { dataSource.directions(origin, dest, mode, emptyList()) }.getOrDefault(emptyList())
            var route = routes.firstOrNull()
            if (route?.provisional == true) route = nameIfNeeded(route)
            if (route == null) { showStatus(appContext.getString(R.string.mapvm_resume_failed)); clearPersistedNav(); return@launch }
            destination = dest
            _state.update { it.copy(activeRoute = route, routes = routes) }
            startLocation()
            navSession.start(route, dest, label, _state.value.selectedEngine?.packageName, emptyList(), mode)
            NavigationService.start(appContext)
            persistNav(dest, label, mode) // keep it persisted through the resumed drive
        }
    }

    /** User dismissed the resume prompt — forget it. */
    fun dismissResume() = clearPersistedNav()

    fun acceptFasterRoute() = navSession.acceptFasterRoute()

    fun dismissFasterRoute() = navSession.dismissFasterRoute()

    fun setStyle(style: MapStyle) =
        _state.update { it.copy(styleUri = style.uri, styleName = style.label) }

    fun voiceEngines(): List<VoiceEngine> = voice.availableEngines()

    /** The in-process synth backing a Vela neural engine id (else null for a system TTS engine). */
    private fun neuralSynthFor(engineId: String?): PiperSynth? =
        if (engineId == VelaPiper.ENGINE_ID) piperSynth else null
    private fun velaLabel(engineId: String): String? =
        if (engineId == VelaPiper.ENGINE_ID) VelaPiper.LABEL else null

    fun setVoiceEngine(e: VoiceEngine) {
        neuralSynthFor(e.packageName)?.let { voice.neural = it; it.warmUp() } // point VoiceGuide at the right synth
        voice.init(e.packageName) // re-init now so the pick applies + a test plays through it
        settingsPrefs.edit().putString("voice_engine", e.packageName).apply() // survive restart
        _state.update { it.copy(selectedEngine = e) }
    }

    fun testVoice() = voice.test()

    /** Whether a Vela neural voice model is downloaded + usable. */
    fun neuralVoiceInstalled(): Boolean = VelaPiper.isReady(appContext)
    fun piperInstalled(): Boolean = VelaPiper.isReady(appContext)

    /** Voice playground: speak arbitrary text through the currently-selected voice. */
    fun speakText(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) voice.speak(t, interrupt = true)
    }

    /** Speakers in the SELECTED Vela voice (from the catalog, so it's correct synchronously the instant
     *  you switch — the live-loaded [PiperSynth.numSpeakers] lags a background reload). 1 for single-
     *  speaker voices; the variant picker only shows when this is > 1. */
    fun voiceSpeakerCount(): Int =
        _state.value.selectedVoiceId?.let { PiperCatalog.byId(it)?.numSpeakers }
            ?: piperSynth.numSpeakers

    /** The saved (or seeded) speaker index for [id]'s per-voice key — matches [PiperSynth.speakerId].
     *  Reads prefs straight from [appContext] (not the `settingsPrefs` property) so it's safe to call
     *  from `init`, before that property's initializer has run. */
    private fun savedSpeakerFor(id: String?): Int {
        if (id == null) return 0
        val prefs = appContext.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
        val seed = if (id == VelaPiper.LEGACY_ID) calibration.current().defaultVoiceSpeaker else 0
        val max = PiperCatalog.byId(id)?.numSpeakers ?: 0
        val n = prefs.getInt(VelaPiper.speakerKey(id), seed)
        return if (max > 0) n.coerceIn(0, max - 1) else n.coerceAtLeast(0)
    }

    /** Step the multi-speaker Vela voice by [delta], persist it, and speak a sample so it's heard. */
    fun stepSpeaker(delta: Int) = setSpeaker(_state.value.voiceSpeaker + delta)

    /** Jump the multi-speaker Vela voice straight to speaker [n] (clamped to the model's range),
     *  persist it PER VOICE, and speak a sample. Lets the user type a variant number instead of stepping. */
    fun setSpeaker(n: Int) {
        val id = _state.value.selectedVoiceId ?: return // no voice installed → nothing to set
        val max = voiceSpeakerCount()
        val clamped = if (max > 0) n.coerceIn(0, max - 1) else n.coerceAtLeast(0)
        settingsPrefs.edit().putInt(VelaPiper.speakerKey(id), clamped).apply()
        _state.update { it.copy(voiceSpeaker = clamped) }
        voice.speak(appContext.getString(R.string.mapvm_voice_sample), interrupt = true)
    }

    /** Adjust the spoken-directions speed by [delta] (clamped 0.5–2.0×), persist, apply, and preview. */
    fun setVoiceSpeed(delta: Float) {
        var s = (_state.value.voiceSpeed + delta).coerceIn(0.5f, 2.0f)
        s = Math.round(s * 20f) / 20f // snap to 0.05 so it can't drift off exactly 1.00
        settingsPrefs.edit().putFloat("voice_speed", s).apply()
        voice.setRate(s) // AOSP engine; the neural voice reads the voice_speed pref per utterance
        _state.update { it.copy(voiceSpeed = s) }
        voice.speak(appContext.getString(R.string.mapvm_voice_sample), interrupt = true)
    }

    // ---- Voice library (the in-app Piper voice browser) --------------------------------------------

    /** The browsable catalog of downloadable Piper voices. */
    fun voiceCatalog(): List<PiperVoice> = PiperCatalog.ALL

    /** Re-derive installed voices + the active selection + its speaker from disk (after any change). */
    private fun refreshInstalledVoices() {
        val active = VelaPiper.effectiveVoiceId(appContext)
        _state.update {
            it.copy(
                installedVoiceIds = VelaPiper.installedVoiceIds(appContext).toSet(),
                selectedVoiceId = active,
                voiceSpeaker = savedSpeakerFor(active),
            )
        }
    }

    /** Download one catalog voice into its own subdir. One-at-a-time (the installer uses fixed temp
     *  paths). Auto-activates the neural engine + selects the voice ONLY when it's the first voice ever
     *  installed (so a user auditioning extra voices, or deliberately on a system TTS engine, isn't
     *  hijacked off their current voice). */
    fun downloadVoice(id: String) {
        if (_state.value.voiceDownloadingId != null) return // serialize
        val v = PiperCatalog.byId(id) ?: return
        // Cheap disk pre-flight (models are 67–131 MB) — fail early with a clear message, not late.
        if (appContext.filesDir.usableSpace < v.sizeBytes * 13 / 10) {
            showStatus(appContext.getString(R.string.mapvm_not_enough_space, v.displayName, v.sizeMb))
            return
        }
        val firstEver = VelaPiper.installedVoiceIds(appContext).isEmpty()
        _state.update { it.copy(voiceDownloadingId = id, kokoroDownloadPct = 0f, voiceInstalling = false) }
        viewModelScope.launch {
            val ok = kokoroInstaller.download(
                PiperCatalog.downloadUrl(id), VelaPiper.modelDirFor(appContext, id), v.sizeBytes,
                onExtracting = { _state.update { if (it.voiceDownloadingId == id) it.copy(voiceInstalling = true) else it } },
            ) { p -> _state.update { if (it.voiceDownloadingId == id) it.copy(kokoroDownloadPct = p) else it } }
            // Clear the downloading state + refresh the installed set in ONE update (no "Download"
            // flicker between finishing and appearing installed).
            _state.update {
                it.copy(
                    voiceDownloadingId = null, kokoroDownloadPct = null, voiceInstalling = false,
                    installedVoiceIds = VelaPiper.installedVoiceIds(appContext).toSet(),
                    selectedVoiceId = VelaPiper.effectiveVoiceId(appContext),
                )
            }
            if (ok && VelaPiper.isVoiceReady(appContext, id)) {
                if (firstEver) selectVoice(id) else flashStatus(appContext.getString(R.string.mapvm_voice_downloaded, v.displayName))
            } else {
                showStatus(appContext.getString(R.string.mapvm_voice_download_failed, v.displayName))
            }
        }
    }

    /** Make an already-downloaded voice active: persist the pick, reload the synth (the single switch
     *  trigger), point the engine at the neural synth, and audition a nav sample. */
    fun selectVoice(id: String) {
        if (!VelaPiper.isVoiceReady(appContext, id)) return
        VelaPiper.setSelectedVoiceId(appContext, id)
        piperSynth.reloadVoice() // THE build of the new voice (race-free; runs first on the worker)
        setVoiceEngine(VoiceEngine(VelaPiper.ENGINE_ID, VelaPiper.LABEL)) // route VoiceGuide→neural + persist engine
        refreshInstalledVoices() // selectedVoiceId + per-voice speaker for the variant UI
        voice.speak(appContext.getString(R.string.mapvm_voice_sample), interrupt = true) // audition
    }

    /** Delete a downloaded voice, reclaiming its disk. Deleting the ACTIVE voice falls to another
     *  installed voice, else to a system TTS engine. Safe mid-nav: the synth is switched off the files
     *  (or released) before the dir is unlinked on the synth's worker thread. */
    fun deleteVoice(id: String) {
        val wasActive = VelaPiper.effectiveVoiceId(appContext) == id
        val dir = VelaPiper.modelDirFor(appContext, id)
        settingsPrefs.edit().remove(VelaPiper.speakerKey(id)).apply()
        // Drop it from the UI IMMEDIATELY (optimistic): the actual unlink is async (worker/IO), and
        // re-reading the registry before it finishes would leave the deleted voice looking installed —
        // that was the "still had the trash icon" bug when deleting the active voice.
        fun hide() = _state.update { it.copy(installedVoiceIds = it.installedVoiceIds - id) }
        hide()
        if (wasActive) {
            val next = VelaPiper.installedVoiceIds(appContext).firstOrNull { it != id }
            if (next != null) {
                selectVoice(next) // reloads the synth onto `next` (off `id`'s files); refreshes state, then:
                piperSynth.deleteModelDir(dir) // unlink `id` on the worker, after the reload
                hide() // selectVoice's refresh re-read the (still-present) dir → hide `id` again
            } else {
                VelaPiper.clearSelectedVoice(appContext)
                piperSynth.release() // no neural voice left → drop the engine
                piperSynth.deleteModelDir(dir)
                // Fall back to a system TTS engine if one is installed, else leave nav silent.
                voiceEngines().firstOrNull { it.packageName != VelaPiper.ENGINE_ID }?.let { setVoiceEngine(it) }
                _state.update { it.copy(installedVoiceIds = it.installedVoiceIds - id, selectedVoiceId = null) }
                flashStatus(appContext.getString(R.string.mapvm_vela_voice_removed))
            }
        } else {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                dir.deleteRecursively()
                withContext(kotlinx.coroutines.Dispatchers.Main) { refreshInstalledVoices() }
            }
        }
    }

    /** Onboarding's one-tap install — grabs a voice that MATCHES the app language (so a French phone
     *  gets a French voice + French nav text out of the box), falling back to the remote-settable fleet
     *  default (HFC) for English. As the first voice, it's activated. */
    fun downloadPiper() {
        downloadVoice(defaultVoiceId())
    }

    /** The Vela voice a fresh install downloads — the fleet default (calibration) for English,
     *  else the app-language's recommended voice. */
    private fun defaultVoiceId(): String {
        val lang = app.vela.ui.AppLocale.effective().language
        return if (lang == "en") calibration.current().defaultVoiceId else PiperCatalog.defaultFor(lang).id
    }

    /** Download size (MB) of the voice [downloadPiper] would fetch — so the onboarding prompt shows
     *  the REAL size (it used to hardcode the long-gone 126 MB Kokoro model). */
    fun defaultVoiceSizeMb(): Int = PiperCatalog.byId(defaultVoiceId())?.sizeMb ?: 67

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
            flashStatus(result ?: appContext.getString(R.string.mapvm_opening_installer, engine.label))
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
        val center = LatLng((south + north) / 2, (west + east) / 2)
        // onViewport fires on EVERY camera idle (unlike onCameraIdle, which is gesture-gated and can
        // miss a pan due to a camera-reason race). Keep the "Search this area" center = the live
        // viewport center here so the search can never bias to a stale, pre-pan location.
        mapCenter = center
        refreshBuildingOverlays(center) // stream the building overlay for whatever region is now in view
        refreshAddressOverlays(center) // + house-number labels for that region
        refreshTrafficControls(south, west, north, east, zoom) // + traffic lights / stop signs at high zoom
        // Half-diagonal of the visible box — used to hand the map only the POIs near the view (the
        // rest can't render anyway), so an old budget phone isn't dragging 800 symbols through the
        // collider every frame.
        val viewRadius = center.distanceTo(LatLng(north, east))
        maybeLoadAmbientPois(center, zoom, viewRadius)
    }

    private var ambientJob: Job? = null
    private var lastAmbientCenter: LatLng? = null
    private var lastAmbientZoom = 0.0
    // LRU (most-recent last, cap 16) of recent ambient fetches — revisiting ANY of the last ~16 areas
    // repaints POIs INSTANTLY (the ~2 s Google floor only hits genuinely-new areas), with no empty-map
    // gap or OSM-POI "small then pop bigger" flash. Entries expire after 30 min so a closed shop doesn't
    // linger all session. Triple = (fetch centre, ranked places, capturedAt elapsedRealtime ms).
    private val ambientCache = ArrayDeque<Triple<LatLng, List<app.vela.core.model.Place>, Long>>()

    private fun cacheAmbient(center: LatLng, places: List<app.vela.core.model.Place>) {
        ambientCache.removeAll { it.first.distanceTo(center) < 400.0 } // replace a near-duplicate area
        ambientCache.addLast(Triple(center, places, android.os.SystemClock.elapsedRealtime()))
        while (ambientCache.size > 16) ambientCache.removeFirst()
    }

    /** Freshest non-stale cached fetch whose centre is within ~900 m of [center], re-centred so its
     *  distances are correct for the new view. Null if nothing recent+near is cached. */
    private fun cachedAmbientNear(center: LatLng): List<app.vela.core.model.Place>? {
        val now = android.os.SystemClock.elapsedRealtime()
        return ambientCache
            .filter { now - it.third < 30 * 60_000L && it.first.distanceTo(center) < 900.0 }
            .minByOrNull { it.first.distanceTo(center) }
            ?.second
            ?.map { it.copy(distanceMeters = center.distanceTo(it.location)) }
    }

    /**
     * Ambient Google POIs: on a bare, zoomed-in browse map, fetch the prominent Google places for
     * the visible area and show them as category dots — so Google-only spots (not in the OSM
     * basemap) appear without searching. The query viewport TRACKS the map zoom (zoom in → tighter
     * box → denser, more local results, like Google), and the dots are CLEARED when you zoom out
     * past neighbourhood level (they'd be sparse + cluttered over a huge area). Tightly gated:
     * bare map only (no results / open place / nav / replay), debounced, re-queried on a real pan
     * OR zoom change.
     */
    private fun maybeLoadAmbientPois(center: LatLng, zoom: Double, viewRadiusMeters: Double = 0.0) {
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
        // Any recent nearby fetch cached (e.g. an area you already visited this session)? Repaint it
        // INSTANTLY so there's no empty→OSM-POI flash→ambient "small then pop bigger" while the network
        // fetch below runs; the fetch then refines it.
        if (s.ambientPois.isEmpty()) {
            cachedAmbientNear(center)?.let { cached ->
                _state.update { it.copy(ambientPois = keepAmbientForView(cached, viewRadiusMeters)) }
            }
        }
        ambientJob = viewModelScope.launch {
            delay(300) // brief settle so a flick doesn't scrape — but snappy
            val res = runCatching { dataSource.nearbyPlaces(center, span) }.getOrNull() ?: return@launch
            lastAmbientCenter = center
            lastAmbientZoom = zoom
            cacheAmbient(center, res)
            // Re-check we're still on the bare map — the user may have searched/opened a place while we fetched.
            val cur = _state.value
            if (cur.navigating || cur.replaying || cur.results.isNotEmpty() || cur.selected != null) return@launch
            _state.update { it.copy(ambientPois = keepAmbientForView(res, viewRadiusMeters)) }
        }
    }

    /** The on-screen ambient set the map layer renders: POIs NEAR the view (a prominence-weighted
     *  keep-radius — anchors survive farther off-centre, like Google) capped at [AMBIENT_ONSCREEN_CAP]
     *  so a budget GPU isn't colliding the whole ~3.5 km pool each drag frame. Off-screen POIs can't
     *  paint anyway. Preserves `res`'s prominence order (the ambient layer's collision key = index),
     *  so the anchor store still beats its in-store tenant. */
    private fun keepAmbientForView(res: List<app.vela.core.model.Place>, viewRadiusMeters: Double): List<app.vela.core.model.Place> =
        res.asSequence()
            .filterNot { p -> p.permanentlyClosed }
            .filter { p ->
                if (viewRadiusMeters <= 0.0) return@filter true
                val reach = viewRadiusMeters * (1.25 + 0.35 * (ambientProminence(p) / 8.0).coerceIn(0.0, 1.0))
                (p.distanceMeters ?: 0.0) <= reach
            }
            .take(AMBIENT_ONSCREEN_CAP)
            .toList()

    fun hasViewport(): Boolean = viewport != null

    /** Download tiles + POIs for the area the map was last showing (Google-style
     *  "download this area", but invoked from Settings → Offline maps). */
    fun downloadViewport() {
        val v = viewport ?: run { showStatus(appContext.getString(R.string.mapvm_pan_to_area_first)); return }
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
        downloadOverlayForArea(lat, lng) // also grab the open building-footprint overlay for this area
    }

    /** Download the open building-footprint overlay (Microsoft, ODbL) covering ([lat],[lng]) alongside the
     *  offline map + routing for this area — fills the map's building gaps where OSM is thin. Best-effort +
     *  silent (a background enhancement, not the reason the user tapped download). Smallest covering box wins,
     *  same rule as routing. */
    private fun downloadOverlayForArea(lat: Double, lng: Double) {
        viewModelScope.launch {
            val regions = overlayStore.manifest(app.vela.BuildConfig.OVERLAY_MANIFEST_URL)
            val region = regions.filter { lat in it.s..it.n && lng in it.w..it.e }
                .minByOrNull { (it.n - it.s) * (it.e - it.w) } ?: return@launch
            if (region.id in overlayStore.installedIds()) return@launch
            overlayStore.download(region) { }
            refreshBuildingOverlays()
        }
    }

    @Volatile
    private var overlayManifestCache: List<app.vela.offline.RoutingRegion>? = null

    /**
     * Compute the building-footprint overlay sources for the map to render BENEATH OSM, as full `pmtiles://`
     * URIs. Downloaded regions render from their local file (offline-safe); the region covering the CURRENT
     * VIEW that isn't downloaded is STREAMED straight from its hosted `.pmtiles` over HTTP — PMTiles range
     * requests fetch only the visible tiles (a few KB), so footprints appear as you pan with **no download**
     * (the manual download is now only for going fully offline). Called on every camera-idle ([center] = the
     * view centre) so the streamed region follows the map; a failed fetch when offline is harmless (MapLibre
     * just shows no tiles, and any downloaded local overlay still renders). De-duped so panning within one
     * region doesn't churn the map sources.
     */
    private fun refreshBuildingOverlays(center: LatLng? = mapCenter ?: _state.value.myLocation) {
        viewModelScope.launch {
            val installed = overlayStore.installed() // id -> local .pmtiles File
            val uris = installed.values.map { "pmtiles://file://${it.absolutePath}" }.toMutableList()
            center?.let { c ->
                runCatching {
                    val man = overlayManifestCache
                        ?: overlayStore.manifest(app.vela.BuildConfig.OVERLAY_MANIFEST_URL).also { overlayManifestCache = it }
                    // Stream the UNION of covering regions (smallest-first, capped), not just the single
                    // smallest: a neighbour's rectangular bbox can spill across an irregular border AND be
                    // smaller — Kansas's box crosses the Missouri River, covers all of NW Missouri (St Joseph)
                    // and beats Missouri's box, but kansas.pmtiles is EMPTY east of the river → no footprints
                    // (probed: the doll-museum tile has 413 features in missouri.pmtiles, 36 river-bank scraps
                    // in kansas's). With both streamed, whichever archive has the data paints; the empty one's
                    // range requests cost ~nothing. Cap 3 bounds pathological corner overlaps.
                    man.filter { c.lat in it.s..it.n && c.lng in it.w..it.e }
                        .sortedBy { (it.n - it.s) * (it.e - it.w) }
                        .take(3)
                        .filter { it.id !in installed.keys }        // downloaded? the local file already covers it
                        .forEach { uris.add("pmtiles://${it.url}") } // else stream over HTTP range requests
                }
            }
            val distinct = uris.distinct()
            if (distinct != _state.value.buildingOverlays) _state.update { it.copy(buildingOverlays = distinct) }
        }
    }

    @Volatile
    private var addressManifestCache: List<app.vela.offline.RoutingRegion>? = null

    /**
     * House-number (address-point) overlay, streamed for the region in view — footprints get their numbers
     * where OSM has no `addr:housenumber` (OpenAddresses data as a PMTiles of points; rendered as a
     * SymbolLayer of numbers at high zoom). Streaming-only for now (a few KB of tiles per view, no download);
     * reuses `overlayStore.manifest` (manifest-URL-agnostic) against `ADDRESS_MANIFEST_URL`. De-duped.
     */
    private fun refreshAddressOverlays(center: LatLng? = mapCenter ?: _state.value.myLocation) {
        val c = center ?: return
        viewModelScope.launch {
            runCatching {
                val man = addressManifestCache
                    ?: overlayStore.manifest(app.vela.BuildConfig.ADDRESS_MANIFEST_URL).also { addressManifestCache = it }
                // UNION of covering regions, same rule (and reason) as refreshBuildingOverlays: a spilled
                // rectangular bbox from a neighbour state (Kansas over NW Missouri) can be the smallest cover
                // while its archive is empty there — stream up to the 3 smallest covers so the one with data wins.
                val list = man.filter { c.lat in it.s..it.n && c.lng in it.w..it.e }
                    .sortedBy { (it.n - it.s) * (it.e - it.w) }
                    .take(3)
                    .map { "pmtiles://${it.url}" }
                if (list != _state.value.addressOverlays) _state.update { it.copy(addressOverlays = list) }
            }
        }
    }

    private var controlsJob: Job? = null
    private var controlsBox: DoubleArray? = null // [s,w,n,e] of the last fetched (padded) box

    /**
     * Traffic lights + stop signs drawn on the map (OSM `highway=traffic_signals`/`stop` via Overpass),
     * gated to close zoom (z >= [CONTROLS_MIN_ZOOM]) so they don't clutter the browse map. The controls are
     * STATIC, so we fetch a box padded 50% beyond the viewport and REUSE it while the center stays inside its
     * inner half — panning/driving through the box triggers no refetch (spares the fair-use Overpass server);
     * only nearing the box edge refetches. Single-flight + a short settle so a flick doesn't scrape.
     */
    private fun refreshTrafficControls(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        if (zoom < CONTROLS_MIN_ZOOM) {
            controlsBox = null
            controlsJob?.cancel()
            if (_state.value.trafficControls.isNotEmpty()) _state.update { it.copy(trafficControls = emptyList()) }
            return
        }
        val cLat = (south + north) / 2; val cLng = (west + east) / 2
        controlsBox?.let { b ->
            val insLat = (b[2] - b[0]) * 0.25; val insLng = (b[3] - b[1]) * 0.25
            // Still comfortably inside the cached box → the drawn set already covers the view, do nothing.
            if (cLat in (b[0] + insLat)..(b[2] - insLat) && cLng in (b[1] + insLng)..(b[3] - insLng)) return
        }
        controlsJob?.cancel()
        controlsJob = viewModelScope.launch {
            delay(350)
            val padLat = (north - south) * 0.5; val padLng = (east - west) * 0.5
            val s = south - padLat; val n = north + padLat; val w = west - padLng; val e = east + padLng
            // null = FETCH FAILED (fetchControlsInBox returns null on network/non-2xx, empty list only on a
            // real empty area) or the job was cancelled — either way DON'T cache the box, so the next viewport
            // retries instead of stamping a padded "no controls here" that blanks the layer until the box edge.
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    app.vela.core.data.OverpassTrafficSignals.fetchControlsInBox(http, s, w, n, e)
                }
            }.getOrNull() ?: return@launch
            controlsBox = doubleArrayOf(s, w, n, e)
            // Cap what's HANDED to the map (nearest to the box center wins): a dense metro's padded box can
            // carry 1000+ signals/stop signs, and MapLibre re-collides every handed symbol per drag frame —
            // the same budget-GPU lesson as the ambient-POI cap (don't hand it the whole pool). 400 covers
            // the padded box everywhere reasonable; beyond that the excess would collide off anyway.
            val cLat0 = (s + n) / 2; val cLng0 = (w + e) / 2
            val lngScale = kotlin.math.cos(Math.toRadians(cLat0))
            val kept = if (res.size <= CONTROLS_ONSCREEN_CAP) res else res.sortedBy {
                val dLat = it.loc.lat - cLat0; val dLng = (it.loc.lng - cLng0) * lngScale
                dLat * dLat + dLng * dLng
            }.take(CONTROLS_ONSCREEN_CAP)
            _state.update { it.copy(trafficControls = kept) }
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
            showStatus(if (ok) appContext.getString(R.string.mapvm_offline_routing_ready, region.name) else appContext.getString(R.string.mapvm_offline_routing_failed))
        }
    }

    fun deleteRoutingGraph(id: String) {
        routingGraphStore.delete(id)
        _state.update { it.copy(routingInstalledIds = routingGraphStore.installedIds()) }
        showStatus(appContext.getString(R.string.mapvm_offline_routing_removed))
    }

    /** When a map region is downloaded for offline use, also pull its POIs from
     *  OSM/Overpass into the on-device index so search works there with no signal. */
    fun downloadOfflinePois(south: Double, west: Double, north: Double, east: Double) {
        viewModelScope.launch {
            val pois = withContext(Dispatchers.IO) { OverpassPois.fetch(http, south, west, north, east) }
            if (pois.isNotEmpty()) {
                withContext(Dispatchers.IO) { offlinePoiStore.add(pois) }
                showStatus(appContext.getString(R.string.mapvm_saved_places_offline, pois.size))
            }
            // Also pull the address data so offline search can GEOCODE an arbitrary typed address and route
            // to it. Geocoding wants coverage well beyond the few blocks of tiles on screen, so this fetch
            // is PADDED to a ~15 km minimum span around the viewport centre — a downloaded area then routes
            // to an address across the whole metro, not just what was visible. Two OSM sources:
            //   • addr:housenumber points → house-precise where mapped,
            //   • named road centrelines → street-level fallback where OSM has the road but no house numbers
            //     (the reality in new US suburbs — houses are thin, streets are complete).
            // Big bodies, so the no-call-timeout client (the shared 12 s scrape cap would abort mid-read).
            val cLat = (south + north) / 2.0
            val cLng = (west + east) / 2.0
            val aS = minOf(south, cLat - GEOCODE_PAD_DEG)
            val aN = maxOf(north, cLat + GEOCODE_PAD_DEG)
            val aW = minOf(west, cLng - GEOCODE_PAD_DEG)
            val aE = maxOf(east, cLng + GEOCODE_PAD_DEG)
            val addrs = withContext(Dispatchers.IO) {
                runCatching { OverpassPois.fetchAddresses(offlineDownloadHttp, aS, aW, aN, aE) }.getOrDefault(emptyList())
            }
            if (addrs.isNotEmpty()) withContext(Dispatchers.IO) { addressStore.add(addrs) }
            val streets = withContext(Dispatchers.IO) {
                runCatching { OverpassPois.fetchStreets(offlineDownloadHttp, aS, aW, aN, aE) }.getOrDefault(emptyList())
            }
            if (streets.isNotEmpty()) withContext(Dispatchers.IO) { addressStore.addStreets(streets) }
            // One combined notice: N addresses over M streets are now routable offline.
            val streetNames = streets.map { it.street }.distinct().size
            if (addrs.isNotEmpty() || streets.isNotEmpty()) {
                showStatus(appContext.getString(R.string.mapvm_saved_addresses_offline, addrs.size, streetNames))
            }
        }
    }

    /** OkHttp with the scrape-bounding call-timeout removed (see the offline-download rule) — for the
     *  large Overpass address body only; the shared [http] stays for the small POI fetch. */
    private val offlineDownloadHttp by lazy {
        http.newBuilder()
            .callTimeout(java.time.Duration.ZERO)
            .readTimeout(java.time.Duration.ofSeconds(120))
            .build()
    }

    companion object {
        const val KEY_DISMISSED = "dismissed"
        const val CONTROLS_MIN_ZOOM = 16.0 // draw traffic lights/stop signs only when zoomed in this close
        const val CONTROLS_ONSCREEN_CAP = 400 // max controls handed to the map (nearest-to-center wins) — a
                                              // dense metro's padded box can carry 1000+, and every handed
                                              // symbol is re-collided per drag frame (budget-GPU jank)
        const val STALE_LOCATION_MS = 12_000L // grey the dot after this long with no fix
        const val SPEED_HOLD_MS = 3_000L // hold a speedless-fix speed at most this long, then show 0
        const val SPEED_ZERO_MS = 6_000L // no fixes AT ALL for this long → zero the mph. Two full cycles
                                         // of the worst normal chipset cadence (~3 s under canopy) — at
                                         // 3 s the zeroer fired BETWEEN ordinary fixes (56→0→56 flicker)
        const val NETWORK_FIX_QUIET_MS = 12_000L // use a NETWORK fix (dot only) when GPS has been quiet
                                                 // this long (OsmAnd's NOT_SWITCH_TO_NETWORK window)
        const val NAV_STARVED_MS = 10_000L // navigating without a guidance-quality fix this long → chip
        const val SPEED_LIMIT_FORGET_M = 300.0 // drive this far past the last KNOWN limit with only
                                               // untagged snaps → clear the badge (don't show a stale limit)
        const val RESUME_MAX_AGE_MS = 60 * 60 * 1000L // a persisted nav older than this = that drive is long
                                                      // over; don't offer to resume it on the next launch
        const val NAV_HEARTBEAT_MS = 5 * 60 * 1000L   // refresh the resume timestamp this often WHILE driving,
                                                      // so RESUME_MAX_AGE_MS measures time since the interruption
                                                      // (not since nav START) — else a >60 min drive can never resume
        const val REPLAY_SPEEDUP = 3f // trip replays play this many × real time — the map view scales
                                      // the puck's dead-reckoning/easing clocks by it so replays glide
                                      // like live drives instead of surging per fix
        // Max ambient POIs handed to the map layer. Bounds symbol-collision cost per frame so old
        // phones (Pixel 5a) stay smooth while dragging; the collider only paints ~a few dozen anyway.
        const val AMBIENT_ONSCREEN_CAP = 140
        // Half-span (degrees) the offline geocoder's address/street fetch is padded to around the viewport
        // centre — ~10 km lat each way (a bit less in lng at mid-latitudes), so a downloaded area can route
        // to an arbitrary address across the surrounding metro, not just the blocks that were on screen.
        const val GEOCODE_PAD_DEG = 0.09
    }
}
