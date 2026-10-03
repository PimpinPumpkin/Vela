package app.vela.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Hides every symbol layer except street names while the nav camera swings through a turn or
 * follows a gesture, and shows them again once it has been calm for [SETTLE_MS]. Symbol placement
 * re-runs on every frame the camera rotates, and that is where a turn's frames went: on a 4a demo
 * drive, turns ran 30-49 fps with the map's symbols up and 53-60 with them hidden; no single group
 * of layers carried the cost on its own (SPEC 4.7b).
 *
 * Only layers that were visible when the hide began are touched, and only those are shown again,
 * so a layer another owner hid stays hidden. Revealed symbols fade in through MapLibre's own
 * placement fade.
 */
internal class TurnDeclutter {
    private val hidden = ArrayList<String>()
    private var hiddenOn: Style? = null
    private var calmSinceMs = 0L

    val active: Boolean get() = hiddenOn != null

    /** [moving] = the camera is swinging (or a gesture is moving it) this frame. */
    fun update(style: Style, moving: Boolean, nowMs: Long, onRestore: (Style) -> Unit) {
        if (hiddenOn != null && hiddenOn !== style) { hidden.clear(); hiddenOn = null } // style reloaded
        if (moving) {
            calmSinceMs = 0L
            if (hiddenOn == null) hide(style)
            return
        }
        if (hiddenOn == null) return
        if (calmSinceMs == 0L) calmSinceMs = nowMs
        else if (nowMs - calmSinceMs >= SETTLE_MS) restore(style, onRestore)
    }

    fun restore(style: Style?, onRestore: (Style) -> Unit) {
        val s = hiddenOn
        if (s != null && s === style) {
            runCatching {
                hidden.forEach { id -> s.getLayer(id)?.setProperties(PropertyFactory.visibility(Property.VISIBLE)) }
                onRestore(s)
            }
        }
        hidden.clear(); hiddenOn = null; calmSinceMs = 0L
    }

    private fun hide(style: Style) {
        hiddenOn = style
        runCatching {
            for (layer in style.layers) {
                if (layer !is SymbolLayer || KEEP.any { layer.id.startsWith(it) }) continue
                if (layer.visibility.value == Property.NONE) continue
                layer.setProperties(PropertyFactory.visibility(Property.NONE))
                hidden += layer.id
            }
        }
    }

    companion object {
        /** Camera bearing error (degrees) that counts as a swing, and the calm level after one. */
        const val START_DEG = 10.0
        const val CALM_DEG = 4.0
        const val SETTLE_MS = 500L
        /** Street names stay: the basemap's road names and shields, exit numbers, the nav callouts
         *  (cross streets, the turn's street, the exit), and the arrow itself. */
        private val KEEP = listOf(
            "highway-name-major", "highway-name-minor", "road_shield", "vela-exit-shield",
            "vela-nav-", "vela-me",
        )
    }
}
