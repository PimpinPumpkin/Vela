package app.vela.car.screen

import androidx.car.app.AppManager
import androidx.car.app.CarContext
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
import app.vela.car.CarMapRenderer
import app.vela.core.model.LatLng
import app.vela.core.model.ShortcutKind

/**
 * Car landing screen: Home/Work shortcuts + recent + saved destinations, and a Search action.
 * Tapping a row previews a route to it ([RoutePreviewCarScreen]). Reuses [CarDeps] stores.
 *
 * Owns a [CarMapRenderer] so the landing map is a LIVE, clean browse map centred on you — otherwise
 * the surface keeps the previous nav screen's final frame and the finished trip's route lingers here.
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
        }.distinctBy { it.second.lat to it.second.lng }.take(MAX_ROWS)

        rows.forEach { (name, loc) -> list.addItem(destRow(name, loc)) }
        if (rows.isEmpty()) {
            list.setNoItemsMessage(carContext.getString(app.vela.R.string.car_no_destinations))
        }

        val search = Action.Builder()
            .setTitle(carContext.getString(app.vela.R.string.car_search))
            .setIcon(icon(android.R.drawable.ic_menu_search))
            .setOnClickListener { screenManager.push(SearchCarScreen(carContext, deps)) }
            .build()

        return PlaceListNavigationTemplate.Builder()
            .setItemList(list.build())
            .setTitle(carContext.getString(app.vela.R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(ActionStrip.Builder().addAction(search).build())
            .build()
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
    }
}
