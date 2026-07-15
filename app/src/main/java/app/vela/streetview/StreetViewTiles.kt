package app.vela.streetview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import app.vela.core.data.MapDataSource
import app.vela.core.model.StreetViewPano
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Fetches a panorama's equirectangular tiles at one zoom level and stitches them into a single
 * bitmap for the GL sphere. The Street View pyramid is fixed: at zoom `z` the image is
 * `tileSize·2^z` wide by `tileSize·2^(z-1)` tall (a 2:1 equirect), cut into `2^z × 2^(z-1)`
 * tiles.
 *
 * v1 uses zoom 2 (2048×1024, 8 tiles) - sharp enough on a phone, ~300 KB, a single 8 MB POT
 * texture well under the memory ceiling. NEVER the full pyramid (16384×8192 ≈ 400 MB decoded).
 */
object StreetViewTiles {
    const val DEFAULT_ZOOM = 2

    suspend fun load(source: MapDataSource, pano: StreetViewPano, zoom: Int = DEFAULT_ZOOM): Bitmap? {
        val z = zoom.coerceIn(1, (pano.maxZoom - 1).coerceAtLeast(1))
        val ts = pano.tileSize
        val cols = 1 shl z            // 2^z
        val rows = (cols / 2).coerceAtLeast(1) // 2^(z-1)
        val bmp = Bitmap.createBitmap(cols * ts, rows * ts, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val placed = coroutineScope {
            val jobs = ArrayList<Deferred<Triple<Int, Int, Bitmap>?>>(cols * rows)
            for (y in 0 until rows) for (x in 0 until cols) {
                jobs += async(Dispatchers.IO) {
                    val bytes = source.streetViewTile(pano.panoId, x, y, z) ?: return@async null
                    val tile = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@async null
                    Triple(x, y, tile)
                }
            }
            var n = 0
            for (job in jobs) {
                val t = job.await() ?: continue
                canvas.drawBitmap(t.third, (t.first * ts).toFloat(), (t.second * ts).toFloat(), null)
                t.third.recycle()
                n++
            }
            n
        }
        if (placed == 0) { bmp.recycle(); return null }
        return bmp
    }
}
