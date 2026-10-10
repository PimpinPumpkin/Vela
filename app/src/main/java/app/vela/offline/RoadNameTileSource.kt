package app.vela.offline

import android.content.Context
import app.vela.core.VelaConfig
import app.vela.core.data.naming.RoadNameTiles
import app.vela.ui.MemoryPressure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Where [RoadNameTiles] reads street names from (issue #478): a downloaded region's basemap archive
 * when it holds the tile, else the live tile host the map itself draws from (OpenFreeMap), whose
 * dated tile path comes from its TileJSON, re-read every few hours.
 */
object RoadNameTileSource {
    private const val TILEJSON = "https://tiles.openfreemap.org/planet"
    private const val TEMPLATE_TTL_MS = 6 * 60 * 60 * 1000L

    // Short: a tile is tens of kilobytes, and the route is waiting on this with a deadline of its own.
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
        .build()
    @Volatile private var template: String? = null
    @Volatile private var templateAt = 0L

    fun install(context: Context) {
        val app = context.applicationContext
        RoadNameTiles.fetch = { z, x, y -> fetch(app, z, x, y) }
        // The tiles kept for the next route (names, roads and their bytes) go when memory is short.
        MemoryPressure.register { level -> if (MemoryPressure.isSevere(level)) RoadNameTiles.clearCache() }
    }

    private suspend fun fetch(context: Context, z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        File(StorageLocation.root(context), "basemap").listFiles { f -> f.extension == "pmtiles" }?.forEach { f ->
            PmtilesReader.tileBytes(f, z, x, y)?.let { return@withContext it }
        }
        val tpl = template() ?: return@withContext null
        val url = tpl.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        get(url)
    }

    /** The body of [url], or null. Cancelling the caller cancels the request: a blocking call
     *  would hold a route that has given up on its street names until the socket timed out. */
    private suspend fun get(url: String): ByteArray? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", VelaConfig.VELA_UA).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { if (cont.isActive) cont.resumeWith(Result.success(null)) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val bytes = runCatching { response.use { r -> if (r.isSuccessful) r.body?.bytes() else null } }.getOrNull()
                if (cont.isActive) cont.resumeWith(Result.success(bytes))
            }
        })
    }

    private suspend fun template(): String? {
        val now = System.currentTimeMillis()
        template?.let { if (now - templateAt < TEMPLATE_TTL_MS) return it }
        val t = get(TILEJSON)?.let { runCatching { JSONObject(String(it)).getJSONArray("tiles").getString(0) }.getOrNull() }
        if (t != null) { template = t; templateAt = now }
        return t ?: template
    }
}
