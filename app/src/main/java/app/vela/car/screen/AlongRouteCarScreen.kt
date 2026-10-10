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
                // The map's own category marker on each row (a real head unit, 2026-09-22: bare
                // text rows read as a settings list next to Google's iconed categories).
                val marker = app.vela.ui.map.PoiIcons.groupMarker(carContext, app.vela.ui.map.PoiIcons.groupFor(null, chip.query))
                items.addItem(
                    Row.Builder()
                        .setTitle(carContext.getString(chip.label))
                        .apply {
                            if (marker != null) setImage(
                                androidx.car.app.model.CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithBitmap(marker)).build(),
                                Row.IMAGE_TYPE_SMALL,
                            )
                        }
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
                    // A gas station's price on its own line (SPEC 5.8).
                    .apply { p.fuelPrice?.let { addText(it) } }
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
            val sorted = here?.let { h -> found.sortedBy { it.location.distanceTo(h) } } ?: found
            results = sorted
            loading = false
            invalidate()
            // UK gas stations get their price once the Fuel Finder file is ready (SPEC 5.8).
            val filled = app.vela.ui.map.UkFuelPrices.fill(carContext, deps.http, sorted)
            if (filled !== sorted && results === sorted) { results = filled; invalidate() }
        }
    }

    private fun addStop(p: Place, here: LatLng?) {
        val loc = here ?: p.location
        // Next, not last: the car has no stops editor to move it with.
        deps.navSession.addStop(NavSession.NavStop(p.location, p.name), loc, atEnd = false)
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
