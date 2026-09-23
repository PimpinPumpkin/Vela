package app.vela.ui.map

import android.content.Context
import app.vela.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vela.core.config.CalibrationStore
import app.vela.core.config.Notice
import app.vela.core.data.CalibrationNeededException
import app.vela.core.data.MapDataSource
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
import app.vela.ui.formatDuration
import app.vela.core.model.bearingTo
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which directions endpoint the "Choose on map" crosshair is currently setting. */
enum class MapPick { ORIGIN, STOP, DEST }

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

/** A match found in the user's OWN data (issue #180) as they type, surfaced above the network
 *  suggestions, instantly and offline. A recent QUERY re-runs the search; anything place-backed
 *  ([place] non-null: a saved-list place, a saved shortcut, or a recently-viewed place) opens
 *  that place directly. [removable] rows carry the X to drop them from history. */
data class LocalSuggestion(
    val kind: Kind,
    val label: String,
    val sublabel: String?,
    val place: Place? = null,
    val query: String? = null,
    val removable: Boolean = false,
    /** CONTACT rows: the address book's label for this address ("Home", "Work"), platform-localized. */
    val badge: String? = null,
    /** CONTACT rows: the contact's thumbnail content: URI, if they have a photo. */
    val photoUri: String? = null,
) {
    // CONTACT rows (issue #243) carry the contact's postal address as [query]. Picking one
    // geocodes that address and opens the result under the CONTACT'S name (see
    // MapViewModel.openContactAddress); the address string is the only thing that leaves the phone.
    enum class Kind { RECENT_QUERY, RECENT_PLACE, SAVED_PLACE, CONTACT }
}

