package app.vela.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import app.vela.car.screen.CarDeps
import app.vela.core.data.MapDataSource
import app.vela.core.data.PlaceShortcutStore
import app.vela.core.data.RecentPlaceStore
import app.vela.core.data.RouteEngine
import app.vela.core.data.SavedPlaceStore
import app.vela.core.location.LocationProvider
import app.vela.core.nav.NavSession
import app.vela.core.voice.VoiceGuide
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Android Auto / AAOS entry point. A [CarAppService] is a bound Service, so Hilt (`@AndroidEntryPoint`)
 * injects the same process-wide `:core` singletons the phone app uses — one [NavSession], one
 * [LocationProvider], one [MapDataSource]. The car UI reuses them entirely (see [CarDeps]); it adds
 * no routing/nav/voice logic of its own (voice already speaks from [NavSession] events).
 *
 * NB projected Android Auto requires Google Play Services on the phone, and Google allowlists
 * NAVIGATION apps for production AA. Developer mode and "Unknown sources" are not enough in a real
 * car: on connect Android Auto asks Play who owns the app, and a sideloaded Vela has no owner, so
 * the validator denies it (car log, 2026-09-22). The Desktop Head Unit skips that lookup, so it
 * previews the UI but proves nothing about a car. The realistic GMS-free target is embedded AAOS.
 * See CLAUDE.md and the ROADMAP.
 */
@AndroidEntryPoint
class VelaCarAppService : CarAppService() {

    @Inject lateinit var navSession: NavSession
    @Inject lateinit var locationProvider: LocationProvider
    @Inject lateinit var mapDataSource: MapDataSource
    @Inject lateinit var recentPlaces: RecentPlaceStore
    @Inject lateinit var savedPlaces: SavedPlaceStore
    @Inject lateinit var shortcuts: PlaceShortcutStore
    @Inject lateinit var voiceGuide: VoiceGuide
    @Inject lateinit var routeEngine: RouteEngine
    @Inject lateinit var piperSynth: app.vela.voice.PiperSynth
    @Inject lateinit var offlinePois: app.vela.core.data.OfflinePoiStore
    @Inject lateinit var offlineAddresses: app.vela.core.data.OfflineAddressStore
    @Inject lateinit var poiPacks: app.vela.offline.PoiPackStore
    @Inject lateinit var http: okhttp3.OkHttpClient

    // Allow ANY Android Auto / AAOS host to connect. Vela is sideloaded (never on Play) and must
    // "just work" on whatever head unit / DHU a user plugs into — the
    // standard release allowlist (hosts_allowlist_sample) rejects hosts it doesn't recognize, which
    // manifested as the app appearing but refusing to open. The host-spoofing risk this guards against
    // is negligible for a non-Play, self-distributed nav app. (2026-07-07)
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    // A car with an instrument-cluster display opens a SECOND session for it, and that display
    // accepts the navigation template only. Handing it the main session (whose first screen is a
    // place list) crashed Vela the moment it was picked in the car: "PlaceListNavigationTemplate
    // is not allowed for session with display type 1" (issue #179, 2026-10-06).
    override fun onCreateSession(sessionInfo: androidx.car.app.SessionInfo): Session =
        if (sessionInfo.displayType == androidx.car.app.SessionInfo.DISPLAY_TYPE_CLUSTER) ClusterSession()
        else onCreateSession()

    override fun onCreateSession(): Session {
        // The neural voice is wired into VoiceGuide by the phone's view model; a drive started from
        // the car with the phone UI closed had no synth attached and fell back to the system TTS
        // (user 2026-09-21: "the voice that speaks is not vela voice"). Attach it here too.
        if (voiceGuide.neural == null && app.vela.core.voice.VelaPiper.isReady(this)) voiceGuide.neural = piperSynth
        // "Spoken directions" is applied to VoiceGuide by the phone's view model too: without it a
        // car-only start spoke with the setting off, and the car's own toggle showed On.
        getSharedPreferences("vela_settings", MODE_PRIVATE).let { prefs ->
            if (!prefs.getBoolean("spoken_directions", true)) {
                voiceGuide.muted = true
                voiceGuide.alertsOnly = prefs.getBoolean("spoken_alerts_only", false)
            }
        }
        // The downloaded place packs are opened by the phone's view model at start; a car session
        // with the phone UI never opened has to open them itself for offline car search.
        Thread { runCatching { poiPacks.registerPacks() } }.start()
        return VelaCarSession(
        CarDeps(navSession, locationProvider, mapDataSource, recentPlaces, savedPlaces, shortcuts, voiceGuide, routeEngine, offlinePois, offlineAddresses, http),
        )
    }
}

/** The instrument cluster's session: one bare navigation template. The turn, distance and arrival
 *  shown there come from the trip data the main session publishes through NavigationManager; this
 *  screen only has to exist and be of the one kind the cluster allows. */
private class ClusterSession : Session() {
    override fun onCreateScreen(intent: android.content.Intent): androidx.car.app.Screen =
        object : androidx.car.app.Screen(carContext) {
            override fun onGetTemplate(): androidx.car.app.model.Template =
                androidx.car.app.navigation.model.NavigationTemplate.Builder()
                    .setActionStrip(
                        androidx.car.app.model.ActionStrip.Builder()
                            .addAction(androidx.car.app.model.Action.APP_ICON)
                            .build(),
                    )
                    .build()
        }
}
