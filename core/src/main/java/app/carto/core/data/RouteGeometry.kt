package app.carto.core.data

import app.carto.core.CartoConfig
import app.carto.core.data.google.PolylineCodec
import app.carto.core.model.LatLng
import app.carto.core.model.Route
import app.carto.core.model.distanceTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The drawn route line.
 *
 * Calibration (2026-06-15) established that Google's `/maps/preview/directions`
 * response carries NO decodable overview polyline — the web client renders the
 * line from vector tiles. So Carto sources the *geometry* from an open router
 * (OSRM, whose geometry is a standard E5 polyline [PolylineCodec] decodes
 * directly) while Google still provides the ETA, live traffic and maneuvers.
 * Same split the offline build will use, just with Valhalla as the engine.
 *
 * NOTE: [OSRM_BASE] is the public demo server — rate-limited, explicitly not for
 * production. Point it at a self-hosted OSRM/Valhalla before any real release.
 */
object RouteGeometry {
    private const val OSRM_BASE = "https://router.project-osrm.org"
    private val json = Json { ignoreUnknownKeys = true }

    /** Road-following polyline for origin→dest, or null on any failure. */
    fun fetch(http: OkHttpClient, origin: LatLng, dest: LatLng): List<LatLng>? = try {
        val url = "$OSRM_BASE/route/v1/driving/" +
            "${origin.lng},${origin.lat};${dest.lng},${dest.lat}" +
            "?overview=full&geometries=polyline"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", CartoConfig.USER_AGENT)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val encoded = json.parseToJsonElement(resp.body?.string().orEmpty())
                .jsonObject["routes"]?.jsonArray?.getOrNull(0)
                ?.jsonObject?.get("geometry")?.jsonPrimitive?.contentOrNull
                ?: return null
            PolylineCodec.decode(encoded).takeIf { it.size >= 2 }
        }
    } catch (e: Exception) {
        null
    }

    /** Copy of [route] drawn along [polyline], maneuvers repositioned along it
     *  by cumulative step distance. Google's distances/durations are kept. */
    fun reposition(route: Route, polyline: List<LatLng>): Route {
        if (polyline.size < 2) return route
        val total = route.maneuvers.sumOf { it.distanceMeters }.coerceAtLeast(1.0)
        var cum = 0.0
        val placed = route.maneuvers.map { m ->
            cum += m.distanceMeters
            m.copy(location = pointAlong(polyline, (cum / total).coerceIn(0.0, 1.0)))
        }
        val legs = route.legs.mapIndexed { i, leg -> if (i == 0) leg.copy(maneuvers = placed) else leg }
        return route.copy(polyline = polyline, legs = legs)
    }

    private fun pointAlong(poly: List<LatLng>, frac: Double): LatLng {
        val seg = DoubleArray(poly.size - 1) { poly[it].distanceTo(poly[it + 1]) }
        val target = seg.sum() * frac
        var acc = 0.0
        for (i in seg.indices) {
            if (acc + seg[i] >= target) {
                val f = if (seg[i] == 0.0) 0.0 else (target - acc) / seg[i]
                val a = poly[i]
                val b = poly[i + 1]
                return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
            }
            acc += seg[i]
        }
        return poly.last()
    }
}
