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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.vela.data.ContactAddresses

/** Destination search on the car. While typing, the provider's AUTOCOMPLETE ([app.vela.core.data.MapDataSource.suggest],
 *  one small request) answers, biased to the last known location; the full
 *  [app.vela.core.data.MapDataSource.search] runs only when the query is submitted or a bare
 *  query row is picked. Tapping a result previews a route to it.
 *
 *  It used to run the full search on every keystroke (2026-09-22, "spinning forever" on the
 *  head unit): three result pages plus the nearby pass per letter, and cancelling the coroutine
 *  never aborted the HTTP calls, so a typed word queued a dozen requests behind OkHttp's
 *  per-host limit and the spinner waited for the whole backlog. */
class SearchCarScreen(carContext: CarContext, private val deps: CarDeps) : Screen(carContext) {

    private var results: List<Place> = emptyList()
    /** Bare query rows from the autocomplete ("Starbucks"): picking one runs the full search. */
    private var queries: List<String> = emptyList()
    // Contact rows (issue #243, opt-in Settings > Search): matched on the phone against the
    // in-memory address list, shown above the search results; picking one geocodes the
    // address and previews a route under the person's name.
    private var contacts: List<ContactAddresses.Entry> = emptyList()
    private var searching = false
    private var searchJob: Job? = null

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = runSearch(searchText, submit = false)
            override fun onSearchSubmitted(searchText: String) = runSearch(searchText, submit = true)
        }
        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
        if (searching) {
            builder.setLoading(true)
        } else {
            val list = ItemList.Builder()
            if (results.isEmpty() && contacts.isEmpty() && queries.isEmpty()) {
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
                queries.take((6 - contacts.size - results.size).coerceAtLeast(0)).forEach { q ->
                    list.addItem(
                        Row.Builder()
                            .setTitle(q)
                            .addText(carContext.getString(app.vela.R.string.car_search))
                            .setOnClickListener { runSearch(q, submit = true) }
                            .build(),
                    )
                }
            }
            builder.setItemList(list.build())
        }
        return builder.build()
    }

    private fun runSearch(text: String, submit: Boolean) {
        searchJob?.cancel()
        if (text.isBlank()) {
            results = emptyList(); queries = emptyList(); contacts = emptyList(); searching = false; invalidate(); return
        }
        // Only a submit shows the spinner: while typing the previous rows stay up and are
        // replaced when the next answer lands, the way the Google app's list behaves.
        if (submit) { searching = true; invalidate() }
        searchJob = lifecycleScope.launch {
            if (!submit) delay(300) // debounce
            val near = deps.locationProvider.lastKnown()
            val foundContacts = if (app.vela.ui.ContactsSearch.enabled.value && text.length >= 2) {
                withContext(Dispatchers.IO) { ContactAddresses.ensureLoaded(carContext) }
                ContactAddresses.matches(text, 2)
            } else emptyList()
            var foundQueries: List<String> = emptyList()
            var found: List<Place> = try {
                if (submit) {
                    deps.mapDataSource.search(text, near).places
                } else {
                    val auto = deps.mapDataSource.suggest(text, near, SUGGEST_SPAN_M)
                    foundQueries = auto.queries
                    // Nothing from the autocomplete (Google off): the online search answers instead.
                    if (auto.places.isEmpty() && auto.queries.isEmpty()) deps.mapDataSource.search(text, near).places else auto.places
                }
            } catch (e: CancellationException) {
                throw e // superseded by a newer keystroke: never publish its rows
            } catch (e: Exception) {
                emptyList()
            }
            // No signal, or the online answer came back empty: the downloaded packs, the way the
            // phone's offline search reads them (a typed address leads, then places). The data
            // source never reads the packs itself, so without this the car found nothing offline.
            if (found.isEmpty() && foundQueries.isEmpty()) {
                found = withContext(Dispatchers.IO) { offlineSearch(text, near) }
            }
            ensureActive()
            contacts = foundContacts
            results = found
            queries = foundQueries
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

    private fun offlineSearch(text: String, near: app.vela.core.model.LatLng?): List<Place> {
        val addrs = if (app.vela.core.data.OfflineAddressStore.looksLikeAddress(text))
            runCatching { deps.offlineAddresses.geocode(text, near, limit = 4) }.getOrDefault(emptyList()) else emptyList()
        val pois = runCatching { deps.offlinePois.search(text, near, limit = 12) }.getOrDefault(emptyList())
        return (addrs + pois).distinctBy { it.id }
    }

    private companion object {
        /** The autocomplete window around the car: a town, like the phone's default. */
        const val SUGGEST_SPAN_M = 20_000.0
    }
}
