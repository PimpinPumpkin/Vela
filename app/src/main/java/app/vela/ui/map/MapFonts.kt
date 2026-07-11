package app.vela.ui.map

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import app.vela.BuildConfig
import app.vela.core.data.tiles.MapStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Roboto on the basemap, keyless. OpenFreeMap's glyph server only carries Noto
 * (every Roboto stack 404s, probed 2026-07-11), so Vela hosts its own glyph set on
 * the repo's GitHub Pages: Roboto composited over OpenFreeMap's Noto per glyph -
 * Roboto wins Latin/Cyrillic/Greek, Noto keeps every other script, and the folder
 * names stay "Noto Sans Regular/Bold/Italic" so the ONLY style change is the
 * `glyphs` URL. (Recipe: scripts/build-map-fonts.sh -> the `map-fonts` release ->
 * fdroid-repo.yml unpacks it into the Pages site.)
 *
 * At launch this fetches the LIVE Liberty style JSON - so tile paths keep
 * auto-following OpenFreeMap's snapshot rotation, the exact property the old
 * bundled-asset attempt lost - patches `glyphs`, and caches the result; the map
 * loads the cache as `file://` on later launches (no restyle flash). Guards, in
 * order of what actually goes wrong:
 *  - the font host is PROBED (range 0-255) before the patched style is used or
 *    kept; an unreachable host EVICTS the cache, because a style whose glyph URLs
 *    fail renders a map with no labels at all - falling back to the plain Liberty
 *    URL (Noto) is invisible by comparison;
 *  - a style fetch failure keeps the last-good cache, but only for
 *    [STALE_EVICT_MS]: a cached style pins a tile snapshot, and OpenFreeMap
 *    eventually retires old snapshots (the blank-basemap failure the bundled
 *    asset hit) - past the window the plain URL wins again;
 *  - only [MapStyle.LIBERTY] is ever patched; MapTiler and offline REGION
 *    DEFINITIONS (OfflineMaps) keep the plain URL on purpose - a definition
 *    outlives any file path, and its download caching Noto glyph URLs is the
 *    offline fallback. Offline label coverage for the patched style rides the
 *    ambient cache (browsing an area warms the ranges it uses).
 */
object MapFonts {
    private val patched = mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val STALE_EVICT_MS = 7L * 24 * 3600 * 1000
    private const val CACHE_NAME = "liberty-roboto.json"

    /** The style the map should load: the patched Liberty when ready, else [base] untouched. */
    fun effective(base: String): String =
        if (base == MapStyle.LIBERTY.uri) patched.value ?: base else base

    fun init(context: Context) {
        val f = cacheFile(context)
        if (f.isFile && f.length() > 0) patched.value = "file://${f.absolutePath}"
        scope.launch { runCatching { refresh(context) } }
    }

    private fun cacheFile(context: Context) =
        File(File(context.filesDir, "style").apply { mkdirs() }, CACHE_NAME)

    private fun evict(f: File) {
        patched.value = null
        f.delete()
    }

    private fun refresh(context: Context) {
        val f = cacheFile(context)
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val fontsBase = BuildConfig.MAP_FONTS_URL.trimEnd('/')

        // A host that can't serve glyphs must never be in the style the map renders.
        val probeUrl = "$fontsBase/Noto%20Sans%20Regular/0-255.pbf"
        val fontsOk = runCatching {
            http.newCall(Request.Builder().url(probeUrl).build()).execute().use { r ->
                // Manual head read: InputStream.readNBytes is API 33+, minSdk is 26.
                val head = ByteArray(16)
                var n = 0
                r.body?.byteStream()?.let { s ->
                    while (n < head.size) {
                        val read = s.read(head, n, head.size - n)
                        if (read < 0) break
                        n += read
                    }
                }
                r.isSuccessful && n == head.size && head[0] == 0x0a.toByte()
            }
        }.getOrDefault(false)
        if (!fontsOk) {
            evict(f)
            return
        }

        val live = runCatching {
            http.newCall(Request.Builder().url(MapStyle.LIBERTY.uri).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
        if (live == null) {
            // Keep the last-good patch while it's fresh; a week offline and the plain
            // live URL (which can't pin a dead tile snapshot) takes over.
            if (f.isFile && System.currentTimeMillis() - f.lastModified() > STALE_EVICT_MS) evict(f)
            return
        }

        val patchedJson = runCatching {
            JSONObject(live).put("glyphs", "$fontsBase/{fontstack}/{range}.pbf").toString()
        }.getOrNull() ?: return
        if (f.isFile && f.readText() == patchedJson) {
            f.setLastModified(System.currentTimeMillis())
            return
        }
        val tmp = File(f.parentFile, "$CACHE_NAME.tmp")
        tmp.writeText(patchedJson)
        if (tmp.renameTo(f)) patched.value = "file://${f.absolutePath}"
    }
}
