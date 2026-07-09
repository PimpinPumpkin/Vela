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

    /** Fetch the pack catalog. Same base shape as the routing manifest plus the update fields
     *  (rev / row counts / optional delta), so rows reuse [RoutingRegion]. */
    suspend fun manifest(manifestUrl: String): List<RoutingRegion> = withContext(Dispatchers.IO) {
        runCatching {
            val json = http.newCall(Request.Builder().url(manifestUrl).build()).execute()
                .use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body!!.string() }
            val arr = JSONObject(json).getJSONArray("regions")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val b = o.getJSONArray("bbox")
                val counts = o.optJSONObject("counts")?.let { c ->
                    c.keys().asSequence().associateWith { k -> c.getLong(k) }
                } ?: emptyMap()
                val delta = o.optJSONObject("delta")
                RoutingRegion(
                    o.getString("id"), o.getString("name"), o.getString("url"), o.optInt("sizeMb"),
                    b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3),
                    rev = o.optInt("rev"),
                    counts = counts,
                    deltaUrl = delta?.optString("url")?.takeIf { it.isNotBlank() },
                    deltaFromRev = delta?.optInt("fromRev") ?: 0,
                    deltaSizeMb = delta?.optInt("sizeMb") ?: 0,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** The installed revision of [id]'s pack, 0 when unknown (deleted, or installed before revs existed). */
    fun installedRev(id: String): Int = readRevs().optInt(id, 0)

    private fun readRevs(): JSONObject = runCatching {
        JSONObject(File(packsRoot, "revs.json").readText())
    }.getOrDefault(JSONObject())

    // Guards the revs.json read-modify-write (parallel downloads / update + delete),
    // same shape as the index guard in OverlayTileStore and RoutingGraphStore.
    private val revsLock = Any()

    private fun writeRev(id: String, rev: Int) = synchronized(revsLock) {
        packsRoot.mkdirs()
        File(packsRoot, "revs.json").writeText(readRevs().put(id, rev).toString())
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
            writeRev(region.id, region.rev)
            registerPacks()
            onProgress(100)
            true
        }.getOrElse { tmp.delete(); false }
    }

    /**
     * Update an installed pack in place with a row-level delta (a small SQLite of del_ and ins_
     * tables built by CI's poipack_delta.py) instead of re-downloading the whole pack. Only valid when the
     * installed revision equals the delta's fromRev — the caller checks that and falls back to a full
     * [download] when this returns false. The apply runs in one transaction and the result is verified
     * against the manifest's per-table row counts, so a bad patch can't leave a half-updated pack.
     */
    suspend fun applyDelta(region: RoutingRegion, onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val deltaUrl = region.deltaUrl ?: return@withContext false
        val dest = File(packsRoot, "${region.id}.db")
        if (!dest.exists() || region.counts.isEmpty()) return@withContext false
        val tmp = File(packsRoot, "${region.id}.delta.tmp")
        runCatching {
            tmp.delete()
            downloadHttp.newCall(Request.Builder().url(deltaUrl).build()).execute().use { resp ->
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
                        if (!e.isDirectory && e.name.endsWith(".db")) { tmp.outputStream().use { zis.copyTo(it) }; wrote = true }
                        e = zis.nextEntry
                    }
                    check(wrote) { "delta zip held no .db" }
                }
            }
            check(tmp.length() > 16 && tmp.inputStream().use { s ->
                val magic = ByteArray(15); s.read(magic); String(magic) == "SQLite format 3"
            }) { "downloaded delta is not a SQLite db" }

            // Close the read-only handle on this pack while we write it (other packs stay searchable).
            OfflinePacks.reload(installedPaths().filter { it != dest.absolutePath })
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dest.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            )
            try {
                db.execSQL("ATTACH DATABASE '${tmp.absolutePath.replace("'", "''")}' AS d")
                db.beginTransaction()
                try {
                    // Delete by full-row match (rows have no surrogate keys; a row's identity is its
                    // tuple). The join drives through each table's existing index; IS handles NULLs.
                    for ((table, cols) in TABLE_COLUMNS) {
                        val match = cols.joinToString(" AND ") { c -> "t.$c IS del.$c" }
                        db.execSQL(
                            "DELETE FROM $table WHERE rowid IN " +
                                "(SELECT t.rowid FROM $table t JOIN d.del_$table del ON $match)",
                        )
                        db.execSQL("INSERT INTO $table SELECT ${cols.joinToString(",")} FROM d.ins_$table")
                    }
                    // Verify against the manifest's expected counts before committing.
                    for ((table, expected) in region.counts) {
                        val got = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                            c.moveToFirst(); c.getLong(0)
                        }
                        check(got == expected) { "$table has $got rows, manifest says $expected" }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                runCatching { db.execSQL("DETACH DATABASE d") }
                db.close()
            }
            writeRev(region.id, region.rev)
            tmp.delete()
            registerPacks()
            onProgress(100)
            true
        }.getOrElse {
            tmp.delete()
            registerPacks() // reopen whatever state the pack is in (transaction rolled back on failure)
            false
        }
    }

    fun delete(id: String) {
        File(packsRoot, "$id.db").delete()
        synchronized(revsLock) {
            packsRoot.mkdirs()
            File(packsRoot, "revs.json").writeText(readRevs().apply { remove(id) }.toString())
        }
        registerPacks()
    }

    companion object {
        // Pack tables and their columns, matching scripts/poipack_build.py (and poipack_delta.py's
        // del_/ins_ tables). Keep in sync with both.
        private val TABLE_COLUMNS = mapOf(
            "poi" to listOf("id", "name", "lat", "lng", "category", "address", "phone", "website", "hours"),
            "streetname" to listOf("sid", "street", "street_norm"),
            "addr" to listOf("hn", "sid", "city", "lat", "lng"),
            "streetpt" to listOf("sid", "lat", "lng"),
        )
    }
}
