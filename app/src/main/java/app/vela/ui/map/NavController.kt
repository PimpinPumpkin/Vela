package app.vela.ui.map

import android.content.Context
import app.vela.R
import app.vela.core.data.MapDataSource
import app.vela.core.location.LocationProvider
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavSession
import app.vela.core.voice.VoiceGuide
import app.vela.service.NavigationService
import app.vela.voice.PiperSynth
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The nav side of the map screen, carved out of MapViewModel (issue #417, refactor 3, step 1):
 * starting, stopping, demo drives and trip replays, the nav-state observer that mirrors
 * [NavSession] into [MapUiState], tunnel dead reckoning, the per-route corridor fetches (controls,
 * speed cameras), the spoken warnings, the route bar and resume-after-process-death.
 *
 * State stays in the shared [MapUiState] flow the view model owns; what the nav code needs from
 * the rest of the view model (live GPS on/off, status cards, the speed-limit badge, route naming)
 * comes through [Host], so this class never reaches into the view model. Construct it ABOVE the
 * view model's `init`: [bind] launches the observer, whose first pass runs inline.
 */
internal class NavController(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val _state: MutableStateFlow<MapUiState>,
    private val navSession: NavSession,
    private val locationProvider: LocationProvider,
    private val dataSource: MapDataSource,
    private val tripStore: app.vela.replay.TripStore,
    private val voice: VoiceGuide,
    private val diag: app.vela.core.diag.DiagLog,
    private val http: okhttp3.OkHttpClient,
    private val host: Host,
) {
    /** What the nav code needs from the view model. */
    interface Host {
        var destination: LatLng?
        var controlsBox: DoubleArray?
        var autoStartOnRoute: Boolean
        fun startLocation()
        fun pauseLiveLocation()
        fun restartStaleTimer()
        fun flashStatus(msg: String, millis: Long = 4500L)
        fun showStatus(msg: String, voiceAction: Boolean = false)
        fun updateSpeedLimit(here: LatLng)
        fun clearSpeedLimit()
        fun clearSelection()
        fun neuralSynthFor(engineId: String?): PiperSynth?
        suspend fun nameIfNeeded(route: Route): Route
        suspend fun roadFeaturesCoverRoute(poly: List<LatLng>): MapViewModel.RoadCover
        fun sanePosition(here: LatLng, prev: LatLng?, lastSpeed: Float?, dt: Double, outlierStreak: IntArray): LatLng
        fun gateMeasuredSpeed(raw: Float, dt: Double): Float?
        fun onNavRoadLatin(map: Map<String, String>)
    }

    private val settingsPrefs = appContext.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private var replayJob: Job? = null
    private var replayOwnsNav = false // a replay auto-started the nav session → tear it down on end/supersede
    private var lastRecordedRoute: app.vela.core.model.Route? = null // last route block written to the
                                                                     // active trip (route swaps append)
    // Nav resume across process death: persist just the DESTINATION (+ label/mode) when nav starts, so if
    // the OS reaps the backgrounded process mid-drive (Android-14 FGS-location limits on GrapheneOS), the
    // next launch can offer to resume - re-fetching a FRESH route from wherever you are now. Route isn't
    // serialized; re-routing from the current fix is simpler + handles the distance you covered while away.
    private val navResumePrefs = appContext.getSharedPreferences("vela_nav_resume", Context.MODE_PRIVATE)
    private var resumeDest: LatLng? = null   // stashed target for resumeNav() after maybeOfferResume()
    private var resumeMode: TravelMode = TravelMode.DRIVE
    private var lastNavHeartbeatMs = 0L       // last time we refreshed the persisted-nav "at" timestamp (see MapViewModel.NAV_HEARTBEAT_MS)
    private val speeding = app.vela.core.nav.SpeedingAlerts()

    private var drProgressM = Double.NaN
    private var drSpeed = 0.0
    private var drTotalM = 0.0
    private var drLastMs = 0L
    /** Last time a guidance-quality fix fed navSession; the view model's location collector writes it,
     *  the tunnel dead-reckon loop reads it to detect a quiet feed. */
    @Volatile var lastNavFedMs = 0L

    /** Whether a route-corridor controls fetch owns [MapUiState.trafficControls] for this drive. */
    val corridorControlsActive: Boolean get() = navControlsKey != null

    /** Launch the observers. Called once from the view model's init; the nav-state observer's first
     *  pass runs inline, so everything it touches is declared above this call. */
    fun bind() {
        // Tunnel dead reckoning: keeps nav estimating along the route when GPS drops (see the
        // loop's own comment). Runs for the process lifetime; every tick self-gates on nav state.
        scope.launch { tunnelDeadReckonLoop() }
        scope.launch {
            navSession.state.collect { ns ->
                // Persist the recorded trip the instant we arrive, so it survives even if
                // the user never taps "Done" on the arrival card. finishTrip is idempotent,
                // so the later Done → stopNav → finishTrip is a harmless no-op.
                val justArrived = ns.arrived && !_state.value.arrived
                val navStarted = ns.navigating && !_state.value.navigating
                // Record LIVE route swaps (reroute / accepted faster route) into the active trip
                // as a new RP/RD/M block at the current fix position - without this the saved trip
                // held only the start route while the drive continued on another, and a replay/
                // audit diffed the trace against a route the driver wasn't on ("arrow on another
                // street"). TripLog parses the blocks as segments; replay swaps at the same spot.
                val nsRoute = ns.route
                if (ns.navigating && nsRoute != null && nsRoute !== lastRecordedRoute) {
                    if (lastRecordedRoute != null) tripStore.saveRoute(nsRoute, navSession.lastSwapReason)
                    lastRecordedRoute = nsRoute
                    // An obf route brings its roads' Latin aliases along; the tile path adds more as
                    // nav tiles load, and nav end resets the dictionary to the offline base.
                    if (nsRoute.roadNamesLatin.isNotEmpty()) host.onNavRoadLatin(nsRoute.roadNamesLatin)
                    // Controls (lights/stop signs) for the WHOLE route in one corridor fetch (issue
                    // #248) - never during a recorded-trip replay (hermetic, no live fetches), but a
                    // DEMO drive keeps it: demoDriving ⟹ replaying under the hood, yet it's presented
                    // as real nav and does live fetches (device-caught 2026-08-08: the bare !replaying
                    // guard silently skipped the fetch on every simulated drive).
                    val vs = _state.value
                    if (!vs.replaying || vs.demoDriving) {
                        refreshNavRouteControls(nsRoute)
                        refreshRouteSpeedCams(nsRoute) // spoken camera warnings (issue #229)
                        refreshRouteFlock(nsRoute) // plate-camera card / voice alerts
                    }
                }
                if (!ns.navigating) {
                    lastRecordedRoute = null
                    clearNavRouteControls()
                    routeCamMeters = emptyList(); routeCamKey = null; spokenCams = emptySet()
                    clearRouteFlock()
                    speeding.reset()
                }
                // Mirror the drive into the theme holder: the "day and night while navigating"
                // setting (issue #262) is the one theme input that is not a preference.
                app.vela.ui.theme.AppTheme.navigating.value = ns.navigating
                // Speak an approach warning for a camera coming up (issue #229). Cheap per tick:
                // a scan of a short list; the projection was done once when the route landed.
                if (ns.navigating) { maybeWarnCamera(ns); maybeWarnFlock(ns); maybeWarnSpeeding() }
                _state.update {
                    it.copy(
                        navigating = ns.navigating,
                        // Every drive starts heading-up (Google's default). The compass toggle is
                        // per-drive, not sticky: a north-up pick from a previous session used to
                        // leak into the next drive's opening frames.
                        navNorthUp = if (navStarted) false else it.navNorthUp,
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
                // what the nav engine did - the tuning signal that pairs with the raw GPS trip
                // trace. Rides the existing opt-in; never uploaded.
                if (navStarted) diag.record("nav", "start → ${ns.destinationLabel.ifBlank { "host.destination" }}")
                // Route bar (issue #228). Two clocks on purpose: the MARKS are projected onto the
                // route once per route (O(marks x polyline), far too heavy for a progress tick),
                // while the model itself is rebuilt from the cached marks every tick, which is
                // just arithmetic. Nav-end clears both.
                if (_state.value.routeBarEnabled) updateRouteBar(ns) else if (_state.value.routeBar != null) {
                    _state.update { it.copy(routeBar = null) }
                }
                // Heartbeat the resume timestamp while a REAL drive is under way (skip replay/demo, which
                // don't persist) so the resume window measures time since the INTERRUPTION, not since nav
                // start - else a drive longer than MapViewModel.RESUME_MAX_AGE_MS could never be resumed (audit 2026-07-06).
                if (ns.navigating && !_state.value.replaying && navResumePrefs.contains("lat")) {
                    val now = System.currentTimeMillis()
                    if (now - lastNavHeartbeatMs > MapViewModel.NAV_HEARTBEAT_MS) {
                        lastNavHeartbeatMs = now
                        navResumePrefs.edit().putLong("at", now).apply()
                    }
                }
                if (justArrived) {
                    tripStore.finishTrip()
                    // Don't touch the resume pref on a REPLAY/DEMO arrival - those never persisted one, and a
                    // real drive could be paused underneath (a replay riding an active nav); only a genuine
                    // live arrival should clear it (audit 2026-07-07).
                    if (!_state.value.replaying) clearPersistedNav()
                    // NavEvent.Arrived fires only at the FINAL host.destination, so this is safe for multi-stop trips.
                    diag.record(
                        "nav",
                        "arrived → ${ns.destinationLabel.ifBlank { "host.destination" }}",
                        String.format(
                            java.util.Locale.US, "drove %.2f mi in %.0f min",
                            ns.tripDistanceMeters / 1609.34, ns.tripElapsedSeconds / 60.0,
                        ),
                    )
                }
            }
        }
        // Flipping "Warn me out loud" on MID-DRIVE fetches the current route's cameras right away;
        // before, the fetch only ran on a route change, so the toggle did nothing until the next
        // reroute (review 2026-09-12). Flipping it off clears the list through the same function.
        scope.launch {
            androidx.compose.runtime.snapshotFlow { app.vela.ui.SpeedCamWarn.on.value && app.vela.ui.SpeedCams.on.value }
                .collect { on ->
                    val r = navSession.state.value.route ?: return@collect
                    if (on) routeCamKey = null // force the fetch even for the same route
                    refreshRouteSpeedCams(r)
                }
        }
        // Same for the plate-camera alerts: turning either one on mid-drive projects the current
        // route's cameras now; turning both off clears them.
        scope.launch {
            androidx.compose.runtime.snapshotFlow { app.vela.ui.FlockNavAlert.card.value || app.vela.ui.FlockNavAlert.voice.value }
                .collect { on ->
                    val r = navSession.state.value.route ?: return@collect
                    if (!navSession.state.value.navigating) return@collect
                    val vs = _state.value
                    if (vs.replaying && !vs.demoDriving) return@collect
                    if (on) routeFlockKey = null // force the projection even for the same route
                    refreshRouteFlock(r)
                }
        }
    }

    private var navStartJob: kotlinx.coroutines.Job? = null

    fun startNav() {
        host.autoStartOnRoute = false // an explicit Start supersedes any pending auto-start
        val route = _state.value.activeRoute ?: return
        // RE-ENTRANCY GUARD (user 2026-07-16: multiple "Starting navigation" from double-tapping
        // while start was slow): ignore Start while a start is already in flight or nav is running.
        if (navStartJob?.isActive == true || _state.value.navigating) return
        // The pre-nav search's results are stale junk once driving - and the nav bottom slot
        // yields to a NON-EMPTY results list (the in-nav along-route flow), so leftovers from
        // planning made the chooser's Start bar render over a live drive (device 2026-07-14).
        _state.update { it.copy(results = emptyList(), query = "", resultsCollapsed = false) }
        navStartJob = scope.launch {
            // If they hit Start before a picked alternate finished naming, name it first (this IS
            // on the critical path - the route isn't drivable until it's named - but it's a fast
            // OSRM snap, not the 25 s Overpass fetch that used to block here).
            val named = if (route.provisional) host.nameIfNeeded(route).also { _state.update { s -> s.copy(activeRoute = it) } } else route
            // Google-style courtesy: warn once, card + voice, when this drive lands within an hour
            // of the host.destination's closing time (or after it).
            maybeWarnClosingSoon(named)
            // START IMMEDIATELY. The "pass the light, then turn" landmark clauses need a live
            // Overpass fetch (up to a 25 s server timeout) - awaiting it here made tapping Start
            // dead for up to ~20 s before nav even began (user 2026-07-16, a regression from
            // making light guidance standard). Nav starts on the un-enriched route now; the
            // clauses fold in a beat later via NavSession.applyEnrichedRoute (same polyline, only
            // turn text changes), or never, if the fetch is slow - it's best-effort landmark text.
            if (settingsPrefs.getBoolean("demo_drive", false)) startDemoDrive(named) else launchNav(named)
            launch {
                val enriched = enrichLights(named)
                if (enriched !== named) navSession.applyEnrichedRoute(enriched)
            }
        }
    }

    /** Warn at nav start when the drive arrives within an hour of the host.destination closing, or after
     *  it - a heads-up card plus the nav voice, so nobody drives forty minutes to a place that locks
     *  its doors on arrival. Closing time comes from the place's own localized status text
     *  ([app.vela.core.data.ClosingTime]); no parsable status, no warning. */
    private fun maybeWarnClosingSoon(route: app.vela.core.model.Route) {
        val sel = _state.value.selected ?: return
        val end = route.polyline.lastOrNull() ?: return
        if (sel.location.distanceTo(end) > 200.0) return // the selected place isn't this trip's host.destination
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
        host.flashStatus(msg, 15_000L)
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
     *  path), so navigation runs with NO real fix - for demos, screenshots and testing nav anywhere.
     *  Reuses the replay machinery wholesale (hermetic nav, puck physics, camera, voice); the synthetic
     *  fixes are clean (monotonic time, real speed/bearing) so they skip the outlier/standstill gating a
     *  recorded trace needs. Ends like a replay: live GPS resumes, the route/dot reset. */
    private fun startDemoDrive(route: app.vela.core.model.Route) {
        val dest = host.destination ?: route.polyline.lastOrNull() ?: return
        val fixes = app.vela.core.location.DemoTrace.fromRoute(route.polyline)
        if (fixes.size < 2) { host.flashStatus(appContext.getString(R.string.mapvm_no_track_to_replay)); return }
        replayJob?.cancel()
        if (replayOwnsNav) { navSession.stop(); replayOwnsNav = false; host.destination = null }
        host.pauseLiveLocation() // live GPS (and its stale timer) pause while the trace owns the puck
        val resumeLoc = _state.value.myLocation
        _state.update { it.copy(replaying = true, demoDriving = true, navCameraDetached = false) }
        val label = _state.value.selected?.name.orEmpty()
        val job = scope.launch {
            try {
                host.destination = dest
                val engine = _state.value.selectedEngine?.packageName
                host.neuralSynthFor(engine)?.let { voice.neural = it }
                navSession.replayMode = true
                // Pass the REAL travel mode: haptics are per-mode (bike buzzes by default, driving
                // doesn't), so a demo of a bike route must buzz like the real ride would. And the
                // stops (2026-09-14): a demo used to start the session without them, so per-stop
                // cues and the mid-drive stops editor (#402) had nothing to work with.
                val demoStops = _state.value.directionsWaypoints.map { NavSession.NavStop(it.location, it.name) }
                navSession.start(route, dest, label, engine, demoStops, _state.value.travelMode)
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
                    host.updateSpeedLimit(here)
                }
            } finally {
                if (replayJob === coroutineContext[Job]) {
                    replayJob = null
                    navSession.replayMode = false
                    if (replayOwnsNav) { navSession.stop(); replayOwnsNav = false; host.destination = null }
                    NavigationService.stop(appContext)
                    host.clearSpeedLimit()
                    _state.update {
                        it.copy(
                            replaying = false, demoDriving = false, speedLimitKmh = null,
                            routes = emptyList(), activeRoute = null, directionsOpen = false,
                            showSteps = false, previewStepIndex = null,
                            myLocation = resumeLoc ?: it.myLocation,
                            // The last simulated speed otherwise outlives the drive: parked with
                            // sim-location on, no fresh fix ever zeroes it, so the speed readout
                            // (movingFree keys on mySpeed) stuck on screen (device 2026-07-13).
                            mySpeed = null, mySpeedRaw = null,
                        )
                    }
                    host.startLocation()
                }
            }
        }
        replayJob = job
    }

    /** Fold traffic-light landmark clauses into [route]'s turns. Standard behaviour since 2026-07-17
     *  (the Advanced toggle it hid behind was cut - "pass the light, then turn right" when a turn is
     *  ambiguous is just better guidance, exactly when Google says it); the enrichment itself stays
     *  conservative (1-2 lights, plain surface-street turns only) and is a NO-OP in languages whose
     *  NavStrings table doesn't implement passLights (currently all but English). Best-effort + IO;
     *  a fetch miss just leaves the route unchanged. */
    private suspend fun enrichLights(route: app.vela.core.model.Route): app.vela.core.model.Route {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val signals = when (host.roadFeaturesCoverRoute(route.polyline)) {
                MapViewModel.RoadCover.LOADED -> withContext(Dispatchers.Default) { app.vela.data.RoadFeatures.signalsAlong(route.polyline) }
                MapViewModel.RoadCover.FAILED -> emptyList()
                MapViewModel.RoadCover.NONE -> app.vela.core.data.OverpassTrafficSignals.fetchAlong(http, route.polyline)
            }
            app.vela.core.data.RouteGeometry.enrichWithLights(route, signals)
        }
    }

    private fun launchNav(route: app.vela.core.model.Route) {
        val dest = host.destination ?: route.polyline.lastOrNull() ?: return
        host.startLocation() // make sure live fixes are flowing - they drive the nav loop
        // Stops are stored in travel order (swapDirections reverses the list itself) → per-stop arrival
        // cues + reroute-through-remaining.
        val s = _state.value
        val stops = s.directionsWaypoints.map { NavSession.NavStop(it.location, it.name) }
        // Robust host.destination lines for the ARRIVE step: name, else address, else the raw
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
            tripStore.saveRoute(route, "start") // save the blue line + maneuvers so a replay drives THIS route
        }
        // If the phone has no voice engine, say so once instead of going silent - with a pill
        // straight to the voice library. Not when spoken directions are OFF: silence is chosen.
        if (voice.availableEngines().isEmpty() && !voice.muted) {
            host.showStatus(appContext.getString(R.string.mapvm_no_voice_engine), voiceAction = true)
        }
    }

    fun stopNav() {
        // A replay OR a demo drive owns nav through the replay job - "End" (and the back gesture, which
        // also routes here) must end the REPLAY, not run live-nav teardown: stopReplay cancels replayJob
        // whose finally does the full owned-nav teardown (replayMode off, navSession.stop, route/dot/camera
        // restore, live-GPS resume) and never clears a real drive's persisted resume prefs. Covers demo
        // (demoDriving ⟹ replaying && replayOwnsNav). Was demoDriving-only, so a recorded-trip replay's End
        // ran live teardown and left the replay job running (audit 2026-07-06).
        if (_state.value.replaying && replayOwnsNav) { stopReplay(); return }
        NavigationService.stop(appContext)
        navSession.stop()
        val recorded = tripStore.finishTrip() // close + persist the recorded trip (drops too-short ones)
        // Offer to name it while the drive is still in mind - a trip auto-named after the
        // host.destination is fine for one drive and useless once there are twenty of the same one.
        // Only when the user asked for the prompt, and only for a trip that actually survived
        // (finishTrip drops ones too short to be worth keeping).
        if (recorded != null && settingsPrefs.getBoolean("trip_name_on_save", false)) _state.update { it.copy(tripToName = recorded) }
        host.clearSpeedLimit() // clear the speed-limit badge for the next drive
        clearPersistedNav() // this drive is over → don't offer to resume it next launch
        _state.update {
            it.copy(
                showSteps = false, previewStepIndex = null, navCameraDetached = false, speedLimitKmh = null,
                // The drive is over: drop the route + chooser leftovers too. The nav observer
                // deliberately KEEPS activeRoute when navigating flips false (the arrival card
                // still shows the route), so the explicit end is where it clears - Ending a
                // drive used to leave the blue line drawn on the bare map (user 2026-07-14).
                activeRoute = null, routes = emptyList(), directionsOpen = false,
                directionsWaypoints = emptyList(), flockOnRoute = emptyList(),
                // The next drive starts a fresh dictionary: an obf route brings its roads' Latin
                // aliases with it and the nav tiles add the rest as they load (issue #184).
                roadNameLatin = emptyMap(),
            )
        }
        voice.roadNameLatin = emptyMap()
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
     *  step - so recenter undoes both a manual pan and a swipe-ahead step preview. */
    fun recenterNav() = _state.update { it.copy(navCameraDetached = false, previewStepIndex = null) }

    /** The in-nav whole-route overview (Google's fly-over). CAMERA ONLY - guidance, voice and the
     *  moving puck are untouched; marking the camera detached makes the follow step aside and puts
     *  the Re-center button up, which glides straight back into the follow. The view layer does the
     *  actual bounds fit off MapScreen's overview tick. */
    fun navOverview() = _state.update { it.copy(navCameraDetached = true, previewStepIndex = null) }

    /** The in-nav compass button: toggle the follow camera between heading-up and north-up. */
    fun toggleNavNorthUp() = _state.update { it.copy(navNorthUp = !it.navNorthUp) }

    fun navStopsForEditor(): List<Place> {
        val known = _state.value.directionsWaypoints
        return navSession.remainingStops().map { st ->
            known.firstOrNull { it.location == st.location }
                ?: Place(id = "stop:${st.location.lat},${st.location.lng}", name = st.label, location = st.location)
        }
    }

    /** The labels of the stops still ahead, for the nav sheet's Stops row. */
    fun navRemainingStopLabels(): List<String> = navSession.remainingStops().map { it.label }
    fun navRemainingStops(): List<app.vela.core.nav.NavSession.NavStop> = navSession.remainingStops()

    /** In-nav stop insert: hand the pick to the session (it replans the drive through it) and
     *  clear the search chrome so the nav view is what's on screen again. The chooser's waypoint
     *  list gains it too, so ending nav back into the panel shows the real trip. */
    fun addStopDuringNav(p: Place) {
        val loc = _state.value.myLocation ?: return
        navSession.addStop(app.vela.core.nav.NavSession.NavStop(p.location, p.name), loc)
        _state.update {
            it.copy(
                directionsWaypoints = it.directionsWaypoints + p,
                results = emptyList(), query = "", suggestions = emptyList(), localSuggestions = emptyList(),
                selected = null, alongRouteDest = null, resultsCollapsed = false,
            )
        }
    }

    fun replayTrip(meta: app.vela.replay.TripMeta) {
        val fixes = tripStore.load(meta.id)
        if (fixes.size < 2) { host.flashStatus(appContext.getString(R.string.mapvm_no_track_to_replay)); return }
        replayJob?.cancel()
        // A superseded replay's stale finally no-ops (the job guard fails below), so tear
        // down any nav IT auto-started here, before this new replay starts its own.
        if (replayOwnsNav) { navSession.stop(); replayOwnsNav = false; host.destination = null }
        host.pauseLiveLocation() // live GPS (and its stale timer) pause while the trace owns the puck
        // Also kill any pending stale-location timer armed by the last live fix - otherwise it can fire
        // ~seconds into the replay and flip myLocationStale=true, briefly greying the replay puck / hiding
        // its arrow until the next trace fix clears it. The replay collector sets stale=false per fix.
        // The user's real position BEFORE the trace took over - restored on teardown so exiting the replay
        // snaps the dot back off the trace's end point to (approximately) where they are; the resumed live
        // GPS refines it on the next fix.
        val resumeLoc = _state.value.myLocation
        _state.update { it.copy(replaying = true, navCameraDetached = false) }
        host.flashStatus(appContext.getString(R.string.mapvm_replaying, meta.label), 3000L)
        val job = scope.launch {
            try {
                // Drive turn-by-turn during the replay without manually starting nav first.
                // Prefer the route SAVED with the trip (the exact blue line the user drove) so the
                // cards/voice replay identically and any divergence is real, not a re-route
                // artifact; fall back to a fresh route for older trips that predate route-saving.
                // Best-effort (the replay still plays if both fail), skipped if nav's already active.
                // Segment-aware: the trip records every route the drive actually used (start +
                // each reroute/faster-route swap as its own RP/RD/M block). The replay starts on
                // the FIRST route and swaps at the recorded fix positions - HERMETICALLY: no live
                // fetches (replayMode suppresses reroute + the faster-route recheck; a live fetch
                // used to swap the route mid-replay and match the trace against a route the
                // driver never drove - arrow on another street, faster-route sheet over a replay).
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
                        host.destination = dest
                        // Replay must speak through the SAME engine as live nav - the user's selected
                        // voice (e.g. the Vela neural voice), not null → which fell back to the system
                        // TTS while still applying the voice-speed pref (the "GrapheneOS voice at 0.8×"
                        // bug). Wire the neural synth too, in case the pick changed since launch.
                        val engine = _state.value.selectedEngine?.packageName
                        host.neuralSynthFor(engine)?.let { voice.neural = it }
                        navSession.replayMode = true
                        navSession.start(route, dest, meta.label, engine)
                        replayOwnsNav = true
                    }
                }
                // reason -> chime: only wrong-turn reroutes chime in replay; faster/heal/
                // stop-added swaps were quiet live. Old reason-less recordings chime for all.
                val swapAt = segments.drop(1).associateBy({ it.fromPoint }, { it.route to (it.reason ?: "reroute") })
                val pts = fixes.map { app.vela.core.location.ReplayFix(it.lat, it.lng, it.t, it.bearing, it.speed) }
                var lastReplayT = 0L
                var fixIdx = 0
                val posOutlierStreak = intArrayOf(0)
                locationProvider.replay(pts, speedup = MapViewModel.REPLAY_SPEEDUP).collect { loc ->
                    // Play back the drive's own route swaps at the fix where they happened.
                    swapAt[fixIdx]?.let { (r, why) -> if (replayOwnsNav) navSession.replaySetRoute(r, chime = why == "reroute") }
                    fixIdx += 1
                    val rawHere = LatLng(loc.latitude, loc.longitude)
                    val prev = _state.value.myLocation
                    val dt = if (lastReplayT > 0L) (loc.time - lastReplayT) / 1000.0 else -1.0
                    lastReplayT = loc.time
                    // Same outlier-reject + standstill-hold as live, so a recorded NETWORK leap
                    // doesn't jump the dot / distance / mph on replay either.
                    val here = host.sanePosition(rawHere, prev, _state.value.mySpeed, dt, posOutlierStreak)
                    val bearing = if (loc.hasBearing() && loc.speed > 0.5f) loc.bearing else _state.value.myBearing
                    // Same symmetric plausibility gate as live GPS - recorded traces carry the raw
                    // glitches (35→157 hops AND one-fix dropouts to 0), and the old one-sided
                    // filter here had no escape at all: one recorded down-glitch latched the
                    // whole rest of the replay at 0 (dead Kalman, camera pinned zoomed-in).
                    val measured = if (loc.hasSpeed()) host.gateMeasuredSpeed(loc.speed, dt.coerceAtLeast(0.0)) else null
                    val speed = measured ?: _state.value.mySpeed
                    _state.update {
                        it.copy(
                            myLocation = here, myBearing = bearing, mySpeed = speed,
                            // Replay fixes carry the recorded doppler - feed the puck Kalman the
                            // same way live does, or the replay puck never seeds (no gliding,
                            // no speed-scaled zoom/gates: replays looked worse than real drives).
                            mySpeedRaw = measured,
                            center = here, myLocationStale = false,
                        )
                    }
                    navSession.onLocation(here, app.vela.ui.Units.imperial.value, speed?.toDouble())
                    host.updateSpeedLimit(here) // posted-limit badge during replay too (local graph read)
                }
            } finally {
                // Only the current replay tears down: a superseded one was already stopped
                // above, so this stale finally (job guard false) no-ops.
                if (replayJob === coroutineContext[Job]) {
                    replayJob = null
                    navSession.replayMode = false
                    val ownedNav = replayOwnsNav
                    if (replayOwnsNav) { navSession.stop(); replayOwnsNav = false; host.destination = null }
                    host.clearSpeedLimit() // mirror stopNav - don't leak the replay's last limit into the next drive
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
                                // Same stale-speed hole the demo teardown had: the trace's last
                                // speed outlives the replay when no fresh fix follows to zero it.
                                mySpeed = null, mySpeedRaw = null,
                            )
                        } else {
                            // Replay rode an already-active nav session - leave its route/location alone.
                            it.copy(replaying = false, speedLimitKmh = null)
                        }
                    }
                    host.startLocation() // resume live GPS
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

    fun finishNav() {
        stopNav()
        host.clearSelection()
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
     *  "Resume navigation?" prompt. Stale (older than [MapViewModel.RESUME_MAX_AGE_MS], i.e. that drive is long over) →
     *  clear it silently. Called from init. */
    fun maybeOfferResume() {
        val at = navResumePrefs.getLong("at", 0L)
        if (at == 0L) return
        if (System.currentTimeMillis() - at > MapViewModel.RESUME_MAX_AGE_MS) { clearPersistedNav(); return }
        val lat = navResumePrefs.getFloat("lat", Float.NaN); val lng = navResumePrefs.getFloat("lng", Float.NaN)
        if (lat.isNaN() || lng.isNaN()) { clearPersistedNav(); return }
        resumeDest = LatLng(lat.toDouble(), lng.toDouble())
        resumeMode = runCatching { TravelMode.valueOf(navResumePrefs.getString("mode", null) ?: "DRIVE") }
            .getOrDefault(TravelMode.DRIVE)
        _state.update { it.copy(resumeNavLabel = navResumePrefs.getString("label", "") ?: "") }
    }

    /** User tapped "Resume": re-route from the CURRENT fix to the saved host.destination + start nav afresh
     *  (a fresh route handles however far you drove while the app was gone, and any traffic since). */
    fun resumeNav() {
        val dest = resumeDest ?: return
        val label = _state.value.resumeNavLabel.orEmpty()
        val mode = resumeMode
        val origin = _state.value.myLocation
        if (origin == null) { host.showStatus(appContext.getString(R.string.mapvm_resume_waiting_gps)); return }
        _state.update { it.copy(resumeNavLabel = null) }
        scope.launch {
            val routes = runCatching { dataSource.directions(origin, dest, mode, emptyList()) }.getOrDefault(emptyList())
            var route = routes.firstOrNull()
            if (route?.provisional == true) route = host.nameIfNeeded(route)
            if (route == null) { host.showStatus(appContext.getString(R.string.mapvm_resume_failed)); clearPersistedNav(); return@launch }
            host.destination = dest
            _state.update { it.copy(activeRoute = route, routes = routes) }
            host.startLocation()
            // No address survives a process kill (only the label was persisted); destinationDisplay
            // still guarantees SOMETHING shows on the arrive step (label, else the coordinates).
            val (resumedName, _) = NavSession.destinationDisplay(label, null, dest)
            navSession.start(route, dest, resumedName, _state.value.selectedEngine?.packageName, emptyList(), mode)
            NavigationService.start(appContext)
            persistNav(dest, label, mode) // keep it persisted through the resumed drive
        }
    }

    /** User dismissed the resume prompt - forget it. */
    fun dismissResume() = clearPersistedNav()

    fun acceptFasterRoute() = navSession.acceptFasterRoute()

    fun dismissFasterRoute() = navSession.dismissFasterRoute()

    private suspend fun tunnelDeadReckonLoop() {
        while (true) {
            delay(1_000)
            val s = _state.value
            val route = s.activeRoute
            val now = android.os.SystemClock.elapsedRealtime()
            val sinceFix = now - lastNavFedMs
            val eligible = s.navigating && !s.replaying && route != null && route.polyline.size >= 2 &&
                !s.nav.offRoute && lastNavFedMs > 0L && sinceFix > MapViewModel.DR_START_MS
            if (!eligible) {
                drProgressM = Double.NaN
                continue
            }
            if (drProgressM.isNaN()) {
                // The feed just went quiet: seed from the engine's along-route progress + the
                // last shown speed. A standstill at signal loss never starts reckoning.
                drSpeed = (s.mySpeed ?: 0f).toDouble()
                if (drSpeed < MapViewModel.DR_MIN_SPEED) continue
                drProgressM = s.nav.traveledM
                drTotalM = 0.0
                drLastMs = now
                continue
            }
            val dt = ((now - drLastMs) / 1000.0).coerceIn(0.5, 3.0)
            drLastMs = now
            drSpeed *= kotlin.math.exp(-dt / MapViewModel.DR_DECAY_S)
            if (drSpeed < MapViewModel.DR_MIN_SPEED || drTotalM > MapViewModel.DR_MAX_M) continue // hold position, stay honest
            val step = drSpeed * dt
            drProgressM += step
            drTotalM += step
            val pt = pointAlongPolyline(route.polyline, drProgressM) ?: continue
            _state.update {
                it.copy(
                    myLocation = pt, mySpeed = drSpeed.toFloat(), mySpeedRaw = null,
                    center = pt, myLocationStale = false,
                    navStarved = it.navStarved || sinceFix > MapViewModel.NAV_STARVED_MS,
                )
            }
            host.restartStaleTimer() // keep the dot/arrow blue while the estimate runs
            navSession.onLocation(pt, app.vela.ui.Units.imperial.value, drSpeed)
        }
    }

    /** The point [m] metres along [poly] (clamped to the ends). Linear walk - called at 1 Hz. */
    private fun pointAlongPolyline(poly: List<LatLng>, m: Double): LatLng? {
        if (poly.size < 2) return null
        if (m <= 0.0) return poly.first()
        var acc = 0.0
        for (i in 1 until poly.size) {
            val seg = poly[i - 1].distanceTo(poly[i])
            if (seg <= 0.0) continue
            if (acc + seg >= m) {
                val f = ((m - acc) / seg).coerceIn(0.0, 1.0)
                return LatLng(
                    poly[i - 1].lat + (poly[i].lat - poly[i - 1].lat) * f,
                    poly[i - 1].lng + (poly[i].lng - poly[i - 1].lng) * f,
                )
            }
            acc += seg
        }
        return poly.last()
    }

    private var navControlsJob: Job? = null
    private var navControlsKey: String? = null // set only after a corridor fetch SUCCEEDED

    /**
     * Issue #248: fetch the traffic lights + stop signs along the ROUTE CORRIDOR once per driven route
     * (nav start + every reroute/faster-route swap) and serve [MapUiState.trafficControls] from that set
     * for the whole drive - the viewport-box path refetched every time the moving camera neared its
     * cached box edge, and the churn (against mirrors that are sometimes down) made the icons appear
     * rarely and vanish quickly during nav. Same cluster-per-intersection pass as the box path.
     */
    // Marks projected onto the CURRENT route, cached by that route's identity so a progress tick
    // never re-walks the polyline. Null route key = nothing cached.
    private var routeBarKey: String? = null
    private var routeBarMarks: List<Pair<app.vela.core.nav.RouteBar.Mark, Double>> = emptyList()
    private var routeBarTotalM: Double? = null // the polyline's own length, the axis the marks are measured on

    /** Recompute the route bar for this nav tick (see the call site for why the work is split). */
    private fun updateRouteBar(ns: app.vela.core.nav.NavSession.State) {
        val route = ns.route
        if (!ns.navigating || route == null || route.polyline.size < 2) {
            if (_state.value.routeBar != null || routeBarKey != null) {
                routeBarKey = null
                routeBarMarks = emptyList()
                _state.update { it.copy(routeBar = null) }
            }
            return
        }
        // Keyed on the route's endpoints + length and on the IDENTITY of the two mark lists (a
        // replaced set with the same count used to keep stale marks; review 2026-09-12).
        val key = "${route.polyline.first()}|${route.polyline.last()}|${route.distanceMeters.toInt()}|" +
            "${System.identityHashCode(_state.value.trafficControls)}|${System.identityHashCode(_state.value.flockCameras)}|" +
            "${System.identityHashCode(_state.value.speedCameras)}"
        if (key != routeBarKey) {
            routeBarKey = key
            val poly = route.polyline
            val cum = app.vela.core.nav.RouteBar.cumulative(poly)
            val controls = _state.value.trafficControls
            val cams = _state.value.flockCameras
            val speedCams = _state.value.speedCameras
            scope.launch(Dispatchers.Default) {
                val marks = buildList {
                    for (c in controls) {
                        val m = app.vela.core.nav.RouteBar.alongMeters(poly, cum, c.loc) ?: continue
                        add(
                            when (c.kind) {
                                app.vela.core.data.TrafficControl.Kind.SIGNAL -> app.vela.core.nav.RouteBar.Mark.SIGNAL
                                app.vela.core.data.TrafficControl.Kind.STOP -> app.vela.core.nav.RouteBar.Mark.STOP
                                app.vela.core.data.TrafficControl.Kind.RAIL_CROSSING -> app.vela.core.nav.RouteBar.Mark.RAIL_CROSSING
                                app.vela.core.data.TrafficControl.Kind.SPEED_HUMP -> app.vela.core.nav.RouteBar.Mark.SPEED_HUMP
                            } to m,
                        )
                    }
                    // Fixed speed cameras get a badge too (they were only ever on the map, never
                    // on the bar). No direction in the data, so distance to the route is the test.
                    for (c in speedCams) {
                        val m = app.vela.core.nav.RouteBar.alongMeters(poly, cum, c.loc) ?: continue
                        add(app.vela.core.nav.RouteBar.Mark.CAMERA to m)
                    }
                    for (c in cams) {
                        // Direction-aware like the route counts: a camera aimed across the road
                        // does not read this route's plates, so it is not a mark on it.
                        val facing = app.vela.core.nav.CameraFacing.parse(c.direction)
                        if (!app.vela.core.nav.CameraFacing.onRoute(poly, c.loc, facing, 40.0)) continue
                        val m = app.vela.core.nav.RouteBar.alongMeters(poly, cum, c.loc) ?: continue
                        add(app.vela.core.nav.RouteBar.Mark.CAMERA to m)
                    }
                }
                withContext(Dispatchers.Main) {
                    // Guard against a route swap landing while this was computing.
                    if (routeBarKey == key) {
                        routeBarMarks = marks
                        routeBarTotalM = cum.last()
                        _state.update { it.copy(routeBar = app.vela.core.nav.RouteBar.build(route, ns.nav.traveledM, marks, totalM = routeBarTotalM)) }
                    }
                }
            }
            return
        }
        _state.update { it.copy(routeBar = app.vela.core.nav.RouteBar.build(route, ns.nav.traveledM, routeBarMarks, totalM = routeBarTotalM)) }
    }

    /** Show or hide the route bar (pref `route_bar`). */
    fun setRouteBar(on: Boolean) {
        settingsPrefs.edit().putBoolean("route_bar", on).apply()
        _state.update { it.copy(routeBarEnabled = on, routeBar = if (on) it.routeBar else null) }
    }

    // Speed cameras projected onto the CURRENT route, in ascending along-route metres, plus the
    // indices already announced. Keyed like the controls corridor fetch so a same-course heal does
    // not refetch or re-arm warnings the driver already heard.
    private var routeCamKey: String? = null
    private var routeCamMeters: List<Double> = emptyList()
    private var spokenCams: Set<Int> = emptySet()
    private var routeCamJob: kotlinx.coroutines.Job? = null

    /** One corridor fetch of speed cameras per driven route, projected onto it for the spoken
     *  approach warning (issue #229). No-op unless the layer AND the spoken warning are on. */
    // Flipping "Warn me out loud" on MID-DRIVE fetches the current route's cameras right away;
    // before, the fetch only ran on a route change, so the toggle did nothing until the next
    // reroute (review 2026-09-12). Flipping it off clears the list through the same function.

    private fun refreshRouteSpeedCams(route: app.vela.core.model.Route) {
        if (!app.vela.ui.SpeedCams.on.value || !app.vela.ui.SpeedCamWarn.on.value) {
            routeCamKey = null; routeCamMeters = emptyList(); spokenCams = emptySet()
            return
        }
        val poly = route.polyline
        if (poly.size < 2) return
        val f = poly.first(); val l = poly.last()
        val key = String.format(
            java.util.Locale.US, "%.4f,%.4f|%.4f,%.4f|%d",
            f.lat, f.lng, l.lat, l.lng, (route.distanceMeters / 500).toInt(),
        )
        if (key == routeCamKey) return
        routeCamKey = key
        spokenCams = emptySet() // a genuinely new route: nothing has been announced on it yet
        // And nothing is KNOWN on it yet: a reroute resets traveledM to 0 on the new route, so
        // the old route's distances compared against it would announce a camera you left
        // kilometres behind, every tick until the fetch lands (or forever if it fails).
        routeCamMeters = emptyList()
        routeCamJob?.cancel()
        routeCamJob = scope.launch {
            val cams = when (host.roadFeaturesCoverRoute(poly)) {
                MapViewModel.RoadCover.LOADED -> withContext(Dispatchers.Default) { app.vela.data.RoadFeatures.camerasAlong(poly, 150.0) }
                MapViewModel.RoadCover.FAILED -> null
                MapViewModel.RoadCover.NONE -> runCatching {
                    withContext(Dispatchers.IO) {
                        app.vela.core.data.OverpassSpeedCameras.fetchAlongCorridor(http, poly)
                    }
                }.getOrNull()
            } ?: run {
                // Leave the key set: a failed fetch means no warnings this route rather than a
                // retry storm mid-drive. The map layer still draws from the viewport path.
                android.util.Log.i("VelaSpeedCam", "route corridor camera fetch FAILED (all endpoints)")
                return@launch
            }
            val meters = withContext(Dispatchers.Default) {
                val cum = app.vela.core.nav.RouteProjection.cumulative(poly)
                cams.mapNotNull { app.vela.core.nav.RouteProjection.alongMeters(poly, cum, it.loc) }.sorted()
            }
            if (routeCamKey == key) {
                routeCamMeters = meters
                diag.record("speedcam", "${meters.size} camera(s) on route", "corridor")
            }
        }
    }

    // Plate (Flock / ALPR) camera groups on the CURRENT route, ascending along-route metres, and
    // the groups already announced. Keyed exactly like the speed-camera corridor so a same-course
    // heal neither re-projects nor re-arms an alert the driver already had.
    private var routeFlockKey: String? = null
    private var routeFlockGroups: List<app.vela.core.nav.CameraAlerts.Group> = emptyList()
    private var routeFlockMeters: List<Double> = emptyList()
    private var alertedFlock: Set<Int> = emptySet()
    private var routeFlockJob: kotlinx.coroutines.Job? = null

    private fun clearRouteFlock() {
        routeFlockJob?.cancel()
        routeFlockKey = null; routeFlockGroups = emptyList(); routeFlockMeters = emptyList(); alertedFlock = emptySet()
    }

    /** Project the plate cameras that can see this route onto it, once per driven route. The
     *  bundled dataset is in memory, so this is local work, no network. Only cameras that face
     *  along the route count ([app.vela.data.FlockCameras.along]); ones within 40 m of each other
     *  along the route become one announcement. No-op unless either alert is on. */
    private fun refreshRouteFlock(route: app.vela.core.model.Route) {
        if (!app.vela.ui.FlockNavAlert.any) { clearRouteFlock(); return }
        val poly = route.polyline
        if (poly.size < 2) return
        val f = poly.first(); val l = poly.last()
        val key = String.format(
            java.util.Locale.US, "%.4f,%.4f|%.4f,%.4f|%d",
            f.lat, f.lng, l.lat, l.lng, (route.distanceMeters / 500).toInt(),
        )
        if (key == routeFlockKey) return
        routeFlockJob?.cancel()
        routeFlockKey = key
        // A genuinely new route: nothing announced on it, and nothing known on it yet (a reroute
        // resets traveledM, so the old route's distances would point at cameras left behind).
        alertedFlock = emptySet(); routeFlockGroups = emptyList(); routeFlockMeters = emptyList()
        routeFlockJob = scope.launch {
            // The dataset parses off the main thread at launch; a drive started in the first
            // seconds waits for it rather than silently getting no alerts for the whole route.
            var waited = 0
            while (!app.vela.data.FlockCameras.isLoaded && waited < 60) { kotlinx.coroutines.delay(1000); waited++ }
            if (!app.vela.data.FlockCameras.isLoaded) return@launch
            val groups = withContext(Dispatchers.Default) {
                val cams = app.vela.data.FlockCameras.along(poly)
                val cum = app.vela.core.nav.RouteProjection.cumulative(poly)
                val meters = cams.mapNotNull { app.vela.core.nav.RouteProjection.alongMeters(poly, cum, it.loc, 45.0) }.sorted()
                app.vela.core.nav.CameraAlerts.group(meters)
            }
            if (routeFlockKey == key) {
                routeFlockGroups = groups
                routeFlockMeters = groups.map { it.atM }
                diag.record("flock", "${groups.sumOf { it.count }} plate camera(s) on route in ${groups.size} group(s)", "bundled")
            }
        }
    }

    /** Announce the plate-camera group coming up, once each: a heads-up card, a spoken line, or
     *  both, per the two settings. Timing is the speed-camera warning's ([CameraAlerts.due]). */
    private fun maybeWarnFlock(ns: app.vela.core.nav.NavSession.State) {
        if (routeFlockMeters.isEmpty() || !app.vela.ui.FlockNavAlert.any) return
        val i = app.vela.core.nav.CameraAlerts.due(
            routeFlockMeters, ns.nav.traveledM, (_state.value.mySpeed ?: 0f).toDouble(), alertedFlock,
        ) ?: return
        alertedFlock = alertedFlock + i
        val msg = appContext.getString(
            if (routeFlockGroups[i].count > 1) R.string.nav_flock_cameras_ahead else R.string.nav_flock_camera_ahead,
        )
        if (app.vela.ui.FlockNavAlert.card.value) host.flashStatus(msg, 6000L)
        if (app.vela.ui.FlockNavAlert.voice.value) voice.speak(msg)
    }

    /** Say so when you have been over the posted limit for a few seconds (issue #404, opt-in).
     *  The limit is the one the speed badge shows: the offline graph's maxspeed, else the online
     *  overlay under the puck. Timing (hold, re-arm, minimum gap) lives in :core [SpeedingAlerts]. */
    private fun maybeWarnSpeeding() {
        if (!app.vela.ui.SpeedingAlert.on.value) return
        val st = _state.value
        val limit = st.speedLimitKmh ?: st.speedLimitOverlayKmh
        val speedKmh = st.mySpeed?.let { it.toDouble() * 3.6 }
        if (!speeding.update(speedKmh, limit, android.os.SystemClock.elapsedRealtime())) return
        voice.speak(appContext.getString(R.string.nav_speeding_alert))
        tripStore.note("K", "speeding alert: ${speedKmh?.toInt()} km/h, limit ${limit?.toInt()}")
    }

    /** Announce the camera coming up, once each. Timing lives in :core [CameraAlerts]. */
    private fun maybeWarnCamera(ns: app.vela.core.nav.NavSession.State) {
        if (routeCamMeters.isEmpty()) return
        if (!app.vela.ui.SpeedCams.on.value || !app.vela.ui.SpeedCamWarn.on.value) return
        val i = app.vela.core.nav.CameraAlerts.due(
            routeCamMeters, ns.nav.traveledM, (_state.value.mySpeed ?: 0f).toDouble(), spokenCams,
        ) ?: return
        spokenCams = spokenCams + i
        voice.speak(appContext.getString(R.string.nav_speed_camera_ahead))
    }

    private fun refreshNavRouteControls(route: app.vela.core.model.Route) {
        val poly = route.polyline
        if (poly.size < 2) return
        // Key on endpoints + coarse length: a same-course heal (stepsUpgrade/trafficUpgrade swaps the
        // route OBJECT, not the drive) must not refetch; a real reroute moves the start point and an
        // accepted faster route changes the length.
        val f = poly.first(); val l = poly.last()
        val key = String.format(
            java.util.Locale.US, "%.4f,%.4f|%.4f,%.4f|%d",
            f.lat, f.lng, l.lat, l.lng, (route.distanceMeters / 500).toInt(),
        )
        if (key == navControlsKey) return
        navControlsJob?.cancel()
        navControlsJob = scope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val res = when (host.roadFeaturesCoverRoute(poly)) {
                MapViewModel.RoadCover.LOADED -> withContext(Dispatchers.Default) { app.vela.data.RoadFeatures.controlsAlong(poly, 120.0) }
                    .also { android.util.Log.i("VelaControls", "baked route controls=${it.size} pts=${poly.size} in ${android.os.SystemClock.elapsedRealtime() - t0} ms") }
                MapViewModel.RoadCover.FAILED -> { android.util.Log.i("VelaControls", "route road-features download FAILED"); return@launch }
                MapViewModel.RoadCover.NONE -> runCatching {
                    withContext(Dispatchers.IO) {
                        app.vela.core.data.OverpassTrafficSignals.fetchControlsAlongCorridor(http, poly)
                    }
                }.getOrNull() ?: run {
                    // Key stays unset → the viewport-box path keeps serving as the fallback (fetch-fail
                    // honesty, same contract as the box fetch: never cache a failure as "no controls").
                    android.util.Log.i("VelaControls", "route corridor fetch FAILED (all endpoints)")
                    return@launch
                }
            }
            val merged = withContext(Dispatchers.Default) {
                res.groupBy { it.kind }.flatMap { (kind, group) ->
                    app.vela.core.data.MapDeclutter.cluster(group, MapViewModel.CONTROLS_CLUSTER_M) { it.loc }
                        .map { c -> app.vela.core.data.TrafficControl(c.centroid, kind) }
                }
            }
            val kept = if (merged.size <= MapViewModel.CONTROLS_ROUTE_CAP) merged else {
                val lngScale = kotlin.math.cos(Math.toRadians(f.lat))
                merged.sortedBy {
                    val dLat = it.loc.lat - f.lat; val dLng = (it.loc.lng - f.lng) * lngScale
                    dLat * dLat + dLng * dLng
                }.take(MapViewModel.CONTROLS_ROUTE_CAP)
            }
            android.util.Log.i("VelaControls", "route corridor fetched=${res.size} merged=${merged.size} kept=${kept.size}")
            navControlsKey = key
            host.controlsBox = null // the box cache is superseded; the post-nav viewport refresh repaints fresh
            _state.update { it.copy(trafficControls = kept) }
        }
    }

    /** Nav ended - drop the corridor set's ownership so browse viewport fetches repaint the layer. */
    private fun clearNavRouteControls() {
        if (navControlsKey == null && navControlsJob == null) return
        navControlsJob?.cancel(); navControlsJob = null
        navControlsKey = null
        host.controlsBox = null
    }

}
