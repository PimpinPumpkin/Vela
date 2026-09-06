package app.vela.car.screen

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.SearchTemplate
import androidx.lifecycle.lifecycleScope
import app.vela.core.model.Place
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.vela.data.ContactAddresses

/** Destination search on the car: debounced [app.vela.core.data.MapDataSource.search] biased to the
 *  last known location; tapping a result previews a route to it. */
class SearchCarScreen(carContext: CarContext, private val deps: CarDeps) : Screen(carContext) {

    private var results: List<Place> = emptyList()
    // Contact rows (issue #243, opt-in Settings > Search): matched on the phone against the
    // in-memory address list, shown above the search results; picking one geocodes the
    // address and previews a route under the person's name.
    private var contacts: List<ContactAddresses.Entry> = emptyList()
    private var searching = false
    private var searchJob: Job? = null

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = runSearch(searchText)
            override fun onSearchSubmitted(searchText: String) = runSearch(searchText)
        }
        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
        if (searching) {
            builder.setLoading(true)
        } else {
            val list = ItemList.Builder()
            if (results.isEmpty() && contacts.isEmpty()) {
                list.setNoItemsMessage(carContext.getString(app.vela.R.string.car_search_hint))
            } else {
                contacts.forEach { e ->
                    list.addItem(
                        Row.Builder()
                            .setTitle(e.name)
                            .addText(listOfNotNull(carContext.getString(app.vela.R.string.suggestion_contact_badge), e.type, e.address).joinToString(" · "))
                            .setOnClickListener { openContact(e) }
                            .build(),
                    )
                }
                results.take(6 - contacts.size).forEach { p ->
                    list.addItem(
                        Row.Builder()
                            .setTitle(p.name)
                            .apply { p.address?.let { addText(it) } }
                            // NOT browsable — SearchTemplate rows are plain clickable results (browsable
                            // implies a drill-in sublist and isn't valid here). onClick pushes preview.
                            .setOnClickListener {
                                screenManager.push(RoutePreviewCarScreen(carContext, deps, p.name, p.location))
                            }
                            .build(),
                    )
                }
            }
            builder.setItemList(list.build())
        }
        return builder.build()
    }

    private fun runSearch(text: String) {
        searchJob?.cancel()
        if (text.isBlank()) {
            results = emptyList(); contacts = emptyList(); searching = false; invalidate(); return
        }
        searching = true
        invalidate()
        searchJob = lifecycleScope.launch {
            delay(300) // debounce
            val near = deps.locationProvider.lastKnown()
            contacts = if (app.vela.ui.ContactsSearch.enabled.value && text.length >= 2) {
                withContext(Dispatchers.IO) { ContactAddresses.ensureLoaded(carContext) }
                ContactAddresses.matches(text, 2)
            } else emptyList()
            val found = runCatching { deps.mapDataSource.search(text, near).places }.getOrDefault(emptyList())
            results = found
            searching = false
            invalidate()
        }
    }

    /** Geocode the contact's address (the one string that leaves the phone) and preview a route. */
    private fun openContact(e: ContactAddresses.Entry) {
        searchJob?.cancel()
        searching = true
        invalidate()
        searchJob = lifecycleScope.launch {
            val near = deps.locationProvider.lastKnown()
            val hit = runCatching { deps.mapDataSource.search(e.address, near).places.firstOrNull() }.getOrNull()
            searching = false
            if (hit != null) {
                screenManager.push(RoutePreviewCarScreen(carContext, deps, e.name, hit.location))
            } else {
                invalidate()
            }
        }
    }
}