data class MapUiState(
    val center: LatLng? = null,
    // Camera zoom requested by a deep link (geo:...?z=17); one-shot - any ordinary selection
    // (place tap, long-press, search) clears it back to the default framing zooms.
    val centerZoom: Double? = null,
    // The satellite imagery's capture year for the viewport (Esri metadata), shown in the attribution.
    val imageryYear: String? = null,
    // Deep satellite imagery for this area (issue #244): 0 = none known, 20..22 = Esri serves
    // native tiles to that level here, -1 = Esri tops out at 19 so the Google imagery fallback
    // fills the deep zooms instead. Probed per area from Esri's own tilemap availability index.
    val satDeep: Int = 0,
    val recenterTick: Int = 0, // bumped per recenter tap so the map force-moves even if "centered"
    val myLocation: LatLng? = null,
    val myBearing: Float? = null,
    val mySpeed: Float? = null, // meters/second, from GPS (spike-filtered, held briefly on speedless fixes)
    val myFixRaw: LatLng? = null, // the last ACCEPTED fix before the parked-hold low-pass; the free-drive follow integrates from this while moving
    val mySpeedRaw: Float? = null, // THIS fix's own measured speed (doppler or derived) — null when the
                                   // fix carried none. The puck's Kalman measures ONLY from this: feeding
                                   // it the held mySpeed re-injected a stale braking speed at high gain
                                   // every fix, which is what kept the puck "moving" at a red light.
    // Posted limit read STREAMING from the hosted maxspeed PMTiles overlay (the map queries the invisible
    // overlay layer under the puck). The "Speed B" online source used when the offline graph can't answer
    // ([speedLimitKmh] null) - so a limit shows anywhere online without a downloaded region.
    val maxspeedOverlays: List<String> = emptyList(), // pmtiles://https:// source URIs covering the view
    val placesOverlays: List<String> = emptyList(),   // open-data places layer (Overture PMTiles), file:// or streamed
    val hiddenOpenPlaceIds: Set<String> = emptySet(), // open places whose Google listing is permanently closed (persisted)
    val ambientClosed: List<Place> = emptyList(), // permanently closed places in the last nearby answer; Both mode hides their open twins
    val placesPending: Boolean = false, // the open places source is on and its first lookup has not answered
    // The covering places archive was baked with the one-set bake (parks, temples, schools and
    // museums inside it, ranked with the shops): the basemap's own point layers then hide.
    val placesOneSet: Boolean = false,
    val speedLimitOverlayKmh: Double? = null,
    val speedLimitKmh: Double? = null, // posted limit of the current road (OSM maxspeed via the obf engine),
                                       // km/h; null = unknown/untagged/no offline graph → badge hidden.
                                       // Converted to the display unit at the badge.
    val navStarved: Boolean = false, // navigating but guidance hasn't received a usable (GPS, ≤50 m
                                     // accuracy) fix in a while — drives the "Searching for GPS" chip
                                     // when coarse fixes keep the ordinary stale timer from firing
    val compassHeading: Float? = null, // device facing (rotation-vector sensor) — browse cone when stopped
    val myLocationStale: Boolean = true, // gray the dot until/unless a live fix is recent
    val myAccuracyM: Float? = null, // the last live fix's reported accuracy radius (m); null = unknown/simulated
    val parkingSpot: LatLng? = null, // one-tap "parked here" pin — survives restarts (prefs)
    val parkedAtMillis: Long = 0L,   // when it was saved (for the sheet/history labels)
    val parkingHistory: List<app.vela.core.model.ParkedSpot> = emptyList(), // recent saves, newest first — accidental-overwrite insurance
    val lists: List<app.vela.core.model.PlaceList> = emptyList(), // user place-lists (issue #1), newest first
    val openListId: String? = null, // the list currently shown as results (its name is in the bar)
    val pendingImport: app.vela.core.model.ImportedList? = null, // an imported Google list shown but NOT yet saved
    val offline: Boolean = false, // no usable internet — drives the subtle offline indicator
    // Active network is satellite / bandwidth-constrained (issue #235): Vela declares itself
    // satellite-optimized in the manifest, so it also has to behave like it - photos and the
    // fat ambient fan-out step aside while this is true.
    val lowData: Boolean = false,
    val query: String = "",
    val results: List<Place> = emptyList(),
    // The query whose results can page further ("More results" row at the end of the list);
    // null when the list came from somewhere else or is exhausted. Compared against [query].
    val resultsMoreQuery: String? = null,
    val resultsLoadingMore: Boolean = false,
    val ambientPois: List<Place> = emptyList(), // Google places for the visible area, shown on the bare browse map
    // True while the CURRENT viewport sits inside the area the ambient Google fetch covered —
    // the basemap OSM POIs hide only then, so panning/zooming past the fetched area blends the
    // OSM icons back in instead of leaving the outskirts iconless (user 2026-07-10).
    val ambientCoversView: Boolean = false,
    val suggestions: List<Place> = emptyList(),
    /** Bare query rows from the provider's autocomplete ("Starbucks" + "See locations"): run as a search. */
    val querySuggestions: List<String> = emptyList(),
    /** Counts the times [query] was set from outside the keyboard (fill-in arrow, voice); the search field moves its cursor to the end on each. */
    val queryEdits: Int = 0,
    // Matches from the user's OWN recents + lists (issue #180), shown above the network suggestions
    // as they type, instant and offline.
    val localSuggestions: List<LocalSuggestion> = emptyList(),
    val selected: Place? = null,
    /** Id of the tapped placeholder whose Google lookup is still in flight: the sheet shows its
     *  loading skeleton while [selected] still has this id. */
    val tapResolvingFor: String? = null,
    /** (resolved listing id, tapped placeholder id): the sheet keeps the placeholder's identity
     *  when the tap resolves to a listing, so the open sheet updates in place. */
    val sheetAlias: Pair<String, String>? = null,
    /** Id of a tapped placeholder whose Google lookup finished without a match: the sheet says so
     *  under the name, with where the map's row came from. */
    val tapUnlinkedFor: String? = null,
    val placesHere: List<Place> = emptyList(), // other Google listings at the selected spot
    val reviews: List<Review> = emptyList(),
    val reviewsLoading: Boolean = false,
    /** Google answered the review feed with its limited view (a short list, no more pages). */
    val reviewsLimited: Boolean = false,
    /** Next page of the native review feed, when Google said there is one: "More reviews". */
    val reviewsNextToken: String? = null,
    val reviewsMoreLoading: Boolean = false,
    val reviewsFound: Int = 0, // live count streamed by the scrape while reviewsLoading (progress, not final)
    val photosLoading: Boolean = false, // the lazy WebView gallery scrape is in flight (more photos coming)
    /** Feature id whose photo strip holds only the FIRST BATCH: the sheet offers "More photos". */
    val morePhotosFor: String? = null,
    /** Cursor for the next native gallery page ("More photos" = one request of 10). */
    val photosNextToken: String? = null,
    val loadingDetails: Boolean = false, // the lazy WebView detail fetch (popular times etc.) is in flight
    val routes: List<Route> = emptyList(),
    val activeRoute: Route? = null,
    // ALPR/Flock cameras counted near each route option (index-aligned with `routes`), for the route
    // picker's opt-in "passes N cameras" badge. Empty when the alert is off or not yet computed.
    val flockOnRoute: List<Int> = emptyList(),
    // Drive nav, "tap places while driving": the place a tap offered as a stop, waiting for the
    // confirm. Never acts on the first tap - a stray touch must not change the drive.
    val navTapCandidate: Place? = null,
    // What that stop would cost, in whole minutes, once the check comes back (null while it is in
    // flight, and whenever the two figures are too close or too far apart to mean anything).
    val navTapDetourMin: Int? = null,
    // Bumped by every offer, including a second tap on the same place: the card's countdown keys
    // on it, and an unchanged candidate would otherwise leave the old clock running.
    val navTapOfferTick: Int = 0,
    val buildingOverlays: List<String> = emptyList(), // full pmtiles:// URIs (file:// downloaded / https:// streamed for the view)
    val addressOverlays: List<String> = emptyList(), // pmtiles:// URIs streamed for house-number labels (OpenAddresses)
                                                      // .pmtiles — rendered beneath OSM to fill gaps
    val trafficControls: List<app.vela.core.data.TrafficControl> = emptyList(), // OSM lights+stop signs drawn at high zoom
    val flockCameras: List<app.vela.core.data.AlprCamera> = emptyList(), // ALPR/Flock cameras (DeFlock/OSM), high zoom
    // The route bar's model (issue #228): what is on the road AHEAD - congestion bands plus the
    // static road furniture already fetched along the corridor. Recomputed on progress, not per
    // frame; empty whenever the bar has nothing honest to show.
    val routeBar: app.vela.core.nav.RouteBar.Model? = null,
    val routeBarEnabled: Boolean = false,
    val speedCameras: List<app.vela.core.data.SpeedCamera> = emptyList(), // fixed radar cameras (OSM), opt-in layer
    val transitStops: List<app.vela.core.data.transit.Transitous.MapStop> = emptyList(), // canonical GTFS stops (Transitous), high zoom
    val directionsOpen: Boolean = false,
    val directionsReversed: Boolean = false, // route from the place back to you
    val directionsOrigin: Place? = null,     // custom "From" (null = your live location)
    val pickingOrigin: Boolean = false,      // the next search pick sets the origin, not a destination
    val pickingDest: Boolean = false,        // the next search pick REPLACES the destination (issue #170)
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
    // Preferred transit vehicle kinds (issue #431): 0 bus, 1 subway, 2 train, 3 tram. Empty = any.
    val transitPrefer: Set<Int> = emptySet(),
    val transit: List<TransitItinerary> = emptyList(),
    val transitLoading: Boolean = false,
    // One time per travel mode for the chooser's mode chips ("25 min" under the car glyph), the
    // way Google's chips read. The current mode's entry is its own route set; the others are
    // prefetched in the background by [MapViewModel.prefetchModeEtas]. Missing = not known yet.
    val modeEtas: Map<TravelMode, String> = emptyMap(),
    val transitNav: TransitNavState? = null,
    // The transit itinerary whose drill-down row is EXPANDED in the chooser — the map draws its
    // legs (issue #233: colored ride lines through the stops, dotted walk links) while it's open.
    val transitPreview: TransitItinerary? = null,
    // A transit stop's live departure board (keyless, from the station's own place page).
    val stopDepartures: app.vela.core.model.StopDepartures? = null,
    val stopDeparturesLoading: Boolean = false,
    val stopDeparturesCachedAt: Long? = null,   // set when the board is the OFFLINE copy (epoch ms it was seen)
    // The id of the place the board belongs to. The sheet renders the board ONLY when this matches
    // the selected place: writers are guarded, but selection paths that don't clear the board (a
    // saved/recent place open) let the previous stop's departures render on an unrelated place
    // (device report 2026-07-16: a house showing another region's intercity rail board).
    val stopDeparturesFor: String? = null,
    // Route detail (tap a route on the board -> its stop timeline with times, tap-through to stops).
    // Reuses a ride leg from a transit itinerary (its board/intermediate/alight stops carry the times).
    val routeDetail: TransitStep? = null,
    val routeDetailTitle: String? = null,
    val routeDetailLoading: Boolean = false,
    // In-app Street View: the resolved pano (null until metadata lands), the stitched equirect
    // bitmap (null while tiles load), and a loading flag covering both fetches. The full-screen
    // sphere viewer shows while streetView != null || streetViewLoading.
    val streetView: app.vela.core.model.StreetViewPano? = null,
    val streetViewBitmap: android.graphics.Bitmap? = null,
    val streetViewLoading: Boolean = false,
    // The date currently DISPLAYED (may differ from the base pano's when time-traveling), and
    // whether that's a historical capture (hides the walk arrows - you look around history, you
    // don't walk it).
    val streetViewShownYear: Int? = null,
    val streetViewShownMonth: Int? = null,
    val streetViewHistorical: Boolean = false,
    val navigating: Boolean = false,
    /** The drive is held: route and figures frozen, puck free, nothing spoken or rerouted. */
    val navPaused: Boolean = false,
    val resumeNavLabel: String? = null, // a nav session was interrupted (process killed mid-drive) and can
                                        // be resumed — drives the "Resume navigation to <label>?" prompt
    val navCameraDetached: Boolean = false,
    // Nav camera orientation toggle (user 2026-07-15): false = heading-up (default, Google's),
    // true = north-up while still following the puck. Flipped by the in-nav compass button.
    val navNorthUp: Boolean = false,
    val voiceMuted: Boolean = false,
    val diagnosticsEnabled: Boolean = false,
    val tripRecordingEnabled: Boolean = false, // record nav GPS traces for replay (more invasive)
    val nameTripsOnSave: Boolean = false, // ask for a name each time a recorded drive is saved
    // A drive just finished and the user asked to be prompted for its name; the prompt shows on
    // the map so it is answered while the drive is fresh, not found later in Settings.
    val tripToName: app.vela.replay.TripMeta? = null,
    val replaying: Boolean = false,            // a recorded trip OR a demo drive is playing (drives the puck)
    val demoDriving: Boolean = false,          // replaying is a Settings→demo synthetic drive (not a recorded trip) — nav chrome only, no "Stop replay" pill
    val arrived: Boolean = false,
    val nav: NavState = NavState(),
    // Foreign road name -> its basemap Latin name (issue #184), grown from map tiles as the drive
    // proceeds; the banner/steps show the real romanized name where we have one. Empty otherwise.
    val roadNameLatin: Map<String, String> = emptyMap(),
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
    val asrDownloadPct: Float? = null,      // 0f..1f while an on-device voice-search engine downloads; null = idle
    val asrInstalling: Boolean = false,     // ASR download done, unpacking
    val asrDownloadingId: String? = null,   // which AsrEngine.id is downloading (Settings row shows progress on it), else null
    val asrInstalledIds: Set<String> = emptySet(), // which voice-search engines are on disk (Whisper/SenseVoice/Moonshine)
    val asrActiveId: String = app.vela.voice.AsrEngine.DEFAULT.id, // the engine the mic will use
    val voiceSpeaker: Int = 0, // chosen speaker # for the multi-speaker Vela voice (playground stepper)
    val voiceSpeed: Float = 1.0f, // spoken-directions speed multiplier (1.0 = normal, >1 = faster)
    val showPsdsTip: Boolean = false,
    val showSearchThisArea: Boolean = false,
    val showSteps: Boolean = false,
    val previewStepIndex: Int? = null,
    val styleUri: String = MapStyle.DEFAULT.uri,
    // Route preference toggles (drive): honored on-device where the region graph carries the
    // avoid profiles; online falls back to a normal route (the public OSRM can't exclude).
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
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
    /** A downloaded update held back because installing it would cost this phone its Android Auto
     *  listing (issue #179). The user chooses: save the file for AAEnabler, or install anyway. */
    val updateApkPending: java.io.File? = null,
    val updateDownloadPct: Int? = null, // non-null while the update APK downloads
    // Offline routing (downloadable per-region CH graphs — Settings → Offline routing)
    val routingRegions: List<app.vela.offline.RoutingRegion> = emptyList(),
    val routingInstalledIds: Set<String> = emptySet(), // region ids whose graphs are on disk
    val routingOffer: app.vela.offline.RoutingRegion? = null, // one-time "download routing for your area" prompt
    val regionExtrasMb: Map<String, Int> = emptyMap(), // places + offline map MB a region download adds, by routing region id
    val routingDownloadingId: String? = null,          // region id currently downloading, else null
    val routingDownloadPct: Int = 0,
    val regionDownloadName: String? = null,            // display name for the heads-up download card
    // "Download all of <country>": the pieces still waiting behind the one downloading, and the
    // total the batch started with, so the group row can say "3 of 16".
    // The installed offline basemap archive covering the view (pmtiles://file://...), or null: the
    // map swaps its tile source to it, so a downloaded region draws with no signal.
    val basemapArchive: String? = null,
    // Installed region data with a newer bake published: region id -> which pieces ("routing",
    // "places", "map"). The place pack's own rev check stays as it was (poiPackRegions).
    val regionUpdates: Map<String, List<String>> = emptyMap(),
    val regionQueueLeft: Int = 0,
    val regionQueueTotal: Int = 0,
    val areaDownloadPct: Int? = null,                  // non-null while a map-area tile download runs
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
    private val regionCatalog: app.vela.offline.RegionCatalog,
    private val poiPackStore: app.vela.offline.PoiPackStore,
    private val obfStore: app.vela.offline.ObfStore,
    private val overlayStore: app.vela.offline.OverlayTileStore,
    private val maxspeedStore: app.vela.offline.MaxspeedOverlayStore,
    private val placesStore: app.vela.offline.PlacesTileStore,
    private val basemapStore: app.vela.offline.BasemapTileStore,
    private val routeEngine: app.vela.core.data.RouteEngine,
    private val http: okhttp3.OkHttpClient,
    private val selfUpdater: app.vela.update.SelfUpdater,
    private val asrRecognizer: app.vela.voice.AsrRecognizer,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    private var destination: LatLng? = null
    private var mapCenter: LatLng? = null
    private var locationJob: Job? = null
    private var staleTimerJob: Job? = null
    @Volatile private var lastVoiceLangHinted: String? = null // last language we told the user they lack a
                                                              // voice for — so the hint shows once, not per prompt
    @Volatile private var lastLimitLoc: LatLng? = null // last fix the road speed-limit was computed at —
                                                       // the snap is only re-run after moving ~a road-segment
    @Volatile private var lastLimitHitLoc: LatLng? = null // last fix that RESOLVED a limit — drives the
                                                          // "forget a stale limit after driving far off it" clear
    private var limitJob: Job? = null // single-flight the off-thread maxspeed snap
    private val noticePrefs = appContext.getSharedPreferences("vela_notices", Context.MODE_PRIVATE)

    // The nav side (start/stop/demo/replay, the nav-state observer, dead reckoning, the corridor
    // fetches, the route bar, resume) lives in NavController; the view model keeps the shared
    // state and forwards. Declared ABOVE the init block: the controller's observer runs its first
    // pass inline inside init (issue #474 rule).
    private val navHost = object : NavController.Host {
        override var destination: LatLng?
            get() = this@MapViewModel.destination
            set(v) { this@MapViewModel.destination = v }
        override var controlsBox: DoubleArray?
            get() = this@MapViewModel.controlsBox
            set(v) { this@MapViewModel.controlsBox = v }
        override fun cancelViewportControls() { this@MapViewModel.controlsJob?.cancel() }
        override var autoStartOnRoute: Boolean
            get() = this@MapViewModel.autoStartOnRoute
            set(v) { this@MapViewModel.autoStartOnRoute = v }
        override fun startLocation() = this@MapViewModel.startLocation()
        override fun pauseLiveLocation() {
            locationJob?.cancel(); locationJob = null
            staleTimerJob?.cancel(); staleTimerJob = null
        }
        override fun restartStaleTimer() = this@MapViewModel.restartStaleTimer()
        override fun flashStatus(msg: String, millis: Long) = this@MapViewModel.flashStatus(msg, millis)
        override fun showStatus(msg: String, voiceAction: Boolean) = this@MapViewModel.showStatus(msg, voiceAction)
        override fun updateSpeedLimit(here: LatLng) = this@MapViewModel.updateSpeedLimit(here)
        override fun clearSpeedLimit() = this@MapViewModel.clearSpeedLimit()
        override fun clearSelection() = this@MapViewModel.clearSelection()
        override fun neuralSynthFor(engineId: String?): PiperSynth? = this@MapViewModel.neuralSynthFor(engineId)
        override suspend fun nameIfNeeded(route: Route): Route = this@MapViewModel.nameIfNeeded(route)
        override suspend fun roadFeaturesCoverRoute(poly: List<LatLng>): RoadCover = this@MapViewModel.roadFeaturesCoverRoute(poly)
        override fun sanePosition(here: LatLng, prev: LatLng?, lastSpeed: Float?, dt: Double, outlierStreak: IntArray): LatLng =
            this@MapViewModel.sanePosition(here, prev, lastSpeed, dt, outlierStreak)
        override fun gateMeasuredSpeed(raw: Float, dt: Double): Float? = this@MapViewModel.gateMeasuredSpeed(raw, dt)
        override fun onNavRoadLatin(map: Map<String, String>) = this@MapViewModel.onNavRoadLatin(map)
    }
    private val nav = NavController(appContext, viewModelScope, _state, navSession, locationProvider, dataSource, tripStore, voice, diag, http, navHost)


    init {
        loadAmbientCacheFromDisk() // ambient LRU survives restarts (paint-then-refine)
        warmWebViewsWhenQuiet() // boot the hidden WebViews at a quiet moment, not at the first place tap
        loadOpenPlaceLinks() // Overture -> Google links remembered from earlier sessions
        // Privacy toggle (Settings -> Data & privacy): periodic in-drive traffic re-checks send
        // the CURRENT position to Google; the opt-out lives on the session so :core enforces it.
        // (Raw prefs read: the settingsPrefs property is declared below this init block.)
        navSession.liveRechecks = appContext
            .getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
            .getBoolean("nav_live_rechecks", true)
        // Trip flight recorder (all no-ops unless a trip is recording): every line the voice
        // actually speaks, a 30 s UI frame-pacing sample, and battery every ~2 min - so a shared
        // trip answers "what did it say", "was it actually dropping frames" and "what did the
        // drive cost" by itself.
        voice.onSpoken = { tripStore.note("S", it) }
        navSession.onNote = { tripStore.note("K", it) } // nav decisions (rechecks, reroutes, swaps)
        // The GraphHopper graphs retired 2026-09-15: reclaim any old install and say so once.
        viewModelScope.launch(Dispatchers.IO) {
            val gone = app.vela.offline.LegacyGraphs.purge(appContext.filesDir)
            if (gone.isNotEmpty()) withContext(Dispatchers.Main) { showStatus(appContext.getString(R.string.mapvm_graphs_retired)) }
        }
        viewModelScope.launch {
            var beat = 0
            while (true) {
                kotlinx.coroutines.delay(30_000)
                if (!_state.value.navigating) { app.vela.ui.map.FrameJank.sampleAndReset(); continue }
                tripStore.note("J", app.vela.ui.map.FrameJank.sampleAndReset())
                if (beat++ % 4 == 0) {
                    val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                    val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    if (pct in 0..100) tripStore.note("B", "$pct")
                }
            }
        }
        // Region bias for the scrape (gl=): the cell network's country is the honest "where is
        // the phone" signal (roaming-aware, no GPS wait, no permission), falling back to the
        // locale's region. Refreshed each launch. Fixes the US-shaped results a non-US user got
        // regardless of language (the gl half of issue #71); a US phone is byte-identical.
        (dataSource as? app.vela.core.data.google.GoogleMapsDataSource)?.glRegion = runCatching {
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: java.util.Locale.getDefault().country.takeIf { it.isNotBlank() }
        // A simulated location (Settings → demo) wins the seed so the app opens "there".
        val seed = app.vela.ui.SimLocation.point.value ?: locationProvider.lastKnown()
        _state.update { it.copy(center = seed, myLocation = it.myLocation ?: seed) }
        // Decide the offline basemap BEFORE the first style load: a process that starts with no
        // signal and loads the remote style first leaves the engine's glyph and sprite managers
        // with requests that never answer, and a local style loaded afterwards never completes a
        // labeled tile (device, 2026-09-14). Starting on the local style avoids that entirely.
        refreshBasemapArchive(seed)
        app.vela.offline.GlyphPackStore.ensureSprite(appContext)
        nav.maybeOfferResume() // a drive that was cut off by a process-kill → offer to pick it back up
        // Warm the contacts-address cache (issue #243) so the first keystroke's synchronous local
        // match has data — the per-keystroke path must never hit the contacts provider itself.
        viewModelScope.launch(Dispatchers.IO) {
            if (app.vela.ui.ContactsSearch.enabled.value) app.vela.data.ContactAddresses.ensureLoaded(appContext)
        }
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
        // The neural voice loads when the route chooser opens (warmVoiceForRoute), not at launch: it
        // holds its model for the whole session, and a drive always passes through the chooser a few
        // seconds before the first spoken prompt.
        val voicePrefs = appContext.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)
        val savedSpeed = voicePrefs.getFloat("voice_speed", calibration.current().defaultVoiceSpeed)
        voice.setRate(savedSpeed) // relay the saved rate to the AOSP TTS engine at startup
        voice.setVolume(voicePrefs.getFloat("voice_volume", 1.0f)) // + the guidance volume (issue #245)
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
        // Fleet default map color set (a user's own Settings pick always wins - see MapColors).
        app.vela.ui.MapColors.remoteDefault.value = calibration.current().defaultMapPalette
        app.vela.ui.MapPoiPrefs.setRemoteDefault(calibration.current().defaultPlacesSource)
        app.vela.ui.RoutePicker.setRemoteDefault(calibration.current().classicRoutePicker)
        adoptKeywordTables()
        // Pull the latest scraper calibration from the repo (non-blocking, once),
        // then surface any freshly-pushed notices.
        viewModelScope.launch {
            runCatching { calibration.refresh() }
            refreshNotices()
            app.vela.ui.MapColors.remoteDefault.value = calibration.current().defaultMapPalette
            app.vela.ui.MapPoiPrefs.setRemoteDefault(calibration.current().defaultPlacesSource)
            app.vela.ui.RoutePicker.setRemoteDefault(calibration.current().classicRoutePicker)
            adoptKeywordTables()
        }
        maybeCheckForUpdate()

        // Returning to the app mid-drive re-attaches the follow camera (Google's behavior). A
        // stray pan while backgrounding often left it detached, so the map sat wherever it was
        // until a manual Re-center tap.
        viewModelScope.launch {
            app.vela.ui.AppVisibility.foreground.collect { fg ->
                if (fg && _state.value.navigating && _state.value.previewStepIndex == null &&
                    _state.value.navCameraDetached
                ) {
                    _state.update { it.copy(navCameraDetached = false) }
                }
            }
        }

        nav.bind()
    }

    /** Decide the displayed position from a new fix. Rejects GPS OUTLIERS — a coarse NETWORK /
     *  multipath fix that leaps hundreds of meters (the "every ~8 s the dot + distance + mph jump
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
            // fixes, so a leftover timer grayed it ~30 s in and nothing ever turned it blue again.
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
                var lastPushMs = 0L
                headingProvider.headings().collect { az ->
                    if (_state.value.navigating) return@collect
                    // AUDIT FIX 5 (2026-07-15): a wall-clock floor beside the 2-degree gate. A
                    // hand-held phone crosses 2 degrees many times a second, and each push
                    // recomposes the WHOLE MapScreen off this one field - 5-16 recompositions/s
                    // during exactly the pan gestures that stutter. ~5/s is invisible on the
                    // heading cone (the map ticker eases it anyway) and caps the churn.
                    val now = android.os.SystemClock.elapsedRealtime()
                    val moved = if (last.isNaN()) 999f else kotlin.math.abs(((az - last + 540f) % 360f) - 180f)
                    if (moved >= 2f && now - lastPushMs >= 200) {
                        last = az
                        lastPushMs = now
                        _state.update { it.copy(compassHeading = az) }
                    }
                }
            }
            var lastFixRtNanos = 0L
            var lastGpsMs = 0L
            var lastSpeedEvidenceMs = 0L
            // Field, not a local: the tunnel dead-reckon loop reads it to detect a quiet feed.
            nav.lastNavFedMs = android.os.SystemClock.elapsedRealtime()
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
                // fixBearing = a course THIS fix actually measured or derived; null when stopped.
                // The dot keeps the last one, but guidance gets only a fresh one: a reroute pins
                // its departure heading, and a stale heading from before a stop is worse than none.
                val fixBearing = when {
                    loc.hasBearing() && loc.speed > 0.5f -> loc.bearing
                    canDerive && movedM > 3.0 -> bearingBetween(prev!!, here)
                    else -> null
                }
                val bearing = fixBearing ?: _state.value.myBearing
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
                // Feed the day/night theme a position (rounded to ~1 km inside the holder). Sunset
                // varies by hours across a country, so a theme that guesses from the clock alone is
                // wrong for most of the world most of the year.
                app.vela.ui.theme.AppTheme.rememberLocation(appContext, here.lat, here.lng)
                _state.update {
                    it.copy(
                        myLocation = here, myBearing = bearing, mySpeed = speed,
                        myFixRaw = if (here === prev) it.myFixRaw else rawHere, // an outlier hold keeps the old raw too
                        // The fix's OWN accepted measurement, null when it had none (or the gate
                        // rejected it) — the puck Kalman's measurement stream must never see a
                        // held display value or a rejected glitch.
                        mySpeedRaw = measured,
                        // Drives the map's accuracy halo: a coarse-permission or network fix reports
                        // hundreds-to-thousands of meters and gets an honest circle; GPS won't.
                        myAccuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
                        showPsdsTip = false, center = it.center ?: here.takeUnless { userPannedSinceLaunch }, myLocationStale = false,
                    )
                }
                restartStaleTimer()
                // Advance transit step-by-step guidance when we reach the current leg's end (no-op off transit).
                maybeAdvanceTransitNav(here)
                // Save the fix to the active trip (no-op unless one is recording).
                tripStore.record(loc, offRoute = _state.value.nav.offRoute, offRouteHits = _state.value.nav.offRouteHits)
                // Drive turn-by-turn from here so navigation works even if the
                // foreground NavigationService can't start (Android-14 FGS-location
                // restrictions / GrapheneOS). No-op unless a session is active. GUIDANCE IS
                // GPS-ONLY, and a coarse fix (accuracy worse than ~50 m) updates the dot but
                // must not steer it — OsmAnd's ACCURACY_FOR_ROUTING does the same. When
                // guidance is starved of usable fixes for a while (urban canyon at 60-80 m
                // accuracy for minutes), SAY so — the frozen banner used to be indistinguishable
                // from working nav (the stale timer never fires while coarse fixes keep coming).
                if (isGps && (!loc.hasAccuracy() || loc.accuracy <= 50f)) {
                    navSession.onLocation(
                        here, app.vela.ui.Units.imperial.value, speed?.toDouble(),
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                        // Course for the engine's heading-vs-route off-route term and the
                        // reroute's departure heading. Fresh only: stopped = null.
                        bearingDeg = fixBearing?.toDouble(),
                    )
                    nav.lastNavFedMs = nowMs
                    updateSpeedLimit(here) // posted-limit badge for the road under the puck (off-thread)
                    if (_state.value.navStarved) _state.update { it.copy(navStarved = false) }
                } else if (_state.value.navigating && nowMs - nav.lastNavFedMs > NAV_STARVED_MS && !_state.value.navStarved) {
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

    /** Great-circle bearing (deg, 0 = N) from [a] to [b] — used to synthesize a heading
     *  when a GPS fix doesn't carry one. */
    private fun bearingBetween(a: LatLng, b: LatLng): Float {
        val dLng = Math.toRadians(b.lng - a.lng)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val y = Math.sin(dLng) * Math.cos(la2)
        val x = Math.cos(la1) * Math.sin(la2) - Math.sin(la1) * Math.cos(la2) * Math.cos(dLng)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** Gray the location dot if no live fix arrives for a while (Google-style) — the
     *  seeded last-known position starts stale and turns blue on the first real fix. */
    // --- tunnel dead reckoning (route-constrained) --------------------------------------------
    // GPS dies in a tunnel and the whole nav stack used to freeze with it: the view's puck reckons
    // ~3 s blind then decays, and the ENGINE only advances on fixes, so the banner/ETA/voice all
    // stopped (real drive 2026-07-14; Google keeps estimating along the route). When the feed goes
    // quiet mid-drive while solidly ON route, this loop synthesizes fixes ALONG THE ROUTE at the
    // last speed (decaying, tau DR_DECAY_S) and feeds them through the normal navSession path, so
    // guidance keeps counting down and turns still announce. Honesty: navStarved stays true, so
    // the "Searching for GPS" chip shows over the moving arrow. Never in replays/demos, never
    // off-route, never from a standstill, bounded by DR_MAX_M; the first real fix re-anchors
    // everything (synthetic positions are route-plausible, so the outlier gate passes it).

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
     * obf region file). Cheap-gated: the snap is only re-run once you've moved ~a road segment ([here] >
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
            coroutineContext.ensureActive() // canceled mid-snap by clearSpeedLimit (stopNav/replay teardown)?
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
    // else the canceled coroutine would run the offline-fallback/error state update.
    private var searchJob: Job? = null
    // Single-flight directions so a late reply can't overwrite newer state / resurrect a route the user
    // backed out of. Each route() supersedes the previous; a directionsOpen/mode guard is the belt-and-
    // suspenders for the back-out (audit 2026-07-06). Canceled by clearRoute/clearSelection.
    private var routeJob: Job? = null
    private var modeEtaJob: Job? = null
    private var modeEtaKey: String? = null // the trip the chips currently describe
    private val modeEtaCache = HashMap<String, MutableMap<TravelMode, String>>()
    // Whether the directions chooser is collapsed to its Start bar. UI-owned (the panel's drag
    // physics live in DirectionsPanel), mirrored here by MapScreen so the route-through-here
    // long-press can gate on it - only read while directionsOpen, so a stale value between
    // sessions is harmless (the panel re-reports on composition).
    private var directionsMinimized = false

    /** MapScreen mirrors the chooser's collapsed state (DirectionsPanel onCollapsedChange). */
    fun onDirectionsCollapsed(minimized: Boolean) { directionsMinimized = minimized }

    /** The user has grabbed the map at least once this launch. The FIRST fix flies the camera to
     *  the phone only while this is false: a cold GPS start can take 30 s indoors, and a fix that
     *  lands after the user has started looking around used to yank the camera back and zoom in
     *  (issue #362, and the same complaint on the 4a; 2026-09-13). */
    @Volatile private var userPannedSinceLaunch = false
    fun onUserPanned() { userPannedSinceLaunch = true }

    /** As the user types, fetch live place suggestions (debounced) so the search
     *  page shows real matches — name + address — to tap, like Google's
     *  autocomplete. Google's own autocomplete (`dataSource.suggest`) answers
     *  first; only when it fails or comes back empty does the older race run
     *  (the calibrated search endpoint plus Photon and the local address pack).
     *  Best-effort, and a stale response is dropped if the query moved on. */
    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        suggestJob?.cancel()
        val term = q.trim()
        if (term.length < 2) {
            _state.update { it.copy(suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList()) }
            return
        }
        // Match the user's OWN history + lists FIRST, synchronous, no network, so these land
        // instantly (and are the only thing that shows offline). Independent of the debounced
        // Google fetch below, which appends behind them.
        _state.update { it.copy(localSuggestions = localMatches(term)) }
        suggestJob = viewModelScope.launch {
            delay(320) // only fire once typing pauses
            val near = plausibleBias(mapCenter) ?: plausibleBias(_state.value.myLocation) // suggestions near the viewport, like search
            val vp0 = viewport
            val spanM0 = vp0?.let { LatLng(it[0], it[1]).distanceTo(LatLng(it[2], it[1])) }
            // ADDRESS queries ("123 main st") get a parallel Photon (OSM) lookup: it honors the
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
            // The ON-DEVICE address geocoder joins the race too (user 2026-07-15: local house
            // numbers Photon can't fuzzy-match - numbered streets with directional suffixes -
            // resolve fine from the downloaded pack's exact/interpolate/street-fallback layers).
            // It was online-gated to the Google-failed path before, so a wrong-but-nonempty
            // Google result set blocked it entirely.
            val localDeferred: kotlinx.coroutines.Deferred<List<Place>>? = if (app.vela.core.data.OfflineAddressStore.looksLikeAddress(term)) {
                async(Dispatchers.IO) {
                    runCatching { addressStore.geocode(term, near, limit = 3) }.getOrDefault(emptyList())
                }
            } else null
            // Google's OWN autocomplete leads (2026-09-22): it honors the location bias for a
            // partial address, which the search endpoint below never did. A bare five-digit house
            // number answered with a same-looking ZIP code in another state and "459 Ralston"
            // typed far from San Francisco found nothing, while the Google app lists the houses
            // with that number on the nearby streets and the San Francisco street with its city. When it answers,
            // its rows are the suggestions (the local pack's exact hits still lead, deduped by
            // house number) and the Photon + search-endpoint race below is skipped; when it
            // fails or is off (offline, Google off), the old pipeline runs unchanged.
            val auto = runCatching { dataSource.suggest(term, near, spanM0) }.getOrNull()
            if (auto != null && (auto.places.isNotEmpty() || auto.queries.isNotEmpty())) {
                val localAddrs = localDeferred?.await().orEmpty()
                photonDeferred?.cancel()
                if (_state.value.query.trim() == term) {
                    val houseNo = Regex("""^\s*(\d+)""").find(term)?.groupValues?.get(1)
                    fun coveredByGoogle(p: Place) = houseNo != null && auto.places.any { g ->
                        g.location.distanceTo(p.location) < 120.0 &&
                            (g.name.contains(houseNo) || g.address?.contains(houseNo) == true)
                    }
                    val addrLead = localAddrs
                        .filter { p -> near == null || p.location.distanceTo(near) <= SUGGEST_NEAR_M }
                        .filter { p -> !coveredByGoogle(p) }
                    val localPlaces = _state.value.localSuggestions.mapNotNull { it.place }
                    val localNameLoc = localPlaces.map { nameLocKey(it) }.toHashSet()
                    val localFids = localPlaces.mapNotNull { it.featureId }.toHashSet()
                    // Looking far away and nothing here starts with the typed name: the place you
                    // mean is probably near you (user 2026-09-22), so its rows lead.
                    val home = homeSuggestions(term, near, auto.places)
                    val deduped = (addrLead + home + auto.places.filterNot { a -> home.any { h -> h.featureId != null && h.featureId == a.featureId } }).filterNot {
                        nameLocKey(it) in localNameLoc || (it.featureId != null && it.featureId in localFids)
                    }
                    _state.update { it.copy(suggestions = deduped.take(8), querySuggestions = auto.queries.take(3)) }
                }
                return@launch
            }
            val res = runCatching { dataSource.search(term, near, spanM0, rankFrom = rankBias(near)).places }.getOrDefault(emptyList())
            val photon = photonDeferred?.await().orEmpty()
            val localAddrs = localDeferred?.await().orEmpty()
            if (_state.value.query.trim() == term) { // ignore if the query changed meanwhile
                // Google gets the viewport bias, but keyless ranking for a PARTIAL address is
                // weak - "123 main st" happily led with matches states away while the one in
                // town sat below (user report). Bucket by metro distance (stable sort: within
                // each bucket Google's own relevance order is preserved) so nearby matches
                // surface first and a famous far match still shows, just lower.
                // ...but never demote Google's TOP suggestion or an exact name match: for a
                // plain entity query ("fresno") the #1 result IS the entity - the city itself -
                // and bucketing it under every nearby Fresno-named business pushed it off the
                // list entirely (user report 2026-07-13). Addresses are unaffected: Photon still
                // leads those, so a famous far "123 Main Street" at #1 sits below the local hits.
                val ranked = if (near == null) res else res.withIndex().sortedBy { (i, p) ->
                    val entity = i == 0 || p.name.equals(term, ignoreCase = true)
                    if (entity || p.location.distanceTo(near) <= SUGGEST_NEAR_M) 0 else 1
                }.map { it.value }
                // Address hits lead: the local pack's geocode first (exact house-number layers),
                // then Photon's nearby OSM hits, then Google. Drop far strays (a metro away isn't
                // what a partial address means). The old "Google within 120 m covers it" dedupe
                // was the reported vanishing act - on a commercial road SOME business always sits
                // within a block of the house, and the ADDRESS suggestion got eaten by it. Only a
                // Google entry that carries the same HOUSE NUMBER actually covers an address hit.
                val houseNo = Regex("""^\s*(\d+)""").find(term)?.groupValues?.get(1)
                fun coveredByGoogle(p: Place) = houseNo != null && ranked.any { g ->
                    g.location.distanceTo(p.location) < 120.0 &&
                        (g.name.contains(houseNo) || g.address?.contains(houseNo) == true)
                }
                val addrLead = (localAddrs + photon)
                    .filter { p -> near == null || p.location.distanceTo(near) <= SUGGEST_NEAR_M }
                    .filter { p -> !coveredByGoogle(p) }
                    .distinctBy { "${(it.location.lat * 2e4).toInt()},${(it.location.lng * 2e4).toInt()}" }
                // Drop any network suggestion the user's OWN data already surfaced above, so a
                // saved/recent place doesn't appear twice. Match on BOTH keys: recents/saved
                // usually lack a feature id (they're SavedPlace-backed), so a feature-id-only
                // compare misses them - name + coarse location catches those.
                val localPlaces = _state.value.localSuggestions.mapNotNull { it.place }
                val localNameLoc = localPlaces.map { nameLocKey(it) }.toHashSet()
                val localFids = localPlaces.mapNotNull { it.featureId }.toHashSet()
                val deduped = (addrLead + ranked).filterNot {
                    nameLocKey(it) in localNameLoc || (it.featureId != null && it.featureId in localFids)
                }
                _state.update { it.copy(suggestions = deduped.take(8), querySuggestions = emptyList()) }
            }
        }
    }

    /** The search box takes this text without searching: the arrow on a suggestion row (Google's
     *  "put it in the box" arrow), so a long address or a name can be finished by hand. */
    fun fillQuery(text: String) {
        onQueryChange(text)
        _state.update { it.copy(queryEdits = it.queryEdits + 1) }
    }

    private fun placeKey(p: Place): String =
        p.featureId ?: nameLocKey(p)

    /** Feature-id-independent identity: lowercased name + ~5 m rounded location. The dedup key
     *  that works for SavedPlace-backed local suggestions, which carry no feature id. */
    private fun nameLocKey(p: Place): String =
        "${p.name.lowercase()}|${(p.location.lat * 2e4).toInt()},${(p.location.lng * 2e4).toInt()}"

    /** Substring-match the typed [term] against the user's recents (searches + viewed places) and
     *  saved lists (issue #180): no network, so it's instant and works offline. Recent SEARCHES
     *  lead (that's what "search your history" most means), then places, deduped and capped so the
     *  network suggestions still get room below. */
    private fun localMatches(term: String): List<LocalSuggestion> {
        val t = term.lowercase()
        val out = ArrayList<LocalSuggestion>()

        // Recent SEARCHES containing the term (skip an exact echo of what's being typed).
        recentStore.recent().asSequence()
            .filter { it.query.lowercase().contains(t) && !it.query.equals(term, ignoreCase = true) }
            .take(3)
            .forEach { out.add(LocalSuggestion(LocalSuggestion.Kind.RECENT_QUERY, it.query, null, query = it.query, removable = true)) }

        // CONTACTS with a saved postal address (issue #243, opt-in toggle, matched on-device
        // against the in-memory cache). Picking one searches the ADDRESS like any typed query —
        // that string reaches the geocoder; the contact list itself never leaves the phone.
        if (app.vela.ui.ContactsSearch.enabled.value) {
            app.vela.data.ContactAddresses.matches(t).forEach {
                out.add(
                    LocalSuggestion(
                        LocalSuggestion.Kind.CONTACT, it.name, it.address, query = it.address,
                        badge = it.type, photoUri = it.photoUri,
                    ),
                )
            }
        }

        // Places from the user's own data, deduped across sources by stable key.
        val seen = HashSet<String>()
        fun addPlace(kind: LocalSuggestion.Kind, p: Place, removable: Boolean) {
            if (!p.name.lowercase().contains(t) && p.address?.lowercase()?.contains(t) != true) return
            if (!seen.add(placeKey(p))) return
            out.add(LocalSuggestion(kind, p.name, p.address, place = p, removable = removable))
        }
        // Recently-VIEWED places (history) first, then saved-list places, then the saved shortlist.
        _state.value.recentPlaces.forEach { addPlace(LocalSuggestion.Kind.RECENT_PLACE, it.place.toPlace(), removable = true) }
        listStore.lists().flatMap { it.places }.forEach { addPlace(LocalSuggestion.Kind.SAVED_PLACE, it.toPlace(), removable = false) }
        savedStore.saved().forEach { addPlace(LocalSuggestion.Kind.SAVED_PLACE, it.toPlace(), removable = false) }

        return out.take(6)
    }

    /** Open (place-backed) or re-run (recent query) a local suggestion tapped in the search page. */
    fun pickLocalSuggestion(s: LocalSuggestion) {
        when {
            s.kind == LocalSuggestion.Kind.CONTACT && s.query != null -> openContactAddress(s.label, s.query)
            s.place != null -> selectPlace(s.place)
            s.query != null -> searchRecent(s.query)
        }
    }

    /**
     * A contact row was picked: geocode the address, then open it under the PERSON'S name with
     * the address beneath, the way Home and Work open (a labeled place, not a bare address).
     * Until 2026-09-06 the pick just searched the address string, so the sheet, Save and Recents
     * all read "1451 W Covell Blvd" with no trace of whose house it was, and typing the name
     * again a week later found nothing in history. Honors the directions endpoint and stop
     * pickers like any other pick, so a contact works in the "To" field. Only the address string
     * goes to the geocoder (the offline address store first when the phone is offline); the
     * name never leaves the phone. Falls back to the plain search when nothing geocodes, so the
     * usual no-results / offline handling shows.
     */
    fun openContactAddress(name: String, address: String) {
        val near = plausibleBias(_state.value.myLocation) ?: plausibleBias(mapCenter)
        searchJob?.cancel()
        suggestJob?.cancel()
        _state.update { it.copy(searching = true, suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList()) }
        searchJob = viewModelScope.launch {
            // Prefer the ADDRESS feature over a business that happens to sit at it: searching a
            // house number where a shop is returns the shop first, and its rating, hours and
            // price would otherwise dress up the contact's home (device-checked 2026-09-06).
            fun pickAddressHit(hits: List<Place>): Place? =
                hits.take(3).firstOrNull { it.rating == null && it.category == null } ?: hits.firstOrNull()
            val online = runCatching { if (isOnline()) pickAddressHit(dataSource.search(address, near).places) else null }.getOrNull()
            val hit = online ?: withContext(Dispatchers.IO) {
                runCatching { addressStore.geocode(address, near, 1).firstOrNull() }.getOrNull()
            } ?: runCatching { if (!isOnline()) pickAddressHit(dataSource.search(address, near).places) else null }.getOrNull()
            _state.update { it.copy(searching = false) }
            if (hit == null) { searchRecent(address); return@launch }
            // A bare place on purpose: whatever else the geocoder knew about that spot is not
            // the contact's, so the sheet shows the person, the address and the actions.
            selectContactPlace(Place(id = hit.id, name = name, location = hit.location, address = hit.address ?: address))
        }
    }

    /** Open a geocoded contact address as a labeled place. Same branches as [selectSaved]
     *  (assign-as-Home/Work, stop and endpoint pickers, a stop on a live drive), minus the
     *  search-by-name enrichment: searching "John Snow" near a house finds nothing useful. */
    private fun selectContactPlace(base: Place) {
        val sp = SavedPlace.of(base)
        if (consumeAssign(sp)) return
        if (_state.value.pickingStop) { addStop(base); return }
        if (_state.value.pickingDest) { setDirectionsDestination(base); return }
        if (_state.value.pickingOrigin) { setDirectionsOrigin(base); return }
        if (_state.value.navigating) { addStopDuringNav(base); return }
        routeJob?.cancel()
        _state.update {
            it.copy(
                selected = base, center = base.location, placesHere = emptyList(), reviews = emptyList(),
                reviewsLoading = false, reviewsFound = 0, photosLoading = false, loadingDetails = false,
                stopDepartures = null, stopDeparturesLoading = false, stopDeparturesFor = null,
                directionsOpen = false, routes = emptyList(), activeRoute = null,
                transit = emptyList(), transitLoading = false, showSteps = false,
            )
        }
        rememberRecentPlace(sp)
    }

    /** Drop a local suggestion from history via its X (recent search or recently-viewed place);
     *  saved-list rows aren't removable here. Refreshes the live local matches for the current query. */
    fun removeLocalSuggestion(s: LocalSuggestion) {
        when (s.kind) {
            LocalSuggestion.Kind.RECENT_QUERY -> s.query?.let { recentStore.remove(it) }
            LocalSuggestion.Kind.RECENT_PLACE -> s.place?.let { recentPlaceStore.remove(it.id) }
            LocalSuggestion.Kind.SAVED_PLACE -> return
            LocalSuggestion.Kind.CONTACT -> return // remove it by turning the toggle off / editing the contact
        }
        val term = _state.value.query.trim()
        _state.update {
            it.copy(
                recents = recentStore.recent(),
                recentPlaces = recentPlaceStore.recent(),
                localSuggestions = if (term.length >= 2) localMatches(term) else emptyList(),
            )
        }
    }

    /** The X in the search bar: wipe the query, results and selection. Closing an ALONG-ROUTE
     *  browse instead returns to the trip it belongs to (restore the destination + panel) —
     *  the user was hunting for a stop, not abandoning the drive. */
    fun clearSearch() {
        openDirectionsOnResult = false
        suggestJob?.cancel()
        val backToTrip = _state.value.alongRouteDest
        if (backToTrip != null) {
            _state.update {
                it.copy(
                    query = "", results = emptyList(), suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(),
                    selected = backToTrip, alongRouteDest = null, directionsOpen = true,
                    resultsCollapsed = false, showSearchThisArea = false,
                )
            }
            return
        }
        _state.update {
            it.copy(
                query = "", results = emptyList(), suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(), selected = null,
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

    /** Settings > Data and privacy > Clear history (issue #425): recent searches, recent places,
     *  parking history and every recorded trip in one go. Saved places and lists are untouched. */
    fun clearAllHistory() {
        clearRecents()
        clearParkingHistory()
        tripStore.list().forEach { runCatching { tripStore.delete(it.id) } }
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
        val channel = app.vela.update.SelfUpdater.channel(prefs)
        viewModelScope.launch {
            val info = selfUpdater.check(app.vela.BuildConfig.VERSION_CODE, channel) ?: return@launch
            if (info.versionCode <= prefs.getInt("update_dismissed_code", 0)) return@launch
            _state.update { it.copy(updateInfo = info) }
        }
    }

    /** Settings "Check for updates" button — unthrottled, reports back via [onResult]
     *  (true = an update was found and the card is up; false = already current / check failed). */
    fun checkForUpdateNow(onResult: (Boolean) -> Unit) {
        val channel = app.vela.update.SelfUpdater.channel(settingsPrefs)
        viewModelScope.launch {
            val info = selfUpdater.check(app.vela.BuildConfig.VERSION_CODE, channel)
            if (info != null) _state.update { it.copy(updateInfo = info) }
            onResult(info != null)
        }
    }

    /** Download the offered update and hand it to the system installer. */
    /** Launch a long download OUTSIDE viewModelScope so it survives the UI (issue #212): the work
     *  rides the app-lifetime [app.vela.download.DownloadWork] scope and [app.vela.download.DownloadService]
     *  holds a dataSync foreground service (quiet notification) for its duration - backgrounding or
     *  swiping the app away no longer kills an in-flight voice/region/update download. Progress
     *  writes to a cleared ViewModel's state are inert; installed-ness is derived from disk at the
     *  next init, so a download that outlives the UI still lands. */
    private fun downloadLaunch(label: String, block: suspend () -> Unit) {
        app.vela.download.DownloadWork.scope.launch {
            app.vela.download.DownloadService.begin(appContext, label)
            try {
                block()
            } finally {
                app.vela.download.DownloadService.end(appContext, label)
            }
        }
    }

    // Per-kind cancel flags (user 2026-07-23: every download gets a Cancel). The store loops poll
    // these per chunk (`active` param) and abort into their normal failure cleanup; the flag is
    // reset at the START of each download so a stale cancel can't kill the next one. Canceled
    // downloads suppress the "failed" toast - the card disappearing IS the feedback.
    private val voiceCancel = java.util.concurrent.atomic.AtomicBoolean(false)
    private val asrCancel = java.util.concurrent.atomic.AtomicBoolean(false)
    private val regionCancel = java.util.concurrent.atomic.AtomicBoolean(false)
    private val updateCancel = java.util.concurrent.atomic.AtomicBoolean(false)

    fun cancelVoiceDownload() = voiceCancel.set(true)
    fun cancelAsrDownload() = asrCancel.set(true)
    fun cancelRegionDownload() {
        regionQueue.clear()
        _state.update { it.copy(regionQueueLeft = 0, regionQueueTotal = 0) }
        regionCancel.set(true) // covers the graph AND its chained place pack
    }

    /** The pieces of a split country waiting their turn: [downloadRoutingGraphs] fills it, the end
     *  of each region download pops the next. One download at a time keeps the progress card and
     *  the cancel button honest. */
    private val regionQueue = ArrayDeque<app.vela.offline.RoutingRegion>()

    /** Download every region in [regions] that is not installed yet, one after another (a whole
     *  country from its state or province pieces). */
    fun downloadRoutingGraphs(regions: List<app.vela.offline.RoutingRegion>) {
        if (_state.value.routingDownloadingId != null) return
        val todo = regions.filter { it.id !in _state.value.routingInstalledIds }
        if (todo.isEmpty()) return
        regionQueue.clear()
        regionQueue.addAll(todo.drop(1))
        _state.update { it.copy(regionQueueLeft = regionQueue.size, regionQueueTotal = todo.size) }
        downloadRoutingGraph(todo.first())
    }

    private fun startNextQueuedRegion() {
        val next = if (regionCancel.get()) null else regionQueue.removeFirstOrNull()
        if (next == null) {
            regionQueue.clear()
            _state.update { it.copy(regionQueueLeft = 0, regionQueueTotal = 0) }
            return
        }
        _state.update { it.copy(regionQueueLeft = regionQueue.size) }
        downloadRoutingGraph(next)
    }
    fun cancelUpdateDownload() = updateCancel.set(true)

    // The map-area tile download is MapLibre's own machinery, so its cancel is region-based, not
    // flag-based: detach the observer (no stray onDone), stop, delete the partial region.
    @Volatile private var areaRegion: org.maplibre.android.offline.OfflineRegion? = null

    fun cancelAreaDownload() {
        val r = areaRegion ?: return
        areaRegion = null
        runCatching { r.setObserver(null) }
        runCatching { r.setDownloadState(org.maplibre.android.offline.OfflineRegion.STATE_INACTIVE) }
        runCatching {
            r.delete(object : org.maplibre.android.offline.OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {}
                override fun onError(error: String) {}
            })
        }
        app.vela.download.DownloadService.end(appContext, appContext.getString(R.string.download_label_map_data))
        _state.update { it.copy(areaDownloadPct = null) }
    }

    fun downloadUpdate() {
        val info = _state.value.updateInfo ?: return
        if (_state.value.updateDownloadPct != null) return // already downloading
        updateCancel.set(false)
        _state.update { it.copy(updateDownloadPct = 0) }
        downloadLaunch(appContext.getString(R.string.download_label_update)) {
            val apk = selfUpdater.download(info, active = { !updateCancel.get() }) { pct ->
                _state.update { it.copy(updateDownloadPct = pct) }
            }
            _state.update { it.copy(updateDownloadPct = null) }
            if (apk != null) {
                // Installing here takes over the install source, and on a phone set up for the car
                // that is what the head unit keys on - so ask instead of quietly breaking it.
                if (app.vela.update.InstallSource.setForCar(appContext)) {
                    _state.update { it.copy(updateApkPending = apk) }
                } else {
                    selfUpdater.install(apk)
                }
            } else if (!updateCancel.get()) { // canceled = quiet
                showStatus(appContext.getString(app.vela.R.string.update_download_failed))
            }
        }
    }

    /** Install the held-back update anyway; the car will stop listing Vela until it is installed
     *  again the way it was the first time. */
    fun installPendingUpdate() {
        val apk = _state.value.updateApkPending ?: return
        _state.update { it.copy(updateApkPending = null, updateInfo = null) }
        selfUpdater.install(apk)
    }

    /** Hand the held-back APK out as a file, which is what AAEnabler and its siblings take. The
     *  card stays: nothing is installed until the user comes back through that tool. */
    fun sharePendingUpdate() {
        val apk = _state.value.updateApkPending ?: return
        _state.update { it.copy(updateApkPending = null) }
        runCatching { app.vela.update.InstallSource.shareApk(appContext, apk) }
    }

    fun dismissPendingUpdate() {
        _state.update { it.copy(updateApkPending = null) }
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
                assigningShortcut = null, selected = null, suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(),
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

    /** Rename a saved place (issue #434). The open sheet follows if it is showing that place. */
    fun renameSaved(sp: SavedPlace, name: String) {
        if (!savedStore.rename(sp.id, name)) return
        val trimmed = name.trim()
        _state.update {
            it.copy(
                saved = savedStore.saved(),
                selected = it.selected?.let { p -> if (p.id == sp.id) p.copy(name = trimmed) else p },
            )
        }
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
        if (_state.value.pickingDest) { setDirectionsDestination(base); return }
        if (_state.value.pickingOrigin) { setDirectionsOrigin(base); return }
        routeJob?.cancel()
        _state.update {
            it.copy(
                selected = base, center = base.location, placesHere = emptyList(), reviews = emptyList(),
                reviewsLoading = false, reviewsFound = 0, photosLoading = false, loadingDetails = false,
                stopDepartures = null, stopDeparturesLoading = false, stopDeparturesFor = null,
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
    // before the map has settled a center.
    fun search() {
        val q = _state.value.query.trim()
        val near = plausibleBias(mapCenter) ?: plausibleBias(_state.value.myLocation)
        if (handleQueryIntent(q, near)) return
        runSearch(q, near)
    }

    // ---- Query intents (discussion #365, 2026-09-13) ------------------------------------------
    // Typed or spoken, "take me home", "Davis to San Francisco", "nearest pharmacy" and "what is
    // my ETA" are ACTIONS, not search strings. `QueryIntents` (:core, per app language, English as
    // the fallback) reads the shape; anything it does not recognize runs as a plain search, so a
    // business called "Home Depot" still searches. Rule-based and on-device: no server, no model.

    /** One-shot: the next search result set opens the route chooser on its top hit. */
    @Volatile private var openDirectionsOnResult = false

    /** True when [q] was an intent and has been acted on; false = run it as a search. */
    private fun handleQueryIntent(q: String, near: LatLng?): Boolean {
        val lang = app.vela.ui.AppLocale.effective().language
        val intent = app.vela.core.search.QueryIntents.parse(q, lang) ?: return false
        diag.record("search", "intent ${intent::class.simpleName} for \"$q\" ($lang)")
        when (intent) {
            is app.vela.core.search.QueryIntent.Home -> {
                val home = _state.value.home ?: run { showStatus(appContext.getString(R.string.intent_home_unset)); return true }
                _state.update { it.copy(query = q, suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList()) }
                // A BARE place under the shortcut's own name: selectSaved enriches by searching
                // the address, which dresses Home as the business at that address (the same
                // trap the contact pick had, issue #342).
                selectPlace(Place(id = home.id, name = appContext.getString(R.string.shortcut_home), location = home.location, address = home.address)); routeToSelected()
            }
            is app.vela.core.search.QueryIntent.Work -> {
                val work = _state.value.work ?: run { showStatus(appContext.getString(R.string.intent_work_unset)); return true }
                _state.update { it.copy(query = q, suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList()) }
                selectPlace(Place(id = work.id, name = appContext.getString(R.string.shortcut_work), location = work.location, address = work.address)); routeToSelected()
            }
            is app.vela.core.search.QueryIntent.NavigateTo -> {
                openDirectionsOnResult = true
                _state.update { it.copy(query = intent.query) }
                runSearch(intent.query, near)
            }
            is app.vela.core.search.QueryIntent.Search -> {
                _state.update { it.copy(query = intent.query) }
                runSearch(intent.query, near)
            }
            is app.vela.core.search.QueryIntent.Route -> routeBetween(intent.from, intent.to, near)
            is app.vela.core.search.QueryIntent.Eta -> {
                val s = _state.value
                if (s.navigating && s.nav.remainingDuration > 0.0) {
                    val msg = appContext.getString(R.string.intent_eta_reply, formatDuration(s.nav.remainingDuration))
                    showStatus(msg)
                    voice.speak(msg, interrupt = true)
                } else showStatus(appContext.getString(R.string.intent_eta_not_navigating))
            }
        }
        return true
    }

    /** "A to B": the destination becomes the selected place with the chooser open, then the
     *  origin is geocoded and set as a custom From (which re-routes). Best-effort; a miss on
     *  either end says so instead of silently routing from the wrong place. */
    private fun routeBetween(from: String, to: String, near: LatLng?) {
        _state.update { it.copy(query = "$from → $to", searching = true, suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList()) }
        viewModelScope.launch {
            val bias = rankBias(near)
            // GUARD: a name that merely contains "to" ("Road to Hana", "Flights to Denver") is a
            // place, not a trip. If the whole phrase matches a real listing by name, show the
            // plain results instead of routing between its halves.
            val whole = runCatching { dataSource.search("$from to $to", near, rankFrom = bias).places }.getOrDefault(emptyList())
            if (whole.any { it.name.contains("$from to $to", ignoreCase = true) }) {
                _state.update { it.copy(query = "$from to $to", results = whole, searching = false, resultsCollapsed = false, selected = null) }
                return@launch
            }
            // A route ENDPOINT is usually a town or an address, not the nearest business whose
            // name contains the word (on-device test: "Davis" picked "Davis Built Homes" next to
            // the phone). Prefer an exact name match, then a result with no rating (a locality or
            // an address), then whatever ranked first.
            // ...and among equally exact matches, the NEAREST to the other end of the trip. A
            // province and its capital share a name ("Montréal à Québec" drove to the label point
            // of the province, hundreds of km past the city, discussion #365), and the label point
            // of a region is not a place anyone drives to.
            fun endpoint(q: String, places: List<Place>, anchor: LatLng?): Place? {
                val exact = places.filter { it.name.equals(q, ignoreCase = true) }
                if (exact.isNotEmpty()) {
                    return if (anchor == null) exact.first() else exact.minByOrNull { anchor.distanceTo(it.location) }
                }
                return places.firstOrNull { it.rating == null && it.category == null } ?: places.firstOrNull()
            }
            // The ORIGIN is resolved first so it can anchor the destination: with the start known,
            // Google ranks the destination around it too, which is what a person means by "A to B".
            val origin = runCatching { endpoint(from, dataSource.search(from, near, rankFrom = bias).places, bias ?: near) }.getOrNull()
            val destNear = origin?.location ?: near
            val dest = runCatching { endpoint(to, dataSource.search(to, destNear, rankFrom = origin?.location ?: bias).places, origin?.location) }.getOrNull()
            if (dest == null) { _state.update { it.copy(searching = false) }; showStatus(appContext.getString(R.string.intent_place_not_found, to)); return@launch }
            _state.update { it.copy(searching = false) }
            selectPlace(dest)
            routeToSelected()
            if (origin != null) setDirectionsOrigin(origin)
            else showStatus(appContext.getString(R.string.intent_place_not_found, from))
        }
    }

    /** Rank results from the USER when they are searching where they are (within ~50 km of the
     *  viewport), else from the viewport center. Fixes the "results ordered around some weird
     *  point" feel: the viewport stays the SEARCH AREA, but the order and the shown distances
     *  no longer reshuffle around wherever the screen happens to be centered. */
    private fun rankBias(near: LatLng?): LatLng? {
        val me = plausibleBias(_state.value.myLocation) ?: return null
        return me.takeIf { near == null || it.distanceTo(near) < 50_000.0 }
    }

    /** Autocomplete rows near the user whose name starts with [term], when [near] is far from the
     *  user and none of [far] does. One extra suggest request, only in that case. */
    private suspend fun homeSuggestions(term: String, near: LatLng?, far: List<Place>): List<Place> {
        val me = plausibleBias(_state.value.myLocation) ?: return emptyList()
        if (near == null || me.distanceTo(near) < 50_000.0) return emptyList()
        val t = app.vela.core.util.PlaceNames.normalized(term)
        if (t.length < 4 || app.vela.core.data.OfflineAddressStore.looksLikeAddress(term)) return emptyList()
        fun starts(p: Place) = app.vela.core.util.PlaceNames.normalized(p.name).startsWith(t)
        if (far.any(::starts)) return emptyList()
        val home = runCatching { dataSource.suggest(term, me, 20_000.0) }.getOrNull()?.places.orEmpty()
        return home.filter(::starts).take(3)
    }

    /** Places near the user whose name matches [q] (exact or generic-word variant), when the search
     *  window [near] is far from the user and none of [far] already matches. Empty otherwise, so
     *  it costs one extra request only in that case. */
    private suspend fun homeNameHits(q: String, near: LatLng?, far: List<Place>): List<Place> {
        val me = plausibleBias(_state.value.myLocation) ?: return emptyList()
        if (near == null || me.distanceTo(near) < 50_000.0) return emptyList()
        val query = q.trim()
        if (query.length < 4 || app.vela.core.data.OfflineAddressStore.looksLikeAddress(query)) return emptyList()
        if (app.vela.ui.QuickCategories.all().any { it.query.equals(query, ignoreCase = true) }) return emptyList()
        fun named(p: Place) = app.vela.core.util.PlaceNames.match(p.name, query).let {
            it == app.vela.core.util.PlaceNames.Match.EXACT || it == app.vela.core.util.PlaceNames.Match.VARIANT
        }
        if (far.any(::named)) return emptyList()
        val home = runCatching { withContext(Dispatchers.IO) { dataSource.searchOnce(query, me) } }.getOrDefault(emptyList())
        return home.filter(::named).sortedBy { it.location.distanceTo(me) }.take(5)
    }

    /** Re-run the current query biased to the area the user has panned to. */
    fun searchThisArea() = runSearch(_state.value.query.trim(), plausibleBias(mapCenter))

    // "More results" (2026-09-13): the search fetches three pages; this pulls the next three of
    // the SAME request (query, window, ranking point) and appends what is new. The row disappears
    // when a pull adds fewer than a handful, or when the query changes.
    private var moreSearch: Triple<String, LatLng?, Double?>? = null
    private var moreFromPage = 3
    private var moreJob: Job? = null
    fun loadMoreResults() {
        val (q, near, spanM) = moreSearch ?: return
        val s = _state.value
        if (s.resultsLoadingMore || s.resultsMoreQuery != q || s.query != q) return
        _state.update { it.copy(resultsLoadingMore = true) }
        moreJob?.cancel()
        moreJob = viewModelScope.launch {
            val got = runCatching { dataSource.searchMore(q, near, spanM, rankBias(near), moreFromPage) }.getOrDefault(emptyList())
            val have = _state.value.results
            val key = { p: Place -> p.featureId ?: "${p.name.lowercase()}|${(p.location.lat * 2000).toInt()}|${(p.location.lng * 2000).toInt()}" }
            val seen = have.map(key).toHashSet()
            val fresh = got.filter { seen.add(key(it)) }
            moreFromPage += 3
            _state.update {
                if (it.query != q) it.copy(resultsLoadingMore = false)
                else it.copy(results = it.results + fresh, resultsLoadingMore = false, resultsMoreQuery = if (fresh.size >= 5) q else null)
            }
        }
    }

    // A point within ~50 km of 0,0 is MapLibre's virgin camera (a no-GPS device that never got a
    // fix or a fly-to) or a bogus provider fix, open ocean, never a real position. Passing it as
    // search bias skews ranking toward null island; no bias at all lets gl/hl regional ranking win.
    private fun plausibleBias(l: LatLng?): LatLng? =
        l?.takeUnless { kotlin.math.abs(it.lat) < 0.5 && kotlin.math.abs(it.lng) < 0.5 }

    /** Map settled after a user pan: offer "Search this area" while results show. */
    fun onCameraIdle(center: LatLng) {
        mapCenter = center
        maybeOfferRouting()
        if (_state.value.results.isNotEmpty() && _state.value.selected == null) {
            _state.update { it.copy(showSearchThisArea = true) }
        }
        warmWebViewsWhenQuiet()
    }

    private var webWarmScheduled = false

    /** Boot the hidden WebViews once, at a quiet moment a few seconds after the map first settles,
     *  and only when the main thread is idle. Chromium's first start is a good half second of
     *  main-thread work plus a sandbox process, and it used to land at the first place tap of a
     *  fresh app, right under the sheet's open animation (the 4a dropped frames "like crazy",
     *  2026-09-14). Searching warms them anyway; this covers the map-tap-first session. */
    private fun warmWebViewsWhenQuiet() {
        if (webWarmScheduled || app.vela.ui.MemoryPressure.lowRam || app.vela.ui.GoogleFree.on.value) return
        webWarmScheduled = true
        viewModelScope.launch {
            // Keep looking for a quiet moment on our own: the first idle often comes with a sheet
            // already up (a geo: link opens straight onto a place), and the map may never move
            // again to hand us another idle. A plain delayed run, not an idle handler: the main
            // looper is rarely idle for long with a live map and a fix a second.
            repeat(12) {
                kotlinx.coroutines.delay(4_000)
                val st = _state.value
                if (!st.navigating && st.selected == null && st.results.isEmpty()) {
                    // ENGINE ONLY (2026-09-22): this used to load google.com and Google Maps in two
                    // hidden views at every launch, ~300 MB of renderer for pages nobody asked for
                    // (and a Google contact carrying the app's package name). The expensive part
                    // for the first tap is Chromium's own start, which a throwaway view pays here;
                    // the Google pages load only when a place actually needs one (2026-09-23).
                    android.util.Log.i("VelaWarm", "webviews: booting the engine at a quiet moment")
                    runCatching { android.webkit.WebView(appContext).destroy() }
                    return@launch
                }
            }
            android.util.Log.i("VelaWarm", "webviews: no quiet moment found, leaving it to the first search")
            webWarmScheduled = false
        }
    }

    /** Track connectivity so the UI can show a quiet offline indicator (no more banner). Seeds now and
     *  updates on every network change; fails safe to "online" so a quirk never falsely grays the app. */
    private var offlineLatchJob: Job? = null

    private fun observeConnectivity() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        fun refresh() {
            // Constrained-link check rides the same callback (it already fires on capability
            // changes, which is exactly when a handset falls back to satellite).
            val constrained = app.vela.ui.ConstrainedNetwork.isConstrained(cm)
            if (constrained != _state.value.lowData) {
                app.vela.core.data.LowDataMode.enabled = constrained
                _state.update { it.copy(lowData = constrained) }
            }
            val off = !isOnline()
            if (!off) {
                // Online applies IMMEDIATELY (and cancels a pending offline latch).
                offlineLatchJob?.cancel()
                val was = _state.value.offline
                _state.update { if (it.offline) it.copy(offline = false) else it }
                // Coming back online re-decides a shallow installed basemap (see refreshBasemapArchive).
                if (was) refreshBasemapArchive()
            } else if (offlineLatchJob?.isActive != true) {
                // DEBOUNCE the offline latch (user 2026-07-18, "thinks it's offline too often"):
                // a WiFi-to-cellular handoff or a doze wake routinely passes through a moment
                // with no active network, and latching instantly flashed the indicator and gated
                // fetches on a device that is actually online. Only call it offline if it is
                // STILL offline ~3 s later.
                offlineLatchJob = viewModelScope.launch {
                    delay(3_000)
                    val stillOff = !isOnline()
                    val changed = _state.value.offline != stillOff
                    _state.update { if (it.offline != stillOff) it.copy(offline = stillOff) else it }
                    if (changed) refreshBasemapArchive()
                }
            }
        }
        refresh()
        // Diagnostics breadcrumbs for the link itself (issue #397, 2026-09-15: an export showed
        // both routers empty for four minutes and nothing said whether the phone had a network).
        // No pinging: the system's own default-network callback already says which transport is
        // up and whether it VALIDATED (reached the internet). Recorded only when the summary
        // changes, so a moving car logs handoffs and dropouts, not signal-strength ticks.
        var lastNet = ""
        fun note(event: String, caps: android.net.NetworkCapabilities?) {
            val summary = if (caps == null) "none" else buildString {
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) append("wifi ")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) append("cellular ")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) append("ethernet ")
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) append("vpn ")
                append(if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "not validated")
                if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) append(" metered")
            }
            val line = "$event: $summary"
            if (line != lastNet) { lastNet = line; diag.record("net", line) }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { note("available", runCatching { cm.getNetworkCapabilities(network) }.getOrNull()); refresh() }
                override fun onLost(network: android.net.Network) { note("lost", null); refresh() }
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { note("link", caps); refresh() }
            })
        }
    }

    /** Is there a usable internet connection right now? Used to skip the Google scrape when offline (it
     *  would only hang to the socket timeout). Fails OPEN - if the check itself errors, assume online so a
     *  quirk can never block search. */
    /** No usable connection right now: the latched offline flag, or the system saying so. Every
     *  Google-side fetch for a place (listing, reviews, photos, details, boards) checks this first,
     *  so an offline tap shows what is on the phone and never a spinner waiting on a host that
     *  cannot answer (user 2026-09-14). */
    private fun offlineNow(): Boolean = _state.value.offline || !isOnline()

    /** Offline, or the user turned Google off (Settings > Privacy): the Google-only fetches take
     *  the same "nothing to ask" path either way. NOT [offlineNow] itself: that one also decides
     *  routing and basemap fallbacks, which must keep using the open services while online. */
    private fun googleOff(): Boolean = offlineNow() || app.vela.ui.GoogleFree.on.value

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

    // (warmPlaceWebViews is gone, 2026-09-23: after every search it loaded google.com and Google
    // Maps in two hidden views on the chance a place got tapped, two whole web apps per search.
    // A tap's photos and reviews are single RPCs now; the pages load only when actually needed.)

    private fun runSearch(q: String, near: LatLng?) {
        if (q.isEmpty()) return
        // Pasted coordinates ("37.77, -122.42" or a geo: string) drop a reverse-geocoded pin
        // there instead of going to the search endpoint as text - same handling a bare external
        // geo: link gets. Strict whole-string match, so addresses with numbers still search.
        MapLinkParser.parseBareCoordinate(q)?.let { link ->
            val at = LatLng(link.lat!!, link.lng!!)
            recentStore.add(q)
            _state.update { it.copy(recents = recentStore.recent(), suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(), searching = false, center = at) }
            onMapLongPress(at)
            return
        }
        // Re-poll connectivity per search: the registered callback alone proved able to
        // wedge `offline` on (missed onAvailable after doze) until an app relaunch. HEAL ONLY:
        // clear a stale offline when the poll says online, but never LATCH offline here - a
        // search fired mid network-handoff read as offline for a beat and falsely grayed the
        // app (the observer's debounced latch owns the offline verdict; the search's own
        // failure path shows the offline guidance if the scrape really can't connect).
        if (isOnline()) {
            offlineLatchJob?.cancel()
            _state.update { if (it.offline) it.copy(offline = false) else it }
        }
        suggestJob?.cancel()
        recentStore.add(q)
        _state.update { it.copy(recents = recentStore.recent()) }
        // A search strongly predicts opening a place — warm the detail WebViews now so
        // popular times AND the photo gallery land faster when the user taps a result
        // (both idempotent; the photo warm primes the renderer + HTTP/2 sockets + cache
        // so the first place page skips the cold start).
        // Skipped on low-RAM devices: each warm spins up a Chromium renderer SPECULATIVELY, on
        // the guess that a search predicts a place tap. When memory is the scarce resource that
        // trade is backwards - two renderers paid on every search whether or not a place opens
        // (ported from vela-dpad, 2026-07-23). Those phones build the WebView on first real use.
        // Since 2026-09-23 there is no page warm-up at all: a tap's photos and reviews are single
        // RPCs, and the hidden pages load only for "More photos", the All reviews page or a
        // details fetch the search reply could not answer.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // A fresh typed search leaves any along-route browse: picks open places normally again.
            _state.update { it.copy(searching = true, suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(), showSearchThisArea = false, resultsCollapsed = false, alongRouteDest = null) }
            // A pasted Google Maps SHARE LINK: try the shared-list import (issue #1). The link
            // resolves keylessly to the list's places, each carrying the owner's note; they land
            // as results (title in the bar) and each is savable/openable like any search hit.
            if (MapLinkParser.isShareLink(q)) {
                // A short link says nothing until Google's shortener is asked where it points. A
                // single place is then opened from the link itself (name + pin, read on the phone,
                // searched like any deep link); only a shared LIST needs the Google import below.
                // With "Use Vela without Google" on, the shortener is asked only while "Open shared
                // Google Maps links" is on (the default; off = a toast, user 2026-09-22).
                val googleOff = app.vela.ui.GoogleFree.on.value
                fun toast(res: Int) = android.widget.Toast.makeText(appContext, res, android.widget.Toast.LENGTH_LONG).show()
                if (googleOff && app.vela.core.data.ShortLinks.isShortener(q) && !app.vela.ui.GoogleFree.resolveLinks.value) {
                    _state.update { it.copy(searching = false) }
                    toast(R.string.map_link_needs_google)
                    return@launch
                }
                val target = if (app.vela.core.data.ShortLinks.isShortener(q)) withContext(Dispatchers.IO) {
                    runCatching {
                        app.vela.core.data.ShortLinks.resolve(http, q, app.vela.core.config.CalibrationStore.latest.userAgent)
                    }.getOrNull()
                } else q
                android.util.Log.i("VelaLink", "short link -> ${target?.substringBefore('?')?.substringBefore("/@")?.take(60) ?: "no redirect"}")
                val single = target?.takeUnless { app.vela.core.data.ShortLinks.isList(it) }
                    ?.let { MapLinkParser.parse(it) }?.takeIf { it.hasTarget }
                if (single != null) {
                    _state.update { it.copy(searching = false) }
                    openDeepLink(single)
                    return@launch
                }
                if (googleOff) {
                    _state.update { it.copy(searching = false) }
                    toast(if (target != null && app.vela.core.data.ShortLinks.isList(target)) R.string.map_import_needs_google else R.string.map_link_unreadable)
                    return@launch
                }
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
                    val rawPois = runCatching { offlinePoiStore.search(q, near) }.getOrDefault(emptyList())
                    // A pack POI usually carries no address of its own (OSM tags few), so the rows
                    // read as bare names; fill the shown ones from the address index, the same
                    // lookup the sheet runs on select (user 2026-09-19, "does not show the POI
                    // address"). Bounded to what the list shows first.
                    val pois = rawPois.mapIndexed { i, p ->
                        if (i >= OFFLINE_ADDR_FILL || !p.address.isNullOrBlank()) p // fill only the first rows, the ones on screen
                        else p.copy(address = runCatching { addressStore.reverseGeocode(p.location) }.getOrNull() ?: p.address)
                    }
                    // If it looks like a street address, geocode it too and lead with the address matches,
                    // and with the BUSINESSES standing at that address ahead of the bare house point:
                    // a typed address is usually a way of naming the shop on it.
                    val addrs = if (app.vela.core.data.OfflineAddressStore.looksLikeAddress(q))
                        runCatching { addressStore.geocode(q, near) }.getOrDefault(emptyList()) else emptyList()
                    val atAddr = addrs.take(3).flatMap { a ->
                        runCatching { offlinePoiStore.near(a.location, OFFLINE_AT_ADDR_M) }.getOrDefault(emptyList())
                            .map { p -> if (p.address.isNullOrBlank()) p.copy(address = a.address ?: a.name) else p }
                    }
                    val merged = (if (addrs.isNotEmpty()) atAddr + addrs + pois else pois + addrs).distinctBy { it.id }
                    val have = merged.isNotEmpty() ||
                        runCatching { offlinePoiStore.count() > 0 || addressStore.count() > 0 || addressStore.streetCount() > 0 }.getOrDefault(false)
                    merged to have
                }
                _state.update {
                    when {
                        // No "Offline results" banner — the quiet offline indicator (globe-slash + the
                        // grayed "Offline" in the search bar) already says we're offline.
                        offline.isNotEmpty() ->
                            it.copy(results = offline, selected = if (it.pickingOrigin || it.pickingDest || it.pickingStop) it.selected else null, status = null, searching = false)
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
                val res = dataSource.search(q, near, spanM, rankFrom = rankBias(near))
                // A typed house address whose results carry no such house number: the search
                // endpoint ranks by prominence over the window and answered "459 Ralston" typed
                // from another state with businesses named Ralston. Google's autocomplete
                // geocodes it (2026-09-22); its rows with that house number lead the results.
                val houseNo = Regex("""^\s*(\d+)\s+\S""").find(q)?.groupValues?.get(1)
                fun carries(p: Place) = houseNo != null && (p.name.contains(houseNo) || p.address?.contains(houseNo) == true)
                val geocoded = if (houseNo != null && res.places.none(::carries)) {
                    runCatching { dataSource.suggest(q, near, spanM).places.filter(::carries).take(3) }.getOrDefault(emptyList())
                } else emptyList()
                // A NAME TYPED WHILE LOOKING FAR AWAY (user 2026-09-22: a local restaurant's name
                // typed with the map over another country opened a fuzzy match over there). When
                // the view is more than RANK_NEAR_M from you and nothing in it carries the typed
                // name, ask once around YOU; a close name match there replaces the far results.
                // Category words never trigger it ("coffee" over Tokyo means Tokyo's coffee).
                val homeHits = homeNameHits(q, near, res.places)
                if (homeHits.isNotEmpty()) {
                    android.util.Log.i("VelaSearch", "far view: ${homeHits.size} name match(es) near you replace ${res.places.size} far result(s)")
                    _state.update {
                        it.copy(
                            results = homeHits, selected = if (it.pickingOrigin || it.pickingDest || it.pickingStop) it.selected else null,
                            status = null, searching = false, offline = false, resultsMoreQuery = null, resultsLoadingMore = false,
                        )
                    }
                    moreSearch = null
                    if (openDirectionsOnResult) {
                        openDirectionsOnResult = false
                        homeHits.first().let { top -> selectPlace(top); routeToSelected() }
                    }
                    return@launch
                }
                if (res.places.isNotEmpty() || geocoded.isNotEmpty()) {
                    // ADDRESS queries: the on-device geocoder's exact house-number hits lead even
                    // when Google returned results (user 2026-07-15) - Google's keyless ranking
                    // for a local house number is weak, and a wrong-but-nonempty result set used
                    // to block the local geocoder entirely. Same house (within a block, same
                    // number) dedupes in Google's favour - its entry is richer.
                    val localAddrs = if (app.vela.core.data.OfflineAddressStore.looksLikeAddress(q)) {
                        withContext(Dispatchers.IO) {
                            runCatching { addressStore.geocode(q, near, limit = 3) }.getOrDefault(emptyList())
                        }.filter { a ->
                            (geocoded + res.places).none { g ->
                                g.location.distanceTo(a.location) < 120.0 &&
                                    a.name.takeWhile { it.isDigit() }.let { n -> n.isNotEmpty() && (g.name.contains(n) || g.address?.contains(n) == true) }
                            }
                        }
                    } else emptyList()
                    // NEARBY MERGE (user 2026-07-18): even with pagination, Google's keyless
                    // ranking is prominence-heavy over the whole viewport, so a modest place
                    // right next to the user can miss every page of a category search. The
                    // ambient pool (the category fan-out at a tight span) usually already holds
                    // it - append matching ambient places the search missed, nearest first.
                    // APPENDED, never reshuffled: the distance re-rank experiment on the dot
                    // layer taught us not to touch Google's ordering. Zero extra network.
                    val ambientExtra = if (near != null && !app.vela.core.data.OfflineAddressStore.looksLikeAddress(q)) {
                        val qn = q.trim().lowercase().trimEnd('s')
                        if (qn.length < 3) emptyList()
                        else _state.value.ambientPois
                            .filter { p ->
                                val cat = p.category?.lowercase() ?: ""
                                (cat.isNotEmpty() && (cat.contains(qn) || (cat.length >= 4 && qn.contains(cat)))) ||
                                    p.name.lowercase().contains(qn)
                            }
                            .filterNot { a -> res.places.any { g -> g.name.equals(a.name, ignoreCase = true) && g.location.distanceTo(a.location) < 150.0 } }
                            .sortedBy { it.location.distanceTo(near) }
                            .take(20)
                    } else emptyList()
                    _state.update {
                        // Keep the directions DESTINATION (held in `selected`) while picking an origin/stop —
                        // else typing the origin query wiped the "To" and the panel showed an empty
                        // "Destination" with stale routes (the from-here edit cleared where you were going).
                        // A live scrape succeeding is definitive proof we're online — clear a stuck
                        // offline flag (the network callback can miss an event after doze and leave
                        // `offline` latched until relaunch; seen on-device 2026-07-09).
                        it.copy(
                            results = localAddrs + geocoded + res.places + ambientExtra, selected = if (it.pickingOrigin || it.pickingDest || it.pickingStop) it.selected else null, status = null, searching = false, offline = false,
                            // Three full pages back = the window holds more; offer the next three.
                            resultsMoreQuery = if (res.places.size >= 40) q else null, resultsLoadingMore = false,
                        )
                    }
                    moreSearch = Triple(q, near, spanM); moreFromPage = 3
                    // "Navigate to X": the top hit is the destination, straight into the chooser.
                    if (openDirectionsOnResult) {
                        openDirectionsOnResult = false
                        (geocoded.firstOrNull() ?: res.places.firstOrNull())?.let { top -> selectPlace(top); routeToSelected() }
                    }
                } else {
                    openDirectionsOnResult = false
                    // Online SUCCEEDED but found nothing. Don't leave a blank screen (the "POI list just
                    // isn't showing up" report): try the on-device OSM index (it may hold a small local
                    // place Google misses), and if that's empty too, say "No results" plainly.
                    val offline = offlineSearch(q, near)
                    _state.update {
                        if (offline.isNotEmpty())
                            it.copy(results = offline, selected = if (it.pickingOrigin || it.pickingDest || it.pickingStop) it.selected else null, status = null, searching = false, offline = false)
                        else
                            it.copy(results = emptyList(), selected = if (it.pickingOrigin || it.pickingDest || it.pickingStop) it.selected else null, status = appContext.getString(R.string.mapvm_no_results, q), searching = false, offline = false)
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
                    _state.update { it.copy(results = offline, selected = if (it.pickingOrigin || it.pickingDest || it.pickingStop) it.selected else null, status = null, searching = false) }
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
                    query = query, searching = true, directionsOpen = false, suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(),
                    resultsCollapsed = false, recents = recentStore.recent(),
                    // Stash the trip's destination: browsing stop candidates must not lose the trip.
                    // While this is set, picking a result ADDS IT AS A STOP and returns to the panel.
                    alongRouteDest = if (it.navigating) null else (it.selected ?: it.alongRouteDest),
                )
            }
            try {
                val res = dataSource.search(query, route[route.size / 2])
                val along = RouteCorridor.alongRoute(res.places, route)
                // "Searched and nothing happened" has three different causes (the search came back
                // empty, the corridor filter dropped everything, or the results arrived and the
                // sheet did not show) and none of them used to leave a trace.
                android.util.Log.d(
                    "VelaNavSearch",
                    "'$query' along ${route.size} points: ${res.places.size} results, ${along.size} in the corridor",
                )
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
                // deep link's point can be anywhere: fly there too (honoring its z= when given),
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
        // NAVIGATING: a pick from the in-nav search-along-route list becomes a stop on the
        // LIVE drive - the normal selection path below would null activeRoute/open sheets
        // that nav's bottom slot doesn't render. Google's in-nav pick does the same. ONLY
        // a results pick though: every map tap during a drive funnels here too (ambient
        // dots, resolved POIs), and a stray tap used to silently pin itself onto the route
        // (user 2026-07-14). With no results list open, a tap during nav does nothing.
        if (_state.value.navigating) {
            when {
                _state.value.results.isNotEmpty() -> addStopDuringNav(p)
                // Tap-to-stop (off by default): the first tap only OFFERS the place; the card's
                // button is the second tap that changes the drive.
                app.vela.ui.MapPoiPrefs.navTapPlaces.value -> offerNavTapStop(p)
            }
            return
        }
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
        if (_state.value.pickingDest) { setDirectionsDestination(p); return }
        if (_state.value.pickingOrigin) { setDirectionsOrigin(p); return }
        suggestJob?.cancel()
        routeJob?.cancel() // a directions fetch in flight must not resurrect the stale panel
        _state.update {
            it.copy(
                selected = withListNote(p), center = p.location, centerZoom = null, reviews = emptyList(), suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(),
                placesHere = othersAt(p, it.results), loadingDetails = false, photosLoading = false,
                // Picking a NEW place while a route chooser is open closes it: the chooser
                // belonged to the previous destination and kept covering the fresh place
                // (the along-route / pick-origin / pick-stop flows early-return above).
                directionsOpen = false, routes = emptyList(), activeRoute = null,
                transit = emptyList(), transitLoading = false, showSteps = false,
                stopDepartures = null, stopDeparturesLoading = false, stopDeparturesFor = null,
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
            """arrêt|parada|paragem|hållplats|przystanek|dworzec|зупинка|станція|megálló|állomás|pályaudvar""",
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
                        if (st.selected?.id == selId) st.copy(stopDepartures = fresh, stopDeparturesFor = selId) else st
                    }
                }
            }
        }
    }

    private fun fetchStopDepartures(p: Place) {
        val cat = p.category ?: ""
        val isTransit = isTransitCategory(cat)
        val isIntersection = cat.contains("intersection", ignoreCase = true)
        if (!isTransit && !isIntersection) return
        if (offlineNow()) { showCachedBoard(p); return }
        // A place with no Google listing (an open-data stop, any tap with Google off) still gets its
        // board: Transitous needs only the coordinate. Only the Google-page fallbacks need the id
        // (user 2026-09-22: the id check used to come first, so those places never got a board).
        val fid = p.featureId?.takeIf { it.contains(":") }
        fun owns(st: MapUiState) = if (fid != null) st.selected?.featureId == fid else st.selected?.id == p.id
        // PRIMARY: Transitous (open GTFS + realtime, keyless). One proximity lookup at the place's own
        // coordinate - no name correlation against Google/OSM at all - and unlike Google's anonymous
        // page it returns EVERY route at the stop (a hub's parent station merges all its bays). The
        // Google blob paths below stay as the fallback where Transitous has no coverage.
        _state.update { if (owns(it)) it.copy(stopDeparturesLoading = true, stopDeparturesFor = p.id) else it }
        viewModelScope.launch {
            val board = withContext(Dispatchers.IO) {
                runCatching { app.vela.core.data.transit.Transitous.board(http, p.location.lat, p.location.lng) }.getOrNull()
            }
            android.util.Log.i("VelaDepartures", "transitous lines=${board?.lines?.size ?: -1}")
            if (board != null && board.lines.isNotEmpty()) {
                withContext(Dispatchers.IO) { transitBoardCache.put(p.location.lat, p.location.lng, board) }
                _state.update { st ->
                    if (!owns(st)) st
                    else st.copy(stopDepartures = board, stopDeparturesLoading = false, stopDeparturesFor = p.id, stopDeparturesCachedAt = null)
                }
                startBoardRefresh(p.id, p.location.lat, p.location.lng)
                return@launch
            }
            // The Google fallbacks need a Google listing and Google itself.
            if (fid == null || googleOff()) {
                _state.update { st -> if (owns(st)) st.copy(stopDeparturesLoading = false) else st }
                return@launch
            }
            // FALLBACK: the Google place-page blob (agency-dependent, one route at hubs - but better
            // than nothing where the open feeds lack the agency).
            when {
                isTransit -> fetchBoardFrom(fid, selectedFid = fid, ownerId = p.id)
                // A stop named by its corner resolves to Google's "Intersection" entity, whose own page
                // has no board - re-resolve to the co-located stop listing (device 2026-07-13).
                else -> resolveIntersectionStopBoard(p)
            }
        }
    }

    /** No connection: show the board this stop had the last time it was fetched, marked with when,
     *  so the routes, headsigns and colors are there and nobody reads an old time as a live one. */
    private fun showCachedBoard(p: Place) {
        viewModelScope.launch {
            val hit = withContext(Dispatchers.IO) { runCatching { transitBoardCache.get(p.location.lat, p.location.lng) }.getOrNull() }
            _state.update { st ->
                if (st.selected?.id != p.id) st
                else if (hit == null) st.copy(stopDeparturesLoading = false)
                else st.copy(stopDepartures = hit.board, stopDeparturesLoading = false, stopDeparturesFor = p.id, stopDeparturesCachedAt = hit.at)
            }
        }
    }

    /** Fetch a transit stop's board from its own [boardFid] and attach it to the still-selected place
     *  ([selectedFid]). Feature-id-gated so a slow fetch can't land on a place the user has moved off. */
    private fun fetchBoardFrom(boardFid: String, selectedFid: String?, ownerId: String) {
        _state.update { if (it.selected?.featureId == selectedFid) it.copy(stopDeparturesLoading = true, stopDeparturesFor = ownerId) else it }
        viewModelScope.launch {
            val board = runCatching { webStopDepartures.fetch(boardFid) }.getOrNull()
            android.util.Log.i("VelaDepartures", "board lines=${board?.lines?.size ?: -1}")
            _state.update { st ->
                if (st.selected?.featureId != selectedFid) st
                else st.copy(stopDepartures = board?.takeIf { it.lines.isNotEmpty() }, stopDeparturesLoading = false, stopDeparturesFor = ownerId, stopDeparturesCachedAt = null)
            }
        }
    }

    /** [p] resolved to an "Intersection", but a bus stop usually sits at the same corner as its own Google
     *  listing. Search "<name> bus stop" near the corner (the transit-hint trick onPoiTap uses), take the
     *  nearest LIVE transit-category listing within 80 m, and pull its board onto [p]'s sheet. No stop -> no
     *  board (a plain intersection just shows nothing, as before). */
    private fun resolveIntersectionStopBoard(p: Place) {
        val selectedFid = p.featureId
        _state.update { if (it.selected?.featureId == selectedFid) it.copy(stopDeparturesLoading = true, stopDeparturesFor = p.id) else it }
        viewModelScope.launch {
            val stopFid = runCatching {
                // A junction's own point sits back from the stops on each approach, so use a generous radius
                // (~250 m): a REAL co-located stop measured 89 m from its junction point (device 2026-07-13,
                // just past the old 80 m cut - exactly why boards never showed), while another junction's
                // stops sit ~575 m out. 250 m catches the right one without grabbing a neighbor's.
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
                else st.copy(stopDepartures = board?.takeIf { it.lines.isNotEmpty() }, stopDeparturesLoading = false, stopDeparturesFor = p.id)
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
            // and /trip returns that run's REAL stop sequence with per-stop realtime and CANCELED
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

    private var streetViewJob: kotlinx.coroutines.Job? = null

    /** Open the in-app Street View for a place: resolve the nearest pano (keyless metadata), then
     *  stitch its tiles into the equirect the GL viewer textures. No coverage → a brief toast, no
     *  viewer. Two-stage state so the viewer can show a spinner while tiles load. */
    fun openStreetView(place: Place) {
        // COPY GOOGLE when we can: the search response's own SV thumbnail carries the exact pano id
        // + camera yaw the Google app opens (svPanoId/svYawDeg). Using them verbatim lands on the
        // same imagery, facing the same way - no picking, no aiming. The heuristics below are only
        // the fallback for places whose response ships no thumbnail.
        val pid = place.svPanoId
        if (pid != null) {
            loadStreetView(faceHeading = place.svYawDeg) { dataSource.streetViewByPano(pid) }
            return
        }
        loadStreetView(faceToward = place.location) {
            // Prefer a pano on the place's OWN street (a mid-block geocode can otherwise snap to the
            // alley pano behind the building). For a business the street is in `address`; for a plain
            // address result it's the `name` ("2005 5th Ave") and `address` is just the locality - so
            // take whichever actually parses to a street.
            val street = listOfNotNull(place.address, place.name)
                .firstOrNull { app.vela.core.data.google.StreetViewParser.streetOf(it) != null }
            dataSource.streetView(place.location, preferStreet = street)
        }
    }

    /** Walk to a neighboring pano (arrow tap): fetch it BY ID so it's epoch-exact - a
     *  nearest-location lookup snapped to a different-year capture (green May imagery under a
     *  "December 2022" label). The new pano carries its own neighbors + history, so you keep
     *  walking. Face the way you walked (the link's bearing) so it reads as moving forward. */
    fun moveStreetView(link: app.vela.core.model.StreetViewLink) =
        loadStreetView(faceHeading = link.bearingDeg) { dataSource.streetViewByPano(link.panoId) }

    /** Tap on the mini-map while Street View is open: jump the viewer to the nearest pano at the
     *  tapped point (pegman-drop), looking toward what was tapped. */
    fun moveStreetViewTo(location: LatLng) =
        loadStreetView(faceToward = location) { dataSource.streetView(location) }

    /**
     * @param faceToward when set, the initial camera faces from the resolved pano TOWARD this point
     *   (the place we opened Street View on), so you look at the address, not the pano's capture
     *   heading. @param faceHeading an explicit initial heading (used when walking).
     */
    private fun loadStreetView(
        faceToward: app.vela.core.model.LatLng? = null,
        faceHeading: Double? = null,
        fetch: suspend () -> app.vela.core.model.StreetViewPano?,
    ) {
        streetViewJob?.cancel()
        _state.value.streetViewBitmap?.recycle()
        _state.update {
            it.copy(streetViewLoading = true, streetViewHistorical = false,
                // keep the old pano/bitmap on screen under the spinner while moving; a fresh open
                // has none anyway.
                )
        }
        streetViewJob = viewModelScope.launch {
            val raw = runCatching { fetch() }.getOrNull()
            if (raw == null) {
                _state.update { it.copy(streetViewLoading = false, streetView = null, streetViewBitmap = null) }
                flashStatus(appContext.getString(R.string.street_view_none))
                return@launch
            }
            // Aim the opening view like Google: look ACROSS the street at the building, not down the
            // road. The metadata heading is the street's direction, so snap to the road-PERPENDICULAR
            // on the target's side, then let the real bearing nudge it toward the facade - clamped to
            // +-40 deg so a road-snapped geocode (bearing points down the street) can't swing the view
            // down the road. On a move we just face the way we walked (faceHeading).
            val facing = faceToward?.let { target ->
                // Tapped (nearly) the pano's own spot - a bearing to a coincident point is noise;
                // fall through to the capture heading (down the street), the pegman-drop default.
                if (LatLng(raw.lat, raw.lng).distanceTo(target) < 8.0) return@let null
                val toTarget = LatLng(raw.lat, raw.lng).bearingTo(target)
                val perpA = (raw.headingDeg + 90.0).mod(360.0)
                val perpB = (raw.headingDeg + 270.0).mod(360.0)
                val perp = if (angleDiff(perpA, toTarget) <= angleDiff(perpB, toTarget)) perpA else perpB
                val nudge = (((toTarget - perp + 540.0) % 360.0) - 180.0).coerceIn(-40.0, 40.0)
                (perp + nudge).mod(360.0)
            } ?: faceHeading
            // initialFacingDeg carries the desired view; headingDeg must STAY the capture heading -
            // it's the texture's compass reference (overwriting it skewed the whole compass frame).
            val pano = if (facing != null) raw.copy(initialFacingDeg = facing) else raw
            _state.update {
                it.copy(streetView = pano, streetViewBitmap = null,
                    streetViewShownYear = pano.captureYear, streetViewShownMonth = pano.captureMonth,
                    streetViewHistorical = false)
            }
            val bmp = runCatching { app.vela.streetview.StreetViewTiles.load(dataSource, pano) }.getOrNull()
            if (bmp == null) {
                _state.update { it.copy(streetViewLoading = false, streetView = null) }
                flashStatus(appContext.getString(R.string.street_view_none))
                return@launch
            }
            _state.update { it.copy(streetViewBitmap = bmp, streetViewLoading = false) }
        }
    }

    /** Go back (or forward) in time: load a historical capture's tiles by pano id, keeping the base
     *  pano's metadata (so the date picker + walk arrows return when you come back to the present). */
    fun timeTravelStreetView(time: app.vela.core.model.StreetViewTime) {
        val base = _state.value.streetView ?: return
        streetViewJob?.cancel()
        _state.value.streetViewBitmap?.recycle()
        val historical = time.panoId != base.panoId
        _state.update {
            it.copy(streetViewLoading = true, streetViewBitmap = null,
                streetViewShownYear = time.year, streetViewShownMonth = time.month,
                streetViewHistorical = historical)
        }
        streetViewJob = viewModelScope.launch {
            // The old capture has its OWN pyramid shape (pre-2016 = 416·2^z, not 512·2^z, and
            // sometimes fewer levels) and its OWN heading (epochs differ by up to 180 deg) -
            // loading its tiles on the base pano's grid left BLACK BANDS over part of the sphere,
            // and keeping the base heading rotated the historical view. Fetch its metadata by id;
            // if that fails, the base pyramid is the best remaining guess.
            val hist = if (time.panoId == base.panoId) base
            else runCatching { dataSource.streetViewByPano(time.panoId) }.getOrNull()
            val bmp = runCatching {
                if (hist != null) app.vela.streetview.StreetViewTiles.load(dataSource, hist)
                else app.vela.streetview.StreetViewTiles.load(dataSource, time.panoId, base.tileSize, base.levelDims)
            }.getOrNull()
            if (bmp == null) {
                // Fall back to the present rather than a black screen.
                _state.update {
                    it.copy(streetViewLoading = false, streetViewHistorical = false,
                        streetViewShownYear = base.captureYear, streetViewShownMonth = base.captureMonth)
                }
                flashStatus(appContext.getString(R.string.street_view_none))
                return@launch
            }
            _state.update {
                // Keep the BASE metadata (arrows/dates come back with the present) but adopt the
                // DISPLAYED capture's heading - it's the texture's compass reference. Guard on
                // identity: a close/reopen mid-fetch must not get the old heading stamped on it.
                val sv = it.streetView?.takeIf { cur -> cur.panoId == base.panoId }
                    ?.copy(headingDeg = (hist ?: base).headingDeg) ?: it.streetView
                it.copy(streetView = sv, streetViewBitmap = bmp, streetViewLoading = false)
            }
        }
    }

    fun closeStreetView() {
        streetViewJob?.cancel()
        _state.value.streetViewBitmap?.recycle()
        _state.update {
            it.copy(streetView = null, streetViewBitmap = null, streetViewLoading = false,
                streetViewShownYear = null, streetViewShownMonth = null, streetViewHistorical = false)
        }
    }

    /** Offline, a POI that OSM never tagged with an address (most US chains) shows a bare place sheet —
     *  no online detail fetch can fill it. Reverse-geocode its location against the on-device address
     *  index (nearest mapped house, else nearest street) so it still shows an address. Only when offline,
     *  only when the place lacks one, and only if it's still the selected place when the lookup returns. */
    private fun backfillOfflineAddress(p: Place) {
        // Fire when there's no real street line, not only when address is fully blank: OSM often tags a POI
        // with just `addr:state`/`addr:city` (a chain came back as bare state initials), which is useless. Treat an
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
        if (p.name.isBlank() || googleOff()) return
        // Fetch unless the place already looks complete. Beyond the three rich fields, a
        // missing review count / full weekly hours / address means this is a sparse summary
        // node (a suite/multi-tenant address snap) worth enriching from the focused re-fetch.
        // 2026-09-23: the editorial blurb and owner description used to be required too, and most
        // businesses have no owner description, so this hidden page loaded on nearly every tap.
        // The search reply already carries both when Google has them.
        val complete = p.popularTimes != null && p.reviewCount != null && !p.address.isNullOrBlank() && p.hours.size >= 2
        if (complete) return
        _state.update { if (it.selected?.id == p.id) it.copy(loadingDetails = true) else it }
        viewModelScope.launch {
            // The details page is a search for "name address" run inside a warmed Google page. Sent
            // plainly the SAME search answers in full too, just not the first time: Google answers a
            // place's first request stripped (no review count, no popular times, one hours line) and
            // the same request seconds later complete (4a, 2026-09-23: 45 KB stripped, then 93 KB
            // with popular times, count and 7 hours lines, through OkHttp and Cronet alike). So: one
            // plain request, one retry if the reply is the stripped kind, and the page only when
            // both come back stripped. A complete reply without popular times means the place has
            // none; the page would not find any either.
            val missing = listOfNotNull(
                "popularTimes".takeIf { p.popularTimes == null }, "reviewCount".takeIf { p.reviewCount == null },
                "address".takeIf { p.address.isNullOrBlank() }, "hours".takeIf { p.hours.size < 2 },
            )
            suspend fun focusedSearch() = runCatching { dataSource.placeDetails(p) }.getOrNull()
            // A reply can carry the count and still lack popular times the place has (seen on the 4a),
            // so retry whenever popular times are missing; after the retry a reply with a count is
            // taken as "this place has none".
            fun complete(f: app.vela.core.model.PlaceDetails?) = f != null && (f.popularTimes != null || f.reviewCount != null)
            val fidKey = p.featureId
            val cachedDetails = fidKey?.let { placeCacheGet(detailsCache, it, DETAILS_CACHE_MS) }
            var focused = if (cachedDetails == null && tuneOn("nativeDetails")) focusedSearch() else null
            if (placeTries() >= 2 && cachedDetails == null && tuneOn("nativeDetails") && focused?.popularTimes == null) {
                delay(placeRetryWait(1))
                if (_state.value.selected?.id != p.id) return@launch
                focused = focusedSearch()
            }
            if (placeTries() >= 3 && cachedDetails == null && tuneOn("nativeDetails") && focused?.popularTimes == null) { // third and last
                delay(placeRetryWait(2))
                if (_state.value.selected?.id != p.id) return@launch
                focused = focusedSearch()
            }
            val native = focused?.takeIf { complete(it) }
            android.util.Log.i("VelaPlaceLoad", "details: missing $missing; ${when { cachedDetails != null -> "cache"; native != null -> "plain search${if (native.popularTimes == null) " (no popular times at this place)" else ""}"; else -> "details page" }}")
            val d = cachedDetails ?: (native ?: runCatching { webPopularTimes.fetch(p) }.getOrNull())
                ?.also { if (fidKey != null) placeCachePut(detailsCache, fidKey, it) }
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
    /** "More photos": the next gallery page, one request of 10 (2026-09-23). When the first batch
     *  came from the page walk instead (the RPC gave nothing), it walks the whole gallery. */
    fun loadAllPhotos() {
        val st = _state.value
        val p = st.selected ?: return
        val fid = p.featureId ?: return
        val token = st.photosNextToken
        if (token == null) {
            _state.update { it.copy(morePhotosFor = null) }
            fetchPhotos(p, full = true)
            return
        }
        if (st.photosLoading) return
        _state.update { it.copy(photosLoading = true) }
        viewModelScope.launch {
            var page = runCatching { dataSource.placePhotoPage(fid, token) }.getOrNull()
            if (page?.photos.isNullOrEmpty()) { // the same first-answer emptiness as the first page
                delay(placeRetryWait(1))
                if (_state.value.selected?.featureId != fid) { _state.update { it.copy(photosLoading = false) }; return@launch }
                page = runCatching { dataSource.placePhotoPage(fid, token) }.getOrNull()
            }
            android.util.Log.i("VelaPlaceLoad", "photos: next page ${page?.photos?.size ?: -1}${if (page?.photos.isNullOrEmpty()) ", walking the page" else ""}")
            if (page?.photos.isNullOrEmpty()) {
                // Asked for more and the RPC will not page: the page walk it is (a tap, not unasked).
                _state.update { it.copy(photosLoading = false, photosNextToken = null, morePhotosFor = null) }
                if (_state.value.selected?.featureId == fid) fetchPhotos(p, full = true)
                return@launch
            }
            _state.update {
                val sel = it.selected
                if (sel?.featureId != fid) it.copy(photosLoading = false)
                else {
                    val have = sel.photoUrls.toSet()
                    val add = page?.photos.orEmpty().filter { ph -> ph.url !in have }
                    it.copy(
                        selected = sel.copy(
                            photoUrls = sel.photoUrls + add.map { ph -> ph.url },
                            photoDates = sel.photoDates + add.map { ph -> ph.postedText },
                            photoCategories = sel.photoCategories + add.map { null },
                        ),
                        photosLoading = false,
                        photosNextToken = page?.nextToken,
                        morePhotosFor = if (page?.nextToken != null) fid else null,
                    )
                }
            }
        }
    }

    private fun fetchPhotos(p: Place, full: Boolean = app.vela.ui.FullPlaceLoad.on.value) {
        // "Load photos" off: never start the gallery scrape (it's the heaviest per-place
        // request); the sheet also hides the photo strip, so no loading flag either.
        if (!app.vela.ui.LoadPhotos.on.value || googleOff()) return
        // Satellite / bandwidth-constrained link (issue #235): the gallery walk is the single
        // heaviest per-place transfer, so it is the first thing to go. Everything else on the
        // sheet still loads - the place is still usable, just without photos.
        if (_state.value.lowData) return
        val fid = p.featureId
        if (fid.isNullOrBlank() || !fid.contains(":")) return
        // Only flash the loading shimmer for places LIKELY to have photos — a rated/reviewed
        // business or one with a preview already. A residential address (no rating, reviews, or
        // preview) shouldn't show a photo placeholder for a gallery it'll never have. We still
        // run the scrape silently in case it surprises us; we just don't promise photos.
        val photoWorthy = p.rating != null || p.reviewCount != null || p.photoUrls.isNotEmpty()
        _state.update { it.copy(photosNextToken = null) } // never page place B with place A's cursor
        if (photoWorthy) _state.update { if (it.selected?.featureId == fid) it.copy(photosLoading = true) else it }
        viewModelScope.launch {
            // FIRST BATCH = ONE REQUEST (2026-09-23): the gallery RPC (hspqX) answers a plain request
            // once it carries Calibration.rpcContext, with each photo's date. The page walk (a whole
            // Google web app) runs only for "More photos" (it adds the Menu tab), or when the RPC
            // gives nothing.
            if (!full && tuneOn("nativePlacePhotos")) {
                // A brand-new Google session answers its first seconds stripped (the slim flavor
                // nearbyPlaces heals too): one short, jittered retry of the ONE request beats
                // falling through to a whole page load (seen on the 4a: 0 photos, then 10).
                val cached = placeCacheGet(photoCache, fid, PHOTOS_CACHE_MS)
                var page = cached ?: runCatching { dataSource.placePhotoPage(fid) }.getOrNull()
                if (placeTries() >= 2 && page?.photos.isNullOrEmpty()) {
                    delay(placeRetryWait(1))
                    if (_state.value.selected?.featureId != fid) return@launch
                    page = runCatching { dataSource.placePhotoPage(fid) }.getOrNull()
                }
                if (placeTries() >= 3 && page?.photos.isNullOrEmpty()) { // a third try, later, before any page load
                    delay(placeRetryWait(2))
                    if (_state.value.selected?.featureId != fid) return@launch
                    page = runCatching { dataSource.placePhotoPage(fid) }.getOrNull()
                }
                val native = page?.photos.orEmpty()
                android.util.Log.i("VelaPlaceLoad", "photos: ${if (cached != null) "cache" else "rpc"} ${native.size} of ${page?.total}${if (native.isEmpty()) ", nothing yet (More photos walks the page)" else ""}")
                if (native.isEmpty()) {
                    // Three empty answers: keep the search's hero photo and leave the page walk (a whole
                    // Google web app) to a tap on "More photos" rather than loading it unasked.
                    _state.update { st -> if (st.selected?.featureId == fid) st.copy(photosLoading = false, morePhotosFor = fid, photosNextToken = null) else st }
                    return@launch
                }
                if (page != null && native.isNotEmpty()) {
                    if (cached == null) placeCachePut(photoCache, fid, page)
                    _state.update { st ->
                        val sel = st.selected
                        if (sel?.featureId == fid) st.copy(
                            selected = sel.copy(photoUrls = native.map { it.url }, photoDates = native.map { it.postedText }, photoCategories = native.map { null }),
                            photosLoading = false,
                            morePhotosFor = if (page.nextToken != null) fid else null,
                            photosNextToken = page.nextToken,
                        ) else st
                    }
                    return@launch
                }
            }
            // The gallery has TWO keyless sources with complementary halves: the WebView page
            // walk carries the CATEGORY tags (the Menu tab) but no per-photo dates, while the
            // hspqX RPC carries each photo's POSTED DATE but no categories. Fire the cheap RPC
            // alongside the walk and join its dates onto the streamed photos by the stable
            // image id (the URL up to the size suffix both pipelines share), so a menu tile
            // can say how old the menu shot is (user 2026-07-11). No author anywhere keyless:
            // the RPC documents it's absent, and mining the page DOM for it isn't worth the
            // extra walking. Best-effort like everything else here.
            var rpcDates: Map<String, String> = emptyMap()
            // The RPC has answered zero photos to every keyless client since 2026-07-11 (bot-gated,
            // not drifted), so it is NOT sent by default: a request per place tap that can only
            // come back empty is Google contact for nothing. The `photoDatesRpc` tuning dial (1 =
            // on) revives it from the signed calibration if Google ever answers again.
            val datesJob = if (app.vela.core.config.CalibrationStore.latest.tune("photoDatesRpc", 1.0) < 0.5) null else launch {
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
            val gallery = runCatching {
                webPhotos.fetch(fid, count = if (full) 80 else FIRST_PHOTOS, early = !full, onPhotoDates = { pairs ->
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
            datesJob?.join()
            _state.update { st ->
                val sel = st.selected
                if (sel?.featureId == fid) st.copy(
                    selected = if (gallery.isNotEmpty()) sel.copy(photoUrls = gallery.map { it.url }, photoDates = datesFor(gallery), photoCategories = gallery.map { it.category }) else sel,
                    photosLoading = false,
                    // A first batch that filled up means there is more to walk.
                    morePhotosFor = if (!full && gallery.size >= FIRST_PHOTOS) fid else null,
                ) else st
            }
        }
    }

    // PER-PLACE CACHE (2026-09-23): reopening a place within a few hours costs Google nothing.
    // Photos and the review feed keep 6 h; details 15 min, because popular times carry the live
    // "busy right now". Process lifetime only, 80 places each, access order.
    private class PlaceCacheEntry<T>(val value: T, val at: Long)
    private fun <T> lru() = object : LinkedHashMap<String, PlaceCacheEntry<T>>(96, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlaceCacheEntry<T>>?) = size > 80
    }
    private val photoCache = lru<app.vela.core.data.google.parse.PhotoPage>()
    private val feedCache = lru<app.vela.core.data.google.parse.ReviewFeed>()
    private val detailsCache = lru<app.vela.core.model.PlaceDetails>()
    private fun <T> placeCacheGet(m: LinkedHashMap<String, PlaceCacheEntry<T>>, key: String, ttlMs: Long): T? = synchronized(m) {
        m[key]?.takeIf { System.currentTimeMillis() - it.at < ttlMs }?.value
    }
    private fun <T> placeCachePut(m: LinkedHashMap<String, PlaceCacheEntry<T>>, key: String, v: T) = synchronized(m) {
        m[key] = PlaceCacheEntry(v, System.currentTimeMillis())
    }

    /** Wait before retry [n] of a one-request place load (calibration `placeRetryMs`, default
     *  2500, plus `placeRetryStepMs` (1000) per later try), jittered. Google answers a place's first
     *  request stripped and the repeat in full; how long it takes to warm is Google's to change. */
    private fun placeRetryWait(n: Int): Long {
        val cal = app.vela.core.config.CalibrationStore.latest
        return app.vela.core.util.Jitter.around((cal.tune("placeRetryMs", 2500.0) + (n - 1) * cal.tune("placeRetryStepMs", 1000.0)).toLong().coerceIn(0L, 20_000L))
    }

    /** How many tries a one-request place load gets before the page fallback (`placeTries`, 3). */
    private fun placeTries(): Int = app.vela.core.config.CalibrationStore.latest.tune("placeTries", 3.0).toInt().coerceIn(1, 5)

    /** A remote kill switch in calibration `tuning` (1 = on, the compiled default; 0 = the old
     *  hidden-page path). The rollback lever for the one-request place loads. */
    private fun tuneOn(key: String) = app.vela.core.config.CalibrationStore.latest.tune(key, 1.0) >= 0.5

    /** "More reviews": the next page of the native feed, appended. */
    fun loadMoreReviews() {
        val st = _state.value
        val p = st.selected ?: return
        val fid = p.featureId ?: return
        val token = st.reviewsNextToken ?: return
        if (st.reviewsMoreLoading) return
        _state.update { it.copy(reviewsMoreLoading = true) }
        viewModelScope.launch {
            val page = runCatching { dataSource.reviewFeed(fid, app.vela.web.WebReviewsFetcher.reviewsHl(), token) }.getOrNull()
            android.util.Log.i("VelaPlaceLoad", "reviews: next page ${page?.reviews?.size ?: -1}")
            _state.update {
                if (it.selected?.featureId != fid) it.copy(reviewsMoreLoading = false)
                else it.copy(
                    reviews = (it.reviews + page?.reviews.orEmpty()).distinctBy { r -> r.author to r.text },
                    reviewsNextToken = page?.nextToken,
                    reviewsMoreLoading = false,
                )
            }
        }
    }

    /** Pull full reviews for a place by its Google feature id (best-effort,
     *  applied only if it's still the selected place when they arrive). */
    private var reviewsJob: Job? = null

    private fun fetchReviews(p: Place, force: Boolean = false) {
        // "Show reviews" off: no review section is rendered, so don't scrape either.
        if (!app.vela.ui.ShowReviews.on.value || googleOff()) return
        // BARE transit stops: never scrape reviews. A bus stop's content is its departure board, not
        // reviews (the WebView grind competed with the board load and rendered awkwardly). But gate on
        // "transit-category AND UNRATED", not category alone - a rated transit CENTER (a real building
        // people review) must KEEP its reviews (user 2026-07-13: broad category gate wrongly killed
        // them). Real buildings carry a Google rating; bare stops don't.
        if (p.rating == null && p.category?.let { isTransitCategory(it) } == true) return
        // Supersede any in-flight scrape: the fetcher serializes on a Mutex, so an abandoned
        // 40 s Taco Bell grind would otherwise make the NEXT place's reviews queue behind it
        // (~90 s worst case to first review). Canceling frees the mutex immediately, and this
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
        _state.update { it.copy(reviewsLoading = true, reviewsFound = 0, reviewsLimited = false, reviewsNextToken = null, reviewsMoreLoading = false) }
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
            // First page only unless the full-load setting is on: every page past the first is
            // another feed request, and the All reviews page has the rest.
            val fullLoad = app.vela.ui.FullPlaceLoad.on.value || force
            val reviewCap = if (fullLoad) 50 else FIRST_REVIEWS
            // FIRST PAGE = ONE REQUEST (2026-09-23): the feed RPC the place page's Reviews tab makes
            // answers a plain request with Calibration.rpcContext. The hidden page scrape (a whole
            // Google web app plus a feed request per scroll) is the fallback, and the full load.
            if (!fullLoad && tuneOn("nativeReviewFeed")) {
                val cached = placeCacheGet(feedCache, fid, REVIEWS_CACHE_MS)
                var feed = cached ?: runCatching { dataSource.reviewFeed(fid, app.vela.web.WebReviewsFetcher.reviewsHl()) }.getOrNull()
                // Same fresh-session retry as the photos. Unless the count is KNOWN to be 0: a stripped
                // search reply (the same fresh-session window) has no count at all.
                if (placeTries() >= 2 && feed?.reviews.isNullOrEmpty() && p.reviewCount != 0) {
                    delay(placeRetryWait(1))
                    if (_state.value.selected?.featureId != fid) return@launch
                    feed = runCatching { dataSource.reviewFeed(fid, app.vela.web.WebReviewsFetcher.reviewsHl()) }.getOrNull()
                }
                if (placeTries() >= 3 && feed?.reviews.isNullOrEmpty() && p.reviewCount != 0) { // a third try, later, before the page scrape
                    delay(placeRetryWait(2))
                    if (_state.value.selected?.featureId != fid) return@launch
                    feed = runCatching { dataSource.reviewFeed(fid, app.vela.web.WebReviewsFetcher.reviewsHl()) }.getOrNull()
                }
                // Limited view = the feed ends after a short list for a place that has more.
                val limited = feed != null && feed.end && feed.reviews.isNotEmpty() && feed.reviews.size < minOf(FIRST_REVIEWS, expected)
                android.util.Log.i("VelaPlaceLoad", "reviews: ${if (cached != null) "cache" else "feed"} ${feed?.reviews?.size ?: -1}${if (limited) " (limited view)" else ""}${if (feed?.reviews.isNullOrEmpty()) ", scraping the page" else ""}")
                if (feed != null && feed.reviews.isNotEmpty()) {
                    if (cached == null) placeCachePut(feedCache, fid, feed)
                    if (_state.value.selected?.featureId == fid) {
                        _state.update { it.copy(reviews = feed.reviews, reviewsLoading = false, reviewsFound = 0, reviewsLimited = limited, reviewsNextToken = feed.nextToken) }
                    }
                    return@launch
                }
            }
            var revs = settle(runCatching { webReviews.fetch(fid, onProgress, onPartial, reviewCap) }.getOrDefault(emptyList()))
            coroutineContext.ensureActive() // superseded by a newer fetch — don't touch state below
            var attempt = 1
            // A fresh fetch clears the flake within a few seconds (confirmed: a manual tap-to-
            // retry succeeds), so auto-retry across a ~3 s window before falling back to the
            // manual retry — most flakes self-heal without the user touching anything.
            while (tooFew(revs) && expected > 0 && attempt <= 2) {
                delay(app.vela.core.util.Jitter.around(500L * attempt)) // the WebView fetch is thorough (internal polling); one retry covers a page-load miss
                if (_state.value.selected?.featureId != fid) return@launch // user moved on
                // The dead attempt's last count would otherwise sit frozen on the bar through the
                // retry's page-load window, then visibly snap backward when its first tick lands.
                _state.update { it.copy(reviewsFound = 0) }
                revs = settle(runCatching { webReviews.fetch(fid, onProgress, onPartial, reviewCap) }.getOrDefault(emptyList()))
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
        // Remember what the user just had open: the close-triggered ambient repaint re-cuts the
        // pool to the zoom-tiered cap, and a modest place could lose its slot to ranking jitter -
        // the icon you were JUST looking at vanished on back-out (user 2026-07-17). The pin below
        // (withRecentlyViewed) force-keeps it in the next paints. A place the LIVE details fetch
        // found permanently closed gets the opposite treatment: never pinned (the pin bypasses
        // keepAmbientForView's closed filter), and the verdict is written back into the ambient
        // store so its cached dot stops painting from now on - cache renders, network verifies.
        _state.value.selected?.let {
            if (it.permanentlyClosed) {
                markClosedInAmbient(it)
            } else {
                recentlyViewed = it
                recentlyViewedAtMs = android.os.SystemClock.elapsedRealtime()
            }
        }
        _state.update {
            it.copy(
                selected = null, placesHere = emptyList(), reviews = emptyList(), reviewsLoading = false, reviewsFound = 0, loadingDetails = false,
                routes = emptyList(), activeRoute = null, directionsOpen = false,
                transit = emptyList(), transitLoading = false,
                showSteps = false, previewStepIndex = null,
                directionsWaypoints = emptyList(), pickingStop = false,
            )
        }
        // Opening a place pans the camera to center it, so the ambient POIs (loaded for the previous
        // center) can be off-screen once we're back on the bare map. Closing no longer moves the camera
        // (that was the "camera spazz"), so nothing fires a camera-idle to reload them. Do it here.
        refreshAmbientForCurrentView()
    }

    /** Re-evaluate the ambient POIs for whatever the map is currently showing. Used when returning to
     *  the bare map without a camera move (e.g. closing a place). No-op if there's no viewport yet, and
     *  [maybeLoadAmbientPois] keeps its own gates (skips while results/nav/a place are up, only refetches
     *  on a real pan/zoom). */
    /** Ambient pool minus parks/schools/civic when that toggle is off (user 2026-07-15) -
     *  filtered IN STATE (not display-side) so the map layer's tap indices stay aligned with
     *  state.ambientPois. */
    private fun civicFiltered(list: List<app.vela.core.model.Place>): List<app.vela.core.model.Place> =
        if (app.vela.ui.MapPoiPrefs.showCivic.value) list
        else list.filter { PoiIcons.groupFor(it.name, it.category) !in CIVIC_GROUPS }

    /** POI prefs changed in Settings: drop the current pool/stops and re-resolve for the view,
     *  so toggles act immediately instead of on the next pan. */
    fun onPoiPrefsChanged() {
        lastAmbientCenter = null
        if (!app.vela.ui.MapPoiPrefs.showTransit.value && _state.value.transitStops.isNotEmpty()) {
            _state.update { it.copy(transitStops = emptyList()) }
        }
        refreshAmbientForCurrentView()
        // The open places layer is driven by its own state, so the switch has to reach it here or
        // it would only take effect on the next camera idle (issue #597).
        refreshPlacesOverlays()
    }

    private fun refreshAmbientForCurrentView() {
        val vp = viewport ?: return
        val c = mapCenter ?: LatLng((vp[0] + vp[2]) / 2, (vp[1] + vp[3]) / 2)
        val radius = c.distanceTo(LatLng(vp[2], vp[3]))
        maybeLoadAmbientPois(c, vp[4], radius)
    }

    /** Back out of the directions preview to the place sheet: drop the route,
     *  keep the place selected (so back peels one layer at a time). */
    fun clearRoute() {
        autoStartOnRoute = false // backing out of directions cancels a pending auto-start (issue #272)
        destination = null
        routeJob?.cancel() // an in-flight directions fetch must not repopulate the route we're backing out of
        modeEtaJob?.cancel(); modeEtaKey = null
        _state.update {
            it.copy(
                routes = emptyList(), activeRoute = null, directionsOpen = false,
                transit = emptyList(), transitLoading = false, modeEtas = emptyMap(),
                showSteps = false, previewStepIndex = null,
                directionsOrigin = null, pickingOrigin = false, pickingDest = false, directionsReversed = false,
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
    /** OpenMapTiles `place` classes: a tapped label of these is a settlement, whose search hit
     *  may legitimately sit kilometers from the label point (the label marks the center). */
    private val SETTLEMENT_KINDS = setOf(
        "city", "town", "village", "hamlet", "suburb", "neighbourhood", "quarter", "locality",
        "borough", "island", "islet", "state", "province", "country", "continent",
    )

    /** A tap on the open places layer (Overture tile feature): the tile's own attributes seed the
     *  sheet at once, so offline it already shows the category, address, phone and website, and the
     *  Google correlation in [onPoiTap] upgrades it to the listing when online. */
    fun onOpenPlaceTap(p: Place) = onPoiTap(p.name, p.location, p.category, seed = p)

    /** Open-places id -> the Google listing it resolved to, so a second tap on the same pin is
     *  instant. Local to the device only (a published crosswalk would redistribute Google ids).
     *  Persisted to `open_place_links.json` so the link survives a restart: a place you tapped once
     *  opens straight to its listing next week, and offline it opens to the last listing seen. */
    private val openPlaceCache = object : LinkedHashMap<String, Place>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Place>?) = size > 500
    }
    private fun openLinksFile() = java.io.File(appContext.filesDir, "open_place_links.json")
    private var openLinksPersistJob: Job? = null

    private fun loadOpenPlaceLinks() {
        // Launched on Main (dispatched, so it runs after the constructor has finished initializing
        // every property below this one), with only the file work on IO: an IO launch straight from
        // init raced the constructor and hit the cache before its initializer ran (crash, 2026-09-14).
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                // AN UPDATE DROPS THE REMEMBERED LINKS (user 2026-09-18). The cache exists so a
                // second tap on the same pin is instant, but it also means a link resolved by an
                // OLDER, worse rule survives the fix for it: a supermarket that once resolved to
                // the fuel station beside it kept opening the fuel station after the ranking bug
                // was fixed, because the tap never reached the ranking again. A new build is
                // exactly the moment those answers stop being trustworthy, and re-resolving costs
                // one search on the next tap.
                val prefs = appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
                val seen = prefs.getInt("open_links_build", 0)
                if (seen != app.vela.BuildConfig.VERSION_CODE) {
                    val had = openLinksFile().length()
                    openLinksFile().delete()
                    prefs.edit().putInt("open_links_build", app.vela.BuildConfig.VERSION_CODE).apply()
                    android.util.Log.d("VelaPlaces", "build changed ($seen -> ${app.vela.BuildConfig.VERSION_CODE}), dropped $had bytes of remembered place links")
                }
                val raw = runCatching { openLinksFile().readText() }.getOrNull() ?: return@withContext emptyList()
                val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return@withContext emptyList()
                val out = ArrayList<Pair<String, Place>>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("o").takeIf { it.isNotBlank() } ?: continue
                    val p = app.vela.core.config.PlaceJson.decode(o.optString("p"))?.firstOrNull() ?: continue
                    out += id to p
                }
                out
            }
            val closed = withContext(Dispatchers.IO) {
                runCatching { org.json.JSONArray(closedOpenPlacesFile().readText()) }.getOrNull()
                    ?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } }.toSet() }
                    .orEmpty()
            }
            if (closed.isNotEmpty()) _state.update { it.copy(hiddenOpenPlaceIds = closed) }
            if (loaded.isEmpty()) return@launch
            synchronized(openPlaceCache) { loaded.forEach { (id, p) -> if (id !in openPlaceCache) openPlaceCache[id] = p } }
            android.util.Log.d("VelaPlaces", "open place links: loaded ${loaded.size}, closed ${closed.size}")
        }
    }

    /** Drop the remembered open-place-to-Google links. The rebaked data may have moved, renamed or
     *  merged the rows those links were keyed on, so keeping them would pin answers to places that
     *  no longer exist in that form. The closures list is NOT dropped: that is a correction, not a
     *  cache. */
    private fun forgetOpenPlaceLinks(why: String) {
        synchronized(openPlaceCache) { openPlaceCache.clear() }
        openLinksFile().delete()
        android.util.Log.d("VelaPlaces", "dropped remembered place links: $why")
    }

    private fun closedOpenPlacesFile() = java.io.File(appContext.filesDir, "open_place_closed.json")

    /** The tapped open place resolved to a Google listing that is permanently closed: Overture lags
     *  Google by months, so hide the pin now and remember it (the closed shops still on the map,
     *  user 2026-09-15). [seedId] is the seeded Place id ("overture:<id>"). */
    /** The map matched an open place to a permanently closed place in Google's nearby answer. */
    fun onOpenPlaceClosed(id: String) = hideClosedOpenPlace(id)

    private fun hideClosedOpenPlace(seedId: String) {
        val raw = seedId.removePrefix("overture:")
        if (raw.isBlank() || raw in _state.value.hiddenOpenPlaceIds) return
        val next = _state.value.hiddenOpenPlaceIds + raw
        _state.update { it.copy(hiddenOpenPlaceIds = next) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = java.io.File(appContext.filesDir, "open_place_closed.json.tmp")
                tmp.writeText(org.json.JSONArray(next.toList()).toString())
                tmp.renameTo(closedOpenPlacesFile())
            }
        }
    }

    private fun rememberOpenPlaceLink(id: String, full: Place) {
        synchronized(openPlaceCache) { openPlaceCache[id] = full }
        openLinksPersistJob?.cancel()
        openLinksPersistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(2_000) // coalesce a burst of taps into one write
            val snapshot = synchronized(openPlaceCache) { openPlaceCache.entries.map { it.key to it.value } }
            val arr = org.json.JSONArray()
            snapshot.forEach { (id, p) ->
                arr.put(org.json.JSONObject().put("o", id).put("p", app.vela.core.config.PlaceJson.encode(listOf(p))))
            }
            runCatching {
                val tmp = java.io.File(appContext.filesDir, "open_place_links.json.tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(openLinksFile())
            }
        }
    }

    fun onPoiTap(name: String, location: LatLng, poiKind: String? = null, seed: Place? = null) {
        // Dead during a live drive: the map is carpeted with tappable POIs at nav zoom, the
        // sheet this would build can't render under nav's bottom slot, and the stale selection
        // popped up when the drive ended. In-nav picks go through the search results instead...
        // ...unless "tap places while driving" is on, where the tap OFFERS the place as a stop
        // (and only the card's button acts on it). No sheet, no selection, no Google lookup.
        if (_state.value.navigating) {
            if (app.vela.ui.MapPoiPrefs.navTapPlaces.value) {
                val p = seed ?: Place(id = "poi:" + name.hashCode(), name = name, location = location)
                offerNavTapStop(p)
            }
            return
        }
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
        val placeholder = seed ?: Place(id = "poi:" + name.hashCode(), name = name, location = location)
        // A transit STOP is usually named by its intersection ("Main St & 1st Ave"), and Google resolves
        // that bare string to the road JUNCTION, not the stop - so a tapped stop opened as an "Intersection"
        // with no board (issue #71 follow-up; verified in a live capture: "<x> & <y>" -> Intersection,
        // "<x> & <y> bus stop" -> the Stop). When the TAPPED POI's kind says transit (its class/subclass,
        // not its name, so a business called "Salt & Straw" is untouched), append a mode word to the lookup
        // so the search returns the stop. Non-transit POIs search by name exactly as before.
        // ...but only for a TRANSIT kind. The open places layer seeds the tap with Overture's
        // category ("Gas station", "Fire station", "Electric vehicle charging station"), and the
        // bare "station" below took every one of those for a stop (user 2026-09-22: a fuel
        // station never linked; its lookup searched "<name> transit stop", kept only transit
        // listings, found none, and the unlimited transit cap hid that). The same exclusion list
        // the results use for "is this a stop" decides it here.
        val transitHint = poiKind?.lowercase()?.takeUnless { NON_TRANSIT_CAT.containsMatchIn(it) || "service station" in it }?.let { k ->
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
        // The sheet shows its loading skeleton while the tap is looked up (user 2026-09-22): only
        // when a lookup will actually run, and not for a transit stop, whose board has its own
        // loading state and which usually has no photos or rating to wait for.
        val willResolve = transitHint == null && !googleOff() &&
            (seed == null || app.vela.ui.MapPoiPrefs.lookupTappedPlaces.value)
        _state.update {
            it.copy(
                selected = placeholder,
                tapResolvingFor = if (willResolve) placeholder.id else null,
                sheetAlias = null,
                tapUnlinkedFor = null,
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
                pickingOrigin = false, pickingDest = false,
                pickingStop = false,
                directionsOpen = false,
            )
        }
        // What the map's own data knows, shown while Google is asked (user 2026-09-22): a matching
        // OSM row from a downloaded place pack fills the fields the tap did not bring (a basemap
        // label brings only its name; an open-data row may lack hours the OSM row has). Google's
        // listing replaces the whole sheet when it lands; offline, this is what stays.
        viewModelScope.launch {
            val twin = offlineTwin(name, location) ?: return@launch
            _state.update { st ->
                val cur = st.selected
                if (!isPlaceholder(cur, placeholder) || cur == null) st
                else st.copy(selected = cur.copy(
                    category = cur.category ?: twin.category,
                    address = cur.address ?: twin.address,
                    phone = cur.phone ?: twin.phone,
                    website = cur.website ?: twin.website,
                    hours = cur.hours.ifEmpty { twin.hours },
                ))
            }
        }
        // "Look up tapped places on Google" off (Settings > Map): an open place stays on what the
        // tile carries, nothing about the tap reaches Google. Basemap taps have no seed and still
        // resolve (they have nothing else to show).
        if (seed != null && !app.vela.ui.MapPoiPrefs.lookupTappedPlaces.value) {
            rememberRecentPlace(SavedPlace.of(placeholder))
            fetchStopDepartures(placeholder) // Transitous is not Google
            return
        }
        // Offline, or Google off: no search, no reviews, no photos. An open place shows its tile
        // data, or the Google listing remembered from an earlier online tap; a basemap tap keeps
        // its name.
        if (googleOff()) {
            val remembered = seed?.let { synchronized(openPlaceCache) { openPlaceCache[it.id] } }
            if (remembered != null && isPlaceholder(_state.value.selected, placeholder)) {
                _state.update { it.copy(selected = withListNote(remembered)) }
            }
            rememberRecentPlace(SavedPlace.of(remembered ?: placeholder))
            // Transitous needs no Google. A basemap stop has only its tile class, which the transit
            // hint already read, so the hint stands in for the category here.
            (_state.value.selected ?: placeholder).let { p ->
                fetchStopDepartures(if (p.category == null && transitHint != null) p.copy(category = transitHint) else p)
            }
            return
        }
        val uiLang = app.vela.ui.AppLocale.language.value.ifBlank { java.util.Locale.getDefault().language }
        // A lookup that hangs must not leave the sheet pulsing: after a few seconds the tapped
        // label's own data shows, and a late listing still swaps in (faded) when it lands.
        fun stopResolving() = _state.update { if (it.tapResolvingFor == placeholder.id) it.copy(tapResolvingFor = null) else it }
        if (willResolve) viewModelScope.launch { delay(TAP_RESOLVE_WATCHDOG_MS); stopResolving() }
        viewModelScope.launch {
          try {
            val remembered = seed?.let { synchronized(openPlaceCache) { openPlaceCache[it.id] } }
            val tTap = android.os.SystemClock.elapsedRealtime()
            var tSearch = 0L
            val resolved = if (remembered != null) (remembered to emptyList<Place>()) else runCatching {
                val results = dataSource.searchOnce(searchQuery, location)
                tSearch = android.os.SystemClock.elapsedRealtime() - tTap
                var tapWhy = "" // filled by the business branch below, printed with the tap line
                val pick = if (transitHint != null) {
                    // Transit tap: pick the OPERATING stop, not the nearest/most-reviewed thing at the
                    // coordinate. A stop's spot usually ALSO has a road junction (Google's "Intersection")
                    // and can carry a stale PERMANENTLY-CLOSED old shelter; nearest/most-reviewed lands on
                    // those (device 2026-07-13: a suburban highway-and-boulevard corner opened a
                    // Permanently-closed old stop; the route tap-through threw you to a corner). Take the nearest LIVE
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
                        ?: runCatching { dataSource.searchOnce(transitHint, location) }.getOrNull()
                            ?.let { nearestLiveStop(it, location) }
                } else {
                    // NAME AGREEMENT with the tapped label comes FIRST (user 2026-07-14: tapping a
                    // sushi restaurant in a strip mall opened the dessert shop two doors down). We
                    // searched for the tapped POI's own name, but the pick then ignored it: in a
                    // shared building Google's per-listing pins are loose enough that a NEIGHBOR
                    // can sit nearer the tapped icon than the business the icon belongs to, and the
                    // 35 m most-reviewed override (built for co-branded DUPLICATES of one business)
                    // cemented the wrong shop whenever the neighbor was more popular. So the pick
                    // pool is the listings whose name shares the tapped name's words; only when
                    // NOTHING agrees (a renamed or closed business) does the full result set - the
                    // old behavior - apply. Within the pool, nearest still wins and the clear-
                    // dominance override still promotes the rich profile of a true duplicate
                    // (a "SpeeDee Midas" tap matches both the SpeeDee and the Midas listings).
                    // A NON-TRANSIT tap must never resolve INTO a transit stop or a road junction
                    // (user 2026-09-18: a fuel station on a corner opened as the bus stop beside
                    // it). Google lists stops and intersections as places, they sit meters from the
                    // businesses on the same corner, and the pool below falls back to "everything"
                    // when no listing agrees by name - so the nearest answer, the stop, became the
                    // place. A stop is only ever the right answer for a tap that came FROM a stop,
                    // which the transit branch above already handles.
                    val answerable = results.filterNot { p ->
                        p.category?.let { isTransitCategory(it) || it.lowercase() in JUNCTION_CATEGORIES } == true
                    }
                    // Nothing agrees by name (a renamed or closed business): the old behavior was
                    // the nearest of everything, which is how a neighbor across the road could
                    // claim the tap. Keep it, but only on the same lot; past that the tapped label's
                    // own name and point stay, which for an open-data place still has its address,
                    // phone and hours.
                    // A PERMANENTLY CLOSED listing never beats a live one (user 2026-09-19: a
                    // chain store that had moved across the road kept resolving to its old,
                    // closed listing, which then hid the open pin for good). Google keeps the
                    // closed profile beside the live one for months; the live one is the answer
                    // whenever there is one.
                    val tappedKind = PoiIcons.groupFor(name, seed?.category ?: poiKind)
                    // THE ADDRESS, where both sides have one (user 2026-09-22: "are we gating by
                    // actual address?"). Distances alone cannot tell a station from the one
                    // across the junction, 40-80 m apart; the house number can. The tapped row's
                    // number (the open-data address, or the pack twin's) against the listing's:
                    // a clash rules the listing out of every fallback that has no name match and
                    // out of name matches beyond the lot, and an agreeing number wins on the lot.
                    // Either side without a number decides nothing.
                    val tappedHouse = app.vela.core.util.PlaceNames.houseNumber(
                        _state.value.selected?.takeIf { isPlaceholder(it, placeholder) }?.address ?: seed?.address,
                    )
                    fun houseClash(p: Place): Boolean {
                        val a = tappedHouse ?: return false
                        val b = app.vela.core.util.PlaceNames.houseNumber(p.address) ?: return false
                        return a != b
                    }
                    fun houseAgrees(p: Place) = tappedHouse != null && app.vela.core.util.PlaceNames.houseNumber(p.address) == tappedHouse
                    // Words shared by three or more of the listings around the tap are the area's
                    // (a neighborhood, a mall, a landmark), generic for the comparison.
                    val localGeneric = app.vela.core.util.PlaceNames.localGeneric(results.map { it.name })
                    // Only a name match within reach of the tap counts (user 2026-09-22: a pin named
                    // for the brand on the pumps agreed with seventeen of that brand's stations
                    // miles away, which kept every nearby fallback from running while the right
                    // listing, under the seller's own name, sat 11 m from the pin; the far ones
                    // were then dropped by the distance cap and the tap linked to nothing).
                    val agreeing = answerable.filter { it.location.distanceTo(location) <= BUSINESS_TAP_CAP_M }
                        .filter { it.location.distanceTo(location) <= SAME_LOT_M || !houseClash(it) }
                        .filter { p ->
                        app.vela.core.util.PlaceNames.sameBusiness(
                            name, tappedKind, p.name, PoiIcons.groupFor(p.name, p.category),
                            app.vela.core.util.PlaceNames.cityWords(p.address) + localGeneric,
                        )
                    }
                    // A label in another script than the app's answer (a Japanese map under an
                    // English phone) gets a second search in the script's own language when
                    // nothing agreed; see crossScriptCandidates.
                    val crossScript = if (agreeing.isEmpty()) crossScriptCandidates(name, location, searchQuery, tappedKind, localGeneric, uiLang) else emptyList()
                    // The tapped kind, found BESIDE the building the name found (user 2026-09-22:
                    // an open-data fuel row named for the site, "<Station> <Pizza counter>", sat
                    // out on the highway; the name search found the pizza counter inside the
                    // station and no gas listing, and the kind rule rightly refused the pizza).
                    // A listing of the tapped KIND on the same spot is the next best thing to a name
                    // match, and costs nothing: it is already in the results.
                    val sameKindNear = if (tappedKind == "default") emptyList() else answerable.filter {
                        it.location.distanceTo(location) <= NO_NAME_MATCH_M && PoiIcons.groupFor(it.name, it.category) == tappedKind &&
                            !houseClash(it)
                    }
                    var kindRescue: List<Place> = emptyList()
                    val pool = agreeing.ifEmpty { crossScript }
                        .ifEmpty { sameKindNear }
                        .ifEmpty {
                            if (tappedKind != "default") kindRescue = kindBesideAnchor(name, location, seed?.category ?: poiKind, tappedKind, answerable)
                                .filterNot(::houseClash)
                            kindRescue
                        }
                        .ifEmpty { answerable.filter { it.location.distanceTo(location) <= NO_NAME_MATCH_M && !houseClash(it) } }
                        .let { p -> p.filter(::houseAgrees).ifEmpty { p } }
                        .let { p -> p.filterNot { it.permanentlyClosed }.ifEmpty { p } }
                    // THE SAME NAME BEATS A NEARER ONE (user 2026-09-18: tapping a supermarket
                    // opened the brand's fuel station, and tapping it opened a counter inside the
                    // store). `nameAgrees` is deliberately loose - it has to match "SpeeDee" to
                    // "SpeeDee Midas" - so a brand's other listings all qualify, and the pick was
                    // then whichever happened to sit nearest the tapped point. A listing whose name
                    // IS the tapped name is what the tap asked for; only when none exists does the
                    // looser pool decide. Store numbers are dropped so "SHOP #1561" still counts.
                    // ...but ONLY AMONG CANDIDATES ON THE SAME LOT (device 2026-09-18). The rule
                    // exists to separate a store from its own forecourt, which are metres apart;
                    // applied to the whole result set it instead preferred a listing with the
                    // exactly-matching name ANYWHERE over the business under the finger, because a
                    // brand's listing is often its name plus a word ("Starbucks Coffee Company" is
                    // not "Starbucks" once normalized). A tap on one landed on a branch 4 km away,
                    // which the distance cap then threw out, so the tap opened NOTHING while the
                    // right listing sat 6 m from it.
                    val local = pool.filter { it.location.distanceTo(location) <= SAME_LOT_M }
                    val exact = local.filter { app.vela.core.util.PlaceNames.same(name, it.name) }
                    // AND THE SAME KIND BEATS THE SAME NAME (user 2026-09-18, second report: the
                    // store still opened as the fuel station). Some brands name their forecourt
                    // listing exactly what they name the store, so the exact-name filter keeps both
                    // and the nearest one wins - and the tapped feature's point is the parcel, which
                    // can sit nearer the pumps than the doors. The tapped feature knows what KIND of
                    // place it is (the tile's own class, or the basemap's), so a candidate of that
                    // kind is preferred over one that merely shares the name: a supermarket tap
                    // takes the supermarket, a fuel tap takes the fuel.
                    val tappedGroup = PoiIcons.groupFor(name, seed?.category ?: poiKind)
                    val sameKind = exact.filter { PoiIcons.groupFor(it.name, it.category) == tappedGroup }
                        .takeIf { tappedGroup != "default" }
                        .orEmpty()
                    val ranked = sameKind.ifEmpty { exact.ifEmpty { pool } }
                    val poolNearest = ranked.minByOrNull { it.location.distanceTo(location) }
                    // Which gate emptied the pool, and what the three nearest answers actually
                    // were. Counts and distances only.
                    tapWhy = "agree=" + answerable.count { nameAgrees(name, it.name, it.address) } +
                        " near60=" + answerable.count { it.location.distanceTo(location) <= NO_NAME_MATCH_M } +
                        " cross=" + crossScript.size + " kindNear=" + sameKindNear.size + " kind=" + kindRescue.size + " pool=" + pool.size + " exact=" + exact.size +
                        " local=" + local.size + " group=" + tappedGroup + " sameKind=" + sameKind.size +
                        " house=" + (tappedHouse ?: "-") + " clash=" + answerable.count(::houseClash) +
                        " nearest=[" + answerable.sortedBy { it.location.distanceTo(location) }.take(3)
                            .joinToString("; ") { it.name + " " + "%.0f".format(it.location.distanceTo(location)) + "m/" + (it.category ?: "-") } + "]"
                    val canonical = ranked
                        .filter { it.location.distanceTo(location) < 35.0 }
                        .maxByOrNull { it.reviewCount ?: 0 }
                    if (canonical != null && poolNearest != null &&
                        (canonical.reviewCount ?: 0) >= 2 * (poolNearest.reviewCount ?: 0) + 5
                    ) canonical else poolNearest
                }
                // The pick must be NEAR THE TAP (issue #429): a town label for Salem, Arkansas
                // searched "Salem" and Google's nearest answer was Salem, Massachusetts, 1191 mi
                // away, which then opened as the place. A settlement label may resolve within a
                // town's radius, anything else within walking distance; farther than that the
                // tap keeps the bare label at its own coordinates instead of a stranger.
                val maxM = when {
                    transitHint != null -> Double.MAX_VALUE
                    poiKind?.lowercase() in SETTLEMENT_KINDS -> 30_000.0
                    else -> BUSINESS_TAP_CAP_M
                }
                val kept = pick?.takeIf { it.location.distanceTo(location) <= maxM }
                val preCap = pick?.let { it.name + " " + "%.0f".format(it.location.distanceTo(location)) + "m" } ?: "none"
                // WHY A TAP DID NOT LINK (user 2026-09-18: "more and more POIs that aren't
                // linking"). Three very different causes look identical on screen - the search
                // came back empty (a throttled session), nothing agreed by name or sat on the same
                // lot (a tile row whose name or point is off), or the pick was dropped for being
                // too far - and none of them left a trace. One line, no coordinates: the name is
                // the map's own label and the rest are counts.
                android.util.Log.d(
                    "VelaTap",
                    "tapped='" + name + "' kind=" + (seed?.category ?: poiKind) +
                        " seeded=" + (seed != null) + " results=" + results.size +
                        " answerable=" + results.count { p ->
                            p.category?.let { isTransitCategory(it) || it.lowercase() in JUNCTION_CATEGORIES } != true
                        } +
                        " " + tapWhy + " beforeCap=" + preCap +
                        " picked=" + (kept?.name ?: "NOTHING") +
                        " at=" + (kept?.let { "%.0f".format(it.location.distanceTo(location)) + "m" } ?: "-") +
                        " cap=" + maxM.toInt() + "m" +
                        " ms=" + tSearch + "/" + (android.os.SystemClock.elapsedRealtime() - tTap)
                )
                kept to results
            }.getOrNull()
            // Google answers with the local-script name even under hl=en (a Hebrew title over an
            // English app's Latin pin, user 2026-09-15): keep the map's own label when it is in
            // the app language's script and Google's is not (core NameScript, unit-tested).
            val full = resolved?.first?.let { f -> f.copy(name = app.vela.core.util.NameScript.prefer(uiLang, f.name, placeholder.name)) }
            // Remember the listing for an instant second tap, unless the session was still on the
            // slim flavor (no review count, no hours) and would pin a stripped listing for the
            // rest of the session. A later tap then resolves it again, fuller.
            if (full != null && seed != null && (full.reviewCount != null || full.hours.isNotEmpty())) {
                rememberOpenPlaceLink(seed.id, full)
            }
            // Hide the open pin for a closed listing ONLY when Google has no live listing of that
            // name nearby: a closure is a correction, a move is not (the same 150 m rule the
            // ambient purge uses). Before this a slow session that surfaced the old profile
            // first buried a business that was open across the street.
            if (full != null && seed != null && full.permanentlyClosed &&
                resolved.second.none { !it.permanentlyClosed && nameAgrees(name, it.name, it.address) && it.location.distanceTo(location) <= 150.0 }
            ) hideClosedOpenPlace(seed.id)
            if (full != null && isPlaceholder(_state.value.selected, placeholder)) {
                // One update: the listing, the end of loading and the sheet identity together, so
                // the open sheet recomposes once, in place.
                _state.update {
                    it.copy(
                        selected = withListNote(full), placesHere = othersAt(full, resolved.second),
                        tapResolvingFor = null, sheetAlias = full.id to placeholder.id,
                    )
                }
                fetchReviews(full)
                fetchStopDepartures(full) // issue #71: a bus stop / station tapped on the MAP gets its board too
                // Photos and popular times are two more Chromium page loads; a beat later, so they
                // do not land under the sheet's open animation together with the reviews scrape.
                launch {
                    kotlinx.coroutines.delay(app.vela.core.util.Jitter.around(700))
                    if (_state.value.selected?.id != full.id) return@launch
                    fetchPhotos(full)
                    fetchPlaceDetails(full) // popular times + editorial/owner, like a search-result tap
                }
                rememberRecentPlace(SavedPlace.of(full))
            } else if (transitHint != null && isPlaceholder(_state.value.selected, placeholder)) {
                // Issue #71 (Jerusalem): a tapped stop with NO resolvable Google stop listing used to
                // dead-end as a name-only sheet - no category, no board, nothing to swipe to. The TAP
                // ITSELF says this is a transit stop (the basemap class, language-independent), and
                // Transitous needs only the coordinate - so fetch the board by proximity regardless
                // of what Google resolution did. The Google-page fallback is impossible here anyway
                // (no feature id), so this is Transitous-or-nothing, which is correct.
                _state.update { if (isPlaceholder(it.selected, placeholder)) it.copy(stopDeparturesLoading = true, stopDeparturesFor = placeholder.id) else it }
                val board = withContext(Dispatchers.IO) {
                    runCatching {
                        app.vela.core.data.transit.Transitous.board(http, location.lat, location.lng)
                    }.getOrNull()
                }
                android.util.Log.i("VelaDepartures", "hinted-tap fallback lines=${board?.lines?.size ?: -1}")
                _state.update {
                    if (isPlaceholder(it.selected, placeholder)) {
                        it.copy(
                            stopDepartures = board?.takeIf { b -> b.lines.isNotEmpty() },
                            stopDeparturesLoading = false,
                            stopDeparturesFor = placeholder.id,
                        )
                    } else it
                }
                if (board != null && board.lines.isNotEmpty()) {
                    startBoardRefresh(placeholder.id, location.lat, location.lng)
                }
            }
          } finally {
            stopResolving() // nothing resolved, an error, or a cancel: the label's own data shows
            // Still the tapped label after a real lookup: Google had nothing that matched.
            if (willResolve) _state.update {
                if (isPlaceholder(it.selected, placeholder)) it.copy(tapUnlinkedFor = placeholder.id) else it
            }
          }
        }
    }

    /** Whether [cur] is still the tapped [placeholder]: the same id at the same point. Not full
     *  equality, because the sheet's placeholder is filled in from the offline packs while the
     *  lookup runs, and the filled-in copy is still the tap's own place. */
    private fun isPlaceholder(cur: Place?, placeholder: Place): Boolean =
        cur != null && cur.id == placeholder.id && cur.location == placeholder.location

    /** The downloaded place packs' row for a tapped label: within 80 m and agreeing by name. */
    private suspend fun offlineTwin(name: String, at: LatLng): Place? = withContext(Dispatchers.IO) {
        runCatching { offlinePoiStore.near(at, 80.0, limit = 12) }.getOrDefault(emptyList())
            .firstOrNull { app.vela.core.util.PlaceNames.same(name, it.name) || nameAgrees(name, it.name, it.address) }
    }

    /** How long a tapped place's sheet may show its loading skeleton before the label's own data
     *  shows anyway. A healthy lookup lands in well under 2 s. */
    private val TAP_RESOLVE_WATCHDOG_MS = 6_000L

    /** Does a Google [listing] name agree with the [tapped] basemap label? Word-set overlap on
     *  normalized tokens, needing the SHORTER name's words (capped at 2) to appear in the other:
     *  "Wild Wasabi Signature" agrees with "Wild Wasabi" (2 shared) but not "Belletreats Dessert"
     *  (0); "SpeeDee Midas" agrees with both the "SpeeDee" and "Midas" listings (a co-brand's
     *  duplicate profiles both stay in the pick pool). Single-character tokens are dropped so
     *  "&"/initials can't fake agreement. */
    /** How near a listing that does NOT agree with the tapped name may be and still become the
     *  place: the same lot, not the far side of the junction. */
    private val NO_NAME_MATCH_M = 60.0

    /** How far a tapped business's listing may be from the tapped point (issue #429). */
    private val BUSINESS_TAP_CAP_M = 1_500.0

    /** How far the "a listing named exactly what I tapped wins" rule may reach. It separates a
     *  store from its own forecourt, which share a lot, so it must not be able to prefer a branch
     *  across town over the business under the finger. Wider than [NO_NAME_MATCH_M] because a big
     *  store and its pumps can sit that far apart. */
    private val SAME_LOT_M = 120.0

    /**
     * The tap resolve's kind pass, for a tapped place whose NAME is the site's rather than the
     * listing's: the open-data row for a fuel station called "<Station> <Pizza counter>" while
     * Google lists the pumps as "<Brand> <Town>". The name search then finds only the other
     * business in the same building, which the kind rule refuses, and the tap linked to nothing.
     *
     * The name still says WHERE: the nearest listing that agrees by name, on the same lot as the
     * tap, is the anchor (the tap's own point when there is none). A search for the tapped kind
     * ([kindText]: the tile's category, "Gas station", or the basemap class) around it returns the
     * candidates, and the nearest one of the same icon group within [NO_NAME_MATCH_M] of the
     * anchor is kept. One extra request, only on a tap that would otherwise not link.
     */
    private suspend fun kindBesideAnchor(name: String, location: LatLng, kindText: String?, group: String?, answerable: List<Place>): List<Place> {
        val q = kindText?.trim()?.takeIf { it.length >= 3 } ?: return emptyList()
        val anchor = answerable
            .filter { nameAgrees(name, it.name, it.address) && it.location.distanceTo(location) <= SAME_LOT_M }
            .minByOrNull { it.location.distanceTo(location) }
        val center = anchor?.location ?: location
        val hits = runCatching { dataSource.searchOnce(q, center) }.getOrDefault(emptyList())
        return hits
            .filter { p ->
                !p.permanentlyClosed && p.location.distanceTo(center) <= NO_NAME_MATCH_M &&
                    PoiIcons.groupFor(p.name, p.category) == group &&
                    p.category?.let { isTransitCategory(it) || it.lowercase() in JUNCTION_CATEGORIES } != true
            }
            .sortedBy { it.location.distanceTo(center) }
            .take(1)
    }

    /** Google categories that are map FURNITURE, never the answer to tapping a business. */
    private val JUNCTION_CATEGORIES = setOf("intersection", "junction", "crossroads", "road", "highway")

    /** The shared same-business rule (`core/util/PlaceNames`, 2026-09-21): descriptor tails, spelling
     *  variants and agreeing identifying words count, shared generic words do not. The town out of
     *  the listing's address is generic for the comparison, so "FIT House Davis" is "FIT House". */
    /**
     * The tap resolve's second pass when nothing agreed by name and the tapped label is written in
     * a script Google answers differently under the app's language: a Japanese label against an
     * English-localized reply reads "東京ミッドタウン" against "Tokyo Midtown", and no name rule
     * bridges that (Tokyo, 2026-09-22: 19% of Google's places linked to the archive under hl=en,
     * 32% under hl=ja, and the tap has only the label). The same search runs in the script's own
     * language ([NameScript.scriptLanguage]), the listings that agree are kept, and the nearest
     * one's English-localized copy is fetched by its own name so the sheet keeps the app language's
     * category and hours; the copy replaces it when the feature ids match, else the foreign
     * listing stands. Two requests at most, only on a cross-script miss.
     */
    private suspend fun crossScriptCandidates(name: String, location: LatLng, query: String, tappedKind: String?, localGeneric: Set<String>, uiLang: String): List<Place> {
        val hl = app.vela.core.util.NameScript.scriptLanguage(name, location.lat, location.lng) ?: return emptyList()
        if (app.vela.core.util.NameScript.sameLanguage(hl, uiLang)) return emptyList()
        val foreign = runCatching { dataSource.searchOnce(query, location, lang = hl) }.getOrDefault(emptyList())
        val agreeing = foreign.filter { p ->
            p.category?.let { isTransitCategory(it) || it.lowercase() in JUNCTION_CATEGORIES } != true &&
                app.vela.core.util.PlaceNames.sameBusiness(
                    name, tappedKind, p.name, PoiIcons.groupFor(p.name, p.category),
                    app.vela.core.util.PlaceNames.cityWords(p.address) + localGeneric,
                )
        }
        if (agreeing.isEmpty()) return emptyList()
        val best = agreeing.minByOrNull { it.location.distanceTo(location) }!!
        val localized = runCatching { dataSource.searchOnce(best.name, best.location) }.getOrDefault(emptyList())
        return agreeing.map { f -> localized.firstOrNull { it.featureId != null && it.featureId == f.featureId } ?: f }
    }

    private fun nameAgrees(tapped: String, listing: String?, address: String? = null): Boolean =
        app.vela.core.util.PlaceNames.agree(tapped, listing, app.vela.core.util.PlaceNames.cityWords(address))

    /** Other Google listings essentially at the same spot as [place] (within ~40 m) —
     *  e.g. a co-branded shop's duplicate profile, or a different unit at the address.
     *  Drawn from search results we already have, so it's free; empty for a place
     *  with nothing co-located. Powers the "Also here" section of the place sheet. */
    /** The street line of an address ("239 G St" out of "239 G St, Davis, CA 95616"),
     *  normalized and with any suite/unit/floor dropped, so two listings in the same
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
        // Dead during a live drive, same as onPoiTap: building/unnamed-POI taps route here too,
        // and an invisible dropped pin surfacing after the drive read as a ghost selection.
        if (_state.value.navigating) return
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
                MapPick.DEST -> setDirectionsDestination(place)
                }
            }
            return
        }
        // While planning a trip, a long-press means "route THROUGH here": grab an arbitrary point and
        // add it as a via-stop (then the route reroutes through it). This is the manual way to steer a
        // route around an area/camera - Google's keyless directions and OSRM can't be told "avoid this
        // region", but a hand-placed waypoint forces the detour. The point itself is what matters, so
        // the stop sits at the exact spot pressed; the reverse-geocode only names it.
        if (_state.value.directionsOpen && !_state.value.pickingOrigin && !_state.value.pickingDest && !_state.value.pickingStop) {
            // Only when the chooser is MINIMIZED to its Start bar and the steps viewer is closed
            // (user 2026-07-15). Plain building/unnamed-POI taps funnel into this handler too, so
            // with the full picker (or the step list) covering the map, a stray tap on the visible
            // strip of map silently added a stop and rerouted the trip. Minimized, the map is the
            // primary surface and routing through a pressed point is plausibly deliberate. A
            // suppressed press does nothing at all: dropping a pin instead would load a place
            // sheet invisibly under the chooser (the ghost-selection class of bug).
            if (_state.value.showSteps || !directionsMinimized) return
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
                // Frame the PIN, not the last named place. Every other selection path sets center
                // to the picked location; this one didn't, so the sheet-lift camera re-framed on
                // the stale center (the previously selected store/home) and the map jumped THERE
                // when you dropped a pin (dpad-vela user report 2026-07-16, reproduced upstream).
                center = location, centerZoom = null,
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
                pickingOrigin = false, pickingDest = false,
                pickingStop = false,
                // A pin dropped right after viewing a transit stop kept that stop's departure
                // board (and its loading spinner) on the pin sheet - the board fields were the
                // one pair this reset missed (device 2026-07-13).
                stopDepartures = null,
                stopDeparturesLoading = false,
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
     *  reverse-geocode can snap to a neighbor (tapped 1020, got 1040), which is exactly the "doesn't
     *  snap to the house number" complaint. A real business sitting on the point still wins. */
    fun onAddressLabelTap(number: String, location: LatLng, tileStreet: String? = null) {
        if (_state.value.navigating) return // dead during a live drive, like onPoiTap
        if (_state.value.pickOnMap != null) { onMapLongPress(location); return } // pick-mode reuses the endpoint flow
        // While planning a trip, a tapped house number behaves like any other pressed point:
        // route-through-here when the chooser is minimized, suppressed otherwise (the gate lives
        // in onMapLongPress). Selecting the address here instead set `selected` under the open
        // chooser - an invisible sheet that popped up when the chooser closed.
        if (_state.value.directionsOpen && !_state.value.pickingOrigin && !_state.value.pickingDest && !_state.value.pickingStop) {
            onMapLongPress(location); return
        }
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
                pickingOrigin = false, pickingDest = false,
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
                        // Street veto (issue #231): the tapped number is authoritative, but the
                        // STREET came from the reverse geocode, which snaps to the nearest
                        // addressable point - around a corner that is routinely a different road,
                        // composing "right number, wrong street". When the map's own road under
                        // the label disagrees (normalized so Ave/Avenue line up), the tile street
                        // wins and the geocode keeps only the locality tail.
                        val street = rest.substringBefore(',').trim()
                        val tail = rest.substringAfter(',', "").let { t -> if (t.isBlank()) "" else ",$t" }
                        // Round two (issue #231, 2026-09-14): the veto only applies when the geocode
                        // snapped to a DIFFERENT number. When Nominatim answers with the tapped
                        // number itself, its road is that address node's own street and wins; the
                        // veto had been moving a side-street house onto the bigger road drawn
                        // beside it.
                        val geoNumber = base.trim().takeWhile { !it.isWhitespace() }
                        val sameNumber = geoNumber.equals(number.trim(), ignoreCase = true)
                        val fixed = if (
                            !sameNumber &&
                            !tileStreet.isNullOrBlank() &&
                            app.vela.core.data.OfflineAddressStore.normalizeStreet(street) !=
                            app.vela.core.data.OfflineAddressStore.normalizeStreet(tileStreet)
                        ) "$tileStreet$tail" else rest
                        val addr = "$number $fixed"
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

    /**
     * Set when Directions was entered via the place sheet's START pill (issue #272): the moment a
     * route lands, begin guidance instead of parking on the picker. Consumed once - a later route
     * refetch (a mode change, an added stop) must not re-launch nav behind the user.
     */
    @Volatile var autoStartOnRoute = false
        private set

    fun consumeAutoStart(): Boolean {
        if (!autoStartOnRoute) return false
        autoStartOnRoute = false
        return true
    }

    /** Directions for the selected place, launching guidance as soon as a route exists. */
    fun startNavToSelected() {
        autoStartOnRoute = true
        routeToSelected()
    }

    fun routeToSelected() {
        val sel = _state.value.selected ?: return
        // The chooser opens seconds before Start: load the neural voice now (it no longer loads at
        // launch), so the "Starting navigation" opener is not waiting on it.
        voice.neural?.warmUp()
        // Start each directions session clean — don't inherit a custom origin, stops, or
        // pick-mode left over from a previous place's directions.
        _state.update { it.copy(directionsOpen = true, directionsReversed = false, directionsOrigin = null, pickingOrigin = false, pickingDest = false, directionsWaypoints = emptyList(), pickingStop = false) }
        // Walking back to the car is the parking spot's whole point — default to WALK there.
        // Otherwise every session opens on the STICKY last-used mode (user 2026-07-11).
        val (avTolls, avHighways, avFerries) = stickyAvoid()
        _state.update { it.copy(avoidTolls = avTolls, avoidHighways = avHighways, avoidFerries = avFerries) }
        syncRoutingAvoid()
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

    /** Custom list order (issue #343): nudge a list up or down; the store's order is the display order. */
    fun moveList(listId: String, delta: Int) {
        _state.update { it.copy(lists = listStore.move(listId, delta)) }
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
                stopDepartures = null, stopDeparturesLoading = false, stopDeparturesFor = null,
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
    // Every pick starts CLEAN (issue #405, 2026-09-13): the destination search's results were
    // still in state, so the picker's first keystroke flipped the overlay off the entry page,
    // the field lost focus after one character, and a stale list sat under the picker.
    fun beginPickOrigin() = _state.update { it.copy(pickingOrigin = true, pickingDest = false, query = "", suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(), results = emptyList(), resultsCollapsed = false) }

    fun cancelPickOrigin() = _state.update { it.copy(pickingOrigin = false, pickingDest = false) }

    /** Tapped the directions DESTINATION row → the next search pick replaces the destination,
     *  keeping the origin, stops and travel mode (issue #170 — Google lets you edit both ends;
     *  backing out and retyping lost the custom origin). [setDirectionsDestination] or
     *  [cancelPickDestination] ends the mode. */
    fun beginPickDestination() = _state.update {
        it.copy(pickingDest = true, pickingOrigin = false, pickingStop = false, query = "", suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(), results = emptyList(), resultsCollapsed = false)
    }

    fun cancelPickDestination() = _state.update { it.copy(pickingDest = false) }

    /** Swap the destination for [p] and re-route: the chooser stays open, origin + stops stay.
     *  The per-place content (reviews/photos/boards) resets like a fresh selection so closing
     *  the chooser later shows [p]'s own sheet, not the old destination's leftovers. */
    fun setDirectionsDestination(p: Place) {
        routeJob?.cancel()
        _state.update {
            it.copy(
                selected = withListNote(p), pickingDest = false, pickOnMap = null,
                directionsOpen = true, results = emptyList(), query = "", suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(),
                reviews = emptyList(), reviewsLoading = false, reviewsFound = 0, photosLoading = false,
                loadingDetails = false, placesHere = emptyList(),
                stopDepartures = null, stopDeparturesLoading = false, stopDeparturesFor = null,
            )
        }
        route(_state.value.travelMode)
    }

    fun chooseDestOnMap() = _state.update { it.copy(pickingDest = false, pickOnMap = MapPick.DEST) }

    /** Set a custom directions origin (a place other than your live location) and
     *  re-route. Clears with [clearRoute]. */
    fun setDirectionsOrigin(p: Place) {
        _state.update { it.copy(directionsOrigin = p, pickingOrigin = false, pickingDest = false, pickOnMap = null) }
        route(_state.value.travelMode)
    }

    /** "Choose on map" for an endpoint — leave the search overlay, show a center crosshair over the
     *  live map, and set that endpoint from wherever the map is centered (or a long-press) on confirm. */
    fun chooseOriginOnMap() = _state.update { it.copy(pickingOrigin = false, pickingDest = false, pickOnMap = MapPick.ORIGIN) }
    fun chooseStopOnMap() = _state.update { it.copy(pickingStop = false, pickOnMap = MapPick.STOP) }
    fun cancelChooseOnMap() = _state.update { it.copy(pickOnMap = null) }

    /** Confirm the crosshair pick: reverse-geocode the map's current center and set it as the
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
                MapPick.DEST -> setDirectionsDestination(place)
            }
        }
    }

    /** Drop a custom origin → route from your live location again. Also exits
     *  pick-mode (it's offered as the top row of the origin picker). */
    fun useMyLocationAsOrigin() {
        _state.update { it.copy(directionsOrigin = null, pickingOrigin = false, pickingDest = false) }
        route(_state.value.travelMode)
    }

    /** Tapped "Add stop" → the next search pick becomes an intermediate stop (multi-stop routing).
     *  [addStop]/[cancelPickStop] ends the mode. */
    fun beginPickStop() = _state.update { it.copy(pickingStop = true, pickingDest = false, editingStops = false, query = "", suggestions = emptyList(), querySuggestions = emptyList(), localSuggestions = emptyList(), results = emptyList(), resultsCollapsed = false) }

    /** The dedicated stops editor (reorder / remove / add in one sheet, one reroute on Done).
     *  During nav (issue #402) it opens over the ETA bar; the step sheet closes first so Done
     *  lands back on the bar, not on a list you were not reading. */
    fun openStopsEditor() = _state.update {
        if (it.navigating) it.copy(editingStops = true, showSteps = false, previewStepIndex = null)
        else it.copy(editingStops = true)
    }

    fun closeStopsEditor() = _state.update { it.copy(editingStops = false) }

    /** The stops still ahead on the drive, as the editor's rows: the chooser's Place where the
     *  session's stop came from one (same coordinates), else a bare Place carrying the label. */
    fun navStopsForEditor(): List<Place> = nav.navStopsForEditor()
    fun navRemainingStopLabels(): List<String> = nav.navRemainingStopLabels()
    fun navRemainingStops(): List<app.vela.core.nav.NavSession.NavStop> = nav.navRemainingStops()


    /** Apply the editor's final ordering in ONE shot — a single reroute per visit, not one per
     *  micro-edit like the old inline arrows. Mid-drive (issue #402) the session replans through
     *  the new list from where you are; the chooser's list becomes the remaining stops, so
     *  ending nav back into the panel shows the trip as it stands. */
    fun applyStops(stops: List<Place>) {
        if (_state.value.navigating) {
            val loc = _state.value.myLocation
            val remaining = navSession.remainingStops()
            val next = stops.map { app.vela.core.nav.NavSession.NavStop(it.location, it.name) }
            _state.update { it.copy(directionsWaypoints = stops, editingStops = false) }
            if (loc != null && next != remaining) navSession.setStops(next, loc, "stops edited mid-nav → ${next.size} ahead")
            return
        }
        val changed = stops != _state.value.directionsWaypoints
        _state.update { it.copy(directionsWaypoints = stops, editingStops = false) }
        if (changed) route(_state.value.travelMode)
    }

    /** The planning trip as the full-trip editor's rows (issue #516): start, stops, destination in
     *  travel order, with null meaning "your location". */
    fun tripPointsForEditor(): List<app.vela.ui.place.TripPoint> {
        val s = _state.value
        val place = s.selected
        val start = if (s.directionsReversed) place else s.directionsOrigin
        val end = if (s.directionsReversed) s.directionsOrigin else place
        return listOf(app.vela.ui.place.TripPoint(start)) +
            s.directionsWaypoints.map { app.vela.ui.place.TripPoint(it) } +
            app.vela.ui.place.TripPoint(end)
    }

    /** Apply a full-trip edit (issue #516): the first point becomes the start and the last the
     *  destination, whatever they were before; "your location" in the middle becomes a stop at the
     *  current fix. Maps back onto the chooser's model (selected / directionsOrigin / reversed, with
     *  stops always in travel order) and reroutes once. */
    fun applyTrip(points: List<app.vela.ui.place.TripPoint>) {
        if (points.size < 2) return
        val s = _state.value
        val start = points.first().place
        val end = points.last().place
        if (start == null && end == null) return // both ends "you": nothing to route
        val me = s.myLocation
        val mids = points.subList(1, points.size - 1).mapNotNull { p ->
            p.place ?: me?.let { Place(id = "me", name = appContext.getString(R.string.mapscreen_your_location), location = it) }
        }
        _state.update {
            if (end == null) {
                it.copy(selected = start, directionsOrigin = null, directionsReversed = true, directionsWaypoints = mids, editingStops = false)
            } else {
                it.copy(selected = end, directionsOrigin = start, directionsReversed = false, directionsWaypoints = mids, editingStops = false)
            }
        }
        route(_state.value.travelMode)
    }

    fun cancelPickStop() = _state.update { it.copy(pickingStop = false) }

    fun addStopDuringNav(p: Place) = nav.addStopDuringNav(p)

    /** Offer the tapped place as a stop and start pricing the detour. The card shows at once;
     *  the minutes land when the check comes back, so a slow answer never delays the offer. */
    private fun offerNavTapStop(p: Place) {
        _state.update { it.copy(navTapCandidate = p, navTapDetourMin = null, navTapOfferTick = it.navTapOfferTick + 1) }
        priceNavTapDetour(p)
    }

    /** One route through the candidate, compared with the drive's own live remaining time. The
     *  fetch is bounded and nothing about the drive is touched by it: the session keeps routing on
     *  what it already has, and a failed or slow check simply leaves the card without a figure. */
    private fun priceNavTapDetour(p: Place) {
        navTapDetourJob?.cancel()
        val s = _state.value
        val loc = s.myLocation ?: return
        // The same fallback the session uses: a trip started from a deep link or a restored drive
        // can be routing without the view model's own destination field set.
        val dest = destination ?: s.activeRoute?.polyline?.lastOrNull() ?: return
        val baseline = s.nav.remainingDuration
        if (baseline <= 0.0) return
        navTapDetourJob = viewModelScope.launch {
            // The candidate goes FIRST, which is where NavSession.addStop puts it: the figure has to
            // price the drive the button would actually build.
            val stops = listOf(p.location) + nav.navRemainingStops().map { it.location }
            val route = withTimeoutOrNull(NAV_DETOUR_TIMEOUT_MS) {
                runCatching {
                    dataSource.directions(
                        loc, dest, s.travelMode, stops,
                        s.avoidTolls, s.avoidHighways, s.avoidFerries,
                    )
                }.getOrNull()?.firstOrNull()
            }
            val via = route?.let { it.durationInTrafficSeconds ?: it.durationSeconds }
            val minutes = via?.let { app.vela.core.nav.DetourEstimate.minutesAdded(baseline, it) }
            // Visible on a device when the card shows no figure: whether the check answered at all,
            // and whether the rule dropped the answer. No place name, no coordinates.
            android.util.Log.d(
                "VelaStopOffer",
                "detour check: baseline=${baseline.toInt()}s via=${via?.toInt() ?: -1}s -> ${minutes?.let { "+$it min" } ?: "nothing"}",
            )
            // A newer tap (or a dismissal) owns the card by now; this answer is stale.
            if (_state.value.navTapCandidate?.id != p.id) return@launch
            _state.update { it.copy(navTapDetourMin = minutes) }
        }
    }

    /** The tapped place becomes the next stop (the confirm on the in-drive card). */
    fun confirmNavTapStop() {
        val p = _state.value.navTapCandidate ?: return
        clearNavTapStop()
        addStopDuringNav(p)
    }

    fun dismissNavTapStop() {
        if (_state.value.navTapCandidate != null) clearNavTapStop()
    }

    private fun clearNavTapStop() {
        navTapDetourJob?.cancel()
        navTapDetourJob = null
        _state.update { it.copy(navTapCandidate = null, navTapDetourMin = null) }
    }

    /** Append an intermediate stop and re-route through it. */
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

    /** Pick one of the alternate routes (drawn grayed on the map / listed in the
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
        return runCatching { dataSource.nameRoute(route, o, d, _state.value.travelMode, _state.value.avoidTolls, _state.value.avoidHighways, _state.value.avoidFerries) }
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

    /** Mirror the chooser's avoid toggles into [app.vela.core.data.RoutingPrefs] so the nav
     *  session's own fetches (reroutes, rechecks) honor them too. */
    private fun syncRoutingAvoid() {
        val st = _state.value
        app.vela.core.data.RoutingPrefs.avoidTolls = st.avoidTolls
        app.vela.core.data.RoutingPrefs.avoidHighways = st.avoidHighways
        app.vela.core.data.RoutingPrefs.avoidFerries = st.avoidFerries
    }

    fun setAvoidTolls(on: Boolean) {
        if (_state.value.avoidTolls == on) return
        appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("avoid_tolls", on).apply()
        _state.update { it.copy(avoidTolls = on) }
        syncRoutingAvoid()
        route(_state.value.travelMode) // re-route with the new preference
    }

    fun setAvoidHighways(on: Boolean) {
        if (_state.value.avoidHighways == on) return
        appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("avoid_highways", on).apply()
        _state.update { it.copy(avoidHighways = on) }
        syncRoutingAvoid()
        route(_state.value.travelMode)
    }

    fun setAvoidFerries(on: Boolean) {
        if (_state.value.avoidFerries == on) return
        appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("avoid_ferries", on).apply()
        _state.update { it.copy(avoidFerries = on) }
        syncRoutingAvoid()
        route(_state.value.travelMode)
    }

    /** The persisted avoid toggles (sticky like the travel mode - the habit is the setting):
     *  tolls, highways, ferries. */
    private fun stickyAvoid(): Triple<Boolean, Boolean, Boolean> = runCatching {
        val p = appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
        Triple(p.getBoolean("avoid_tolls", false), p.getBoolean("avoid_highways", false), p.getBoolean("avoid_ferries", false))
    }.getOrDefault(Triple(false, false, false))

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
        // Stops are ALWAYS stored in travel order (swapDirections physically reverses the list), so no
        // per-call reversal here — display, reorder arrows and routing all agree on one order.
        val stops = s.directionsWaypoints.map { it.location }
        val etaKey = modeEtaKeyOf(origin, dest, stops, s.avoidTolls, s.avoidHighways, s.avoidFerries, s.directionsTimeMode, s.directionsTimeEpochSec)
        beginModeEtas(etaKey)
        if (mode == TravelMode.TRANSIT) { routeTransit(origin, dest, s.directionsTimeMode, s.directionsTimeEpochSec, etaKey); return }
        // Guard: this reply is only applied if directions is still open for the SAME mode (the user hasn't
        // backed out or switched away while it was fetching). Mirrors routeTransit's stale-load guard.
        fun stillWanted() = _state.value.directionsOpen && _state.value.travelMode == mode
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            try {
                val routes = dataSource.directions(origin, dest, mode, stops, s.avoidTolls, s.avoidHighways, s.avoidFerries)
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
                // A fetch that found nothing must not leave the Start-pill auto-start armed for
                // the next, unrelated Directions request.
                if (routes.isEmpty()) autoStartOnRoute = false
                shownDuration(routes)?.let { publishModeEta(etaKey, mode, formatDuration(it)) }
                prefetchModeEtas(etaKey, origin, dest, stops, s.avoidTolls, s.avoidHighways, s.avoidFerries, s.directionsTimeMode, s.directionsTimeEpochSec, except = mode)
                val flockEpoch = ++routesEpoch // stamp THIS route set; a newer route() bumps it and stales the flock job
                if (routes.isNotEmpty()) refreshFlockOnRoute(routes, flockEpoch, origin, dest, mode, stops, s.avoidTolls, s.avoidHighways, s.avoidFerries)
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

    /** When "Avoid surveillance cameras" is on, count the ALPR cameras near each route option (the bundled
     *  FlockCameras set; keyless Overpass only until it loads; index-aligned with [routes]) so the picker can badge "passes N cameras" AND auto-prefer
     *  the fewest-camera alternate - but only for a MODEST detour (never send you an hour around a camera
     *  on a 15-minute trip). Off the hot path; a failure just shows no badge and no reroute. */
    private fun refreshFlockOnRoute(
        routes: List<Route>,
        epoch: Int,
        origin: LatLng,
        dest: LatLng,
        mode: TravelMode,
        stops: List<LatLng>,
        avoidTolls: Boolean,
        avoidHighways: Boolean,
        avoidFerries: Boolean,
    ) {
        flockRouteJob?.cancel()
        if (!app.vela.ui.FlockRouteAlert.on.value) return
        flockRouteJob = viewModelScope.launch {
            val counts = withContext(Dispatchers.Default) {
                val local = app.vela.data.FlockCameras.isLoaded
                routes.map { r ->
                    // Bundled dataset: instant + reliable, so the auto-avoid re-rank always has real counts to
                    // work with (the live Overpass fan-out per tile was slow and often returned 0 = nothing to
                    // avoid). Fall back to Overpass only until the bundled set finishes loading.
                    // Both paths are direction-aware (nav/CameraFacing): a camera aimed across
                    // the road is not counted against a route it cannot read.
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
            val eta = { r: Route -> r.durationInTrafficSeconds ?: r.durationSeconds }
            val eta0 = eta(cur[0]) // routes are sorted fastest-first, and cur[0] is the default active
            val cap = minOf(eta0 * 0.25, 600.0)
            if (counts.any { it > 0 }) {
                val best = counts.indices.minByOrNull { counts[it] * 1_000_000L + eta(cur[it]).toLong() } ?: 0
                val extra = eta(cur[best]) - eta0
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
            // SIDE STREETS (issue #600, opt-in): the leading route still passes cameras, so try the
            // reporter's own workaround for them - a point a little way to either side of the road
            // at each camera cluster, routed through like a stop. Google routes and prices every
            // candidate through its stops with traffic (the 2026-09-21 waypoint work), so the
            // compare against the same cap as the re-rank is an honest one.
            if (app.vela.ui.FlockDetour.on.value && mode == TravelMode.DRIVE) {
                val lead = _state.value.routes.firstOrNull()
                val leadCount = _state.value.flockOnRoute.firstOrNull() ?: 0
                if (lead != null && leadCount > 0 && lead.detourPlan.isEmpty()) {
                    tryCameraDetour(lead, leadCount, eta0, cap, epoch, origin, dest, mode, stops, avoidTolls, avoidHighways, avoidFerries)
                }
            }
        }
    }

    /** One greedy pass over the camera clusters of [lead]: for each, its left then right point is
     *  added to the trip and the trip re-routed; a candidate that passes fewer cameras inside
     *  [cap] is kept and the next cluster builds on it. The result, if any, leads the list with its
     *  badge and carries its waypoint plan ([Route.detourPlan]) so a drive keeps the detour. */
    private suspend fun tryCameraDetour(
        lead: Route, leadCount: Int, eta0: Double, cap: Double, epoch: Int,
        origin: LatLng, dest: LatLng, mode: TravelMode, stops: List<LatLng>,
        avoidTolls: Boolean, avoidHighways: Boolean, avoidFerries: Boolean,
    ) {
        val eta = { r: Route -> r.durationInTrafficSeconds ?: r.durationSeconds }
        val poly = lead.polyline
        val cum = app.vela.core.nav.RouteProjection.cumulative(poly)
        val cands = withContext(Dispatchers.Default) {
            val along = app.vela.data.FlockCameras.along(poly).mapNotNull { app.vela.core.nav.RouteProjection.alongMeters(poly, cum, it.loc, 45.0) }
            app.vela.core.nav.CameraDetour.candidates(poly, along)
        }
        if (cands.isEmpty()) return
        val stopAt = stops.map { app.vela.core.nav.RouteProjection.alongMeters(poly, cum, it, 250.0) to it }
        var vias = emptyList<Pair<Double, LatLng>>()
        var best: Route? = null
        var bestCount = leadCount
        var requests = 0
        outer@ for (c in cands) {
            for (via in listOf(c.left, c.right)) {
                if (requests >= app.vela.core.nav.CameraDetour.MAX_REQUESTS) break@outer
                val trial = vias + (c.atM to via)
                val plan = app.vela.core.nav.CameraDetour.mergePlan(stopAt, trial)
                requests++
                val r = runCatching { dataSource.directions(origin, dest, mode, plan, avoidTolls, avoidHighways, avoidFerries) }
                    .getOrDefault(emptyList()).firstOrNull() ?: continue
                if (routesEpoch != epoch) return
                val n = withContext(Dispatchers.Default) { app.vela.data.FlockCameras.along(r.polyline).size }
                val extra = eta(r) - eta0
                if (n < bestCount && extra <= cap) {
                    vias = trial
                    best = r.copy(detourPlan = plan)
                    bestCount = n
                    break
                }
            }
        }
        android.util.Log.i("VelaFlockRoute", "detour: clusters=${cands.size} requests=$requests kept=${best != null} cameras $leadCount -> $bestCount")
        val kept = best ?: return
        if (routesEpoch != epoch) return
        _state.update { it.copy(routes = listOf(kept) + it.routes, flockOnRoute = listOf(bestCount) + it.flockOnRoute) }
        selectRoute(0)
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
    /** A drill-down row expanded/collapsed in the transit chooser — the map draws the expanded
     *  itinerary's legs (issue #233). Collapsing only clears the preview if that row still owns
     *  it, so expanding B then collapsing A can't blank B's drawing. */
    fun onTransitRowExpanded(itin: TransitItinerary, expanded: Boolean) = _state.update {
        when {
            expanded -> it.copy(transitPreview = itin)
            it.transitPreview === itin -> it.copy(transitPreview = null)
            else -> it
        }
    }

    /** Prefer bus / subway / train / tram on transit trips (issue #431): the pick rides along on
     *  Google's request, so a bus-only rider gets the slower all-bus itinerary instead of the train. */
    fun setTransitPrefer(modes: Set<Int>) {
        if (_state.value.transitPrefer == modes) return
        _state.update { it.copy(transitPrefer = modes) }
        if (_state.value.travelMode == TravelMode.TRANSIT) route(TravelMode.TRANSIT)
    }

    private fun routeTransit(origin: LatLng, dest: LatLng, timeMode: Int = 0, timeEpochSec: Long? = null, etaKey: String? = null) {
        _state.update { it.copy(routes = emptyList(), activeRoute = null, transit = emptyList(), transitLoading = true, transitPreview = null, status = null) }
        val prefer = _state.value.transitPrefer
        viewModelScope.launch {
            val trips = runCatching { webDirections.transit(origin, dest, timeMode, timeEpochSec, prefer) }.getOrDefault(emptyList())
            _state.update {
                if (it.travelMode != TravelMode.TRANSIT) it // user switched away mid-load
                else it.copy(
                    transit = trips,
                    transitLoading = false,
                    status = if (trips.isEmpty()) appContext.getString(R.string.mapvm_no_transit_routes) else null,
                )
            }
            if (etaKey != null) {
                trips.firstOrNull()?.durationText?.let { publishModeEta(etaKey, TravelMode.TRANSIT, transitChipText(it)) }
                val s = _state.value
                prefetchModeEtas(etaKey, origin, dest, s.directionsWaypoints.map { it.location }, s.avoidTolls, s.avoidHighways, s.avoidFerries, timeMode, timeEpochSec, except = TravelMode.TRANSIT)
            }
        }
    }

    // ---- Per-mode ETAs for the mode chips ------------------------------------------------------
    // Google's chips carry the time and the glyph carries the mode; ours read the same way. The
    // current mode's time is its own route set (the picker's "Fastest" figure); the other three
    // are fetched in the background, one after another, through the SAME directions()/transit()
    // calls the picker makes when that chip is tapped, so a chip never shows a number the list
    // then contradicts (an OSRM free-flow guess reads minutes under the traffic-aware time on a
    // signaled arterial, see the #227 calibration). Cached per trip in 5-minute buckets so
    // flipping between modes refetches nothing.

    private fun modeEtaKeyOf(origin: LatLng, dest: LatLng, stops: List<LatLng>, avoidTolls: Boolean, avoidHighways: Boolean, avoidFerries: Boolean, timeMode: Int, timeEpochSec: Long?): String {
        val pts = (listOf(origin) + stops + dest).joinToString(";") { "%.5f,%.5f".format(java.util.Locale.US, it.lat, it.lng) }
        return "$pts|$avoidTolls|$avoidHighways|$avoidFerries|$timeMode|$timeEpochSec|${System.currentTimeMillis() / 300_000L}"
    }

    /** Google's transit summary says "21 hr 6 min" where formatDuration says "21 h 6 min"; the chips
     *  sit side by side, so the English form is folded to ours. Other languages pass through. */
    private fun transitChipText(t: String): String = t.replace(Regex("(\\d+) hr\\b"), "$1 h")
    /** The picker's shown time for a route set: the fastest route's live ETA, free-flow when no traffic. */
    private fun shownDuration(routes: List<Route>): Double? =
        routes.minOfOrNull { it.durationInTrafficSeconds ?: it.durationSeconds }

    /** A new trip is being routed: publish whatever the cache already knows for it. */
    private fun beginModeEtas(key: String) {
        if (modeEtaCache.size > 16) modeEtaCache.clear()
        modeEtaKey = key
        _state.update { it.copy(modeEtas = modeEtaCache[key].orEmpty().toMap()) }
    }

    private fun publishModeEta(key: String, mode: TravelMode, eta: String) {
        modeEtaCache.getOrPut(key) { mutableMapOf() }[mode] = eta
        if (modeEtaKey == key) _state.update { it.copy(modeEtas = modeEtaCache[key].orEmpty().toMap()) }
    }

    private fun prefetchModeEtas(
        key: String, origin: LatLng, dest: LatLng, stops: List<LatLng>,
        avoidTolls: Boolean, avoidHighways: Boolean, avoidFerries: Boolean, timeMode: Int, timeEpochSec: Long?, except: TravelMode,
    ) {
        modeEtaJob?.cancel()
        val known = modeEtaCache[key].orEmpty()
        // Cheap OSRM modes first; transit last because it is a hidden-WebView page load.
        val missing = listOf(TravelMode.DRIVE, TravelMode.WALK, TravelMode.BICYCLE, TravelMode.TRANSIT)
            .filter { it != except && it !in known }
        if (missing.isEmpty()) return
        modeEtaJob = viewModelScope.launch {
            for (m in missing) {
                if (!_state.value.directionsOpen || modeEtaKey != key) return@launch
                if (_state.value.travelMode == m) continue // the user tapped it; route() is on it
                val eta = runCatching {
                    if (m == TravelMode.TRANSIT) webDirections.transit(origin, dest, timeMode, timeEpochSec).firstOrNull()?.durationText?.let(::transitChipText)
                    else shownDuration(dataSource.directions(origin, dest, m, stops, avoidTolls, avoidHighways, avoidFerries))?.let { formatDuration(it) }
                }.getOrNull() ?: continue
                publishModeEta(key, m, eta)
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
            // durationText is Google's own abbreviated string ("10 min"), which TTS read
            // literally as "min" (user 2026-08-08). English guidance expands the units to words;
            // other languages carry their own hl= abbreviations and pass through unchanged.
            val dur = (step.durationText ?: "").let {
                if (app.vela.core.i18n.NavStringsRegistry.current().locale.language == "en") {
                    app.vela.core.voice.SpeechText.spokenEnUnits(it)
                } else it
            }
            appContext.getString(R.string.transit_nav_walk, dur).trim()
        } else {
            val s = StringBuilder(appContext.getString(R.string.transit_nav_take, step.line?.name.orEmpty()))
            step.headsign?.let { s.append(" ").append(appContext.getString(R.string.transit_nav_towards, it)) }
            step.boardStop?.name?.let { s.append(" ").append(appContext.getString(R.string.transit_nav_from, it)) }
            step.alightStop?.name?.let { s.append(". ").append(appContext.getString(R.string.transit_nav_get_off, it)) }
            s.toString()
        }

    /** Start the drive. With a CUSTOM start that is not where you are (issue #463: "from this gas
     *  station to that city"), the planned line is one you are not standing on, and nav would begin
     *  by deciding you are off route and re-planning from your position a few seconds later - which
     *  reads as the app throwing your trip away for an older one. Re-plan from your position first,
     *  keeping the destination and the stops, and start on that. Near the chosen start (you really
     *  are there), nothing changes. */
    fun startNav() {
        val s = _state.value
        val start = if (s.directionsReversed) null else s.directionsOrigin
        val me = s.myLocation
        if (start != null && me != null && me.distanceTo(start.location) > START_FROM_ME_M) {
            autoStartOnRoute = true
            _state.update { it.copy(directionsOrigin = null) }
            showStatus(appContext.getString(R.string.mapvm_start_from_here))
            route(_state.value.travelMode)
            return
        }
        nav.startNav()
    }
    fun stopNav() = nav.stopNav()
    fun onNavPanned() = nav.onNavPanned()
    fun recenterNav() = nav.recenterNav()
    fun navOverview() = nav.navOverview()
    fun toggleNavNorthUp() = nav.toggleNavNorthUp()

    /** Reset the speed-limit badge + its throttle state (shared by nav-stop and replay-teardown so the
     *  next drive/replay starts clean - else a stale limit could flash near the last drive's end point). */
    private fun clearSpeedLimit() {
        limitJob?.cancel()
        lastLimitLoc = null
        lastLimitHitLoc = null
    }

    /** VelaMapView reports romanized road names (local -> basemap Latin) as nav tiles load (issue
     *  #184). Merge them into state (banner/steps consult it) and hand the full map to VoiceGuide so
     *  guidance SAYS "Rehov Herzl" instead of the ICU skeleton. Only grows during a drive;
     *  reset on nav end. */
    fun onNavRoadLatin(map: Map<String, String>) {
        if (map.isEmpty()) return
        val merged = if (_state.value.roadNameLatin.isEmpty()) map
        else _state.value.roadNameLatin + map
        if (merged.size == _state.value.roadNameLatin.size) return
        voice.roadNameLatin = merged
        _state.update { it.copy(roadNameLatin = merged) }
    }

    /** Mute / unmute spoken guidance (the in-nav speaker button). Persisted. */
    /** Hold the drive where it is, or let it go again (the nav Pause button). The route, the
     *  stops and the figures stay put; the puck keeps following you. Resuming reroutes from here
     *  if the stop took us off the route, and driving on resumes it by itself. */
    fun toggleNavPause() {
        val s = _state.value
        if (!s.navigating) return
        navSession.setPaused(!s.navPaused)
    }

    fun toggleVoice() = setSpokenDirections(voice.muted)

    /** Turn spoken directions on/off (Settings toggle; the nav mute button shares this state). */
    fun setSpokenDirections(on: Boolean) {
        voice.muted = !on
        settingsPrefs.edit().putBoolean("spoken_directions", on).apply()
        _state.update { it.copy(voiceMuted = !on) }
    }

    /** Settings -> Data & privacy: periodic in-drive traffic re-checks (they send the current
     *  position to Google every couple of minutes while navigating). Off kills the faster-route
     *  offers, the live ETA recalibration and the abbreviated-steps self-heal; off-course
     *  reroutes still work. */
    fun liveRechecksOn() = settingsPrefs.getBoolean("nav_live_rechecks", true)
    fun setLiveRechecks(on: Boolean) {
        settingsPrefs.edit().putBoolean("nav_live_rechecks", on).apply()
        navSession.liveRechecks = on
    }

    /** Reflect the persisted opt-in diagnostics flag into UI state (Settings reads it). */
    fun refreshDiagnostics() = _state.update { it.copy(diagnosticsEnabled = diag.isEnabled()) }

    /** Opt in/out of the local diagnostics log. Off clears anything collected. */
    fun setDiagnostics(on: Boolean) {
        diag.setEnabled(on)
        _state.update { it.copy(diagnosticsEnabled = on) }
    }

    private val settingsPrefs = appContext.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    // Seeded HERE, not in the main init block above: Kotlin runs property initializers and init
    // blocks in DECLARATION order, and `settingsPrefs` is declared this far down the class, so an
    // early caller reads it as null and the app dies on launch before the map ever draws. Any
    // future pref read that has to happen at construction belongs after this line too.
    init { refreshRouteBar(); scheduleAutoRegionPatches() }

    /**
     * "Update downloaded regions" doing what it says (2026-09-22). The setting used to decide only
     * whether the Update BUTTON could use a patch; nothing ever updated on its own, whatever the
     * row read. Now, when the setting allows the current connection, a minute after start and at
     * most once a day, every installed places or basemap archive and place pack whose manifest
     * publishes a patch FROM the installed revision takes it, quietly. Patches only: a full
     * re-download is never automatic on any setting (a region is hundreds of megabytes), and the
     * routing files publish no patches, so they stay on the Update button. Skipped mid-drive.
     */
    private fun scheduleAutoRegionPatches() {
        viewModelScope.launch {
            delay(AUTO_PATCH_DELAY_MS)
            if (!app.vela.ui.RegionUpdates.allowedNow(appContext) || _state.value.navigating) return@launch
            val now = System.currentTimeMillis()
            if (now - settingsPrefs.getLong(KEY_AUTO_PATCH_AT, 0L) < AUTO_PATCH_EVERY_MS) return@launch
            settingsPrefs.edit().putLong(KEY_AUTO_PATCH_AT, now).apply()
            val note: (String) -> Unit = { line ->
                app.vela.ui.RegionUpdates.lastResult.value = line
                diag.record("delta", "auto: $line")
                android.util.Log.d("VelaDelta", "auto: $line")
            }
            val due = withContext(Dispatchers.IO) {
                listOf(placesStore to app.vela.BuildConfig.PLACES_MANIFEST_URL, basemapStore to app.vela.BuildConfig.BASEMAP_MANIFEST_URL)
                    .flatMap { (store, url) ->
                        runCatching { store.updatable(store.manifest(url)) }.getOrDefault(emptyList())
                            .filter { r -> r.delta != null && store.installedRev(r.id) == r.delta.fromRev }
                            .map { store to it }
                    }
            }
            val packs = withContext(Dispatchers.IO) {
                runCatching { poiPackStore.manifest(app.vela.BuildConfig.POI_PACK_MANIFEST_URL) }.getOrDefault(emptyList())
                    .filter { p -> p.id in poiPackStore.installedIds() && p.deltaUrl != null && p.rev > poiPackStore.installedRev(p.id) &&
                        p.deltaFromRev == poiPackStore.installedRev(p.id) }
            }
            if (due.isEmpty() && packs.isEmpty()) { note("nothing to patch"); return@launch }
            downloadLaunch(appContext.getString(R.string.settings_region_updates)) {
                for ((store, r) in due) {
                    if (_state.value.navigating) break
                    if (store.updateWithDelta(r, onProgress = { }, log = note)) forgetOpenPlaceLinks("${r.id} patched")
                }
                for (p in packs) {
                    if (_state.value.navigating) break
                    _state.value.routingRegions.firstOrNull { it.id == p.id }?.let { downloadPoiPack(it, update = true) }
                }
                refreshPlacesOverlays()
                refreshBasemapArchive()
                if (_state.value.routingRegions.isNotEmpty()) refreshRegionUpdates()
            }
        }
    }

    /** Reflect the persisted route-bar flag into UI state (pref `route_bar`, default off - it is
     *  extra chrome on the nav screen, so it should be asked for, not imposed). */
    fun refreshRouteBar() =
        _state.update { it.copy(routeBarEnabled = settingsPrefs.getBoolean("route_bar", false)) }

    fun refreshTripRecording() =
        _state.update {
            it.copy(
                tripRecordingEnabled = settingsPrefs.getBoolean("trip_recording_on", false),
                nameTripsOnSave = settingsPrefs.getBoolean("trip_name_on_save", false),
            )
        }

    /** Opt in/out of recording nav trips (GPS traces) for replay — strictly local,
     *  more invasive than diagnostics, so it's its own toggle. */
    fun setTripRecording(on: Boolean) {
        settingsPrefs.edit().putBoolean("trip_recording_on", on).apply()
        _state.update { it.copy(tripRecordingEnabled = on) }
    }

    fun recordedTrips(): List<app.vela.replay.TripMeta> = tripStore.list()
    fun deleteTrip(id: String) = tripStore.delete(id)

    /** Rename a saved trip. Returns false when the file could not be rewritten, so the caller
     *  can say so rather than silently showing the old name again. */
    fun renameTrip(id: String, label: String): Boolean = tripStore.rename(id, label)

    /** Whether to ask for a name each time a recorded drive is saved (pref `trip_name_on_save`).
     *  Off by default: a prompt after every drive is the wrong default for someone recording
     *  continuously, and the trips list can rename after the fact either way. */
    fun nameTripsOnSave(): Boolean = settingsPrefs.getBoolean("trip_name_on_save", false)

    fun setNameTripsOnSave(on: Boolean) {
        settingsPrefs.edit().putBoolean("trip_name_on_save", on).apply()
        _state.update { it.copy(nameTripsOnSave = on) }
    }

    /** Dismiss the name-this-trip prompt, keeping whatever the trip was auto-named. */
    fun dismissTripNaming() = _state.update { it.copy(tripToName = null) }

    /** Apply the name from the prompt, then dismiss it. */
    fun nameRecordedTrip(id: String, label: String) {
        tripStore.rename(id, label)
        dismissTripNaming()
    }

    /** Replay a recorded trip's GPS trace through the live pipeline (camera + dot +
     *  nav loop), at 3× so it's quick. Auto-routes to the trip's destination and starts
     *  turn-by-turn so the drive replays exactly as it did (best-effort; the trace still
     *  plays if routing fails), tearing that nav back down when the replay ends. */
    fun replayTrip(meta: app.vela.replay.TripMeta) = nav.replayTrip(meta)
    fun stopReplay() = nav.stopReplay()

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
    fun importListsFromUri(uri: android.net.Uri): app.vela.core.data.ImportResult {
        val json = runCatching {
            appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return app.vela.core.data.ImportResult.Unreadable
        val res = listStore.importMerge(json)
        if (res is app.vela.core.data.ImportResult.Added) _state.update { it.copy(lists = listStore.lists()) }
        return res
    }

    /** Import saved places from a picked file [uri]. Reports WHICH outcome happened so the UI can
     *  tell "that is another app's file" from "you already have all of these" (issue #287). */
    fun importSavedFromUri(uri: android.net.Uri): app.vela.core.data.ImportResult {
        val json = runCatching {
            appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return app.vela.core.data.ImportResult.Unreadable
        val res = savedStore.importMerge(json)
        if (res is app.vela.core.data.ImportResult.Added) _state.update { it.copy(saved = savedStore.saved()) }
        return res
    }

    /**
     * Trim every trip in a multi-select share at ONE radius, in the order given. A null entry is
     * a trip that could not be read or trimmed to nothing; it is left out of the share, never
     * sent raw. Reads and rewrites whole files, so call it off the main thread.
     */
    fun scrubTripsForSharing(
        metas: List<app.vela.replay.TripMeta>,
        radiusM: Double,
    ): List<app.vela.core.replay.TripScrub.Report?> = metas.map { scrubTripForSharing(it, radiusM) }

    /** Whether Settings > Diagnostics > "Redact places in exports" is on. Trip shares then start on
     *  the widest trim ([app.vela.core.replay.TripScrub.defaultRadius]). */
    fun redactExports(): Boolean = settingsPrefs.getBoolean(app.vela.diag.DiagExporter.REDACT_PREF, false)

    /**
     * Share SEVERAL trips at once as ONE zip file.
     *
     * [reports] are the already-trimmed trips from [scrubTripsForSharing], index-aligned with
     * [metas]; nulls are skipped. One file instead of ACTION_SEND_MULTIPLE because several
     * attachments did not arrive in every messenger (Signal, user report). The zip is named by the
     * export's local date and time, each entry by its drive's, never by a label or destination.
     * Returns null when nothing is left to send. Writes a file, so call it off the main thread.
     */
    fun shareTripsZipIntent(
        metas: List<app.vela.replay.TripMeta>,
        reports: List<app.vela.core.replay.TripScrub.Report?>,
    ): android.content.Intent? {
        val kept = metas.zip(reports).mapNotNull { (m, r) -> r?.let { m to it } }
        if (kept.isEmpty()) return null
        return runCatching {
            val names = app.vela.core.replay.TripShareBatch.entryNames(kept.map { tripStamp(it.first.startedAt) })
            val dir = java.io.File(appContext.cacheDir, "export").apply { mkdirs() }
            val file = java.io.File(dir, "vela-trips-${tripStamp(System.currentTimeMillis())}.zip")
            app.vela.core.replay.TripShareBatch.writeZip(
                names.zip(kept.map { it.second.csv }),
                file.outputStream(),
            )
            val uri = androidx.core.content.FileProvider.getUriForFile(
                appContext, "${appContext.packageName}.fileprovider", file,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = app.vela.core.replay.TripShareBatch.MIME
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(
                    android.content.Intent.EXTRA_SUBJECT,
                    appContext.getString(R.string.mapvm_export_trips_subject, kept.size, kept.sumOf { it.second.fixesAfter }),
                )
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            android.content.Intent.createChooser(send, appContext.getString(R.string.mapvm_share_trip_chooser))
                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        }.getOrNull()
    }

    fun exportTripIntent(meta: app.vela.replay.TripMeta): android.content.Intent? {
        val csv = tripStore.rawCsv(meta.id) ?: return null
        return runCatching {
            val dir = java.io.File(appContext.cacheDir, "export").apply { mkdirs() }
            val file = java.io.File(dir, "vela-trip-${tripStamp(meta.startedAt)}-full.csv")
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

    /**
     * Scrub a recorded trip for publication — trim the private ends off, see [TripScrub]. Returns
     * what the scrub did (including the CSV) so the UI can show it BEFORE anything leaves the
     * device, or null when the trip can't be read or is shorter than one trim zone.
     *
     * Home and Work are added as trim zones automatically when they're set: a drive that merely
     * PASSES one of them leaks it just as thoroughly as one that starts there, and they're the two
     * places most worth protecting.
     */
    /** "2026-09-13-1432": the local date and time a drive started, for export file names. */
    fun tripStamp(startedAt: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date(startedAt))

    fun scrubTripForSharing(
        meta: app.vela.replay.TripMeta,
        radiusM: Double = app.vela.core.replay.TripScrub.DEFAULT_RADIUS_M,
    ): app.vela.core.replay.TripScrub.Report? {
        val csv = tripStore.rawCsv(meta.id) ?: return null
        val zones = listOfNotNull(
            shortcutStore.get(ShortcutKind.HOME)?.location,
            shortcutStore.get(ShortcutKind.WORK)?.location,
        )
        return runCatching {
            app.vela.core.replay.TripScrub.scrub(csv, radiusM = radiusM, extraZones = zones)
        }.getOrNull()
    }

    /**
     * A share intent for an already-scrubbed trip. Deliberately separate from [exportTripIntent]:
     * the filename and the subject line of that one both carry the trip's LABEL and id, and the
     * label is the destination's own name (trips are named after where they went) while the id is
     * its start timestamp. Sharing a scrubbed body under a filename that names the address would
     * defeat the whole thing.
     */
    fun shareScrubbedTripIntent(report: app.vela.core.replay.TripScrub.Report, startedAt: Long? = null): android.content.Intent? =
        runCatching {
            val dir = java.io.File(appContext.cacheDir, "export").apply { mkdirs() }
            // Named by the drive's date and time (user 2026-09-13: "vela-trip-shared.csv" says
            // nothing), never by its label or destination, which the scrub removed from the body.
            val file = java.io.File(dir, "vela-trip-${startedAt?.let { tripStamp(it) } ?: "shared"}.csv")
            file.writeText(report.csv)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                appContext, "${appContext.packageName}.fileprovider", file,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(
                    android.content.Intent.EXTRA_SUBJECT,
                    appContext.getString(R.string.mapvm_export_trip_scrubbed_subject, report.fixesAfter),
                )
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            android.content.Intent.createChooser(send, appContext.getString(R.string.mapvm_share_trip_chooser))
                .apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
        }.getOrNull()

    /** Dismiss the arrival summary and return to a clean map (drops the finished
     *  route + selection). */
    fun finishNav() = nav.finishNav()
    /** Show or hide the route bar (pref `route_bar`). */
    fun setRouteBar(on: Boolean) = nav.setRouteBar(on)
    fun resumeNav() = nav.resumeNav()
    fun dismissResume() = nav.dismissResume()
    fun acceptFasterRoute() = nav.acceptFasterRoute()
    fun dismissFasterRoute() = nav.dismissFasterRoute()

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
    /** Guidance volume tier (issue #245): persisted multiplier the neural voice applies to its
     *  own PCM (boost possible) and the system TTS takes capped at 1.0 (Android can only
     *  attenuate). Auditions the nav sample so the change is heard immediately. */
    fun setVoiceVolume(v: Float) {
        val vol = v.coerceIn(0.2f, 3.0f)
        settingsPrefs.edit().putFloat("voice_volume", vol).apply()
        voice.setVolume(vol)
        voice.speak(appContext.getString(R.string.mapvm_voice_sample), interrupt = true)
    }

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
        voiceCancel.set(false)
        _state.update { it.copy(voiceDownloadingId = id, voiceDownloadPct = 0f, voiceInstalling = false) }
        downloadLaunch(v.displayName) {
            val ok = kokoroInstaller.download(
                PiperCatalog.downloadUrl(id), VelaPiper.modelDirFor(appContext, id), v.sizeBytes,
                onExtracting = { _state.update { if (it.voiceDownloadingId == id) it.copy(voiceInstalling = true) else it } },
                active = { !voiceCancel.get() },
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
            } else if (!voiceCancel.get()) { // a user cancel is not a failure - stay quiet
                showStatus(appContext.getString(R.string.mapvm_voice_download_failed, v.displayName))
            }
        }
    }

    // ---- On-device voice search (tier-1 ASR: Whisper / SenseVoice / Moonshine) ----

    /** Reflect which engines are on disk + which is active (Settings picker shows Download/Remove/Use). */
    fun refreshAsr() {
        _state.update {
            it.copy(
                asrInstalledIds = app.vela.voice.AsrEngine.installed(appContext).map { e -> e.id }.toSet(),
                asrActiveId = app.vela.voice.AsrEngine.active(appContext).id,
            )
        }
        // "Load voice search at startup" (Settings > Performance): the earlier behavior, kept as a
        // choice and on by default only on roomy phones.
        if (app.vela.ui.SpeechPreload.on.value) warmAsrForSearch()
    }

    /** Pre-build the recognizer when the user REACHES for search (the search box gains focus), not
     *  at launch (2026-09-22): the model is ~290 MB of native memory for two minutes on the chance
     *  of a mic tap, and at launch it stacked with the web view warm-up into a full swap on the 4a.
     *  Focus comes a second or two before a spoken query, which is most of the load; a mic tapped
     *  straight from the bare map shows the "Getting ready" beat instead. */
    fun warmAsrForSearch() {
        if (app.vela.ui.VoiceSearch.enabled.value &&
            app.vela.ui.VoiceSearch.engine.value != app.vela.ui.VoiceSearch.Engine.SYSTEM
        ) {
            asrRecognizer.warmUp()
        }
    }

    /** Download the DEFAULT (Whisper) engine - the one-tap "install voice search" offer on the map. */
    fun downloadAsrModel() = downloadAsrEngine(app.vela.voice.AsrEngine.DEFAULT)

    /** Download a specific voice-search engine, reusing the neural-voice installer + its no-call-timeout
     *  client (the shared 12 s cap would abort a download this size). The FIRST engine installed becomes
     *  active automatically; later ones are downloaded but not auto-selected (the user picks in Settings). */
    fun downloadAsrEngine(engine: app.vela.voice.AsrEngine) {
        if (_state.value.asrDownloadPct != null) return // serialize: one engine at a time
        val bytes = engine.sizeMb.toLong() * 1024 * 1024
        if (appContext.filesDir.usableSpace < bytes * 13 / 10) {
            showStatus(appContext.getString(R.string.mapvm_not_enough_space, appContext.getString(R.string.settings_voice_search_model), engine.sizeMb))
            return
        }
        val hadNone = app.vela.voice.AsrEngine.installed(appContext).isEmpty()
        asrRecognizer.clearQuarantine(engine) // a fresh download replaces whatever was quarantined
        asrCancel.set(false)
        _state.update { it.copy(asrDownloadPct = 0f, asrInstalling = false, asrDownloadingId = engine.id) }
        downloadLaunch(engine.displayName) {
            val ok = kokoroInstaller.download(
                engine.url, engine.dir(appContext), bytes,
                onExtracting = { _state.update { it.copy(asrInstalling = true) } },
                active = { !asrCancel.get() },
            ) { p -> _state.update { it.copy(asrDownloadPct = p) } }
            // A fresh install with nothing selected yet becomes the active engine, so voice search
            // works right after the very first download with no extra "pick one" step.
            if (ok && engine.isInstalled(appContext) && hadNone) {
                app.vela.voice.AsrEngine.setActive(appContext, engine)
            }
            _state.update { it.copy(asrDownloadPct = null, asrInstalling = false, asrDownloadingId = null) }
            refreshAsr()
            if (ok && engine.isInstalled(appContext)) {
                asrRecognizer.warmUp() // a fresh install should listen immediately on first tap
                flashStatus(appContext.getString(R.string.mapvm_asr_ready))
            } else {
                showStatus(appContext.getString(R.string.mapvm_asr_download_failed))
            }
        }
    }

    /** Make an already-installed engine the active one and warm it, so the next mic tap uses it. */
    fun selectAsrEngine(engine: app.vela.voice.AsrEngine) {
        if (!engine.isInstalled(appContext)) return
        app.vela.voice.AsrEngine.setActive(appContext, engine)
        refreshAsr()
        asrRecognizer.warmUp()
    }

    /** Remove a downloaded engine's files. If it was active, [AsrEngine.active] falls back to another
     *  installed engine (or the default), so the mic keeps working when possible. */
    fun deleteAsrEngine(engine: app.vela.voice.AsrEngine) {
        // Free the loaded model BEFORE removing its files. Deleting the directory alone left the
        // native recognizer resident for the rest of the process (~267 MB measured on the fork),
        // so Remove reclaimed disk but no memory at all (ported from vela-dpad, 2026-07-23).
        asrRecognizer.release()
        engine.dir(appContext).deleteRecursively()
        refreshAsr()
        if (app.vela.voice.AsrEngine.anyInstalled(appContext)) asrRecognizer.warmUp()
    }

    fun voiceMicGranted(): Boolean = asrRecognizer.hasMicPermission()

    /** Record + transcribe on-device (tier-1); returns the heard text or null. Driven by the capture
     *  dialog, which supplies the loudness sink, a start callback and an early-stop check. */
    suspend fun voiceListen(
        onLevel: (Float) -> Unit,
        onListening: () -> Unit,
        canceled: () -> Boolean,
    ): app.vela.voice.VoiceResult =
        asrRecognizer.listen(onLevel, onListening, canceled)

    /** Apply a transcript from either voice tier as the query and run the search. */
    fun applyVoiceQuery(text: String) {
        onQueryChange(text)
        search() // intents first ("take me home"), else the plain search
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
        // Drop it from the UI IMMEDIATELY (optimiztic): the actual unlink is async (worker/IO), and
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
     *  else the app-language's recommended voice. Public: the voice browser brands this id
     *  "Vela voice" and offers a one-tap reinstall when it's missing (user 2026-07-18, after a
     *  crash mid-install left them hunting the list for which voice was the right one). */
    fun defaultVoiceId(): String {
        val lang = app.vela.ui.AppLocale.effective().language
        return if (lang == "en") calibration.current().defaultVoiceId else PiperCatalog.defaultFor(lang).id
    }

    /** Download size (MB) of the voice [downloadPiper] would fetch — so the onboarding prompt shows
     *  the REAL size (it used to hardcode the long-gone 126 MB Kokoro model). */
    fun defaultVoiceSizeMb(): Int = PiperCatalog.byId(defaultVoiceId())?.sizeMb ?: 67

    /** null = still initializing, true = a voice is ready, false = no usable voice. */
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
     *  center. While on, the live GPS collector is suspended and every "your location" (the dot,
     *  the search-distance bias, the directions origin, recenter) reads this point, so the app can
     *  be shown from anywhere without leaking where you actually are. Sibling of demo-drive. */
    fun simulateLocationHere() {
        val here = mapCenter ?: _state.value.myLocation ?: return
        app.vela.ui.SimLocation.set(appContext, here)
        locationJob?.cancel(); locationJob = null // sim owns the puck — no live fixes
        // The timer armed by the collector's LAST fix keeps ticking after the cancel and would
        // gray the pinned dot ~30 s in (same hole as the startLocation() sim branch).
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
        // COLD-LOAD STAGGER (user 2026-07-17, 4a): the house-shape footprints + house-number labels
        // are the heaviest render layers, and on a fresh launch they came online at the same instant
        // as the ambient POI symbol-collision - three heavy things at once on a weak GPU. When ambient
        // hasn't painted yet (cold), hold these two a beat so the POIs place first; warm pans refresh
        // immediately. Gated on the viewport still being live so a fast pan during the wait doesn't
        // stream a stale region.
        if (_state.value.ambientPois.isEmpty()) {
            val vp = viewport
            viewModelScope.launch {
                kotlinx.coroutines.delay(600)
                if (viewport === vp) { refreshBuildingOverlays(center); refreshAddressOverlays(center) }
            }
        } else {
            refreshBuildingOverlays(center) // stream the building overlay for whatever region is now in view
            refreshAddressOverlays(center) // + house-number labels for that region
        }
        refreshMaxspeedOverlay(center) // + the posted-speed-limit overlay (read under the puck for the sign)
        refreshPlacesOverlays(center)
        refreshTrafficControls(south, west, north, east, zoom) // + traffic lights / stop signs at high zoom
        lastFlockViewport = doubleArrayOf(south, west, north, east, zoom)
        refreshFlock(south, west, north, east, zoom) // + ALPR/Flock cameras when the layer is on
        refreshSpeedCams(south, west, north, east, zoom) // + fixed radar cameras when that layer is on
        refreshTransitStops(south, west, north, east, zoom) // + canonical GTFS stop icons at street zoom
        refreshImageryYear(south, west, north, east) // + the capture year for the satellite attribution
        refreshSatDeep(south, west, north, east, zoom) // + deep-zoom imagery availability (issue #244)
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

    // How long a completed fetch is served AS-IS for views it covers (no refetch). Short on
    // purpose: it only needs to absorb the tap-a-POI-and-close camera shift, not stand in for
    // the moved-gate's refresh loop.
    private val AMBIENT_FRESH_MS = 3 * 60_000L

    private fun cacheAmbient(center: LatLng, spanM: Double, places: List<app.vela.core.model.Place>) {
        ambientCache.removeAll { it.center.distanceTo(center) < 400.0 } // replace a near-duplicate area
        ambientCache.addLast(AmbientEntry(center, spanM, places, android.os.SystemClock.elapsedRealtime()))
        while (ambientCache.size > 32) ambientCache.removeFirst()
        persistAmbientCache()
    }

    // ---- Ambient cache on DISK: browsed areas paint instantly for WEEKS, online or not. ----
    // Slim rows via :core's AmbientDiskCache codec (the app stays out of kotlinx.serialization),
    // newest 32 areas x 200 places (~1 MB), debounced writes; entries older than 14 days drop at
    // load. WRITE-THROUGH: caching never skips a fetch the moved-gate would make - the store is
    // populated as a side effect of normal browsing and every live result overwrites its area, so
    // being online keeps it current for free. It only DECIDES anything when the network can't
    // answer (cold launch, offline): then the dots come from here, and the live layer (the place
    // sheet's details fetch) remains the truth for hours / closed status when tapped.
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
            val entries = snapshot.takeLast(32).map { e ->
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
            }.onSuccess {
                android.util.Log.d("VelaAmbient", "persisted ${entries.size} areas, ${entries.sumOf { it.places.size }} places")
            }.onFailure { android.util.Log.d("VelaAmbient", "persist FAILED: $it") }
        }
    }

    private fun loadAmbientCacheFromDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = runCatching { ambientDiskFile().readText() }.getOrNull()
                ?.let { app.vela.core.data.AmbientDiskCache.decode(it) } ?: run {
                android.util.Log.d("VelaAmbient", "disk load: no file / decode failed")
                return@launch
            }
            val nowWall = System.currentTimeMillis()
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            // places.isNotEmpty(): drop areas an offline empty-success poisoned before the
            // empty-pool guard existed - a blank area can only ever paint a blank map.
            val fresh = entries.filter { it.places.isNotEmpty() && nowWall - it.atWallMs < 14 * 24 * 3600_000L }
            android.util.Log.d("VelaAmbient", "disk load: ${entries.size} areas, ${fresh.size} fresh")
            if (fresh.isEmpty()) return@launch
            val loaded = fresh.map { e ->
                // Backdated past the fresh-skip window: disk entries repaint instantly but MUST
                // NOT satisfy the fresh-and-covering no-refetch path - they are paint-then-refine
                // (a day-old pool trusted for even 3 minutes would hide the slim-flavor heal and
                // any overnight closures on the first view).
                AmbientEntry(LatLng(e.lat, e.lng), e.spanM, e.places.map { it.toPlace() }, nowElapsed - AMBIENT_FRESH_MS)
            }
            // Main-thread hop: the cache deque is only ever touched from the main dispatcher.
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (ambientCache.isEmpty()) loaded.forEach { ambientCache.addLast(it) }
            }
        }
    }
    /** Freshest non-stale cached fetch whose center is within ~900 m of [center], re-centered so its
     *  distances are correct for the new view. Null if nothing recent+near is cached. */
    private fun cachedAmbientNear(center: LatLng): AmbientEntry? {
        val now = android.os.SystemClock.elapsedRealtime()
        // SPAN-AWARE hit: a fetch covers spanM around its center (3.5-9 km), so any view whose
        // center sits well inside that area can repaint from it. The old fixed 900 m radius
        // missed most legitimate revisits (zoom-out-and-back, pan-away-and-return) and forced
        // a full ~2-4 s Google refetch - the P9 "POIs don't stick around" report (2026-07-11).
        return ambientCache
            .filter { it.places.isNotEmpty() && now - it.atMs < 30 * 60_000L && it.center.distanceTo(center) < it.spanM * 0.45 }
            .minByOrNull { it.center.distanceTo(center) }
    }

    /** Warm the ambient LRU for the four neighboring view-sized areas (N/S/E/W at ~0.9 of the
     *  span) so a pan in any direction repaints instantly from cache. Gated HARD: bare map only,
     *  UNMETERED network only (this is real extra traffic: 4 more fan-outs), skips areas already
     *  cached, sequential (never bursts 52 parallel requests at Google), one round per fetch. */
    private var prefetchJob: Job? = null
    private fun prefetchAmbientNeighbors(center: LatLng, span: Double, zoom: Double) {
        if (zoom < 14.5) return // wide views cover the neighbors already
        // Google-only mode only (2026-09-23): four neighbors x the category fan-out is ~60 requests
        // to Google per settle for areas nobody has panned to yet. With Vela's own places layer
        // drawing (Vela data, Both) the neighbors already paint instantly from the archive.
        if (app.vela.ui.MapPoiPrefs.openPlaces) return
        val cm = appContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            val dLat = span * 0.9 / 111_320.0
            val dLng = dLat / kotlin.math.cos(Math.toRadians(center.lat)).coerceAtLeast(0.2)
            val neighbors = listOf(
                LatLng(center.lat + dLat, center.lng), LatLng(center.lat - dLat, center.lng),
                LatLng(center.lat, center.lng + dLng), LatLng(center.lat, center.lng - dLng),
            )
            for (n in neighbors) {
                delay(app.vela.core.util.Jitter.around(700)) // spread the extra load; a real pan cancels via ambientJob's own churn
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
     * past neighborhood level (they'd be sparse + cluttered over a huge area). Tightly gated:
     * bare map only (no results / open place / nav / replay), debounced, re-queried on a real pan
     * OR zoom change.
     */
    private fun maybeLoadAmbientPois(center: LatLng, zoom: Double, viewRadiusMeters: Double = 0.0, settled: Boolean = false) {
        val s = _state.value
        // "Both" places setting with the open layer covering the view: Google is a top-up, not the
        // paint, so nothing (not even the cache) goes on the map until the view has properly
        // settled. One fetch at the end of a pan across town instead of one per flick, and by
        // then the open tiles are loaded, which the map needs to drop the overlap.
        if (!settled && app.vela.ui.MapPoiPrefs.openPlaces && s.placesOverlays.isNotEmpty() && app.vela.ui.MapPoiPrefs.showPois.value) {
            ambientJob?.cancel()
            ambientJob = viewModelScope.launch {
                delay(1500)
                maybeLoadAmbientPois(center, zoom, viewRadiusMeters, settled = true)
            }
            return
        }
        // "Show places on the map" master switch (user 2026-07-15): off = clean basemap, only
        // searched results draw. Clear whatever is up so flipping the toggle acts immediately.
        // The open places layer owns the dots where it covers the view (2026-09-14): no Google
        // fan-out at all, the map draws the baked tiles, Google is asked only when a place is tapped.
        // In the "both" setting the layer still draws the map, and the fan-out below runs once the
        // view has properly settled to fill in what the open data lacks (the map drops the overlap).
        if (!app.vela.ui.MapPoiPrefs.showPois.value || (app.vela.ui.MapPoiPrefs.openPlacesOnly && s.placesOverlays.isNotEmpty())) {
            ambientJob?.cancel()
            lastAmbientCenter = null
            app.vela.ui.map.AmbientStability.reset()
            if (s.ambientPois.isNotEmpty() || s.ambientCoversView) {
                _state.update { it.copy(ambientPois = emptyList(), ambientCoversView = false) }
            }
            return
        }
        // Street View counts as "not a bare map" too (user 2026-07-17): panning the mini-map while
        // the sphere is up was still firing the full ambient fan-out - scrape + parse + repaint
        // churning behind a viewer the user is actually looking at, for dots they can barely see.
        if (s.navigating || s.replaying || s.results.isNotEmpty() || s.selected != null ||
            s.streetView != null || s.streetViewLoading
        ) return
        // Zoomed out past neighborhood level → drop the dots (and let the OSM POIs come back).
        if (zoom < 14.0) {
            ambientJob?.cancel()
            lastAmbientCenter = null
            app.vela.ui.map.AmbientStability.reset()
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
        // A real pan or zoom: the view the painted ranking was frozen for is gone, so rank the new
        // one from scratch (AmbientStability).
        app.vela.ui.map.AmbientStability.reset()
        ambientJob?.cancel()
        prefetchJob?.cancel() // the old neighborhood's warm-up is moot once the view moved
        // Span ≈ viewport height: ~9 km at z14 down to ~3.5 km zoomed in (kept ≥3.5 km — tighter
        // than that returns FEWER local hits, per the live calibration).
        val span = (9000.0 / 2.0.pow(zoom - 14.0)).coerceIn(3500.0, 9000.0)
        // Any recent nearby fetch cached (e.g. an area you already visited this session)? Repaint it
        // INSTANTLY so there's no empty→OSM-POI flash→ambient "small then pop bigger" while the network
        // fetch below runs; the fetch then refines it. UNCONDITIONAL on purpose (2026-07-11): the old
        // empty-only gate meant panning BACK to a cached area kept the PREVIOUS area's dots (non-empty,
        // but filtered to nothing in this view) and never consulted the cache - a bare map for the whole
        // refetch, the P9 "tap a POI / pan back and everything is gone" report. The hit is by definition
        // the best-known data for THIS center; the fetch below still refines it.
        cachedAmbientNear(center)?.let { entry ->
            val cached = entry.places.map { it.copy(distanceMeters = center.distanceTo(it.location)) }
            _state.update { it.copy(ambientPois = withRecentlyViewed(civicFiltered(keepAmbientForView(cached, viewRadiusMeters, zoom))), ambientClosed = cached.filter { p -> p.permanentlyClosed }) }
            // A FRESH fetch that still COVERS this view is served as-is, no network refetch
            // (user 2026-07-15): tapping a POI shifts the camera enough to trip the moved-gate,
            // so closing the sheet re-fetched the SAME area seconds later - and Google's ranking
            // jitters between identical requests, so the replace randomly swapped/dropped a few
            // icons ("the ones around it disappear then reload"). Same-area data seconds apart
            // is not fresher, just different. Coverage = the ambientCoversView predicate; the
            // window is short so lingering somewhere still refreshes on the next real pan.
            val fresh = android.os.SystemClock.elapsedRealtime() - entry.atMs < AMBIENT_FRESH_MS
            val covers = entry.center.distanceTo(center) < entry.spanM * 0.35 &&
                viewRadiusMeters <= entry.spanM * 0.55
            if (fresh && covers) {
                lastAmbientCenter = entry.center
                lastAmbientZoom = zoom
                lastAmbientSpan = entry.spanM
                if (!_state.value.ambientCoversView) _state.update { it.copy(ambientCoversView = true) }
                return
            }
        }
        // OFFLINE the fan-out is thirteen requests that cannot succeed. The cache repaint above
        // still runs, so an area visited earlier keeps its dots; only the network is skipped.
        // Cleanly offline these fail fast and cost little, but the case that actually burns the
        // radio is a FLAKY link, where every one of them hangs to the call timeout.
        if (googleOff()) return
        ambientJob = viewModelScope.launch {
            delay(300) // brief settle so a flick doesn't scrape — but snappy
            // PROGRESSIVE paint: the fan-out streams its accumulated pool as category terms
            // land, so first dots show ~1 s in instead of waiting for the slowest request
            // (the tail was most of the perceived wait; user 2026-07-11). Each partial passes
            // the same bare-map gates as the final.
            fun bareMap(): Boolean {
                val cur = _state.value
                return !(
                    cur.navigating || cur.replaying || cur.results.isNotEmpty() || cur.selected != null ||
                        cur.streetView != null || cur.streetViewLoading
                    )
            }
            // A canceled fetch's SLOW straggler must not paint: the fan-out children have no
            // suspension point between the blocking HTTP call and the merge, so they outlive
            // cancel() long enough to fire onPartial for the OLD center - and the moved-gate
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
                            val kept = keepAmbientForView(partial, viewRadiusMeters, zoom)
                            if (kept.size >= cur.ambientPois.size) cur.copy(ambientPois = withRecentlyViewed(civicFiltered(kept))) else cur
                        }
                    }
                }
            }.getOrNull()
            // STALE-IF-ERROR: a thrown fetch is null, but OFFLINE usually is not - each fan-out
            // term swallows its network error into an empty list, so no-network comes back as an
            // EMPTY SUCCESS. Both mean the same thing here: Google did not answer. Treat them
            // identically, because caching/painting the empty "result" poisoned the durable store
            // with a blank area and wiped the painted dots (device-caught 2026-07-17: the store
            // dropped 1800 -> 1600 places after one airplane-mode pan). Serve the freshest
            // covering store entry regardless of age if nothing is painted - week-old dots beat a
            // bare map, and the sheet's live details fetch stays the truth for anything actually
            // opened. lastAmbientCenter stays unset so the next settle retries the network.
            if (res.isNullOrEmpty()) {
                if (live() && bareMap() && _state.value.ambientPois.isEmpty()) {
                    val hit = ambientCache
                        .filter { it.places.isNotEmpty() && it.center.distanceTo(center) < it.spanM * 0.45 }
                        .maxByOrNull { it.atMs }
                    android.util.Log.d("VelaAmbient", "no answer (null/empty); stale-if-error hit=${hit != null} cacheSize=${ambientCache.size}")
                    hit?.let { e ->
                        val rec = e.places.map { p -> p.copy(distanceMeters = center.distanceTo(p.location)) }
                        _state.update { it.copy(ambientPois = withRecentlyViewed(civicFiltered(keepAmbientForView(rec, viewRadiusMeters, zoom)))) }
                    }
                }
                return@launch
            }
            if (!live()) return@launch
            lastAmbientCenter = center
            lastAmbientZoom = zoom
            lastAmbientSpan = span
            cacheAmbient(center, span, res)
            // Re-check we're still on the bare map — the user may have searched/opened a place while we fetched.
            if (!bareMap()) return@launch
            // A completed live fan-out is definitive proof of connectivity - heal a stale offline
            // flag here too (same rule the search path applies).
            _state.update { it.copy(ambientPois = withRecentlyViewed(civicFiltered(keepAmbientForView(res, viewRadiusMeters, zoom))), ambientClosed = res.filter { p -> p.permanentlyClosed }, ambientCoversView = true, offline = false) }
            // Idle now: quietly warm the four NEIGHBOR areas into the LRU so panning one screen
            // over paints instantly (unmetered connections only - it's ~4 extra fan-outs).
            prefetchAmbientNeighbors(center, span, zoom)
        }
    }

    /** The place the user just had OPEN (sheet since dismissed), pinned into ambient paints for a couple of minutes so
     *  the tapped icon can't vanish on back-out. The zoom-tiered cap made this visible: the
     *  close-triggered repaint re-cuts to top-N by prominence and Google's ranking jitters between
     *  identical requests, so a mid-tier place near the cap boundary randomly lost its slot. */
    private var recentlyViewed: app.vela.core.model.Place? = null
    private var recentlyViewedAtMs = 0L

    /** A live details fetch confirmed [p] permanently closed: flip the flag on every cached copy
     *  (same name+150m identity the selected-copy drop uses) and prune it from the painted set,
     *  then persist - so the dead dot stays gone across repaints AND restarts, not just until the
     *  next cache paint resurrects it. */
    private fun markClosedInAmbient(p: app.vela.core.model.Place) {
        fun matches(o: app.vela.core.model.Place) =
            o.name.equals(p.name, ignoreCase = true) && o.location.distanceTo(p.location) < 150.0
        var changed = false
        for (i in ambientCache.indices) {
            val e = ambientCache[i]
            if (e.places.none { matches(it) && !it.permanentlyClosed }) continue
            ambientCache[i] = e.copy(places = e.places.map { if (matches(it)) it.copy(permanentlyClosed = true) else it })
            changed = true
        }
        if (changed) persistAmbientCache()
        if (_state.value.ambientPois.any { matches(it) }) {
            _state.update { st -> st.copy(ambientPois = st.ambientPois.filterNot { matches(it) }) }
        }
    }

    private fun withRecentlyViewed(kept: List<app.vela.core.model.Place>): List<app.vela.core.model.Place> {
        val p = recentlyViewed ?: return kept
        if (android.os.SystemClock.elapsedRealtime() - recentlyViewedAtMs > 120_000L) {
            recentlyViewed = null
            return kept
        }
        // Same identity match the selected-copy drop uses (name + ~150 m) - if a copy survived the
        // cut there's nothing to add. One extra symbol past the cap is nothing to the layer.
        if (kept.any { it.name.equals(p.name, ignoreCase = true) && it.location.distanceTo(p.location) < 150.0 }) return kept
        return kept + p
    }

    /** The on-screen ambient set the map layer renders: POIs NEAR the view (a prominence-weighted
     *  keep-radius - anchors survive farther off-center, like Google) capped at [AMBIENT_ONSCREEN_CAP]
     *  so a budget GPU isn't colliding the whole ~3.5 km pool each drag frame. Off-screen POIs can't
     *  paint anyway. Preserves `res`'s prominence order (the ambient layer's collision key = index),
     *  so the anchor store still beats its in-store tenant. */
    private fun keepAmbientForView(res: List<app.vela.core.model.Place>, viewRadiusMeters: Double, zoom: Double): List<app.vela.core.model.Place> =
        res.asSequence()
            .filterNot { p -> p.permanentlyClosed }
            .filter { p ->
                if (viewRadiusMeters <= 0.0) return@filter true
                val reach = viewRadiusMeters * (1.25 + 0.35 * (app.vela.ui.map.AmbientStability.prominenceOf(p) / 8.0).coerceIn(0.0, 1.0))
                (p.distanceMeters ?: 0.0) <= reach
            }
            // Re-rank with the prominence each place was PAINTED with (AmbientStability): the pool
            // arrives ranked on whatever review counts this request carried, and a settled view is
            // painted several times, so the cap and the collision order used to shuffle under a
            // user who had not moved. New places still sort into place on their own value.
            .sortedWith(
                compareByDescending<app.vela.core.model.Place> { app.vela.ui.map.AmbientStability.prominenceOf(it) }
                    .thenBy { it.distanceMeters ?: Double.MAX_VALUE },
            )
            .take(ambientCap(zoom))
            .toList()
            .also { app.vela.ui.map.AmbientStability.remember(it) }

    fun hasViewport(): Boolean = viewport != null

    /** On-disk sizes for the Offline maps storage breakdown (issue #214: 8 GB arrived unannounced). */
    data class OfflineStorage(val mapsMb: Int, val routingMb: Int, val placesMb: Int, val voicesMb: Int)

    suspend fun offlineStorageBreakdown(): OfflineStorage = kotlinx.coroutines.withContext(Dispatchers.IO) {
        fun mbOf(f: java.io.File): Int = when {
            !f.exists() -> 0
            f.isFile -> (f.length() / (1024 * 1024)).toInt()
            else -> (f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)).toInt()
        }
        val files = appContext.filesDir
        OfflineStorage(
            // MapLibre keeps saved areas AND the browsing cache in one database (.mapbox);
            // the downloaded building/address overlays are map data too, and so are the label
            // glyph pack the offline basemap needs (~200 MB) and the baked road features.
            mapsMb = mbOf(java.io.File(files, ".mapbox")) + mbOf(java.io.File(files, "mbgl-offline.db")) +
                mbOf(java.io.File(files, "overlays")) + mbOf(java.io.File(files, "basemap")) +
                mbOf(java.io.File(files, "glyphs")) + mbOf(java.io.File(files, "roadfeatures")),
            routingMb = mbOf(java.io.File(files, "obf")),
            // The packs AND the places archives a region download pulls: both are the place data
            // behind the map's businesses, and leaving the archives out of the only storage screen
            // made a few hundred MB invisible.
            placesMb = mbOf(java.io.File(files, "poipacks")) + mbOf(java.io.File(files, "places")),
            voicesMb = mbOf(java.io.File(files, "piper")) + mbOf(java.io.File(files, "asr")),
        )
    }

    /** Everything downloaded for offline use, gone (issue #601): every saved area, every region's
     *  routing, place pack, places and basemap archives, the building and address overlays (which
     *  ride along with an area save and had NO delete path of their own), the road features, any
     *  legacy graph tree, the basemap's label glyph pack, and the browsing cache; then MapLibre's database is PACKED so the file
     *  actually shrinks. Files a per-region delete could not reach (an archive whose id left the
     *  catalog when a country was re-split) go too: after the stores have deleted what they know,
     *  every remaining file under their folders is swept, keeping only the index files. Voices and
     *  speech models are not offline map data and are left alone. */
    fun deleteAllOfflineData() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { obfStore.installedIds().forEach { obfStore.delete(it) } }
                runCatching { poiPackStore.installedIds().forEach { poiPackStore.delete(it) } }
                runCatching { placesStore.installedIds().forEach { placesStore.delete(it) } }
                runCatching { basemapStore.installedIds().forEach { basemapStore.delete(it) } }
                runCatching { overlayStore.installedIds().forEach { overlayStore.delete(it) } }
                val keep = setOf("index.json", "revs.json", "dead.json")
                for (folder in listOf("obf", "poipacks", "places", "basemap", "overlays", "roadfeatures", "graphs")) {
                    java.io.File(appContext.filesDir, folder).listFiles()?.forEach { f ->
                        if (f.name !in keep) runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
                    }
                }
                // The label glyph pack only serves the offline basemap, which is gone now; it comes
                // back with the next basemap download (and heals itself if one is ever installed
                // without it).
                runCatching { app.vela.offline.GlyphPackStore.delete(appContext) }
            }
            (routeEngine as? app.vela.core.data.ObfRouteEngine)?.shutdown()
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                app.vela.offline.OfflineMaps.deleteAll(appContext) { if (cont.isActive) cont.resume(Unit) { _, _, _ -> } }
            }
            clearMapCache(flash = false)
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                app.vela.offline.OfflineMaps.packDatabase(appContext) { if (cont.isActive) cont.resume(Unit) { _, _, _ -> } }
            }
            _state.update {
                it.copy(
                    routingInstalledIds = obfStore.installedIds(), poiPackInstalledIds = poiPackStore.installedIds(),
                    placesOverlays = emptyList(), basemapArchive = null, buildingOverlays = emptyList(), addressOverlays = emptyList(),
                )
            }
            refreshPlacesOverlays()
            flashStatus(appContext.getString(R.string.mapvm_offline_all_deleted))
        }
    }

    /** Clear MapLibre's ambient (browsing) tile cache. Saved offline areas are untouched -
     *  clearAmbientCache only drops the cache the map filled while browsing. Packs the database
     *  after, so the cleared bytes leave the file (issue #601). */
    suspend fun clearMapCache(flash: Boolean = true): Unit = kotlinx.coroutines.withContext(Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            runCatching {
                org.maplibre.android.offline.OfflineManager.getInstance(appContext).clearAmbientCache(
                    object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                        override fun onSuccess() { if (cont.isActive) cont.resume(Unit) { _, _, _ -> } }
                        override fun onError(message: String) { if (cont.isActive) cont.resume(Unit) { _, _, _ -> } }
                    },
                )
            }.onFailure { if (cont.isActive) cont.resume(Unit) { _, _, _ -> } }
        }
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            app.vela.offline.OfflineMaps.packDatabase(appContext) { if (cont.isActive) cont.resume(Unit) { _, _, _ -> } }
        }
        if (flash) flashStatus(appContext.getString(R.string.settings_map_cache_cleared))
    }

    fun downloadViewport() {
        val v = viewport ?: run { showStatus(appContext.getString(R.string.mapvm_pan_to_area_first)); return }
        if (_state.value.areaDownloadPct != null) return // one area at a time
        val (s, w, n, e, zoom) = listOf(v[0], v[1], v[2], v[3], v[4])
        val minZ = (zoom - 1).coerceIn(0.0, 15.0)
        val maxZ = (zoom + 3).coerceIn(minZ, 16.0)
        val bounds = org.maplibre.android.geometry.LatLngBounds.from(n, e, s, w)
        // The coordinate name is STORED metadata (it is what tells two saved areas apart in the
        // Settings list) - it is deliberately NOT shown in any banner (user 2026-07-23: the raw
        // coords flashing over the progress card read as a second, junk banner).
        val name = "Area near %.2f, %.2f".format((s + n) / 2, (w + e) / 2)
        val label = appContext.getString(R.string.download_label_map_data)
        _state.update { it.copy(areaDownloadPct = 0) }
        // Tile downloads are MapLibre's own machinery (not downloadLaunch), so they hold the
        // background-download keeper directly for their duration (issue #212).
        app.vela.download.DownloadService.begin(appContext, label)
        app.vela.offline.OfflineMaps.download(
            appContext, _state.value.styleUri, bounds, minZ, maxZ, name,
            onCreated = { areaRegion = it },
            onProgress = { pct -> _state.update { st -> st.copy(areaDownloadPct = pct) } },
        ) { reason ->
            areaRegion = null
            app.vela.download.DownloadService.end(appContext, label)
            _state.update { it.copy(areaDownloadPct = null) }
            showStatus(
                appContext.getString(
                    when (reason) {
                        app.vela.offline.OfflineMaps.DoneReason.SAVED -> R.string.offline_area_saved
                        app.vela.offline.OfflineMaps.DoneReason.FAILED -> R.string.offline_area_failed
                        app.vela.offline.OfflineMaps.DoneReason.TOO_LARGE -> R.string.offline_area_too_large
                    },
                ),
            )
        }
        downloadOfflinePois(s, w, n, e)
        downloadRoutingForArea((s + n) / 2, (w + e) / 2)
    }

    /** Saving an area offline also pulls the routing graph for the region that CONTAINS it (if one is
     *  catalogd + not already installed) — so "offline for this area" means map AND navigation, one tap. */
    private fun downloadRoutingForArea(lat: Double, lng: Double) {
        downloadLaunch(appContext.getString(R.string.download_label_map_data)) {
            val regions = _state.value.routingRegions.ifEmpty {
                regionCatalog.manifest(app.vela.BuildConfig.OBF_MANIFEST_URL)
                    .also { rs -> _state.update { it.copy(routingRegions = rs) } }
            }
            // smallest covering box = the specific region for this area (boxes overlap at borders; a big
            // neighbor like British Columbia shouldn't be grabbed for a the metro download)
            val region = regions.filter { it.covers(lat, lng) }
                .minByOrNull { it.boxArea() } ?: return@downloadLaunch
            if (region.id in obfStore.installedIds() || _state.value.routingDownloadingId != null) return@downloadLaunch
            downloadRoutingGraph(region) // shows its own progress + status
        }
        downloadOverlayForArea(lat, lng) // also grab the open building-footprint overlay for this area
        if (app.vela.ui.MapPoiPrefs.placesWithDownloads.value) downloadPlacesForArea(lat, lng) // and the places archive, so the map's businesses show offline
        downloadBasemapForArea(lat, lng) // and the region's basemap, so the map draws past the saved viewport
    }

    /** Download the open building-footprint overlay (Microsoft, ODbL) covering ([lat],[lng]) alongside the
     *  offline map + routing for this area — fills the map's building gaps where OSM is thin. Best-effort +
     *  silent (a background enhancement, not the reason the user tapped download). Smallest covering box wins,
     *  same rule as routing. */
    private fun downloadOverlayForArea(lat: Double, lng: Double) {
        downloadLaunch(appContext.getString(R.string.download_label_map_data)) {
            val regions = overlayStore.manifest(app.vela.BuildConfig.OVERLAY_MANIFEST_URL)
            val region = regions.filter { it.covers(lat, lng) }
                .minByOrNull { (it.n - it.s) * (it.e - it.w) } ?: return@downloadLaunch
            if (region.id in overlayStore.installedIds()) return@downloadLaunch
            overlayStore.download(region) { }
            refreshBuildingOverlays()
        }
    }

    /** Every places archive that belongs to [region]: the ones whose box center falls inside it. The
     *  places catalog is cut finer than the older routing catalog (German states, French regions,
     *  Brazil's five regions), so a whole-country download on that catalog pulls all its pieces, and
     *  a state or province download on the finer catalog pulls just its own. Best-effort and silent. */
    /** The places or basemap archives a region download pulls: the archive with the region's own id,
     *  else every piece whose center lies inside [region] (a country baked in pieces); a region with
     *  no piece of its own inside (a small country inside a bigger box)
     *  still gets the smallest archive covering its center. */
    private fun archivesFor(region: app.vela.offline.RoutingRegion, regions: List<app.vela.offline.PmtilesRegionStore.Region>): List<app.vela.offline.PmtilesRegionStore.Region> {
        // The bakes share region ids, so the matching archive is the answer. The center rule alone
        // also pulled every archive whose center fell in the region's buffered box: a Northern
        // California download took the whole-state places file, a city test bake and Nevada's
        // places and map (1.5 GB for an 800 MB region, 2026-09-17).
        regions.firstOrNull { it.id == region.id }?.let { return listOf(it) }
        val inside = regions.filter { p -> region.covers((p.s + p.n) / 2, (p.w + p.e) / 2) }
        return inside.ifEmpty {
            listOfNotNull(regions.filter { it.covers((region.s + region.n) / 2, (region.w + region.e) / 2) }.minByOrNull { it.area() })
        }
    }

    /** What a region download adds on top of the routing file and place pack: the places archive
     *  (when that setting is on) and the offline map, in MB, per routing region id. The Offline
     *  page and the routing offer add it to their size, which used to show routing and search only
     *  (a Northern California download read 126 MB and installed about 800). */
    private suspend fun regionExtrasMb(routing: List<app.vela.offline.RoutingRegion>): Map<String, Int> {
        val places = if (app.vela.ui.MapPoiPrefs.placesWithDownloads.value) {
            runCatching { placesStore.manifest(app.vela.BuildConfig.PLACES_MANIFEST_URL) }.getOrDefault(emptyList())
        } else emptyList()
        val maps = runCatching { basemapStore.manifest(app.vela.BuildConfig.BASEMAP_MANIFEST_URL) }.getOrDefault(emptyList())
        return routing.associate { r ->
            r.id to ((archivesFor(r, places) + archivesFor(r, maps)).sumOf { it.sizeMb }).toInt()
        }
    }

    private fun downloadPlacesForRegion(region: app.vela.offline.RoutingRegion) {
        downloadLaunch(appContext.getString(R.string.download_label_map_data)) {
            val regions = placesStore.manifest(app.vela.BuildConfig.PLACES_MANIFEST_URL)
            val picks = archivesFor(region, regions)
            var any = false
            for (p in picks) {
                if (p.id in placesStore.installedIds()) continue
                if (placesStore.download(p) { }) any = true
            }
            if (any) refreshPlacesOverlays()
        }
    }

    /** Every basemap archive inside [region] (same containment rule as the places archives): the
     *  streets, land and labels, so the region draws offline. The routing obf is invisible data;
     *  without this a downloaded region is a blank map with pins on it. */
    private fun downloadBasemapForRegion(region: app.vela.offline.RoutingRegion) {
        downloadLaunch(appContext.getString(R.string.download_label_map_data)) {
            val regions = basemapStore.manifest(app.vela.BuildConfig.BASEMAP_MANIFEST_URL)
            val picks = archivesFor(region, regions)
            var any = false
            for (p in picks) {
                if (p.id in basemapStore.installedIds()) continue
                if (basemapStore.download(p) { }) any = true
            }
            if (any) {
                app.vela.offline.GlyphPackStore.ensureInstalled(appContext, http)
                ensureWorldBasemap()
                refreshBasemapArchive()
            }
        }
    }

    /** The whole planet at low zoom, fetched once alongside the first offline download (about
     *  11 MB). It is the floor under the basemap pick: away from a saved region, losing the
     *  network draws a coarse world instead of an empty screen. Best effort and silent - it is an
     *  improvement on nothing, so failing to get it changes nothing. */
    private suspend fun ensureWorldBasemap() {
        runCatching { basemapStore.ensureWorld(app.vela.BuildConfig.WORLD_BASEMAP_URL) }
    }

    /** The smallest basemap archive covering ([lat],[lng]), pulled with a viewport download. */
    private fun downloadBasemapForArea(lat: Double, lng: Double) {
        downloadLaunch(appContext.getString(R.string.download_label_map_data)) {
            val region = basemapStore.manifest(app.vela.BuildConfig.BASEMAP_MANIFEST_URL)
                .filter { it.covers(lat, lng) }
                .minByOrNull { it.area() } ?: return@downloadLaunch
            if (region.id in basemapStore.installedIds()) return@downloadLaunch
            if (basemapStore.download(region) { }) {
                app.vela.offline.GlyphPackStore.ensureInstalled(appContext, http)
                ensureWorldBasemap()
                refreshBasemapArchive()
            }
        }
    }

    /** The open places archive covering ([lat],[lng]), pulled with a viewport download so the map's
     *  businesses draw offline. Best-effort and silent, like the building overlay. */
    private fun downloadPlacesForArea(lat: Double, lng: Double) {
        downloadLaunch(appContext.getString(R.string.download_label_map_data)) {
            val regions = placesStore.manifest(app.vela.BuildConfig.PLACES_MANIFEST_URL)
            val region = regions.filter { it.covers(lat, lng) }
                .minByOrNull { it.area() } ?: return@downloadLaunch
            if (region.id in placesStore.installedIds()) return@downloadLaunch
            placesStore.download(region) { }
            refreshPlacesOverlays()
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
     * view center) so the streamed region follows the map; a failed fetch when offline is harmless (MapLibre
     * just shows no tiles, and any downloaded local overlay still renders). De-duped so panning within one
     * region doesn't churn the map sources.
     */
    private fun refreshBuildingOverlays(center: LatLng? = mapCenter ?: _state.value.myLocation) {
        // Hard off switch (Settings → Advanced → Fill missing buildings). When off, clear any layers
        // already streamed and skip the whole thing - OSM buildings are untouched.
        if (!app.vela.ui.BuildingOverlay.on.value) {
            if (_state.value.buildingOverlays.isNotEmpty()) _state.update { it.copy(buildingOverlays = emptyList()) }
            return
        }
        viewModelScope.launch {
            val installed = overlayStore.installed() // id -> local .pmtiles File
            val uris = installed.values.map { "pmtiles://file://${it.absolutePath}" }.toMutableList()
            center?.let { c ->
                runCatching {
                    val man = overlayManifestCache
                        ?: overlayStore.manifest(app.vela.BuildConfig.OVERLAY_MANIFEST_URL).also { overlayManifestCache = it }
                    // Stream the UNION of covering regions (smallest-first, capped), not just the single
                    // smallest: a neighbor's rectangular bbox can spill across an irregular border AND be
                    // smaller — Kansas's box crosses the Missouri River, covers all of NW Missouri (St Joseph)
                    // and beats Missouri's box, but kansas.pmtiles is EMPTY east of the river → no footprints
                    // (probed: the doll-museum tile has 413 features in missouri.pmtiles, 36 river-bank scraps
                    // in kansas's). With both streamed, whichever archive has the data paints; the empty one's
                    // range requests cost ~nothing. Cap 3 bounds pathological corner overlaps.
                    man.filter { it.covers(c.lat, c.lng) }
                        .sortedBy { it.boxArea() }
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
    /** The open-data places layer for [center] (beta setting): installed archives plus streamed
     *  manifest regions. Empty when the setting is off, which also hands the dots back to Google. */
    /** The installed basemap archive for [center], if any. Cheap (a folder listing), runs with the
     *  places refresh on camera idle and after every download or delete. */
    private fun refreshBasemapArchive(center: LatLng? = mapCenter ?: _state.value.myLocation) {
        // Off the main thread: picking the archive now reads a directory page and one tile out of
        // each candidate file to see whether it actually draws the map there (issue #552). The
        // answers are memoized, so this is a handful of small reads on the first look at an area.
        basemapArchiveJob?.cancel()
        basemapArchiveJob = viewModelScope.launch(Dispatchers.IO) { pickBasemapArchive(center) }
    }

    private var basemapArchiveJob: Job? = null
    private var lastBasemapSwapMs = 0L

    private suspend fun pickBasemapArchive(center: LatLng?) {
        val mountedNow = _state.value.basemapArchive?.removePrefix("pmtiles://file://")?.let { java.io.File(it) }
        val corners = viewport?.let { v -> listOf(LatLng(v[0], v[1]), LatLng(v[0], v[3]), LatLng(v[2], v[1]), LatLng(v[2], v[3])) }.orEmpty()
        // Offline the archive in use is kept while any of the view is still inside it: there is
        // nothing to stream in its place (issue #552, fourth round).
        val file = basemapStore.installedFor(center, mountedNow, corners, keepMounted = _state.value.offline)
        // A SHALLOW archive (baked a zoom level short because the full bake would pass GitHub's
        // 2 GiB asset limit) draws as a blurred version of the same map once you are past its
        // depth, so a download made the map worse than streaming (issue #552). Online, the streamed
        // tiles win; offline it is still far better than an empty screen.
        val shallow = file != null &&
            (basemapStore.maxZoomOf(file) ?: app.vela.offline.BasemapTileStore.FULL_MAP_ZOOM) < app.vela.offline.BasemapTileStore.FULL_MAP_ZOOM
        val usable = file?.takeUnless { shallow && !_state.value.offline }
        if (shallow) android.util.Log.i("VelaBasemap", "installed basemap is shallow (max zoom < ${app.vela.offline.BasemapTileStore.FULL_MAP_ZOOM}); using it only offline")
        val uri = usable?.let { "pmtiles://file://${it.absolutePath}" }
        if (uri != _state.value.basemapArchive) {
            // Swapping the source re-points every basemap layer, which re-tiles and re-lays out the
            // whole map: a visible freeze. At the edge of a downloaded region the honest answer
            // genuinely changes as the view crosses the data, so without a floor on how often that
            // can happen, panning along the border stutters on every camera idle (issue #552,
            // HirschBerge). A newer camera idle cancels this job outright, so waiting here can only
            // ever delay a swap the view still wants.
            val since = android.os.SystemClock.elapsedRealtime() - lastBasemapSwapMs
            if (since < BASEMAP_SWAP_COOLDOWN_MS) kotlinx.coroutines.delay(BASEMAP_SWAP_COOLDOWN_MS - since)
            lastBasemapSwapMs = android.os.SystemClock.elapsedRealtime()
            val fonts = app.vela.offline.GlyphPackStore.installed(appContext)
            android.util.Log.i("VelaBasemap", "offline basemap for the view: ${uri?.substringAfterLast('/') ?: "none"} (glyph pack installed=$fonts)")
            _state.update { it.copy(basemapArchive = uri) }
            // An archive without the glyph pack (an interrupted first download) heals itself the
            // next time there is a connection: labels need the pack, and without it the tiles
            // with labels never complete offline.
            if (uri != null && !fonts) {
                viewModelScope.launch(Dispatchers.IO) { app.vela.offline.GlyphPackStore.ensureInstalled(appContext, http) }
            }
        }
    }

    private var placesLookedUp = false

    private fun refreshPlacesOverlays(center: LatLng? = mapCenter ?: _state.value.myLocation) {
        refreshBasemapArchive(center)
        // "Show places on the map" is the MASTER switch and the open layer has to obey it too
        // (issue #597). It predates this layer, so it only ever cleared the ambient dots and hid
        // the OSM business icons; once the open source became the fleet default, turning the
        // switch off left the very places it is meant to hide still drawn.
        if (!app.vela.ui.MapPoiPrefs.openPlaces || !app.vela.ui.MapPoiPrefs.showPois.value) {
            // placesOneSet goes too: it hides the basemap's parks and temples, and with the open layer
            // off nothing else draws them (a switch to Google places mid-session left them hidden).
            if (_state.value.placesOverlays.isNotEmpty() || _state.value.placesPending || _state.value.placesOneSet) {
                _state.update { it.copy(placesOverlays = emptyList(), placesPending = false, placesOneSet = false) }
            }
            return
        }
        // Only the very first lookup is "pending": the manifest is memoized after it, so later
        // lookups answer at once and a pan never flips the OSM business icons back and forth.
        if (!placesLookedUp) _state.update { it.copy(placesPending = true) }
        viewModelScope.launch {
            val pick = runCatching { placesStore.sourcesFor(center, app.vela.BuildConfig.PLACES_MANIFEST_URL) }
                .getOrDefault(app.vela.offline.PmtilesRegionStore.Pick(emptyList(), 0))
            val uris = pick.uris
            placesLookedUp = true
            // ONE SET OF MAP POINTS (2026-09-22): an archive baked on or after `placesOneSetRev`
            // carries OSM's landmarks, so the basemap's copy of them hides. A calibration dial, off
            // until the world rebake has run (an older archive has no landmarks, and hiding the
            // basemap points over it would lose every park and temple).
            val oneSetRev = app.vela.core.config.CalibrationStore.latest.tune("placesOneSetRev", 99_999_999.0).toInt()
            val oneSet = uris.isNotEmpty() && pick.rev >= oneSetRev
            if (uris != _state.value.placesOverlays || _state.value.placesPending || oneSet != _state.value.placesOneSet) {
                _state.update { it.copy(placesOverlays = uris, placesPending = false, placesOneSet = oneSet) }
            }
        }
    }

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
        // House-number labels are browse furniture for a CAR: during drive-nav the layers are
        // hidden anyway (the declutter effect), so skip the per-viewport manifest/source churn too
        // (battery). Walking/biking keeps them - you navigate TO house numbers on foot.
        if (_state.value.navigating && _state.value.travelMode == TravelMode.DRIVE) return
        viewModelScope.launch {
            runCatching {
                val man = addressManifestCache
                    ?: overlayStore.manifest(app.vela.BuildConfig.ADDRESS_MANIFEST_URL).also { addressManifestCache = it }
                // UNION of covering regions, same rule (and reason) as refreshBuildingOverlays: a spilled
                // rectangular bbox from a neighbor state (Kansas over NW Missouri) can be the smallest cover
                // while its archive is empty there — stream up to the 3 smallest covers so the one with data wins.
                val list = man.filter { it.covers(c.lat, c.lng) }
                    .sortedBy { it.boxArea() }
                    .take(3)
                    .map { "pmtiles://${it.url}" }
                if (list != _state.value.addressOverlays) _state.update { it.copy(addressOverlays = list) }
            }
        }
    }

    private var controlsJob: Job? = null
    // The in-drive stop card's detour check. Cancelled by a newer tap, the confirm and the dismiss.
    private var navTapDetourJob: Job? = null
    private var controlsBox: DoubleArray? = null // [s,w,n,e] of the last fetched (padded) box
    private var flockBox: DoubleArray? = null
    private var transitStopsBox: DoubleArray? = null
    private var transitStopsJob: Job? = null
    private val transitStopCache by lazy { app.vela.data.TransitStopCache(appContext) }
    private val transitBoardCache by lazy { app.vela.data.TransitBoardCache(appContext) }
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
        // During NAV the layer is served by the per-route corridor set (issue #248): the moving camera
        // crossed the cached box edge constantly, and the refetch churn against sometimes-dead mirrors
        // blinked the icons in and out. While a corridor set is loaded (key set), viewport refreshes
        // must neither refetch NOR clear it (the nav zoom floor 15.5 sits under CONTROLS_MIN_ZOOM, so
        // the clear branch below would blank the corridor set at highway speed). A FAILED corridor
        // fetch leaves the key unset and this path keeps running as the fallback.
        if (_state.value.navigating && nav.corridorControlsActive) return
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
            // Re-check ownership AFTER the settle, not just when the job was scheduled (user
            // 2026-09-18: "stoplights and stop signs rendered that probably shouldn't be", off to
            // the side of the route). A viewport settle fires as the camera swings into the drive,
            // its 350 ms settle outlives the flip into navigation, and the write then landed on
            // top of the route-corridor set: 28 controls along the route replaced by 99 across the
            // whole padded box, most of them on streets the driver never touches.
            if (_state.value.navigating && nav.corridorControlsActive) return@launch
            val padLat = (north - south) * 0.5; val padLng = (east - west) * 0.5
            val s = south - padLat; val n = north + padLat; val w = west - padLng; val e = east + padLng
            // null = FETCH FAILED (fetchControlsInBox returns null on network/non-2xx, empty list only on a
            // real empty area) or the job was canceled — either way DON'T cache the box, so the next viewport
            // retries instead of stamping a padded "no controls here" that blanks the layer until the box edge.
            // BAKED FIRST (issue #304): the region's road-features file, downloaded once and read from
            // memory. Overpass only where the manifest has no region for this spot.
            val res = when (roadFeaturesCover(s, w, n, e)) {
                RoadCover.LOADED -> app.vela.data.RoadFeatures.controlsInBox(s, w, n, e).also { android.util.Log.i("VelaControls", "baked box controls=${it.size}") }
                RoadCover.FAILED -> { android.util.Log.i("VelaControls", "road-features download FAILED"); return@launch }
                RoadCover.NONE -> runCatching {
                    withContext(Dispatchers.IO) {
                        app.vela.core.data.OverpassTrafficSignals.fetchControlsInBox(http, s, w, n, e)
                    }
                }.getOrNull() ?: run {
                    android.util.Log.i("VelaControls", "fetch FAILED (all endpoints) z=${"%.1f".format(zoom)}")
                    return@launch
                }
            }
            controlsBox = doubleArrayOf(s, w, n, e)
            // Cap what's HANDED to the map (nearest to the box center wins): a dense metro's padded box can
            // carry 1000+ signals/stop signs, and MapLibre re-collides every handed symbol per drag frame —
            // the same budget-GPU lesson as the ambient-POI cap (don't hand it the whole pool). 400 covers
            // the padded box everywhere reasonable; beyond that the excess would collide off anyway.
            // ONE glyph per intersection, like Google: OSM maps a control node per APPROACH, so a
            // four-way stop arrived as four stop signs. Cluster per type at the same 30 m the
            // spoken "pass the light" counting already uses (adjacent intersections on a dense
            // grid stay separate), each cluster drawn at its centroid. Fewer allowOverlap symbols
            // is also a straight render win.
            val merged = withContext(Dispatchers.Default) {
                res.groupBy { it.kind }.flatMap { (kind, group) ->
                    app.vela.core.data.MapDeclutter.cluster(group, CONTROLS_CLUSTER_M) { it.loc }
                        .map { c -> app.vela.core.data.TrafficControl(c.centroid, kind) }
                }
            }
            val cLat0 = (s + n) / 2; val cLng0 = (w + e) / 2
            val lngScale = kotlin.math.cos(Math.toRadians(cLat0))
            val kept = if (merged.size <= CONTROLS_ONSCREEN_CAP) merged else merged.sortedBy {
                val dLat = it.loc.lat - cLat0; val dLng = (it.loc.lng - cLng0) * lngScale
                dLat * dLat + dLng * dLng
            }.take(CONTROLS_ONSCREEN_CAP)
            // Fetch/merge visibility: like flock's diag line, "controls don't show" reports are
            // only diagnosable when the pipeline says what it actually produced.
            android.util.Log.i("VelaControls", "fetched=${res.size} merged=${merged.size} kept=${kept.size}")
            _state.update { it.copy(trafficControls = kept) }
        }
    }

    /** Whether the baked road-features data covers a spot (issue #304): LOADED = read memory;
     *  NONE = the manifest has no region there, the live Overpass path may answer; FAILED = a
     *  region exists but its file could not be fetched right now (show nothing, retry later). */
    internal enum class RoadCover { LOADED, NONE, FAILED }
    private suspend fun roadFeaturesCover(s: Double, w: Double, n: Double, e: Double): RoadCover =
        roadCoverOf(app.vela.data.RoadFeatures.ensureBox(appContext, app.vela.BuildConfig.ROAD_FEATURES_MANIFEST_URL, s, w, n, e), (s + n) / 2, (w + e) / 2)
    private suspend fun roadFeaturesCoverRoute(poly: List<LatLng>): RoadCover =
        roadCoverOf(app.vela.data.RoadFeatures.ensureAlong(appContext, app.vela.BuildConfig.ROAD_FEATURES_MANIFEST_URL, poly), poly.first().lat, poly.first().lng)
    private suspend fun roadCoverOf(ok: Boolean, lat: Double, lng: Double): RoadCover =
        if (ok) RoadCover.LOADED
        else if (app.vela.data.RoadFeatures.hasRegion(appContext, app.vela.BuildConfig.ROAD_FEATURES_MANIFEST_URL, lat, lng)) RoadCover.FAILED
        else RoadCover.NONE

    /** Re-fetch (or clear) the Flock layer for the current viewport - called when the toggle flips,
     *  so turning it on shows cameras without needing a pan first. */
    fun refreshFlockNow() {
        lastFlockViewport?.let { refreshFlock(it[0], it[1], it[2], it[3], it[4]) }
    }

    private var speedCamBox: DoubleArray? = null
    private var speedCamJob: Job? = null

    /** Re-fetch (or clear) the speed-camera layer when its toggle flips - Flock's twin. */
    fun refreshSpeedCamsNow() {
        lastFlockViewport?.let { refreshSpeedCams(it[0], it[1], it[2], it[3], it[4]) }
    }

    /** Fixed radar/speed cameras for the viewport (issue #229). Mirrors [refreshFlock]'s contract
     *  minus the bundled dataset: opt-in, sparse-landmark zoom floor, padded area cache, 350 ms
     *  settle, failure never cached so the next viewport retries. */
    private fun refreshSpeedCams(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        if (!app.vela.ui.SpeedCams.on.value || zoom < FLOCK_MIN_ZOOM) {
            speedCamBox = null
            speedCamJob?.cancel()
            if (_state.value.speedCameras.isNotEmpty()) _state.update { it.copy(speedCameras = emptyList()) }
            return
        }
        val cLat = (south + north) / 2; val cLng = (west + east) / 2
        speedCamBox?.let { b ->
            val insLat = (b[2] - b[0]) * 0.25; val insLng = (b[3] - b[1]) * 0.25
            if (cLat in (b[0] + insLat)..(b[2] - insLat) && cLng in (b[1] + insLng)..(b[3] - insLng)) return
        }
        speedCamJob?.cancel()
        speedCamJob = viewModelScope.launch {
            delay(350)
            val padLat = (north - south) * 0.5; val padLng = (east - west) * 0.5
            val s = south - padLat; val n = north + padLat; val w = west - padLng; val e = east + padLng
            val cover = roadFeaturesCover(s, w, n, e)
            val res = when (cover) {
                RoadCover.LOADED -> app.vela.data.RoadFeatures.camerasInBox(s, w, n, e)
                RoadCover.FAILED -> null
                RoadCover.NONE -> withContext(Dispatchers.IO) {
                    runCatching { app.vela.core.data.OverpassSpeedCameras.fetchInBox(http, s, w, n, e) }.getOrNull()
                }
            }
            if (res == null) {
                diag.record("speedcam", "camera fetch failed at z${"%.1f".format(zoom)}", "box [$s,$w,$n,$e] source=${cover.name.lowercase()}")
                return@launch
            }
            speedCamBox = doubleArrayOf(s, w, n, e)
            diag.record("speedcam", "showing ${res.size} camera(s) at z${"%.1f".format(zoom)}", if (cover == RoadCover.LOADED) "baked" else "overpass")
            _state.update { it.copy(speedCameras = res.take(600)) }
        }
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
        satDeepBox = null
        viewport?.let {
            refreshImageryYear(it[0], it[1], it[2], it[3])
            refreshSatDeep(it[0], it[1], it[2], it[3], it[4])
        }
    }

    private var satDeepBox: DoubleArray? = null
    private var satDeepJob: kotlinx.coroutines.Job? = null

    /** Deep-zoom satellite availability for the viewport (issue #244). The base imagery source is
     *  capped at z19 (Esri's safe global max), so past that the renderer just stretched the z19
     *  tile - the "blurry and unusable" report. Esri actually serves native z20+ in many areas and
     *  publishes per-tile availability on the same service (`tilemap`), so this probes 22→21→20 at
     *  the view center and reports the deepest level with data; where Esri tops out at 19 the map
     *  falls back to Google's imagery tiles for the deep zooms (Google upsamples rather than 404s
     *  outside cities, so the fallback never paints holes). Area-cached like the other viewport
     *  probes; a fetch failure caches nothing so the next idle retries. */
    private fun refreshSatDeep(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        if (!app.vela.ui.SatelliteLayer.on.value) {
            satDeepBox = null
            satDeepJob?.cancel()
            if (_state.value.satDeep != 0) _state.update { it.copy(satDeep = 0) }
            return
        }
        // Only probe when close enough that deep tiles are about to matter; keep whatever the
        // last probe found while zoomed out (the deep layers gate themselves by minZoom anyway).
        if (zoom < SAT_DEEP_PROBE_ZOOM) return
        val cLat = (south + north) / 2; val cLng = (west + east) / 2
        satDeepBox?.let { b ->
            val insLat = (b[2] - b[0]) * 0.25; val insLng = (b[3] - b[1]) * 0.25
            if (cLat in (b[0] + insLat)..(b[2] - insLat) && cLng in (b[1] + insLng)..(b[3] - insLng)) return
        }
        satDeepJob?.cancel()
        satDeepJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            val level = withContext(Dispatchers.IO) {
                runCatching {
                    // Bottom-up: 20 first, because everywhere Esri stops at 19 (most of the
                    // world outside metros) one request settles the Google fallback, where
                    // 22-then-21-then-20 spent three sequential round trips on the blur. Only
                    // areas that DO have z20 pay for the z21/z22 checks. ensureActive between
                    // requests, or a probe canceled by the next pan keeps burning the chain.
                    var found = -1
                    for (lvl in intArrayOf(20, 21, 22)) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        if (!esriTileExists(cLat, cLng, lvl)) break
                        found = lvl
                    }
                    found
                }.getOrNull()
            } ?: return@launch // network failure: don't cache the box, retry on the next idle
            val padLat = (north - south) * 0.5; val padLng = (east - west) * 0.5
            satDeepBox = doubleArrayOf(south - padLat, west - padLng, north + padLat, east + padLng)
            if (_state.value.satDeep != level) _state.update { it.copy(satDeep = level) }
        }
    }

    /** One cell of Esri's World_Imagery `tilemap` availability index: 1 = a native tile exists at
     *  this level here. Throws on network/non-2xx so the caller can tell "no tile" from "no answer". */
    private fun esriTileExists(lat: Double, lng: Double, level: Int): Boolean {
        val n = 1 shl level
        val x = ((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat)
        val y = ((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n)
            .toInt().coerceIn(0, n - 1)
        val url = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tilemap/$level/$y/$x/1/1?f=json"
        val body = http.newCall(okhttp3.Request.Builder().url(url).build()).execute()
            .use { resp -> if (resp.isSuccessful) resp.body?.string() else throw java.io.IOException("tilemap ${resp.code}") }
            ?: throw java.io.IOException("tilemap empty body")
        return org.json.JSONObject(body).optJSONArray("data")?.optInt(0, 0) == 1
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
        // No stop icons during turn-by-turn (user drive 2026-07-14) - the layer hides in the nav
        // declutter effect, and skipping the fetch here saves the per-viewport Transitous calls a
        // whole drive would otherwise fire at nav zoom.
        if (_state.value.navigating) return
        // Transit-stop toggle (user 2026-07-15): treat "off" like zoomed-out - clear + skip.
        if (!app.vela.ui.MapPoiPrefs.showTransit.value || zoom < TRANSIT_STOPS_MIN_ZOOM) {
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
        if (_state.value.navigating) return // dead during a live drive, like onPoiTap
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
                stopDeparturesFor = placeholder.id,
                reviewsLoading = false,
                reviewsFound = 0,
                loadingDetails = false,
                photosLoading = false,
                pickingOrigin = false, pickingDest = false,
                pickingStop = false,
                directionsOpen = false,
            )
        }
        if (offlineNow()) { showCachedBoard(placeholder); return }
        viewModelScope.launch {
            val board = withContext(Dispatchers.IO) {
                runCatching { app.vela.core.data.transit.Transitous.boardFor(http, stop) }.getOrNull()
                    ?.also { if (it.lines.isNotEmpty()) transitBoardCache.put(stop.lat, stop.lon, it) }
            }
            _state.update { st ->
                if (st.selected?.id != placeholder.id) st
                else st.copy(stopDepartures = board?.takeIf { it.lines.isNotEmpty() }, stopDeparturesLoading = false, stopDeparturesFor = placeholder.id, stopDeparturesCachedAt = null)
            }
            if (board != null && board.lines.isNotEmpty()) startBoardRefresh(placeholder.id, stop.lat, stop.lon)
        }
    }

    /** ALPR/Flock cameras for the viewport, when the layer is on. Mirrors [refreshTrafficControls]:
     *  high-zoom only, area-cached (cameras are static), 350 ms debounced, failure not cached. */
    private fun refreshFlock(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
        // ALPR cameras are SPARSE landmarks people want from a neighborhood view (the way
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

    /** Cap the drawn cameras to the [CONTROLS_ONSCREEN_CAP] NEAREST the box center (a dense metro cell can
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

    private var routingOfferChecked = false

    /** Offer, once ever, the routing download for the region around Home (or around you when no
     *  Home is saved): with it installed, a mid-drive reroute can race the on-device engine
     *  against the network instead of waiting on it, and directions work with no signal. Asked
     *  after setup, on the bare map, online, and never again once answered or already installed. */
    private fun maybeOfferRouting() {
        if (routingOfferChecked) return
        val prefs = appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean(ROUTING_OFFER_DONE, false)) { routingOfferChecked = true; return }
        val ob = app.vela.ui.Onboarding
        if (!ob.welcomeDone.value || ob.showLocationPrompt.value || ob.showNotifPrompt.value || ob.showVoicePrompt.value || ob.showDonatePrompt.value) return
        val s = _state.value
        if (s.navigating || s.directionsOpen || s.selected != null || s.results.isNotEmpty() || s.offline) return
        if (s.routingDownloadingId != null) return
        val anchor = s.home?.let { LatLng(it.lat, it.lng) } ?: s.myLocation ?: return
        routingOfferChecked = true
        viewModelScope.launch {
            val regions = runCatching { regionCatalog.manifest(app.vela.BuildConfig.OBF_MANIFEST_URL) }.getOrDefault(emptyList())
            if (regions.isEmpty()) { routingOfferChecked = false; return@launch } // try again on a later idle
            val region = regions.filter { it.covers(anchor.lat, anchor.lng) }
                .minByOrNull { it.boxArea() } ?: run { markRoutingOfferDone(); return@launch }
            if (region.id in obfStore.installedIds()) { markRoutingOfferDone(); return@launch }
            if (_state.value.poiPackRegions.isEmpty()) {
                val packs = runCatching { poiPackStore.manifest(app.vela.BuildConfig.POI_PACK_MANIFEST_URL) }.getOrDefault(emptyList())
                _state.update { it.copy(poiPackRegions = packs) }
            }
            val extras = regionExtrasMb(listOf(region))
            _state.update { it.copy(regionExtrasMb = it.regionExtrasMb + extras, routingOffer = region) }
        }
    }

    private fun markRoutingOfferDone() {
        appContext.getSharedPreferences("vela_settings", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(ROUTING_OFFER_DONE, true).apply()
    }

    fun answerRoutingOffer(download: Boolean) {
        val region = _state.value.routingOffer ?: return
        markRoutingOfferDone()
        _state.update { it.copy(routingOffer = null) }
        if (download) downloadRoutingGraph(region)
    }

    /** Reflect what's installed + fetch the obf region catalog. */
    fun refreshRoutingRegions() {
        _state.update { it.copy(routingInstalledIds = obfStore.installedIds()) }
        viewModelScope.launch {
            val regions = regionCatalog.manifest(app.vela.BuildConfig.OBF_MANIFEST_URL)
            _state.update { it.copy(routingRegions = regions) }
            val extras = regionExtrasMb(regions)
            _state.update { it.copy(regionExtrasMb = extras) }
            // The pack catalog too (revs + deltas) — Settings compares it against the installed pack
            // revisions to offer "Update places" on stale regions.
            refreshRegionUpdates()
            val packs = poiPackStore.manifest(app.vela.BuildConfig.POI_PACK_MANIFEST_URL)
            _state.update {
                it.copy(
                    poiPackRegions = packs,
                    poiPackInstalledRevs = poiPackStore.installedIds().associateWith { id -> poiPackStore.installedRev(id) },
                )
            }
        }
    }

    /** Download + install [region]'s obf for fully-offline routing in that area, then the
     *  region's PLACE pack (whole-region POIs + addresses) so search/geocoding covers it offline too. */
    fun downloadRoutingGraph(region: app.vela.offline.RoutingRegion) {
        if (_state.value.routingDownloadingId != null) return
        regionCancel.set(false)
        _state.update { it.copy(routingDownloadingId = region.id, routingDownloadPct = 0, regionDownloadName = region.name) }
        downloadLaunch(region.name) {
            val ok = obfStore.download(region, active = { !regionCancel.get() }) { pct ->
                _state.update { it.copy(routingDownloadPct = pct) }
            }
            if (ok) obfStore.writeRev(region.id, region.rev)
            _state.update {
                it.copy(routingDownloadingId = null, routingInstalledIds = obfStore.installedIds())
            }
            if (ok || !regionCancel.get()) { // canceled = quiet; the card going away is the feedback
                showStatus(if (ok) appContext.getString(R.string.mapvm_offline_routing_ready, region.name) else appContext.getString(R.string.mapvm_offline_routing_failed))
            }
            // The place pack still rides along until search moves onto the obf too.
            if (ok) {
                downloadPoiPack(region)
                // The Vela places archive for the region rides along (Settings > Offline maps toggle,
                // on by default), so the map's businesses draw with no signal, not just search.
                if (app.vela.ui.MapPoiPrefs.placesWithDownloads.value) downloadPlacesForRegion(region)
                downloadBasemapForRegion(region) // the map itself: a region without it is blank offline
            } else _state.update { it.copy(regionDownloadName = null) }
            // A "download all" batch continues with the next piece (the queue is empty otherwise).
            startNextQueuedRegion()
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
        if (!ok && !regionCancel.get()) { // no delta path (or it failed) → full download replaces the pack
            ok = poiPackStore.download(pack, active = { !regionCancel.get() }) { pct -> _state.update { it.copy(poiPackDownloadPct = pct) } }
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
    /** Which installed pieces of each region have a newer bake in their manifests: the routing obf
     *  (rev on the catalog row), the places archives and the basemap archives whose box centers sit
     *  inside the region. Runs with the catalog refresh (Offline maps open) and after an update. */
    private suspend fun refreshRegionUpdates() {
        val regions = _state.value.routingRegions
        val places = runCatching { placesStore.updatable(placesStore.manifest(app.vela.BuildConfig.PLACES_MANIFEST_URL)) }.getOrDefault(emptyList())
        val maps = runCatching { basemapStore.updatable(basemapStore.manifest(app.vela.BuildConfig.BASEMAP_MANIFEST_URL)) }.getOrDefault(emptyList())
        val out = HashMap<String, MutableList<String>>()
        for (r in regions) {
            if (r.id !in _state.value.routingInstalledIds) continue
            val kinds = ArrayList<String>()
            if (r.rev > obfStore.installedRev(r.id) && obfStore.installedRev(r.id) > 0) kinds += "routing"
            fun inside(s: Double, w: Double, n: Double, e: Double) = r.covers((s + n) / 2, (w + e) / 2)
            if (places.any { inside(it.s, it.w, it.n, it.e) }) kinds += "places"
            if (maps.any { inside(it.s, it.w, it.n, it.e) }) kinds += "map"
            if (kinds.isNotEmpty()) out[r.id] = kinds
        }
        _state.update { it.copy(regionUpdates = out) }
    }

    /** Refresh everything installed for [region] that has a newer bake: the place pack (delta when
     *  offered), then every places and basemap archive inside the region, then the routing obf. One
     *  tap on the row's Update button; the progress card shows the region's name throughout. */
    /**
     * Bring one archive up to the manifest's revision: the published delta when there is one that
     * fits what is installed, the whole file otherwise.
     *
     * Every attempt is recorded (diagnostics ring, kind "delta", plus logcat VelaDelta) with what
     * it cost and why it fell back, because the failure worth seeing is not a crash: it is a region
     * that silently downloads itself whole every week while a patch sits published beside it.
     */
    private suspend fun refreshArchive(
        store: app.vela.offline.PmtilesRegionStore,
        region: app.vela.offline.PmtilesRegionStore.Region,
    ) {
        val note: (String) -> Unit = { line ->
            app.vela.ui.RegionUpdates.lastResult.value = line
            diag.record("delta", line)
            android.util.Log.d("VelaDelta", line)
        }
        if (region.delta != null && app.vela.ui.RegionUpdates.allowedNow(appContext)) {
            if (store.updateWithDelta(region, onProgress = { }, log = note)) { forgetOpenPlaceLinks("${region.id} updated"); return }
        } else if (region.delta != null) {
            note("${region.id}: delta available but updates are ${app.vela.ui.RegionUpdates.mode.value.name.lowercase()} on this connection")
        }
        val size = region.sizeMb
        // Over the installed copy, never after deleting it: a failed download keeps the region.
        val ok = store.download(region, replace = true) { }
        if (ok) forgetOpenPlaceLinks("${region.id} redownloaded")
        note("${region.id}: full download of ${"%.0f".format(size)} MB ${if (ok) "done" else "FAILED"}")
    }

    fun updateRegion(region: app.vela.offline.RoutingRegion) {
        if (_state.value.poiPackDownloadingId != null || _state.value.routingDownloadingId != null) return
        regionCancel.set(false)
        downloadLaunch(region.name) {
            val kinds = _state.value.regionUpdates[region.id].orEmpty()
            val packRegion = _state.value.poiPackRegions.firstOrNull { it.id == region.id }
            if (packRegion != null && region.id in poiPackStore.installedIds() && packRegion.rev > poiPackStore.installedRev(region.id)) {
                downloadPoiPack(region, update = true)
            }
            fun inside(s: Double, w: Double, n: Double, e: Double) = region.covers((s + n) / 2, (w + e) / 2)
            if ("places" in kinds) {
                placesStore.updatable(placesStore.manifest(app.vela.BuildConfig.PLACES_MANIFEST_URL))
                    .filter { inside(it.s, it.w, it.n, it.e) }
                    .forEach { if (!regionCancel.get()) refreshArchive(placesStore, it) }
                refreshPlacesOverlays()
            }
            if ("map" in kinds) {
                basemapStore.updatable(basemapStore.manifest(app.vela.BuildConfig.BASEMAP_MANIFEST_URL))
                    .filter { inside(it.s, it.w, it.n, it.e) }
                    .forEach { if (!regionCancel.get()) refreshArchive(basemapStore, it) }
                refreshBasemapArchive()
            }
            if ("routing" in kinds && !regionCancel.get()) {
                _state.update { it.copy(routingDownloadingId = region.id, routingDownloadPct = 0, regionDownloadName = region.name) }
                val ok = obfStore.download(region, active = { !regionCancel.get() }) { pct -> _state.update { it.copy(routingDownloadPct = pct) } }
                if (ok) obfStore.writeRev(region.id, region.rev)
                _state.update { it.copy(routingDownloadingId = null, routingInstalledIds = obfStore.installedIds()) }
            }
            _state.update { it.copy(regionDownloadName = null) }
            refreshRegionUpdates()
        }
    }

    fun downloadPoiPackFor(region: app.vela.offline.RoutingRegion, update: Boolean = false) {
        if (_state.value.poiPackDownloadingId != null || _state.value.routingDownloadingId != null) return
        regionCancel.set(false)
        downloadLaunch(region.name) {
            val available = poiPackStore.manifest(app.vela.BuildConfig.POI_PACK_MANIFEST_URL)
                .any { it.id == region.id }
            if (!available) {
                showStatus(appContext.getString(R.string.mapvm_poipack_unavailable, region.name))
                return@downloadLaunch
            }
            downloadPoiPack(region, update = update)
        }
    }

    /** The routing manifest's bbox `[S,W,N,E]` for region [id], from the cached manifest, or null. */
    private fun routingRegionBox(id: String): DoubleArray? =
        overlayManifestCache?.firstOrNull { it.id == id }?.let { doubleArrayOf(it.s, it.w, it.n, it.e) }

    fun deleteRoutingGraph(id: String) {
        obfStore.delete(id)
        poiPackStore.delete(id) // the place pack rides with the region — remove them together
        // The open places archives that came with this region go too: any whose bbox center sits
        // inside the region's box, plus a same-id archive.
        runCatching {
            val box = routingRegionBox(id)
            (listOf(id) + (box?.let { placesStore.idsInside(it[0], it[1], it[2], it[3]) } ?: emptyList()))
                .distinct().forEach { placesStore.delete(it) }
            (listOf(id) + (box?.let { basemapStore.idsInside(it[0], it[1], it[2], it[3]) } ?: emptyList()))
                .distinct().forEach { basemapStore.delete(it) }
        }
        refreshPlacesOverlays()
        (routeEngine as? app.vela.core.data.ObfRouteEngine)?.shutdown() // drop cached readers for the removed region
        _state.update {
            it.copy(routingInstalledIds = obfStore.installedIds(), poiPackInstalledIds = poiPackStore.installedIds())
        }
        showStatus(appContext.getString(R.string.mapvm_offline_routing_removed))
    }

    /** When a map region is downloaded for offline use, also pull its POIs from
     *  OSM/Overpass into the on-device index so search works there with no signal. */
    fun downloadOfflinePois(south: Double, west: Double, north: Double, east: Double) {
        downloadLaunch(appContext.getString(R.string.download_label_offline_places)) {
            // PACK FIRST (issue #304, 2026-09-13). The place pack for the region that contains this
            // area is built on CI from a Geofabrik extract and already holds every POI, address and
            // street the Overpass queries below used to fetch live, for the whole region rather
            // than a 15 km box. Saving an area also pulls the region's graph, and the graph's
            // completion pulls its pack, so where a pack exists in the catalog the public Overpass
            // servers are not asked at all. The live path survives only for an area no pack
            // covers, which after the world catalog is nowhere Geofabrik publishes.
            val cLat0 = (south + north) / 2.0
            val cLng0 = (west + east) / 2.0
            val pack = runCatching { poiPackStore.manifest(app.vela.BuildConfig.POI_PACK_MANIFEST_URL) }.getOrDefault(emptyList())
                .filter { it.covers(cLat0, cLng0) }
                .minByOrNull { it.boxArea() }
            if (pack != null) {
                val graphHere = pack.id in obfStore.installedIds()
                when {
                    pack.id in poiPackStore.installedIds() -> Unit // already searchable offline
                    // Region installed before packs existed (or the pack download failed): fetch it now.
                    graphHere -> downloadPoiPackFor(pack)
                    // Otherwise the region download this save triggered brings the pack with it.
                    else -> showStatus(appContext.getString(R.string.mapvm_area_uses_pack, pack.name))
                }
                return@downloadLaunch
            }
            val pois = withContext(Dispatchers.IO) { OverpassPois.fetch(http, south, west, north, east) }
            if (pois.isNotEmpty()) {
                withContext(Dispatchers.IO) { offlinePoiStore.add(pois) }
                showStatus(appContext.getString(R.string.mapvm_saved_places_offline, pois.size))
            }
            // Also pull the address data so offline search can GEOCODE an arbitrary typed address and route
            // to it. Geocoding wants coverage well beyond the few blocks of tiles on screen, so this fetch
            // is PADDED to a ~15 km minimum span around the viewport center — a downloaded area then routes
            // to an address across the whole metro, not just what was visible. Two OSM sources:
            //   • addr:housenumber points → house-precise where mapped,
            //   • named road centerlines → street-level fallback where OSM has the road but no house numbers
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
        /** What a place tap loads before "More photos" / All reviews (2026-09-23, FullPlaceLoad off). */
        const val FIRST_PHOTOS = 6
        const val FIRST_REVIEWS = 10
        const val PHOTOS_CACHE_MS = 6 * 3_600_000L
        const val REVIEWS_CACHE_MS = 6 * 3_600_000L
        const val DETAILS_CACHE_MS = 15 * 60_000L
        /** How long the in-drive stop card waits for its detour figure. The card is already on
         *  screen; past this the offer simply carries no minutes rather than holding a stale
         *  spinner over a drive. */
        private const val NAV_DETOUR_TIMEOUT_MS = 8_000L

        /** Past this from the trip's chosen start, Start re-plans from where you are (issue #463). */
        private const val START_FROM_ME_M = 150.0
        private const val ROUTING_OFFER_DONE = "routing_offer_done"
        const val KEY_DISMISSED = "dismissed"
        const val CONTROLS_MIN_ZOOM = 16.0 // draw traffic lights/stop signs only when zoomed in this close
        /** The least time between two offline-basemap source swaps. A swap re-points every basemap
         *  layer and re-lays out the map, so at a downloaded region's edge this is what keeps a pan
         *  along the border from stuttering on every camera idle (issue #552). */
        const val BASEMAP_SWAP_COOLDOWN_MS = 2_000L
        const val SAT_DEEP_PROBE_ZOOM = 17.0 // probe deep-imagery availability once this close (tiles ready before the blur)
        // One glyph per intersection: per-approach OSM nodes within this radius merge before draw.
        // 30 m was the spoken pass-the-light radius and it is too tight for a real four-way, where
        // the stop line on each approach sits well back from the middle: a wide junction drew two
        // lights where one in the center says the same thing (user 2026-09-18, from a drive). 45 m
        // still keeps the next junction down a dense grid block separate.
        const val CONTROLS_CLUSTER_M = 45.0
        const val CONTROLS_ROUTE_CAP = 800 // max controls from a route-corridor fetch (nearest-to-start wins) —
        // the corridor is thin so a whole drive stays modest; this is a dense-metro backstop, and the layer's
        // symbols are allowOverlap (no per-frame collision), so the cap can sit above the viewport one.
        // Show ALPR cameras from ROUTE-OVERVIEW zoom (~z11-12), the "I know this route has cameras but
        // don't see any" view. z11 was tried 2026-07-13 and reverted the same day because the padded
        // Overpass box (~16x bigger) with a full-body read + full-DOM parse per pan OOM'd the heap; the
        // follow-up note said route-overview visibility needed a path without the giant box. That path
        // exists now: the BUNDLED on-device dataset answers fetchInBox with no network and the Overpass
        // fallback stream-parses, so z11 is back (alltechdev re-proved it in the vela-dpad fork, #131).
        const val FLOCK_MIN_ZOOM = 11.0
        val CIVIC_GROUPS = setOf("park", "edu", "civic") // the "not really a business" ambient tier
        const val SUGGEST_NEAR_M = 80_000.0 // ~a metro radius: suggestions inside it rank first
        /** The automatic region patch pass: a minute after start, at most once in 20 hours. */
        const val AUTO_PATCH_DELAY_MS = 60_000L
        const val AUTO_PATCH_EVERY_MS = 20 * 60 * 60 * 1000L
        const val KEY_AUTO_PATCH_AT = "region_autopatch_at"
        const val TRANSIT_STOPS_MIN_ZOOM = 15.0 // GTFS stop icons from street-ish zoom (denser than cameras)
        const val CONTROLS_ONSCREEN_CAP = 400 // max controls handed to the map (nearest-to-center wins) — a
                                              // dense metro's padded box can carry 1000+, and every handed
                                              // symbol is re-collided per drag frame (budget-GPU jank)
        const val STALE_LOCATION_MS = 12_000L // gray the dot after this long with no fix
        const val SPEED_HOLD_MS = 3_000L // hold a speedless-fix speed at most this long, then show 0
        const val SPEED_ZERO_MS = 6_000L // no fixes AT ALL for this long → zero the mph. Two full cycles
                                         // of the worst normal chipset cadence (~3 s under canopy) — at
                                         // 3 s the zeroer fired BETWEEN ordinary fixes (56→0→56 flicker)
        const val NETWORK_FIX_QUIET_MS = 12_000L // use a NETWORK fix (dot only) when GPS has been quiet
                                                 // this long (OsmAnd's NOT_SWITCH_TO_NETWORK window)
        const val NAV_STARVED_MS = 10_000L // navigating without a guidance-quality fix this long → chip
        // Tunnel dead reckoning (route-constrained): when the GPS feed stops mid-drive while
        // solidly on-route, keep advancing along the route at the last speed (decaying) so the
        // puck, banner, ETA and voice keep working through the outage - Google's behavior.
        const val DR_START_MS = 3_500L    // feed gap before synthesis starts (the view's own 3 s blind reckon covers less)
        const val DR_DECAY_S = 60.0       // the assumed speed decays with this tau (no evidence we're still moving)
        const val DR_MIN_SPEED = 1.5      // stop synthesizing below this (and never start from a standstill)
        const val DR_MAX_M = 3_000.0      // hard cap on blind travel - longer than any common tunnel, short enough to bound a wrong guess
        const val SPEED_LIMIT_FORGET_M = 300.0 // drive this far past the last KNOWN limit with only
                                               // untagged snaps → clear the badge (don't show a stale limit)
        const val OFFLINE_ADDR_FILL = 20      // offline search rows whose blank address is filled from the index
        const val OFFLINE_AT_ADDR_M = 40.0    // a POI this close to a typed address is "at" it
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
        // Ambient on-screen cap is ZOOM-TIERED, Google-style (2026-07-17): Google shows a handful of
        // POIs at a wide browse zoom and reveals more as you zoom in. We used a flat 140, so a dense
        // downtown re-ran symbol-collision placement over ~140 icons+labels EVERY drag frame on the
        // main thread = the "so many dots, laggy" report. The cap now ramps 45 (wide, ambient fetch
        // floor z14) -> 140 (z17.5+), cutting the per-frame collision ~3x exactly where it hurts
        // (zoomed-out) while keeping full detail zoomed in. NB Vela's MapLibre zoom reads ~1 below
        // Google's for the same extent (512px tiles), so z14 here ~ Google z15.
        const val AMBIENT_ONSCREEN_CAP = 140
        const val AMBIENT_ONSCREEN_CAP_MIN = 45
        fun ambientCap(zoom: Double): Int {
            // Both ends of the zoom-tiered cap are fleet-tunable through calibration.json
            // ("ambientCapMin" / "ambientCapMax") - the direct dial if a device class ever
            // needs fewer symbols without an app release.
            val lo = app.vela.core.config.CalibrationStore.latest.tune("ambientCapMin", AMBIENT_ONSCREEN_CAP_MIN.toDouble())
            val hi = app.vela.core.config.CalibrationStore.latest.tune("ambientCapMax", AMBIENT_ONSCREEN_CAP.toDouble())
            return (lo + ((zoom - 14.0).coerceIn(0.0, 3.5) / 3.5) * (hi - lo)).toInt()
        }
        // Half-span (degrees) the offline geocoder's address/street fetch is padded to around the viewport
        // center — ~10 km lat each way (a bit less in lng at mid-latitudes), so a downloaded area can route
        // to an arbitrary address across the surrounding metro, not just the blocks that were on screen.
        const val GEOCODE_PAD_DEG = 0.09
    }
}

/** Smallest absolute angle between two compass bearings, in degrees (0..180). */
private fun angleDiff(a: Double, b: Double): Double =
    kotlin.math.abs(((a - b + 540.0) % 360.0) - 180.0)
