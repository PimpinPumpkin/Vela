package app.vela.ui.map

import android.graphics.PointF
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MLLatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * Key-driven map control for D-pad-only operation (no touchscreen). [VelaMapView] wires
 * itself in when handed one of these; MapScreen's focusable map target, the on-screen
 * zoom buttons and the map keys ([app.vela.ui.MapKeys]) call the public methods. Kept as its OWN seam (not a refactor of
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

    /** Reports a key-driven turn of the map, as a finger turn reports itself (MapKeys). */
    internal var markRotate: (() -> Unit)? = null

    /** Pan by a fraction of the view size (e.g. 0.18f of the width per D-pad press). The step is
     *  taken from where the camera's target is drawn, which is off the view's middle while a
     *  sheet or panel pads the map: stepping from the middle added that offset to every press. */
    fun panBy(fracX: Float, fracY: Float) {
        val m = map ?: return
        val v = mapView ?: return
        if (v.width <= 0 || v.height <= 0) return
        val from = m.projection.toScreenLocation(m.cameraPosition.target ?: return)
        val target = m.projection.fromScreenLocation(
            PointF(from.x + v.width * fracX, from.y + v.height * fracY),
        )
        markPan?.invoke()
        m.easeCamera(CameraUpdateFactory.newLatLng(target), 150)
    }

    fun zoomBy(delta: Double) {
        val m = map ?: return
        markZoom?.invoke(m.cameraPosition.zoom + delta)
        m.easeCamera(CameraUpdateFactory.zoomBy(delta), 200)
    }

    /** [zoomBy] around a point given in window pixels, which stays where it is on screen.
     *  `CameraUpdateFactory.zoomBy(amount, point)` moves that point to the middle instead. */
    fun zoomAt(delta: Double, windowX: Float, windowY: Float) {
        val m = map ?: return
        val p = viewPoint(windowX, windowY) ?: return zoomBy(delta)
        val cp = m.cameraPosition
        val target = cp.target ?: return zoomBy(delta)
        markZoom?.invoke(cp.zoom + delta)
        // The map scales by s about the target's screen point c. For p to stay put, the new target
        // is p - (p - c) / s, in today's projection.
        val c = m.projection.toScreenLocation(target)
        val s = Math.pow(2.0, delta).toFloat()
        val next = m.projection.fromScreenLocation(PointF(p.x - (p.x - c.x) / s, p.y - (p.y - c.y) / s))
        m.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                org.maplibre.android.camera.CameraPosition.Builder(cp).target(next).zoom(cp.zoom + delta).build(),
            ),
            200,
        )
    }

    /** Turns the map on screen by [clockwiseDeg]; the camera's bearing moves the other way. With a
     *  point in window pixels the turn is around that point, which stays where it is on screen;
     *  without one it is around the camera's target. Does nothing under North lock, which turns
     *  rotation off for touch too. */
    fun rotateBy(clockwiseDeg: Double, windowX: Float? = null, windowY: Float? = null) {
        val m = map ?: return
        if (app.vela.ui.NorthLock.on.value) return
        markRotate?.invoke()
        val cp = m.cameraPosition
        val bearing = (cp.bearing - clockwiseDeg).mod(360.0)
        val p = if (windowX != null && windowY != null) viewPoint(windowX, windowY) else null
        val target = cp.target
        if (p == null || target == null) {
            m.easeCamera(CameraUpdateFactory.bearingTo(bearing), 150)
            return
        }
        // The map turns about the target's screen point c. For p to stay put, the new target is the
        // point that lands on c once the turn is done: p - R(-angle)(p - c), in today's projection.
        val c = m.projection.toScreenLocation(target)
        val a = Math.toRadians(clockwiseDeg)
        val cos = kotlin.math.cos(a).toFloat()
        val sin = kotlin.math.sin(a).toFloat()
        val dx = p.x - c.x
        val dy = p.y - c.y
        val next = m.projection.fromScreenLocation(PointF(p.x - (dx * cos + dy * sin), p.y - (-dx * sin + dy * cos)))
        m.easeCamera(
            CameraUpdateFactory.newCameraPosition(
                org.maplibre.android.camera.CameraPosition.Builder(cp).target(next).bearing(bearing).build(),
            ),
            150,
        )
    }

    /** A "tap" at the crosshair (view center). Returns true if something was hit. */
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

    /** A tap at a point given in window pixels ([MapCover.center]), through the same tap lambda. */
    fun tapAt(windowX: Float, windowY: Float): Boolean {
        val c = latLngAtWindow(windowX, windowY) ?: return false
        return onTap?.invoke(c) ?: false
    }

    /** A long press at a point given in window pixels. */
    fun longPressAt(windowX: Float, windowY: Float) {
        val c = latLngAtWindow(windowX, windowY) ?: return
        onLongPress?.invoke(c)
    }

    private fun latLngAtWindow(windowX: Float, windowY: Float): MLLatLng? {
        val m = map ?: return null
        val p = viewPoint(windowX, windowY) ?: return null
        return m.projection.fromScreenLocation(p)
    }

    /** A point in window pixels, in the map view's own pixels. */
    private fun viewPoint(windowX: Float, windowY: Float): PointF? {
        val v = mapView ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        val at = IntArray(2)
        v.getLocationInWindow(at)
        return PointF(windowX - at[0], windowY - at[1])
    }
}
