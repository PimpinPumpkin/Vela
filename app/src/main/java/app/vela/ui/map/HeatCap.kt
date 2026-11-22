package app.vela.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import org.maplibre.android.maps.MapView

/**
 * The map's frame cap while the phone is hot (issue #795). From Android's thermal status
 * `SEVERE` up, a drive draws the map at [HOT_NAV_MAX_FPS] instead of the display's rate, so the
 * phone has room to render the voice and cool. Set by the thermal listener in `MapViewModel`.
 */
object HeatCap {
    val hot = mutableStateOf(false)

    const val HOT_NAV_MAX_FPS = 30
    // MapLibre ignores a cap of 0 or less; this one makes its frame budget well under a nanosecond.
    private const val UNCAPPED = Int.MAX_VALUE

    @Composable
    fun Apply(mapView: MapView, ready: Boolean, navMode: Boolean) {
        val cap = navMode && hot.value
        val capped = androidx.compose.runtime.remember(mapView) { booleanArrayOf(false) } // what this view's renderer has
        LaunchedEffect(cap, ready, mapView) {
            if (!ready) return@LaunchedEffect
            // Throws before the renderer exists; the next key change tries again.
            if (cap == capped[0]) return@LaunchedEffect
            if (runCatching { mapView.setMaximumFps(if (cap) HOT_NAV_MAX_FPS else UNCAPPED) }.isFailure) return@LaunchedEffect
            capped[0] = cap
            app.vela.diag.TripNote.add(if (cap) "map: capped at $HOT_NAV_MAX_FPS fps, phone hot" else "map: frame cap off")
        }
    }
}
