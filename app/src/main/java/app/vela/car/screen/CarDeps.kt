package app.vela.car.screen

import androidx.car.app.CarContext
import app.vela.car.CarMapRenderer
import app.vela.core.data.MapDataSource
import app.vela.core.data.PlaceShortcutStore
import app.vela.core.data.RecentPlaceStore
import app.vela.core.data.RouteEngine
import app.vela.core.data.SavedPlaceStore
import app.vela.core.location.LocationProvider
import app.vela.core.nav.NavSession
import app.vela.core.voice.VoiceGuide

/** The `:core` singletons the car screens share (all the same instances the phone app uses). */
data class CarDeps(
    val navSession: NavSession,
    val locationProvider: LocationProvider,
    val mapDataSource: MapDataSource,
    val recentPlaces: RecentPlaceStore,
    val savedPlaces: SavedPlaceStore,
    val shortcuts: PlaceShortcutStore,
    val voiceGuide: VoiceGuide,
    val routeEngine: RouteEngine, // for the speed-limit badge (offline graphs' max_speed)
) {
    // ONE shared map renderer for the whole car session. Per-screen renderer instances DON'T work:
    // swapping the surface callback to a new instance doesn't re-deliver onSurfaceAvailable, so the
    // new renderer never gets the surface and the map freezes. All screens use this same instance
    // (set as the callback in each onStart) and just switch its mode (browse / preview / nav).
    private var sharedRenderer: CarMapRenderer? = null
    fun mapRenderer(ctx: CarContext): CarMapRenderer =
        sharedRenderer ?: CarMapRenderer(ctx, locationProvider, navSession, routeEngine).also { sharedRenderer = it }
    fun stopMapRenderer() { sharedRenderer?.stop() }
}
