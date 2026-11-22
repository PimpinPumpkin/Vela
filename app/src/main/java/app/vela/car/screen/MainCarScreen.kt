package app.vela.car.screen

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.vela.car.CarLocationAccess
import app.vela.car.CarMapRenderer
import app.vela.core.model.LatLng
import app.vela.core.model.ShortcutKind

/**
 * Car landing screen. It opens on the map alone, the way Google Maps' car screen does: a
 * `NavigationTemplate` with Search, a list button, Saved and Settings, the map tilted and turning
 * with the car and the speed badge drawn. The list button shows the destinations card in place
 * (Home, Work, recents, saved places, nearby categories) and the map button on it puts it away
 * (issue #899). With no location permission yet the card stays, since its first row asks for it.
 * Tapping a row previews a route to it ([RoutePreviewCarScreen]).
 *
 * Claims the session's SHARED [CarMapRenderer] (`deps.mapRenderer`) in browse mode on start, so the
 * landing map is a LIVE, clean browse map centered on you; otherwise the surface keeps the previous
 * nav screen's final frame and the finished trip's route lingers here.
 */
class MainCarScreen(carContext: CarContext, private val deps: CarDeps) :
    Screen(carContext), DefaultLifecycleObserver {

    init { lifecycle.addObserver(this) }

    /** The destinations card is up; off, the map is alone on the screen. */
    private var listShown = false

    override fun onStart(owner: LifecycleOwner) {
        // Shared renderer, browse mode (clean live map, no route). Never clear the callback / stop the
        // collector on transitions — the shared renderer lives for the session (VelaCarSession stops it).
        val renderer = deps.mapRenderer(carContext)
        renderer.follow()
        renderer.setDriveScreen(!showsList())
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        renderer.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        // A screen pushed on top (search, a preview) frames the map its own way.
        deps.mapRenderer(carContext).setDriveScreen(false)
    }

    private fun showsList(): Boolean = listShown || !CarLocationAccess.check(carContext)

    private fun showList(on: Boolean) {
        listShown = on
        deps.mapRenderer(carContext).setDriveScreen(!showsList())
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val renderer = deps.mapRenderer(carContext)
        // Map controls: the map pans and pinches, so it needs a way back to you and a way to zoom
        // without a pinch.
        val mapStrip = ActionStrip.Builder()
            .addAction(mapAction(app.vela.R.drawable.ic_car_recenter) { renderer.follow() })
            .addAction(mapAction(app.vela.R.drawable.ic_car_zoom_in) { renderer.zoomBy(1.0) })
            .addAction(mapAction(app.vela.R.drawable.ic_car_zoom_out) { renderer.zoomBy(-1.0) })
            .build()
        val search = Action.Builder()
            .setTitle(carContext.getString(app.vela.R.string.car_search))
            .setIcon(icon(app.vela.R.drawable.ic_car_search))
            .setOnClickListener { screenManager.push(SearchCarScreen(carContext, deps)) }
            .build()
        val saved = mapAction(app.vela.R.drawable.ic_car_saved) { screenManager.push(SavedCarScreen(carContext, deps)) }
        val settings = mapAction(app.vela.R.drawable.ic_car_settings) { screenManager.push(CarSettingsScreen(carContext, deps)) }
        if (!showsList()) {
            return NavigationTemplate.Builder()
                .setActionStrip(
                    ActionStrip.Builder()
                        .addAction(search)
                        .addAction(mapAction(app.vela.R.drawable.ic_car_list) { showList(true) })
                        .addAction(saved)
                        .addAction(settings)
                        .build(),
                )
                .setMapActionStrip(mapStrip)
                .build()
        }

        val list = ItemList.Builder()

        // PlaceListNavigationTemplate caps the list at MAX_ROWS (6) — adding more THROWS. Build the
        // candidates (Home, Work, recents, saved) and take the first 6, de-duped by location.
        val rows = destinations(deps)

        // A car can connect before Vela was set up on the phone, so onboarding never asked for
        // location. The first row asks here; the map shows the world until the answer comes back.
        val needsLocation = !CarLocationAccess.check(carContext)
        if (needsLocation) list.addItem(locationRow())
        var free = if (needsLocation) MAX_ROWS - 1 else MAX_ROWS

        // Destinations first (at most MAX_DESTINATIONS; Saved has the rest), then nearby
        // categories fill what is left, so the list is never empty on a new install. When not
        // every category fits, the last row opens all of them.
        val shown = rows.take(minOf(MAX_DESTINATIONS, free))
        shown.forEach { d ->
            list.addItem(destinationRow(carContext, d, browsable = true) { screenManager.push(RoutePreviewCarScreen(carContext, deps, d.name, d.location)) })
        }
        free -= shown.size
        val categories = NearbyCarScreen.driving()
        // The last free row is always "More nearby": with exactly as many categories as rows
        // (a new install with no destinations) the rest of the categories had no way in.
        val fit = if (categories.size < free) categories else categories.take((free - 1).coerceAtLeast(0))
        fit.forEach { c ->
            list.addItem(NearbyCarScreen.categoryRow(carContext, c) { screenManager.push(NearbyCarScreen(carContext, deps, c)) })
        }
        if (free > fit.size) list.addItem(moreNearbyRow())

        // Beside the card on a narrow map (an 800 by 480 unit, or a split screen), the titled
        // Search pill reached over the card's header; there it is the icon alone.
        val narrow = renderer.surfaceWidthDp() in 1 until NARROW_MAP_DP
        val listSearch = if (!narrow) search else Action.Builder()
            .setIcon(icon(app.vela.R.drawable.ic_car_search))
            .setOnClickListener { screenManager.push(SearchCarScreen(carContext, deps)) }
            .build()
        return PlaceListNavigationTemplate.Builder()
            .setItemList(list.build())
            .setTitle(carContext.getString(app.vela.R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(listSearch)
                    // Puts the card away and leaves the map alone (issue #899). Not offered while
                    // the card's first row is the location prompt.
                    .apply { if (CarLocationAccess.check(carContext)) addAction(mapAction(app.vela.R.drawable.ic_car_drive) { showList(false) }) }
                    .addAction(saved)
                    .addAction(settings)
                    .build(),
            )
            .setMapActionStrip(mapStrip)
            .build()
    }

    private fun mapAction(iconRes: Int, onClick: () -> Unit): Action =
        Action.Builder().setIcon(icon(iconRes)).setOnClickListener(onClick).build()

    private fun moreNearbyRow(): Row =
        Row.Builder()
            .setTitle(carContext.getString(app.vela.R.string.car_more_nearby))
            .setImage(icon(app.vela.R.drawable.ic_car_search))
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(NearbyCarScreen(carContext, deps)) }
            .build()

    private fun locationRow(): Row =
        Row.Builder()
            .setTitle(carContext.getString(app.vela.R.string.car_location_title))
            .addText(carContext.getString(app.vela.R.string.car_location_body))
            .setImage(icon(app.vela.R.drawable.ic_car_recenter))
            // PlaceListNavigationTemplate throws on a row that is neither browsable nor carries a
            // distance span; this one leads somewhere (the permission prompt), so browsable.
            .setBrowsable(true)
            .setOnClickListener { askForLocation() }
            .build()

    /** Shows Android's own permission prompt on the phone; the car keeps its screen meanwhile. */
    private fun askForLocation() {
        carContext.requestPermissions(CarLocationAccess.PERMISSIONS) { _, _ ->
            if (CarLocationAccess.check(carContext)) {
                deps.mapRenderer(carContext).follow()
                deps.mapRenderer(carContext).setDriveScreen(!showsList())
            } else {
                CarToast.makeText(carContext, app.vela.R.string.car_location_denied, CarToast.LENGTH_LONG).show()
            }
            invalidate()
        }
    }

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    companion object {
        // PlaceListNavigationTemplate hard-caps its list at 6 rows (exceeding it throws at build).
        private const val MAX_ROWS = 6
        // Destinations on the landing list; the rest of the rows are nearby categories.
        private const val MAX_DESTINATIONS = 3
        // A map narrower than this has no room for the card and a titled Search side by side.
        private const val NARROW_MAP_DP = 960

        /** A destination row: Home, Work, a recent or a saved place, with its kind's icon. */
        data class Destination(val name: String, val location: LatLng, val address: String?, val iconRes: Int)

        /** Home, Work, the recents and the saved places, one row per location. */
        fun destinations(deps: CarDeps): List<Destination> = buildList {
            deps.shortcuts.get(ShortcutKind.HOME)?.let { add(Destination(it.name, it.location, it.address, app.vela.R.drawable.ic_car_home)) }
            deps.shortcuts.get(ShortcutKind.WORK)?.let { add(Destination(it.name, it.location, it.address, app.vela.R.drawable.ic_car_work)) }
            deps.recentPlaces.recent().forEach { add(Destination(it.place.name, it.place.location, it.place.address, app.vela.R.drawable.ic_car_history)) }
            deps.savedPlaces.saved().forEach { add(Destination(it.name, it.location, it.address, app.vela.R.drawable.ic_car_saved)) }
        }.distinctBy { it.location.lat to it.location.lng }

        /** A destination's row: its icon, the name and, when it adds something, the address. */
        fun destinationRow(ctx: CarContext, d: Destination, browsable: Boolean, onClick: () -> Unit): Row =
            Row.Builder()
                .setTitle(d.name)
                .setImage(CarIcon.Builder(IconCompat.createWithResource(ctx, d.iconRes)).build(), Row.IMAGE_TYPE_ICON)
                .apply { d.address?.takeIf { it.isNotBlank() && it != d.name }?.let { addText(it) } }
                .apply { if (browsable) setBrowsable(true) }
                .setOnClickListener(onClick)
                .build()
    }
}
