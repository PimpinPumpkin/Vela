package app.vela.ui.map

import android.graphics.PointF
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MLLatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Key-driven map control for D-pad-only operation (no touchscreen). [VelaMapView] wires
 * itself in when handed one of these; MapScreen's focusable map target and the on-screen
 * zoom buttons call the public methods. Kept as its OWN seam (not a refactor of
 * VelaMapView's gesture listeners) so upstream changes to the touch path merge cleanly.
 *
 * All methods are safe to call before the map is ready — they just no-op.
 */
class MapDpadController {
    internal var mapView: MapView? = null
    internal var map: MapLibreMap? = null

    /** The SAME tap-resolution logic the touch click listener runs (pins → ambient POIs →
     *  alternate routes → basemap POIs), so OK-at-crosshair behaves exactly like a tap. */
    internal var onTap: ((MLLatLng) -> Boolean)? = null
    internal var onLongPress: ((MLLatLng) -> Unit)? = null

    /** Marks the camera move as user-driven (feeds "Search this area" / nav detach),
     *  mirroring what a drag gesture does. */
    internal var markPan: (() -> Unit)? = null

    /** Reports a key-driven zoom target so nav adopts it as the manual zoom override
     *  (exactly like a pinch during nav). */
    internal var markZoom: ((Double) -> Unit)? = null

    /** Pan by a fraction of the view size (e.g. 0.18f of the width per D-pad press). */
    fun panBy(fracX: Float, fracY: Float) {
        val m = map ?: return
        val v = mapView ?: return
        if (v.width <= 0 || v.height <= 0) return
        val target = m.projection.fromScreenLocation(
            PointF(v.width / 2f + v.width * fracX, v.height / 2f + v.height * fracY),
        )
        markPan?.invoke()
        m.easeCamera(CameraUpdateFactory.newLatLng(target), 150)
    }

    fun zoomBy(delta: Double) {
        val m = map ?: return
        markZoom?.invoke(m.cameraPosition.zoom + delta)
        m.easeCamera(CameraUpdateFactory.zoomBy(delta), 200)
    }

    /** A "tap" at the crosshair (view centre). Returns true if something was hit. */
    fun selectAtCenter(): Boolean {
        val m = map ?: return false
        val v = mapView ?: return false
        if (v.width <= 0 || v.height <= 0) return false
        val c = m.projection.fromScreenLocation(PointF(v.width / 2f, v.height / 2f))
        return onTap?.invoke(c) ?: false
    }

    /** A long-press at the crosshair — drops a pin + reverse-geocodes, like touch. */
    fun longPressAtCenter() {
        val m = map ?: return
        val v = mapView ?: return
        if (v.width <= 0 || v.height <= 0) return
        val c = m.projection.fromScreenLocation(PointF(v.width / 2f, v.height / 2f))
        onLongPress?.invoke(c)
    }
}
