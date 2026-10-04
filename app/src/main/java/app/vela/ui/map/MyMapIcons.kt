package app.vela.ui.map

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.graphics.drawable.toBitmap
import coil.Coil
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The icon images of a custom map's markers (issue #669): fetched once through the app's image
 * loader (so they are cached on disk and counted like every Google image), kept here as bitmaps
 * for the map, which can only draw an image it has been handed. [tick] moves when one arrives so
 * the pins are drawn again with it; until then a pin wears Vela's own pin in its color.
 */
object MyMapIcons {
    val tick = mutableIntStateOf(0)
    private val loaded = ConcurrentHashMap<String, Bitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private const val MAX = 400

    /** The drawn width in dp before the layer's own scale: a little wider than Vela's pin circle. */
    private const val WIDTH_DP = 23f

    fun get(url: String): Bitmap? = loaded[url]

    fun key(url: String) = "vela-mm-" + Integer.toHexString(url.hashCode())

    suspend fun load(context: Context, urls: Collection<String>) {
        val want = urls.filter { !loaded.containsKey(it) && !failed.contains(it) }.distinct()
        if (want.isEmpty()) return
        var got = 0
        withContext(Dispatchers.IO) {
            for (u in want) {
                if (loaded.size >= MAX) break
                val bmp = runCatching {
                    Coil.imageLoader(context).execute(ImageRequest.Builder(context).data(u).allowHardware(false).build()).drawable?.toBitmap()
                }.getOrNull()
                if (bmp == null) { failed += u; continue }
                // MapLibre sizes an image by its density: say what density makes it WIDTH_DP wide.
                bmp.density = (bmp.width / WIDTH_DP * 160f).toInt().coerceAtLeast(160)
                loaded[u] = bmp; got++
            }
        }
        if (got > 0) tick.intValue++
    }
}
