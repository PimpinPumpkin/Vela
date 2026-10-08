package app.vela.car.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import app.vela.core.data.tiles.MapStyle
import app.vela.core.model.LatLng
import app.vela.core.model.destinationPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng as MLLatLng
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume

/**
 * Renders one still image of the Vela map for the media card ([VelaMediaService]).
 *
 * Android Auto's media UI draws the card itself and takes a single bitmap as the artwork, so this
 * produces that bitmap off-screen with MapLibre's [MapSnapshotter] (the same headless path the
 * projected car map uses in `CarMapRenderer`), themed with Vela's palette, with the route and the
 * puck drawn on top. It is not a live surface: a fresh image is asked for when the drive advances,
 * a second or so apart, which is all a glance at a media card needs.
 *
 * One snapshotter is kept per image size and reused. The palette is applied once, on the first
 * image (a JSON style finishes parsing before the snapshotter's style observer runs, so the first
 * frame comes back unthemed and is re-rendered).
 */
class MapCardRenderer(context: Context) {
    private val appContext = context.applicationContext

    private var snapshotter: MapSnapshotter? = null
    private var snapW = 0
    private var snapH = 0
    private var styleLayerIds: List<String> = emptyList()
    private var themed = false
    private var themedDark = false

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#1A73E8")
    }
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#174EA6")
    }

    /**
     * An image of the map at [zoom] with [route] drawn under the [puck]. Null when the snapshot
     * could not be produced. Call off the main thread; the snapshotter runs on the main looper.
     *
     * Overview ([pov] false): north-up, [center] in the middle. POV ([pov] true): heading-up along
     * [bearing], [center] (the puck) low in the frame so the road ahead fills it, like the drive
     * view. Overview reads at a glance; POV matches what the driver sees.
     */
    suspend fun render(
        center: LatLng,
        zoom: Double,
        bearing: Double,
        dark: Boolean,
        width: Int,
        height: Int,
        route: List<LatLng>? = null,
        puck: LatLng? = null,
        pov: Boolean = false,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return withContext(Dispatchers.Main.immediate) {
            // MapLibre must be initialized on the main thread, before a snapshotter is created.
            runCatching { MapLibre.getInstance(appContext); app.vela.offline.LocalBasemapTiles.installIntoMapLibre() }
            val s = snapshotterFor(width, height) ?: return@withContext null
            // POV: look ahead of the puck so it sits low in the frame (the camera target moves
            // forward along the heading by the puck's offset below center).
            val target = if (pov) {
                val mpp = 78271.517 * kotlin.math.cos(Math.toRadians(center.lat)) / Math.pow(2.0, zoom)
                val ahead = (PUCK_DOWN - 0.5) * height * mpp
                center.destinationPoint(ahead, bearing)
            } else center
            val cam = CameraPosition.Builder()
                .target(MLLatLng(target.lat, target.lng))
                .zoom(zoom)
                .bearing(if (pov) bearing else 0.0)
                .tilt(0.0)
                .build()
            // Re-theme if the palette on the kept snapshotter no longer matches day/night.
            if (themed && themedDark != dark) applyTheme(s, dark)
            val snap = snapshot(s, cam) ?: return@withContext null
            if (!themed) {
                applyTheme(s, dark)
                val themedSnap = snapshot(s, cam) ?: snap
                drawCard(themedSnap, width, height, route, puck)
            } else {
                drawCard(snap, width, height, route, puck)
            }
        }
    }

    fun release() {
        runCatching { snapshotter?.cancel() }
        snapshotter = null
        snapW = 0; snapH = 0
        themed = false
    }

    // ------------------------------------------------------------------------

    private fun snapshotterFor(width: Int, height: Int): MapSnapshotter? {
        snapshotter?.let { if (snapW == width && snapH == height) return it }
        runCatching { snapshotter?.cancel() }
        themed = false
        val effectiveStyle = app.vela.ui.map.MapFonts.effective(MapStyle.LIBERTY.uri)
        val patchedJson = if (effectiveStyle.startsWith("file://")) {
            runCatching { java.io.File(effectiveStyle.removePrefix("file://")).readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        } else null
        styleLayerIds = runCatching {
            val json = patchedJson ?: appContext.assets.open("styles/liberty-roboto.json").bufferedReader().use { it.readText() }
            val layers = org.json.JSONObject(json).getJSONArray("layers")
            (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
        }.getOrDefault(emptyList())
        val opts = MapSnapshotter.Options(width, height)
            .let { if (patchedJson != null) it.withStyleJson(patchedJson) else it.withStyle(MapStyle.LIBERTY.uri) }
            .withPixelRatio(1.0f)
            .withLogo(false)
        snapshotter = runCatching { MapSnapshotter(appContext, opts) }.getOrNull()
        snapW = width; snapH = height
        return snapshotter
    }

    private suspend fun snapshot(s: MapSnapshotter, cam: CameraPosition): MapSnapshot? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                s.setSize(snapW, snapH)
                s.setCameraPosition(cam)
                s.start({ result -> if (cont.isActive) cont.resume(result) }, { if (cont.isActive) cont.resume(null) })
            }.onFailure { if (cont.isActive) cont.resume(null) }
            cont.invokeOnCancellation { runCatching { s.cancel() } }
        }

    private fun applyTheme(s: MapSnapshotter, dark: Boolean) {
        val host = app.vela.ui.map.SnapshotterHost(s, styleLayerIds)
        runCatching {
            app.vela.ui.map.applyMapTheme(
                host,
                dark = dark,
                amoled = dark && app.vela.ui.theme.AppTheme.mode.value == app.vela.ui.theme.ThemeMode.AMOLED,
            )
        }
        themed = true
        themedDark = dark
    }

    private fun drawCard(snap: MapSnapshot, width: Int, height: Int, route: List<LatLng>?, puck: LatLng?): Bitmap? {
        val src = runCatching { snap.bitmap }.getOrNull() ?: return null
        val out = runCatching { src.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return null
        val canvas = Canvas(out)
        if (route != null && route.size >= 2) runCatching { drawRoute(canvas, snap, route) }
        val here = puck ?: route?.firstOrNull()
        if (here != null) runCatching { drawPuck(canvas, snap, here) }
        return out
    }

    private fun project(snap: MapSnapshot, p: LatLng): PointF? =
        runCatching { snap.pixelForLatLng(MLLatLng(p.lat, p.lng)) }.getOrNull()?.let { PointF(it.x, it.y) }

    private fun drawRoute(canvas: Canvas, snap: MapSnapshot, route: List<LatLng>) {
        val path = Path()
        var started = false
        for (p in route) {
            val pt = project(snap, p) ?: continue
            if (!started) { path.moveTo(pt.x, pt.y); started = true } else path.lineTo(pt.x, pt.y)
        }
        if (!started) return
        routeCasing.strokeWidth = 18f
        routePaint.strokeWidth = 11f
        canvas.drawPath(path, routeCasing)
        canvas.drawPath(path, routePaint)
    }

    private fun drawPuck(canvas: Canvas, snap: MapSnapshot, at: LatLng) {
        val pt = project(snap, at) ?: return
        val px = (minOf(canvas.width, canvas.height) * 0.09f).coerceIn(28f, 64f)
        val bmp = runCatching {
            Bitmap.createScaledBitmap(app.vela.ui.map.navPuckBitmap(scale = 1f), px.toInt(), px.toInt(), true)
        }.getOrNull() ?: return
        canvas.drawBitmap(bmp, pt.x - px / 2f, pt.y - px / 2f, null)
    }

    private companion object {
        /** The puck's vertical position in POV, as a fraction from the top: the road ahead fills
         *  the space above it. */
        const val PUCK_DOWN = 0.70
    }
}
