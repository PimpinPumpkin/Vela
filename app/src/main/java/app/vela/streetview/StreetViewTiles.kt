package app.vela.streetview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import app.vela.core.data.MapDataSource
import app.vela.core.model.StreetViewPano
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Fetches a panorama's equirectangular tiles at one zoom level and stitches them into a single
 * bitmap for the GL sphere.
 *
 * The pyramid is NOT one fixed shape: modern car panos are `512·2^z` wide (…4096, 8192, 16384),
 * but pre-2016 captures are `416·2^z` (…3328, 6656, 13312) and sometimes stop at 4 levels - so
 * the grid MUST be sized from the pano's own per-level dimensions ([StreetViewPano.levelDims]).
 * Assuming the modern shape requested tiles past an old pano's grid edge, which failed and
 * stitched as BLACK BANDS over part of the sphere in time travel (device report 2026-07-16).
 * Edge tiles are cropped to the image bounds (they're padded past them), and a non-4096×2048
 * result is scaled up to it: the GL sphere needs a power-of-two texture for the 360° wrap, and
 * an old capture's full equirect still covers 360°×180°, so scaling is geometrically exact.
 *
 * Targets the highest level ≤4096 wide (32 tiles, ~1 MB JPEG, one 32 MB texture) - visibly
 * sharper than the v1 zoom-2 (user 2026-07-15), within the GPU's max texture size, freed the
 * moment the viewer closes. NEVER the full pyramid (16384×8192 ≈ 400 MB decoded).
 */
object StreetViewTiles {
    private const val OUT_W = 4096
    private const val OUT_H = 2048

    suspend fun load(source: MapDataSource, pano: StreetViewPano): Bitmap? =
        load(source, pano.panoId, pano.tileSize, pano.levelDims)

    /** Load by pano id (time-travel fallback when the historical metadata fetch failed - the
     *  base pano's pyramid is the best guess we have). */
    suspend fun load(
        source: MapDataSource,
        panoId: String,
        tileSize: Int = 512,
        levelDims: List<Pair<Int, Int>> = emptyList(),
    ): Bitmap? {
        // The pano's own pyramid, else the modern default shape.
        val dims = levelDims.ifEmpty { List(6) { 512 * (1 shl it) to 256 * (1 shl it) } }
        val z = dims.indexOfLast { it.first in 1..OUT_W }.coerceAtLeast(0)
        val (w, h) = dims[z]
        val ts = tileSize.coerceAtLeast(1)
        val cols = (w + ts - 1) / ts
        val rows = (h + ts - 1) / ts
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val placed = coroutineScope {
            val jobs = ArrayList<Deferred<Triple<Int, Int, Bitmap>?>>(cols * rows)
            for (y in 0 until rows) for (x in 0 until cols) {
                jobs += async(Dispatchers.IO) {
                    val bytes = source.streetViewTile(panoId, x, y, z) ?: return@async null
                    val tile = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@async null
                    Triple(x, y, tile)
                }
            }
            var n = 0
            for (job in jobs) {
                val t = job.await() ?: continue
                val x = t.first * ts
                val y = t.second * ts
                // Crop edge tiles to the image bounds - past them is padding, not imagery.
                val srcW = minOf(ts, w - x).coerceAtMost(t.third.width)
                val srcH = minOf(ts, h - y).coerceAtMost(t.third.height)
                if (srcW > 0 && srcH > 0) {
                    canvas.drawBitmap(t.third, Rect(0, 0, srcW, srcH), Rect(x, y, x + srcW, y + srcH), null)
                    n++
                }
                t.third.recycle()
            }
            n
        }
        if (placed == 0) {
            bmp.recycle()
            return null
        }
        if (w == OUT_W && h == OUT_H) return bmp
        val scaled = Bitmap.createScaledBitmap(bmp, OUT_W, OUT_H, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }
}
