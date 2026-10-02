package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import app.vela.core.model.LatLng
import app.vela.core.model.ShortcutKind

/**
 * Every saved place, Home and Work first. The landing screen shows only six destinations mixed
 * with recents; this is the whole list. A row previews a route to the place.
 */
class SavedCarScreen(carContext: CarContext, private val deps: CarDeps) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val places = buildList {
            deps.shortcuts.get(ShortcutKind.HOME)?.let {
                add(Triple(carContext.getString(app.vela.R.string.car_home), it.address, it.location))
            }
            deps.shortcuts.get(ShortcutKind.WORK)?.let {
                add(Triple(carContext.getString(app.vela.R.string.car_work), it.address, it.location))
            }
            deps.savedPlaces.saved().forEach { add(Triple(it.name, it.address, it.location)) }
        }.distinctBy { it.third.lat to it.third.lng }

        // The host says how many rows a list may hold; more throws at build time.
        val limit = runCatching {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        }.getOrDefault(DEFAULT_LIMIT)

        val list = ItemList.Builder()
        places.take(limit).forEach { (name, address, loc) -> list.addItem(placeRow(name, address, loc)) }
        if (places.isEmpty()) list.setNoItemsMessage(carContext.getString(app.vela.R.string.car_saved_empty))

        return ListTemplate.Builder()
            .setTitle(carContext.getString(app.vela.R.string.car_saved))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun placeRow(name: String, address: String?, dest: LatLng): Row =
        Row.Builder()
            .setTitle(name)
            .apply { address?.takeIf { it.isNotBlank() }?.let { addText(it) } }
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(RoutePreviewCarScreen(carContext, deps, name, dest)) }
            .build()

    private companion object {
        const val DEFAULT_LIMIT = 6
    }
}
