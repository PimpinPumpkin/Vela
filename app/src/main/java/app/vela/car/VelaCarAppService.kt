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
 * NAVIGATION apps for production AA — so on a degoogled phone the car UI is reachable only via
 * Android Auto developer mode. The realistic GMS-free target is embedded AAOS. See the plan/ROADMAP.
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

    // Allow ANY Android Auto / AAOS host to connect. Vela is sideloaded (never on Play) and must
    // "just work" on whatever head unit / DHU a user plugs into — the
    // standard release allowlist (hosts_allowlist_sample) rejects hosts it doesn't recognise, which
    // manifested as the app appearing but refusing to open. The host-spoofing risk this guards against
    // is negligible for a non-Play, self-distributed nav app. (2026-07-07)
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = VelaCarSession(
        CarDeps(navSession, locationProvider, mapDataSource, recentPlaces, savedPlaces, shortcuts, voiceGuide, routeEngine),
    )
}
