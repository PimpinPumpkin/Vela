package app.vela.offline

import java.io.File
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Online, the map's basemap tiles come from a DOWNLOADED region file wherever one covers the tile,
 * and from the network everywhere else, with no change to the map's style (user 2026-10-02).
 *
 * Mounting a downloaded file as the map's source swapped the whole style at every edge of the
 * downloaded data, a full reload and a visible flicker (zooming far out from inside a region did it
 * twice), and streaming instead spent data on a map the phone already holds. This hook sits in
 * MapLibre's own HTTP client: a request for a streamed basemap tile (OpenFreeMap, OpenMapTiles
 * schema, the same schema the region files are baked in) is answered from the file when the tile
 * lies wholly inside the region's boundary, padded by [EDGE_PAD_DEG] (the files are cut to the
 * boundary, and the shipped boundaries are simplified to about 5 km), and the file is a full-depth
 * bake. Everything else goes to the network as before. Offline the file is still mounted as the
 * map's source (MapViewModel.pickBasemapArchive).
 */
object LocalBasemapTiles : Interceptor {
    private val TILE_URL = Regex("""^https://tiles\.openfreemap\.org/planet/[^/]+/(\d+)/(\d+)/(\d+)\.pbf""")
    private const val EDGE_PAD_DEG = 0.05

    private class Archive(val id: String, val file: File, val maxZoom: Int)

    @Volatile private var installed = false

    /** Hands MapLibre an HTTP client carrying this hook (same dispatcher limits as its default).
     *  Must run AFTER MapLibre.getInstance: the HTTP class refuses to load before it. Once. */
    fun installIntoMapLibre() {
        if (installed) return
        installed = true
        runCatching {
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(
                okhttp3.OkHttpClient.Builder()
                    .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 20 })
                    .addInterceptor(this)
                    // After the local hook: a tile answered from a downloaded file is not proof of a network.
                    .addInterceptor(app.vela.core.net.NetHealth.interceptor)
                    .addInterceptor(app.vela.diag.NetCount) // after the local hook too: only what reaches the network
                    .addInterceptor(ReleaseRedirects) // a release file's signed address, kept between range reads
                    .build(),
            )
        }.onFailure { installed = false; android.util.Log.w("VelaLocalTiles", "could not install the tile hook: ${it.message}") }
    }

    @Volatile private var archives: List<Archive> = emptyList()
    private val coveredCache = object : LinkedHashMap<String, String?>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 4096
    }

    @Volatile var served = 0L
        private set

    /** The installed region files, re-read after every download or delete (cheap: a folder listing
     *  and one header read per file). The world file and shallow bakes are left out. */
    private var signature = ""

    fun refresh(installed: Map<String, File>, worldId: String, fullZoom: Int) {
        val sig = installed.entries.sortedBy { it.key }.joinToString("|") { (id, f) -> "$id:${f.length()}:${f.lastModified()}" }
        if (sig == signature) return
        signature = sig
        archives = installed.filter { (id, f) -> id != worldId && f.exists() }
            .mapNotNull { (id, f) ->
                val h = PmtilesReader.header(f) ?: return@mapNotNull null
                if (h.maxZoom < fullZoom) null else Archive(id, f, h.maxZoom)
            }
        synchronized(coveredCache) { coveredCache.clear() }
        android.util.Log.i("VelaLocalTiles", "downloaded basemaps usable online: ${archives.joinToString { it.id }.ifEmpty { "none" }} (of ${installed.size} installed)")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val list = archives
        if (list.isEmpty()) return chain.proceed(req)
        val m = TILE_URL.find(req.url.toString()) ?: return chain.proceed(req)
        val z = m.groupValues[1].toInt(); val x = m.groupValues[2].toInt(); val y = m.groupValues[3].toInt()
        val bytes = tile(list, z, x, y) ?: return chain.proceed(req)
        served++
        if (served == 1L || served % 50 == 0L) android.util.Log.i("VelaLocalTiles", "basemap tiles from downloaded files: $served")
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "application/x-protobuf")
            .body(bytes.toResponseBody("application/x-protobuf".toMediaType()))
            .build()
    }

    private fun tile(list: List<Archive>, z: Int, x: Int, y: Int): ByteArray? {
        val key = "$z/$x/$y"
        val id = synchronized(coveredCache) {
            if (coveredCache.containsKey(key)) coveredCache[key]
            else coveringId(list, z, x, y).also { coveredCache[key] = it }
        } ?: return null
        val a = list.firstOrNull { it.id == id } ?: return null
        if (z > a.maxZoom) return null
        return PmtilesReader.tileBytes(a.file, z, x, y)
    }

    /** The region whose boundary holds the whole tile with a margin, or null. */
    private fun coveringId(list: List<Archive>, z: Int, x: Int, y: Int): String? {
        val n = 1 shl z
        val w = x.toDouble() / n * 360.0 - 180.0
        val e = (x + 1).toDouble() / n * 360.0 - 180.0
        fun lat(ty: Int) = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2.0 * ty / n))))
        val north = lat(y); val south = lat(y + 1)
        val pts = listOf(
            north + EDGE_PAD_DEG to w - EDGE_PAD_DEG, north + EDGE_PAD_DEG to e + EDGE_PAD_DEG,
            south - EDGE_PAD_DEG to w - EDGE_PAD_DEG, south - EDGE_PAD_DEG to e + EDGE_PAD_DEG,
        )
        return list.firstOrNull { a -> pts.all { (la, lo) -> RegionPolys.covers(a.id, la, lo) == true } }?.id
    }
}
