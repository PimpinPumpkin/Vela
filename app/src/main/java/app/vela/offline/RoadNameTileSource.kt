package app.vela.offline

import android.content.Context
import app.vela.core.VelaConfig
import app.vela.core.data.naming.RoadNameTiles
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

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .build()
    @Volatile private var template: String? = null
    @Volatile private var templateAt = 0L

    fun install(context: Context) {
        val app = context.applicationContext
        RoadNameTiles.fetch = { z, x, y -> fetch(app, z, x, y) }
    }

    private suspend fun fetch(context: Context, z: Int, x: Int, y: Int): ByteArray? = withContext(Dispatchers.IO) {
        File(StorageLocation.root(context), "basemap").listFiles { f -> f.extension == "pmtiles" }?.forEach { f ->
            PmtilesReader.tileBytes(f, z, x, y)?.let { return@withContext it }
        }
        val tpl = template() ?: return@withContext null
        val url = tpl.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", VelaConfig.VELA_UA).build()).execute()
                .use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()
    }

    private fun template(): String? {
        val now = System.currentTimeMillis()
        template?.let { if (now - templateAt < TEMPLATE_TTL_MS) return it }
        val t = runCatching {
            http.newCall(Request.Builder().url(TILEJSON).header("User-Agent", VelaConfig.VELA_UA).build()).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body!!.string()).getJSONArray("tiles").getString(0)
            }
        }.getOrNull()
        if (t != null) { template = t; templateAt = now }
        return t ?: template
    }
}
