package app.vela.core.data

import app.vela.core.VelaConfig
import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
import app.vela.core.nav.RouteProjection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class WazeAlertCategory(val rawType: String, val iconId: String) {
    POLICE("POLICE", "vela-waze-police-badge"),
    ACCIDENT("ACCIDENT", "vela-waze-accident-badge"),
    HAZARD("HAZARD", "vela-waze-hazard-badge"),
    ROAD_CLOSED("ROAD_CLOSED", "vela-waze-closure-badge"),
    JAM("JAM", "vela-waze-jam-badge"),
    OTHER("OTHER", "vela-waze-hazard-badge");

    companion object {
        private val CAMERA_TOKENS = arrayOf("CAMERA", "SPEED_CAM")

        fun fromType(raw: String?): WazeAlertCategory {
            if (raw == null) return OTHER
            val u = raw.uppercase(Locale.US)
            return when {
                u.contains("POLICE") || CAMERA_TOKENS.any { u.contains(it) } -> POLICE
                u.contains("ACCIDENT") -> ACCIDENT
                u.contains("HAZARD") || u.contains("WEATHER") || u.contains("CONSTRUCTION") -> HAZARD
                u.contains("ROAD_CLOSED") || u.contains("CLOSURE") -> ROAD_CLOSED
                u.contains("JAM") || u.contains("TRAFFIC") -> JAM
                else -> OTHER
            }
        }

        /** True when [raw] type or subtype denotes a fixed or reported camera (not a police trap). */
        fun isCamera(type: String, subtype: String?): Boolean {
            val t = type.uppercase(Locale.US)
            val s = subtype?.uppercase(Locale.US) ?: ""
            return CAMERA_TOKENS.any { it in t || it in s }
        }
    }
}

/**
 * An active crowdsourced alert (police, accident, hazard, closure, jam) from Waze's live map GeoRSS feed.
 *
 * [subtype] indicates specific alert detail (e.g. "POLICE_VISIBLE", "ACCIDENT_MAJOR", "HAZARD_ON_ROAD_POTHOLE").
 * [nThumbsUp] counts driver confirmations. [reliability] / [confidence] are Waze community scores.
 */
@Serializable
data class WazePoliceAlert(
    val id: String,
    val loc: LatLng,
    val type: String = "POLICE",
    val subtype: String? = null,
    val street: String? = null,
    val city: String? = null,
    val reliability: Int = 0,
    val confidence: Int = 0,
    val nThumbsUp: Int = 0,
    val pubMillis: Long = 0L,
) {
    fun category(): WazeAlertCategory = WazeAlertCategory.fromType(type)
    fun isCamera(): Boolean = WazeAlertCategory.isCamera(type, subtype)
    fun isPothole(): Boolean {
        val s = subtype?.uppercase(Locale.US) ?: return false
        return "POT_HOLE" in s || "POTHOLE" in s
    }
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = WazePoliceAlerts.isExpired(this, nowMs)
}

typealias WazeAlert = WazePoliceAlert

@Serializable
private data class WazeGeoRssResponse(
    val alerts: List<WazeRawAlert> = emptyList(),
    val jams: List<WazeRawJam> = emptyList(),
)

@Serializable
private data class WazeRawJam(
    val id: String? = null,
    val uuid: String? = null,
    val street: String? = null,
    val city: String? = null,
    val line: List<WazePoint> = emptyList(),
    val level: Int = 0,
    val length: Int = 0,
    val speedKMH: Double = 0.0,
    val pubMillis: Long = 0L,
    val updateMillis: Long = 0L,
)

@Serializable
private data class WazeRawAlert(
    val id: String? = null,
    val uuid: String? = null,
    val type: String? = null,
    val subtype: String? = null,
    val street: String? = null,
    val city: String? = null,
    val location: WazePoint? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val reliability: Int = 0,
    val confidence: Int = 0,
    val nThumbsUp: Int = 0,
    val pubMillis: Long = 0L,
)

@Serializable
private data class WazePoint(
    val x: Double? = null, // Longitude
    val y: Double? = null, // Latitude
)

/**
 * Public live-map crowdsourced police reports from Waze (`/live-map/api/georss`).
 *
 * Anonymous, keyless GET query using viewport bounding box. Waze routes requests by continental
 * cluster ([envFor]: `na` for North America, `il` for Israel, `row` for Rest of World).
 * Waze caps responses at ~200 alerts per query, so callers gate requests to meaningful zoom levels
 * ([MIN_ZOOM] ≈ 11.0).
 */
object WazePoliceAlerts {
    const val MIN_ZOOM = 7.0
    private const val BASE_URL = "https://www.waze.com/live-map/api/georss"
    private const val BRIDGE_URL = "http://127.0.0.1:8765/georss"
    private const val REFERER = "https://www.waze.com/live-map/directions"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile
    private var wazeHttp: OkHttpClient? = null

