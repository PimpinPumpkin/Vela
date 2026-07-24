package app.vela.offline

import android.content.Context
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
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

    fun list(context: Context, onResult: (List<OfflineRegion>) -> Unit) {
        OfflineManager.getInstance(context).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) = onResult(regions?.toList().orEmpty())
                override fun onError(error: String) = onResult(emptyList())
            },
        )
    }

    fun delete(region: OfflineRegion, onDone: () -> Unit) {
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = onDone()
            override fun onError(error: String) = onDone()
        })
    }

    fun nameOf(region: OfflineRegion): String =
        runCatching { JSONObject(String(region.metadata)).optString(KEY_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "Saved area"

    /** The saved area's tile bounds, so callers can re-fetch its offline data (POIs/addresses) for the
     *  same box. Null if the region isn't a tile-pyramid definition. */
    fun boundsOf(region: OfflineRegion): LatLngBounds? =
        (region.definition as? org.maplibre.android.offline.OfflineTilePyramidRegionDefinition)?.bounds

    private const val KEY_NAME = "name"
}
