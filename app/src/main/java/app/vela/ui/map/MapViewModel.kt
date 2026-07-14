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
import app.vela.core.data.MapLinkParser
import app.vela.core.data.OfflinePoiStore
import app.vela.core.data.RecentPlace
import app.vela.core.data.RecentQuery
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
import app.vela.core.model.TransitStep
import app.vela.web.WebDirectionsFetcher
import app.vela.web.WebPhotoFetcher
import app.vela.web.WebReviewsFetcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which directions endpoint the "Choose on map" crosshair is currently setting. */
enum class MapPick { ORIGIN, STOP }

/** Live step-by-step guidance through a transit trip (Moovit-style): the itinerary + which leg
 *  you're on. Advances by GPS proximity to each leg's end (or manually). */
data class TransitNavState(
    val itinerary: TransitItinerary,
    val stepIndex: Int = 0,
    val arrived: Boolean = false,
) {
    val step: TransitStep? get() = itinerary.steps.getOrNull(stepIndex)
    val isLastStep: Boolean get() = stepIndex >= itinerary.steps.lastIndex
}

data class MapUiState(
    val center: LatLng? = null,
    // Camera zoom requested by a deep link (geo:...?z=17); one-shot - any ordinary selection
    // (place tap, long-press, search) clears it back to the default framing zooms.
    val centerZoom: Double? = null,
    // The satellite imagery's capture year for the viewport (Esri metadata), shown in the attribution.
    val imageryYear: String? = null,
    val recenterTick: Int = 0, // bumped per recenter tap so the map force-moves even if "centered"
    val myLocation: LatLng? = null,
    val myBearing: Float? = null,
    val mySpeed: Float? = null, // metres/second, from GPS (spike-filtered, held briefly on speedless fixes)
    val mySpeedRaw: Float? = null, // THIS fix's own measured speed (doppler or derived) — null when the
                                   // fix carried none. The puck's Kalman measures ONLY from this: feeding
                                   // it the held mySpeed re-injected a stale braking speed at high gain
                                   // every fix, which is what kept the puck "moving" at a red light.
    // Posted limit read STREAMING from the hosted maxspeed PMTiles overlay (the map queries the invisible
    // overlay layer under the puck). The "Speed B" online source used when the offline graph can't answer
    // ([speedLimitKmh] null) - so a limit shows anywhere online without a downloaded region.
    val maxspeedOverlays: List<String> = emptyList(), // pmtiles://https:// source URIs covering the view
    val speedLimitOverlayKmh: Double? = null,
    val speedLimitKmh: Double? = null, // posted limit of the current road (OSM maxspeed via GraphHopper),
                                       // km/h; null = unknown/untagged/no offline graph → badge hidden.
                                       // Converted to the display unit at the badge.
    val navStarved: Boolean = false, // navigating but guidance hasn't received a usable (GPS, ≤50 m
                                     // accuracy) fix in a while — drives the "Searching for GPS" chip
                                     // when coarse fixes keep the ordinary stale timer from firing
    val compassHeading: Float? = null, // device facing (rotation-vector sensor) — browse cone when stopped
    val myLocationStale: Boolean = true, // grey the dot until/unless a live fix is recent
    val myAccuracyM: Float? = null, // the last live fix's reported accuracy radius (m); null = unknown/simulated
    val parkingSpot: LatLng? = null, // one-tap "parked here" pin — survives restarts (prefs)
    val parkedAtMillis: Long = 0L,   // when it was saved (for the sheet/history labels)
    val parkingHistory: List<app.vela.core.model.ParkedSpot> = emptyList(), // recent saves, newest first — accidental-overwrite insurance
    val lists: List<app.vela.core.model.PlaceList> = emptyList(), // user place-lists (issue #1), newest first
    val openListId: String? = null, // the list currently shown as results (its name is in the bar)
    val pendingImport: app.vela.core.model.ImportedList? = null, // an imported Google list shown but NOT yet saved
    val offline: Boolean = false, // no usable internet — drives the subtle offline indicator
    val query: String = "",
    val results: List<Place> = emptyList(),
    val ambientPois: List<Place> = emptyList(), // Google places for the visible area, shown on the bare browse map
    // True while the CURRENT viewport sits inside the area the ambient Google fetch covered —
    // the basemap OSM POIs hide only then, so panning/zooming past the fetched area blends the
    // OSM icons back in instead of leaving the outskirts iconless (user 2026-07-10).
    val ambientCoversView: Boolean = false,
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
    // ALPR/Flock cameras counted near each route option (index-aligned with `routes`), for the route
    // picker's opt-in "passes N cameras" badge. Empty when the alert is off or not yet computed.
    val flockOnRoute: List<Int> = emptyList(),
    val buildingOverlays: List<String> = emptyList(), // full pmtiles:// URIs (file:// downloaded / https:// streamed for the view)
    val addressOverlays: List<String> = emptyList(), // pmtiles:// URIs streamed for house-number labels (OpenAddresses)
                                                      // .pmtiles — rendered beneath OSM to fill gaps
    val trafficControls: List<app.vela.core.data.TrafficControl> = emptyList(), // OSM lights+stop signs drawn at high zoom
    val flockCameras: List<app.vela.core.data.AlprCamera> = emptyList(), // ALPR/Flock cameras (DeFlock/OSM), high zoom
    val transitStops: List<app.vela.core.data.transit.Transitous.MapStop> = emptyList(), // canonical GTFS stops (Transitous), high zoom
    val directionsOpen: Boolean = false,
    val directionsReversed: Boolean = false, // route from the place back to you
    val directionsOrigin: Place? = null,     // custom "From" (null = your live location)
    val pickingOrigin: Boolean = false,      // the next search pick sets the origin, not a destination
    val directionsWaypoints: List<Place> = emptyList(), // intermediate stops, in order (multi-stop)
    val pickingStop: Boolean = false,        // the next search pick is added as a stop
    val editingStops: Boolean = false,       // the dedicated stops editor sheet is open
    // Set while browsing search-along-route results: the trip's DESTINATION, stashed so the trip
    // survives the browse. A result pick adds a STOP to the trip (Google-style) instead of opening
    // the place's own sheet, and closing the results returns to the directions panel.
    val alongRouteDest: Place? = null,
    val pickOnMap: MapPick? = null,          // "Choose on map" crosshair mode is active for this endpoint
    val travelMode: TravelMode = TravelMode.DRIVE,
    // Depart/arrive time for directions: 0 = leave now, 1 = depart at, 2 = arrive by, 3 = last available;
    // [directionsTimeEpochSec] is the chosen wall-clock (null when "now"). Drives the transit re-fetch at
    // that time (Google's board is time-dependent).
    val directionsTimeMode: Int = 0,
    val directionsTimeEpochSec: Long? = null,
    val transit: List<TransitItinerary> = emptyList(),
    val transitLoading: Boolean = false,
    val transitNav: TransitNavState? = null,
    // A transit stop's live departure board (keyless, from the station's own place page).
    val stopDepartures: app.vela.core.model.StopDepartures? = null,
    val stopDeparturesLoading: Boolean = false,
    // Route detail (tap a route on the board -> its stop timeline with times, tap-through to stops).
    // Reuses a ride leg from a transit itinerary (its board/intermediate/alight stops carry the times).
    val routeDetail: TransitStep? = null,
    val routeDetailTitle: String? = null,
    val routeDetailLoading: Boolean = false,
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
    // Destination address line for the ARRIVE step (banner + step list); blank when the
    // address adds nothing over arrivedLabel or is simply unknown (offline/partial data).
    val navDestAddress: String = "",
    val arrivedDistanceMeters: Double = 0.0,
    val arrivedSeconds: Double = 0.0,
    val status: String? = null,
    val statusVoiceAction: Boolean = false, // status is a voice problem -> show the Get-a-voice pill
    val statusOpensTtsSettings: Boolean = false, // the pill opens the SYSTEM voice settings, not Vela's library
    val installingEngine: String? = null, // pkg of the voice engine currently downloading
    val voiceDownloadPct: Float? = null, // 0f..1f while the neural-voice model downloads; null = idle
    val installedVoiceIds: Set<String> = emptySet(), // Piper voices present on disk (the voice browser)
    val selectedVoiceId: String? = null, // the active Piper voice id (null = none installed)
    val voiceDownloadingId: String? = null, // the ONE voice currently downloading (one-at-a-time), else null
    val voiceInstalling: Boolean = false,   // download done, unpacking the archive (the map card shows "Installing…")
    val asrDownloadPct: Float? = null,      // 0f..1f while the on-device voice-search model downloads; null = idle
    val asrInstalling: Boolean = false,     // ASR download done, unpacking
    val asrInstalled: Boolean = false,      // Whisper voice-search model present on disk (Settings shows Download vs Remove)
    val voiceSpeaker: Int = 0, // chosen speaker # for the multi-speaker Vela voice (playground stepper)
    val voiceSpeed: Float = 1.0f, // spoken-directions speed multiplier (1.0 = normal, >1 = faster)
    val showPsdsTip: Boolean = false,
    val showSearchThisArea: Boolean = false,
    val showSteps: Boolean = false,
    val previewStepIndex: Int? = null,
    val styleUri: String = MapStyle.DEFAULT.uri,
    // Route preference toggles (drive): honoured on-device where the region graph carries the
    // avoid profiles; online falls back to a normal route (the public OSRM can't exclude).
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val styleName: String = MapStyle.DEFAULT.label,
    val selectedEngine: VoiceEngine? = null,
    val searching: Boolean = false,
    val resultsCollapsed: Boolean = false,
    val recents: List<RecentQuery> = emptyList(),
    val recentPlaces: List<RecentPlace> = emptyList(),
    val saved: List<SavedPlace> = emptyList(),
    val home: SavedPlace? = null,
    val work: SavedPlace? = null,
    val assigningShortcut: ShortcutKind? = null, // picking a place to pin as Home/Work
    val notices: List<Notice> = emptyList(), // pushed via the signed calibration channel
    val updateInfo: app.vela.update.SelfUpdater.UpdateInfo? = null, // newer release found (card on the bare map)
    val updateDownloadPct: Int? = null, // non-null while the update APK downloads
    // Offline routing (downloadable per-region CH graphs — Settings → Offline routing)
    val routingRegions: List<app.vela.offline.RoutingRegion> = emptyList(),
    val routingInstalledIds: Set<String> = emptySet(), // region ids whose graphs are on disk
    val routingDownloadingId: String? = null,          // region id currently downloading, else null
    val routingDownloadPct: Int = 0,
    val regionDownloadName: String? = null,            // display name for the heads-up download card
    // Offline PLACE pack (whole-region POI/address db, pulled after the region's routing graph)
    val poiPackDownloadingId: String? = null,
    val poiPackDownloadPct: Int = 0,
    val poiPackInstalledIds: Set<String> = emptySet(),
    val poiPackRegions: List<app.vela.offline.RoutingRegion> = emptyList(), // the pack catalog (revs/deltas)
    val poiPackInstalledRevs: Map<String, Int> = emptyMap(),                // installed pack revision per region
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
    private val parkingStore: app.vela.core.data.ParkingStore,
    private val listStore: app.vela.core.data.PlaceListStore,
    private val shortcutStore: PlaceShortcutStore,
    private val calibration: CalibrationStore,
    private val offlinePoiStore: OfflinePoiStore,
    private val addressStore: app.vela.core.data.OfflineAddressStore,
    private val webPhotos: WebPhotoFetcher,
    private val webReviews: WebReviewsFetcher,
    private val webDirections: WebDirectionsFetcher,
    private val webStopDepartures: app.vela.web.WebStopDeparturesFetcher,
    private val diag: app.vela.core.diag.DiagLog,
    private val diagExporter: app.vela.diag.DiagExporter,
    private val webPopularTimes: app.vela.web.WebPopularTimesFetcher,
    private val tripStore: app.vela.replay.TripStore,
    private val routingGraphStore: app.vela.offline.RoutingGraphStore,
    private val poiPackStore: app.vela.offline.PoiPackStore,
    private val overlayStore: app.vela.offline.OverlayTileStore,
    private val maxspeedStore: app.vela.offline.MaxspeedOverlayStore,
    private val routeEngine: app.vela.core.data.RouteEngine,
    private val http: okhttp3.OkHttpClient,
    private val selfUpdater: app.vela.update.SelfUpdater,
    private val whisperRecognizer: app.vela.voice.WhisperRecognizer,
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
        loadAmbientCacheFromDisk() // ambient LRU survives restarts (paint-then-refine)
        // A simulated location (Settings → demo) wins the seed so the app opens "there".
        val seed = app.vela.ui.SimLocation.point.value ?: locationProvider.lastKnown()
        _state.update { it.copy(center = seed, myLocation = it.myLocation ?: seed) }
        maybeOfferResume() // a drive that was cut off by a process-kill → offer to pick it back up
        restoreParkingSpot() // a saved "parked here" pin survives restarts
        _state.update { it.copy(lists = listStore.lists()) } // user place-lists (issue #1)
        refreshBuildingOverlays() // surface any installed open building overlays for the map to render
        observeConnectivity() // drive the subtle offline indicator (globe-slash + "Offline" in the bar)
        // Open any downloaded offline place packs so the POI/address stores can query them right away.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                poiPackStore.registerPacks()
                _state.update { it.copy(poiPackInstalledIds = poiPackStore.installedIds()) }
            }
        }
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
                // Two fixes for two causes. A language Vela CAN train a voice for (fr, ru, …) →
                // nudge to the voice library. One it CAN'T (Japanese: no Piper/espeak voice) → the
                // library is a dead end, so point at the phone's own voice settings to add a system
                // voice, which is where ja guidance is spoken from.
                val hasVela = app.vela.core.voice.PiperCatalog.hasVoiceFor(lang)
                val msg = appContext.getString(
                    if (hasVela) R.string.mapvm_voice_lang_missing else R.string.mapvm_voice_lang_system,
                    endonym,
                )
                viewModelScope.launch(Dispatchers.Main) {
                    flashStatus(msg, 6000L, voiceAction = true, ttsSettings = !hasVela)
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
        // Spoken directions on/off is PERSISTENT: the Settings toggle and the in-nav mute
        // button share the one pref, so a muted choice survives restarts.
        if (!voicePrefs.getBoolean("spoken_directions", true)) {
            voice.muted = true
            _state.update { it.copy(voiceMuted = true) }
        }
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
        // Fleet default map colour set (a user's own Settings pick always wins - see MapColors).
        app.vela.ui.MapColors.remoteDefault.value = calibration.current().defaultMapPalette
        adoptKeywordTables()
        // Pull the latest scraper calibration from the repo (non-blocking, once),
        // then surface any freshly-pushed notices.
        viewModelScope.launch {
            runCatching { calibration.refresh() }
            refreshNotices()
            app.vela.ui.MapColors.remoteDefault.value = calibration.current().defaultMapPalette
            adoptKeywordTables()
        }
        maybeCheckForUpdate()

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
                        navDestAddress = ns.destinationAddress,
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
        // Simulated location (Settings → demo): pin the puck to the chosen point and DON'T collect real
        // GPS, so nothing leaks the real position. stopSimulateLocation() restarts the collector.
        app.vela.ui.SimLocation.point.value?.let { sim ->
            // Kill any stale-timer armed by the last REAL fix: the pinned demo dot gets no fresh
            // fixes, so a leftover timer greyed it ~30 s in and nothing ever turned it blue again.
            staleTimerJob?.cancel(); staleTimerJob = null
            _state.update { it.copy(myLocation = sim, center = it.center ?: sim, myLocationStale = false, showPsdsTip = false, myAccuracyM = null) }
            return
        }
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
                        // Drives the map's accuracy halo: a coarse-permission or network fix reports
                        // hundreds-to-thousands of meters and gets an honest circle; GPS won't.
                        myAccuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                        showPsdsTip = false, center = it.center ?: here, myLocationStale = false,
                    )
                }
                restartStaleTimer()
                // Advance transit step-by-step guidance when we reach the current leg's end (no-op off transit).
                maybeAdvanceTransitNav(here)
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
            val vp0 = viewport
            val spanM0 = vp0?.let { LatLng(it[0], it[1]).distanceTo(LatLng(it[2], it[1])) }
            // ADDRESS queries ("123 main st") get a parallel Photon (OSM) lookup: it honours the
            // location bias properly, which is where Google's keyless suggest falls down. The two
            // fetches race concurrently; Photon's nearby addresses lead, Google's places follow.
            val photonDeferred: kotlinx.coroutines.Deferred<List<Place>>? = if (app.vela.core.data.PhotonGeocoder.looksLikeAddress(term)) {
                async(Dispatchers.IO) {
                    runCatching {
                        app.vela.core.data.PhotonGeocoder.suggest(
                            http, term, near, app.vela.ui.AppLocale.effective().language,
                        )
                    }.getOrDefault(emptyList())
                }
            } else null
            val res = runCatching { dataSource.search(term, near, spanM0).places }.getOrDefault(emptyList())
            val photon = photonDeferred?.await().orEmpty()
            if (_state.value.query.trim() == term) { // ignore if the query changed meanwhile
                // Google gets the viewport bias, but keyless ranking for a PARTIAL address is
                // weak - "123 main st" happily led with matches states away while the one in
                // town sat below (user report). Bucket by metro distance (stable sort: within
                // each bucket Google's own relevance order is preserved) so nearby matches
                // surface first and a famous far match still shows, just lower.
                // ...but never demote Google's TOP suggestion or an exact name match: for a
                // plain entity query ("tacoma") the #1 result IS the entity - the city itself -
                // and bucketing it under every nearby Tacoma-named business pushed it off the
                // list entirely (user report 2026-07-13). Addresses are unaffected: Photon still
                // leads those, so a famous far "123 Main Street" at #1 sits below the local hits.
                val ranked = if (near == null) res else res.withIndex().sortedBy { (i, p) ->
                    val entity = i == 0 || p.name.equals(term, ignoreCase = true)
                    if (entity || p.location.distanceTo(near) <= SUGGEST_NEAR_M) 0 else 1
                }.map { it.value }
                // Photon's nearby addresses lead; drop its far strays (a metro away isn't what a
                // partial address means) and anything Google already covers within a block.
                val photonNear = photon.filter { p ->
                    near == null || p.location.distanceTo(near) <= SUGGEST_NEAR_M
                }.filter { p -> ranked.none { g -> g.location.distanceTo(p.location) < 120.0 } }
                _state.update { it.copy(suggestions = (photonNear + ranked).take(8)) }
            }
        }
    }

    /** The X in the search bar: wipe the query, results and selection. Closing an ALONG-ROUTE
     *  browse instead returns to the trip it belongs to (restore the destination + panel) —
     *  the user was hunting for a stop, not abandoning the drive. */
    fun clearSearch() {
        suggestJob?.cancel()
        val backToTrip = _state.value.alongRouteDest
        if (backToTrip != null) {
            _state.update {
                it.copy(
                    query = "", results = emptyList(), suggestions = emptyList(),
                    selected = backToTrip, alongRouteDest = null, directionsOpen = true,
                    resultsCollapsed = false, showSearchThisArea = false,
                )
            }
            return
        }
        _state.update {
            it.copy(
                query = "", results = emptyList(), suggestions = emptyList(), selected = null,
                resultsCollapsed = false, showSearchThisArea = false, openListId = null, pendingImport = null,
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

    /** The X on a single recent row - remove just that entry. */
    fun removeRecentQuery(query: String) {
        recentStore.remove(query)
        _state.update { it.copy(recents = recentStore.recent()) }
    }

    fun removeRecentPlace(placeId: String) {
        recentPlaceStore.remove(placeId)
        _state.update { it.copy(recentPlaces = recentPlaceStore.recent()) }
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

    // --- Self-updater (GitHub releases) ------------------------------------------------------

    /** Launch check, at most ~daily, gated by the Settings toggle. "Not now" on a version
     *  silences that version (a NEWER release shows the card again).
     *  NB called from init{}, which runs BEFORE the later-declared `settingsPrefs` field
     *  initializer — resolve the prefs locally or this NPEs on launch (it did). */
    private fun maybeCheckForUpdate() {
        val prefs = appContext.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("self_update_check", true)) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_update_check_ms", 0L) < 20 * 60 * 60_000L) return
        prefs.edit().putLong("last_update_check_ms", now).apply()
        val nightly = prefs.getBoolean("update_nightly", false)
        viewModelScope.launch {
            val info = selfUpdater.check(app.vela.BuildConfig.VERSION_CODE, nightly) ?: return@launch
            if (info.versionCode <= prefs.getInt("update_dismissed_code", 0)) return@launch
            _state.update { it.copy(updateInfo = info) }
        }
    }

    /** Settings "Check for updates" button — unthrottled, reports back via [onResult]
     *  (true = an update was found and the card is up; false = already current / check failed). */
    fun checkForUpdateNow(onResult: (Boolean) -> Unit) {
        val nightly = settingsPrefs.getBoolean("update_nightly", false)
        viewModelScope.launch {
            val info = selfUpdater.check(app.vela.BuildConfig.VERSION_CODE, nightly)
            if (info != null) _state.update { it.copy(updateInfo = info) }
            onResult(info != null)
        }
    }

    /** Download the offered update and hand it to the system installer. */
    fun downloadUpdate() {
        val info = _state.value.updateInfo ?: return
        if (_state.value.updateDownloadPct != null) return // already downloading
        _state.update { it.copy(updateDownloadPct = 0) }
        viewModelScope.launch {
            val apk = selfUpdater.download(info) { pct ->
                _state.update { it.copy(updateDownloadPct = pct) }
            }
            _state.update { it.copy(updateDownloadPct = null) }
            if (apk != null) {
                selfUpdater.install(apk)
            } else {
                showStatus(appContext.getString(app.vela.R.string.update_download_failed))
            }
        }
    }

    /** "Not now": hide the card and stay quiet about THIS version (a newer one re-offers). */
    fun dismissUpdate() {
        _state.value.updateInfo?.let {
            settingsPrefs.edit().putInt("update_dismissed_code", it.versionCode).apply()
        }
        _state.update { it.copy(updateInfo = null, updateDownloadPct = null) }
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
        routeJob?.cancel()
        _state.update {
            it.copy(
                selected = base, center = base.location, placesHere = emptyList(), reviews = emptyList(),
                reviewsLoading = false, reviewsFound = 0, photosLoading = false, loadingDetails = false,
                // Same cohesion rule as selectPlace: a new destination closes the old chooser.
                directionsOpen = false, routes = emptyList(), activeRoute = null,
                transit = emptyList(), transitLoading = false, showSteps = false,
            )
        }
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
                fetchStopDepartures(enriched) // a saved/recent transit stop shows its board too
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

    /** Is there a usable internet connection right now? Used to skip the Google scrape when offline (it
     *  would only hang to the socket timeout). Fails OPEN - if the check itself errors, assume online so a
     *  quirk can never block search. */
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
        // Pasted coordinates ("47.61, -122.33" or a geo: string) drop a reverse-geocoded pin
        // there instead of going to the search endpoint as text - same handling a bare external
        // geo: link gets. Strict whole-string match, so addresses with numbers still search.
        MapLinkParser.parseBareCoordinate(q)?.let { link ->
            val at = LatLng(link.lat!!, link.lng!!)
            recentStore.add(q)
            _state.update { it.copy(recents = recentStore.recent(), suggestions = emptyList(), searching = false, center = at) }
            onMapLongPress(at)
            return
        }
        // Re-poll connectivity per search: the registered callback alone proved able to
        // wedge `offline` on (missed onAvailable after doze) until an app relaunch.
        _state.update { val off = !isOnline(); if (it.offline != off) it.copy(offline = off) else it }
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
            // A fresh typed search leaves any along-route browse: picks open places normally again.
            _state.update { it.copy(searching = true, suggestions = emptyList(), showSearchThisArea = false, resultsCollapsed = false, alongRouteDest = null) }
            // A pasted Google Maps SHARE LINK: try the shared-list import (issue #1). The link
            // resolves keylessly to the list's places, each carrying the owner's note; they land
            // as results (title in the bar) and each is savable/openable like any search hit.
            if (MapLinkParser.isShareLink(q)) {
                val imported = withContext(Dispatchers.IO) { runCatching { dataSource.importList(q) }.getOrNull() }
                if (imported != null && imported.places.isNotEmpty()) {
                    // Show the places as results and OFFER to save (a banner over the results),
                    // rather than silently persisting a list on every peeked link — user choice
                    // 2026-07-09. Nothing lands in Your lists until they tap Save.
                    _state.update {
                        it.copy(
                            results = imported.places, query = imported.title, pendingImport = imported,
                            searching = false, selected = null, status = null, resultsCollapsed = false,
                            openListId = null,
                        )
                    }
                } else {
                    _state.update { it.copy(searching = false, status = appContext.getString(R.string.map_import_failed)) }
                }
                return@launch
            }
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
                // Widen the request to the REAL viewport: the pb template bakes a ~25 km span, so
                // a zoomed-out search only ever covered a city-sized window however far you could
                // see (user 2026-07-11). Span = the visible box's vertical extent.
                val vp = viewport
                val spanM = vp?.let { LatLng(it[0], it[1]).distanceTo(LatLng(it[2], it[1])) }
                val res = dataSource.search(q, near, spanM)
                if (res.places.isNotEmpty()) {
                    _state.update {
                        // Keep the directions DESTINATION (held in `selected`) while picking an origin/stop —
                        // else typing the origin query wiped the "To" and the panel showed an empty
                        // "Destination" with stale routes (the from-here edit cleared where you were going).
                        // A live scrape succeeding is definitive proof we're online — clear a stuck
                        // offline flag (the network callback can miss an event after doze and leave
                        // `offline` latched until relaunch; seen on-device 2026-07-09).
                        it.copy(results = res.places, selected = if (it.pickingOrigin || it.pickingStop) it.selected else null, status = null, searching = false, offline = false)
                    }
                } else {
                    // Online SUCCEEDED but found nothing. Don't leave a blank screen (the "POI list just
                    // isn't showing up" report): try the on-device OSM index (it may hold a small local
                    // place Google misses), and if that's empty too, say "No results" plainly.
                    val offline = offlineSearch(q, near)
                    _state.update {
                        if (offline.isNotEmpty())
                            it.copy(results = offline, selected = if (it.pickingOrigin || it.pickingStop) it.selected else null, status = null, searching = false, offline = false)
                        else
                            it.copy(results = emptyList(), selected = if (it.pickingOrigin || it.pickingStop) it.selected else null, status = appContext.getString(R.string.mapvm_no_results, q), searching = false, offline = false)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // superseded by a newer search — don't run the fallback/error update on a dead job
            } catch (e: CalibrationNeededException) {
                _state.update { it.copy(status = appContext.getString(R.string.mapvm_search_needs_recalibration, e.message), searching = false) }
            } catch (e: Exception) {
                // Network/Google failure → fall back to the offline OSM index (POIs + address geocode, same
                // as the straight-offline branch so an address still resolves when the scrape times out).
                val offline = offlineSearch(q, near)
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

    /** The on-device OSM fallback for a query: matched POIs plus, when the text looks like an address,
     *  interpolated address hits (addresses first so a typed street resolves). Used both when the online
     *  scrape throws AND when it succeeds with zero results, so a small local place Google misses still
     *  surfaces instead of a blank screen. */
    private suspend fun offlineSearch(q: String, near: LatLng?): List<Place> = withContext(Dispatchers.IO) {
        val pois = runCatching { offlinePoiStore.search(q, near) }.getOrDefault(emptyList())
        val addrs = if (app.vela.core.data.OfflineAddressStore.looksLikeAddress(q))
            runCatching { addressStore.geocode(q, near) }.getOrDefault(emptyList()) else emptyList()
        (if (addrs.isNotEmpty()) addrs + pois else pois + addrs).distinctBy { it.id }
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
                it.copy(
                    query = query, searching = true, directionsOpen = false, suggestions = emptyList(),
                    resultsCollapsed = false, recents = recentStore.recent(),
                    // Stash the trip's destination: browsing stop candidates must not lose the trip.
                    // While this is set, picking a result ADDS IT AS A STOP and returns to the panel.
                    alongRouteDest = if (it.navigating) null else (it.selected ?: it.alongRouteDest),
                )
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
    /** Text SHARED to Vela (the system share sheet): a Google Maps share link imports like a
     *  pasted one, a geo:/maps URL opens like a deep link, and anything else - an address, a
     *  place name - just searches. Share payloads are usually "Check out X! https://..." so the
     *  link is fished out of the prose first. */
    fun openSharedText(raw: String) {
        val token = raw.trim().split(Regex("\\s+")).firstOrNull {
            MapLinkParser.isShareLink(it) || it.startsWith("http", ignoreCase = true) || it.startsWith("geo:", ignoreCase = true)
        }
        when {
            token != null && MapLinkParser.isShareLink(token) -> {
                _state.update { it.copy(query = token) }
                runSearch(token, _state.value.myLocation ?: _state.value.center)
            }
            token != null -> {
                val link = MapLinkParser.parse(token)
                if (link != null) openDeepLink(link)
                else runSearch(raw.trim(), _state.value.myLocation ?: _state.value.center)
            }
            raw.isNotBlank() -> {
                _state.update { it.copy(query = raw.trim()) }
                runSearch(raw.trim(), _state.value.myLocation ?: _state.value.center)
            }
        }
    }

    fun openDeepLink(link: MapLink) {
        val near = link.lat?.let { la -> link.lng?.let { ln -> LatLng(la, ln) } }
        val q = link.query
        when {
            !q.isNullOrBlank() -> {
                _state.update { it.copy(query = q, center = near ?: it.center, centerZoom = link.zoom) }
                runSearch(q, near ?: _state.value.myLocation ?: _state.value.center)
            }
            near != null -> {
                onMapLongPress(near)
                // A long-press is always at an on-screen point, so it never moves the camera. A
                // deep link's point can be anywhere: fly there too (honouring its z= when given),
                // or the sheet opens for a place the map isn't showing (the camera stayed home on
                // every geo: URI, cold or warm).
                _state.update { it.copy(center = near, centerZoom = link.zoom) }
                // A long-press pin assumes the camera is already there (the user pressed the
                // screen); a deep link's coordinate is usually far away, so move the camera
                // too - without this the pin sheet opened while the map stayed put (device
                // 2026-07-13). Setting center also trips the follow-disarm rule, correctly:
                // a coordinate link means "look over there".
                _state.update { it.copy(center = near) }
                onMapLongPress(near)
            }
        }
    }

    fun selectPlace(p: Place) {
        if (consumeAssign(SavedPlace.of(p))) return
        // NAVIGATING: any place picked (the in-nav search-along-route list) becomes a stop on
        // the LIVE drive - the normal selection path below would null activeRoute/open sheets
        // that nav's bottom slot doesn't render. Google's in-nav pick does the same.
        if (_state.value.navigating) { addStopDuringNav(p); return }
        // Search-along-route pick: the tapped place becomes a STOP on the stashed trip (Google's
        // flow), not a new destination — tapping "Directions" on it used to silently replace the
        // whole trip. Restore the destination first so the panel reopens showing the real trip;
        // picking the destination itself just returns to the panel (a stop AT the destination is
        // nonsense).
        _state.value.alongRouteDest?.let { dest ->
            _state.update { it.copy(selected = dest, alongRouteDest = null) }
            if (p.id != dest.id && p.location != dest.location) addStop(p)
            else _state.update { it.copy(directionsOpen = true, results = emptyList(), query = "") }
            return
        }
        if (_state.value.pickingStop) { addStop(p); return }
        if (_state.value.pickingOrigin) { setDirectionsOrigin(p); return }
        suggestJob?.cancel()
        routeJob?.cancel() // a directions fetch in flight must not resurrect the stale panel
        _state.update {
            it.copy(
                selected = withListNote(p), center = p.location, centerZoom = null, reviews = emptyList(), suggestions = emptyList(),
                placesHere = othersAt(p, it.results), loadingDetails = false, photosLoading = false,
                // Picking a NEW place while a route chooser is open closes it: the chooser
                // belonged to the previous destination and kept covering the fresh place
                // (the along-route / pick-origin / pick-stop flows early-return above).
                directionsOpen = false, routes = emptyList(), activeRoute = null,
                transit = emptyList(), transitLoading = false, showSteps = false,
                stopDepartures = null, stopDeparturesLoading = false,
            )
        }
        fetchReviews(p)
        fetchPhotos(p)
        fetchPlaceDetails(p)
        fetchStopDepartures(p)
        backfillOfflineAddress(p)
        rememberRecentPlace(SavedPlace.of(p))
    }

    /** Transit-station category words (English + a few common ones). The board fetch is gated on
     *  these to avoid a WebView load on every ordinary place; the parser returns null anyway for a
     *  place with no board, so a miss here just means no board, never a wrong one. */
    private val TRANSIT_CAT_COMPILED = Regex(
        """station|stop|subway|metro|transit|transport|\bhub\b|\bbus\b|train|\brail\b|tram|light rail|terminal|ferry|""" +
            """bahnhof|haltestelle|gare|estaci|estaç|stazione|fermata|estação|estação|halte|stanice|""" +
            """지하철|driehoek|û|вокзал|станц|остановка|停|駅|车站|車站|""" +
            // The gaps issue #71 exposed (a Hebrew-locale stop's category is "תחנת אוטובוס" and
            // nothing here matched): Hebrew stems + the app languages that were missing entirely.
            """תחנ|אוטובוס|רכבת|מסוף|רציף|""" + // he: stop/station stem, bus, rail, terminal, platform
            """arrêt|parada|paragem|hållplats|przystanek|dworzec|зупинка|станція""",
        RegexOption.IGNORE_CASE,
    )

    // Categories that must NOT count as transit even though a gate word matches - "Gas station" /
    // "Charging station" / "Fire station" all contain "station", and the board fetch is by
    // PROXIMITY now, so a fuel stop beside a bus stop showed that stop's departures (device report
    // 2026-07-13). Localized like the gate: fuel/EV/emergency/broadcast words across the app
    // languages.
    private val NON_TRANSIT_CAT_COMPILED = Regex(
        """gas|fuel|petrol|filling|gasolin|benzin|essence|carburant|paliw|бензин|заправ|азс|tank|""" +
            """station-service|servicio|serviço|servizio|加油|ガソリン|주유|דלק|""" +
            """charging|laadstation|ladestation|recharge|recarga|ricarica|зарядн|טעינה|充电|充電|""" +
            """fire|police|power|pumping|weigh|radio|television|\btv\b|pompiers|bomberos|feuerwehr""",
        RegexOption.IGNORE_CASE,
    )

    // Remote-overridable via the signed calibration bundle (transitCategoryWords /
    // transitExcludeWords - each term joins one case-insensitive alternation): a missing or wrong
    // word in some language becomes a config edit, not an app release. Falls back to the compiled
    // regexes when absent or unbuildable.
    @Volatile private var TRANSIT_CAT = TRANSIT_CAT_COMPILED
    @Volatile private var NON_TRANSIT_CAT = NON_TRANSIT_CAT_COMPILED

    /** The ONE transit-category predicate: a gate word must match AND no exclusion word may. */
    private fun isTransitCategory(cat: String): Boolean =
        TRANSIT_CAT.containsMatchIn(cat) && !NON_TRANSIT_CAT.containsMatchIn(cat)

    /** Push the calibration bundle's keyword-table overrides into their consumers (called at init
     *  and again after the remote refresh). Absent fields leave the compiled tables in place. */
    private fun adoptKeywordTables() {
        val cal = calibration.current()
        app.vela.core.data.google.parse.SearchParser.remoteClosedWords = cal.statusClosedWords
        app.vela.core.data.google.parse.SearchParser.remoteOpenWords = cal.statusOpenWords
        app.vela.core.data.google.parse.StopDeparturesParser.remoteIndices = cal.stopBoardIndices
        TRANSIT_CAT = cal.transitCategoryWords?.takeIf { it.isNotEmpty() }?.let { words ->
            runCatching { Regex(words.joinToString("|"), RegexOption.IGNORE_CASE) }.getOrNull()
        } ?: TRANSIT_CAT_COMPILED
        NON_TRANSIT_CAT = cal.transitExcludeWords?.takeIf { it.isNotEmpty() }?.let { words ->
            runCatching { Regex(words.joinToString("|"), RegexOption.IGNORE_CASE) }.getOrNull()
        } ?: NON_TRANSIT_CAT_COMPILED
    }

    /** The nearest LIVE transit-category listing to [at] among [results], within [radiusM]. Shared by the
     *  stop-icon tap and the intersection board re-resolve - the one predicate for "the operating stop". */
    private fun nearestLiveStop(results: List<Place>, at: LatLng, radiusM: Double = 250.0): Place? =
        results.asSequence()
            .filter { !it.permanentlyClosed && it.location.distanceTo(at) < radiusM }
            .filter { p -> p.category?.let { isTransitCategory(it) } == true }
            .minByOrNull { it.location.distanceTo(at) }

    /** A transit stop's live departure board, from the station's own place page (keyless, anonymous).
     *  Only fired for places whose category reads like a transit stop AND that carry a feature id
     *  (needed for the `?cid=` deep-link); guarded to the still-selected place when it returns. */
    private var boardRefreshJob: kotlinx.coroutines.Job? = null

    /** Keep a Transitous board fresh while its sheet is open: re-query the open feed every 30 s
     *  (one small JSON call, same cadence as the countdown clock) and swap the board in place.
     *  Ends itself the moment the selection changes. Google-page boards are NOT refreshed - that
     *  path is a full WebView load, and its realtime drift is the price of the fallback. */
    private fun startBoardRefresh(selId: String, lat: Double, lng: Double) {
        boardRefreshJob?.cancel()
        boardRefreshJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                // Backgrounding with a stop sheet open used to keep this polling every 30 s
                // (viewModelScope outlives the screen). Park until the app is visible again;
                // the first tick after coming back refreshes immediately.
                app.vela.ui.AppVisibility.foreground.first { it }
                if (_state.value.selected?.id != selId) return@launch
                val fresh = withContext(Dispatchers.IO) {
                    runCatching { app.vela.core.data.transit.Transitous.board(http, lat, lng) }.getOrNull()
                }
                if (fresh != null && fresh.lines.isNotEmpty()) {
                    _state.update { st ->
                        if (st.selected?.id == selId) st.copy(stopDepartures = fresh) else st
                    }
                }
            }
        }
    }

    private fun fetchStopDepartures(p: Place) {
        val fid = p.featureId
        if (fid.isNullOrBlank() || !fid.contains(":")) return
        val cat = p.category ?: ""
        val isTransit = isTransitCategory(cat)
        val isIntersection = cat.contains("intersection", ignoreCase = true)
        if (!isTransit && !isIntersection) return
        // PRIMARY: Transitous (open GTFS + realtime, keyless). One proximity lookup at the place's own
        // coordinate - no name correlation against Google/OSM at all - and unlike Google's anonymous
        // page it returns EVERY route at the stop (a hub's parent station merges all its bays). The
        // Google blob paths below stay as the fallback where Transitous has no coverage.
        _state.update { if (it.selected?.featureId == fid) it.copy(stopDeparturesLoading = true) else it }
        viewModelScope.launch {
            val board = withContext(Dispatchers.IO) {
                runCatching { app.vela.core.data.transit.Transitous.board(http, p.location.lat, p.location.lng) }.getOrNull()
            }
            android.util.Log.i("VelaDepartures", "transitous lines=${board?.lines?.size ?: -1}")
            if (board != null && board.lines.isNotEmpty()) {
                _state.update { st ->
                    if (st.selected?.featureId != fid) st
                    else st.copy(stopDepartures = board, stopDeparturesLoading = false)
                }
                startBoardRefresh(p.id, p.location.lat, p.location.lng)
                return@launch
            }
            // FALLBACK: the Google place-page blob (agency-dependent, one route at hubs - but better
            // than nothing where the open feeds lack the agency).
            when {
                isTransit -> fetchBoardFrom(fid, selectedFid = fid)
                // A stop named by its corner resolves to Google's "Intersection" entity, whose own page
                // has no board - re-resolve to the co-located stop listing (device 2026-07-13).
                else -> resolveIntersectionStopBoard(p)
            }
        }
    }

    /** Fetch a transit stop's board from its own [boardFid] and attach it to the still-selected place
     *  ([selectedFid]). Feature-id-gated so a slow fetch can't land on a place the user has moved off. */
    private fun fetchBoardFrom(boardFid: String, selectedFid: String?) {
        _state.update { if (it.selected?.featureId == selectedFid) it.copy(stopDeparturesLoading = true) else it }
        viewModelScope.launch {
            val board = runCatching { webStopDepartures.fetch(boardFid) }.getOrNull()
            android.util.Log.i("VelaDepartures", "board lines=${board?.lines?.size ?: -1}")
            _state.update { st ->
                if (st.selected?.featureId != selectedFid) st
                else st.copy(stopDepartures = board?.takeIf { it.lines.isNotEmpty() }, stopDeparturesLoading = false)
            }
        }
    }

    /** [p] resolved to an "Intersection", but a bus stop usually sits at the same corner as its own Google
     *  listing. Search "<name> bus stop" near the corner (the transit-hint trick onPoiTap uses), take the
     *  nearest LIVE transit-category listing within 80 m, and pull its board onto [p]'s sheet. No stop -> no
     *  board (a plain intersection just shows nothing, as before). */
    private fun resolveIntersectionStopBoard(p: Place) {
        val selectedFid = p.featureId
        _state.update { if (it.selected?.featureId == selectedFid) it.copy(stopDeparturesLoading = true) else it }
        viewModelScope.launch {
            val stopFid = runCatching {
                // A junction's own point sits back from the stops on each approach, so use a generous radius
                // (~250 m): a REAL co-located stop measured 89 m from its junction point (device 2026-07-13,
                // just past the old 80 m cut - exactly why boards never showed), while another junction's
                // stops sit ~575 m out. 250 m catches the right one without grabbing a neighbour's.
                // Name-first, then a bare proximity query: OSM and Google often NAME the same stop
                // differently ("A & B" vs "B & A", Hwy vs the road's name), and a name-keyed search
                // can miss even when the listing is right there.
                val byName = nearestLiveStop(dataSource.search("${p.name} bus stop", p.location).places, p.location)
                val stop = byName ?: nearestLiveStop(dataSource.search("bus stop", p.location).places, p.location)
                stop?.featureId?.takeIf { it.contains(":") }
            }.getOrNull()
            if (stopFid == null) {
                _state.update { st -> if (st.selected?.featureId == selectedFid) st.copy(stopDeparturesLoading = false) else st }
                return@launch
            }
            val board = runCatching { webStopDepartures.fetch(stopFid) }.getOrNull()
            android.util.Log.i("VelaDepartures", "intersection stop board lines=${board?.lines?.size ?: -1}")
            _state.update { st ->
                if (st.selected?.featureId != selectedFid) st
                else st.copy(stopDepartures = board?.takeIf { it.lines.isNotEmpty() }, stopDeparturesLoading = false)
            }
        }
    }

    /** Tap a route on the departure board -> show its stop timeline with times (issue #71 follow-up).
     *  Reuses the PROVEN transit-itinerary parser: a directions query from this stop toward the route's
     *  destination returns a ride leg whose board/intermediate/alight stops already carry per-stop times.
     *  No new keyless scraping. Needs the line's headsign (its destination) to aim the query. */
    fun openRouteDetail(line: app.vela.core.model.StopDepartureLine) {
        val origin = _state.value.selected?.location ?: return
        val title = listOfNotNull(line.label, line.headsign?.takeIf { it.isNotBlank() }).joinToString(" · ")
        _state.update { it.copy(routeDetailLoading = true, routeDetailTitle = title, routeDetail = null) }
        routeDetailJob?.cancel()
        routeDetailJob = viewModelScope.launch {
            // PRIMARY: the GTFS trip itself. Transitous boards stamp each departure with its tripId,
            // and /trip returns that run's REAL stop sequence with per-stop realtime and CANCELLED
            // flags straight from the agency feed - exact where the itinerary reuse below has to
            // guess at a matching leg, and no headsign geocode at all. Google-fallback boards carry
            // no tripId, and a failed trip fetch falls through to the itinerary path.
            val tripId = line.upcoming.firstOrNull { it.tripId != null }?.tripId
            val gtfs = tripId?.let {
                withContext(Dispatchers.IO) {
                    runCatching {
                        app.vela.core.data.transit.Transitous.tripStops(http, it, origin.lat, origin.lng)
                    }.getOrNull()
                }
            }
            android.util.Log.d("VelaRouteDetail", "gtfs=${gtfs != null} tripId=${tripId != null}")
            val step = gtfs ?: itineraryStep(line, origin)
            _state.update {
                if (step == null) { flashStatus(appContext.getString(R.string.route_detail_unavailable)); it.copy(routeDetailLoading = false) }
                else it.copy(routeDetail = step, routeDetailLoading = false)
            }
        }
    }

    /** The pre-GTFS fallback for the stop timeline: geocode the line's headsign near the stop and
     *  reuse a transit itinerary's matching ride leg. Used for Google-fallback boards (their
     *  departures carry no tripId) and when the trip fetch fails. Null when nothing usable. */
    private suspend fun itineraryStep(line: app.vela.core.model.StopDepartureLine, origin: LatLng): TransitStep? {
        val dest = line.headsign?.takeIf { it.isNotBlank() } ?: return null
        // Aim at the route's destination (geocode the headsign near the stop), then ride transit there.
        // A bare headsign is often ambiguous ("Richmond" is a city district AND a far-off city), so
        // prefer a candidate that is itself a transit place (station/airport/terminal) and, among
        // those, the one nearest the tapped stop - a sanity filter that rejects a same-named place
        // across the country. (The RIDE-LEG match below is what actually pins the tapped line.)
        val cands = runCatching { dataSource.search(dest, origin).places }.getOrDefault(emptyList())
        val transitish = cands.filter { it.category?.let { c ->
            listOf("station", "transit", "stop", "airport", "terminal", "bart", "metro", "rail")
                .any { k -> c.contains(k, ignoreCase = true) }
        } == true }
        val destLoc = (transitish.minByOrNull { it.location.distanceTo(origin) }
            ?: cands.minByOrNull { it.location.distanceTo(origin) })?.location ?: return null
        val trips = runCatching { webDirections.transit(origin, destLoc) }.getOrDefault(emptyList())
        val rides = trips.flatMap { it.steps }.filter { it.line != null && it.intermediateStops.isNotEmpty() }
        // Pick the leg the user actually tapped. Rank by the tapped LINE first (a short board label
        // like "N" matches a longer itinerary name "N-Judah"), then by how close its board stop is to
        // the tapped stop - so a same-line leg heading the OTHER way (boarding far off) loses to the
        // one boarding here. Falls back to whatever leg boards at this stop, then the first ride.
        val boardDist = { s: TransitStep -> s.boardStop?.location?.distanceTo(origin) ?: Double.MAX_VALUE }
        val step = rides.filter { lineLabelMatches(line.label, it.line?.name) }.minByOrNull { boardDist(it) }
            ?: rides.filter { boardDist(it) <= 500.0 }.minByOrNull { boardDist(it) }
            ?: rides.firstOrNull()
        android.util.Log.d("VelaRouteDetail", "'$dest' rides=${rides.size} -> ${step?.line?.name} (${step?.intermediateStops?.size} stops)")
        return step
    }

    /** Does a departure-board line label (a short route code, "N" / "42") match an itinerary line name
     *  (which may be longer, "N-Judah")? Exact, or the label is the FIRST token of the name, so a "1"
     *  label doesn't spuriously match a "10" line. */
    private fun lineLabelMatches(label: String?, name: String?): Boolean {
        if (label.isNullOrBlank() || name.isNullOrBlank()) return false
        if (name.equals(label, ignoreCase = true)) return true
        return name.trim().split(Regex("[\\s\\-/]")).firstOrNull()?.equals(label, ignoreCase = true) == true
    }

    fun closeRouteDetail() {
        // Cancel the in-flight lookup too: without this, dismissing the sheet (Back) mid-load lets the
        // fetch complete and set routeDetail = step, springing the full-screen sheet back open over
        // whatever the user moved on to (or flashing "unavailable" after they already left).
        routeDetailJob?.cancel()
        _state.update { it.copy(routeDetail = null, routeDetailLoading = false, routeDetailTitle = null) }
    }

    /** Tap a stop in the route timeline -> open THAT stop (its own departure board), so you can keep
     *  tapping through the network. Closes the route detail and selects the stop as a place. */
    fun openRouteStop(stop: app.vela.core.model.TransitStopTime) {
        val loc = stop.location ?: return
        closeRouteDetail()
        // Pass a transit KIND (not just name+coord): without it, onPoiTap searched the bare stop name,
        // which Google resolves to the road JUNCTION - so tap-through kept throwing you to a corner
        // (device 2026-07-13). The hint makes it search "<name> transit stop" and prefer the live
        // stop listing, same as a map tap on the stop; its board then fires and the tap-through continues.
        onPoiTap(stop.name, loc, "transit stop")
    }

    private var routeDetailJob: kotlinx.coroutines.Job? = null

    /** Offline, a POI that OSM never tagged with an address (most US chains) shows a bare place sheet —
     *  no online detail fetch can fill it. Reverse-geocode its location against the on-device address
     *  index (nearest mapped house, else nearest street) so it still shows an address. Only when offline,
     *  only when the place lacks one, and only if it's still the selected place when the lookup returns. */
    private fun backfillOfflineAddress(p: Place) {
        // Fire when there's no real street line, not only when address is fully blank: OSM often tags a POI
        // with just `addr:state`/`addr:city` (Applebee's came back as bare "WA"), which is useless. Treat an
        // address with no digit (no house number) as "needs a street".
        if (isOnline() || (!p.address.isNullOrBlank() && p.address!!.any { it.isDigit() })) return
        viewModelScope.launch {
            val addr = withContext(Dispatchers.IO) {
                runCatching { addressStore.reverseGeocode(p.location) }.getOrNull()
            } ?: return@launch
            _state.update { st ->
                val sel = st.selected
                val stillNeeds = sel?.id == p.id && (sel.address.isNullOrBlank() || sel.address!!.none { it.isDigit() })
                if (stillNeeds) st.copy(selected = sel!!.copy(address = addr)) else st
            }
        }
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
        // "Load photos" off: never start the gallery scrape (it's the heaviest per-place
        // request); the sheet also hides the photo strip, so no loading flag either.
        if (!app.vela.ui.LoadPhotos.on.value) return
        val fid = p.featureId
        if (fid.isNullOrBlank() || !fid.contains(":")) return
        // Only flash the loading shimmer for places LIKELY to have photos — a rated/reviewed
        // business or one with a preview already. A residential address (no rating, reviews, or
        // preview) shouldn't show a photo placeholder for a gallery it'll never have. We still
        // run the scrape silently in case it surprises us; we just don't promise photos.
        val photoWorthy = p.rating != null || p.reviewCount != null || p.photoUrls.isNotEmpty()
        if (photoWorthy) _state.update { if (it.selected?.featureId == fid) it.copy(photosLoading = true) else it }
        viewModelScope.launch {
            // The gallery has TWO keyless sources with complementary halves: the WebView page
            // walk carries the CATEGORY tags (the Menu tab) but no per-photo dates, while the
            // hspqX RPC carries each photo's POSTED DATE but no categories. Fire the cheap RPC
            // alongside the walk and join its dates onto the streamed photos by the stable
            // image id (the URL up to the size suffix both pipelines share), so a menu tile
            // can say how old the menu shot is (user 2026-07-11). No author anywhere keyless:
            // the RPC documents it's absent, and mining the page DOM for it isn't worth the
            // extra walking. Best-effort like everything else here.
            var rpcDates: Map<String, String> = emptyMap()
            val datesJob = launch {
                val rpc = runCatching { dataSource.placePhotos(fid) }.getOrDefault(emptyList())
                rpcDates = rpc.mapNotNull { ph -> ph.postedText?.let { ph.url.substringBefore('=') to it } }.toMap()
                // Join diagnostics (menu dates weren't showing, user 2026-07-11): how many photos
                // the RPC returned, how many carried dates, and a sample key from each side so a
                // key-namespace mismatch (gps-cs-s vs /p/) is visible at a glance in logcat.
                android.util.Log.i(
                    "VelaPhotoDates",
                    "rpc=${rpc.size} dated=${rpcDates.size} rpcKey=${rpc.firstOrNull()?.url?.substringBefore('=')?.takeLast(40)}",
                )
            }
            fun datesFor(photos: List<app.vela.core.model.Photo>): List<String?> {
                val joined = photos.map { it.postedText ?: rpcDates[it.url.substringBefore('=')] }
                android.util.Log.i(
                    "VelaPhotoDates",
                    "join walk=${photos.size} matched=${joined.count { it != null }} walkKey=${photos.firstOrNull()?.url?.substringBefore('=')?.takeLast(40)}",
                )
                return joined
            }
            // Photos STREAM in: the scraper reports the accumulated set whenever it grows, so the
            // strip fills progressively (first partial = the page's hero photos, ~1s after load)
            // instead of waiting ~20s for the full category walk. Monotonic (a partial never
            // shrinks the strip below the search preview) + feature-id/loading gated (a stale
            // partial can't touch the next place; the final result clears the flag in the same
            // atomic copy, so a straggler can't overwrite it — same pattern as review streaming).
            val full = runCatching {
                webPhotos.fetch(fid, onPhotoDates = { pairs ->
                    // The walk mined per-photo dates from the place page itself (the dead RPC's
                    // replacement). Absolute Y-M-D entries get a localized short date; relative
                    // "N ago" strings pass through. Merged INTO the join map - the final apply
                    // (after the walk) restamps everything with whatever arrived.
                    val mined = pairs.mapNotNull { (u, d) ->
                        val text = if (Regex("^\\d{4}-\\d{1,2}-\\d{1,2}$").matches(d)) {
                            runCatching {
                                val p3 = d.split("-").map { it.toInt() }
                                java.time.LocalDate.of(p3[0], p3[1], p3[2])
                                    .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
                            }.getOrNull()
                        } else d
                        text?.let { u.substringBefore('=') to it }
                    }.toMap()
                    if (mined.isNotEmpty()) rpcDates = mined + rpcDates // RPC (if ever revived) wins ties
                }, onHistogram = { counts ->
                    // The page's [5-star..1-star] review counts, grabbed in passing by the walk -
                    // drives the native histogram on the inline Reviews tab. Feature-id gated.
                    _state.update { st ->
                        val sel = st.selected
                        if (sel?.featureId == fid) st.copy(selected = sel.copy(ratingHistogram = counts)) else st
                    }
                }, onPartial = { part ->
                    if (part.isNotEmpty()) _state.update { st ->
                        val sel = st.selected
                        if (sel?.featureId == fid && st.photosLoading && part.size > sel.photoUrls.size) st.copy(
                            selected = sel.copy(photoUrls = part.map { it.url }, photoDates = datesFor(part), photoCategories = part.map { it.category }),
                        ) else st
                    }
                })
            }.getOrDefault(emptyList())
            // Wait for the date fetch before the final apply so the settled gallery is dated
            // even when the RPC was slower than the walk (partials may have gone out dateless).
            datesJob.join()
            _state.update { st ->
                val sel = st.selected
                if (sel?.featureId == fid) st.copy(
                    selected = if (full.isNotEmpty()) sel.copy(photoUrls = full.map { it.url }, photoDates = datesFor(full), photoCategories = full.map { it.category }) else sel,
                    photosLoading = false,
                ) else st
            }
        }
    }

    /** Pull full reviews for a place by its Google feature id (best-effort,
     *  applied only if it's still the selected place when they arrive). */
    private var reviewsJob: Job? = null

    private fun fetchReviews(p: Place, force: Boolean = false) {
        // "Show reviews" off: no review section is rendered, so don't scrape either.
        if (!app.vela.ui.ShowReviews.on.value) return
        // BARE transit stops: never scrape reviews. A bus stop's content is its departure board, not
        // reviews (the WebView grind competed with the board load and rendered awkwardly). But gate on
        // "transit-category AND UNRATED", not category alone - a rated transit CENTER (a real building
        // people review) must KEEP its reviews (user 2026-07-13: broad category gate wrongly killed
        // them). Real buildings carry a Google rating; bare stops don't.
        if (p.rating == null && p.category?.let { isTransitCategory(it) } == true) return
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
                alongRouteDest = null, editingStops = false,
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
    fun onPoiTap(name: String, location: LatLng, poiKind: String? = null) {
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
        // A transit STOP is usually named by its intersection ("Main St & 1st Ave"), and Google resolves
        // that bare string to the road JUNCTION, not the stop - so a tapped stop opened as an "Intersection"
        // with no board (issue #71 follow-up; verified in a live capture: "<x> & <y>" -> Intersection,
        // "<x> & <y> bus stop" -> the Stop). When the TAPPED POI's kind says transit (its class/subclass,
        // not its name, so a business called "Salt & Straw" is untouched), append a mode word to the lookup
        // so the search returns the stop. Non-transit POIs search by name exactly as before.
        val transitHint = poiKind?.lowercase()?.let { k ->
            when {
                "bus" in k -> "bus stop"
                "tram" in k || "light_rail" in k -> "tram stop"
                "subway" in k || "metro" in k -> "station"
                "railway" in k || "train" in k || "rail" in k -> "station"
                "ferry" in k -> "ferry terminal"
                "station" in k || "halt" in k || "platform" in k || "stop" in k || "transit" in k -> "transit stop"
                else -> null
            }
        }
        val searchQuery = if (transitHint != null && !name.lowercase().contains(transitHint)) "$name $transitHint" else name
        _state.update {
            it.copy(
                selected = placeholder,
                results = emptyList(),
                center = location,
                placesHere = emptyList(),
                reviews = emptyList(),
                // Clear any previous stop's departure board so it never lingers under a new POI.
                stopDepartures = null,
                stopDeparturesLoading = false,
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
                val results = dataSource.search(searchQuery, location).places
                val nearest = results.minByOrNull { p -> p.location.distanceTo(location) }
                val pick = if (transitHint != null) {
                    // Transit tap: pick the OPERATING stop, not the nearest/most-reviewed thing at the
                    // coordinate. A stop's spot usually ALSO has a road junction (Google's "Intersection")
                    // and can carry a stale PERMANENTLY-CLOSED old shelter; nearest/most-reviewed lands on
                    // those (device 2026-07-13: "a suburban junction" opened a Permanently-closed old
                    // stop; the route tap-through threw you to a corner). Take the nearest LIVE
                    // transit-category listing near the tap, and SKIP the most-reviewed override (a
                    // defunct-but-reviewed shelter must not beat the live stop). No such listing -> null,
                    // so the lightweight name+location placeholder set above stays (a stop name beats a
                    // corner; there's no board without a real stop listing anyway).
                    // 250 m, not 80: the OSM icon and Google's stop listing routinely sit on different
                    // corners of the junction (a real pair measured 89 m apart, past the old 80 m cut -
                    // device 2026-07-13). Nearest-wins keeps the wide radius safe. Name-first, then a bare
                    // proximity query - OSM and Google often name the same stop differently, so the
                    // name-keyed search can miss a listing that's right at the icon.
                    nearestLiveStop(results, location)
                        ?: runCatching { dataSource.search(transitHint, location).places }.getOrNull()
                            ?.let { nearestLiveStop(it, location) }
                } else {
                    // A tapped POI can map to several Google listings at one spot - e.g. a co-branded
                    // "SpeeDee Midas" has a rich "SpeeDee" profile (543 reviews) AND a sparse "Midas" one,
                    // both at 2000 F St. Among listings ~AT the tap (35 m) the most-reviewed is the
                    // maintained, canonical one, but two genuinely distinct shops can also share a spot
                    // (a strip mall), so only override nearest when the canonical listing CLEARLY dominates
                    // by review count (a true duplicate, not a close call).
                    val canonical = results
                        .filter { it.location.distanceTo(location) < 35.0 }
                        .maxByOrNull { it.reviewCount ?: 0 }
                    if (canonical != null && nearest != null &&
                        (canonical.reviewCount ?: 0) >= 2 * (nearest.reviewCount ?: 0) + 5
                    ) canonical else nearest
                }
                pick to results
            }.getOrNull()
            val full = resolved?.first
            if (full != null && _state.value.selected == placeholder) {
                _state.update { it.copy(selected = withListNote(full), placesHere = othersAt(full, resolved.second)) }
                fetchReviews(full)
                fetchPhotos(full)
                fetchPlaceDetails(full) // popular times + editorial/owner, like a search-result tap
                fetchStopDepartures(full) // issue #71: a bus stop / station tapped on the MAP gets its board too
                rememberRecentPlace(SavedPlace.of(full))
            } else if (transitHint != null && _state.value.selected == placeholder) {
                // Issue #71 (Jerusalem): a tapped stop with NO resolvable Google stop listing used to
                // dead-end as a name-only sheet - no category, no board, nothing to swipe to. The TAP
                // ITSELF says this is a transit stop (the basemap class, language-independent), and
                // Transitous needs only the coordinate - so fetch the board by proximity regardless
                // of what Google resolution did. The Google-page fallback is impossible here anyway
                // (no feature id), so this is Transitous-or-nothing, which is correct.
                _state.update { if (it.selected == placeholder) it.copy(stopDeparturesLoading = true) else it }
                val board = withContext(Dispatchers.IO) {
                    runCatching {
                        app.vela.core.data.transit.Transitous.board(http, location.lat, location.lng)
                    }.getOrNull()
                }
                android.util.Log.i("VelaDepartures", "hinted-tap fallback lines=${board?.lines?.size ?: -1}")
                _state.update {
                    if (it.selected == placeholder) {
                        it.copy(
                            stopDepartures = board?.takeIf { b -> b.lines.isNotEmpty() },
                            stopDeparturesLoading = false,
                        )
                    } else it
                }
                if (board != null && board.lines.isNotEmpty()) {
                    startBoardRefresh(placeholder.id, location.lat, location.lng)
                }
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
        // While planning a trip, a long-press means "route THROUGH here": grab an arbitrary point and
        // add it as a via-stop (then the route reroutes through it). This is the manual way to steer a
        // route around an area/camera - Google's keyless directions and OSRM can't be told "avoid this
        // region", but a hand-placed waypoint forces the detour. The point itself is what matters, so
        // the stop sits at the exact spot pressed; the reverse-geocode only names it.
        if (_state.value.directionsOpen && !_state.value.pickingOrigin && !_state.value.pickingStop) {
            viewModelScope.launch {
                val geo = runCatching { dataSource.reverseGeocode(location) }.getOrNull()
                val stop = (geo ?: Place(id = "pin:${location.lat},${location.lng}", name = appContext.getString(R.string.mapvm_dropped_pin), location = location))
                    .copy(location = location)
                addStop(stop)
                // No flash banner: the route refetch that addStop triggers resets `status` a beat later
                // anyway (the banner blinked for a few frames, user 2026-07-13), and the stop appearing in
                // the chooser + the route redrawing through it IS the feedback.
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

    /** Tap on a house-number LABEL (the map's own `addr:housenumber` or the address overlay's
     *  `number`). Unlike a long-press we KNOW the number the user aimed at, so we LEAD the pin with
     *  that exact number and use the reverse-geocode only for the street/city — otherwise Google's
     *  reverse-geocode can snap to a neighbour (tapped 6110, got 6138), which is exactly the "doesn't
     *  snap to the house number" complaint. A real business sitting on the point still wins. */
    fun onAddressLabelTap(number: String, location: LatLng) {
        if (_state.value.pickOnMap != null) { onMapLongPress(location); return } // pick-mode reuses the endpoint flow
        reviewsJob?.cancel()
        val id = "addr:$number@${location.lat},${location.lng}"
        val immediate = Place(id = id, name = number, location = location)
        _state.update {
            it.copy(
                selected = immediate,
                results = emptyList(),
                resultsCollapsed = false,
                showSearchThisArea = false,
                placesHere = emptyList(),
                reviews = emptyList(),
                reviewsLoading = false,
                reviewsFound = 0,
                photosLoading = false,
                loadingDetails = false,
                pickingOrigin = false,
                pickingStop = false,
            )
        }
        viewModelScope.launch {
            val geo = runCatching { dataSource.reverseGeocode(location) }.getOrNull()
            val place = when {
                geo == null -> immediate.copy(address = number)
                // A real POI (has a rating/category) at that spot — show it, the user gets the business.
                geo.rating != null || geo.category != null -> geo
                else -> {
                    val base = geo.address ?: geo.name
                    if (base.any { it.isLetter() }) {
                        // Strip any house number the reverse-geocode led with, then prepend the tapped one.
                        val rest = base.replaceFirst(Regex("^\\s*\\d+\\S*\\s+"), "")
                        val addr = "$number $rest"
                        immediate.copy(name = addr.substringBefore(",").trim(), address = addr)
                    } else immediate.copy(address = number)
                }
            }
            if (_state.value.selected?.id == id) _state.update { it.copy(selected = place) }
        }
    }

    fun quickSearch(category: String) {
        _state.update { it.copy(query = category) }
        search()
    }

    fun routeToSelected() {
        val sel = _state.value.selected ?: return
        // Start each directions session clean — don't inherit a custom origin, stops, or
        // pick-mode left over from a previous place's directions.
        _state.update { it.copy(directionsOpen = true, directionsReversed = false, directionsOrigin = null, pickingOrigin = false, directionsWaypoints = emptyList(), pickingStop = false) }
        // Walking back to the car is the parking spot's whole point — default to WALK there.
        // Otherwise every session opens on the STICKY last-used mode (user 2026-07-11).
        val (avTolls, avHighways) = stickyAvoid()
        _state.update { it.copy(avoidTolls = avTolls, avoidHighways = avHighways) }
        val mode = if (sel.id.startsWith("parking:")) TravelMode.WALK else stickyTravelMode()
        if (mode != _state.value.travelMode) setTravelMode(mode) else route(mode)
    }

    // ---- Parking spot ----------------------------------------------------------------
    // Long-press the locate button: remember where the car is. Persisted so it survives
    // app restarts; the map shows a small "Parked" chip while one is set.

    /** Saves the current fix as the parking spot. False when there's no location yet.
     *  Every save also lands in the HISTORY (newest first, capped), so an accidental
     *  overwrite is recoverable from the P button's long-press or Settings. */
    fun saveParkingSpot(): Boolean {
        val here = _state.value.myLocation ?: return false
        val now = System.currentTimeMillis()
        val history = parkingStore.save(app.vela.core.model.ParkedSpot(here.lat, here.lng, now))
        _state.update { it.copy(parkingSpot = here, parkedAtMillis = now, parkingHistory = history) }
        return true
    }

    fun clearParkingSpot() {
        // Only the CURRENT spot clears — history stays (it's the safety net).
        parkingStore.clearCurrent()
        _state.update { it.copy(parkingSpot = null, parkedAtMillis = 0L) }
    }

    /** Makes a history entry the current spot again (accidental-overwrite recovery). */
    fun restoreParkingFromHistory(entry: app.vela.core.model.ParkedSpot) {
        parkingStore.restore(entry)
        _state.update { it.copy(parkingSpot = entry.location, parkedAtMillis = entry.savedAtMillis) }
    }

    fun deleteParkingHistoryEntry(entry: app.vela.core.model.ParkedSpot) {
        val history = parkingStore.deleteFromHistory(entry)
        _state.update { it.copy(parkingHistory = history) }
    }

    fun clearParkingHistory() {
        parkingStore.clearHistory()
        _state.update { it.copy(parkingHistory = emptyList()) }
    }

    // ---- Place lists (issue #1) -------------------------------------------------------

    /** Creates a list and returns its id (so the caller can immediately add a place to it). */
    fun createList(name: String, icon: String = "bookmark", color: Long = 0xFF1A73E8): String {
        val id = "list:" + name.hashCode().toString(16) + ":" + _state.value.lists.size
        _state.update { it.copy(lists = listStore.create(app.vela.core.model.PlaceList(id, name.trim(), icon, color))) }
        return id
    }

    fun updateList(list: app.vela.core.model.PlaceList) {
        _state.update { it.copy(lists = listStore.update(list)) }
    }

    fun deleteList(listId: String) {
        _state.update {
            it.copy(
                lists = listStore.delete(listId),
                // If we were viewing it, drop back to the map.
                results = if (it.openListId == listId) emptyList() else it.results,
                openListId = if (it.openListId == listId) null else it.openListId,
                query = if (it.openListId == listId) "" else it.query,
            )
        }
    }

    fun addPlaceToList(listId: String, place: Place) {
        _state.update { it.copy(lists = listStore.addPlace(listId, app.vela.core.model.ListPlace.of(place))) }
    }

    fun removePlaceFromList(listId: String, place: Place) {
        val lists = listStore.removePlace(listId, place.id, place.featureId)
        _state.update { it.copy(lists = lists, results = refreshedOpenList(it, lists) ?: it.results) }
    }

    /** When the results sheet is showing an open list, rebuild it from [lists] so a
     *  note edit / removal shows immediately (the rows are a snapshot from openList()). */
    private fun refreshedOpenList(st: MapUiState, lists: List<app.vela.core.model.PlaceList>): List<Place>? =
        st.openListId?.let { id -> lists.firstOrNull { it.id == id }?.places?.map { it.toPlace() } }

    /** Sets/clears the owner's note on a place across every list, and reflects it on the
     *  open sheet so the change shows immediately. Keyed by feature id AND place id — the
     *  volatile id alone lost notes on re-resolved chain listings (the Safeway bug). */
    fun setPlaceNote(place: Place, note: String?) {
        val lists = listStore.setNote(place.id, note, place.featureId)
        _state.update {
            it.copy(
                lists = lists,
                results = refreshedOpenList(it, lists) ?: it.results,
                selected = it.selected?.let { s ->
                    if (s.id == place.id || (place.featureId != null && s.featureId == place.featureId)) {
                        s.copy(savedNote = note?.ifBlank { null })
                    } else s
                },
            )
        }
    }

    /** Which lists a place is in (drives the sheet's "Saved in <list>" + checkmarks). */
    fun listsContaining(place: Place): List<app.vela.core.model.PlaceList> =
        _state.value.lists.filter { l -> l.places.any { it.matches(place.id, place.featureId) } }

    /** Carry the user's saved list note onto a freshly opened place: a place opened from the
     *  map or search is rebuilt from Google data (savedNote = null) even when a list holds a
     *  note for it, so without this the note only ever showed when opened FROM the list. */
    private fun withListNote(p: Place): Place {
        if (p.savedNote != null) return p
        val note = _state.value.lists.asSequence()
            .flatMap { it.places.asSequence() }
            .firstOrNull { it.matches(p.id, p.featureId) }
            ?.note
        return if (note != null) p.copy(savedNote = note) else p
    }

    /** Saves the currently-previewed imported Google list into Your lists (the Save banner).
     *  Reuses a same-named list on re-import. Returns the new/updated list id. */
    fun saveImportedList(): String? {
        val imp = _state.value.pendingImport ?: return null
        val existing = listStore.lists().firstOrNull { it.name == imp.title }
        val listId = existing?.id ?: ("list:import:" + imp.title.hashCode().toString(16))
        val list = app.vela.core.model.PlaceList(
            id = listId, name = imp.title, icon = "bookmark",
            description = imp.description, places = imp.places.map { app.vela.core.model.ListPlace.of(it) },
        )
        val lists = if (existing != null) listStore.update(list) else listStore.create(list)
        _state.update { it.copy(lists = lists, pendingImport = null, openListId = listId) }
        return listId
    }

    /** Opens a list as search results (its places), the list name in the search bar. */
    fun openList(listId: String) {
        val list = _state.value.lists.firstOrNull { it.id == listId } ?: return
        val places = list.places.map { it.toPlace() }
        _state.update {
            it.copy(
                results = places, query = list.name, openListId = listId,
                selected = null, resultsCollapsed = false, searching = false, status = null,
            )
        }
    }

    /** Opens the parked car as a place sheet (tap the map pin, or the P button while a
     *  spot is set). Sets `selected` directly — a synthetic place must not trigger the
     *  Google detail fetches [selectPlace] runs. [label] is the localized "Parked car". */
    fun showParkedCar(label: String) {
        val spot = _state.value.parkingSpot ?: return
        val p = Place(id = "parking:${spot.lat},${spot.lng}", name = label, location = spot)
        // A parked car never fetches reviews/photos/details — but the PREVIOUS place's in-flight
        // scrape (and its reviews/shimmer flags) would otherwise bleed onto the parking sheet
        // ("loading reviews on the parked car" bug). Cancel + clear them, like the pin/POI paths.
        reviewsJob?.cancel()
        routeJob?.cancel()
        // Frame the spot too — opened from the P button while browsing another city, the
        // sheet alone doesn't tell you WHERE the car is.
        _state.update {
            it.copy(
                selected = p, results = emptyList(), query = "", directionsOpen = false,
                placesHere = emptyList(), reviews = emptyList(), reviewsLoading = false,
                reviewsFound = 0, photosLoading = false, loadingDetails = false,
                routes = emptyList(), activeRoute = null, showSteps = false, previewStepIndex = null,
                transit = emptyList(), transitLoading = false,
                center = spot, recenterTick = it.recenterTick + 1,
            )
        }
    }

    private fun restoreParkingSpot() {
        val history = parkingStore.history()
        val current = parkingStore.current()
        _state.update {
            it.copy(
                parkingHistory = history,
                parkingSpot = current?.let { c -> LatLng(c.lat, c.lng) },
                parkedAtMillis = current?.savedAtMillis ?: 0L,
            )
        }
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
    fun beginPickStop() = _state.update { it.copy(pickingStop = true, editingStops = false, query = "", suggestions = emptyList()) }

    /** The dedicated stops editor (reorder / remove / add in one sheet, one reroute on Done). */
    fun openStopsEditor() = _state.update { it.copy(editingStops = true) }

    fun closeStopsEditor() = _state.update { it.copy(editingStops = false) }

    /** Apply the editor's final ordering in ONE shot — a single reroute per visit, not one per
     *  micro-edit like the old inline arrows. */
    fun applyStops(stops: List<Place>) {
        val changed = stops != _state.value.directionsWaypoints
        _state.update { it.copy(directionsWaypoints = stops, editingStops = false) }
        if (changed) route(_state.value.travelMode)
    }

    fun cancelPickStop() = _state.update { it.copy(pickingStop = false) }

    /** Append an intermediate stop and re-route through it. */
    /** In-nav stop insert: hand the pick to the session (it replans the drive through it) and
     *  clear the search chrome so the nav view is what's on screen again. The chooser's waypoint
     *  list gains it too, so ending nav back into the panel shows the real trip. */
    fun addStopDuringNav(p: Place) {
        val loc = _state.value.myLocation ?: return
        navSession.addStop(app.vela.core.nav.NavSession.NavStop(p.location, p.name), loc)
        _state.update {
            it.copy(
                directionsWaypoints = it.directionsWaypoints + p,
                results = emptyList(), query = "", suggestions = emptyList(),
                selected = null, alongRouteDest = null, resultsCollapsed = false,
            )
        }
    }

    fun addStop(p: Place) {
        _state.update {
            it.copy(
                directionsWaypoints = it.directionsWaypoints + p, pickingStop = false, pickOnMap = null,
                // A stop pick always belongs to an open trip: return to the directions panel and
                // drop the pick UI (query/results) so the route is what's on screen. Setting
                // directionsOpen BEFORE route() also keeps its stillWanted() guard satisfied.
                directionsOpen = true, results = emptyList(), query = "", resultsCollapsed = false,
            )
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
        return runCatching { dataSource.nameRoute(route, o, d, _state.value.travelMode, _state.value.avoidTolls, _state.value.avoidHighways) }
            .getOrNull() ?: route.copy(provisional = false)
    }

    fun setTravelMode(mode: TravelMode) {
        if (_state.value.travelMode == mode) return
        // Sticky: the pick becomes the default for the NEXT directions session too (a cyclist
        // shouldn't re-tap Bike every trip; Google remembers the same way). No Settings row -
        // the habit IS the setting (user 2026-07-11).
        appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("travel_mode", mode.name).apply()
        _state.update { it.copy(travelMode = mode) }
        route(mode)
    }

    fun setAvoidTolls(on: Boolean) {
        if (_state.value.avoidTolls == on) return
        appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("avoid_tolls", on).apply()
        _state.update { it.copy(avoidTolls = on) }
        route(_state.value.travelMode) // re-route with the new preference
    }

    fun setAvoidHighways(on: Boolean) {
        if (_state.value.avoidHighways == on) return
        appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("avoid_highways", on).apply()
        _state.update { it.copy(avoidHighways = on) }
        route(_state.value.travelMode)
    }

    /** The persisted avoid toggles (sticky like the travel mode - the habit is the setting). */
    private fun stickyAvoid(): Pair<Boolean, Boolean> = runCatching {
        val p = appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
        p.getBoolean("avoid_tolls", false) to p.getBoolean("avoid_highways", false)
    }.getOrDefault(false to false)

    /** The remembered last-used travel mode (see [setTravelMode]); DRIVE until first changed. */
    private fun stickyTravelMode(): TravelMode = runCatching {
        TravelMode.valueOf(
            appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
                .getString("travel_mode", null) ?: return TravelMode.DRIVE,
        )
    }.getOrDefault(TravelMode.DRIVE)

    /** Set the depart/arrive time for directions (mode 0=now, 1=depart at, 2=arrive by, 3=last available;
     *  [epochSec] null for now) and re-route so transit shows departures at that time. */
    fun setDirectionsTime(mode: Int, epochSec: Long?) {
        val s = _state.value
        if (s.directionsTimeMode == mode && s.directionsTimeEpochSec == epochSec) return
        _state.update { it.copy(directionsTimeMode = mode, directionsTimeEpochSec = if (mode == 0) null else epochSec) }
        // Only TRANSIT re-routes on a time change: the schedule board is genuinely
        // time-dependent. The keyless drive/walk/bike request has no departure field at all,
        // so refetching those returned identical routes and just flickered the list while the
        // chooser's own arrival-window arithmetic was the only thing that changed (the "kinda
        // janky" report, user 2026-07-11).
        if (_state.value.travelMode == TravelMode.TRANSIT) route(TravelMode.TRANSIT)
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
        if (mode == TravelMode.TRANSIT) { routeTransit(origin, dest, s.directionsTimeMode, s.directionsTimeEpochSec); return }
        // Stops are ALWAYS stored in travel order (swapDirections physically reverses the list), so no
        // per-call reversal here — display, reorder arrows and routing all agree on one order.
        val stops = s.directionsWaypoints.map { it.location }
        // Guard: this reply is only applied if directions is still open for the SAME mode (the user hasn't
        // backed out or switched away while it was fetching). Mirrors routeTransit's stale-load guard.
        fun stillWanted() = _state.value.directionsOpen && _state.value.travelMode == mode
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            try {
                val routes = dataSource.directions(origin, dest, mode, stops, s.avoidTolls, s.avoidHighways)
                if (!stillWanted()) return@launch // backed out / switched mode mid-fetch — don't resurrect it
                _state.update {
                    it.copy(
                        routes = routes,
                        activeRoute = routes.firstOrNull(),
                        flockOnRoute = emptyList(), // recomputed below when the alert's on
                        transit = emptyList(), transitLoading = false,
                        status = if (routes.isEmpty()) appContext.getString(R.string.mapvm_no_mode_route_found, mode.name.lowercase()) else null,
                    )
                }
                val flockEpoch = ++routesEpoch // stamp THIS route set; a newer route() bumps it and stales the flock job
                if (routes.isNotEmpty()) refreshFlockOnRoute(routes, flockEpoch)
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

    private var flockRouteJob: kotlinx.coroutines.Job? = null
    private var routesEpoch = 0 // bumped on each fresh route(); stales an in-flight flock count if a newer route set lands

    /** When "Avoid surveillance cameras" is on, count the ALPR cameras near each route option (keyless
     *  Overpass, index-aligned with [routes]) so the picker can badge "passes N cameras" AND auto-prefer
     *  the fewest-camera alternate - but only for a MODEST detour (never send you an hour around a camera
     *  on a 15-minute trip). Off the hot path; a failure just shows no badge and no reroute. */
    private fun refreshFlockOnRoute(routes: List<Route>, epoch: Int) {
        flockRouteJob?.cancel()
        if (!app.vela.ui.FlockRouteAlert.on.value) return
        flockRouteJob = viewModelScope.launch {
            val counts = withContext(Dispatchers.Default) {
                val local = app.vela.data.FlockCameras.isLoaded
                routes.map { r ->
                    // Bundled dataset: instant + reliable, so the auto-avoid re-rank always has real counts to
                    // work with (the live Overpass fan-out per tile was slow and often returned 0 = nothing to
                    // avoid). Fall back to Overpass only until the bundled set finishes loading.
                    if (local) app.vela.data.FlockCameras.along(r.polyline).size
                    else runCatching { app.vela.core.data.OverpassAlprCameras.fetchAlong(http, r.polyline).size }.getOrDefault(0)
                }
            }
            android.util.Log.i("VelaFlockRoute", "counts=$counts")
            // Still THIS route set? Guard on the epoch, not the polyline: naming a provisional route
            // (selectRoute(0), which fires right after this launches for the common provisional-top case)
            // RE-SNAPS its geometry, so a polyline compare tripped and silently dropped the badges +
            // auto-avoid. The epoch only bumps on a fresh route(); naming/user-pick keep the same set
            // (same size + order), so the counts still line up index-for-index with _state.routes.
            val cur = _state.value.routes
            if (routesEpoch != epoch || cur.size != counts.size) return@launch
            _state.update { it.copy(flockOnRoute = counts) }
            // Auto-avoid: pick the fewest-camera route (tie → the faster one) IF it beats the fastest on
            // cameras and costs at most 25% / 10 min more. The cap is where we "draw the line" - a modest
            // detour to dodge cameras, not a wild one. Long-press "route through here" is the manual override.
            if (counts.any { it > 0 }) {
                val eta = { r: Route -> r.durationInTrafficSeconds ?: r.durationSeconds }
                val eta0 = eta(cur[0]) // routes are sorted fastest-first, and cur[0] is the default active
                val best = counts.indices.minByOrNull { counts[it] * 1_000_000L + eta(cur[it]).toLong() } ?: 0
                val extra = eta(cur[best]) - eta0
                val cap = minOf(eta0 * 0.25, 600.0)
                // No heads-up flash for the swap (removed 2026-07-13): the reorder below makes
                // the pick visible at the top of the list; the banner was noise on the card.
                if (counts[best] < counts[0] && extra <= cap && best != 0) {
                    // The avoided route LEADS the list (user 2026-07-14): with avoid-cameras on
                    // the ranking is augmented by camera counts, not pure ETA - the low-camera
                    // pick moves to the top for visibility and its count badge moves with it.
                    // The "Fastest" tag still lands on the true fastest row (it keys off the
                    // shown ETA, not list position), so the tradeoff stays legible.
                    val order = listOf(best) + counts.indices.filter { it != best }
                    _state.update {
                        it.copy(
                            routes = order.map { i -> cur[i] },
                            flockOnRoute = order.map { i -> counts[i] },
                        )
                    }
                    selectRoute(0)
                }
            }
        }
    }

    /** Turn-by-turn walking steps between two points (for a transit trip's walk legs), via the
     *  normal walk router. Returns the maneuver instructions, or empty on failure. */
    suspend fun walkDirections(from: LatLng, to: LatLng): List<String> = runCatching {
        dataSource.directions(from, to, TravelMode.WALK).firstOrNull()
            ?.maneuvers?.mapNotNull { it.instruction.takeIf { s -> s.isNotBlank() } }.orEmpty()
    }.getOrDefault(emptyList())

    /** Transit can't self-route (no traffic-free open transit graph) and Google
     *  only serves it to a real browser engine, so it goes through the hidden
     *  WebView ([WebDirectionsFetcher]) rather than the OkHttp data source. We
     *  clear the driving route line while it loads — transit shows a results
     *  board, not a single drawn path. */
    private fun routeTransit(origin: LatLng, dest: LatLng, timeMode: Int = 0, timeEpochSec: Long? = null) {
        _state.update { it.copy(routes = emptyList(), activeRoute = null, transit = emptyList(), transitLoading = true, status = null) }
        viewModelScope.launch {
            val trips = runCatching { webDirections.transit(origin, dest, timeMode, timeEpochSec) }.getOrDefault(emptyList())
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

    // Auto-advance ARMING: a leg only auto-advances once GPS has been FAR from its end (armed) and
    // then reaches it — so standing at a transfer hub (two leg-ends <40 m apart) can't cascade through
    // legs, a short final walk can't fire a premature "arrived", and it can't double-fire with Next.
    private var transitLegArmed = false
    private val TRANSIT_ARRIVE_M = 40.0
    private val TRANSIT_ARM_M = 90.0 // must have been at least this far from the leg end to arm

    /** Begin guiding through [itin] leg by leg. Speaks the first instruction; GPS auto-advances. */
    fun startTransitNav(itin: TransitItinerary) {
        if (itin.steps.isEmpty()) return
        transitLegArmed = false
        _state.update { it.copy(transitNav = TransitNavState(itin, 0), directionsOpen = false, selected = null) }
        startLocation()
        itin.steps.firstOrNull()?.let { voice.speak(transitStepSpoken(it), interrupt = true) }
    }

    fun advanceTransitNav() {
        val tn = _state.value.transitNav ?: return
        transitLegArmed = false // the new leg must re-arm (leave its end zone) before auto-advancing
        if (tn.isLastStep) {
            _state.update { it.copy(transitNav = tn.copy(arrived = true)) }
            voice.speak(appContext.getString(R.string.transit_nav_arrived), interrupt = true)
            return
        }
        val ni = tn.stepIndex + 1
        _state.update { it.copy(transitNav = tn.copy(stepIndex = ni)) }
        tn.itinerary.steps.getOrNull(ni)?.let { voice.speak(transitStepSpoken(it), interrupt = true) }
    }

    fun backTransitNav() {
        val tn = _state.value.transitNav ?: return
        transitLegArmed = false
        _state.update { it.copy(transitNav = tn.copy(stepIndex = (tn.stepIndex - 1).coerceAtLeast(0), arrived = false)) }
    }

    fun endTransitNav() = _state.update { it.copy(transitNav = null) }

    /** Auto-advance transit guidance when GPS reaches the current leg's end (board/alight stop or the
     *  leg's walk destination). Latched: the leg must first be ARMED by being >TRANSIT_ARM_M from its
     *  end, then advances on entering the TRANSIT_ARRIVE_M radius — one advance per leg, no cascade. */
    private fun maybeAdvanceTransitNav(here: LatLng) {
        val tn = _state.value.transitNav ?: return
        if (tn.arrived) return
        val step = tn.step ?: return
        val end = (if (step.line != null) step.alightStop?.location else step.walkTo) ?: return
        val d = here.distanceTo(end)
        if (d > TRANSIT_ARM_M) transitLegArmed = true
        else if (transitLegArmed && d < TRANSIT_ARRIVE_M) advanceTransitNav()
    }

    /** The spoken cue for a transit leg. */
    private fun transitStepSpoken(step: TransitStep): String =
        if (step.line == null) {
            appContext.getString(R.string.transit_nav_walk, step.durationText ?: "").trim()
        } else {
            val s = StringBuilder(appContext.getString(R.string.transit_nav_take, step.line?.name.orEmpty()))
            step.headsign?.let { s.append(" ").append(appContext.getString(R.string.transit_nav_towards, it)) }
            step.boardStop?.name?.let { s.append(" ").append(appContext.getString(R.string.transit_nav_from, it)) }
            step.alightStop?.name?.let { s.append(". ").append(appContext.getString(R.string.transit_nav_get_off, it)) }
            s.toString()
        }

    fun startNav() {
        val route = _state.value.activeRoute ?: return
        // The pre-nav search's results are stale junk once driving - and the nav bottom slot
        // yields to a NON-EMPTY results list (the in-nav along-route flow), so leftovers from
        // planning made the chooser's Start bar render over a live drive (device 2026-07-14).
        _state.update { it.copy(results = emptyList(), query = "", resultsCollapsed = false) }
        viewModelScope.launch {
            // If they hit Start before a picked alternate finished naming, name it first.
            val named = if (route.provisional) nameIfNeeded(route).also { _state.update { s -> s.copy(activeRoute = it) } } else route
            // Optional Google-style "pass the light, then turn" landmark clauses (off by default) — fetch the
            // route's traffic signals once + fold the clauses into its turns before the session starts.
            val enriched = enrichLightsIfEnabled(named)
            if (enriched !== named) _state.update { it.copy(activeRoute = enriched) }
            // Google-style courtesy: warn once, card + voice, when this drive lands within an hour
            // of the destination's closing time (or after it).
            maybeWarnClosingSoon(enriched)
            // Demo / screenshot / test mode (Settings → Navigation): drive the route as a SYNTHETIC GPS
            // trace instead of using the real fix, so nav can be shown/tested anywhere (a Davis route
            // while the phone is elsewhere). Same replay pipeline as a recorded trip.
            if (settingsPrefs.getBoolean("demo_drive", false)) startDemoDrive(enriched) else launchNav(enriched)
        }
    }

    /** Warn at nav start when the drive arrives within an hour of the destination closing, or after
     *  it — a heads-up card plus the nav voice, so nobody drives forty minutes to a place that locks
     *  its doors on arrival. Closing time comes from the place's own localized status text
     *  ([app.vela.core.data.ClosingTime]); no parsable status, no warning. */
    private fun maybeWarnClosingSoon(route: app.vela.core.model.Route) {
        val sel = _state.value.selected ?: return
        val end = route.polyline.lastOrNull() ?: return
        if (sel.location.distanceTo(end) > 200.0) return // the selected place isn't this trip's destination
        val closing = app.vela.core.data.ClosingTime.closingMinuteOfDay(sel.statusText, sel.openNow) ?: return
        val cal = java.util.Calendar.getInstance()
        val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        // A closing that reads EARLIER than now is past midnight ("Closes 1 AM" seen at 11 PM).
        val closeAbs = if (closing < nowMin) closing + 24 * 60 else closing
        val etaSec = route.durationInTrafficSeconds ?: route.durationSeconds
        val arriveMin = nowMin + (etaSec / 60.0).toInt()
        val gap = closeAbs - arriveMin
        if (gap >= 60) return
        val msg = appContext.getString(
            if (gap < 0) R.string.mapvm_closing_before_arrival else R.string.mapvm_closing_soon,
            sel.name,
            formatMinuteOfDay(closeAbs % 1440),
            formatMinuteOfDay(arriveMin % 1440),
        )
        flashStatus(msg, 15_000L)
        voice.speak(msg)
    }

    /** A minute-of-day in the user's clock format (locale + the system 12/24-hour setting). */
    private fun formatMinuteOfDay(min: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, min / 60)
        cal.set(java.util.Calendar.MINUTE, min % 60)
        return android.text.format.DateFormat.getTimeFormat(appContext).format(cal.time)
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
                // Pass the REAL travel mode: haptics are per-mode (bike buzzes by default, driving
                // doesn't), so a demo of a bike route must buzz like the real ride would.
                navSession.start(route, dest, label, engine, mode = _state.value.travelMode)
                replayOwnsNav = true
                // Demo mode presents as REAL nav, so the ongoing turn notification is part of
                // what's being demoed (and how it gets verified without a drive).
                NavigationService.start(appContext)
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
                    NavigationService.stop(appContext)
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
        // Robust destination lines for the ARRIVE step: name, else address, else the raw
        // coordinates (offline routing can have any of those missing); the address rides along
        // only when it says something the primary line doesn't.
        val (destName, destAddr) = NavSession.destinationDisplay(s.selected?.name, s.selected?.address, dest)
        navSession.start(route, dest, destName, s.selectedEngine?.packageName, stops, s.travelMode, destinationAddress = destAddr.orEmpty())
        NavigationService.start(appContext)
        persistNav(dest, s.selected?.name.orEmpty(), s.travelMode) // so a process-kill mid-drive can resume
        if (_state.value.resumeNavLabel != null) _state.update { it.copy(resumeNavLabel = null) } // starting fresh clears any stale offer
        // Record this trip's GPS trace for later replay, if the user opted in. Read
        // the pref directly so it works even before Settings has been opened.
        if (settingsPrefs.getBoolean("trip_recording_on", false)) {
            tripStore.startTrip(_state.value.selected?.name ?: appContext.getString(R.string.mapvm_trip_default_name), dest, System.currentTimeMillis())
            tripStore.saveRoute(route) // save the blue line + maneuvers so a replay drives THIS route
        }
        // If the phone has no voice engine, say so once instead of going silent - with a pill
        // straight to the voice library. Not when spoken directions are OFF: silence is chosen.
        if (voice.availableEngines().isEmpty() && !voice.muted) {
            showStatus(appContext.getString(R.string.mapvm_no_voice_engine), voiceAction = true)
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

    /** Mute / unmute spoken guidance (the in-nav speaker button). Persisted. */
    fun toggleVoice() = setSpokenDirections(voice.muted)

    /** Turn spoken directions on/off (Settings toggle; the nav mute button shares this state). */
    fun setSpokenDirections(on: Boolean) {
        voice.muted = !on
        settingsPrefs.edit().putBoolean("spoken_directions", on).apply()
        _state.update { it.copy(voiceMuted = !on) }
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

    /** A share/save intent for ALL user lists as a portable JSON file, or null when there
     *  are none. Same FileProvider path as the saved-places export. */
    fun exportListsIntent(): android.content.Intent? {
        val lists = listStore.lists()
        if (lists.isEmpty()) return null
        return runCatching {
            val dir = java.io.File(appContext.cacheDir, "export").apply { mkdirs() }
            val file = java.io.File(dir, "vela-lists.json")
            file.writeText(listStore.exportJson())
            val uri = androidx.core.content.FileProvider.getUriForFile(
                appContext, "${appContext.packageName}.fileprovider", file,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, appContext.getString(R.string.mapvm_export_lists_subject, lists.size))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            android.content.Intent.createChooser(send, appContext.getString(R.string.mapvm_export_lists_chooser))
                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        }.getOrNull()
    }

    /** Import lists from a picked file [uri]; returns how many lists were newly added. */
    fun importListsFromUri(uri: android.net.Uri): Int {
        val json = runCatching {
            appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return 0
        val added = listStore.importMerge(json)
        if (added > 0) _state.update { it.copy(lists = listStore.lists()) }
        return added
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
            // No address survives a process kill (only the label was persisted); destinationDisplay
            // still guarantees SOMETHING shows on the arrive step (label, else the coordinates).
            val (resumedName, _) = NavSession.destinationDisplay(label, null, dest)
            navSession.start(route, dest, resumedName, _state.value.selectedEngine?.packageName, emptyList(), mode)
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

    /** Voice playground: speak arbitrary text through the currently-selected voice. Bypasses the
     *  spoken-directions mute - tapping Speak IS the request for sound. */
    fun speakText(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) voice.speak(t, interrupt = true, ignoreMute = true)
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
        _state.update { it.copy(voiceDownloadingId = id, voiceDownloadPct = 0f, voiceInstalling = false) }
        viewModelScope.launch {
            val ok = kokoroInstaller.download(
                PiperCatalog.downloadUrl(id), VelaPiper.modelDirFor(appContext, id), v.sizeBytes,
                onExtracting = { _state.update { if (it.voiceDownloadingId == id) it.copy(voiceInstalling = true) else it } },
            ) { p -> _state.update { if (it.voiceDownloadingId == id) it.copy(voiceDownloadPct = p) else it } }
            // Clear the downloading state + refresh the installed set in ONE update (no "Download"
            // flicker between finishing and appearing installed).
            _state.update {
                it.copy(
                    voiceDownloadingId = null, voiceDownloadPct = null, voiceInstalling = false,
                    installedVoiceIds = VelaPiper.installedVoiceIds(appContext).toSet(),
                    selectedVoiceId = VelaPiper.effectiveVoiceId(appContext),
                )
            }
            if (ok && VelaPiper.isVoiceReady(appContext, id)) {
                if (firstEver) selectVoice(id, audition = false) else flashStatus(appContext.getString(R.string.mapvm_voice_downloaded, v.displayName))
            } else {
                showStatus(appContext.getString(R.string.mapvm_voice_download_failed, v.displayName))
            }
        }
    }

    // ---- On-device voice search (tier-1 Whisper ASR) ----

    /** Reflect whether the on-device speech model is present (Settings shows Download vs Remove). */
    fun refreshAsr() {
        _state.update { it.copy(asrInstalled = whisperRecognizer.isInstalled()) }
        // Pre-build the Whisper recognizer when the mic would actually use it, so the first
        // dictation listens immediately instead of showing a "Getting ready" beat while the
        // ONNX model loads. refreshAsr runs at VM init and the engine pref rarely changes.
        if (app.vela.ui.VoiceSearch.enabled.value &&
            app.vela.ui.VoiceSearch.engine.value != app.vela.ui.VoiceSearch.Engine.SYSTEM
        ) {
            whisperRecognizer.warmUp()
        }
    }

    /** Download the ~47 MB on-device speech-to-text model, reusing the neural-voice installer + its
     *  no-call-timeout client (the shared 12 s cap would abort a download this size). */
    fun downloadAsrModel() {
        if (_state.value.asrDownloadPct != null) return // serialize
        val bytes = app.vela.voice.AsrModel.SIZE_MB.toLong() * 1024 * 1024
        if (appContext.filesDir.usableSpace < bytes * 13 / 10) {
            showStatus(appContext.getString(R.string.mapvm_not_enough_space, appContext.getString(R.string.settings_voice_search_model), app.vela.voice.AsrModel.SIZE_MB))
            return
        }
        _state.update { it.copy(asrDownloadPct = 0f, asrInstalling = false) }
        viewModelScope.launch {
            val ok = kokoroInstaller.download(
                app.vela.voice.AsrModel.URL, app.vela.voice.AsrModel.dir(appContext), bytes,
                onExtracting = { _state.update { it.copy(asrInstalling = true) } },
            ) { p -> _state.update { it.copy(asrDownloadPct = p) } }
            _state.update { it.copy(asrDownloadPct = null, asrInstalling = false, asrInstalled = whisperRecognizer.isInstalled()) }
            if (ok && whisperRecognizer.isInstalled()) {
                whisperRecognizer.warmUp() // a fresh install should listen immediately on first tap
                flashStatus(appContext.getString(R.string.mapvm_asr_ready))
            }
            else showStatus(appContext.getString(R.string.mapvm_asr_download_failed))
        }
    }

    fun deleteAsrModel() {
        app.vela.voice.AsrModel.dir(appContext).deleteRecursively()
        _state.update { it.copy(asrInstalled = false) }
    }

    fun voiceMicGranted(): Boolean = whisperRecognizer.hasMicPermission()

    /** Record + transcribe on-device (tier-1); returns the heard text or null. Driven by the capture
     *  dialog, which supplies the loudness sink, a start callback and an early-stop check. */
    suspend fun voiceListen(onLevel: (Float) -> Unit, onListening: () -> Unit, cancelled: () -> Boolean): String? =
        whisperRecognizer.listen(onLevel, onListening, cancelled)

    /** Apply a transcript from either voice tier as the query and run the search. */
    fun applyVoiceQuery(text: String) {
        onQueryChange(text)
        search()
    }

    /** Make an already-downloaded voice active: persist the pick, reload the synth (the single switch
     *  trigger), point the engine at the neural synth. [audition] speaks a nav sample - true only for
     *  an explicit pick in the voice library (hearing the voice you chose is the point there); the
     *  install-completion and delete-fallback paths pass false, because a phone that starts talking
     *  on its own right after a download reads as a bug (user 2026-07-10). The Test button remains
     *  the on-demand way to hear the active voice. */
    fun selectVoice(id: String, audition: Boolean = true) {
        if (!VelaPiper.isVoiceReady(appContext, id)) return
        VelaPiper.setSelectedVoiceId(appContext, id)
        piperSynth.reloadVoice() // THE build of the new voice (race-free; runs first on the worker)
        setVoiceEngine(VoiceEngine(VelaPiper.ENGINE_ID, VelaPiper.LABEL)) // route VoiceGuide→neural + persist engine
        refreshInstalledVoices() // selectedVoiceId + per-voice speaker for the variant UI
        if (audition) voice.speak(appContext.getString(R.string.mapvm_voice_sample), interrupt = true)
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
                selectVoice(next, audition = false) // reloads the synth onto `next` (off `id`'s files); refreshes state, then:
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
    fun flashStatus(msg: String, millis: Long = 4500L, voiceAction: Boolean = false, ttsSettings: Boolean = false) {
        statusJob?.cancel()
        _state.update { it.copy(status = msg, statusVoiceAction = voiceAction, statusOpensTtsSettings = ttsSettings) }
        statusJob = viewModelScope.launch {
            delay(millis)
            _state.update { if (it.status == msg) it.copy(status = null, statusVoiceAction = false, statusOpensTtsSettings = false) else it }
        }
    }

    fun dismissPsdsTip() = _state.update { it.copy(showPsdsTip = false) }

    fun recenter() = _state.update { it.copy(center = it.myLocation, recenterTick = it.recenterTick + 1) }

    /** Demo-drive simulates the route with no GPS, so the precise-location nav gate skips it. */
    fun demoDriveOn(): Boolean =
        appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE).getBoolean("demo_drive", false)

    /** Screenshot/demo tool (Settings → "Simulate my location"): pretend to be at the current map
     *  centre. While on, the live GPS collector is suspended and every "your location" (the dot,
     *  the search-distance bias, the directions origin, recenter) reads this point, so the app can
     *  be shown from anywhere without leaking where you actually are. Sibling of demo-drive. */
    fun simulateLocationHere() {
        val here = mapCenter ?: _state.value.myLocation ?: return
        app.vela.ui.SimLocation.set(appContext, here)
        locationJob?.cancel(); locationJob = null // sim owns the puck — no live fixes
        // The timer armed by the collector's LAST fix keeps ticking after the cancel and would
        // grey the pinned dot ~30 s in (same hole as the startLocation() sim branch).
        staleTimerJob?.cancel(); staleTimerJob = null
        _state.update {
            it.copy(myLocation = here, center = here, recenterTick = it.recenterTick + 1, myLocationStale = false)
        }
    }

    /** Turn the simulated location off and resume real GPS. */
    fun stopSimulateLocation() {
        app.vela.ui.SimLocation.set(appContext, null)
        startLocation() // resume the live collector (no-ops if already running)
    }

    fun clearStatus() = _state.update { it.copy(status = null, statusVoiceAction = false, statusOpensTtsSettings = false) }

    fun showStatus(msg: String, voiceAction: Boolean = false) =
        _state.update { it.copy(status = msg, statusVoiceAction = voiceAction, statusOpensTtsSettings = false) }

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
        refreshMaxspeedOverlay(center) // + the posted-speed-limit overlay (read under the puck for the sign)
        refreshTrafficControls(south, west, north, east, zoom) // + traffic lights / stop signs at high zoom
        lastFlockViewport = doubleArrayOf(south, west, north, east, zoom)
        refreshFlock(south, west, north, east, zoom) // + ALPR/Flock cameras when the layer is on
        refreshTransitStops(south, west, north, east, zoom) // + canonical GTFS stop icons at street zoom
        refreshImageryYear(south, west, north, east) // + the capture year for the satellite attribution
        // Half-diagonal of the visible box — used to hand the map only the POIs near the view (the
        // rest can't render anyway), so an old budget phone isn't dragging 800 symbols through the
        // collider every frame.
        val viewRadius = center.distanceTo(LatLng(north, east))
        maybeLoadAmbientPois(center, zoom, viewRadius)
    }

    private var ambientJob: Job? = null
    private var lastAmbientCenter: LatLng? = null
    private var lastAmbientSpan = 9000.0 // span of the last completed ambient fetch (m)
    private var lastAmbientZoom = 0.0
    // LRU (most-recent last, cap 16) of recent ambient fetches — revisiting ANY of the last ~16 areas
    // repaints POIs INSTANTLY (the ~2 s Google floor only hits genuinely-new areas), with no empty-map
    // gap or OSM-POI "small then pop bigger" flash. Entries expire after 30 min so a closed shop doesn't
    // linger all session. Entries carry the fetch SPAN so the hit test knows how far the data reaches.
    private data class AmbientEntry(
        val center: LatLng,
        val spanM: Double,
        val places: List<app.vela.core.model.Place>,
        val atMs: Long,
    )
    private val ambientCache = ArrayDeque<AmbientEntry>()

    private fun cacheAmbient(center: LatLng, spanM: Double, places: List<app.vela.core.model.Place>) {
        ambientCache.removeAll { it.center.distanceTo(center) < 400.0 } // replace a near-duplicate area
        ambientCache.addLast(AmbientEntry(center, spanM, places, android.os.SystemClock.elapsedRealtime()))
        while (ambientCache.size > 16) ambientCache.removeFirst()
        persistAmbientCache()
    }

    // ---- Ambient cache on DISK: the home area paints instantly on a COLD launch too. ----
    // Slim rows via :core's AmbientDiskCache codec (the app stays out of kotlinx.serialization),
    // newest 8 areas x 200 places, debounced writes; entries older than a day drop at load.
    private fun ambientDiskFile() = java.io.File(appContext.filesDir, "ambient_cache.json")
    private var ambientPersistJob: Job? = null

    private fun persistAmbientCache() {
        ambientPersistJob?.cancel()
        // Snapshot on the caller (main) thread: the deque is main-only, and reading it from
        // the IO worker raced cacheAmbient's mutations (a prefetch landing while a persist
        // read = ConcurrentModificationException; review 2026-07-11).
        val snapshot = ambientCache.toList()
        ambientPersistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(2000) // debounce a pan session into one write
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            val entries = snapshot.takeLast(8).map { e ->
                app.vela.core.data.AmbientCachedArea(
                    e.center.lat, e.center.lng, nowWall - (nowElapsed - e.atMs),
                    e.places.take(200).map { app.vela.core.data.AmbientCachedPlace.of(it) },
                    spanM = e.spanM,
                )
            }
            runCatching {
                val tmp = java.io.File(appContext.filesDir, "ambient_cache.json.tmp")
                tmp.writeText(app.vela.core.data.AmbientDiskCache.encode(entries))
                tmp.renameTo(ambientDiskFile())
            }
        }
    }

    private fun loadAmbientCacheFromDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = runCatching { ambientDiskFile().readText() }.getOrNull()
                ?.let { app.vela.core.data.AmbientDiskCache.decode(it) } ?: return@launch
            val nowWall = System.currentTimeMillis()
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val fresh = entries.filter { nowWall - it.atWallMs < 24 * 3600_000L }
            if (fresh.isEmpty()) return@launch
            val loaded = fresh.map { e ->
                // Timestamps read as fresh: the moved-gate refetches the first real view anyway,
                // so disk entries are paint-then-refine, never paint-and-trust.
                AmbientEntry(LatLng(e.lat, e.lng), e.spanM, e.places.map { it.toPlace() }, nowElapsed)
            }
            // Main-thread hop: the cache deque is only ever touched from the main dispatcher.
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (ambientCache.isEmpty()) loaded.forEach { ambientCache.addLast(it) }
            }
        }
    }
    /** Freshest non-stale cached fetch whose centre is within ~900 m of [center], re-centred so its
     *  distances are correct for the new view. Null if nothing recent+near is cached. */
    private fun cachedAmbientNear(center: LatLng): List<app.vela.core.model.Place>? {
        val now = android.os.SystemClock.elapsedRealtime()
        // SPAN-AWARE hit: a fetch covers spanM around its centre (3.5-9 km), so any view whose
        // centre sits well inside that area can repaint from it. The old fixed 900 m radius
        // missed most legitimate revisits (zoom-out-and-back, pan-away-and-return) and forced
        // a full ~2-4 s Google refetch - the P9 "POIs don't stick around" report (2026-07-11).
        return ambientCache
            .filter { now - it.atMs < 30 * 60_000L && it.center.distanceTo(center) < it.spanM * 0.45 }
            .minByOrNull { it.center.distanceTo(center) }
            ?.places
            ?.map { it.copy(distanceMeters = center.distanceTo(it.location)) }
    }

    /** Warm the ambient LRU for the four neighbouring view-sized areas (N/S/E/W at ~0.9 of the
     *  span) so a pan in any direction repaints instantly from cache. Gated HARD: bare map only,
     *  UNMETERED network only (this is real extra traffic: 4 more fan-outs), skips areas already
     *  cached, sequential (never bursts 52 parallel requests at Google), one round per fetch. */
    private var prefetchJob: Job? = null
    private fun prefetchAmbientNeighbours(center: LatLng, span: Double, zoom: Double) {
        if (zoom < 14.5) return // wide views cover the neighbours already
        val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            val dLat = span * 0.9 / 111_320.0
            val dLng = dLat / kotlin.math.cos(Math.toRadians(center.lat)).coerceAtLeast(0.2)
            val neighbours = listOf(
                LatLng(center.lat + dLat, center.lng), LatLng(center.lat - dLat, center.lng),
                LatLng(center.lat, center.lng + dLng), LatLng(center.lat, center.lng - dLng),
            )
            for (n in neighbours) {
                delay(700) // spread the extra load; a real pan cancels via ambientJob's own churn
                val cur = _state.value
                if (cur.navigating || cur.replaying || cur.results.isNotEmpty() || cur.selected != null) return@launch
                if (cachedAmbientNear(n) != null) continue
                val res = runCatching { dataSource.nearbyPlaces(n, span) }.getOrNull() ?: continue
                if (res.isNotEmpty()) cacheAmbient(n, span, res)
            }
        }
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
            if (s.ambientPois.isNotEmpty() || s.ambientCoversView) {
                _state.update { it.copy(ambientPois = emptyList(), ambientCoversView = false) }
            }
            return
        }
        // Coverage check EVERY settle (cheap): the OSM basemap POIs hide only while the view is
        // truly inside the fetched ambient area — outside it they stay, so the map is never
        // iconless past the fetch's ~3.5-9 km span. Fresh fetches below re-tighten this.
        run {
            val covers = s.ambientPois.isNotEmpty() &&
                (lastAmbientCenter?.let { it.distanceTo(center) < lastAmbientSpan * 0.35 } == true) &&
                viewRadiusMeters <= lastAmbientSpan * 0.55
            if (covers != s.ambientCoversView) _state.update { it.copy(ambientCoversView = covers) }
        }
        // Re-query only on a real pan or a real zoom change (not every settle).
        val moved = lastAmbientCenter?.let { it.distanceTo(center) >= 180.0 } ?: true
        val zoomed = abs(zoom - lastAmbientZoom) >= 0.8
        if (!moved && !zoomed && s.ambientPois.isNotEmpty()) return
        ambientJob?.cancel()
        prefetchJob?.cancel() // the old neighbourhood's warm-up is moot once the view moved
        // Span ≈ viewport height: ~9 km at z14 down to ~3.5 km zoomed in (kept ≥3.5 km — tighter
        // than that returns FEWER local hits, per the live calibration).
        val span = (9000.0 / 2.0.pow(zoom - 14.0)).coerceIn(3500.0, 9000.0)
        // Any recent nearby fetch cached (e.g. an area you already visited this session)? Repaint it
        // INSTANTLY so there's no empty→OSM-POI flash→ambient "small then pop bigger" while the network
        // fetch below runs; the fetch then refines it. UNCONDITIONAL on purpose (2026-07-11): the old
        // empty-only gate meant panning BACK to a cached area kept the PREVIOUS area's dots (non-empty,
        // but filtered to nothing in this view) and never consulted the cache - a bare map for the whole
        // refetch, the P9 "tap a POI / pan back and everything is gone" report. The hit is by definition
        // the best-known data for THIS centre; the fetch below still refines it.
        cachedAmbientNear(center)?.let { cached ->
            _state.update { it.copy(ambientPois = keepAmbientForView(cached, viewRadiusMeters)) }
        }
        ambientJob = viewModelScope.launch {
            delay(300) // brief settle so a flick doesn't scrape — but snappy
            // PROGRESSIVE paint: the fan-out streams its accumulated pool as category terms
            // land, so first dots show ~1 s in instead of waiting for the slowest request
            // (the tail was most of the perceived wait; user 2026-07-11). Each partial passes
            // the same bare-map gates as the final.
            fun bareMap(): Boolean {
                val cur = _state.value
                return !(cur.navigating || cur.replaying || cur.results.isNotEmpty() || cur.selected != null)
            }
            // A cancelled fetch's SLOW straggler must not paint: the fan-out children have no
            // suspension point between the blocking HTTP call and the merge, so they outlive
            // cancel() long enough to fire onPartial for the OLD centre - and the moved-gate
            // would then hold the wrong dots on screen (review 2026-07-11). Gate every paint
            // on this launch still being the live one.
            val self = kotlin.coroutines.coroutineContext[Job]
            fun live() = self?.isActive == true
            val res = runCatching {
                dataSource.nearbyPlaces(center, span) { partial ->
                    if (live() && bareMap()) {
                        _state.update { cur ->
                            // A partial never SHRINKS what's painted: after a cache repaint the
                            // early pool is leaner than the rich cached set, and letting it
                            // replace blinked most dots off then back (2026-07-11). The final
                            // ranked pool below always replaces outright.
                            val kept = keepAmbientForView(partial, viewRadiusMeters)
                            if (kept.size >= cur.ambientPois.size) cur.copy(ambientPois = kept) else cur
                        }
                    }
                }
            }.getOrNull() ?: return@launch
            if (!live()) return@launch
            lastAmbientCenter = center
            lastAmbientZoom = zoom
            lastAmbientSpan = span
            cacheAmbient(center, span, res)
            // Re-check we're still on the bare map — the user may have searched/opened a place while we fetched.
            if (!bareMap()) return@launch
            _state.update { it.copy(ambientPois = keepAmbientForView(res, viewRadiusMeters), ambientCoversView = true) }
            // Idle now: quietly warm the four NEIGHBOUR areas into the LRU so panning one screen
            // over paints instantly (unmetered connections only - it's ~4 extra fan-outs).
            prefetchAmbientNeighbours(center, span, zoom)
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
            // neighbour like British Columbia shouldn't be grabbed for a the metro download)
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

    /** Stream the posted-speed-limit overlay covering [center] so the map can read a limit under the puck
     *  ("Speed B"). Streaming-only (no download): MapLibre range-fetches the visible tiles. De-duped so
     *  panning within one region doesn't churn the source. */
    private fun refreshMaxspeedOverlay(center: LatLng? = mapCenter ?: _state.value.myLocation) {
        val c = center ?: return
        viewModelScope.launch {
            val uris = runCatching { maxspeedStore.sourcesFor(c, app.vela.BuildConfig.MAXSPEED_MANIFEST_URL) }.getOrDefault(emptyList())
            if (uris != _state.value.maxspeedOverlays) _state.update { it.copy(maxspeedOverlays = uris) }
        }
    }

    /** The map reports the posted limit (km/h, or null) it read from the streaming overlay layer under the
     *  puck. Only the online source; the offline graph fills [speedLimitKmh] directly. */
    fun onOverlayRoadLimit(kmh: Double?) {
        if (kmh != _state.value.speedLimitOverlayKmh) {
            android.util.Log.i("VelaSpeedB", "overlay maxspeed=$kmh km/h (offline=${_state.value.speedLimitKmh})")
            _state.update { it.copy(speedLimitOverlayKmh = kmh) }
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
    private var flockBox: DoubleArray? = null
    private var transitStopsBox: DoubleArray? = null
    private var transitStopsJob: Job? = null
    private val transitStopCache by lazy { app.vela.data.TransitStopCache(appContext) }
    private var lastFlockViewport: DoubleArray? = null
    private var flockJob: Job? = null

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

    /** Re-fetch (or clear) the Flock layer for the current viewport - called when the toggle flips,
     *  so turning it on shows cameras without needing a pan first. */
    fun refreshFlockNow() {
        lastFlockViewport?.let { refreshFlock(it[0], it[1], it[2], it[3], it[4]) }
    }

    /** Canonical GTFS transit stops for the viewport (Transitous `map/stops`), the same area-cached,
     *  350 ms-debounced contract as the traffic-controls layer. Every ONLINE fetch also refreshes the
     *  DISK cache ([TransitStopCache]) - the offline floor: with no network, a previously visited
     *  area's stops still draw (the OSM basemap icons cover never-visited areas). Stops replace the
     *  OSM bus icons wherever this layer has coverage (VelaMapView hides poi_transit's bus class then). */
    /** The satellite attribution's capture year: while imagery is on, one small keyless Esri
     *  identify at the viewport center tells when this area was photographed (the metadata rides
     *  the same World_Imagery service the tiles come from). Area-cached like the other viewport
     *  fetches; best-effort, the attribution just shows no year until it lands. */
    /** Kick the year fetch the moment satellite flips on (the layers panel calls this) - the
     *  normal path only runs on camera idle, so the year otherwise waited for the first pan. */
    fun onSatelliteToggled() {
        imageryYearBox = null
        viewport?.let { refreshImageryYear(it[0], it[1], it[2], it[3]) }
    }

    private var imageryYearBox: DoubleArray? = null
    private var imageryYearJob: kotlinx.coroutines.Job? = null
    private fun refreshImageryYear(south: Double, west: Double, north: Double, east: Double) {
        if (!app.vela.ui.SatelliteLayer.on.value) {
            imageryYearBox = null
            if (_state.value.imageryYear != null) _state.update { it.copy(imageryYear = null) }
            return
        }
        val cLat = (south + north) / 2; val cLng = (west + east) / 2
        imageryYearBox?.let { b -> if (cLat in b[0]..b[2] && cLng in b[1]..b[3]) return }
        imageryYearJob?.cancel()
        imageryYearJob = viewModelScope.launch {
            val year = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/identify" +
                        "?geometry=$cLng,$cLat&geometryType=esriGeometryPoint&sr=4326&layers=top&tolerance=1" +
                        "&mapExtent=$west,$south,$east,$north&imageDisplay=400,400,96&returnGeometry=false&f=json"
                    val body = http.newCall(okhttp3.Request.Builder().url(url).build()).execute()
                        .use { if (it.isSuccessful) it.body?.string() else null } ?: return@runCatching null
                    val results = org.json.JSONObject(body).optJSONArray("results") ?: return@runCatching null
                    (0 until results.length()).firstNotNullOfOrNull { i ->
                        results.getJSONObject(i).optJSONObject("attributes")
                            ?.optString("DATE (YYYYMMDD)")?.takeIf { d -> d.length >= 4 }?.take(4)
                    }
                }.getOrNull()
            }
            // Cache the area only on SUCCESS - caching a failed/empty identify latched "no
            // year" for the whole box and nothing ever retried (year vanished after one blip,
            // user 2026-07-14). On failure the box stays null, so the next camera idle retries.
            if (year != null) imageryYearBox = doubleArrayOf(south, west, north, east)
            _state.update { it.copy(imageryYear = year) }
        }
    }

    private fun refreshTransitStops(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        if (zoom < TRANSIT_STOPS_MIN_ZOOM) {
            transitStopsBox = null
            transitStopsJob?.cancel()
            if (_state.value.transitStops.isNotEmpty()) _state.update { it.copy(transitStops = emptyList()) }
            return
        }
        val cLat = (south + north) / 2; val cLng = (west + east) / 2
        transitStopsBox?.let { b ->
            val insLat = (b[2] - b[0]) * 0.25; val insLng = (b[3] - b[1]) * 0.25
            if (cLat in (b[0] + insLat)..(b[2] - insLat) && cLng in (b[1] + insLng)..(b[3] - insLng)) return
        }
        transitStopsJob?.cancel()
        transitStopsJob = viewModelScope.launch {
            delay(350)
            val padLat = (north - south) * 0.5; val padLng = (east - west) * 0.5
            val s0 = south - padLat; val n0 = north + padLat; val w0 = west - padLng; val e0 = east + padLng
            val live = withContext(Dispatchers.IO) {
                runCatching { app.vela.core.data.transit.Transitous.stopsInBox(http, s0, w0, n0, e0) }.getOrNull()
            }
            val stops = if (live != null) {
                withContext(Dispatchers.IO) { runCatching { transitStopCache.store(s0, w0, n0, e0, live) } }
                live
            } else {
                // Offline / fetch failed: the disk cache is the floor. Null there too -> keep whatever
                // is drawn (don't blank the layer on a blip) and leave the box unset so we retry.
                withContext(Dispatchers.IO) { runCatching { transitStopCache.lookup(s0, w0, n0, e0) }.getOrNull() } ?: return@launch
            }
            transitStopsBox = doubleArrayOf(s0, w0, n0, e0)
            // One icon per station: bays collapse onto their parent (the board queries the parent
            // anyway), and a same-named directional pair folds into ONE icon at its midpoint whose
            // board carries both directions (Transitous.mergeDirectionalPairs; user 2026-07-13 -
            // the two curbs reading as a doubled stop). Direction-suffixed names differ, so a
            // "NB Station"/"SB Station" pair naturally stays separate.
            val deduped = stops.groupBy { it.parentId ?: it.stopId }.map { (_, group) -> group.first() }
            _state.update { it.copy(transitStops = app.vela.core.data.transit.Transitous.mergeDirectionalPairs(deduped)) }
        }
    }

    /** A tapped Transitous stop icon: open a lightweight place at the stop and fetch its board
     *  DIRECTLY by stop id - no Google resolution, no name correlation. */
    fun onTransitStopTap(stop: app.vela.core.data.transit.Transitous.MapStop) {
        val placeholder = Place(
            id = "gtfs:${stop.stopId}",
            name = stop.name,
            location = app.vela.core.model.LatLng(stop.lat, stop.lon),
            category = "Bus stop",
        )
        reviewsJob?.cancel()
        _state.update {
            it.copy(
                selected = placeholder,
                results = emptyList(),
                center = placeholder.location,
                placesHere = emptyList(),
                reviews = emptyList(),
                stopDepartures = null,
                stopDeparturesLoading = true,
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
            val board = withContext(Dispatchers.IO) {
                runCatching { app.vela.core.data.transit.Transitous.boardFor(http, stop) }.getOrNull()
            }
            _state.update { st ->
                if (st.selected?.id != placeholder.id) st
                else st.copy(stopDepartures = board?.takeIf { it.lines.isNotEmpty() }, stopDeparturesLoading = false)
            }
            if (board != null && board.lines.isNotEmpty()) startBoardRefresh(placeholder.id, stop.lat, stop.lon)
        }
    }

    /** ALPR/Flock cameras for the viewport, when the layer is on. Mirrors [refreshTrafficControls]:
     *  high-zoom only, area-cached (cameras are static), 350 ms debounced, failure not cached. */
    private fun refreshFlock(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        // ALPR cameras are SPARSE landmarks people want from a neighbourhood view (the way
        // maps.deflock.org shows them), not dense street furniture like stop signs - fetch from a wider
        // zoom than the traffic controls. The tag is rare, so the wider Overpass box stays light.
        if (!app.vela.ui.Flock.on.value || zoom < FLOCK_MIN_ZOOM) {
            flockBox = null
            flockJob?.cancel()
            if (_state.value.flockCameras.isNotEmpty()) _state.update { it.copy(flockCameras = emptyList()) }
            return
        }
        val cLat = (south + north) / 2; val cLng = (west + east) / 2
        flockBox?.let { b ->
            val insLat = (b[2] - b[0]) * 0.25; val insLng = (b[3] - b[1]) * 0.25
            if (cLat in (b[0] + insLat)..(b[2] - insLat) && cLng in (b[1] + insLng)..(b[3] - insLng)) return
        }
        val padLat = (north - south) * 0.5; val padLng = (east - west) * 0.5
        val s = south - padLat; val n = north + padLat; val w = west - padLng; val e = east + padLng
        // FAST PATH: the bundled on-device dataset - instant, no Overpass round-trip. This is what makes
        // the layer draw like a tile instead of waiting on a network fetch per viewport (user 2026-07-13).
        if (app.vela.data.FlockCameras.isLoaded) {
            flockJob?.cancel()
            flockJob = viewModelScope.launch {
                val res = withContext(Dispatchers.Default) { app.vela.data.FlockCameras.inBox(s, w, n, e) }
                flockBox = doubleArrayOf(s, w, n, e)
                val kept = capFlock(res, s, n, w, e)
                diag.record("flock", "showing ${kept.size} camera(s) at z${"%.1f".format(zoom)}", "bundled dataset")
                _state.update { it.copy(flockCameras = kept) }
            }
            return
        }
        flockJob?.cancel()
        flockJob = viewModelScope.launch {
            delay(350)
            // fetchInBox returns NULL on failure (network/timeout/non-2xx), an empty list on a clean
            // "no cameras here". Record both to the shareable diagnostic (Settings -> Diagnostics): on
            // GrapheneOS adb logcat can't see app logs, so this is how a "cameras don't show" report is
            // actually diagnosable - the user shares diagnostics and the fetch outcome is right there.
            val res = withContext(Dispatchers.IO) {
                runCatching { app.vela.core.data.OverpassAlprCameras.fetchInBox(http, s, w, n, e) }.getOrNull()
            }
            if (res == null) {
                diag.record("flock", "camera fetch failed at z${"%.1f".format(zoom)}", "Overpass box [$s,$w,$n,$e]")
                android.util.Log.i("VelaFlock", "fetch FAILED zoom=$zoom")
                return@launch
            }
            flockBox = doubleArrayOf(s, w, n, e)
            val kept = capFlock(res, s, n, w, e)
            diag.record("flock", "showing ${kept.size} camera(s) at z${"%.1f".format(zoom)}", if (res.size != kept.size) "fetched ${res.size}, capped" else null)
            android.util.Log.i("VelaFlock", "fetched=${res.size} kept=${kept.size} zoom=$zoom")
            _state.update { it.copy(flockCameras = kept) }
        }
    }

    /** Cap the drawn cameras to the [CONTROLS_ONSCREEN_CAP] NEAREST the box centre (a dense metro cell can
     *  hold hundreds; drawing them all clutters the map and costs tessellation). Shared by both the bundled
     *  and the Overpass paths. */
    private fun capFlock(res: List<app.vela.core.data.AlprCamera>, s: Double, n: Double, w: Double, e: Double): List<app.vela.core.data.AlprCamera> {
        if (res.size <= CONTROLS_ONSCREEN_CAP) return res
        val cLat0 = (s + n) / 2; val cLng0 = (w + e) / 2
        val lngScale = kotlin.math.cos(Math.toRadians(cLat0))
        return res.sortedBy {
            val dLat = it.loc.lat - cLat0; val dLng = (it.loc.lng - cLng0) * lngScale
            dLat * dLat + dLng * dLng
        }.take(CONTROLS_ONSCREEN_CAP)
    }

    // --- Offline ROUTING graphs (Settings → Offline routing) ---------------------------------

    /** Reflect what's installed + fetch the manifest of downloadable region graphs. */
    fun refreshRoutingRegions() {
        _state.update { it.copy(routingInstalledIds = routingGraphStore.installedIds()) }
        viewModelScope.launch {
            val regions = routingGraphStore.manifest(app.vela.BuildConfig.ROUTING_MANIFEST_URL)
            _state.update { it.copy(routingRegions = regions) }
            // The pack catalog too (revs + deltas) — Settings compares it against the installed pack
            // revisions to offer "Update places" on stale regions.
            val packs = poiPackStore.manifest(app.vela.BuildConfig.POI_PACK_MANIFEST_URL)
            _state.update {
                it.copy(
                    poiPackRegions = packs,
                    poiPackInstalledRevs = poiPackStore.installedIds().associateWith { id -> poiPackStore.installedRev(id) },
                )
            }
        }
    }

    /** Download + install [region]'s CH graph for fully-offline routing in that area, then the
     *  region's PLACE pack (whole-region POIs + addresses) so search/geocoding covers it offline too. */
    fun downloadRoutingGraph(region: app.vela.offline.RoutingRegion) {
        if (_state.value.routingDownloadingId != null) return
        _state.update { it.copy(routingDownloadingId = region.id, routingDownloadPct = 0, regionDownloadName = region.name) }
        viewModelScope.launch {
            val ok = routingGraphStore.download(region) { pct ->
                _state.update { it.copy(routingDownloadPct = pct) }
            }
            _state.update {
                it.copy(routingDownloadingId = null, routingInstalledIds = routingGraphStore.installedIds())
            }
            showStatus(if (ok) appContext.getString(R.string.mapvm_offline_routing_ready, region.name) else appContext.getString(R.string.mapvm_offline_routing_failed))
            if (ok) downloadPoiPack(region)
            else _state.update { it.copy(regionDownloadName = null) }
        }
    }

    /** Pull [region]'s offline place pack (best-effort — regions without a pack just skip). The pack
     *  catalog shares the routing catalog's region ids, so the graph's region row looks itself up.
     *  With [update] set, an installed pack is refreshed: by row-level DELTA when the manifest offers
     *  one matching the installed revision (a few MB), else by full re-download. */
    private suspend fun downloadPoiPack(region: app.vela.offline.RoutingRegion, update: Boolean = false) {
        val pack = poiPackStore.manifest(app.vela.BuildConfig.POI_PACK_MANIFEST_URL)
            .firstOrNull { it.id == region.id }
        val installed = region.id in poiPackStore.installedIds()
        if (pack == null || (installed && !update)) {
            _state.update { it.copy(regionDownloadName = null) }
            return
        }
        _state.update { it.copy(poiPackDownloadingId = pack.id, poiPackDownloadPct = 0, regionDownloadName = region.name) }
        val canDelta = installed && pack.deltaUrl != null && poiPackStore.installedRev(pack.id) == pack.deltaFromRev
        var ok = false
        if (canDelta) {
            ok = poiPackStore.applyDelta(pack) { pct -> _state.update { it.copy(poiPackDownloadPct = pct) } }
        }
        if (!ok) { // no delta path (or it failed) → full download replaces the pack
            ok = poiPackStore.download(pack) { pct -> _state.update { it.copy(poiPackDownloadPct = pct) } }
        }
        _state.update {
            it.copy(
                poiPackDownloadingId = null, regionDownloadName = null,
                poiPackInstalledIds = poiPackStore.installedIds(),
                poiPackInstalledRevs = poiPackStore.installedIds().associateWith { id -> poiPackStore.installedRev(id) },
            )
        }
        if (ok) showStatus(appContext.getString(R.string.mapvm_poipack_ready, region.name))
    }

    /** Settings "Get places" / "Update places" on an installed routing region — pulls or refreshes just
     *  the place pack. Says so when the region has no pack published yet (the catalog builds out region
     *  by region), instead of silently doing nothing. */
    fun downloadPoiPackFor(region: app.vela.offline.RoutingRegion, update: Boolean = false) {
        if (_state.value.poiPackDownloadingId != null || _state.value.routingDownloadingId != null) return
        viewModelScope.launch {
            val available = poiPackStore.manifest(app.vela.BuildConfig.POI_PACK_MANIFEST_URL)
                .any { it.id == region.id }
            if (!available) {
                showStatus(appContext.getString(R.string.mapvm_poipack_unavailable, region.name))
                return@launch
            }
            downloadPoiPack(region, update = update)
        }
    }

    fun deleteRoutingGraph(id: String) {
        routingGraphStore.delete(id)
        poiPackStore.delete(id) // the place pack rides with the region — remove them together
        _state.update {
            it.copy(routingInstalledIds = routingGraphStore.installedIds(), poiPackInstalledIds = poiPackStore.installedIds())
        }
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

    /** How many offline address+street rows are indexed. Settings uses this to decide whether to nudge a
     *  user whose SAVED areas predate the geocoder (they have tiles/POIs but no address data). */
    fun offlineAddressCount(cb: (Int) -> Unit) {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching { addressStore.count() + addressStore.streetCount() }.getOrDefault(0)
            }
            cb(n)
        }
    }

    /** Re-fetch offline POIs + the address/street index for every already-saved map area, so areas
     *  downloaded before the geocoder existed become address-searchable without the user hunting each one
     *  down. Each area runs the same padded fetch as a fresh download. */
    fun refreshOfflineDataForSavedAreas() {
        app.vela.offline.OfflineMaps.list(appContext) { regions ->
            regions.forEach { r ->
                app.vela.offline.OfflineMaps.boundsOf(r)?.let { b ->
                    downloadOfflinePois(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
                }
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
        // Show ALPR cameras from ROUTE-OVERVIEW zoom (~z11-12), the "I know this route has cameras but
        // don't see any" view. z11 was tried 2026-07-13 and reverted the same day because the padded
        // Overpass box (~16x bigger) with a full-body read + full-DOM parse per pan OOM'd the heap; the
        // follow-up note said route-overview visibility needed a path without the giant box. That path
        // exists now: the BUNDLED on-device dataset answers fetchInBox with no network and the Overpass
        // fallback stream-parses, so z11 is back (alltechdev re-proved it in the vela-dpad fork, #131).
        const val FLOCK_MIN_ZOOM = 11.0
        const val SUGGEST_NEAR_M = 80_000.0 // ~a metro radius: suggestions inside it rank first
        const val TRANSIT_STOPS_MIN_ZOOM = 15.0 // GTFS stop icons from street-ish zoom (denser than cameras)
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
