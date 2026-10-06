package app.vela.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Hides every symbol layer except street names while the nav camera swings through a turn, and
 * shows them again once it has been calm for [SETTLE_MS]. (Not for a hand gesture since
 * 2026-10-06: strokes with rests between flipped the set once per stroke.) Symbol placement
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
    private var movingFrames = 0

    val active: Boolean get() = hiddenOn != null

    /** [moving] = the camera is swinging (or a gesture is moving it) this frame. */
    fun update(style: Style, moving: Boolean, nowMs: Long, onRestore: (Style) -> Unit) {
        if (hiddenOn != null && hiddenOn !== style) { hidden.clear(); hiddenOn = null } // style reloaded
        if (moving) {
            calmSinceMs = 0L
            // Every flip re-lays out every tile of every source the hidden layers draw from (a
            // visibility change is a layout change), which on a 4a is 300-680 ms of worker CPU
            // and a 60-200 ms map frame per flip; a swing that lasts one frame is not worth it.
            if (hiddenOn == null && ++movingFrames >= ARM_FRAMES) hide(style)
            return
        }
        movingFrames = 0
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
        hidden.clear(); hiddenOn = null; calmSinceMs = 0L; movingFrames = 0
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
        /** Camera bearing error (degrees) that counts as a swing, and the calm level after one.
         *  START_DEG was 10 until 2026-10-03: with the camera's 1.6 s bearing constant a gentle
         *  curve holds a steady 8-11 degree error, so the layers flipped on and off through every
         *  bend (a 4a demo drive: six flips in 24 s, one restore followed 220 ms later by a hide),
         *  and each flip costs a relayout. A real turn's error passes 15 within a frame or two. */
        const val START_DEG = 15.0
        const val CALM_DEG = 4.0
        /** Calm this long before the layers return; 500 ms let an S-bend flip twice. */
        const val SETTLE_MS = 1_000L
        /** Swing frames in a row before the hide: one spiking frame never flips the map. */
        const val ARM_FRAMES = 3
        /** Street names stay: the basemap's road names and shields, exit numbers, the nav callouts
         *  (cross streets, the turn's street, the exit), and the arrow itself. */
        private val KEEP = listOf(
            "highway-name-major", "highway-name-minor", "road_shield", "vela-exit-shield",
            "vela-nav-", "vela-me",
        )
    }
}
