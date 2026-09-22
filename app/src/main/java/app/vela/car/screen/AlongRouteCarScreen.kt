package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
import app.vela.core.nav.NavSession
import app.vela.ui.QuickCategories
import kotlinx.coroutines.launch

/**
 * Search along the drive from the car: the phone's quick categories (fuel, food, coffee...) as
 * rows, a pick searches around the car, and a result becomes the NEXT stop through the same
 * `NavSession.addStop` the phone's in-nav search uses. Two list templates, no typing while
 * driving (the host would refuse a search template mid-drive anyway).
 */
class AlongRouteCarScreen(carContext: CarContext, private val deps: CarDeps) : Screen(carContext) {
    private var query: String? = null
    private var results: List<Place>? = null
    private var loading = false

    override fun onGetTemplate(): Template {
        val q = query
        val items = ItemList.Builder()
        if (q == null) {
            QuickCategories.all().forEach { chip ->
                items.addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(chip.label))
                        .setOnClickListener { search(chip.query) }
                        .build(),
                )
            }
            return ListTemplate.Builder()
                .setTitle(carContext.getString(app.vela.R.string.car_along_route))
                .setHeaderAction(Action.BACK)
                .setSingleList(items.build())
                .build()
        }
        val here = deps.locationProvider.lastKnown()
        val res = results
        if (res == null) {
            return ListTemplate.Builder().setTitle(q).setHeaderAction(Action.BACK).setLoading(true).build()
        }
        if (res.isEmpty()) {
            items.addItem(Row.Builder().setTitle(carContext.getString(app.vela.R.string.car_along_none)).build())
        }
        res.take(6).forEach { p ->
            val dist = here?.let { ManeuverMapperDistance.text(p.location.distanceTo(it), app.vela.ui.Units.imperial.value) }
            items.addItem(
                Row.Builder()
                    .setTitle(p.name)
                    .addText(listOfNotNull(dist, p.address).joinToString(" · "))
                    .setOnClickListener { addStop(p, here) }
                    .build(),
            )
        }
        return ListTemplate.Builder().setTitle(q).setHeaderAction(Action.BACK).setSingleList(items.build()).build()
    }

    private fun search(q: String) {
        query = q; results = null; loading = true
        invalidate()
        lifecycleScope.launch {
            val here = deps.locationProvider.lastKnown()
            val found = runCatching { deps.mapDataSource.search(q, here).places }.getOrDefault(emptyList())
            results = here?.let { h -> found.sortedBy { it.location.distanceTo(h) } } ?: found
            loading = false
            invalidate()
        }
    }

    private fun addStop(p: Place, here: LatLng?) {
        val loc = here ?: p.location
        deps.navSession.addStop(NavSession.NavStop(p.location, p.name), loc)
        CarToast.makeText(carContext, carContext.getString(app.vela.R.string.car_stop_added, p.name), CarToast.LENGTH_SHORT).show()
        screenManager.pop()
    }
}

/** Short distance text for a list row, in the user's units. */
internal object ManeuverMapperDistance {
    fun text(meters: Double, imperial: Boolean): String = if (imperial) {
        val mi = meters / 1609.344
        if (mi < 0.1) "${(meters * 3.28084 / 10).toInt() * 10} ft" else String.format(java.util.Locale.getDefault(), "%.1f mi", mi)
    } else {
        if (meters < 1000) "${(meters / 10).toInt() * 10} m" else String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1000)
    }
}
