package app.vela.car.media

import android.content.Context
import android.graphics.Bitmap
import app.vela.core.model.LatLng
import app.vela.core.model.bearingTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Writes images of the media card ([MapCardRenderer]) to `filesDir`, so the card artwork can be
 * seen and its render time measured without a car. adb-only: runs at app start when
 * `debug.vela.cardshot` is `demo`, over the Davis fixture.
 */
object CardDemo {
    fun maybeRender(context: Context) {
        val mode = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("get", String::class.java, String::class.java).invoke(null, "debug.vela.cardshot", "") as String
        }.getOrDefault("")
        if (mode != "demo") return
        CoroutineScope(Dispatchers.Default).launch {
            val here = LatLng(38.5449, -121.7405)
            val ahead = LatLng(38.5486, -121.7351)
            val route = listOf(here, ahead, LatLng(38.5529, -121.7312))
            val heading = here.bearingTo(ahead)
            val r = MapCardRenderer(context)

            fun write(name: String, bmp: Bitmap?) {
                bmp ?: return
                runCatching { File(context.filesDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }

            // Cold render (loads the style + applies the palette), then warm renders for timing.
            var t = android.os.SystemClock.elapsedRealtime()
            val overview = r.render(here, 15.5, 0.0, false, 500, 500, route, here, pov = false)
            android.util.Log.i("VelaCard", "overview cold ${android.os.SystemClock.elapsedRealtime() - t} ms")
            write("vela-card-overview.png", overview)

            t = android.os.SystemClock.elapsedRealtime()
            val pov = r.render(here, 17.0, heading, false, 500, 500, route, here, pov = true)
            android.util.Log.i("VelaCard", "pov warm ${android.os.SystemClock.elapsedRealtime() - t} ms")
            write("vela-card-pov.png", pov)

            // A run of warm renders along the route, as a drive would ask for them.
            repeat(6) { i ->
                val f = i / 6.0
                val p = LatLng(here.lat + (ahead.lat - here.lat) * f, here.lng + (ahead.lng - here.lng) * f)
                t = android.os.SystemClock.elapsedRealtime()
                r.render(p, 17.0, heading, false, 500, 500, route, p, pov = true)
                android.util.Log.i("VelaCard", "warm[$i] ${android.os.SystemClock.elapsedRealtime() - t} ms")
            }
            android.util.Log.i("VelaCard", "card demo done")
        }
    }
}
