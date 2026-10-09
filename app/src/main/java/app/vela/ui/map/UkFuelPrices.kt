package app.vela.ui.map

import android.content.Context
import app.vela.core.data.FuelGb
import app.vela.core.data.FuelGbStations
import app.vela.core.data.FuelGbStore
import app.vela.core.model.Place
import app.vela.offline.StorageLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * UK fuel prices on gas stations (SPEC 5.8). Watches the search results and the open place, and
 * when a UK gas station among them has no price, fills it from the Fuel Finder file, which
 * [FuelGbStore] downloads the first time it is needed and checks again at most every 3 hours.
 * Results show at once; the prices land when the file is ready. Whatever made the places
 * (Google, the downloaded packs, the places archives, a tapped open-data place) goes through the
 * same state, so this needs no hook in any of them, and works with Google off.
 *
 * Construct it ABOVE the view model's `init` and call [bind] from there: the collector's first
 * pass runs inline.
 */
internal class UkFuelPrices(
    appContext: Context,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<MapUiState>,
    http: okhttp3.OkHttpClient,
) {
    private val store = storeFor(appContext, http)
    private val fuel: (Place) -> Boolean = { isFuel(it) }
    private var job: Job? = null

    fun bind() {
        scope.launch {
            state.map { it.results to it.selected }
                .distinctUntilChanged { a, b -> a.first === b.first && a.second === b.second }
                .collect { (results, selected) -> onPlaces(results, selected) }
        }
    }

    private fun onPlaces(results: List<Place>, selected: Place?) {
        if (results.none { FuelGb.wants(it, fuel) } && (selected == null || !FuelGb.wants(selected, fuel))) return
        store.current?.let(::apply)
        if (job?.isActive == true) return
        job = scope.launch {
            if (store.ensure()) store.current?.let(::apply)
        }
    }

    private fun apply(data: FuelGbStations) {
        val now = System.currentTimeMillis() / 1000
        state.update { s ->
            val results = FuelGb.annotateAll(s.results, data, now, fuel)
            val selected = s.selected?.let { FuelGb.annotate(it, data, now, fuel) }
            if (results === s.results && selected === s.selected) s else s.copy(results = results, selected = selected)
        }
    }

    companion object {
        /** A gas station by the map's own icon rule, without the chargers that share its group. */
        fun isFuel(p: Place): Boolean =
            PoiIcons.groupFor(p.name, p.category) == "fuel" &&
                p.category?.lowercase()?.contains("charg") != true

        // One store per process, so a recreated view model keeps the parsed file.
        @Volatile private var shared: FuelGbStore? = null

        private fun storeFor(context: Context, http: okhttp3.OkHttpClient): FuelGbStore = shared ?: synchronized(this) {
            shared ?: FuelGbStore(
                dir = { File(StorageLocation.root(context), FuelGbStore.FOLDER) },
                // A download client per CLAUDE.md: the shared client's call timeout cuts a body short.
                http = http.newBuilder()
                    .callTimeout(java.time.Duration.ZERO)
                    .readTimeout(java.time.Duration.ofSeconds(60))
                    .addInterceptor(app.vela.diag.NetCount)
                    .build(),
            ).also { s ->
                shared = s
                app.vela.ui.MemoryPressure.register { level -> if (app.vela.ui.MemoryPressure.isSevere(level)) s.release() }
            }
        }
    }
}
