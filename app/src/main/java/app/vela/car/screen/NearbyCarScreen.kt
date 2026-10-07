package app.vela.car.screen

import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.vela.core.model.LatLng
import app.vela.core.model.Place
import app.vela.core.model.distanceTo
import app.vela.ui.QuickCategories
import kotlinx.coroutines.launch

/**
 * Places around the car by category, before a drive: the phone's quick categories, then the
 * nearest results for one of them over the live map. A result previews a route to it, the same
 * as a saved place. ([AlongRouteCarScreen] is the mid-drive twin, where a pick becomes a stop.)
 *
 * Opened with a [chip] it goes straight to that category's results (the landing screen's
 * category rows); without one it lists every category first.
 */
class NearbyCarScreen(
    carContext: CarContext,
    private val deps: CarDeps,
    private val chip: QuickCategories.Chip? = null,
) : Screen(carContext), DefaultLifecycleObserver {

    private var results: List<Place>? = null

    init {
        lifecycle.addObserver(this)
        chip?.let { search(it) }
    }

    override fun onStart(owner: LifecycleOwner) {
        // The results sit over the shared live map, centered on the car.
        val renderer = deps.mapRenderer(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        renderer.start()
        renderer.follow()
    }

    override fun onGetTemplate(): Template {
        val c = chip ?: return categoryList()
        val title = carContext.getString(c.label)
        val res = results
            ?: return PlaceListNavigationTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK).setLoading(true).build()

        val here = deps.locationProvider.lastKnown()
        val list = ItemList.Builder()
        if (res.isEmpty()) list.setNoItemsMessage(carContext.getString(app.vela.R.string.car_along_none))
        // PlaceListNavigationTemplate caps its list at six rows and throws past that.
        res.take(MAX_ROWS).forEach { list.addItem(resultRow(it, here)) }
        return PlaceListNavigationTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setItemList(list.build())
            .build()
    }

    private fun categoryList(): Template {
        val list = ItemList.Builder()
        QuickCategories.all().forEach { c ->
            // Its own screen, so Back from the results comes back to this list.
            list.addItem(categoryRow(carContext, c) { screenManager.push(NearbyCarScreen(carContext, deps, c)) })
        }
        return ListTemplate.Builder()
            .setTitle(carContext.getString(app.vela.R.string.car_nearby))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun resultRow(p: Place, here: LatLng?): Row {
        val row = Row.Builder().setTitle(p.name)
        val address = p.address?.takeIf { it.isNotBlank() }
        if (here != null) {
            // A distance span is what the template wants on a row; the host formats it.
            val text = SpannableString(if (address != null) "  ·  $address" else " ")
            text.setSpan(DistanceSpan.create(distance(p.location.distanceTo(here))), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            row.addText(text)
        } else if (address != null) {
            row.addText(address)
        }
        return row
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(RoutePreviewCarScreen(carContext, deps, p.name, p.location)) }
            .build()
    }

    private fun distance(meters: Double): Distance = if (app.vela.ui.Units.imperial.value) {
        val miles = meters / 1609.344
        if (miles < 0.1) Distance.create(meters * 3.28084, Distance.UNIT_FEET)
        else Distance.create(miles, Distance.UNIT_MILES)
    } else {
        if (meters < 1000) Distance.create(meters, Distance.UNIT_METERS)
        else Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS)
    }

    // Runs once, from init: the first template is the loading one, so only the answer invalidates.
    private fun search(c: QuickCategories.Chip) {
        lifecycleScope.launch {
            val here = deps.locationProvider.lastKnown()
            // A cancelled search is rethrown, never turned into "nothing found".
            val found = try {
                deps.mapDataSource.search(c.query, here).places
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            results = here?.let { h -> found.sortedBy { it.location.distanceTo(h) } } ?: found
            invalidate()
        }
    }

    companion object {
        private const val MAX_ROWS = 6

        /** The categories a driver reaches for, in the order the landing screen shows them. */
        fun driving(): List<QuickCategories.Chip> {
            val all = QuickCategories.all()
            return listOf(
                app.vela.R.string.cat_gas,
                app.vela.R.string.cat_ev,
                app.vela.R.string.cat_restaurants,
                app.vela.R.string.cat_coffee,
                app.vela.R.string.cat_parking,
            ).mapNotNull { id -> all.firstOrNull { it.label == id } }
        }

        /** A category row with the map's own marker for it. */
        fun categoryRow(ctx: CarContext, c: QuickCategories.Chip, onClick: () -> Unit): Row {
            val marker = app.vela.ui.map.PoiIcons.groupMarker(ctx, app.vela.ui.map.PoiIcons.groupFor(null, c.query))
            return Row.Builder()
                .setTitle(ctx.getString(c.label))
                .apply {
                    if (marker != null) setImage(CarIcon.Builder(IconCompat.createWithBitmap(marker)).build(), Row.IMAGE_TYPE_SMALL)
                }
                .setBrowsable(true)
                .setOnClickListener(onClick)
                .build()
        }
    }
}
