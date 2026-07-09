package app.vela.car.screen

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.vela.car.CarMapRenderer
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import app.vela.service.NavigationService
import kotlinx.coroutines.launch

/** Route preview for a chosen destination: fetches routes via [app.vela.core.data.MapDataSource],
 *  lists them with live ETAs on a map showing the route framed, and on "Go" names the picked route
 *  and starts [NavSession]. */
class RoutePreviewCarScreen(
    carContext: CarContext,
    private val deps: CarDeps,
    private val destName: String,
    private val dest: LatLng,
) : Screen(carContext), DefaultLifecycleObserver {

    private var routes: List<Route> = emptyList()
    private var selected = 0
    private var loading = true
    private var fetched = false
    private var origin: LatLng? = null

    init { lifecycle.addObserver(this) }

    override fun onStart(owner: LifecycleOwner) {
        // Shared renderer, preview mode (frame the selected route). Never clear/stop on transitions.
        val renderer = deps.mapRenderer(carContext)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        renderer.start()
        renderer.showPreview(routes.getOrNull(selected))
    }

    override fun onGetTemplate(): Template {
        if (!fetched) { fetched = true; fetchRoutes() }

        val builder = RoutePreviewNavigationTemplate.Builder().setHeaderAction(Action.BACK)
        builder.setTitle(destName)

        if (loading) {
            builder.setLoading(true)
            return builder.build()
        }
        if (routes.isEmpty()) {
            // No location / no route found. RoutePreviewNavigationTemplate REQUIRES a navigate action
            // whenever its list is set (non-loading) — a "no items" list with none throws
            // "navigation action cannot be null". So show a MessageTemplate instead of the preview.
            return MessageTemplate.Builder(carContext.getString(app.vela.R.string.car_no_route))
                .setTitle(destName)
                .setHeaderAction(Action.BACK)
                .build()
        }

        // RoutePreviewNavigationTemplate caps its route list at 3 — more rows THROW at build.
        val shown = routes.take(3)
        val list = ItemList.Builder()
        shown.forEach { r -> list.addItem(routeRow(r)) }
        list.setOnSelectedListener { idx -> selected = idx; deps.mapRenderer(carContext).showPreview(shown.getOrNull(idx)) }
        list.setSelectedIndex(selected.coerceIn(0, shown.lastIndex))
        builder.setItemList(list.build())
        builder.setNavigateAction(
            Action.Builder()
                .setTitle(carContext.getString(app.vela.R.string.car_go))
                .setOnClickListener { startNav() }
                .build(),
        )
        return builder.build()
    }

    private fun routeRow(r: Route): Row {
        val secs = (r.durationInTrafficSeconds ?: r.durationSeconds).toLong()
        val title = r.summary?.takeIf { it.isNotBlank() } ?: destName
        // The RoutePreviewNavigationTemplate REQUIRES every route row to carry a DurationSpan or
        // DistanceSpan on its title/texts (else "All rows must have either a distance or duration
        // span attached" — a hard crash). Attach both: a DurationSpan and a DistanceSpan, each
        // replacing a placeholder char, so the host renders the ETA + distance in its own format.
        val text = SpannableString("  ·  ")
        text.setSpan(DurationSpan.create(secs), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        text.setSpan(
            DistanceSpan.create(Distance.create(distanceValue(r.distanceMeters), distanceUnit())),
            text.length - 1, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
        )
        return Row.Builder()
            .setTitle(CarText.create(title))
            .addText(text)
            .build()
    }

    private fun distanceUnit(): Int =
        if (app.vela.ui.Units.imperial.value) Distance.UNIT_MILES else Distance.UNIT_KILOMETERS

    private fun distanceValue(meters: Double): Double =
        if (app.vela.ui.Units.imperial.value) meters / 1609.344 else meters / 1000.0

    private fun fetchRoutes() {
        // Don't invalidate() here — this runs during onGetTemplate (which already returns the loading
        // template); invalidate mid-build is a no-op the host discourages. Only invalidate on completion.
        loading = true
        lifecycleScope.launch {
            // The GPS fix may still be locking when this screen opens — poll up to ~15 s for a location
            // instead of giving up immediately (which left the screen stuck on "loading" forever).
            var from = deps.locationProvider.lastKnown()
            var tries = 0
            while (from == null && tries < 30) {
                kotlinx.coroutines.delay(500)
                from = deps.locationProvider.lastKnown()
                tries++
            }
            origin = from
            routes = if (from == null) emptyList()
            else runCatching { deps.mapDataSource.directions(from, dest, TravelMode.DRIVE) }.getOrDefault(emptyList())
            loading = false
            invalidate()
            deps.mapRenderer(carContext).showPreview(routes.getOrNull(selected)) // frame the route once fetched
        }
    }

    private fun startNav() {
        val from = origin ?: deps.locationProvider.lastKnown() ?: return
        val picked = routes.getOrNull(selected) ?: routes.firstOrNull() ?: return
        lifecycleScope.launch {
            val named = if (picked.provisional) {
                runCatching { deps.mapDataSource.nameRoute(picked, from, dest, TravelMode.DRIVE) }.getOrDefault(picked)
            } else picked
            deps.navSession.start(named, dest, destName, null, emptyList(), TravelMode.DRIVE)
            runCatching { NavigationService.start(carContext.applicationContext) }
            screenManager.push(ActiveNavCarScreen(carContext, deps))
        }
    }

}
