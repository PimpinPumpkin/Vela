package app.vela.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.sources.Source

/**
 * The part of a loaded MapLibre style the palette functions need: a layer by id, a source by id,
 * the layer list. A [Style] has all three; a [MapSnapshotter] (the Android Auto renderer) has the
 * first two and no list, and until 2026-09-21 the car map could not be themed at all because every
 * palette function took a [Style]. The same `applyMapTheme` now runs on both, so the car screen
 * draws Vela's own colors instead of the stock Liberty look.
 */
interface StyleLayers {
    fun getLayer(id: String): Layer?
    fun getSource(id: String): Source?
    val layers: List<Layer>
}

class StyleHost(private val style: Style) : StyleLayers {
    override fun getLayer(id: String): Layer? = style.getLayer(id)
    override fun getSource(id: String): Source? = style.getSource(id)
    override val layers: List<Layer> get() = style.layers
}

/** A snapshotter exposes layers and sources by id only; the loops over the whole list (Vela's own
 *  runtime layers, none of which exist on the car map) see nothing, which is right. */
class SnapshotterHost(private val snapshotter: MapSnapshotter) : StyleLayers {
    override fun getLayer(id: String): Layer? = runCatching { snapshotter.getLayer(id) }.getOrNull()
    override fun getSource(id: String): Source? = runCatching { snapshotter.getSource(id) }.getOrNull()
    override val layers: List<Layer> get() = emptyList()
}
