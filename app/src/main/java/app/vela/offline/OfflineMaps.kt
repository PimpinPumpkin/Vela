package app.vela.offline

import android.content.Context
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import app.vela.core.model.distanceTo
import org.json.JSONObject
import kotlin.math.max

/**
 * Offline basemap regions, via MapLibre Native's built-in offline store (the same
 * SDK we already render with). Downloading a region pulls the keyless basemap's
 * tiles/glyphs/sprites for a bounding box + zoom range into a local SQLite store;
 * the map then renders that area with no network. On-ethos: open tiles, no Google,
 * no backend. Routing + POI search still need network (offline routing/search are
 * a heavier follow-on).
 */
object OfflineMaps {

    /** Generous tile cap so a screen-sized area downloads; very large areas still
     *  hit it and report back so the user can zoom in. */
    private const val TILE_LIMIT = 50_000L

    /** How a tile download ended - the caller turns these into localized user text. */
    enum class DoneReason { SAVED, FAILED, TOO_LARGE }

    /** Kick off a tile download for [bounds]. Progress arrives as 0..100 through [onProgress]
     *  (state-driven card material, NOT flash-status spam - the old string callback re-flashed the
     *  heads-up banner on every tick, which stacked a second banner over the progress card, user
     *  2026-07-23), the created [OfflineRegion] is handed out through [onCreated] so the caller can
     *  CANCEL (STATE_INACTIVE + delete), and [onDone] fires exactly once with the outcome. */
    fun download(
        context: Context,
        styleUrl: String,
        bounds: LatLngBounds,
        minZoom: Double,
        maxZoom: Double,
        name: String,
        onCreated: (OfflineRegion) -> Unit = {},
        onProgress: (Int) -> Unit = {},
        onDone: (DoneReason) -> Unit,
    ) {
        val manager = OfflineManager.getInstance(context)
        manager.setOfflineMapboxTileCountLimit(TILE_LIMIT)
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            minZoom,
            maxZoom,
            context.resources.displayMetrics.density,
        )
        val metadata = JSONObject().put(KEY_NAME, name).toString().toByteArray()
        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    onCreated(region)
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        // onStatusChanged keeps firing around completion; onDone must fire ONCE.
                        var done = false
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            if (done) return
                            if (status.isComplete) {
                                done = true
                                onDone(DoneReason.SAVED)
                            } else {
                                onProgress(
                                    (100.0 * status.completedResourceCount /
                                        max(1L, status.requiredResourceCount)).toInt(),
                                )
                            }
                        }
                        override fun onError(error: OfflineRegionError) {
                            if (done) return
                            done = true
                            onDone(DoneReason.FAILED)
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            if (done) return
                            done = true
                            onDone(DoneReason.TOO_LARGE)
                        }
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    onDone(DoneReason.FAILED)
                }
            },
        )
    }

    /** The areas the user saved; the map along the route ([saveRouteStrip]) is not one. */
    fun list(context: Context, onResult: (List<OfflineRegion>) -> Unit) =
        all(context) { regions -> onResult(regions.filterNot(::isRouteStrip)) }

    private fun all(context: Context, onResult: (List<OfflineRegion>) -> Unit) {
        OfflineManager.getInstance(context).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) = onResult(regions?.toList().orEmpty())
                override fun onError(error: String) = onResult(emptyList())
            },
        )
    }

    private fun isRouteStrip(region: OfflineRegion): Boolean =
        runCatching { JSONObject(String(region.metadata)).optBoolean(KEY_ROUTE_STRIP) }.getOrDefault(false)

    /** "Save the map along the route" (Settings > Offline maps), on by default. */
    val routeStrip = androidx.compose.runtime.mutableStateOf(true)

    fun init(context: Context) {
        routeStrip.value = prefs(context).getBoolean(KEY_ROUTE_STRIP_ON, true)
    }

    fun setRouteStrip(context: Context, on: Boolean) {
        routeStrip.value = on
        prefs(context).edit().putBoolean(KEY_ROUTE_STRIP_ON, on).apply()
        if (!on) deleteRouteStrip(context) {}
    }

    /**
     * The map along a drive's route, saved while there is signal so the streets around it still
     * draw without one: Google keeps the map along its route the same way. The first
     * [STRIP_MAX_M] of [line], at zooms [STRIP_MIN_Z] to [STRIP_MAX_Z] (the basemap's tiles stop
     * at 14; the drive camera draws closer from them). One strip at a time: a new route's strip
     * replaces the last. Tiles already in the cache are not fetched again. Not with Data Saver on.
     */
    fun saveRouteStrip(context: Context, styleUrl: String, line: List<app.vela.core.model.LatLng>) {
        if (!routeStrip.value || styleUrl.isBlank() || line.size < 2) return
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm?.isActiveNetworkMetered == true &&
            cm.restrictBackgroundStatus == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        ) return
        val points = mutableListOf<org.maplibre.geojson.Point>()
        var along = 0.0
        var last: app.vela.core.model.LatLng? = null
        for (p in line) {
            val prev = last
            if (prev != null) {
                val d = prev.distanceTo(p)
                if (d < STRIP_STEP_M && p !== line.last()) continue
                along += d
            }
            points += org.maplibre.geojson.Point.fromLngLat(p.lng, p.lat)
            last = p
            if (along >= STRIP_MAX_M) break
        }
        if (points.size < 2) return
        val definition = org.maplibre.android.offline.OfflineGeometryRegionDefinition(
            styleUrl,
            org.maplibre.geojson.LineString.fromLngLats(points),
            STRIP_MIN_Z,
            STRIP_MAX_Z,
            context.resources.displayMetrics.density,
        )
        val metadata = JSONObject().put(KEY_NAME, "Route").put(KEY_ROUTE_STRIP, true).toString().toByteArray()
        // The new strip first, the old ones after it is done: tiles the two routes share stay
        // referenced throughout and are not fetched again.
        OfflineManager.getInstance(context).createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        var done = false
                        fun finish(line: String) {
                            if (done) return
                            done = true
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            android.util.Log.i("VelaOffline", line)
                            deleteRouteStrip(context, keep = region.id) {}
                        }
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            if (status.isComplete) {
                                finish("route strip saved: ${status.completedTileCount} tiles, ${status.completedResourceSize / 1024} KB")
                            }
                        }
                        override fun onError(error: OfflineRegionError) {
                            // A tile that fails is retried by MapLibre; only a stop ends the strip.
                            android.util.Log.i("VelaOffline", "route strip: ${error.reason}")
                        }
                        override fun mapboxTileCountLimitExceeded(limit: Long) = finish("route strip: over $limit tiles")
                    })
                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }
                override fun onError(error: String) {
                    android.util.Log.i("VelaOffline", "route strip not created: $error")
                }
            },
        )
        android.util.Log.i("VelaOffline", "route strip: ${points.size} points, ${(along / 1000).toInt()} km")
    }

    /** Remove the map along the route, all but region [keep], then [onDone]. */
    fun deleteRouteStrip(context: Context, keep: Long? = null, onDone: () -> Unit) {
        all(context) { regions ->
            val strips = regions.filter { isRouteStrip(it) && it.id != keep }
            var left = strips.size
            if (left == 0) { onDone(); return@all }
            strips.forEach { r ->
                r.setDownloadState(OfflineRegion.STATE_INACTIVE)
                delete(r) { if (--left == 0) onDone() }
            }
        }
    }

    fun delete(region: OfflineRegion, onDone: () -> Unit) {
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = onDone()
            override fun onError(error: String) = onDone()
        })
    }

    /** Give the space back. MapLibre keeps saved areas and the browsing cache in ONE SQLite file,
     *  and deleting a region removes its rows, not the bytes: the file keeps its size until it is
     *  packed (VACUUM). A phone that had saved and deleted a few big areas showed 5 GB of "map
     *  data" with nothing left to delete (issue #601). Called after every area delete and by the
     *  delete-everything path; always completes (an error is reported as done). */
    fun packDatabase(context: Context, onDone: () -> Unit = {}) {
        runCatching {
            OfflineManager.getInstance(context).packDatabase(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = onDone()
                override fun onError(message: String) = onDone()
            })
        }.onFailure { onDone() }
    }

    /** Delete every saved area and the map along the route, then pack the database. */
    fun deleteAll(context: Context, onDone: () -> Unit) {
        all(context) { regions ->
            var left = regions.size
            if (left == 0) { packDatabase(context, onDone); return@all }
            regions.forEach { r -> delete(r) { if (--left == 0) packDatabase(context, onDone) } }
        }
    }

    fun nameOf(region: OfflineRegion): String =
        runCatching { JSONObject(String(region.metadata)).optString(KEY_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "Saved area"

    /** The saved area's tile bounds, so callers can re-fetch its offline data (POIs/addresses) for the
     *  same box. Null if the region isn't a tile-pyramid definition. */
    fun boundsOf(region: OfflineRegion): LatLngBounds? =
        (region.definition as? org.maplibre.android.offline.OfflineTilePyramidRegionDefinition)?.bounds

    private fun prefs(c: Context) = c.getSharedPreferences("vela_settings", Context.MODE_PRIVATE)

    private const val KEY_NAME = "name"
    private const val KEY_ROUTE_STRIP = "routeStrip"
    private const val KEY_ROUTE_STRIP_ON = "route_strip"
    const val STRIP_MAX_M = 150_000.0
    const val STRIP_STEP_M = 50.0
    const val STRIP_MIN_Z = 11.0
    const val STRIP_MAX_Z = 14.0
}
