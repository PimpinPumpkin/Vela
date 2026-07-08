package app.vela.offline

import android.content.Context
import app.vela.core.data.OfflinePacks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline PLACE packs — per-region SQLite databases of the whole region's OSM POIs, addresses and
 * street names (built by `scripts/build-poi-region.sh`, hosted like the routing graphs), so a state
 * download makes the entire state searchable offline (Organic-Maps-style), not just saved map areas.
 * Sibling of [RoutingGraphStore]; a pack is pulled automatically alongside its region's routing
 * graph and deleted with it. Packs share the routing catalog's region ids, so the manifest rows
 * reuse [RoutingRegion]. Installed packs are registered in [OfflinePacks], where the core stores
 * (OfflinePoiStore / OfflineAddressStore) query them.
 */
@Singleton
class PoiPackStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val packsRoot = File(context.filesDir, "poipacks")

    // Packs are hundreds of MB — same no-call-timeout rule as every large download (the shared
    // client's 12 s scrape cap would abort the body mid-read, silently).
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun installedIds(): Set<String> =
        packsRoot.listFiles { f -> f.extension == "db" }?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()

    fun installedPaths(): List<String> =
        packsRoot.listFiles { f -> f.extension == "db" }?.map { it.absolutePath } ?: emptyList()

    /** Register every installed pack with the core stores. Call at startup and after install/delete. */
    fun registerPacks() = OfflinePacks.reload(installedPaths())

    /** Fetch the pack catalog. Same manifest shape as routing graphs, so rows reuse [RoutingRegion]. */
    suspend fun manifest(manifestUrl: String): List<RoutingRegion> = withContext(Dispatchers.IO) {
        runCatching {
            val json = http.newCall(Request.Builder().url(manifestUrl).build()).execute()
                .use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
            val arr = JSONObject(json).getJSONArray("regions")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val b = o.getJSONArray("bbox")
                RoutingRegion(
                    o.getString("id"), o.getString("name"), o.getString("url"), o.optInt("sizeMb"),
                    b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Download + unzip [region]'s pack to `poipacks/<id>.db` and register it. 0..100 progress. */
    suspend fun download(region: RoutingRegion, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        packsRoot.mkdirs()
        val dest = File(packsRoot, "${region.id}.db")
        val tmp = File(packsRoot, "${region.id}.db.tmp")
        runCatching {
            tmp.delete()
            downloadHttp.newCall(Request.Builder().url(region.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val total = resp.body!!.contentLength()
                var lastPct = -1
                val counting = CountingInputStream(resp.body!!.byteStream()) { read ->
                    if (total > 0) (100 * read / total).toInt().let { p -> if (p != lastPct) { lastPct = p; onProgress(p) } }
                }
                ZipInputStream(counting).use { zis ->
                    var e = zis.nextEntry
                    var wrote = false
                    while (e != null) {
                        if (!e.isDirectory && e.name.endsWith(".db")) {
                            tmp.outputStream().use { zis.copyTo(it) }
                            wrote = true
                        }
                        e = zis.nextEntry
                    }
                    check(wrote) { "pack zip held no .db" }
                }
            }
            // SQLite magic check — a truncated/error body must not install as a "pack".
            check(tmp.length() > 16 && tmp.inputStream().use { s ->
                val magic = ByteArray(15); s.read(magic); String(magic) == "SQLite format 3"
            }) { "downloaded pack is not a SQLite db" }
            dest.delete()
            check(tmp.renameTo(dest)) { "could not install pack (rename failed)" }
            registerPacks()
            onProgress(100)
            true
        }.getOrElse { tmp.delete(); false }
    }

    fun delete(id: String) {
        File(packsRoot, "$id.db").delete()
        registerPacks()
    }
}
