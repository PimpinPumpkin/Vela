package app.vela.offline

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Serves the tiles of a streamed PMTiles archive to MapLibre through Vela's own reader. Since
 * MapLibre 13 its own path for a `pmtiles://https://` source fails the archive's metadata with
 * "incorrect header check" (maplibre-native issue 4462): the cache it keeps for PMTiles sources
 * answers one byte range with another's bytes, and the source never loads. A local archive file
 * loads fine, so only streamed archives come here.
 *
 * [tileSet] turns an archive's address into a plain tile template on [HOST], and the interceptor
 * answers those tile requests from [PmtilesReader.Archive.http]: the header and the directory
 * pages are read once per archive and kept, then each tile is one range read. MapLibre caches
 * the tiles it gets back the way it caches any tile.
 *
 * A rebake replaces the file under the same address. The reader notices on its next read
 * ([PmtilesReader.Archive.replaced]), and the archive is opened again, header and all, before the
 * tile is read, so a running app never reads the new file at the old file's offsets.
 */
object StreamedPmtiles : Interceptor {
    const val HOST = "pmtiles.vela.invalid"
    private const val PREFIX = "pmtiles://"

    private class Entry(val url: String, val archive: PmtilesReader.Archive, val header: PmtilesReader.Header)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val reopenLock = Any()

    /** Tests swap the HTTP client. */
    @Volatile internal var clientOverride: okhttp3.OkHttpClient? = null
    private val client get() = clientOverride ?: defaultClient
    private val defaultClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .addInterceptor(ReleaseRedirects) // the signed address of a release file, kept between reads
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Whether [uri] names a streamed archive this object serves (`pmtiles://https://...`). */
    fun serves(uri: String): Boolean = uri.startsWith(PREFIX + "https://") || uri.startsWith(PREFIX + "http://")

    /**
     * The tile set for the archive at [uri]: its tiles come through [HOST], its zoom range from
     * its header. The first call for an archive reads the header over the network, so call it on
     * IO; later calls answer from memory. Null when the header cannot be read.
     */
    fun tileSet(uri: String): org.maplibre.android.style.sources.TileSet? {
        val url = uri.removePrefix(PREFIX)
        val key = keyOf(url)
        val e = entries[key] ?: (open(url) ?: return null).also { entries[key] = it }
        return org.maplibre.android.style.sources.TileSet("2.2.0", "https://$HOST/$key/{z}/{x}/{y}.pbf").apply {
            minZoom = e.header.minZoom.toFloat()
            maxZoom = e.header.maxZoom.toFloat()
        }
    }

    /** The archive's source object for a style JSON, from memory only: null until [tileSet] has
     *  read its header, so a caller on the main thread never waits on the network. */
    fun sourceJson(uri: String): String? {
        val key = keyOf(uri.removePrefix(PREFIX))
        val e = entries[key] ?: return null
        return "{\"type\":\"vector\",\"tiles\":[\"https://$HOST/$key/{z}/{x}/{y}.pbf\"],\"minzoom\":${e.header.minZoom},\"maxzoom\":${e.header.maxZoom}}"
    }

    private fun open(url: String): Entry? {
        val archive = PmtilesReader.Archive.http(client, url)
        val h = archive.header ?: return null
        return Entry(url, archive, h)
    }

    /** Opens the archive at [url] if it is not open yet, and returns its tile key (tests). */
    internal fun register(url: String): String? {
        val key = keyOf(url)
        if (entries[key] == null) entries[key] = open(url) ?: return null
        return key
    }

    /** The tile at z/x/y of the archive under [key], read again from a fresh open when the file
     *  behind its address was replaced. Null when the archive does not hold it. */
    internal fun tileBytes(key: String, z: Int, x: Int, y: Int): ByteArray? {
        val e = entries[key] ?: return null
        e.archive.tile(z, x, y)?.let { return it }
        if (!e.archive.replaced) return null
        return reopen(key, e)?.archive?.tile(z, x, y)
    }

    /** Opens [stale]'s address again, once for all the tile reads that found it replaced. When the
     *  new header cannot be read the stale entry stays, and the next tile tries again. */
    private fun reopen(key: String, stale: Entry): Entry? = synchronized(reopenLock) {
        entries[key]?.takeIf { it !== stale }?.let { return it }
        val fresh = open(stale.url) ?: return null
        entries[key] = fresh
        fresh
    }

    private fun keyOf(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    private val TILE = Regex("""^/([0-9a-f]+)/(\d+)/(\d+)/(\d+)\.pbf$""")

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != HOST) return chain.proceed(req)
        val m = TILE.find(req.url.encodedPath)
        // The HTTP reader has no shared state between reads, so tiles are read as MapLibre asks
        // for them, in parallel.
        val bytes = m?.let { tileBytes(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3].toInt(), it.groupValues[4].toInt()) }
        val type = "application/x-protobuf"
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(if (bytes == null) 204 else 200)
            .message(if (bytes == null) "No Content" else "OK")
            .header("Content-Type", type)
            .body((bytes ?: ByteArray(0)).toResponseBody(type.toMediaType()))
            .build()
    }
}
