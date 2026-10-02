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
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.vela.car.CarLocationAccess
import app.vela.car.CarMapRenderer
import app.vela.core.model.LatLng
import app.vela.core.model.ShortcutKind

/**
 * Car landing screen: Home/Work shortcuts + recent + saved destinations, and a Search action.
 * Tapping a row previews a route to it ([RoutePreviewCarScreen]). Reuses [CarDeps] stores.
 *
 * Claims the session's SHARED [CarMapRenderer] (`deps.mapRenderer`) in browse mode on start, so the
 * landing map is a LIVE, clean browse map centered on you; otherwise the surface keeps the previous
 * nav screen's final frame and the finished trip's route lingers here.
 */
class MainCarScreen(carContext: CarContext, private val deps: CarDeps) :
    Screen(carContext), DefaultLifecycleObserver {

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) {
        // Shared renderer, browse mode (clean live map, no route). Never clear the callback / stop the
        // collector on transitions — the shared renderer lives for the session (VelaCarSession stops it).
        val renderer = deps.mapRenderer(carContext)
        renderer.follow()
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        renderer.start()
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()

        // PlaceListNavigationTemplate caps the list at MAX_ROWS (6) — adding more THROWS. Build the
        // candidates (Home, Work, recents, saved) and take the first 6, de-duped by location.
        val rows = buildList {
            deps.shortcuts.get(ShortcutKind.HOME)?.let { add(it.name to it.location) }
            deps.shortcuts.get(ShortcutKind.WORK)?.let { add(it.name to it.location) }
            deps.recentPlaces.recent().forEach { add(it.place.name to it.place.location) }
            deps.savedPlaces.saved().forEach { add(it.name to it.location) }
        }.distinctBy { it.second.lat to it.second.lng }

        // A car can connect before Vela was set up on the phone, so onboarding never asked for
        // location. The first row asks here; the map shows the world until the answer comes back.
        val needsLocation = !CarLocationAccess.check(carContext)
        if (needsLocation) list.addItem(locationRow())
        var free = if (needsLocation) MAX_ROWS - 1 else MAX_ROWS

        // Destinations first (at most MAX_DESTINATIONS; Saved has the rest), then nearby
        // categories fill what is left, so the list is never empty on a new install. When not
        // every category fits, the last row opens all of them.
        val shown = rows.take(minOf(MAX_DESTINATIONS, free))
        shown.forEach { (name, loc) -> list.addItem(destRow(name, loc)) }
        free -= shown.size
        val categories = NearbyCarScreen.driving()
        val fit = if (categories.size <= free) categories else categories.take((free - 1).coerceAtLeast(0))
        fit.forEach { c ->
            list.addItem(NearbyCarScreen.categoryRow(carContext, c) { screenManager.push(NearbyCarScreen(carContext, deps, c)) })
        }
        if (fit.size < categories.size && free > 0) list.addItem(moreNearbyRow())

        val search = Action.Builder()
            .setTitle(carContext.getString(app.vela.R.string.car_search))
            .setIcon(icon(app.vela.R.drawable.ic_car_search))
            .setOnClickListener { screenManager.push(SearchCarScreen(carContext, deps)) }
            .build()

        // Map controls, the same set the drive screen has: the landing map pans and pinches, so it
        // needs a way back to you and a way to zoom without a pinch.
        val renderer = deps.mapRenderer(carContext)
        val mapStrip = ActionStrip.Builder()
            .addAction(mapAction(app.vela.R.drawable.ic_car_recenter) { renderer.follow() })
            .addAction(mapAction(app.vela.R.drawable.ic_car_zoom_in) { renderer.zoomBy(1.0) })
            .addAction(mapAction(app.vela.R.drawable.ic_car_zoom_out) { renderer.zoomBy(-1.0) })
            .build()

        return PlaceListNavigationTemplate.Builder()
            .setItemList(list.build())
            .setTitle(carContext.getString(app.vela.R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(search)
                    .addAction(mapAction(app.vela.R.drawable.ic_car_saved) { screenManager.push(SavedCarScreen(carContext, deps)) })
                    .addAction(mapAction(app.vela.R.drawable.ic_car_settings) { screenManager.push(CarSettingsScreen(carContext, deps)) })
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
            } else {
                CarToast.makeText(carContext, app.vela.R.string.car_location_denied, CarToast.LENGTH_LONG).show()
            }
            invalidate()
        }
    }

    private fun destRow(name: String, dest: LatLng): Row =
        Row.Builder()
            .setTitle(name)
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(RoutePreviewCarScreen(carContext, deps, name, dest)) }
            .build()

    private fun icon(res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private companion object {
        // PlaceListNavigationTemplate hard-caps its list at 6 rows (exceeding it throws at build).
        const val MAX_ROWS = 6
        // Destinations on the landing list; the rest of the rows are nearby categories.
        const val MAX_DESTINATIONS = 3
    }
}