    private val bridgeHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(800, TimeUnit.MILLISECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private fun client(base: OkHttpClient): OkHttpClient =
        wazeHttp ?: base.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
            .also { wazeHttp = it }

    /**
     * Attempts to query a local development/companion bridge (e.g. over `adb reverse tcp:8765 tcp:8765`).
     * Returns parsed alerts on success, or null if bridge is offline.
     */
    fun tryBridge(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): List<WazePoliceAlert>? {
        val midLat = (south + north) / 2.0
        val midLng = (west + east) / 2.0
        val env = envFor(midLat, midLng)
        val query = String.format(
            Locale.US,
            "top=%.5f&bottom=%.5f&left=%.5f&right=%.5f&env=%s&types=alerts,traffic",
            north, south, west, east, env,
        )
        val req = Request.Builder()
            .url("$BRIDGE_URL?$query")
            .header("Accept", "application/json, text/plain, */*")
            .build()
        return runCatching {
            bridgeHttp.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) parseAlerts(resp.body.string()) else null
            }
        }.getOrNull()
    }

    /**
     * Determines the Waze regional routing environment parameter:
     * - `il`: Israel
     * - `na`: North America (USA, Canada, Mexico)
     * - `row`: Rest of World (Europe, UK, Australia, South America, Asia, Africa)
     */
    fun envFor(lat: Double, lng: Double): String = when {
        lat in 29.0..33.5 && lng in 34.0..36.0 -> "il"
        lat in 14.0..75.0 && lng in -170.0..-50.0 -> "na"
        else -> "row"
    }

    /**
     * An alert is expired when its age exceeds its retention window:
     * - Police: 25 min (unconfirmed) / 40 min (confirmed)
     * - Accident: 40 min (unconfirmed) / 60 min (confirmed)
     * - Hazard / Weather: 45 min (unconfirmed) / 90 min (confirmed)
     * - Road Closed: 90 min (unconfirmed) / 180 min (confirmed)
     * - Jam: 20 min (unconfirmed) / 35 min (confirmed)
     */
    fun isExpired(alert: WazePoliceAlert, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (alert.pubMillis <= 0L) return false
        val age = nowMs - alert.pubMillis
        if (age < 0L) return false
        val isConfirmed = alert.nThumbsUp > 0
        val maxAgeMs = when (alert.category()) {
            WazeAlertCategory.POLICE -> if (isConfirmed) 40 * 60 * 1000L else 25 * 60 * 1000L
            WazeAlertCategory.ACCIDENT -> if (isConfirmed) 60 * 60 * 1000L else 40 * 60 * 1000L
            WazeAlertCategory.HAZARD -> if (isConfirmed) 90 * 60 * 1000L else 45 * 60 * 1000L
            WazeAlertCategory.ROAD_CLOSED -> if (isConfirmed) 180 * 60 * 1000L else 90 * 60 * 1000L
            WazeAlertCategory.JAM -> if (isConfirmed) 35 * 60 * 1000L else 20 * 60 * 1000L
            WazeAlertCategory.OTHER -> if (isConfirmed) 60 * 60 * 1000L else 30 * 60 * 1000L
        }
        return age > maxAgeMs
    }

    /**
     * Parse raw JSON response into a list of [WazePoliceAlert].
     * Can filter by [allowedCategories]; if null, accepts all categories.
     * Safely returns emptyList() if response is non-JSON or HTML error page.
     */
    fun parseAlerts(
        jsonText: String,
        allowedCategories: Set<WazeAlertCategory>? = null,
    ): List<WazePoliceAlert> {
        val trimmed = jsonText.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val parsed = runCatching { json.decodeFromString<WazeGeoRssResponse>(trimmed) }.getOrNull() ?: return emptyList()
        val now = System.currentTimeMillis()
        val alertList = parsed.alerts.mapNotNull { a ->
            val rawType = a.type ?: return@mapNotNull null
            val cat = WazeAlertCategory.fromType(rawType)
            if (allowedCategories != null && cat !in allowedCategories) return@mapNotNull null
            val lat = a.location?.y ?: a.latitude ?: return@mapNotNull null
            val lng = a.location?.x ?: a.longitude ?: return@mapNotNull null
            val alertId = a.id ?: a.uuid ?: String.format(Locale.US, "%.5f,%.5f", lat, lng)
            val pub = if (a.pubMillis > 0L) a.pubMillis else now
            val alert = WazePoliceAlert(
                id = alertId,
                loc = LatLng(lat, lng),
                type = rawType,
                subtype = a.subtype,
                street = a.street,
                city = a.city,
                reliability = a.reliability,
                confidence = a.confidence,
                nThumbsUp = a.nThumbsUp,
                pubMillis = pub,
            )
            if (alert.isExpired(now)) return@mapNotNull null
            alert
        }

        val jamList = if (allowedCategories == null || WazeAlertCategory.JAM in allowedCategories) {
            parsed.jams.mapNotNull { j ->
                val pt = j.line.getOrNull(j.line.size / 2) ?: j.line.firstOrNull() ?: return@mapNotNull null
                val lat = pt.y ?: return@mapNotNull null
                val lng = pt.x ?: return@mapNotNull null
                val jamId = j.uuid ?: j.id ?: String.format(Locale.US, "jam_%.5f,%.5f", lat, lng)
                val pub = when {
                    j.pubMillis > 0L -> j.pubMillis
                    j.updateMillis > 0L -> j.updateMillis
                    else -> now
                }
                val alert = WazePoliceAlert(
                    id = jamId,
                    loc = LatLng(lat, lng),
                    type = "JAM",
                    subtype = if (j.level > 0) "JAM_LEVEL_${j.level}" else null,
                    street = j.street,
                    city = j.city,
                    reliability = j.level,
                    confidence = j.level,
                    pubMillis = pub,
                )
                if (alert.isExpired(now)) return@mapNotNull null
                alert
            }
        } else emptyList()

        return alertList + jamList
    }

    /**
     * Fetches active police reports within a bounding box.
     * Returns null on network/HTTP failure (so callers do not cache the box), or a List of alerts.
     */
    fun fetchInBox(
        http: OkHttpClient,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): List<WazePoliceAlert>? {
        tryBridge(south, west, north, east)?.let { return it }

        val midLat = (south + north) / 2.0
        val midLng = (west + east) / 2.0
        val env = envFor(midLat, midLng)

        val url = String.format(
            Locale.US,
            "%s?top=%.5f&bottom=%.5f&left=%.5f&right=%.5f&env=%s&types=alerts,traffic",
            BASE_URL, north, south, west, east, env,
        )

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", VelaConfig.USER_AGENT)
            .header("Referer", REFERER)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        return runCatching {
            client(http).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    null
                } else {
                    val body = resp.body.string()
                    parseAlerts(body)
                }
            }
        }.getOrNull()
    }

    /**
     * Fetches police reports along a driven route corridor.
     * Segments long routes if needed so the ~200 alert cap per query isn't exceeded.
     */
    fun fetchAlongCorridor(
        http: OkHttpClient,
        polyline: List<LatLng>,
        radiusM: Double = 250.0,
    ): List<WazePoliceAlert>? {
        if (polyline.size < 2) return emptyList()

        val degPad = (radiusM / 111_000.0) * 1.5
        val minLat = polyline.minOf { it.lat } - degPad
        val maxLat = polyline.maxOf { it.lat } + degPad
        val minLng = polyline.minOf { it.lng } - degPad
        val maxLng = polyline.maxOf { it.lng } + degPad

        val latSpan = maxLat - minLat
        val lngSpan = maxLng - minLng

        val allAlerts = mutableListOf<WazePoliceAlert>()

        // For shorter routes (< ~50 km box), a single query suffices
        if (latSpan <= 0.5 && lngSpan <= 0.5) {
            val alerts = fetchInBox(http, minLat, minLng, maxLat, maxLng) ?: return null
            allAlerts.addAll(alerts)
        } else {
            // For longer routes, split into bounding boxes along polyline samples to avoid 200 alert cap
            val stepSize = (polyline.size / 4).coerceAtLeast(1)
            var idx = 0
            while (idx < polyline.size) {
                val nextIdx = (idx + stepSize).coerceAtMost(polyline.size - 1)
                val sub = polyline.subList(idx, nextIdx + 1)
                val s = sub.minOf { it.lat } - degPad
                val n = sub.maxOf { it.lat } + degPad
                val w = sub.minOf { it.lng } - degPad
                val e = sub.maxOf { it.lng } + degPad

                val alerts = fetchInBox(http, s, w, n, e)
                if (alerts != null) allAlerts.addAll(alerts)

                if (nextIdx >= polyline.size - 1) break
                idx = nextIdx
            }
        }

        val distinct = allAlerts.distinctBy { it.id }

        // Filter alerts within [radiusM] of any segment in polyline
        return distinct.filter { alert ->
            isNearPolyline(alert.loc, polyline, radiusM)
        }
    }

    private fun isNearPolyline(pt: LatLng, poly: List<LatLng>, maxDistanceM: Double): Boolean {
        if (poly.size < 2) return false
        val cum = RouteProjection.cumulative(poly)
        return RouteProjection.alongMeters(poly, cum, pt, maxDistanceM) != null
    }
}
