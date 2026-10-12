package app.vela.ui.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.mutableStateMapOf
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
 * At launch this fetches the LIVE style JSON of the chosen look - so tile paths keep
 * auto-following OpenFreeMap's snapshot rotation, the exact property the old
 * bundled-asset attempt lost - patches `glyphs`, and caches the result; the map
 * loads the cache as `file://` on later launches (no restyle flash). Every
 * OpenFreeMap look ([MapStyle]) uses the same Noto stacks, so the same patch fits
 * each, and each look keeps its own cache file. Guards, in order of what actually
 * goes wrong:
 *  - with no validated network the refresh does nothing: the cache is what an
 *    installed offline basemap loads, and the probe below could only fail;
 *  - the font host is PROBED (range 0-255) before the patched style is used or
 *    kept; an unreachable host EVICTS the cache, because a style whose glyph URLs
 *    fail renders a map with no labels at all - falling back to the plain style
 *    URL (Noto) is invisible by comparison;
 *  - a style fetch failure keeps the last-good cache, but only for
 *    [STALE_EVICT_MS]: a cached style pins a tile snapshot, and OpenFreeMap
 *    eventually retires old snapshots (the blank-basemap failure the bundled
 *    asset hit) - past the window the plain URL wins again;
 *  - only OpenFreeMap looks are ever patched; MapTiler and offline REGION
 *    DEFINITIONS (OfflineMaps) keep the plain URL on purpose - a definition
 *    outlives any file path, and its download caching Noto glyph URLs is the
 *    offline fallback. Offline label coverage for the patched style rides the
 *    ambient cache (browsing an area warms the ranges it uses).
 */
object MapFonts {
    /** Style key to the `file://` path of its patched cache, for the looks that have one. */
    private val patched = mutableStateMapOf<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val STALE_EVICT_MS = 7L * 24 * 3600 * 1000

    /** The style the map should load: the patched copy of an OpenFreeMap look when ready, else
     *  [base] untouched. */
    fun effective(base: String): String {
        val look = MapStyle.byUri(base) ?: return base
        return patched[look.key] ?: base
    }

    /** The style JSON an installed offline basemap loads, and whether the map paints it as the
     *  Vela look. */
    class OfflineStyle(val json: String, val painted: Boolean)

    /** [cached] is [look]'s patched cache. A look with none loads [asset], the bundled Liberty,
     *  painted as the Vela look: stock Liberty under another look's colors is neither. */
    fun offlineStyle(look: MapStyle, cached: String?, asset: () -> String?): OfflineStyle? {
        if (!cached.isNullOrBlank()) return OfflineStyle(cached, look.painted)
        val json = asset()?.takeIf { it.isNotBlank() } ?: return null
        return OfflineStyle(json, painted = true)
    }

    fun init(context: Context) {
        for (look in MapStyle.entries) {
            val f = cacheFile(context, look)
            if (f.isFile && f.length() > 0) patched[look.key] = "file://${f.absolutePath}"
        }
        ensure(context, app.vela.ui.MapLook.style.value)
    }

    /** Refreshes [look]'s patched style in the background: at launch for the chosen look, and
     *  when the user picks another one. */
    fun ensure(context: Context, look: MapStyle) {
        scope.launch { runCatching { refresh(context, look) } }
    }

    /** Liberty keeps the file name it has had since the patch began, so an update finds it. */
    private fun cacheFile(context: Context, look: MapStyle) =
        File(File(context.filesDir, "style").apply { mkdirs() }, if (look == MapStyle.LIBERTY) "liberty-roboto.json" else "${look.key}-roboto.json")

    private fun evict(look: MapStyle, f: File) {
        patched.remove(look.key)
        f.delete()
    }

    /** What Android says about the default network: one that has reached the internet. A missing
     *  service counts as online so the probe decides, as it did before this check. */
    private fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    private fun refresh(context: Context, look: MapStyle) {
        // No network: the probe can only fail, and the cache it would evict is what the offline
        // basemap loads. The eviction below is for a live network with a dead font host.
        if (!online(context)) return
        val f = cacheFile(context, look)
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val fontsBase = app.vela.core.config.VelaLinks.mapFonts().trimEnd('/')

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
            evict(look, f)
            return
        }

        val live = runCatching {
            http.newCall(Request.Builder().url(look.uri).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
        if (live == null) {
            // Keep the last-good patch while it's fresh; a week offline and the plain
            // live URL (which can't pin a dead tile snapshot) takes over.
            if (f.isFile && System.currentTimeMillis() - f.lastModified() > STALE_EVICT_MS) evict(look, f)
            return
        }

        val patchedJson = runCatching {
            JSONObject(live).put("glyphs", "$fontsBase/{fontstack}/{range}.pbf").toString()
        }.getOrNull() ?: return
        if (f.isFile && f.readText() == patchedJson) {
            f.setLastModified(System.currentTimeMillis())
            return
        }
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(patchedJson)
        if (tmp.renameTo(f)) patched[look.key] = "file://${f.absolutePath}"
    }
}
